package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecConfigTest {

    private val golden = CodecConfig(
        codecType = 10,
        priority = 1000000,
        sampleRate = 32,
        bitsPerSample = 2,
        channelMode = 2,
        cs1 = 50888712L,
        cs2 = 0L,
        cs3 = 1543503872L,
        cs4 = 0L,
    )

    @Test
    fun jsonRoundTripPreservesAllNineFields() {
        assertEquals(golden, CodecConfig.fromJson(golden.toJson()))
    }

    @Test
    fun jsonRoundTripIsStable() {
        val once = CodecConfig.fromJson(golden.toJson())
        assertEquals(golden, CodecConfig.fromJson(once.toJson()))
    }

    @Test
    fun summaryContains192kHz() {
        assertTrue(golden.summary().contains("192kHz"))
    }

    @Test
    fun summaryFormat() {
        assertEquals("LHDC·192kHz·24bit·1000kbps", golden.summary())
    }

    @Test
    fun summaryStaysThreeSegmentsWhenCs1Unknown() {
        val unknown = golden.copy(cs1 = 12345L)
        assertEquals("LHDC·192kHz·24bit", unknown.summary())
    }
}
