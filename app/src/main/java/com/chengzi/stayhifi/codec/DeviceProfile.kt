package com.chengzi.stayhifi.codec

import kotlinx.serialization.Serializable

/** 已知设备的持久化档案：MAC + 名称 + 应应用的 codec 配置。 */
@Serializable
data class DeviceProfile(
    val mac: String,
    val name: String,
    val config: CodecConfig,
    val source: Source,
    val autoApply: Boolean,
) {
    /** 配置来源：AUTO=用户手选，CAPTURED=抓包捕获，PRESET=内置预设。 */
    enum class Source { AUTO, CAPTURED, PRESET }
}
