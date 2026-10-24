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
    fun decideReturnsFalseWhenCurrentIsStackNormalizedStayHiFi() {
        // 对抗审查 I4（报告编号）：协议栈会规范化 priority/cs2~cs4；
        // current 是满血态的栈读回值（关键字段一致）→ 跳过写入，不再每次重连重协商
        val current = golden.copy(priority = 0, cs2 = 7L, cs3 = 42L, cs4 = 1L)
        assertFalse(ApplyPolicy.decide(current, golden))
    }

    @Test
    fun deriveTargetTakesHighestBitFromCapabilityMask() {
        val current = golden.copy(sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(32, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
        assertEquals(1_000_000, target.priority)
    }

    @Test
    fun deriveTargetKeepsCodecFieldsFromCurrent() {
        // Task 27：codecType/channelMode/cs2~cs4 继承 current；cs1 由档位表接管（codecType=10 → 黄金值，巧合同值）
        val current = golden.copy(sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 1,
            cs1 = 12345L, cs2 = 1L, cs3 = 2L, cs4 = 3L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(10, target.codecType)
        assertEquals(2, target.channelMode)
        assertEquals(50888712L, target.cs1)
        assertEquals(0L, target.cs2)
        assertEquals(1543503872L, target.cs3)
        assertEquals(0L, target.cs4)
    }

    @Test
    fun deriveTargetForcesLdacQualityHigh() {
        // LDAC（codecType=4，cs1=1003 自适应）→ 档位表强制 AOSP 标准 LDAC_QUALITY_HIGH（1000 = 990kbps）
        val current = golden.copy(codecType = 4, cs1 = 1003L, sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 4, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(1000L, target.cs1)
        assertEquals(32, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
        assertEquals(1_000_000, target.priority)
    }

    @Test
    fun deriveTargetForcesLhdcVivoBestCs1() {
        // Task 27 档位表：LHDC-vivo（codecType=10）cs1 强制黄金配置实证值 50888712（1000kbps 音质优先），
        // 即使 current 是自适应档（cs1=0）也拉满——原 autoTarget"保持 current cs1"语义作废
        val current = golden.copy(cs1 = 0L, sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(50888712L, target.cs1)
        assertEquals(32, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
    }

    @Test
    fun deriveTargetKeepsCs1ForUnknownCodec() {
        // 档位表之外的编码器（codecType=2）无可信档位数据，cs1 保持 current，不臆造
        val current = golden.copy(codecType = 2, cs1 = 33914889L, sampleRate = 2, priority = 0)
        val capability = CodecConfig(
            codecType = 2, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(33914889L, target.cs1)
        assertEquals(32, target.sampleRate)
    }

    @Test
    fun deriveTargetKeepsCurrentBitsWhenCapabilityBitsAreNone() {
        // AOSP BITS_PER_SAMPLE_NONE=0 是“未指定”，不是可写入的最高位深；
        // 不能把 24bit 现状改成 0，再借 matches 的 0 通配语义宣称已拉满。
        val current = golden.copy(codecType = 2, sampleRate = 2, bitsPerSample = 2)
        val capability = CodecConfig(
            codecType = 2, priority = 0,
            sampleRate = 32, bitsPerSample = 0, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(2, target.bitsPerSample)
    }

    @Test
    fun deriveTargetKeepsCurrentRateWhenCapabilityRateIsNone() {
        // SAMPLE_RATE_NONE=0 同样不是可协商档位；保留现状，避免派生出空降档序列。
        val current = golden.copy(codecType = 2, sampleRate = 2, bitsPerSample = 2)
        val capability = CodecConfig(
            codecType = 2, priority = 0,
            sampleRate = 0, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(capability))

        assertEquals(2, target.sampleRate)
    }

    @Test
    fun deriveTargetKeepsCurrentRateBitsWhenCodecMissingInSelectable() {
        val current = golden.copy(sampleRate = 2, priority = 0)
        val otherCodec = CodecConfig(
            codecType = 4, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val target = ApplyPolicy.deriveTarget(current, listOf(otherCodec))

        assertEquals(2, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
        assertEquals(1_000_000, target.priority)
    }

    @Test
    fun fullBarsSemanticsViaDeriveTargetAndMatches() {
        // Task 27 两态：fullBars = matches(deriveTarget(current, selectable), current)。
        // 统一路径下有无档案判定一致——本用例即"非档案设备统一路径"的判定语义。
        val capability = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 43, bitsPerSample = 3, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )
        val target = ApplyPolicy.deriveTarget(golden, listOf(capability))
        assertTrue(ApplyPolicy.matches(target, golden))

        // 未拉满态：cs1 还是自适应档 → 与派生目标不等 → 未达本机最高
        val adaptive = golden.copy(cs1 = 0L)
        assertFalse(ApplyPolicy.matches(target, adaptive))
        assertTrue(ApplyPolicy.decide(adaptive, target))
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
        // cs1 是派生目标指定的音质档（档位表），栈未套用则视为未生效
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
