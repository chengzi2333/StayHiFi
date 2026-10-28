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
}
