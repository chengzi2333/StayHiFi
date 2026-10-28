package com.chengzi.stayhifi.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context

/** 本机 A2DP 已连接耳机（BluetoothManager 普通 API 读取，不依赖特权通道）。 */
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

/** 本机蓝牙设备的最小只读面（解耦 android.bluetooth.BluetoothDevice，供纯函数测试）。 */
internal data class RawBtDevice(val name: String?, val address: String)

/**
 * v1.2.2 修复：本机设备来源。BluetoothManager.getConnectedDevices(A2DP) 是普通系统 API
 * （仅需 BLUETOOTH_CONNECT 运行时权限），不依赖特权载体——特权通道未就绪时，
 * 系统蓝牙已连接也不再被误判为「未连接」。权限缺失如实映射 [LocalHeadsetResult.SecurityBlocked]，
 * 绝不伪造连接。
 */
class LocalHeadsetSource(context: Context) {

    private val manager: BluetoothManager? = try {
        context.applicationContext.getSystemService(BluetoothManager::class.java)
    } catch (t: Throwable) {
        null
    }

    // SecurityException（BLUETOOTH_CONNECT 未授权）按设计单独映射，与 CodecService 同款抑制。
    @SuppressLint("MissingPermission")
    private val query: () -> List<RawBtDevice>? = {
        manager?.getConnectedDevices(BluetoothProfile.A2DP)?.map { RawBtDevice(it.name, it.address) }
    }

    fun current(): LocalHeadsetResult = classify(query)

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
