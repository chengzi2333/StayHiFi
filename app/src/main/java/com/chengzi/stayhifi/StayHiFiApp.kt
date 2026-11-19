package com.chengzi.stayhifi

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.chengzi.stayhifi.codec.CodecSnapshotPresenter
import com.chengzi.stayhifi.codec.CodecStateSnapshotJson
import com.chengzi.stayhifi.codec.PlaybackDiagnosticsController
import com.chengzi.stayhifi.codec.PlaybackDiagnosticsStore
import com.chengzi.stayhifi.codec.RealtimeDiagnosticDump
import com.chengzi.stayhifi.device.LocalHeadsetResult
import com.chengzi.stayhifi.device.LocalHeadsetSource
import com.chengzi.stayhifi.data.ApplyPreferenceStore
import com.chengzi.stayhifi.data.ProfileRepository
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.shell.LibsuRootBackend
import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.ProviderOwner
import com.chengzi.stayhifi.shell.ProviderSource
import com.chengzi.stayhifi.shell.RootLogContext
import com.chengzi.stayhifi.shell.RootTransport
import com.chengzi.stayhifi.shell.ShizukuShellProvider
import com.chengzi.stayhifi.trigger.HeadsetEvents
import com.chengzi.stayhifi.trigger.ListenerService
import com.chengzi.stayhifi.updater.UpdateCleanup
import com.chengzi.stayhifi.util.DeviceNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 应用入口：autoEnabled=true 时常驻拉起 [ListenerService]。
 * FGS 是 OriginOS 7 上 ACL_CONNECTED 的主触发载体（广播冷启动被系统拦截，真机实证），
 * 因此在进程创建的第一时间确保它活着。
 */
class StayHiFiApp : Application() {

    /** Task D7：进程级 Root transport（设计 §11.3）。构造零 shell 预热、零授权交互。 */
    lateinit var rootTransport: RootTransport
        private set

    /** Task D7：进程级特权通道协调器；构造即在注入 scope 异步首读持久选择，不阻塞主线程。 */
    lateinit var providerCoordinator: ProviderCoordinator
        private set

    lateinit var playbackDiagnostics: PlaybackDiagnosticsController
        private set

    lateinit var applyPreferences: ApplyPreferenceStore
        private set

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /** 上一次观察到的「正在放音乐」状态：用来识别播放↔暂停的跳变。 */
    @Volatile
    private var lastMusicActive = false

    /**
     * v1.2.5 功耗治理：App 是否在前台。播放诊断的 dumpsys 采样只在为 true 时进行，
     * 后台（进程被 FGS 保活）不再因别的 App 播放音频而跑媒体 dump。
     */
    @Volatile
    var isAppForeground: Boolean = false
        private set
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // Task C4：机型目录只注入 applicationContext（主线程、无 IO）；
        // 有界 GZIP/JSON 预载挪到 IO 协程单飞执行，首屏不解析全表、不阻塞启动。
        DeviceNames.initialize(this)
        CoroutineScope(Dispatchers.IO).launch {
            DeviceNames.preload()
        }
        // Task 30：档案 DataStore 预热——冷文件 IO + 全量 JSON 解码挪到启动期 IO 线程。
        // 不预热时首次切设备页才冷初始化，全砸在切换帧上（实测单帧 150ms）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ProfileRepository(applicationContext).all.first() }
        }
        // Task 41：启动兜底清理自更新 APK 残包（升级成功走 MY_PACKAGE_REPLACED
        // 更快，这里是下载后取消安装/安装失败/进程被杀等残留场景的保险）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { UpdateCleanup.deleteStaleApks(applicationContext) }
        }
        // Task D7：仅创建进程级 RootTransport/ProviderCoordinator——构造即异步首读持久
        // 选择（collect 在注入 scope），不新增 runBlocking、不预热 shell/RootService（§11.3/§11.4.1）。
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        rootTransport = RootTransport(
            applicationScope = appScope,
            backend = LibsuRootBackend(applicationContext),
            authDispatcher = Dispatchers.IO,
            mainDispatcher = Dispatchers.Main,
            // Root 日志的 generation/selected/active 取 Coordinator 当前值（§11.6）；
            // lambda 在日志落笔时求值，Coordinator 随后即完成赋值，无初始化环。
            logContext = {
                if (::providerCoordinator.isInitialized) {
                    RootLogContext(
                        generation = providerCoordinator.currentGeneration(),
                        selected = providerCoordinator.selectedProvider.value,
                        active = providerCoordinator.activeProvider.value,
                    )
                } else {
                    RootLogContext.EMPTY
                }
            },
        )
        providerCoordinator = ProviderCoordinator(
            applicationScope = appScope,
            store = SettingsRepository(applicationContext),
            shizukuFactory = { ShizukuShellProvider(applicationContext) },
            rootStatusSource = ProviderCoordinator.RootStatusSource { rootTransport.grantedState() },
            rootTransport = rootTransport,
        )
        if (Application.getProcessName() == packageName) {
            applyPreferences = ApplyPreferenceStore.create(this)
            initializePlaybackDiagnostics(appScope)
        }
        registerForegroundTracking()
        // 对抗审查 C2：权限被撤（隐私恐慌后常见）时 startForeground 会抛 SecurityException
        // 连带进程崩溃死循环。拉起前先查 BLUETOOTH_CONNECT，runBlocking 整体兜底 try/catch。
        try {
            val auto = runBlocking { SettingsRepository(applicationContext).isAutoEnabled() }
            if (!auto) return
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                LogStore.log("应用启动：autoEnabled=true 但 BLUETOOTH_CONNECT 未授予，不拉起 ListenerService")
                return
            }
            LogStore.log("应用启动：autoEnabled=true，拉起 ListenerService")
            ListenerService.start(this)
        } catch (t: Throwable) {
            LogStore.log("应用启动拉起 ListenerService 失败: $t")
        }
    }

    private fun initializePlaybackDiagnostics(scope: CoroutineScope) {
        val audioManager = getSystemService(AudioManager::class.java)
        val localHeadsets = LocalHeadsetSource(this)
        playbackDiagnostics = PlaybackDiagnosticsController(
            scope = scope,
            store = PlaybackDiagnosticsStore(),
            resolveConnection = {
                val local = withContext(Dispatchers.IO) { localHeadsets.current() }
                if (local is LocalHeadsetResult.Connected) {
                    PlaybackDiagnosticsController.Connection(
                        mac = local.headset.mac,
                        providerGeneration = providerCoordinator.currentGeneration(),
                    )
                } else {
                    null
                }
            },
            openCapture = { connection -> openPlaybackCapture(connection) },
            canCapture = { isAppForeground },
        )
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configurations: List<AudioPlaybackConfiguration>) {
                val active = audioManager.isMusicActive
                val transitioned = active != lastMusicActive
                lastMusicActive = active
                if (active) {
                    playbackDiagnostics.onPlaybackSignal()
                } else {
                    playbackDiagnostics.onPlaybackStopped()
                }
                // 播放↔暂停跳变时立即让首页刷新一次：首页轮询已改为「播放 3s / 暂停 15s」，
                // 靠轮询感知切换最坏要等 15s，这里用现有事件总线把它变成即时。
                // 只在 App 前台时 bump：后台刷新会白跑一次特权读取。
                if (transitioned && isAppForeground) {
                    HeadsetEvents.bump()
                }
            }
        }
        playbackCallback = callback
        audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
    }

    /**
     * 前台/后台跟踪：以「started Activity 数量」判定，避免多 Activity 场景误判。
     * 回到前台时若音乐仍在播放且上一次采样被跳过，这里补一个信号把窗口续上。
     */
    private fun registerForegroundTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                val wasBackground = !isAppForeground
                isAppForeground = true
                if (wasBackground && ::playbackDiagnostics.isInitialized &&
                    getSystemService(AudioManager::class.java).isMusicActive
                ) {
                    playbackDiagnostics.onPlaybackSignal()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) isAppForeground = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private suspend fun openPlaybackCapture(
        connection: PlaybackDiagnosticsController.Connection,
    ): PlaybackDiagnosticsController.Capture? {
        val probe = providerCoordinator.probeSelected(ProviderSource.DIAGNOSTIC)
        if (!probe.isRunning || !probe.hasPermission) return null
        val lease = providerCoordinator.acquire(
            ProviderOwner("playback-diagnostics"),
            ProviderSource.DIAGNOSTIC,
        )
        try {
            val initial = lease.withService { svc ->
                withContext(Dispatchers.IO) {
                    readPlaybackCaptureState(svc.readConfig(connection.mac), connection.mac, lease.generation)
                }
            }
            return object : PlaybackDiagnosticsController.Capture {
                override var signature = initial.signature

                override suspend fun sample(index: Int): RealtimeDiagnosticDump = lease.withService { svc ->
                    withContext(Dispatchers.IO) {
                        val latest = readPlaybackCaptureState(svc.readConfig(connection.mac), connection.mac, lease.generation)
                        signature = latest.signature
                        RealtimeDiagnosticDump.parse(svc.dumpRealtimeDiagnostics(latest.codecName))
                    }
                }

                override fun close() = lease.close()
            }
        } catch (t: Throwable) {
            lease.close()
            throw t
        }
    }

    private fun readPlaybackCaptureState(raw: String, mac: String, generation: Long): PlaybackCaptureState {
        val presentation = CodecSnapshotPresenter.present(CodecStateSnapshotJson.parse(raw))
            ?: throw IllegalStateException("readConfig returned no current codec")
        return PlaybackCaptureState(
            signature = PlaybackDiagnosticsController.CaptureSignature(
                mac = mac,
                current = presentation.current,
                providerGeneration = generation,
            ),
            codecName = presentation.identity.displayName,
        )
    }

    private data class PlaybackCaptureState(
        val signature: PlaybackDiagnosticsController.CaptureSignature,
        val codecName: String,
    )
}
