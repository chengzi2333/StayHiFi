package com.chengzi.stayhifi.shell

/**
 * 特权 codec 操作的目标设备定位。
 *
 * connectedDevices 在某些 Root/ROM 组合会为空，但调用方已经从本机 API 或
 * ACL 广播获得真实 MAC。此时 BluetoothAdapter.getRemoteDevice(mac) 可以构造
 * 系统设备句柄，不应让空枚举阻断后续 getCodecStatus/setCodecConfigPreference。
 * 构造句柄本身不冒认连接：后续 current=null / 系统校验仍决定是否可读、可写。
 */
internal object CodecDeviceLocator {

    fun <T> select(
        mac: String?,
        connectedDevices: List<T>,
        addressOf: (T) -> String,
        remoteDevice: (String) -> T?,
    ): T? {
        if (mac.isNullOrBlank()) return connectedDevices.firstOrNull()
        val target = mac.trim()
        connectedDevices.firstOrNull {
            addressOf(it).equals(target, ignoreCase = true)
        }?.let { return it }
        return remoteDevice(target)
    }
}
