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

/**
 * 显示名 → 家族映射：只做通用显示/诊断归类，名称匹配大小写不敏感；
 * LHDC 接受 generic "LHDC" 与带版本后缀的 "LHDC V3" 等；不识别的名称归 UNKNOWN。
 */
fun codecFamilyOf(displayName: String): CodecFamily {
    val upper = displayName.trim().uppercase()
    return when {
        upper == "SBC" -> CodecFamily.SBC
        upper == "AAC" -> CodecFamily.AAC
        upper == "APTX" || upper == "APT-X" -> CodecFamily.APTX
        upper == "APTX-HD" || upper == "APTX HD" -> CodecFamily.APTX_HD
        upper == "APTX-ADAPTIVE" || upper == "APTX ADAPTIVE" -> CodecFamily.APTX_ADAPTIVE
        upper == "LDAC" -> CodecFamily.LDAC
        upper == "LC3" -> CodecFamily.LC3
        upper == "OPUS" -> CodecFamily.OPUS
        upper == "LHDC" || upper.startsWith("LHDC ") -> CodecFamily.LHDC
        else -> CodecFamily.UNKNOWN
    }
}
