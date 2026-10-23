package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Test

class MasksTest {

    @Test
    fun rateHzDecodesSingleBits() {
        assertEquals("44.1kHz", Masks.rateHz(1))
        assertEquals("48kHz", Masks.rateHz(2))
        assertEquals("88.2kHz", Masks.rateHz(4))
        assertEquals("96kHz", Masks.rateHz(8))
        assertEquals("176.4kHz", Masks.rateHz(16))
        assertEquals("192kHz", Masks.rateHz(32))
    }

    @Test
    fun rateHzRejectsMultiBitMask() {
        // 43 = 44.1k+48k+96k+192k — single-value decoder must not guess
        assertEquals("unknown", Masks.rateHz(43))
    }

    @Test
    fun bitsDepthDecodesKnownBits() {
        assertEquals("16bit", Masks.bitsDepth(1))
        assertEquals("24bit", Masks.bitsDepth(2))
    }

    @Test
    fun codecNameKnowsStandardCodecs() {
        assertEquals("SBC", Masks.codecName(0))
        assertEquals("AAC", Masks.codecName(1))
        assertEquals("aptX", Masks.codecName(2))
        assertEquals("aptX-HD", Masks.codecName(3))
        assertEquals("LDAC", Masks.codecName(4))
    }

    @Test
    fun codecNameKnowsLhdc() {
        assertEquals("LHDC", Masks.codecName(10))
    }

    @Test
    fun codecNameFallsBackToVendor() {
        assertEquals("vendor(99)", Masks.codecName(99))
    }

    @Test
    fun qualityLabelDecodesLdacTiers() {
        // AOSP BluetoothCodecConfig 标准值
        assertEquals("990kbps", Masks.qualityLabel(4, 1000L))
        assertEquals("660kbps", Masks.qualityLabel(4, 1001L))
        assertEquals("330kbps", Masks.qualityLabel(4, 1002L))
        assertEquals("自适应", Masks.qualityLabel(4, 1003L))
    }

    @Test
    fun qualityLabelDecodesLhdcVendorTiers() {
        // 厂商私有实测值（OPPO/vivo 系栈）
        assertEquals("1000kbps", Masks.qualityLabel(10, 50888712L))
        assertEquals("自适应", Masks.qualityLabel(10, 33914889L))
    }

    @Test
    fun qualityLabelReturnsNullForUnknownCs1() {
        assertEquals(null, Masks.qualityLabel(10, 0L))
        assertEquals(null, Masks.qualityLabel(4, 999L))
    }

    @Test
    fun qualityLabelReturnsNullForUnknownCodec() {
        assertEquals(null, Masks.qualityLabel(0, 1000L))
        assertEquals(null, Masks.qualityLabel(99, 50888712L))
    }

    @Test
    fun codecNameWithCapsAcceptsLhdcOn192kBit() {
        // ColorOS 新栈 0x13 + 仅 192kHz 位（32）→ LHDC 能力签名成立
        assertEquals("LHDC", Masks.codecName(0x13, 32))
    }

    @Test
    fun codecNameWithCapsAcceptsLhdcOnMultiBitMask() {
        // 43 = 32+8+2+1 多比特叠加，仍含 32 位 → and 判位必须认
        assertEquals("LHDC", Masks.codecName(0x13, 43))
    }

    @Test
    fun codecNameWithCapsRejectsLhdcBelow176k() {
        // 最高 96kHz（8）→ 佐证失败，回落 vendor 显示
        assertEquals("vendor(19)", Masks.codecName(0x13, 8))
    }

    @Test
    fun codecNameWithCapsRejectsOutsideVendorRange() {
        // 区间边界：0x0F 与 0x20 即使带 192k 位也不认（vendor(N) 为十进制，0x20=32）
        assertEquals("vendor(15)", Masks.codecName(0x0F, 32))
        assertEquals("vendor(32)", Masks.codecName(0x20, 32))
    }

    @Test
    fun capsRateMaskAggregatesSelectableEntries() {
        // 两条 19 号条目（48k + 192k）位或 = 34
        val selectable = listOf(
            CodecConfig(19, 0, 2, 0, 0, 0L, 0L, 0L, 0L),
            CodecConfig(19, 0, 32, 0, 0, 0L, 0L, 0L, 0L),
        )
        assertEquals(34, Masks.capsRateMask(19, selectable))
    }
}
