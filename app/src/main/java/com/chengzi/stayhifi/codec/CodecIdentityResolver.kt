package com.chengzi.stayhifi.codec

/**
 * Task 4：纯函数编码器身份裁决。
 *
 * 裁决顺序（设计 §4.3，旧启发式只作回退，不得反向覆盖系统身份）：
 * 1. 与 current.nativeType 匹配且无冲突的系统 supported type；
 * 2. 可信的 extended config 身份；
 * 3. 既有已实证静态映射（AOSP 标准 0..6 + vivo 栈实证 7/10）；
 * 4. vendor 区间（0x10..0x1F）+ 高采样率能力佐证（仅当前连接的 selectable，禁用 local）；
 * 5. "vendor(N)" 与 UNKNOWN。
 *
 * 可信名称门槛：trim 后非空、大写后不以 "UNKNOWN CODEC" 开头（覆盖
 * "UNKNOWN CODEC(18)" 等变体）、长度 ≤128。冲突/不可信一律留 issue 进下一来源，
 * 不取第一条硬认。身份与能力解耦：不得仅凭 cs1/cs3 补认身份。
 */
object CodecIdentityResolver {

    private const val UNKNOWN_CODEC_PREFIX = "UNKNOWN CODEC"
    private const val MAX_TRUSTED_NAME_LENGTH = 128

    /**
     * 已实证 legacy 表（出处见 [Masks.codecName] 注释）：AOSP 标准 0..6 已核对
     * BluetoothCodecConfig 源码；7=aptX-Adaptive、10=LHDC V5 为 vivo 栈真机实证。
     * 表外编号（含 8/9/13/14/18/19 等厂商编号）一律不在此层硬认。
     */
    private val VERIFIED_LEGACY_NAMES: Map<Int, String> = mapOf(
        0 to "SBC",
        1 to "AAC",
        2 to "aptX",
        3 to "aptX-HD",
        4 to "LDAC",
        5 to "LC3",
        6 to "Opus",
        7 to "aptX-Adaptive",
        10 to "LHDC V5",
    )

    fun resolve(
        current: CodecConfig,
        selectable: List<CodecConfig>,
        supportedTypes: List<SupportedCodecType> = emptyList(),
        extendedIdentity: ExtendedCodecIdentity? = null,
    ): CodecIdentityResolution {
        val issues = mutableSetOf<CodecIdentityIssue>()

        // 1. 系统 supported types：先按 current.nativeType 过滤成组。
        //    整组先过可信门槛：任何一条不可信（blank / UNKNOWN CODEC* / 超长）即整组
        //    不选、不留残余可信项——冲突/不可信组必须进下一身份来源，不取第一条硬认。
        val group = supportedTypes.filter { it.nativeType == current.codecType }
        if (group.isNotEmpty()) {
            when {
                group.any { !isTrustedName(it.codecName) } ->
                    issues += CodecIdentityIssue.SUPPORTED_TYPE_INVALID

                else -> {
                    val distinct = group.distinctBy { it.codecName.trim() to it.codecId }
                    if (distinct.size > 1) {
                        // 名称或 codecId 冲突 → 整组忽略。
                        issues += CodecIdentityIssue.SUPPORTED_TYPE_CONFLICT
                    } else {
                        val entry = distinct.single()
                        val systemIdentity = CodecIdentity(
                            nativeType = current.codecType,
                            family = codecFamilyOf(entry.codecName),
                            displayName = entry.codecName.trim(),
                            codecId = entry.codecId,
                            source = CodecIdentitySource.SYSTEM_TYPE,
                        )
                        val legacyName = VERIFIED_LEGACY_NAMES[current.codecType]
                        if (legacyName != null && conflictsWithLegacy(systemIdentity, legacyName)) {
                            issues += CodecIdentityIssue.SYSTEM_LEGACY_CONFLICT
                        }
                        return CodecIdentityResolution(systemIdentity, issues)
                    }
                }
            }
        }

        // 2. extended config：仅作辅助证据，名称同样过可信门槛。
        val extendedName = extendedIdentity?.codecName
        if (extendedName != null) {
            if (isTrustedName(extendedName)) {
                return CodecIdentityResolution(
                    CodecIdentity(
                        nativeType = current.codecType,
                        family = codecFamilyOf(extendedName),
                        displayName = extendedName.trim(),
                        codecId = extendedIdentity.codecId,
                        source = CodecIdentitySource.EXTENDED_CONFIG,
                    ),
                    issues,
                )
            }
            issues += CodecIdentityIssue.EXTENDED_INVALID
        }

        // 3. 已实证 legacy 静态映射。
        val legacyName = VERIFIED_LEGACY_NAMES[current.codecType]
        if (legacyName != null) {
            return CodecIdentityResolution(
                CodecIdentity(
                    nativeType = current.codecType,
                    family = codecFamilyOf(legacyName),
                    displayName = legacyName,
                    source = CodecIdentitySource.VERIFIED_LEGACY,
                ),
                issues,
            )
        }

        // 4. vendor 区间 + 高采样率能力佐证（LHDC 5.0 独有能力签名）。
        //    只用当前连接的 selectable 位或，禁读 localCapabilities。
        val capsMask = Masks.capsRateMask(current.codecType, selectable)
        if (Masks.isVendorLhdcRange(current.codecType) && capsMask and (16 or 32) != 0) {
            return CodecIdentityResolution(
                CodecIdentity(
                    nativeType = current.codecType,
                    family = CodecFamily.LHDC,
                    displayName = "LHDC",
                    source = CodecIdentitySource.CAPABILITY_HEURISTIC,
                ),
                issues,
            )
        }

        // 5. 未知：vendor(N) / UNKNOWN。cs1/cs3 形似某族也不据此补认。
        return CodecIdentityResolution(
            CodecIdentity(
                nativeType = current.codecType,
                family = CodecFamily.UNKNOWN,
                displayName = "vendor(${current.codecType})",
                source = CodecIdentitySource.UNKNOWN,
            ),
            issues,
        )
    }

    /** snapshot 便捷入口：current==null 返回 null；只传 selectable，绝不读 localCapabilities。 */
    fun resolve(snapshot: CodecStateSnapshot): CodecIdentityResolution? {
        val current = snapshot.current ?: return null
        return resolve(
            current = current,
            selectable = snapshot.selectable,
            supportedTypes = snapshot.supportedTypes,
            extendedIdentity = snapshot.extendedIdentity,
        )
    }

    /** 名称可信门槛：trim 非空、大写不以 UNKNOWN CODEC 开头、长度 ≤128。 */
    private fun isTrustedName(raw: String): Boolean {
        val name = raw.trim()
        if (name.isEmpty()) return false
        if (name.length > MAX_TRUSTED_NAME_LENGTH) return false
        if (name.uppercase().startsWith(UNKNOWN_CODEC_PREFIX)) return false
        return true
    }

    /**
     * 系统与 legacy 冲突判定：仅在 family 不同（系统身份推翻旧映射）或
     * 明确 LHDC 版本互斥（如 V3 vs V5）时成立；generic LHDC 与已版本化名称不算冲突。
     */
    private fun conflictsWithLegacy(systemIdentity: CodecIdentity, legacyName: String): Boolean {
        if (systemIdentity.family != codecFamilyOf(legacyName)) return true
        val systemVersion = lhdcVersionSuffix(systemIdentity.displayName)
        val legacyVersion = lhdcVersionSuffix(legacyName)
        return systemVersion != null && legacyVersion != null && systemVersion != legacyVersion
    }

    /** LHDC 版本后缀（如 "V3"）；generic "LHDC" 或无版本信息返回 null。 */
    private fun lhdcVersionSuffix(name: String): String? {
        val upper = name.trim().uppercase()
        if (upper != "LHDC" && !upper.startsWith("LHDC ")) return null
        return upper.removePrefix("LHDC").trim().ifEmpty { null }
    }
}
