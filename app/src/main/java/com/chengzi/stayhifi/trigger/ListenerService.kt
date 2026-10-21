package com.chengzi.stayhifi.trigger

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.codec.CodecConfig
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
 * - 常驻通知 channel_listener（IMPORTANCE_LOW 静默）：标题「StayHiFi 运行中」。
 *   notifyPersistent 关 = 静态正文「音质满格运行中」，不轮询；
 *   notifyPersistent 开 = 事件驱动（连接/断开/写入后）+ 30s 轮询 readConfig 刷新，
 *   已连接显示 "Enco X3 · LHDC·192kHz·24bit"，未连接或读不到参数时「等待耳机连接」。
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
                notifier.persistentNotification(TEXT_WAITING),
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
        scope.launch {
            settings.notifyPersistent.collect { on ->
                notifyPersistent = on
                if (on) startPolling() else stopPolling()
                refreshNotification()
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // ACL_CONNECTED 的发送方是 com.android.bluetooth（uid 1002，非 system_server）：
        // Android 13+ 下 RECEIVER_NOT_EXPORTED 的运行时 receiver 收不到非 system uid 进程的广播
        //（真机实证零送达）。必须 EXPORTED；伪造广播的代价只是多跑一次无害的 ApplyWorker。
        ContextCompat.registerReceiver(
            this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        LogStore.log("ListenerService 前台服务已启动（FGS 主触发）")
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

    // ================= 常驻通知 =================

    /** notifyPersistent 开时的 30s 轮询刷新。 */
    private fun startPolling() {
        if (pollJob != null) return
        pollJob = scope.launch {
            while (isActive) {
                refreshNotification()
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshNotification() {
        val content = if (!notifyPersistent) {
            TEXT_RUNNING
        } else {
            readConnectedSummary() ?: TEXT_WAITING
        }
        runCatching {
            ServiceCompat.startForeground(
                this, ID_LISTENER,
                notifier.persistentNotification(content),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { LogStore.log("常驻通知刷新失败: $it") }
    }

    /** 读第一台已连接 A2DP 设备的当前参数；载体异常/无设备返回 null。 */
    private suspend fun readConnectedSummary(): String? {
        if (!provider.isRunning || !provider.hasPermission) return null
        return try {
            val svc = withContext(Dispatchers.IO) { provider.bind() }
            try {
                val arr = JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                if (arr.length() == 0) {
                    null
                } else {
                    val mac = arr.getJSONObject(0).getString("mac")
                    val name = arr.getJSONObject(0).getString("name")
                    val obj = JSONObject(withContext(Dispatchers.IO) { svc.readConfig(mac) })
                    if (obj.has("error")) null
                    else "$name · ${CodecConfig.fromJson(obj.getJSONObject("current").toString()).summary()}"
                }
            } finally {
                provider.unbind()
            }
        } catch (t: Throwable) {
            LogStore.log("常驻通知读取参数失败: $t")
            null
        }
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
