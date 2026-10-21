package com.chengzi.stayhifi.codec

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 9 字段 codec 配置，对应蓝牙 A2DP codec 配置项。
 * 黄金样例（Enco X3 实测捕获）：
 * CodecConfig(10, 1000000, 32, 2, 2, 50888712L, 0L, 1543503872L, 0L)
 */
@Serializable
data class CodecConfig(
    val codecType: Int,
    val priority: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val channelMode: Int,
    val cs1: Long,
    val cs2: Long,
    val cs3: Long,
    val cs4: Long,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    /** 如 "LHDC·192kHz·24bit" 或 "LHDC·192kHz·24bit·1000kbps"（cs1 可识别时追加质量档） */
    fun summary(): String {
        val base = "${Masks.codecName(codecType)}·${Masks.rateHz(sampleRate)}·${Masks.bitsDepth(bitsPerSample)}"
        return Masks.qualityLabel(codecType, cs1)?.let { "$base·$it" } ?: base
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        fun fromJson(s: String): CodecConfig = json.decodeFromString(serializer(), s)
    }
}

/** 位掩码 / codec 类型到人类可读名字的纯函数解码。 */
object Masks {
    private val RATE_NAMES: Map<Int, String> = linkedMapOf(
        1 to "44.1kHz",
        2 to "48kHz",
        4 to "88.2kHz",
        8 to "96kHz",
        16 to "176.4kHz",
        32 to "192kHz",
    )

    private val BITS_NAMES: Map<Int, String> = mapOf(
        1 to "16bit",
        2 to "24bit",
    )

    /** 仅解单比特；多比特掩码（如 43=多档叠加）无法唯一确定，返回 "unknown"。 */
    fun rateHz(mask: Int): String = RATE_NAMES[mask] ?: "unknown"

    fun bitsDepth(mask: Int): String = BITS_NAMES[mask] ?: "unknown"

    /**
     * codecType ≥ 5 为厂商私有编号，10=LHDC 仅在 OPPO/vivo 系栈上成立；
     * 此映射仅用于显示。0~4 为 AOSP 标准编号，全平台通用。
     */
    fun codecName(type: Int): String = when (type) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX-HD"
        4 -> "LDAC"
        10 -> "LHDC"
        else -> "vendor($type)"
    }

    /**
     * cs1（codec-specific 1）质量档到人类可读标签；未知编号返回 null（调用方保持三段显示）。
     * 4=LDAC 用 AOSP 标准值（见 BluetoothCodecConfig 注释）：
     * 1000=990kbps 高 / 1001=660kbps / 1002=330kbps / 1003=自适应。
     * 10=LHDC 为厂商私有编号（仅 OPPO/vivo 系栈实测）：
     * 50888712=音质优先(1000kbps)、33914889=自适应。
     */
    fun qualityLabel(codecType: Int, cs1: Long): String? = when (codecType) {
        4 -> when (cs1) {
            1000L -> "990kbps"
            1001L -> "660kbps"
            1002L -> "330kbps"
            1003L -> "自适应"
            else -> null
        }
        10 -> when (cs1) {
            50888712L -> "1000kbps"
            33914889L -> "自适应"
            else -> null
        }
        else -> null
    }
}
