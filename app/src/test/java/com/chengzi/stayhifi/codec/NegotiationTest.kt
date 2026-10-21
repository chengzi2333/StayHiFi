package com.chengzi.stayhifi.codec

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
        // 对抗审查 C3：RATE_STEPS 必须补全 16(176.4k)/4(88.2k)/1(44.1k)，base 自身恒为首选
        val candidates = Negotiation.descending(golden)
        assertEquals(6, candidates.size)
        assertEquals(listOf(32, 16, 8, 4, 2, 1), candidates.map { it.sampleRate })
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
    fun baseRateSixteenYieldsFiveCandidates() {
        // 对抗审查 C3 场景 a：176.4k（=16）档案此前被静默降到 96k，修复后 base 自身必须入选
        val candidates = Negotiation.descending(golden.copy(sampleRate = 16))
        assertEquals(listOf(16, 8, 4, 2, 1), candidates.map { it.sampleRate })
    }

    @Test
    fun baseRateOneYieldsSingleCandidate() {
        // 对抗审查 C3 场景 b：44.1k（=1）档案此前候选为空直接误报失败，修复后 base 自身兜底
        val candidates = Negotiation.descending(golden.copy(sampleRate = 1))
        assertEquals(1, candidates.size)
        assertEquals(1, candidates[0].sampleRate)
    }

    @Test
    fun baseRateTwoIncludesFortyFourKFallback() {
        val candidates = Negotiation.descending(golden.copy(sampleRate = 2))
        assertEquals(listOf(2, 1), candidates.map { it.sampleRate })
    }
}
