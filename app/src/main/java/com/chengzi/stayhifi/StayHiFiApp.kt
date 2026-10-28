package com.chengzi.stayhifi

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.chengzi.stayhifi.data.ProfileRepository
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.shell.LibsuRootBackend
import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.RootTransport
import com.chengzi.stayhifi.shell.ShizukuShellProvider
import com.chengzi.stayhifi.trigger.ListenerService
import com.chengzi.stayhifi.updater.UpdateCleanup
import com.chengzi.stayhifi.util.DeviceNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        )
        providerCoordinator = ProviderCoordinator(
            applicationScope = appScope,
            store = SettingsRepository(applicationContext),
            shizukuFactory = { ShizukuShellProvider(applicationContext) },
            rootStatusSource = ProviderCoordinator.RootStatusSource { rootTransport.grantedState() },
            rootTransport = rootTransport,
        )
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
}
