package com.chengzi.stayhifi.trigger

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.codec.CodecIdentityDiagnosticGate
import com.chengzi.stayhifi.codec.CodecIdentityIssue
import com.chengzi.stayhifi.codec.CodecPresentation
import com.chengzi.stayhifi.codec.CodecSnapshotPresenter
import com.chengzi.stayhifi.codec.CodecStateSnapshotJson
import com.chengzi.stayhifi.codec.Masks
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.notify.Notifier
import com.chengzi.stayhifi.shell.ShizukuShellProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * FGS 主触发载体（2026-10-21 设计裁决：OriginOS 7 禁广播冷启动，
 * manifest 广播接收器永远唤不醒 App，本服务成为 ACL_CONNECTED 的唯一可靠触发路径）。
 *
 * - 生命周期：[SettingsRepository.autoEnabled] == true 时由 Application.onCreate /
 *   开关切换 / BootReceiver 三处拉起；开关变 false 时自行 stopSelf。
 * - 运行时注册 ACL_CONNECTED / ACL_DISCONNECTED（exported=false 仅本进程），
 *   连接事件走 [ApplyWorker]（与广播兜底共用同一入口）。
 *   Task 31：断开检测双信号源——OriginOS 不投递 ACL_DISCONNECTED，另注册
 *   A2DP ACTION_CONNECTION_STATE_CHANGED，DISCONNECTED 时走同一刷新路径。
 * - 常驻通知 channel_listener（IMPORTANCE_LOW 静默）：标题「StayHiFi 运行中」。
 *   notifyPersistent 关 = 静态正文「音质满格运行中」，不轮询；
 *   notifyPersistent 开 = 事件驱动（连接/断开/写入后）+ 30s 轮询刷新，
 *   已连接显示 "Enco X3 · LHDC·192kHz·24bit"，未连接或读不到参数时「等待耳机连接」。
 *   Task 31 浸泡实测裁决：后台断开判定以「本机 A2DP API + 30s 轮询」为主路径
 *   （不依赖特权载体，不受系统冻结 codec 进程影响）；ACL/A2DP 断开广播为加速信号
 *   （OriginOS 对后台应用延迟投递，到达即刷，不到达轮询兜底）。
 *   Task 32：notifyPersistent 关 = 静态一次性通知（不轮询不重 post，用户划除即消失
 *   至进程重启）；开 = 事件驱动 + 30s 轮询参数行。
 */
class ListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsRepository
    private lateinit var notifier: Notifier
    private lateinit var provider: ShizukuShellProvider

    @Volatile
    private var notifyPersistent = false

    private var pollJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(intent)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> scope.launch {
                    LogStore.log("ACL_DISCONNECTED，刷新常驻通知")
                    HeadsetEvents.bump()
                    refreshNotification()
                }
                // Task 31：OriginOS 未投递 ACL_DISCONNECTED（真机广播史取证），
                // A2DP profile 的 CONNECTION_STATE_CHANGED 是系统蓝牙进程发出的独立信号，
                // 收到 DISCONNECTED 走同一刷新路径，日志区分信号源。
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        scope.launch {
                            LogStore.log("A2DP_CONNECTION_STATE_CHANGED=DISCONNECTED，刷新常驻通知")
                            HeadsetEvents.bump()
                            refreshNotification()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settings = SettingsRepository(this)
        notifier = Notifier(this)
        provider = ShizukuShellProvider(this)

        // 对抗审查 C2：connectedDevice 类型 FGS 要求 BLUETOOTH_CONNECT，权限缺失时
        // startForeground 抛 SecurityException——无 try/catch 会连带进程崩溃死循环。
        // 捕获后记录原因并 stopSelf，不再注册 receiver，服务干净退出。
        try {
            ServiceCompat.startForeground(
                this, ID_LISTENER,
                // 首帧即「运行中」：等 notifyPersistent collect 才刷新期间的瞬态不该闪「等待耳机连接」。
                notifier.persistentNotification(TEXT_RUNNING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (se: SecurityException) {
            LogStore.log("ListenerService startForeground 失败（BLUETOOTH_CONNECT 等权限缺失）: $se")
            isRunning = false
            stopSelf()
            return
        }

        // 开关被关掉 → 立即退出
        scope.launch {
            settings.autoEnabled.collect { enabled ->
                if (!enabled) {
                    LogStore.log("autoEnabled=false，ListenerService stopSelf")
                    stopSelf()
                }
            }
        }
        // 常驻通知参数详情开关：开 → 事件驱动 + 30s 轮询；关 → 静态文案
        // Task 31 健壮性：collect 体内任一步抛异常都不能静默杀死整条流（否则
        // notifyPersistent 永久停留旧值、轮询/通知双失效）。
        scope.launch {
            settings.notifyPersistent.collect { on ->
                try {
                    notifyPersistent = on
                    if (on) startPolling() else stopPolling()
                    refreshNotification()
                } catch (t: Throwable) {
                    LogStore.log("notifyPersistent 切换处理失败（流继续）: $t")
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        // ACL_CONNECTED 的发送方是 com.android.bluetooth（uid 1002，非 system_server）：
        // Android 13+ 下 RECEIVER_NOT_EXPORTED 的运行时 receiver 收不到非 system uid 进程的广播
        //（真机实证零送达）。必须 EXPORTED；伪造广播的代价只是多跑一次无害的 ApplyWorker。
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        LogStore.log("ListenerService 前台服务已启动（FGS 主触发）")

        // Task 37：耳机先于 App 连接时不产生 ACL_CONNECTED 广播，ApplyWorker
        // 从未运行（logcat 实证：引导完成后服务启动，无任何派生/写入/建档记录）。
        // 启动末尾补跑一次「启动补触发」覆盖该场景。onCreate 语义更准（每实例一次），
        // 且幂等——已拉满走 Task 36 早退分支（仍建档），重复触发无害。
        scope.launch { retriggerIfAlreadyConnected() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            if (!settings.isAutoEnabled()) {
                LogStore.log("onStartCommand：autoEnabled=false，不驻留")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(receiver) }
        pollJob?.cancel()
        provider.unbind()
        scope.cancel()
        super.onDestroy()
        LogStore.log("ListenerService 已停止")
    }

    override fun onBind(intent: Intent?) = null

    // ================= ACL_CONNECTED =================

    private fun onConnected(intent: Intent) {
        val device: BluetoothDevice? = try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (se: SecurityException) {
            LogStore.log("ListenerService 读取 EXTRA_DEVICE 需要 BLUETOOTH_CONNECT: $se")
            null
        }
        if (device == null) {
            LogStore.log("ListenerService：ACL_CONNECTED 无 EXTRA_DEVICE，跳过")
            return
        }
        val mac: String
        val name: String?
        try {
            mac = device.address
            name = device.name
        } catch (se: SecurityException) {
            LogStore.log("ListenerService 读取设备地址/名称需要 BLUETOOTH_CONNECT: $se")
            return
        }
        scope.launch {
            HeadsetEvents.bump()
            refreshNotification()
            try {
                ApplyWorker(this@ListenerService).run(mac, name)
            } catch (t: Throwable) {
                LogStore.log("ListenerService 触发 ApplyWorker 未捕获异常: $t")
            }
            // 写入成功后立即刷新一次通知正文
            HeadsetEvents.bump()
            refreshNotification()
        }
    }

    // ================= 启动补触发（Task 37） =================

    /**
     * Task 37 启动补触发：服务启动时若本机 A2DP 已连接（耳机先于 App 连接，
     * ACL_CONNECTED 广播已错过，ApplyWorker 永远不会被触发），经载体 dumpDevices
     * 取回全部已连接设备（参照 MainActivity.onRefreshConnection 写法），逐台补跑
     * ApplyWorker.run——force=false 尊重 autoApply=false 拦截；ApplyLock 自带互斥
     * 串行，顺序调用即可。
     *
     * 幂等：已是最高 → Task 36 早退分支（仍建档）；未拉满 → 正常写入+建档。
     * 载体未就绪/未授权/超时/空列表 → 记一句跳过原因，不报错不崩；
     * 载体调用整体包 withTimeoutOrNull（同 readConnectedSummary 的
     * CARRIER_READ_TIMEOUT_MS），防 vivo 冻结 codec 进程的永久阻塞（Task 31 教训）。
     * 日志以「启动补触发」前缀区分信号源，便于浸泡排查。
     */
    private suspend fun retriggerIfAlreadyConnected() {
        if (!isA2dpConnectedLocally()) return
        if (!provider.isRunning || !provider.hasPermission) {
            LogStore.log("启动补触发：载体未就绪或未授权，跳过")
            return
        }
        try {
            val devices = withTimeoutOrNull(CARRIER_READ_TIMEOUT_MS) {
                val svc = withContext(Dispatchers.IO) { provider.bind() }
                try {
                    JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                } finally {
                    provider.unbind()
                }
            }
            if (devices == null) {
                LogStore.log("启动补触发：载体读取超时（进程可能被系统冻结），跳过")
                return
            }
            if (devices.length() == 0) {
                LogStore.log("启动补触发：本机 A2DP 已连接但载体设备列表为空，跳过")
                return
            }
            for (i in 0 until devices.length()) {
                val obj = devices.getJSONObject(i)
                val mac = obj.getString("mac")
                val name = obj.getString("name")
                LogStore.log("启动补触发：发现已连接设备 $name ($mac)，补跑 ApplyWorker")
                try {
                    ApplyWorker(this).run(mac, name)
                } catch (t: Throwable) {
                    LogStore.log("启动补触发 ApplyWorker 未捕获异常: $t")
                }
            }
        } catch (t: Throwable) {
            LogStore.log("启动补触发失败: $t")
        }
    }

    // ================= 常驻通知 =================

    /**
     * notifyPersistent 开时的 30s 轮询刷新。
     * Task 31 轮询加固：单轮异常 try/catch + continue（异常被 refreshNotification/
     * readConnectedSummary 内层兜住时本层不触发，双保险），启动/停止/失败全量进 LogStore，
     * 便于后台浸泡时定位「轮询静默死亡」类故障。
     */
    private fun startPolling() {
        if (pollJob != null) return
        LogStore.log("常驻通知 30s 轮询已启动")
        pollJob = scope.launch {
            while (isActive) {
                try {
                    refreshNotification()
                } catch (t: Throwable) {
                    LogStore.log("常驻通知轮询本轮失败（下轮继续）: $t")
                }
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        if (pollJob != null) LogStore.log("常驻通知轮询已停止")
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 常驻通知刷新。Task 32 语义裁决：notifyPersistent=false = 静态一次性通知
     * （onCreate 首贴即法定存在，正文「音质满格运行中」从不变化），用户划除后即消失
     * 至进程重启——任何事件（连接/断开/collect）都不得重 post，否则每次
     * startForeground 都会撤销用户的划除。notifyPersistent=true 行为不变：
     * 事件驱动 + 30s 轮询实时刷新参数行。
     */
    private suspend fun refreshNotification() {
        if (!notifyPersistent) return
        val content = readConnectedSummary() ?: TEXT_WAITING
        runCatching {
            ServiceCompat.startForeground(
                this, ID_LISTENER,
                notifier.persistentNotification(content),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { LogStore.log("常驻通知刷新失败: $it") }
    }

    /**
     * 读第一台已连接 A2DP 设备的当前参数；载体异常/无设备/超时返回 null。
     *
     * Task 31（浸泡实测追加）：后台浸泡发现 vivo 会冻结 Shizuku 拉起的 codec 用户服务
     * 进程，binder 同步调用 transact() 永久阻塞（connect 路径的 latch.await 自带 10s
     * 超时，但 dumpDevices/readConfig 没有）——轮询循环自此停摆，通知参数永久停留。
     * 两道防线：
     * 1. 先用本机蓝牙 API 判 A2DP 连接态，断连判定零载体依赖、秒级返回；
     * 2. 载体读取整体包 withTimeoutOrNull，单轮冻结最多卡 8s，循环不死。
     */
    private suspend fun readConnectedSummary(): String? {
        if (!isA2dpConnectedLocally()) return null
        if (!provider.isRunning || !provider.hasPermission) return null
        return try {
            withTimeoutOrNull(CARRIER_READ_TIMEOUT_MS) {
                val svc = withContext(Dispatchers.IO) { provider.bind() }
                try {
                    val arr = JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                    if (arr.length() == 0) {
                        null
                    } else {
                        val mac = arr.getJSONObject(0).getString("mac")
                        val name = arr.getJSONObject(0).getString("name")
                        val stateJson = withContext(Dispatchers.IO) { svc.readConfig(mac) }
                        val obj = JSONObject(stateJson)
                        if (obj.has("error")) {
                            null
                        } else {
                            val presentation = CodecSnapshotPresenter.present(
                                CodecStateSnapshotJson.parse(stateJson),
                            )
                            if (presentation == null) {
                                null
                            } else {
                                recordIdentityConflict(mac, presentation)
                                checkDrift(mac, name, presentation)
                                "$name · ${presentation.summary.text()}"
                            }
                        }
                    }
                } finally {
                    provider.unbind()
                }
            } ?: run {
                LogStore.log("常驻通知读取参数超时（载体进程可能被系统冻结），本轮按读不到处理")
                null
            }
        } catch (t: Throwable) {
            LogStore.log("常驻通知读取参数失败: $t")
            null
        }
    }

    private fun recordIdentityConflict(mac: String, presentation: CodecPresentation) {
        if (CodecIdentityIssue.SYSTEM_LEGACY_CONFLICT !in presentation.issues) return
        val identity = presentation.identity
        if (CodecIdentityDiagnosticGate.shouldRecord(mac, presentation.current, identity)) {
            LogStore.log(
                "IDENTITY_CONFLICT: nativeType=${identity.nativeType} " +
                    "system=${identity.displayName} legacy=${Masks.codecName(identity.nativeType)}"
            )
        }
    }

    /**
     * Task 43 参数漂移提醒：轮询读回与「最近一次已验证生效目标」不一致时，
     * 弹一条结果通知「检测到系统改回了参数」+ 记 LogStore；每轮漂移期只弹一次，
     * 读回恢复一致（系统协商回来/用户重新拉满）后重置标志，下次漂移可再提醒。
     *
     * 取舍：漂移判定只挂在 notifyPersistent=on 的 30s 轮询路径上（off 时没有轮询，
     * 靠连接事件自然重拉即可，不为漂移提醒额外唤醒载体）；通知本身受 notifyResult 开关控制。
     */
    private suspend fun checkDrift(mac: String, name: String, presentation: CodecPresentation) {
        val current = presentation.current
        val currentSummary = presentation.summary.text()
        val target = VerifiedTargetStore.targetFor(mac) ?: return
        if (ApplyPolicy.matches(target, current)) {
            if (VerifiedTargetStore.isNotified(mac)) {
                VerifiedTargetStore.resetNotified(mac)
                LogStore.log("$name 参数已恢复与拉满目标一致")
            }
            return
        }
        if (VerifiedTargetStore.isNotified(mac)) return
        VerifiedTargetStore.markNotified(mac)
        LogStore.log("检测到参数漂移: $name ($mac) 目标 ${target.summary()}，读回 $currentSummary")
        if (settings.isNotifyResult()) {
            notifier.notifyResult(
                "检测到系统改回了参数",
                "$name 当前 $currentSummary，与已拉满目标 ${target.summary()} 不符",
            )
        }
    }

    /**
     * 本机 A2DP 连接态（不依赖特权载体）。SecurityException（BLUETOOTH_CONNECT 被撤）
     * 与任何异常都不拦截——返回 true 让调用方走载体路径按原逻辑处理。
     */
    private fun isA2dpConnectedLocally(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()
            ?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
    } catch (se: SecurityException) {
        LogStore.log("本地 A2DP 状态读取需要 BLUETOOTH_CONNECT: $se")
        true
    } catch (t: Throwable) {
        true
    }

    companion object {
        /**
         * FGS 存活标志（Task 7 双触发互斥）：真机实证 ACL_CONNECTED 会同时命中
         * 本服务的运行时 receiver 与 manifest 的 ConnectionReceiver，导致 ApplyWorker
         * 并发跑两遍。广播兜底侧读取本标志，FGS 活着时直接让位。
         */
        @Volatile
        var isRunning = false
            private set

        private const val ID_LISTENER = 100
        private const val POLL_MS = 30_000L
        private const val CARRIER_READ_TIMEOUT_MS = 8_000L
        private const val TEXT_WAITING = "等待耳机连接"
        private const val TEXT_RUNNING = "音质满格运行中"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}
