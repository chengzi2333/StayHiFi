package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.CodecDeviceFixtures
import com.chengzi.stayhifi.codec.Negotiation
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
    fun deriveTargetCapsNxCaps11Stays96k() {
        // Task 4 回归：NX type14 caps=11（最高 96k），身份识别成功不得新增 192 目标
        val target = ApplyPolicy.deriveTarget(
            CodecDeviceFixtures.nxCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.nxType, CodecDeviceFixtures.nxCapsMask)),
        )

        assertEquals(8, target.sampleRate)
    }

    @Test
    fun deriveTargetCapsOpdCaps10Stays96k() {
        // Task 4 回归：OPD type18 caps=10（最高 96k），即使系统身份为 LHDC V3 也不变
        val target = ApplyPolicy.deriveTarget(
            CodecDeviceFixtures.opdCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.opdType, CodecDeviceFixtures.opdCapsMask)),
        )

        assertEquals(8, target.sampleRate)
    }

    @Test
    fun deriveTargetCapsPhzCaps42Keeps192() {
        // Task 4 回归：PHZ type19 caps=42（含 192k 位），目标维持 192kHz
        val target = ApplyPolicy.deriveTarget(
            CodecDeviceFixtures.phzCurrent,
            listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.phzType, CodecDeviceFixtures.phzCapsMask)),
        )

        assertEquals(32, target.sampleRate)
    }

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

    // assessTarget：同条目能力目标与完整降档链（Task B1）

    private val assessCurrent = CodecConfig(
        codecType = 10, priority = 0,
        sampleRate = 2, bitsPerSample = 1, channelMode = 2,
        cs1 = 50888712L, cs2 = 0L, cs3 = 1543503872L, cs4 = 0L,
    )

    /** 条目 A：192kHz + 16bit（channel 兼容 current）。 */
    private val cap192Bits16 = CodecConfig(
        codecType = 10, priority = 0,
        sampleRate = 32, bitsPerSample = 1, channelMode = 2,
        cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
    )

    /** 条目 B：96kHz + 24bit（channel 兼容 current）。 */
    private val cap96Bits24 = CodecConfig(
        codecType = 10, priority = 0,
        sampleRate = 8, bitsPerSample = 2, channelMode = 2,
        cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
    )

    @Test
    fun completeTargetNeverCombinesDifferentCapabilityEntries() {
        // 条目 A=192+16，条目 B=96+24；目标必须整条来自同一条目，不得拼出 192+24
        val result = ApplyPolicy.assessTarget(assessCurrent, listOf(cap192Bits16, cap96Bits24))

        assertEquals(TargetAssessment.Status.COMPLETE, result.status)
        assertEquals(32, result.target?.sampleRate)
        assertEquals(1, result.target?.bitsPerSample)
        assertFalse(result.candidates.any { it.sampleRate == 32 && it.bitsPerSample == 2 })
        assertEquals(listOf(32), result.candidates.map { it.sampleRate })
    }

    @Test
    fun completeDescendingContainsOnlySameEntryReportedRates() {
        // 唯一条目报告 192/48（掩码 34）+ 24bit；不得从 192 降出 176.4/96/88.2/44.1
        val soleEntry = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 34, bitsPerSample = 2, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )

        val result = ApplyPolicy.assessTarget(assessCurrent, listOf(soleEntry))

        assertEquals(TargetAssessment.Status.COMPLETE, result.status)
        assertEquals(listOf(32, 2), result.candidates.map { it.sampleRate })
        assertTrue(result.candidates.all { it.bitsPerSample == 2 })
    }

    @Test
    fun incompleteCurrentHasNoTargetOrCandidates() {
        listOf(0, 3, 43, 64, -1).forEach { rate ->
            val result = ApplyPolicy.assessTarget(assessCurrent.copy(sampleRate = rate), listOf(cap192Bits16))
            assertEquals(TargetAssessment.Status.INCOMPLETE_CURRENT, result.status)
            assertEquals(null, result.target)
            assertTrue(result.candidates.isEmpty())
        }
        // bits=0/3/未知值同样不可写
        listOf(0, 3, 8).forEach { bits ->
            val result = ApplyPolicy.assessTarget(assessCurrent.copy(bitsPerSample = bits), listOf(cap192Bits16))
            assertEquals(TargetAssessment.Status.INCOMPLETE_CURRENT, result.status)
            assertEquals(null, result.target)
            assertTrue(result.candidates.isEmpty())
        }
        // channel=0/3/4 不可写（AOSP 仅 MONO=1/STEREO=2）
        listOf(0, 3, 4).forEach { channel ->
            val result = ApplyPolicy.assessTarget(assessCurrent.copy(channelMode = channel), listOf(cap192Bits16))
            assertEquals(TargetAssessment.Status.INCOMPLETE_CURRENT, result.status)
            assertEquals(null, result.target)
            assertTrue(result.candidates.isEmpty())
        }
    }

    @Test
    fun capabilityDegradedWhenCurrentCodecMissingFromSelectable() {
        val otherCodec = cap192Bits16.copy(codecType = 4)
        val selectable = listOf(otherCodec)

        val result = ApplyPolicy.assessTarget(assessCurrent, selectable)

        assertEquals(TargetAssessment.Status.CAPABILITY_DEGRADED, result.status)
        // 旧路径：target = deriveTarget，candidates = 单参 descending
        val oldTarget = ApplyPolicy.deriveTarget(assessCurrent, selectable)
        assertEquals(oldTarget, result.target)
        assertEquals(Negotiation.descending(oldTarget), result.candidates)
        assertFalse(ApplyPolicy.isAtHighest(result, assessCurrent))
    }

    @Test
    fun capabilityDegradedWhenSelectableIsEmpty() {
        val result = ApplyPolicy.assessTarget(assessCurrent, emptyList())

        assertEquals(TargetAssessment.Status.CAPABILITY_DEGRADED, result.status)
        val oldTarget = ApplyPolicy.deriveTarget(assessCurrent, emptyList())
        assertEquals(oldTarget, result.target)
        assertEquals(Negotiation.descending(oldTarget), result.candidates)
        assertFalse(ApplyPolicy.isAtHighest(result, assessCurrent))
    }

    @Test
    fun capabilityDegradedWhenCapabilityIsAllZero() {
        // 条目在但 rate/bits 全 0（AOSP NONE）：无可证明组合，回落旧路径
        val zeroCap = cap192Bits16.copy(sampleRate = 0, bitsPerSample = 0)

        val result = ApplyPolicy.assessTarget(assessCurrent, listOf(zeroCap))

        assertEquals(TargetAssessment.Status.CAPABILITY_DEGRADED, result.status)
        val oldTarget = ApplyPolicy.deriveTarget(assessCurrent, listOf(zeroCap))
        assertEquals(oldTarget, result.target)
        assertEquals(Negotiation.descending(oldTarget), result.candidates)
        assertFalse(ApplyPolicy.isAtHighest(result, assessCurrent))
    }

    @Test
    fun capsMaskTenAndElevenDeriveOnly96k() {
        // capsRateMask=10（8|2）与 11（8|2|1）最高位都是 8（96kHz）：同条目可证明 → COMPLETE，
        // 身份识别成功不得派生 192 目标（Task 4 回归），降档链只含条目报告的 96/48
        listOf(10, 11).forEach { mask ->
            val cap = cap192Bits16.copy(sampleRate = mask, bitsPerSample = 2)
            val result = ApplyPolicy.assessTarget(assessCurrent, listOf(cap))

            assertEquals(TargetAssessment.Status.COMPLETE, result.status)
            assertEquals(8, result.target?.sampleRate)
            assertEquals(2, result.target?.bitsPerSample)
            // 降档链只含条目逐位报告的档位：mask=10 → [8,2]，mask=11 另含 44.1k
            val expectedChain = listOf(8, 2) + if (mask and 1 == 1) listOf(1) else emptyList()
            assertEquals(expectedChain, result.candidates.map { it.sampleRate })
        }
    }

    @Test
    fun channelIncompatibleHighEntryDoesNotOverrideLowerCompatibleEntry() {
        // 条目 A=192+24 仅 STEREO（channel=2），current 为 MONO（channel=1）→ 不兼容；
        // 条目 B=96+24 MONO 兼容 → COMPLETE 目标必须是 96+24，不得被 A 覆盖
        val stereoOnlyHigh = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 32, bitsPerSample = 2, channelMode = 2,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )
        val monoLow = CodecConfig(
            codecType = 10, priority = 0,
            sampleRate = 8, bitsPerSample = 2, channelMode = 1,
            cs1 = 0L, cs2 = 0L, cs3 = 0L, cs4 = 0L,
        )
        val monoCurrent = assessCurrent.copy(channelMode = 1)

        val result = ApplyPolicy.assessTarget(monoCurrent, listOf(stereoOnlyHigh, monoLow))

        assertEquals(TargetAssessment.Status.COMPLETE, result.status)
        assertEquals(8, result.target?.sampleRate)
        assertEquals(2, result.target?.bitsPerSample)
        assertEquals(1, result.target?.channelMode)
    }

    @Test
    fun isAtHighestTrueOnlyWhenCompleteAndMatches() {
        val complete = ApplyPolicy.assessTarget(assessCurrent, listOf(cap192Bits16))
        assertEquals(TargetAssessment.Status.COMPLETE, complete.status)

        // COMPLETE 且 current 已达目标（cs1 已是黄金档、rate/bits 与 target 一致）
        val aligned = assessCurrent.copy(sampleRate = 32, bitsPerSample = 1)
        assertTrue(ApplyPolicy.isAtHighest(complete, aligned))
        // current 未达 → false
        assertFalse(ApplyPolicy.isAtHighest(complete, assessCurrent))

        // 非 COMPLETE 一律 false（即使字段恰好相等）
        val degraded = ApplyPolicy.assessTarget(assessCurrent, emptyList())
        val degradedAligned = assessCurrent.copy(priority = 1_000_000)
        assertFalse(ApplyPolicy.isAtHighest(degraded, degradedAligned))
    }
}
