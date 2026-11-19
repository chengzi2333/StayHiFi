package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.2.4 用户反馈回归：nubia Z80 Ultra（Android 17 / NebulaAIOS 3.0）系统把
 * type=7 报成 "LHDCv5"（无空格），旧 [codecFamilyOf] 只认 "LHDC"/"LHDC " 前缀，
 * 归 UNKNOWN → 摘要第四段 quality 恒 null → 首页「码率」格显示「—」。
 *
 * 真机日志（2026-11-19 反馈）：
 *   identity={name=LHDCv5 family=UNKNOWN source=SYSTEM_TYPE nativeType=7}
 *   current={codecType:7, sampleRate:8, bitsPerSample:2, cs1:32777(0x8009)}
 *   当前配置: LHDCv5·96kHz·24bit
 */
class LhdcNameFamilyTest {

    // 反馈日志原值：cs1=32777=0x8000|9
    private val nubiaCs1 = 32777L

    @Test
    fun familyAcceptsLhdcWithoutSpaceSeparator() {
        assertEquals(CodecFamily.LHDC, codecFamilyOf("LHDCv5"))
        assertEquals(CodecFamily.LHDC, codecFamilyOf("LHDCV5"))
        assertEquals(CodecFamily.LHDC, codecFamilyOf("LHDC V5"))
        assertEquals(CodecFamily.LHDC, codecFamilyOf("LHDC-V3"))
        assertEquals(CodecFamily.LHDC, codecFamilyOf("LHDC 5.0"))
        assertEquals(CodecFamily.LHDC, codecFamilyOf("LHDC"))
    }

    @Test
    fun familyDoesNotSwallowUnrelatedLhdcPrefixNames() {
        // 非版本后缀不硬认，保持 UNKNOWN（诚实原则）
        assertEquals(CodecFamily.UNKNOWN, codecFamilyOf("LHDCX"))
        assertEquals(CodecFamily.UNKNOWN, codecFamilyOf("LHDC CODEC"))
    }

    @Test
    fun displayNameIsCanonicalizedForReadability() {
        assertEquals("LHDC V5", canonicalCodecDisplayName("LHDCv5"))
        assertEquals("LHDC V5", canonicalCodecDisplayName("LHDC V5"))
        assertEquals("LDAC", canonicalCodecDisplayName("LDAC"))
        assertEquals("aptX-Adaptive", canonicalCodecDisplayName("aptX-Adaptive"))
    }

    @Test
    fun versionSuffixUsedForConflictComparison() {
        assertEquals("V5", lhdcVersionOf("LHDCv5"))
        assertEquals("V5", lhdcVersionOf("LHDC V5"))
        assertEquals("V3", lhdcVersionOf("LHDC-V3"))
        assertNull(lhdcVersionOf("LHDC"))
        assertNull(lhdcVersionOf("LHDCX"))
    }

    @Test
    fun nubiaSystemIdentityYieldsQualityTier() {
        // 系统 supported types 报 type=7 / "LHDCv5"，legacy 表 type=7 是 aptX-Adaptive
        val resolution = CodecIdentityResolver.resolve(
            current = CodecConfig(
                codecType = 7, priority = 1000000, sampleRate = 8, bitsPerSample = 2,
                channelMode = 2, cs1 = nubiaCs1, cs2 = 0, cs3 = 1543503872L, cs4 = 0,
            ),
            selectable = listOf(
                CodecDeviceFixtures.unverified(7, 11, bitsPerSample = 3, cs1 = 5002L),
            ),
            supportedTypes = listOf(SupportedCodecType(7, 327307049727L, "LHDCv5")),
        )

        assertEquals(CodecFamily.LHDC, resolution.identity.family)
        assertEquals("LHDC V5", resolution.identity.displayName)
        assertEquals(CodecIdentitySource.SYSTEM_TYPE, resolution.identity.source)
        // 系统名与 legacy 表（type7=aptX-Adaptive）确实冲突：如实留证，不改身份
        assertEquals(setOf(CodecIdentityIssue.SYSTEM_LEGACY_CONFLICT), resolution.issues)

        val summary = CodecSummaryFormatter.format(
            CodecConfig(
                codecType = 7, priority = 1000000, sampleRate = 8, bitsPerSample = 2,
                channelMode = 2, cs1 = nubiaCs1, cs2 = 0, cs3 = 1543503872L, cs4 = 0,
            ),
            resolution.identity,
        )
        // 0x8009 → magic 0x8000 + 低字节 9 = 自适应（与实况 qualityMode=ABR 一致）
        assertEquals("自适应", summary.quality)
        assertEquals("LHDC V5·96kHz·24bit·自适应", summary.text())
    }

    @Test
    fun lhdcQualityTierStillRequiresMagicBits() {
        assertEquals("自适应", Masks.qualityLabel(CodecFamily.LHDC, 0x8009L))
        assertEquals("400kbps", Masks.qualityLabel(CodecFamily.LHDC, 0x8005L))
        // 缺 magic 不解码，仍显示「—」
        assertNull(Masks.qualityLabel(CodecFamily.LHDC, 0x0009L))
    }

    @Test
    fun nubiaSectionNameMatchesCanonicalIdentity() {
        // 系统名叫 "LHDCv5"、dumpsys 段头也是 "A2DP LHDCv5 State:"，实况码率必须仍可读到
        val dump = """
            A2DP LHDCv5 State:
              LHDC quality mode : ABR
              LHDC transmission bitrate (Kbps) :400000
        """.trimIndent()
        val identity = CodecIdentity(
            nativeType = 7,
            family = CodecFamily.LHDC,
            displayName = canonicalCodecDisplayName("LHDCv5"),
            source = CodecIdentitySource.SYSTEM_TYPE,
        )
        val result = BtStateParser.parseResult(dump, identity)

        assertEquals(BtStateParseStatus.AVAILABLE, result.status)
        assertEquals("LHDCv5", result.state?.codec)
        assertEquals(400, result.state?.bitrateKbps)
        assertEquals("ABR", result.state?.qualityMode)
    }
}
