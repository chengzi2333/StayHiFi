package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecSummaryFormatterTest {

    @Test
    fun opdFormatsSystemIdentityAsExpectedText() {
        // 黄金断言：OPD 系统 LHDC V3 + cs1=0x8006（500kbps 档）
        val identity = CodecIdentityResolver.resolve(
            CodecDeviceFixtures.opdCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.opdType, CodecDeviceFixtures.opdCapsMask)),
            supportedTypes = listOf(SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3")),
        ).identity

        val summary = CodecSummaryFormatter.format(CodecDeviceFixtures.opdCurrent, identity)

        assertEquals("LHDC V3", summary.codec)
        assertEquals("96kHz", summary.rate)
        assertEquals("24bit", summary.bits)
        assertEquals("500kbps", summary.quality)
        assertEquals("LHDC V3·96kHz·24bit·500kbps", summary.text())
        assertEquals(summary.text(), CodecSummaryFormatter.formatText(CodecDeviceFixtures.opdCurrent, identity))
    }

    @Test
    fun genericLhdcWithAdaptiveCs1ShowsAdaptiveQuality() {
        // PHZ type19+caps42 → generic LHDC；cs1=0x8009 低字节 9 = 自适应档
        val current = CodecDeviceFixtures.unverified(
            CodecDeviceFixtures.phzType, CodecDeviceFixtures.phzCurrentRate,
            bitsPerSample = CodecDeviceFixtures.phzBits, cs1 = 0x8009L,
        )
        val identity = CodecIdentityResolver.resolve(
            current,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.phzType, CodecDeviceFixtures.phzCapsMask)),
        ).identity

        val summary = CodecSummaryFormatter.format(current, identity)

        assertEquals(CodecFamily.LHDC, identity.family)
        assertEquals("自适应", summary.quality)
        assertEquals("LHDC·192kHz·24bit·自适应", summary.text())
    }

    @Test
    fun ldacFamilyKeepsAospQualityTiers() {
        val identity = CodecIdentity(
            nativeType = 4,
            family = CodecFamily.LDAC,
            displayName = "LDAC",
            source = CodecIdentitySource.VERIFIED_LEGACY,
        )
        val high = CodecDeviceFixtures.unverified(4, 2, cs1 = 1000L)
        val adaptive = CodecDeviceFixtures.unverified(4, 2, cs1 = 1003L)

        assertEquals("990kbps", CodecSummaryFormatter.format(high, identity).quality)
        assertEquals("LDAC·48kHz·16bit·990kbps", CodecSummaryFormatter.formatText(high, identity))
        assertEquals("自适应", CodecSummaryFormatter.format(adaptive, identity).quality)
    }

    @Test
    fun unknownFamilyNeverDecodesLhdcShapedCs1() {
        // UNKNOWN family 即使 cs1 形似 LHDC 也不解码 → 仅三段
        val identity = CodecIdentity(
            nativeType = CodecDeviceFixtures.opdType,
            family = CodecFamily.UNKNOWN,
            displayName = "vendor(18)",
            source = CodecIdentitySource.UNKNOWN,
        )

        val summary = CodecSummaryFormatter.format(CodecDeviceFixtures.opdCurrent, identity)

        assertNull(summary.quality)
        assertEquals("vendor(18)·96kHz·24bit", summary.text())
    }

    @Test
    fun nonLhdcFamiliesHaveNoQualityTier() {
        val identity = CodecIdentity(
            nativeType = 0,
            family = CodecFamily.SBC,
            displayName = "SBC",
            source = CodecIdentitySource.VERIFIED_LEGACY,
        )

        val summary = CodecSummaryFormatter.format(
            CodecDeviceFixtures.unverified(0, 2, cs1 = 0x8006L),
            identity,
        )

        assertNull(summary.quality)
        assertEquals("SBC·48kHz·16bit", summary.text())
    }

    @Test
    fun legacyCodecConfigSummaryStringIsUnchanged() {
        // 旧 summary() 路径不得被 formatter 改动（历史档案无身份上下文时仍走旧逻辑）
        assertEquals(
            "LHDC V5·192kHz·24bit·1000kbps",
            CodecDeviceFixtures.iqoo15Golden.summary(),
        )
    }
}
