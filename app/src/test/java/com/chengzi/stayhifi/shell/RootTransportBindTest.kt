package com.chengzi.stayhifi.shell

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D5：libsu bind、10 秒 bind watchdog、dumpDevices 健康检查与 READY anchor（设计 §11.4.7-9）。
 *  - null binder → BIND_NULL；bind 抛异常 → BIND_FAILED；10 秒无回调 → BIND_TIMEOUT；
 *  - timeout 后迟到 onServiceConnected 不得 READY，必须在 Main 立即 unbind，重复事件只清理一次；
 *  - 主动解绑（local_disconnect）与远端断开（remote_disconnect）分别记录，重复调用幂等；
 *  - binder 非空但 dumpDevices 有 error/异常/超时 → 停留 GRANTED_NOT_READY（HEALTH_FAILURE），不 READY；
 *  - READY = root shell + 非空 binder + dumpDevices 无 error 三者齐；anchor 属进程级，provider.unbind 不解 anchor。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootTransportBindTest {

    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class RecordingSink : (String) -> Unit {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        override fun invoke(line: String) {
            lines.add(line)
        }
        fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    }

    private open class FakeCodecService(
        private val dumpResult: String = "[]",
        private val dumpGate: CountDownLatch? = null,
    ) : ICodecService {
        val dumpCalls = AtomicInteger()
        @Volatile var dumpReturned = false

        override fun dumpDevices(): String {
            dumpCalls.incrementAndGet()
            dumpGate?.await(30, TimeUnit.SECONDS)
            dumpReturned = true
            return dumpResult
        }

        override fun readConfig(mac: String?): String = "{}"
        override fun writeConfig(mac: String?, configJson: String?): String = "{}"
        override fun dumpBtState(): String = "{}"
        override fun destroy() = Unit
        override fun asBinder(): IBinder? = null
    }

    private class ThrowingCodecService : FakeCodecService() {
        override fun dumpDevices(): String = throw RuntimeException("binder dead")
    }

    private class FakeBindBackend(
        var granted: Boolean? = null,
        var shellResult: RootShellObservation = RootShellObservation(isRoot = true),
    ) : RootBackend {
        enum class BindMode { CONNECT, CONNECT_NULL, THROW, PENDING }

        @Volatile var bindMode = BindMode.CONNECT
        @Volatile var service: ICodecService = FakeCodecService()
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        @Volatile var lastCallbacks: RootBindCallbacks? = null
        private val bindingIds = AtomicLong(0)

        override fun grantedState(): Boolean? = granted
        override fun openShell(): RootShellObservation {
            openShellCalls.incrementAndGet()
            return shellResult
        }

        override fun bind(callbacks: RootBindCallbacks): RootBinding {
            bindCalls.incrementAndGet()
            lastCallbacks = callbacks
            val binding = FakeBinding(bindingIds.incrementAndGet())
            when (bindMode) {
                BindMode.CONNECT -> callbacks.onConnected(service)
                BindMode.CONNECT_NULL -> callbacks.onConnected(null)
                BindMode.THROW -> throw RuntimeException("bind boom")
                BindMode.PENDING -> Unit
            }
            return binding
        }

        override fun unbind(binding: RootBinding) {
            unbindCalls.incrementAndGet()
        }

        private class FakeBinding(override val id: Long) : RootBinding
    }

    private fun TestScope.newTransport(
        backend: FakeBindBackend,
        sink: RecordingSink = RecordingSink(),
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Pair<RootTransport, RecordingSink> = RootTransport(
        applicationScope = appScope(),
        backend = backend,
        authDispatcher = StandardTestDispatcher(testScheduler),
        mainDispatcher = StandardTestDispatcher(testScheduler),
        ioDispatcher = ioDispatcher,
        logger = RootDiagnosticLogger(sink),
    ) to sink

    private fun realIoDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "root-health-fake").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private fun awaitReal(deadlineMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("真实线程条件未在 ${deadlineMs}ms 内满足", condition())
    }

    private val owner = ProviderOwner("settings-ui")
    private val source = ProviderSource.SETTINGS

    /** 授权阶段走完，拿到未提交 candidate。 */
    private fun TestScope.toCandidate(transport: RootTransport): RootCandidate {
        val attempt = transport.beginForegroundAuthorization(owner, source)
        var candidate: RootCandidate? = null
        var thrown: Throwable? = null
        val waiter = launch {
            try {
                candidate = transport.awaitCandidate(attempt)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertNull("授权阶段应成功", thrown)
        assertEquals(RootStatus.GRANTED_NOT_READY, transport.state.value.status)
        return candidate!!
    }

    // ─────────────────────────── bind 失败三态 ───────────────────────────

    @Test
    fun nullBinderMapsToBindNullAndUnbinds() = runTest {
        val backend = FakeBindBackend().apply { bindMode = FakeBindBackend.BindMode.CONNECT_NULL }
        val (transport, _) = newTransport(backend)
        val candidate = toCandidate(transport)

        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.bindAndCheck(candidate)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertTrue(thrown is RootAccessException)
        assertEquals(RootStatus.BIND_NULL, (thrown as RootAccessException).status)
        assertEquals(RootStatus.BIND_NULL, transport.state.value.status)
        assertEquals("null binder 的 connection 必须解绑", 1, backend.unbindCalls.get())
    }

    @Test
    fun bindExceptionMapsToBindFailed() = runTest {
        val backend = FakeBindBackend().apply { bindMode = FakeBindBackend.BindMode.THROW }
        val (transport, sink) = newTransport(backend)
        val candidate = toCandidate(transport)

        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.bindAndCheck(candidate)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertTrue(thrown is RootAccessException)
        assertEquals(RootStatus.BIND_FAILED, (thrown as RootAccessException).status)
        assertEquals(RootStatus.BIND_FAILED, transport.state.value.status)
        assertEquals("bind 未建立，无 connection 可解", 0, backend.unbindCalls.get())
        transport.logger.flushSuppressed()
        assertTrue(sink.snapshot().any { it.contains("errorClass=BIND_FAILED") })
    }

    @Test
    fun bindTimeoutLateConnectIsUnboundOnceOnMain() = runTest {
        val backend = FakeBindBackend().apply { bindMode = FakeBindBackend.BindMode.PENDING }
        val (transport, _) = newTransport(backend)
        val candidate = toCandidate(transport)

        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.bindAndCheck(candidate)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        runCurrent()                          // 发起 bind 并挂上 10s 虚拟 watchdog
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(waiter.isCompleted)
        assertTrue(thrown is RootAccessException)
        assertEquals(RootStatus.BIND_TIMEOUT, (thrown as RootAccessException).status)
        assertEquals(RootStatus.BIND_TIMEOUT, transport.state.value.status)
        assertEquals("watchdog 不冒充底层取消，bind 尚未归还", 0, backend.unbindCalls.get())

        // 迟到 onServiceConnected：不得 READY，必须主线程立即 unbind，且只清理一次
        val callbacks = backend.lastCallbacks!!
        callbacks.onConnected(backend.service)
        advanceUntilIdle()
        assertEquals(1, backend.unbindCalls.get())
        assertNotEquals(RootStatus.READY, transport.state.value.status)

        callbacks.onConnected(backend.service)
        callbacks.onDisconnected()
        advanceUntilIdle()
        assertEquals("重复事件只清理一次", 1, backend.unbindCalls.get())
    }

    // ─────────────────────────── READY 与断开 ───────────────────────────

    @Test
    fun readyRequiresBinderAndHealthyDump() = runTest {
        val backend = FakeBindBackend()
        val (transport, _) = newTransport(backend)
        val candidate = toCandidate(transport)

        var thrown: Throwable? = null
        var bound: RootCandidate? = null
        val waiter = launch {
            try {
                bound = transport.bindAndCheck(candidate)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertNull(thrown)
        assertSame(candidate, bound)
        assertEquals(RootStatus.READY, transport.state.value.status)

        val provider = RootShellProvider(transport)
        assertTrue(provider.isRunning)
        assertTrue(provider.hasPermission)
        assertSame(backend.service, provider.bind())
        // anchor 属进程级：provider.unbind 不得解 anchor（§11.3）
        provider.unbind()
        assertEquals(0, backend.unbindCalls.get())
    }

    @Test
    fun coldProviderReportsNotReadyAndNeverInteracts() = runTest {
        val backend = FakeBindBackend()
        val (transport, _) = newTransport(backend)
        val provider = RootShellProvider(transport)
        assertFalse(provider.isRunning)
        assertFalse(provider.hasPermission)
        try {
            provider.bind()
            throw AssertionError("未 READY 的 bind 必须失败")
        } catch (e: RootAccessException) {
            assertEquals(RootStatus.NEEDS_FOREGROUND_REVALIDATION, e.status)
        }
        advanceUntilIdle()
        assertEquals(0, backend.openShellCalls.get())
        assertEquals(0, backend.bindCalls.get())
    }

    @Test
    fun remoteDisconnectDropsAnchorAndIsLoggedOnce() = runTest {
        val backend = FakeBindBackend()
        val (transport, sink) = newTransport(backend)
        val candidate = toCandidate(transport)
        val waiter = launch {
            runCatching { transport.bindAndCheck(candidate) }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertEquals(RootStatus.READY, transport.state.value.status)

        // 远端断开：anchor 丢失、状态 DISCONNECTED、记录 remote_disconnect
        backend.lastCallbacks!!.onDisconnected()
        advanceUntilIdle()
        assertEquals(RootStatus.DISCONNECTED, transport.state.value.status)
        val provider = RootShellProvider(transport)
        assertFalse(provider.isRunning)
        transport.logger.flushSuppressed()
        assertTrue(sink.snapshot().any { it.contains("bindCallback=remote_disconnect") })

        // 重复远端回调幂等：不产生第二次记录/解绑
        val linesBefore = sink.snapshot().size
        backend.lastCallbacks!!.onDisconnected()
        transport.logger.flushSuppressed()
        assertEquals(linesBefore, sink.snapshot().size)
    }

    @Test
    fun manualDisconnectUnbindsAnchorOnce() = runTest {
        val backend = FakeBindBackend()
        val (transport, sink) = newTransport(backend)
        val candidate = toCandidate(transport)
        val waiter = launch {
            runCatching { transport.bindAndCheck(candidate) }
        }
        advanceUntilIdle()
        assertEquals(RootStatus.READY, transport.state.value.status)

        transport.disconnectAnchor()
        advanceUntilIdle()
        assertEquals(RootStatus.DISCONNECTED, transport.state.value.status)
        assertEquals("主动解绑 anchor 一次", 1, backend.unbindCalls.get())
        transport.logger.flushSuppressed()
        assertTrue(sink.snapshot().any { it.contains("bindCallback=local_disconnect") })

        transport.disconnectAnchor()
        advanceUntilIdle()
        assertEquals("重复 disconnect 幂等", 1, backend.unbindCalls.get())
    }

    // ─────────────────────────── 并发 bind 单飞（final review fix） ───────────────────────────

    @Test
    fun concurrentBindAndCheckOnSameCandidateBindsOnce() = runTest {
        val backend = FakeBindBackend()
        val (transport, _) = newTransport(backend)
        val candidate = toCandidate(transport)

        // 两路并发 bindAndCheck：同一 transport 同时只能一个 bind（§11.4.8）
        val first = async { runCatching { transport.bindAndCheck(candidate) } }
        val second = async { runCatching { transport.bindAndCheck(candidate) } }
        advanceUntilIdle()

        val r1 = first.await()
        val r2 = second.await()
        assertTrue("两路并发 bind 都必须成功返回，实际 $r1 / $r2",
            r1.isSuccess && r2.isSuccess)
        assertEquals("同一 candidate 并发 bind 必须单飞，不得重复 backend.bind",
            1, backend.bindCalls.get())
        assertEquals("单飞后健康检查只跑一次", 1,
            (backend.service as FakeCodecService).dumpCalls.get())
        assertEquals(RootStatus.READY, transport.state.value.status)
        assertEquals(0, backend.unbindCalls.get())
    }

    // ─────────────────────────── 诊断日志上下文（final review fix） ───────────────────────────

    @Test
    fun authAndBindLogsCarryInjectedCoordinatorContext() = runTest {
        val backend = FakeBindBackend()
        val sink = RecordingSink()
        // 生产由 StayHiFiApp 注入 Coordinator 的 generation/selected/active；此处注入固定上下文
        val transport = RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = RootDiagnosticLogger(sink),
            logContext = { RootLogContext(7L, ProviderKind.ROOT, ProviderKind.SHIZUKU) },
        )
        val candidate = toCandidate(transport)   // attempt start / auth end 走 logLocked
        val waiter = launch { runCatching { transport.bindAndCheck(candidate) } }
        advanceUntilIdle()                        // bind/health end 走 logPhase
        assertEquals(RootStatus.READY, transport.state.value.status)

        transport.logger.flushSuppressed()
        val lines = sink.snapshot()
        assertTrue("应产生授权与绑定日志", lines.isNotEmpty())
        assertTrue("D7 后日志必须带当前 Coordinator generation/selected/active，实际: $lines",
            lines.all {
                it.contains("generation=7") &&
                    it.contains("selected=root") &&
                    it.contains("active=shizuku")
            })
    }

    // ─────────────────────────── 健康检查 ───────────────────────────

    @Test
    fun healthyEmptyDeviceArrayReachesReady() = runTest {
        val backend = FakeBindBackend()   // FakeCodecService 默认 "[]"
        val (transport, _) = newTransport(backend)
        val candidate = toCandidate(transport)
        val waiter = launch {
            runCatching { transport.bindAndCheck(candidate) }
        }
        advanceUntilIdle()
        assertEquals("空设备数组本身健康（§11.4.9）", RootStatus.READY, transport.state.value.status)
    }

    @Test
    fun errorDumpKeepsGrantedNotReady() = runTest {
        val backend = FakeBindBackend().apply {
            service = FakeCodecService(dumpResult = """{"error":"dumpDevices: boom"}""")
        }
        val (transport, sink) = newTransport(backend)
        val candidate = toCandidate(transport)

        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.bindAndCheck(candidate)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertTrue(thrown is RootAccessException)
        // binder 非空但 health 失败：停留 GRANTED_NOT_READY，不 READY
        assertEquals(RootStatus.GRANTED_NOT_READY, (thrown as RootAccessException).status)
        assertEquals(RootStatus.GRANTED_NOT_READY, transport.state.value.status)
        assertEquals(1, backend.unbindCalls.get())
        transport.logger.flushSuppressed()
        assertTrue(sink.snapshot().any { it.contains("errorClass=HEALTH_FAILURE") })
    }

    @Test
    fun binderExceptionDuringHealthIsNotReady() = runTest {
        val backend = FakeBindBackend().apply { service = ThrowingCodecService() }
        val (transport, _) = newTransport(backend)
        val candidate = toCandidate(transport)

        var thrown: Throwable? = null
        val waiter = launch {
            try {
                transport.bindAndCheck(candidate)
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertTrue(thrown is RootAccessException)
        assertEquals(RootStatus.GRANTED_NOT_READY, transport.state.value.status)
        assertNotEquals(RootStatus.READY, transport.state.value.status)
    }

    @Test
    fun healthWatchdogMarksWaitOverAndCleansUpAfterIpcReturns() = runTest {
        val gate = CountDownLatch(1)
        val service = FakeCodecService(dumpResult = "[]", dumpGate = gate)
        val backend = FakeBindBackend().apply { this.service = service }
        val (transport, _) = newTransport(backend, ioDispatcher = realIoDispatcher())
        val candidate = toCandidate(transport)
        try {
            var thrown: Throwable? = null
            val waiter = launch {
                try {
                    transport.bindAndCheck(candidate)
                } catch (t: Throwable) {
                    thrown = t
                }
            }
            runCurrent()                       // bind 完成，health IPC 在真实线程阻塞
            awaitReal { service.dumpCalls.get() == 1 }
            advanceTimeBy(8_000)               // 8 秒 health watchdog
            runCurrent()
            // watchdog 只标记等待结束：状态已落定，但 IPC 未归还前不清理
            assertEquals(RootStatus.GRANTED_NOT_READY, transport.state.value.status)
            assertEquals(0, backend.unbindCalls.get())

            gate.countDown()                   // IPC 实际归还
            awaitReal { service.dumpReturned }
            advanceUntilIdle()
            assertTrue(waiter.isCompleted)
            assertTrue(thrown is RootAccessException)
            assertEquals("IPC 归还后完成清理", 1, backend.unbindCalls.get())
            assertNotEquals(RootStatus.READY, transport.state.value.status)
        } finally {
            gate.countDown()
        }
    }
}
