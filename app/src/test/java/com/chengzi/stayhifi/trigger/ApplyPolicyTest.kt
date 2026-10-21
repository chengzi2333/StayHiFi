package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyPolicyTest {

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
    fun decideReturnsFalseWhenAlreadyAligned() {
        assertFalse(ApplyPolicy.decide(golden, golden))
    }

    @Test
    fun decideReturnsTrueWhenCs1Differs() {
        assertTrue(ApplyPolicy.decide(golden, golden.copy(cs1 = 0L)))
    }

    @Test
    fun decideReturnsTrueWhenRateDiffers() {
        assertTrue(ApplyPolicy.decide(golden, golden.copy(sampleRate = 8)))
    }

    @Test
    fun autoTargetTakesHighestBitFromCapabilityMask() {
        val current = golden.copy(sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.autoTarget(current, listOf(capability))

        assertEquals(32, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
        assertEquals(1_000_000, target.priority)
    }

    @Test
    fun autoTargetKeepsCodecFieldsFromCurrent() {
        val current = golden.copy(sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 1,
            cs1 = 12345L, cs2 = 1L, cs3 = 2L, cs4 = 3L,
        )

        val target = ApplyPolicy.autoTarget(current, listOf(capability))

        assertEquals(10, target.codecType)
        assertEquals(2, target.channelMode)
        assertEquals(50888712L, target.cs1)
        assertEquals(0L, target.cs2)
        assertEquals(1543503872L, target.cs3)
        assertEquals(0L, target.cs4)
    }

    @Test
    fun autoTargetKeepsCurrentRateBitsWhenCodecMissingInSelectable() {
        val current = golden.copy(sampleRate = 2, priority = 0)
        val otherCodec = CodecConfig(
            codecType = 4, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.autoTarget(current, listOf(otherCodec))

        assertEquals(2, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
        assertEquals(1_000_000, target.priority)
    }

    // matches：与服务端 CodecService 生效语义一致 —— codecType + rate(target≠0) + bits(target≠0) + cs1

    @Test
    fun matchesIgnoresStackManagedFields() {
        // priority/cs2~cs4 不同但关键字段相同 → 视为已生效（协议栈可能规范化栈管理字段）
        val actual = golden.copy(cs2 = 7L, cs3 = 42L, cs4 = 1L, priority = 0)
        assertTrue(ApplyPolicy.matches(golden, actual))
    }

    @Test
    fun matchesReturnsFalseWhenCs1Differs() {
        // cs1 是档案/PRESET 目标指定的音质档，栈未套用则视为未生效
        val actual = golden.copy(cs1 = 33914889L)
        assertFalse(ApplyPolicy.matches(golden, actual))
    }

    @Test
    fun matchesReturnsFalseWhenRateDiffers() {
        val actual = golden.copy(sampleRate = 8)
        assertFalse(ApplyPolicy.matches(golden, actual))
    }

    @Test
    fun matchesReturnsFalseWhenBitsDiffers() {
        val actual = golden.copy(bitsPerSample = 1)
        assertFalse(ApplyPolicy.matches(golden, actual))
    }

    @Test
    fun matchesReturnsFalseWhenCodecTypeDiffers() {
        val actual = golden.copy(codecType = 4)
        assertFalse(ApplyPolicy.matches(golden, actual))
    }

    @Test
    fun matchesIgnoresRateAndBitsWhenTargetMasksAreZero() {
        val target = golden.copy(sampleRate = 0, bitsPerSample = 0)
        val actual = golden.copy(sampleRate = 8, bitsPerSample = 1)
        assertTrue(ApplyPolicy.matches(target, actual))
    }
}
