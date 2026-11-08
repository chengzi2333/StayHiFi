package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ApplyCandidateRunnerTest {

    private val runner = ApplyCandidateRunner()
    private val current = CodecConfig(
        codecType = 2,
        priority = 0,
        sampleRate = 2,
        bitsPerSample = 1,
        channelMode = 2,
        cs1 = 100L,
        cs2 = 1L,
        cs3 = 2L,
        cs4 = 3L,
    )

    @Test
    fun incompleteCurrentDoesNotWriteOrRecordOrigin() = runBlocking {
        var originCalls = 0
        var writeCalls = 0
        val assessment = TargetAssessment(
            TargetAssessment.Status.INCOMPLETE_CURRENT,
            target = null,
            candidates = emptyList(),
        )

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = { originCalls++ },
            writeVerified = { writeCalls++; true },
        )

        assertEquals(ApplyRunResult.IncompleteCurrent, result)
        assertEquals(0, originCalls)
        assertEquals(0, writeCalls)
    }

    @Test
    fun degradedStillUsesLegacyCandidatesButIsNeverFullBars() = runBlocking {
        var originCalls = 0
        var writeCalls = 0
        val aligned = current.copy(priority = 1_000_000)
        val assessment = TargetAssessment(
            TargetAssessment.Status.CAPABILITY_DEGRADED,
            target = aligned,
            candidates = listOf(aligned, current.copy(sampleRate = 1)),
        )

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = { originCalls++ },
            writeVerified = { writeCalls++; true },
        )

        assertEquals(ApplyRunResult.AlreadyAligned(aligned), result)
        assertEquals(0, originCalls)
        assertEquals(0, writeCalls)
        assertFalse(ApplyPolicy.isAtHighest(assessment, current))
    }

    @Test
    fun failedCandidateContinuesAndSuccessStopsAfterOneOriginRecord() = runBlocking {
        var originCalls = 0
        val writes = mutableListOf<CodecConfig>()
        val first = current.copy(sampleRate = 4)
        val second = current.copy(bitsPerSample = 2)
        val third = current.copy(sampleRate = 1)
        val assessment = TargetAssessment(
            TargetAssessment.Status.COMPLETE,
            target = first,
            candidates = listOf(first, second, third),
        )

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = { originCalls++ },
            writeVerified = { candidate ->
                writes += candidate
                writes.size == 2
            },
        )

        assertEquals(ApplyRunResult.Applied(second), result)
        assertEquals(listOf(first, second), writes)
        assertEquals(1, originCalls)
    }

    @Test
    fun exhaustedCandidatesRecordOriginOnlyOnce() = runBlocking {
        var originCalls = 0
        var writeCalls = 0
        val candidates = listOf(current.copy(sampleRate = 4), current.copy(sampleRate = 1))
        val assessment = TargetAssessment(
            TargetAssessment.Status.COMPLETE,
            target = candidates.first(),
            candidates = candidates,
        )

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = { originCalls++ },
            writeVerified = { writeCalls++; false },
        )

        assertEquals(ApplyRunResult.Exhausted, result)
        assertEquals(2, writeCalls)
        assertEquals(1, originCalls)
    }

    @Test
    fun raisedLhdcQualityFallsBackToNextQualityWhenStrictlyRejected() = runBlocking {
        val current = CodecConfig(19, 8004, 2, 2, 2, 0x8004, 0, 0, 0)
        val capability = current.copy(sampleRate = 43, bitsPerSample = 7, cs1 = 0, cs3 = 1)
        val assessment = ApplyPolicy.assessTarget(current, listOf(capability))
        val writes = mutableListOf<CodecConfig>()

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = {},
            writeVerified = { candidate ->
                writes += candidate
                candidate.cs1 != 0x8008L
            },
        )

        assertEquals(ApplyRunResult.Applied(assessment.candidates[1]), result)
        assertEquals(listOf(0x8008L, 0x8007L), writes.map { it.cs1 })
    }

    @Test
    fun multipleRejectedQualitiesNeverUseStaleCurrentBeforeOriginalTier() = runBlocking {
        val current = CodecConfig(19, 8004, 2, 2, 2, 0x8005, 0, 0, 0)
        val capability = current.copy(sampleRate = 43, bitsPerSample = 7, cs1 = 0, cs3 = 1)
        val assessment = ApplyPolicy.assessTarget(current, listOf(capability))
        val writes = mutableListOf<CodecConfig>()

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = {},
            writeVerified = { candidate ->
                writes += candidate
                candidate.cs1 !in listOf(0x8008L, 0x8007L, 0x8006L)
            },
        )

        assertEquals(0x8005L, (result as ApplyRunResult.Applied).candidate.cs1)
        assertEquals(listOf(0x8008L, 0x8007L, 0x8006L, 0x8005L), writes.map { it.cs1 })
    }

    @Test
    fun failedWriteCannotShortCircuitLaterCandidateAgainstStaleCurrent() = runBlocking {
        val high = current.copy(sampleRate = 32)
        val assessment = TargetAssessment(
            TargetAssessment.Status.COMPLETE,
            target = high,
            candidates = listOf(high, current),
        )
        val writes = mutableListOf<CodecConfig>()

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = {},
            writeVerified = { candidate ->
                writes += candidate
                writes.size == 2
            },
        )

        assertEquals(ApplyRunResult.Applied(current), result)
        assertEquals(listOf(high, current), writes)
    }

    @Test
    fun terminalFailureRestoresOriginExactlyOnce() = runBlocking {
        val high = current.copy(sampleRate = 32)
        val assessment = TargetAssessment(
            TargetAssessment.Status.COMPLETE,
            target = high,
            candidates = listOf(high, current.copy(sampleRate = 1)),
        )
        var writes = 0
        var recoveryCalls = 0

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = {},
            writeVerified = { writes++; false },
            restoreOriginOnce = { recoveryCalls++; true },
        )

        assertEquals(ApplyRunResult.Recovered(current), result)
        assertEquals(2, writes)
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun terminalRecoveryFailureIsReportedWithoutRetry() = runBlocking {
        val high = current.copy(sampleRate = 32)
        val assessment = TargetAssessment(
            TargetAssessment.Status.COMPLETE,
            target = high,
            candidates = listOf(high),
        )
        var recoveryCalls = 0

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = {},
            writeVerified = { false },
            restoreOriginOnce = { recoveryCalls++; false },
        )

        assertEquals(ApplyRunResult.RecoveryFailed(current), result)
        assertEquals(1, recoveryCalls)
    }

    @Test
    fun successfulCandidateNeverRestoresOrigin() = runBlocking {
        val high = current.copy(sampleRate = 32)
        val assessment = TargetAssessment(
            TargetAssessment.Status.COMPLETE,
            target = high,
            candidates = listOf(high),
        )
        var recoveryCalls = 0

        val result = runner.run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = {},
            writeVerified = { true },
            restoreOriginOnce = { recoveryCalls++; true },
        )

        assertEquals(ApplyRunResult.Applied(high), result)
        assertEquals(0, recoveryCalls)
    }
}
