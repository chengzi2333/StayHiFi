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
        // Task 44：5/6 为 AOSP 标准编号（已核对 BluetoothCodecConfig 源码：LC3/Opus）
        assertEquals("LC3", Masks.codecName(5))
        assertEquals("Opus", Masks.codecName(6))
    }

    @Test
    fun codecNameKnowsLhdc() {
        // Task 44：10 按 vivo 栈实证给出精确名（dumpsys 段头 "A2DP LHDC V5 State:"）
        assertEquals("LHDC V5", Masks.codecName(10))
    }

    @Test
    fun codecNameKnowsVivoAptxAdaptive() {
        // Task 44：7=aptX-Adaptive 为 vivo 栈真机实证（高通私有编号，AOSP 无公共常量）
        assertEquals("aptX-Adaptive", Masks.codecName(7))
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
}
