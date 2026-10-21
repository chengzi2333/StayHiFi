package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetsTest {

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
    fun matchesEncoX3WithLhdc() {
        val hit = Presets.match("OPPO Enco X3", 10)
        assertEquals(golden, hit)
    }

    @Test
    fun rejectsRightNameWrongCodec() {
        assertNull(Presets.match("Enco X3", 4))
    }

    @Test
    fun rejectsWrongNameRightCodec() {
        assertNull(Presets.match("Sony XM5", 10))
    }

    @Test
    fun rejectsBothWrong() {
        assertNull(Presets.match("Sony XM5", 0))
    }
}
