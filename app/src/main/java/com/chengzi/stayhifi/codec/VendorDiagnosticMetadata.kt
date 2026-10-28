package com.chengzi.stayhifi.codec

import android.os.Build
import com.chengzi.stayhifi.util.DeviceNames

/**
 * Task B5：通用厂商栈诊断的设备元数据。只读 Build 与现有 [DeviceNames] 缓存快照，
 * 不收集序列号、IMEI、shell 环境或任何新 MAC 来源；任何读取失败回落 "unknown"，
 * 绝不破坏 readConfig 主结果。
 */
data class DiagnosticDeviceMetadata(
    val marketModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val buildDisplay: String,
    val manufacturer: String,
    val hardware: String,
    val board: String,
    val socManufacturer: String,
    val socModel: String,
)

object VendorDiagnosticMetadata {

    private const val UNKNOWN = "unknown"

    /**
     * 当前只读元数据。SOC_MANUFACTURER/SOC_MODEL 仅 API 31+ 存在，
     * 低版本直接填 "unknown"；整体 try/catch 兜底，异常时全字段 unknown。
     */
    fun current(): DiagnosticDeviceMetadata = try {
        val api31 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        DiagnosticDeviceMetadata(
            marketModel = runCatching { DeviceNames.marketModel() }.getOrElse { UNKNOWN },
            androidRelease = Build.VERSION.RELEASE ?: UNKNOWN,
            sdkInt = Build.VERSION.SDK_INT,
            buildDisplay = Build.DISPLAY ?: UNKNOWN,
            manufacturer = Build.MANUFACTURER ?: UNKNOWN,
            hardware = Build.HARDWARE ?: UNKNOWN,
            board = Build.BOARD ?: UNKNOWN,
            socManufacturer = if (api31) Build.SOC_MANUFACTURER ?: UNKNOWN else UNKNOWN,
            socModel = if (api31) Build.SOC_MODEL ?: UNKNOWN else UNKNOWN,
        )
    } catch (t: Throwable) {
        DiagnosticDeviceMetadata(
            marketModel = UNKNOWN,
            androidRelease = UNKNOWN,
            sdkInt = 0,
            buildDisplay = UNKNOWN,
            manufacturer = UNKNOWN,
            hardware = UNKNOWN,
            board = UNKNOWN,
            socManufacturer = UNKNOWN,
            socModel = UNKNOWN,
        )
    }
}
