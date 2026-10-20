package com.chengzi.btmax.codec

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
}
