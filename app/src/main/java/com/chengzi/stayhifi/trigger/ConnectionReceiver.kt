package com.chengzi.stayhifi.trigger

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * ACL_CONNECTED 触发器（manifest 注册，exported=false）：
 * 自动开关关 → 忽略；读不到设备（无 BLUETOOTH_CONNECT）→ 记日志返回；
 * 否则 goAsync 转 IO 协程跑 [ApplyWorker]，结束后 finish 放行进程退出。
 */
class ConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        // 双触发互斥：FGS（ListenerService）活着时它是唯一可靠触发路径，
        // 广播兜底让位，避免 ApplyWorker 并发跑两遍
        if (ListenerService.isRunning) {
            LogStore.log("FGS 存活，ACL_CONNECTED 由 ListenerService 处理，广播兜底让位")
            return
        }

        val enabled = runBlocking { SettingsRepository(context).isAutoEnabled() }
        if (!enabled) {
            LogStore.log("自动拉满开关关闭，忽略 ACL_CONNECTED")
            return
        }

        val device: BluetoothDevice? = try {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } catch (se: SecurityException) {
            LogStore.log("读取 EXTRA_DEVICE 需要 BLUETOOTH_CONNECT 权限: $se")
            null
        }
        if (device == null) {
            LogStore.log("ACL_CONNECTED 无 EXTRA_DEVICE，跳过")
            return
        }

        val mac: String
        val name: String?
        try {
            mac = device.address
            name = device.name
        } catch (se: SecurityException) {
            LogStore.log("读取设备地址/名称需要 BLUETOOTH_CONNECT 权限: $se")
            return
        }

        LogStore.log("收到 ACL_CONNECTED: ${name ?: "?"} ($mac)")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ApplyWorker(context).run(mac, name)
            } catch (t: Throwable) {
                LogStore.log("ApplyWorker 未捕获异常: $t")
            } finally {
                pending.finish()
            }
        }
    }
}
