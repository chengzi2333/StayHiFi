package com.chengzi.stayhifi.trigger

import android.content.Context
import com.chengzi.stayhifi.device.LocalA2dpDeviceQuery
import com.chengzi.stayhifi.device.androidA2dpDeviceQuery

/**
 * 本机 A2DP 链路状态（Task B4 锁后复查）。
 * UNKNOWN 是兼容回退（读取不可得），不代表「已确认连接」——调用方须如实记录。
 */
enum class LocalA2dpState { CONNECTED, DISCONNECTED, UNKNOWN }

fun interface LocalA2dpProbe {
    fun state(mac: String): LocalA2dpState
}

/**
 * 只做本机 A2DP proxy 地址匹配：与首页共用正确的设备查询路径，目标 MAC
 * 忽略大小写比对。单次 proxy 等待有界，不增加轮询。
 * SecurityException（如 BLUETOOTH_CONNECT 未授权）或任何读取异常 → UNKNOWN，
 * 由调用方按旧路径兼容执行。
 */
class LocalBluetoothA2dpProbe internal constructor(
    private val query: LocalA2dpDeviceQuery,
) : LocalA2dpProbe {

    constructor(context: Context) : this(androidA2dpDeviceQuery(context))

    override fun state(mac: String): LocalA2dpState = try {
        stateOf(query.connectedDevices()?.map { it.address }, mac)
    } catch (t: Throwable) {
        LocalA2dpState.UNKNOWN
    }

    internal companion object {
        /**
         * 纯地址匹配逻辑（JVM 单测直接覆盖）：
         * null=本机读取不可得（UNKNOWN）；空列表=确认无该设备连接（DISCONNECTED）。
         */
        fun stateOf(addresses: List<String>?, mac: String): LocalA2dpState = when {
            addresses == null -> LocalA2dpState.UNKNOWN
            addresses.any { it.equals(mac, ignoreCase = true) } -> LocalA2dpState.CONNECTED
            else -> LocalA2dpState.DISCONNECTED
        }
    }
}
