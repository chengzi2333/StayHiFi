package com.chengzi.stayhifi.device

import android.content.Context

/** 本机 A2DP 已连接耳机（普通 A2DP proxy API 读取，不依赖特权通道）。 */
data class LocalHeadset(val name: String, val mac: String)

/**
 * 本机连接读取结果。空列表 / 权限异常 / 其他异常必须分开：
 * [Disconnected] 是「确认无 A2DP 连接」的正面事实；
 * [SecurityBlocked] / [Unknown] 只是「读不到」，UNKNOWN 不等于断开，
 * 调用方不得据此把连接改判为未连接，只能回退其他来源或保持原状。
 */
sealed interface LocalHeadsetResult {
    data class Connected(val headset: LocalHeadset) : LocalHeadsetResult
    data object Disconnected : LocalHeadsetResult
    data object SecurityBlocked : LocalHeadsetResult
    data object Unknown : LocalHeadsetResult
}

/** 只在本机读取类别变化时记录，不输出耳机名/MAC，也不让轮询刷满 200 行日志。 */
internal class LocalHeadsetDiagnosticLogger(
    private val sink: (String) -> Unit,
) {
    private var lastLabel: String? = null

    fun log(result: LocalHeadsetResult) {
        val label = when (result) {
            is LocalHeadsetResult.Connected -> "CONNECTED"
            LocalHeadsetResult.Disconnected -> "DISCONNECTED"
            LocalHeadsetResult.SecurityBlocked -> "SECURITY_BLOCKED"
            LocalHeadsetResult.Unknown -> "UNKNOWN"
        }
        if (label == lastLabel) return
        lastLabel = label
        sink("本机 A2DP 读取: $label")
    }
}

/** 本机蓝牙设备的最小只读面（解耦 android.bluetooth.BluetoothDevice，供纯函数测试）。 */
internal data class RawBtDevice(val name: String?, val address: String)

/**
 * v1.2.2.1 修复：本机设备来源改用 BluetoothAdapter.getProfileProxy(A2DP)。
 * v1.2.2 错用的 BluetoothManager.getConnectedDevices 只支持 GATT/GATT_SERVER，
 * 会在 A2DP 上抛 IllegalArgumentException 并被归为 Unknown，导致解耦路径失效。
 * 正确 proxy 路径仅需 BLUETOOTH_CONNECT，不依赖 Root/Shizuku；权限缺失仍如实映射。
 */
class LocalHeadsetSource internal constructor(
    private val query: LocalA2dpDeviceQuery,
) {

    constructor(context: Context) : this(androidA2dpDeviceQuery(context))

    fun current(): LocalHeadsetResult = classify(query::connectedDevices)

    /** 启动补触发需要遍历全部本机 A2DP 设备；首页单设备展示仍使用 [current]。 */
    internal fun connectedHeadsets(): List<LocalHeadset>? = try {
        query.connectedDevices()?.map { device ->
            LocalHeadset(name = device.name ?: device.address, mac = device.address)
        }
    } catch (_: Throwable) {
        null
    }

    internal companion object {
        /**
         * 纯结果裁决（JVM 单测以 fake query 直接覆盖）：
         * null=读不到（Unknown）；空列表=确认无连接（Disconnected）；
         * SecurityException=权限缺失（SecurityBlocked）；其余异常=读不到（Unknown）。
         * 设备名为空时回退地址，展示永不空串。
         */
        fun classify(query: () -> List<RawBtDevice>?): LocalHeadsetResult = try {
            when (val devices = query()) {
                null -> LocalHeadsetResult.Unknown
                emptyList<RawBtDevice>() -> LocalHeadsetResult.Disconnected
                else -> {
                    val first = devices.first()
                    LocalHeadsetResult.Connected(
                        LocalHeadset(name = first.name ?: first.address, mac = first.address),
                    )
                }
            }
        } catch (se: SecurityException) {
            LocalHeadsetResult.SecurityBlocked
        } catch (t: Throwable) {
            LocalHeadsetResult.Unknown
        }
    }
}

/**
 * 连接展示合并规则（教程/首页共用）：本机普通 API 是连接事实来源，
 * 特权 dump/read 只作参数增强——任何特权失败都不得把本机已连接改判为断开。
 */
object ConnectionPresentation {

    /** 本机是否已确认连接（可直接点亮「已连接」UI）。 */
    fun isLocallyConnected(local: LocalHeadsetResult): Boolean =
        local is LocalHeadsetResult.Connected

    /**
     * 特权通道的空设备列表能否改判为「未连接」：
     * 仅本机读不到（Unknown/SecurityBlocked）时才允许；
     * 本机已确认连接（或已确认断开）时不允许——UNKNOWN 不等于断开。
     */
    fun providerEmptyListAuthoritative(local: LocalHeadsetResult): Boolean =
        local is LocalHeadsetResult.Unknown || local is LocalHeadsetResult.SecurityBlocked
}
