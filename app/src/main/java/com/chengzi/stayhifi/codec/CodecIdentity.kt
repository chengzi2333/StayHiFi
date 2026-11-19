package com.chengzi.stayhifi.codec

/**
 * Task 4：统一编码器身份裁决的公共类型。
 * family 仅用于显示与通用诊断（质量档、实况段选择等），不得据此新增任何
 * aptX 专属写入策略；写入路径仍只看 9 字段 [CodecConfig]。
 */

/** 通用编码器家族；身份无法归入已知家族时为 [CodecFamily.UNKNOWN]。 */
enum class CodecFamily { SBC, AAC, APTX, APTX_HD, APTX_ADAPTIVE, LHDC, LDAC, LC3, OPUS, UNKNOWN }

/** 身份来源，按可信度/优先级从高到低排列。 */
enum class CodecIdentitySource { SYSTEM_TYPE, EXTENDED_CONFIG, VERIFIED_LEGACY, CAPABILITY_HEURISTIC, UNKNOWN }

/** 身份裁决中发现的诊断问题；不影响最终身份的显示，只如实留证。 */
enum class CodecIdentityIssue { SUPPORTED_TYPE_CONFLICT, SUPPORTED_TYPE_INVALID, SYSTEM_LEGACY_CONFLICT, EXTENDED_INVALID }

/**
 * 已裁决的编码器身份。nativeType 是系统 codecType 编号；
 * displayName 如 "LHDC V3"，未知为 "vendor(N)"；codecId 仅系统/extended 来源可提供。
 */
data class CodecIdentity(
    val nativeType: Int,
    val family: CodecFamily,
    val displayName: String,
    val codecId: Long? = null,
    val source: CodecIdentitySource,
)

/** 身份裁决结果：最终身份 + 全程积累的诊断问题（去重集合）。 */
data class CodecIdentityResolution(
    val identity: CodecIdentity,
    val issues: Set<CodecIdentityIssue>,
)

/** LHDC 版本后缀：可选的 "V" 前缀 + 数字（允许小数），如 "V5" / "5" / "5.0"。 */
private val LHDC_VERSION = Regex("^V?\\d+(\\.\\d+)?$")

/** 名称归一化：去空格与连字符、转大写，用于家族归类（不做其他改写）。 */
internal fun normalizeCodecName(raw: String): String =
    raw.trim().uppercase().replace(" ", "").replace("-", "").replace("_", "")

/**
 * 显示名 → 家族映射：只做通用显示/诊断归类，名称匹配大小写不敏感，
 * 且忽略空格/连字符差异（实证：nubia 栈 "LHDCv5"、vivo 栈 "LHDC V5"、
 * ColorOS 段头 "LHDC V3" 同为 LHDC 家族）。
 * LHDC 接受 generic "LHDC" 与带版本后缀的 "LHDC V3"/"LHDCv5"/"LHDCV5"/"LHDC 5.0"；
 * "LHDC" 后紧跟非版本内容（如 "LHDCX"/"LHDC CODEC"）仍归 UNKNOWN，不硬认。
 * 不识别的名称归 UNKNOWN。
 */
fun codecFamilyOf(displayName: String): CodecFamily {
    val name = normalizeCodecName(displayName)
    return when {
        name == "SBC" -> CodecFamily.SBC
        name == "AAC" -> CodecFamily.AAC
        name == "APTX" -> CodecFamily.APTX   // 兼容 "aptX" / "APT-X"（归一化后同为 APTX）
        name == "APTXHD" -> CodecFamily.APTX_HD
        name == "APTXADAPTIVE" -> CodecFamily.APTX_ADAPTIVE
        name == "LDAC" -> CodecFamily.LDAC
        name == "LC3" -> CodecFamily.LC3
        name == "OPUS" -> CodecFamily.OPUS
        name == "LHDC" || isLhdcVersionedName(name) -> CodecFamily.LHDC
        else -> CodecFamily.UNKNOWN
    }
}

/** "LHDC" 后为合法版本后缀（V5 / 5 / 5.0）才算带版本 LHDC。 */
private fun isLhdcVersionedName(normalized: String): Boolean =
    normalized.startsWith("LHDC") &&
        LHDC_VERSION.matches(normalized.removePrefix("LHDC"))

/**
 * LHDC 版本后缀归一化为 "V<n>"（"LHDCv5"→"V5"、"LHDC 5"→"V5"）；无版本返回 null。
 * 供身份冲突判定比对版本，非 LHDC 名一律 null。
 */
internal fun lhdcVersionOf(displayName: String): String? {
    val name = normalizeCodecName(displayName)
    if (!name.startsWith("LHDC")) return null
    val suffix = name.removePrefix("LHDC")
    if (!LHDC_VERSION.matches(suffix)) return null
    return if (suffix.startsWith("V")) suffix else "V$suffix"
}

/**
 * 展示名归一化：LHDC 统一成 "LHDC V5" 这种带空格的读法（"LHDCv5"→"LHDC V5"），
 * 其余名称原样返回。仅影响展示，段落匹配另走 [normalizeCodecName]。
 */
fun canonicalCodecDisplayName(raw: String): String {
    val trimmed = raw.trim()
    val suffix = lhdcVersionOf(trimmed) ?: return trimmed
    return "LHDC $suffix"
}
