package com.chengzi.stayhifi.shell

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task D4：前台 Root 授权单飞状态机（设计 §11.4）。
 *  - 冷启动 UNVERIFIED；持久 Root 不自动 openShell（DataStore 不是授权证明，§11.3）；
 *  - grantedState 三态：false → NOT_GRANTED（不创建 shell），null 保持未知语义继续走 shell 验证；
 *  - openShell 只在显式 begin 后单飞执行；异常 → SHELL_FAILURE；isRoot=false → NON_ROOT_SHELL 永不 READY；
 *  - 30 秒 UI watchdog 只代表自有等待结束（AUTH_TIMEOUT），底层任务不被冒充取消，
 *    再次点击单飞复用同一物理任务，绝不并发第二次 openShell（§11.4.6）；
 *  - 离页/改选 invalidate 后，迟到成功不得成为 candidate、不得 READY。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootTransportAuthTest {

    /** 见 D2 报告：自带 StandardTestDispatcher 的 scope 才能模拟"首读/物理任务进行中"窗口。 */
    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class RecordingSink : (String) -> Unit {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        override fun invoke(line: String) {
            lines.add(line)
        }
        fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    }

    private class FakeRootBackend(
        var granted: Boolean? = null,
        var shellResult: RootShellObservation = RootShellObservation(isRoot = true),
        var shellFailure: Throwable? = null,
        val shellGate: CountDownLatch? = null,
    ) : RootBackend {
        val grantedCalls = AtomicInteger()
        val openShellCalls = AtomicInteger()
        @Volatile var openShellReturned = false

        override fun grantedState(): Boolean? {
            grantedCalls.incrementAndGet()
            return granted
        }

        override fun openShell(): RootShellObservation {
            openShellCalls.incrementAndGet()
            shellGate?.await(30, TimeUnit.SECONDS)   // 安全阀；测试显式 countDown
            shellFailure?.let { throw it }
            openShellReturned = true
            return shellResult
        }
    }

    private fun TestScope.newTransport(
        backend: FakeRootBackend,
        sink: RecordingSink = RecordingSink(),
        authDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Pair<RootTransport, RecordingSink> =
        RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = authDispatcher,
            logger = RootDiagnosticLogger(sink),
        ) to sink

    /** 真实后台线程：模拟底层 openShell 长时间阻塞（虚拟时间 watchdog 与被挂起的物理任务并存）。 */
    private fun realAuthDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "root-auth-fake").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun awaitReal(deadlineMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("真实线程条件未在 ${deadlineMs}ms 内满足", condition())
    }

    private suspend fun assertRootAccessFails(expected: RootStatus, block: suspend () -> Any?) {
        try {
            block()
            fail("期望 RootAccessException($expected)，实际成功返回")
        } catch (e: RootAccessException) {
            assertEquals(expected, e.status)
        }
    }

    private val owner = ProviderOwner("settings-ui")
    private val source = ProviderSource.SETTINGS

    // ─────────────────────────── 冷启动与持久选择 ───────────────────────────

    @Test
    fun coldStartIsUnverifiedAndTouchesNothing() = runTest {
        val backend = FakeRootBackend(granted = null)
        val (transport, _) = newTransport(backend)
        advanceUntilIdle()
        val s = transport.state.value
        assertEquals(RootStatus.UNVERIFIED, s.status)
        assertNull(s.granted)
        assertEquals(RootShellStatus.UNKNOWN, s.shellStatus)
        assertEquals(0, backend.openShellCalls.get())
        assertEquals(0, backend.grantedCalls.get())
    }

    @Test
    fun persistedRootSelectionNeverAutoOpensShell() = runTest {
        // 即使可观察授权为 true，持久选择只是"希望使用的通道"，不是授权证明（§11.3）
        val backend = FakeRootBackend(granted = true)
        val (transport, _) = newTransport(backend)
        advanceUntilIdle()
        assertEquals(RootStatus.UNVERIFIED, transport.state.value.status)
        assertEquals("无显式 begin 绝不 openShell", 0, backend.openShellCalls.get())
        assertEquals(0, backend.grantedCalls.get())
    }

    // ─────────────────────────── granted 三态 ───────────────────────────

    @Test
    fun grantedFalseMapsToNotGrantedWithoutShell() = runTest {
        val backend = FakeRootBackend(granted = false)
        val (transport, sink) = newTransport(backend)
        val attempt = transport.beginForegroundAuthorization(owner, source)
        assertEquals(RootStatus.NOT_GRANTED, transport.state.value.status)
        assertEquals(false, transport.state.value.granted)
        assertEquals("granted=false 不需要创建 shell", 0, backend.openShellCalls.get())
        assertRootAccessFails(RootStatus.NOT_GRANTED) { transport.awaitCandidate(attempt) }
        transport.logger.flushSuppressed()
        assertTrue(sink.snapshot().any { it.contains("errorClass=AUTH_FALSE") })
    }

    @Test
    fun grantedNullKeepsUnknownSemanticsAndProceedsToShell() = runTest {
        val gate = CountDownLatch(1)
        val backend = FakeRootBackend(granted = null, shellGate = gate)
        val (transport, _) = newTransport(backend, authDispatcher = realAuthDispatcher())
        try {
            val attempt = transport.beginForegroundAuthorization(owner, source)
            // null 是"未知"，不得直接当作未授权；显式 begin 后允许继续 openShell
            assertEquals(RootStatus.REQUESTING, transport.state.value.status)
            assertNotEquals(RootStatus.NOT_GRANTED, transport.state.value.status)
            awaitReal { backend.openShellCalls.get() == 1 }

            val candidateDeferred = async { transport.awaitCandidate(attempt) }
            gate.countDown()
            awaitReal { transport.state.value.status == RootStatus.GRANTED_NOT_READY }
            advanceUntilIdle()
            assertTrue(candidateDeferred.isCompleted)
            assertEquals(attempt.id, candidateDeferred.getCompleted().attemptId)
            assertEquals(RootShellStatus.ROOT_SHELL, transport.state.value.shellStatus)
        } finally {
            gate.countDown()
        }
    }

    // ─────────────────────────── 失败与非 root shell ───────────────────────────

    @Test
    fun openShellFailureMapsToShellFailure() = runTest {
        val backend = FakeRootBackend(granted = null, shellFailure = RuntimeException("boom"))
        val (transport, sink) = newTransport(backend)
        val attempt = transport.beginForegroundAuthorization(owner, source)
        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.awaitCandidate(attempt)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        val ex = thrown
        assertTrue(ex is RootAccessException)
        assertEquals(RootStatus.SHELL_FAILURE, (ex as RootAccessException).status)
        assertEquals(RootStatus.SHELL_FAILURE, transport.state.value.status)
        assertEquals(RootShellStatus.UNKNOWN, transport.state.value.shellStatus)
        transport.logger.flushSuppressed()
        assertTrue(sink.snapshot().any { it.contains("errorClass=SHELL_FAILURE") })
    }

    @Test
    fun nonRootShellIsNeverReady() = runTest {
        // libsu 默认 shell 可能回落普通 sh（§11.4.1）：isRoot=false 不算授权
        val backend = FakeRootBackend(granted = true, shellResult = RootShellObservation(isRoot = false))
        val (transport, _) = newTransport(backend)
        val attempt = transport.beginForegroundAuthorization(owner, source)
        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.awaitCandidate(attempt)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        val ex = thrown
        assertTrue(ex is RootAccessException)
        assertEquals(RootStatus.NOT_GRANTED, (ex as RootAccessException).status)
        assertEquals(RootStatus.NOT_GRANTED, transport.state.value.status)
        assertEquals(RootShellStatus.NON_ROOT_SHELL, transport.state.value.shellStatus)
        advanceUntilIdle()
        assertNotEquals("非 root shell 永不 READY", RootStatus.READY, transport.state.value.status)
    }

    // ─────────────────────────── 单飞与 30 秒 watchdog ───────────────────────────

    @Test
    fun secondClickDuringRequestReusesSameAttempt() = runTest {
        val gate = CountDownLatch(1)
        val backend = FakeRootBackend(granted = null, shellGate = gate)
        val (transport, _) = newTransport(backend, authDispatcher = realAuthDispatcher())
        try {
            val first = transport.beginForegroundAuthorization(owner, source)
            awaitReal { backend.openShellCalls.get() == 1 }
            val second = transport.beginForegroundAuthorization(owner, source)
            assertEquals("等待中再次点击返回同一 attempt（单飞）", first.id, second.id)
            assertEquals(1, backend.openShellCalls.get())
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun authTimeoutKeepsUnderlyingPendingAndNeverOpensSecondShell() = runTest {
        val gate = CountDownLatch(1)
        val backend = FakeRootBackend(granted = null, shellGate = gate)
        val (transport, _) = newTransport(backend, authDispatcher = realAuthDispatcher())
        try {
            val attempt1 = transport.beginForegroundAuthorization(owner, source)
            awaitReal { backend.openShellCalls.get() == 1 }

            var thrown1: Throwable? = null
            val wait1 = launch {
                try {
                    transport.awaitCandidate(attempt1)
                } catch (t: Throwable) {
                    thrown1 = t
                }
            }
            runCurrent()                       // 启动等待并挂上 30s 虚拟 watchdog
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(wait1.isCompleted)
            val ex = thrown1
            assertTrue(ex is RootAccessException)
            assertEquals(RootStatus.AUTH_TIMEOUT, (ex as RootAccessException).status)
            assertEquals(RootStatus.AUTH_TIMEOUT, transport.state.value.status)
            // watchdog 只代表自有等待结束：底层物理任务仍在进行，且没有第二次 openShell
            assertEquals(1, backend.openShellCalls.get())

            // 再次点击：不并发第二次请求，挂新等待者到同一物理任务
            val attempt2 = transport.beginForegroundAuthorization(owner, source)
            assertNotEquals(attempt1.id, attempt2.id)
            assertEquals(1, backend.openShellCalls.get())
            assertEquals(RootStatus.REQUESTING, transport.state.value.status)

            var candidate2: RootCandidate? = null
            var thrown2: Throwable? = null
            val wait2 = launch {
                try {
                    candidate2 = transport.awaitCandidate(attempt2)
                } catch (t: Throwable) {
                    thrown2 = t
                }
            }
            gate.countDown()
            awaitReal { transport.state.value.status == RootStatus.GRANTED_NOT_READY }
            advanceUntilIdle()
            assertTrue(wait2.isCompleted)
            assertNull(thrown2)
            assertEquals(attempt2.id, candidate2?.attemptId)
            assertEquals("全程只有一次物理 openShell", 1, backend.openShellCalls.get())
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun invalidationDropsLateSuccess() = runTest {
        val gate = CountDownLatch(1)
        val backend = FakeRootBackend(granted = null, shellGate = gate)
        val (transport, _) = newTransport(backend, authDispatcher = realAuthDispatcher())
        try {
            val attempt = transport.beginForegroundAuthorization(owner, source)
            var thrown: Throwable? = null
            val waiting = launch {
                try {
                    transport.awaitCandidate(attempt)
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            runCurrent()
            awaitReal { backend.openShellCalls.get() == 1 }

            // 离页/改选：旧 attempt 失效
            transport.invalidateAttempt(attempt.id)
            assertEquals(RootStatus.UNVERIFIED, transport.state.value.status)
            advanceUntilIdle()
            assertTrue(waiting.isCompleted)
            assertTrue(thrown is RootAccessException)

            // 底层迟到成功：不得成为 candidate，不得进入 GRANTED_NOT_READY/READY
            gate.countDown()
            awaitReal { backend.openShellReturned }
            advanceUntilIdle()
            assertEquals(RootStatus.UNVERIFIED, transport.state.value.status)
            assertEquals(1, backend.openShellCalls.get())
        } finally {
            gate.countDown()
        }
    }
}
