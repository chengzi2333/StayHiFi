package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecIdentityResolverTest {

    private fun opdSelectable() =
        listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.opdType, CodecDeviceFixtures.opdCapsMask))

    @Test
    fun opdSystemIdentityWinsDespiteCaps10() {
        // OPD native18 系统目录给出 LHDC V3；caps=10（最高 96k）不得推翻系统身份
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
            supportedTypes = listOf(SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3")),
        )

        assertEquals(CodecIdentitySource.SYSTEM_TYPE, res.identity.source)
        assertEquals("LHDC V3", res.identity.displayName)
        assertEquals(CodecFamily.LHDC, res.identity.family)
        assertEquals(0x11L, res.identity.codecId)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun systemLhdcV3OverridesLegacyType10V5WithSingleConflict() {
        // 系统 LHDC V3 覆盖已实证 legacy type10/LHDC V5；版本互斥 → 恰好一次 SYSTEM_LEGACY_CONFLICT
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.iqoo15Golden,
            listOf(CodecDeviceFixtures.selectableCaps(10, 43)),
            supportedTypes = listOf(SupportedCodecType(10, 0x11L, "LHDC V3")),
        )

        assertEquals(CodecIdentitySource.SYSTEM_TYPE, res.identity.source)
        assertEquals("LHDC V3", res.identity.displayName)
        assertEquals(CodecFamily.LHDC, res.identity.family)
        assertEquals(setOf(CodecIdentityIssue.SYSTEM_LEGACY_CONFLICT), res.issues)
    }

    @Test
    fun genericSystemLhdcIsNotLegacyConflict() {
        // generic LHDC 与已版本化 legacy 名称不算冲突
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.iqoo15Golden,
            listOf(CodecDeviceFixtures.selectableCaps(10, 43)),
            supportedTypes = listOf(SupportedCodecType(10, 0x11L, "LHDC")),
        )

        assertEquals(CodecIdentitySource.SYSTEM_TYPE, res.identity.source)
        assertEquals("LHDC", res.identity.displayName)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun identicalDuplicateSupportedEntriesAreDeduplicated() {
        val dup = SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3")
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
            supportedTypes = listOf(dup, dup),
        )

        assertEquals(CodecIdentitySource.SYSTEM_TYPE, res.identity.source)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun conflictingSupportedGroupIsIgnoredAndFallsToExtended() {
        // 同 nativeType 名称冲突 → 整组忽略 + SUPPORTED_TYPE_CONFLICT，不得取第一条硬认
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
            supportedTypes = listOf(
                SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3"),
                SupportedCodecType(CodecDeviceFixtures.opdType, 0x22L, "LHDC V5"),
            ),
            extendedIdentity = ExtendedCodecIdentity(codecId = 0x33L, codecName = "LHDC V3"),
        )

        assertEquals(CodecIdentityIssue.SUPPORTED_TYPE_CONFLICT, res.issues.single())
        assertEquals(CodecIdentitySource.EXTENDED_CONFIG, res.identity.source)
        assertEquals("LHDC V3", res.identity.displayName)
        assertEquals(0x33L, res.identity.codecId)
    }

    @Test
    fun conflictingSupportedCodecIdsAreIgnoredAndFallsToExtended() {
        // 名称相同但 codecId 冲突同样整组忽略
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
            supportedTypes = listOf(
                SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3"),
                SupportedCodecType(CodecDeviceFixtures.opdType, 0x22L, "LHDC V3"),
            ),
            extendedIdentity = ExtendedCodecIdentity(codecId = 0x33L, codecName = "LHDC V3"),
        )

        assertEquals(CodecIdentityIssue.SUPPORTED_TYPE_CONFLICT, res.issues.single())
        assertEquals(CodecIdentitySource.EXTENDED_CONFIG, res.identity.source)
    }

    @Test
    fun untrustedSupportedNamesAreRejected() {
        // blank、UNKNOWN CODEC、UNKNOWN CODEC(18)、超长名称、大小写变体均不可信
        val badNames = listOf(
            " ",
            "UNKNOWN CODEC",
            "UNKNOWN CODEC(18)",
            "unknown codec",
            "x".repeat(129),
        )
        for (name in badNames) {
            val res = CodecIdentityResolver.resolve(
                CodecDeviceFixtures.opdCurrent,
                opdSelectable(),
                supportedTypes = listOf(SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, name)),
            )
            assertEquals("name=$name", CodecIdentityIssue.SUPPORTED_TYPE_INVALID, res.issues.single())
            assertEquals("name=$name", CodecIdentitySource.UNKNOWN, res.identity.source)
            assertEquals("name=$name", "vendor(18)", res.identity.displayName)
            assertEquals("name=$name", CodecFamily.UNKNOWN, res.identity.family)
        }
    }

    @Test
    fun supportedEntriesForOtherNativeTypesAreIgnored() {
        // 目录里只有其他 nativeType 的条目时，等同无系统身份，静默落到下一来源
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
            supportedTypes = listOf(SupportedCodecType(4, 0x11L, "LDAC")),
        )

        assertEquals(CodecIdentitySource.UNKNOWN, res.identity.source)
        assertEquals("vendor(18)", res.identity.displayName)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun extendedIsSecondSourceOnly() {
        // 系统身份在时 extended 不得覆盖
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
            supportedTypes = listOf(SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3")),
            extendedIdentity = ExtendedCodecIdentity(codecId = 0x33L, codecName = "LDAC"),
        )

        assertEquals(CodecIdentitySource.SYSTEM_TYPE, res.identity.source)
        assertEquals("LHDC V3", res.identity.displayName)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun invalidExtendedNameLeavesIssueAndFallsToLegacy() {
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.unverified(4, 2),
            listOf(CodecDeviceFixtures.selectableCaps(4, 43)),
            extendedIdentity = ExtendedCodecIdentity(codecId = 0x33L, codecName = "UNKNOWN CODEC"),
        )

        assertEquals(CodecIdentityIssue.EXTENDED_INVALID, res.issues.single())
        assertEquals(CodecIdentitySource.VERIFIED_LEGACY, res.identity.source)
        assertEquals("LDAC", res.identity.displayName)
        assertEquals(CodecFamily.LDAC, res.identity.family)
    }

    @Test
    fun legacyVerifiedStandardAndProvenTypes() {
        // 标准 0..6（AOSP）与已实证 7/10 走 VERIFIED_LEGACY
        val expected = mapOf(
            0 to ("SBC" to CodecFamily.SBC),
            1 to ("AAC" to CodecFamily.AAC),
            2 to ("aptX" to CodecFamily.APTX),
            3 to ("aptX-HD" to CodecFamily.APTX_HD),
            4 to ("LDAC" to CodecFamily.LDAC),
            5 to ("LC3" to CodecFamily.LC3),
            6 to ("Opus" to CodecFamily.OPUS),
            7 to ("aptX-Adaptive" to CodecFamily.APTX_ADAPTIVE),
            10 to ("LHDC V5" to CodecFamily.LHDC),
        )
        for ((type, nameAndFamily) in expected) {
            val res = CodecIdentityResolver.resolve(
                CodecDeviceFixtures.unverified(type, 2),
                listOf(CodecDeviceFixtures.selectableCaps(type, 2)),
            )
            assertEquals("type=$type", CodecIdentitySource.VERIFIED_LEGACY, res.identity.source)
            assertEquals("type=$type", nameAndFamily.first, res.identity.displayName)
            assertEquals("type=$type", nameAndFamily.second, res.identity.family)
            assertEquals("type=$type", emptySet<CodecIdentityIssue>(), res.issues)
        }
    }

    @Test
    fun phzCaps42ResolvesGenericLhdcByCapabilityHeuristic() {
        // 只有 vendor 区间 + ≥176.4kHz 能力签名才走 CAPABILITY_HEURISTIC
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.phzCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.phzType, CodecDeviceFixtures.phzCapsMask)),
        )

        assertEquals(CodecIdentitySource.CAPABILITY_HEURISTIC, res.identity.source)
        assertEquals("LHDC", res.identity.displayName)
        assertEquals(CodecFamily.LHDC, res.identity.family)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun nxCaps11StaysUnknownVendor() {
        // caps=11（最高 96k）不足以佐证 LHDC → vendor(14)/UNKNOWN
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.nxCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.nxType, CodecDeviceFixtures.nxCapsMask)),
        )

        assertEquals(CodecIdentitySource.UNKNOWN, res.identity.source)
        assertEquals("vendor(14)", res.identity.displayName)
        assertEquals(CodecFamily.UNKNOWN, res.identity.family)
        assertEquals(emptySet<CodecIdentityIssue>(), res.issues)
    }

    @Test
    fun opdCaps10WithoutSystemIdentityStaysUnknownEvenWithLhdcShapedCs1() {
        // cs1=0x8006 形似 LHDC 档，但无系统/extended/legacy 身份且 caps=10 → 不得凭 cs1 补认
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            opdSelectable(),
        )

        assertEquals(CodecIdentitySource.UNKNOWN, res.identity.source)
        assertEquals("vendor(18)", res.identity.displayName)
        assertEquals(CodecFamily.UNKNOWN, res.identity.family)
    }

    @Test
    fun iqooPadType8Caps2StaysUnknownVendor() {
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.iqooPadCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.iqooPadType, CodecDeviceFixtures.iqooPadCapsMask)),
        )

        assertEquals(CodecIdentitySource.UNKNOWN, res.identity.source)
        assertEquals("vendor(8)", res.identity.displayName)
        assertEquals(CodecFamily.UNKNOWN, res.identity.family)
    }

    @Test
    fun snapshotOverloadReturnsNullWhenCurrentNull() {
        val snapshot = CodecStateSnapshot(
            current = null,
            selectable = opdSelectable(),
            supportedTypes = listOf(SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3")),
        )

        assertNull(CodecIdentityResolver.resolve(snapshot))
    }

    @Test
    fun snapshotOverloadResolvesWithSupportedTypesAndExtended() {
        val snapshot = CodecStateSnapshot(
            current = CodecDeviceFixtures.opdCurrent,
            selectable = opdSelectable(),
            supportedTypes = listOf(SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3")),
            extendedIdentity = ExtendedCodecIdentity(codecId = 0x33L, codecName = "LDAC"),
        )

        val res = CodecIdentityResolver.resolve(snapshot)

        assertEquals(CodecIdentitySource.SYSTEM_TYPE, res!!.identity.source)
        assertEquals("LHDC V3", res.identity.displayName)
    }

    @Test
    fun snapshotOverloadUsesSelectableOnlyNeverLocalCapabilities() {
        // local 暴露 192 能力但 selectable 为空 → 不得借 local 提升身份
        val snapshot = CodecStateSnapshot(
            current = CodecDeviceFixtures.phzCurrent,
            selectable = emptyList(),
            localCapabilities = listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.phzType, CodecDeviceFixtures.phzCapsMask)),
        )

        val res = CodecIdentityResolver.resolve(snapshot)

        assertEquals(CodecIdentitySource.UNKNOWN, res!!.identity.source)
        assertEquals("vendor(19)", res.identity.displayName)
        assertEquals(CodecFamily.UNKNOWN, res.identity.family)
    }

    @Test
    fun issuesAccumulateAcrossFallbacks() {
        // 系统组冲突 + extended 无效 → 两个 issue 都保留，最终落到 legacy
        val res = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.unverified(4, 2),
            listOf(CodecDeviceFixtures.selectableCaps(4, 43)),
            supportedTypes = listOf(
                SupportedCodecType(4, 0x11L, "LDAC"),
                SupportedCodecType(4, 0x22L, "LHDC V3"),
            ),
            extendedIdentity = ExtendedCodecIdentity(codecId = 0x33L, codecName = "UNKNOWN CODEC"),
        )

        assertTrue(res.issues.contains(CodecIdentityIssue.SUPPORTED_TYPE_CONFLICT))
        assertTrue(res.issues.contains(CodecIdentityIssue.EXTENDED_INVALID))
        assertEquals(CodecIdentitySource.VERIFIED_LEGACY, res.identity.source)
        assertEquals("LDAC", res.identity.displayName)
    }
}
