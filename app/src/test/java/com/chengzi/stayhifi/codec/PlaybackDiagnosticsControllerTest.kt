package com.chengzi.stayhifi.codec

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackDiagnosticsControllerTest {

    private val mac = "AA:BB:CC:DD:EE:01"
    private val current = CodecConfig(19, 1_000_000, 32, 2, 2, 32_777, 0, 0, 0)
    private val signature = PlaybackDiagnosticsController.CaptureSignature(mac, current, 1)
    private val connection = PlaybackDiagnosticsController.Connection(mac, 1)

    @Test
    fun repeatedSignalsProduceOnlyOneThreeSampleWindow() = runTest {
        val capture = FakeCapture(signature)
        var opens = 0
        val controller = controller(openCapture = { opens++; capture })

        repeat(100) { controller.onPlaybackSignal() }
        advanceUntilIdle()

        assertEquals(1, opens)
        assertEquals(listOf(0, 1, 2), capture.sampleCalls)
        assertTrue(controller.store.snapshot().any { it.contains("times=0,3000,8000") })
    }

    @Test
    fun pauseAndNextSongDoNotRecollectSameSignature() = runTest {
        val capture = FakeCapture(signature)
        var opens = 0
        val controller = controller(openCapture = { opens++; capture })

        controller.onPlaybackSignal()
        advanceUntilIdle()
        controller.onPlaybackStopped()
        controller.onPlaybackSignal()
        advanceUntilIdle()

        assertEquals(1, opens)
        assertEquals(3, capture.sampleCalls.size)
    }

    @Test
    fun clearAllowsOneNewSessionAndRejectsLateOldSample() = runTest {
        val pending = CompletableDeferred<RealtimeDiagnosticDump>()
        val capture = FakeCapture(signature) { pending.await() }
        var opens = 0
        val controller = controller(openCapture = { opens++; capture })

        controller.onPlaybackSignal()
        runCurrent()
        controller.onPlaybackStopped()
        controller.clear()
        pending.complete(emptyDump())
        advanceUntilIdle()

        assertEquals(1, opens)
        assertTrue(controller.store.snapshot().isEmpty())

        controller.onPlaybackSignal()
        advanceUntilIdle()
        assertEquals(2, opens)
        assertTrue(controller.store.snapshot().isNotEmpty())
    }

    @Test
    fun nonA2dpPlaybackNeverOpensCapture() = runTest {
        var opens = 0
        val controller = controller(
            resolveConnection = { null },
            openCapture = { opens++; FakeCapture(signature) },
        )

        controller.onPlaybackSignal()
        advanceUntilIdle()

        assertEquals(0, opens)
    }

    @Test
    fun providerUnavailableDoesNotDumpOrLoop() = runTest {
        var opens = 0
        val logs = mutableListOf<String>()
        val controller = controller(
            openCapture = { opens++; null },
            log = logs::add,
        )

        repeat(10) { controller.onPlaybackSignal() }
        advanceUntilIdle()

        assertEquals(1, opens)
        assertTrue(logs.any { it.contains("unavailable") })
    }

    @Test
    fun readFailureDoesNotDump() = runTest {
        val logs = mutableListOf<String>()
        val controller = controller(
            openCapture = { error("readConfig failed") },
            log = logs::add,
        )

        controller.onPlaybackSignal()
        advanceUntilIdle()

        assertTrue(logs.any { it.contains("readConfig failed") })
    }

    @Test
    fun firstTimeoutCancelsRemainingSamples() = runTest {
        val capture = FakeCapture(signature) { awaitCancellation() }
        val logs = mutableListOf<String>()
        val controller = controller(
            openCapture = { capture },
            log = logs::add,
        )

        controller.onPlaybackSignal()
        advanceUntilIdle()

        assertEquals(listOf(0), capture.sampleCalls)
        assertTrue(logs.any { it.contains("timeout") })
    }

    @Test
    fun observedConfigChangeReplacesOldWindow() = runTest {
        val changed = signature.copy(current = current.copy(sampleRate = 2))
        val captures = listOf(FakeCapture(signature), FakeCapture(changed))
        var opens = 0
        val controller = controller(openCapture = { captures[opens++] })

        controller.onPlaybackSignal(signature)
        advanceUntilIdle()
        controller.onPlaybackSignal(changed)
        advanceUntilIdle()

        assertEquals(2, opens)
        assertTrue(controller.store.snapshot().first().contains("window=1"))
    }

    @Test
    fun copyingStoreSnapshotPerformsNoNewCaptureIo() = runTest {
        val capture = FakeCapture(signature)
        var opens = 0
        val controller = controller(openCapture = { opens++; capture })
        controller.onPlaybackSignal()
        advanceUntilIdle()

        val exported = controller.store.snapshot()

        assertTrue(exported.isNotEmpty())
        assertEquals(1, opens)
        assertEquals(3, capture.sampleCalls.size)
    }

    @Test
    fun clearDoesNotImmediatelyRecaptureWhileMusicIsPlaying() = runTest {
        val capture = FakeCapture(signature)
        var opens = 0
        val controller = controller(openCapture = { opens++; capture })
        controller.onPlaybackSignal()
        advanceUntilIdle()

        controller.clear()
        advanceUntilIdle()

        assertEquals(1, opens)
        assertTrue(controller.store.snapshot().isEmpty())

        controller.onPlaybackSignal()
        advanceUntilIdle()
        assertEquals(2, opens)
    }

    @Test
    fun stopDuringInFlightSampleDoesNotStorePostPauseEvidence() = runTest {
        val pending = CompletableDeferred<RealtimeDiagnosticDump>()
        val capture = FakeCapture(signature) { pending.await() }
        val controller = controller(openCapture = { capture })
        controller.onPlaybackSignal()
        runCurrent()

        controller.onPlaybackStopped()
        pending.complete(emptyDump())
        advanceUntilIdle()

        assertTrue(controller.store.snapshot().isEmpty())
        assertEquals(listOf(0), capture.sampleCalls)
    }

    @Test
    fun initialOpenHasBoundedTimeout() = runTest {
        val logs = mutableListOf<String>()
        val controller = controller(
            openCapture = { awaitCancellation() },
            log = logs::add,
        )
        controller.onPlaybackSignal()

        val drained = withTimeoutOrNull(15_000) { advanceUntilIdle() }

        assertTrue(drained != null)
        assertTrue(logs.any { it.contains("open timeout") })
    }

    @Test
    fun failedWindowCanRetryOnlyOnANewPlaybackSession() = runTest {
        val captures = listOf(
            FakeCapture(signature) { awaitCancellation() },
            FakeCapture(signature),
        )
        var opens = 0
        val controller = controller(openCapture = { captures[opens++] })
        controller.onPlaybackSignal()
        advanceUntilIdle()

        controller.onPlaybackStopped()
        controller.onPlaybackSignal()
        advanceUntilIdle()

        assertEquals(2, opens)
    }

    @Test
    fun signalStormDoesNotLaunchOneCoroutinePerCallback() = runTest {
        val pending = CompletableDeferred<RealtimeDiagnosticDump>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = PlaybackDiagnosticsController(
            scope = scope,
            store = PlaybackDiagnosticsStore(),
            resolveConnection = { connection },
            openCapture = { FakeCapture(signature) { pending.await() } },
            sampleDelay = {},
        )
        controller.onPlaybackSignal()
        runCurrent()

        repeat(100) { controller.onPlaybackSignal() }
        val children = scope.coroutineContext[Job]?.children?.count() ?: 0

        assertTrue(children <= 2)
        pending.complete(emptyDump())
        advanceUntilIdle()
    }

    private fun TestScope.controller(
        resolveConnection: suspend () -> PlaybackDiagnosticsController.Connection? = { connection },
        openCapture: suspend (PlaybackDiagnosticsController.Connection) -> PlaybackDiagnosticsController.Capture?,
        log: (String) -> Unit = {},
    ) = PlaybackDiagnosticsController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        store = PlaybackDiagnosticsStore(),
        resolveConnection = resolveConnection,
        openCapture = openCapture,
        sampleDelay = {},
        log = log,
    )

    private class FakeCapture(
        override var signature: PlaybackDiagnosticsController.CaptureSignature,
        private val onSample: suspend (Int) -> RealtimeDiagnosticDump = { emptyDump() },
    ) : PlaybackDiagnosticsController.Capture {
        val sampleCalls = mutableListOf<Int>()
        override suspend fun sample(index: Int): RealtimeDiagnosticDump {
            sampleCalls += index
            return onSample(index)
        }
        override fun close() = Unit
    }

    companion object {
        private fun emptyDump() = RealtimeDiagnosticDump(
            bluetooth = RealtimeDiagnosticDump.Source(DumpStatus.OK, "A2DP LHDC V5 State:"),
            audio = RealtimeDiagnosticDump.Source(DumpStatus.OK, "sampleRate=48000"),
            audioFlinger = RealtimeDiagnosticDump.Source(DumpStatus.OK, "sampleRate=48000"),
        )
    }
}
