package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // 合法 base 前置检查（Task B1）：非法 base 返回空链，合法 base 结果不变

    @Test
    fun singleArgDescendingRejectsInvalidBaseRate() {
        // rate=0（AOSP NONE）、多比特 3/43、未知 64 均非法
        listOf(0, 3, 43, 64, -1).forEach { rate ->
            assertTrue(Negotiation.descending(golden.copy(sampleRate = rate)).isEmpty())
        }
    }

    @Test
    fun singleArgDescendingRejectsInvalidBaseBitsAndChannel() {
        // bits 仅 {1,2,4}、channel 仅 {1,2}（AOSP MONO=1/STEREO=2）
        listOf(0, 3, 8).forEach { bits ->
            assertTrue(Negotiation.descending(golden.copy(bitsPerSample = bits)).isEmpty())
        }
        listOf(0, 3, 4).forEach { channel ->
            assertTrue(Negotiation.descending(golden.copy(channelMode = channel)).isEmpty())
        }
    }

    @Test
    fun singleArgDescendingKeepsResultsForValidBases() {
        // 合法 base（含 1/2/4/8/16/32 各档）结果与旧实现完全一致
        listOf(1, 2, 4, 8, 16, 32).forEach { rate ->
            val candidates = Negotiation.descending(golden.copy(sampleRate = rate))
            val expectedRates = listOf(32, 16, 8, 4, 2, 1).filter { it <= rate }
            assertEquals(expectedRates, candidates.map { it.sampleRate })
        }
    }

    // 双参 descending（Task B1）：每个 rate 必须由同一条目证明（rate+bits+channel+codecType）

    private val entry192and48Bits24 = CodecConfig(
        codecType = 10, priority = 0,
        sampleRate = 34, bitsPerSample = 2, channelMode = 2,
        cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
    )

    @Test
    fun twoArgDescendingKeepsOnlySameEntryProvenRates() {
        // base=192/24bit：仅 192/48 被同一条目证明 → 不得降出 176.4/96/88.2/44.1
        val candidates = Negotiation.descending(golden, listOf(entry192and48Bits24))
        assertEquals(listOf(32, 2), candidates.map { it.sampleRate })
        assertTrue(candidates.all { it.bitsPerSample == 2 })
    }

    @Test
    fun twoArgDescendingRejectsUnprovenBase() {
        // base 自身（rate=16）未被任何同条目证明 → 空链
        val cap32Only = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 32, bitsPerSample = 2, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )
        assertTrue(Negotiation.descending(golden.copy(sampleRate = 16), listOf(cap32Only)).isEmpty())
        // base 的 rate 与 bits 分属两条目（条目 A 有 16 但只有 16bit，条目 B 有 24bit 但只有 8）→ 无同条目证明 → 空链
        val cap16Bits16 = cap32Only.copy(sampleRate = 16, bitsPerSample = 1)
        val cap8Bits24 = cap32Only.copy(sampleRate = 8)
        assertTrue(Negotiation.descending(golden.copy(sampleRate = 16), listOf(cap16Bits16, cap8Bits24)).isEmpty())
    }

    @Test
    fun twoArgDescendingRejectsInvalidBaseAndMismatchedFields() {
        // 非法 base 同样空链
        assertTrue(Negotiation.descending(golden.copy(sampleRate = 0), listOf(entry192and48Bits24)).isEmpty())
        assertTrue(Negotiation.descending(golden.copy(bitsPerSample = 3), listOf(entry192and48Bits24)).isEmpty())
        // 条目 bits 不含 base bits（条目仅 24bit，base 16bit）→ 空链
        val base16bit = golden.copy(bitsPerSample = 1)
        assertTrue(Negotiation.descending(base16bit, listOf(entry192and48Bits24)).isEmpty())
        // 条目 channel 不含 base channel → 空链
        val monoEntry = entry192and48Bits24.copy(channelMode = 1)
        assertTrue(Negotiation.descending(golden, listOf(monoEntry)).isEmpty())
        // 条目 codecType 不同 → 空链
        val otherCodec = entry192and48Bits24.copy(codecType = 4)
        assertTrue(Negotiation.descending(golden, listOf(otherCodec)).isEmpty())
    }
}
