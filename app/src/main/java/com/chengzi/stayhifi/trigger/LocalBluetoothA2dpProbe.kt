package com.chengzi.stayhifi.trigger

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

import android.annotation.SuppressLint

/**
 * 本机 A2DP 链路状态（Task B4 锁后复查）。
 * UNKNOWN 是兼容回退（读取不可得），不代表「已确认连接」——调用方须如实记录。
 */
enum class LocalA2dpState { CONNECTED, DISCONNECTED, UNKNOWN }

fun interface LocalA2dpProbe {
    fun state(mac: String): LocalA2dpState
}

/**
 * 只做本机 API 地址匹配：[BluetoothManager] 的 A2DP connectedDevices 与目标 MAC
 * 忽略大小写比对。不创建 BluetoothA2dp proxy、不增加等待或轮询。
 * SecurityException（如 BLUETOOTH_CONNECT 未授权）或任何读取异常 → UNKNOWN，
 * 由调用方按旧路径兼容执行。
 */
class LocalBluetoothA2dpProbe(context: Context) : LocalA2dpProbe {

    private val manager: BluetoothManager? = try {
        context.applicationContext.getSystemService(BluetoothManager::class.java)
    } catch (t: Throwable) {
        null
    }

    // SecurityException（BLUETOOTH_CONNECT 未授权）按设计落 UNKNOWN，与 CodecService 同款抑制。
    @SuppressLint("MissingPermission")
    override fun state(mac: String): LocalA2dpState = try {
        stateOf(manager?.getConnectedDevices(BluetoothProfile.A2DP)?.map { it.address }, mac)
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
