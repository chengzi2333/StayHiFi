package com.chengzi.btmax.codec

import org.junit.Assert.assertEquals
import org.junit.Test

class NegotiationTest {

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
    fun descendingRatesFromGolden() {
        val candidates = Negotiation.descending(golden)
        assertEquals(3, candidates.size)
        assertEquals(32, candidates[0].sampleRate)
        assertEquals(8, candidates[1].sampleRate)
        assertEquals(2, candidates[2].sampleRate)
    }

    @Test
    fun descendingKeepsOtherFields() {
        for (c in Negotiation.descending(golden)) {
            assertEquals(2, c.bitsPerSample)
            assertEquals(10, c.codecType)
            assertEquals(1000000, c.priority)
            assertEquals(2, c.channelMode)
            assertEquals(50888712L, c.cs1)
            assertEquals(0L, c.cs2)
            assertEquals(1543503872L, c.cs3)
            assertEquals(0L, c.cs4)
        }
    }

    @Test
    fun baseRateTwoYieldsSingleCandidate() {
        val candidates = Negotiation.descending(golden.copy(sampleRate = 2))
        assertEquals(1, candidates.size)
        assertEquals(2, candidates[0].sampleRate)
    }

    @Test
    fun baseRateEightYieldsTwoCandidates() {
        val candidates = Negotiation.descending(golden.copy(sampleRate = 8))
        assertEquals(2, candidates.size)
        assertEquals(8, candidates[0].sampleRate)
        assertEquals(2, candidates[1].sampleRate)
    }
}
