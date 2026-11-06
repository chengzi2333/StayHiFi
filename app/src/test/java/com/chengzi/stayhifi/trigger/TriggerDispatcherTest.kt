package com.chengzi.stayhifi.trigger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerDispatcherTest {

    @Test
    fun automaticSourcesShareOneWindowAndLogMergeFacts() = runBlocking {
        var calls = 0
        val logs = mutableListOf<String>()
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { 0L }),
            runner = TriggerRunner { _, _, _ -> calls++ },
            log = logs::add,
        )

        assertTrue(dispatcher.dispatch("aa:bb", "one", false, TriggerSource.CONNECTION_RECEIVER))
        assertFalse(dispatcher.dispatch("AA:BB", "one", false, TriggerSource.LISTENER_SERVICE))
        assertFalse(dispatcher.dispatch("aa:bb", "one", false, TriggerSource.STARTUP))

        assertEquals(1, calls)
        val mergeLogs = logs.filter { it.contains("重复触发已合并") }
        assertEquals(2, mergeLogs.size)
        assertTrue(mergeLogs[0].contains("LISTENER_SERVICE"))
        assertTrue(mergeLogs[0].contains("AA:BB"))
        assertTrue(mergeLogs.all { it.contains("reason=COMPLETED_WINDOW") })
        assertFalse(logs.any { it.contains("已阻止错误写入") })
    }

    @Test
    fun grantReturnAndCooldownExposeTheirActualDecisions() = runBlocking {
        val logs = mutableListOf<String>()
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { 0L }),
            runner = TriggerRunner { _, _, _ -> },
            log = logs::add,
        )

        assertTrue(dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP))
        assertFalse(dispatcher.dispatch("AA", null, false, TriggerSource.LISTENER_SERVICE))

        assertTrue(logs.any {
            it.contains("trigger_decision decision=GRANTED") &&
                it.contains("source=STARTUP") && it.contains("force=false")
        })
        assertTrue(logs.any {
            it.contains("trigger_decision decision=REJECTED reason=COMPLETED_WINDOW") &&
                it.contains("remainingMs=10000")
        })
        assertTrue(logs.any { it.contains("trigger_runner event=RETURNED") })
        assertFalse(logs.any { it.contains("trigger_runner event=RETURNED") && it.contains("成功") })
    }

    @Test
    fun runnerExceptionIsLoggedRethrownAndPermitReleased() = runBlocking {
        val logs = mutableListOf<String>()
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { 0L }),
            runner = TriggerRunner { _, _, _ -> throw IllegalStateException("boom") },
            log = logs::add,
        )

        val error = runCatching {
            dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(logs.any {
            it.contains("trigger_runner event=EXCEPTION") &&
                it.contains("error=IllegalStateException")
        })
        assertTrue(logs.any { it.contains("trigger_gate event=RELEASED") })
    }

    @Test
    fun differentMacsDoNotShareWindow() = runBlocking {
        var calls = 0
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { 0L }),
            runner = TriggerRunner { _, _, _ -> calls++ },
        )

        assertTrue(dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP))
        assertTrue(dispatcher.dispatch("BB", null, false, TriggerSource.STARTUP))

        assertEquals(2, calls)
    }

    @Test
    fun completedWindowIncludesStartAndExcludesEnd() = runBlocking {
        var now = 1_000L
        var calls = 0
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { now }),
            runner = TriggerRunner { _, _, _ -> calls++ },
        )

        assertTrue(dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP))
        now = 10_999L
        assertFalse(dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP))
        now = 11_000L
        assertTrue(dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP))

        assertEquals(2, calls)
    }

    @Test
    fun activeWorkerBeyondWindowStillMerges() = runBlocking {
        var now = 0L
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { now }),
            runner = TriggerRunner { _, _, _ ->
                calls++
                started.complete(Unit)
                finish.await()
            },
            log = logs::add,
        )
        val active = async {
            dispatcher.dispatch("AA", null, false, TriggerSource.LISTENER_SERVICE)
        }
        started.await()

        now = 20_000L
        assertFalse(dispatcher.dispatch("AA", null, false, TriggerSource.CONNECTION_RECEIVER))
        assertEquals(1, calls)
        assertTrue(logs.any {
            it.contains("trigger_decision decision=REJECTED reason=ACTIVE") &&
                it.contains("source=CONNECTION_RECEIVER")
        })

        finish.complete(Unit)
        assertTrue(active.await())
    }

    @Test
    fun forceBypassesCompletedWindowButNotActiveWorker() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { 0L }),
            runner = TriggerRunner { _, _, _ ->
                calls++
                if (calls == 3) {
                    started.complete(Unit)
                    finish.await()
                }
            },
        )

        assertTrue(dispatcher.dispatch("AA", null, false, TriggerSource.STARTUP))
        assertTrue(dispatcher.dispatch("AA", null, true, TriggerSource.MANUAL))
        val active = async { dispatcher.dispatch("AA", null, true, TriggerSource.QS) }
        started.await()
        assertFalse(dispatcher.dispatch("AA", null, true, TriggerSource.MANUAL))
        assertEquals(3, calls)

        finish.complete(Unit)
        assertTrue(active.await())
    }

    @Test
    fun exceptionAndTimeoutReleasePermitInFinally() = runBlocking {
        var now = 0L
        val gate = TriggerGate(nowMillis = { now })
        var throws = true
        var slow = true
        val throwing = TriggerDispatcher(gate, runner = TriggerRunner { _, _, _ ->
            if (throws) throw IllegalStateException("boom")
        })
        val timingOut = TriggerDispatcher(gate, runner = TriggerRunner { _, _, _ ->
            if (slow) delay(10)
        })

        val exception = runCatching {
            throwing.dispatch("EXCEPTION", null, false, TriggerSource.STARTUP)
        }.exceptionOrNull()
        assertTrue(exception is IllegalStateException)
        now += 10_000L
        throws = false
        assertTrue(throwing.dispatch("EXCEPTION", null, false, TriggerSource.STARTUP))

        val timeout = runCatching {
            withTimeout(1) {
                timingOut.dispatch("TIMEOUT", null, false, TriggerSource.STARTUP)
            }
        }.exceptionOrNull()
        assertTrue(timeout is kotlinx.coroutines.TimeoutCancellationException)
        now += 10_000L
        slow = false
        assertTrue(timingOut.dispatch("TIMEOUT", null, false, TriggerSource.STARTUP))
    }
}
