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
     * codecType → 人类可读名字，仅用于显示。
     * Task 44：映射表扩充，只收有出处的编号，查不到出处的不编，未知仍回落 vendor($type)——
     * 0~6 为 AOSP 标准编号（已核对 AOSP 源码 packages/modules/Bluetooth
     * BluetoothCodecConfig.java，android-14.0.0_r1 与 main 一致：5=LC3、6=Opus；
     * aptX Adaptive/TWS+ 不在 AOSP 公共编号里，属高通私有编号）；
     * 7=aptX-Adaptive、10=LHDC V5 为 vivo 栈真机实证（2026-10，OriginOS，
     * dumpsys 段头 "A2DP LHDC V5 State:" 佐证 10 的 V5 后缀）。
     */
    fun codecName(type: Int): String = when (type) {
        0 -> "SBC"
        1 -> "AAC"
        2 -> "aptX"
        3 -> "aptX-HD"
        4 -> "LDAC"
        5 -> "LC3"            // AOSP SOURCE_CODEC_TYPE_LC3
        6 -> "Opus"           // AOSP SOURCE_CODEC_TYPE_OPUS
        7 -> "aptX-Adaptive"  // vivo 栈真机实证；AOSP 无此公共编号
        10 -> "LHDC V5"       // vivo 栈真机实证（原映射 "LHDC" 无版本后缀）
        else -> "vendor($type)"
    }

    /** OPPO/OnePlus 新 ColorOS 厂商栈把 LHDC 家族放在 0x10..0x1F（旧栈为 8/9/10/13），
     *  子编号随手机栈 build 变；需能力佐证，不能裸认。 */
    fun isVendorLhdcRange(type: Int): Boolean = type in 0x10..0x1F

    /** 佐证版：capsRateMask 为该 codecType 在 selectable 中所有条目的 sampleRate 位或集；
     *  含 ≥176.4kHz 位（16 或 32）才可认 LHDC——这是 LHDC 5.0 独有能力签名
     *  （LDAC 上限 96kHz，aptX/LC3 更低）。佐证失败回落单参 codecName（显示 vendor(N)）。
     *  用 and 判位，禁止相等比较（capsRateMask 是多比特或集）。 */
    fun codecName(type: Int, capsRateMask: Int): String =
        if (isVendorLhdcRange(type) && capsRateMask and (16 or 32) != 0) "LHDC"
        else codecName(type)

    /** 把 selectable 中 codecType 相同的条目 sampleRate 位或起来，得能力掩码。
     *  sampleRate 字段在 selectable 语境是多比特掩码（可含 43 这类叠加值），或集天然兼容。 */
    fun capsRateMask(type: Int, selectable: List<CodecConfig>): Int =
        selectable.filter { it.codecType == type }.fold(0) { acc, c -> acc or c.sampleRate }

    /**
     * cs1（codec-specific 1）质量档到人类可读标签；未知编号返回 null（调用方保持三段显示）。
     * 4=LDAC 用 AOSP 标准值（见 BluetoothCodecConfig 注释）：
     * 1000=990kbps 高 / 1001=660kbps / 1002=330kbps / 1003=自适应。
     * 10=LHDC V5 为厂商私有编号（仅 OPPO/vivo 系栈实测）：
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
