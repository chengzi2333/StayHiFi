package com.chengzi.stayhifi.trigger

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.shell.ProviderAccessException
import com.chengzi.stayhifi.shell.ProviderAccessReason
import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.ProviderKind
import com.chengzi.stayhifi.shell.ProviderLease
import com.chengzi.stayhifi.shell.ProviderOwner
import com.chengzi.stayhifi.shell.ProviderSource
import com.chengzi.stayhifi.shell.RootBackend
import com.chengzi.stayhifi.shell.RootBindCallbacks
import com.chengzi.stayhifi.shell.RootBinding
import com.chengzi.stayhifi.shell.RootShellObservation
import com.chengzi.stayhifi.shell.RootStatus
import com.chengzi.stayhifi.shell.RootTransport
import com.chengzi.stayhifi.shell.ProviderSelectionStore
import com.chengzi.stayhifi.shell.ShellProvider
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task D7：ApplyProviderGate 调用契约（设计 §11.3）。
 *  - 顺序：lock → awaitSelection/acquire → IPC → close → unlock；
 *  - 排队期间选择被切换后，拿到锁的任务取的是最新 provider（§11.3 锁后解析）；
 *  - 默认锁即全局 [ApplyLock.mutex]；block 抛异常时 lease 照常 close、锁照常释放。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplyProviderGateTest {

    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class FakeCodecService : ICodecService {
        override fun dumpDevices(): String = "[]"
        override fun readConfig(mac: String?): String = "{}"
        override fun writeConfig(mac: String?, configJson: String?): String = "{}"
        override fun dumpBtState(): String = "{}"
        override fun dumpRealtimeDiagnostics(codecName: String?): String = com.chengzi.stayhifi.shell.EMPTY_REALTIME_DUMP
        override fun destroy() = Unit
        override fun asBinder(): IBinder? = null
    }

    private class FakeRootBackend : RootBackend {
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        val service = FakeCodecService()
        private val bindingIds = AtomicLong(0)
        override fun grantedState(): Boolean? = true
        override fun openShell(): RootShellObservation {
            openShellCalls.incrementAndGet()
            return RootShellObservation(isRoot = true)
        }
        override fun bind(callbacks: RootBindCallbacks): RootBinding {
            bindCalls.incrementAndGet()
            callbacks.onConnected(service)
            return FakeBinding(bindingIds.incrementAndGet())
        }
        override fun unbind(binding: RootBinding) {
            unbindCalls.incrementAndGet()
        }
        private class FakeBinding(override val id: Long) : RootBinding
    }

    private class FakeShellProvider : ShellProvider {
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        var onBind: (() -> Unit)? = null
        var onUnbind: (() -> Unit)? = null
        override val isRunning: Boolean get() = true
        override val hasPermission: Boolean get() = true
        override suspend fun requestPermission(activity: android.app.Activity): Boolean = true
        override fun bind(): ICodecService {
            bindCalls.incrementAndGet()
            onBind?.invoke()
            return FakeCodecService()
        }
        override fun unbind() {
            unbindCalls.incrementAndGet()
            onUnbind?.invoke()
        }
    }

    private class ShizukuFactory {
        val created = Collections.synchronizedList(mutableListOf<FakeShellProvider>())
        var onCreate: ((FakeShellProvider) -> Unit)? = null
        fun create(): ShellProvider = FakeShellProvider().also {
            created.add(it)
            onCreate?.invoke(it)
        }
    }

    private class FakeStore(val raw: MutableSharedFlow<String?>) : ProviderSelectionStore {
        val writes = Collections.synchronizedList(mutableListOf<String>())
        override val rawSelection: Flow<String?> = raw
        override suspend fun setRawSelection(value: String) {
            writes.add(value)
        }
    }

    private class Harness(
        val transport: RootTransport,
        val backend: FakeRootBackend,
        val coordinator: ProviderCoordinator,
        val shizukuFactory: ShizukuFactory,
        val raw: MutableSharedFlow<String?>,
        val store: FakeStore,
    )

    private fun TestScope.harness(): Harness {
        val backend = FakeRootBackend()
        // 注入内存 sink：默认 RootDiagnosticLogger 落 LogStore（android.util.Log）在 JVM 单测未 mock
        val logger = com.chengzi.stayhifi.shell.RootDiagnosticLogger { }
        val transport = RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = logger,
        )
        val factory = ShizukuFactory()
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val store = FakeStore(raw)
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = store,
            shizukuFactory = factory::create,
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        return Harness(transport, backend, coordinator, factory, raw, store)
    }

    private fun TestScope.driveToReady(h: Harness) {
        val attempt = h.transport.beginForegroundAuthorization(
            ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        val waiter = launch {
            h.transport.bindAndCheck(h.transport.awaitCandidate(attempt))
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertEquals(RootStatus.READY, h.transport.state.value.status)
    }

    // ─────────────────────────── 顺序契约 ───────────────────────────

    @Test
    fun withLeaseOrdersLockAcquireIpcCloseUnlock() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val lock = Mutex()
        val gate = ApplyProviderGate(lock)
        val events = Collections.synchronizedList(mutableListOf<String>())
        h.shizukuFactory.onCreate = { provider ->
            events += "acquire locked=${lock.isLocked}"
            provider.onBind = { events += "bind locked=${lock.isLocked}" }
            provider.onUnbind = { events += "close locked=${lock.isLocked}" }
        }

        val result = gate.withLease(h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY) { lease ->
            lease.withService { events += "ipc locked=${lock.isLocked}" }
            "ok"
        }
        events += "returned locked=${lock.isLocked}"

        assertEquals("ok", result)
        assertEquals(
            "顺序必须为 lock→acquire→IPC→unlock；解绑延迟到空闲窗口之后",
            listOf(
                "acquire locked=true",
                "bind locked=true",
                "ipc locked=true",
                "returned locked=false",
            ),
            events,
        )
        advanceTimeBy(90_000)
        advanceUntilIdle()
        assertEquals("空闲窗口结束后解绑，此时锁已释放", "close locked=false", events.last())
    }

    @Test
    fun defaultGateUsesGlobalApplyLock() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val gate = ApplyProviderGate()
        gate.withLease(h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY) {
            assertTrue("默认锁必须即全局 ApplyLock.mutex", ApplyLock.mutex.isLocked)
        }
        assertFalse(ApplyLock.mutex.isLocked)
    }

    // ─────────────────────────── 排队期间选择切换取最新 provider ───────────────────────────

    @Test
    fun queuedTaskTakesLatestProviderAfterSelectionSwitch() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        val lock = Mutex()
        val gate = ApplyProviderGate(lock)
        var firstKind: ProviderKind? = null
        var secondKind: ProviderKind? = null

        val first = async {
            gate.withLease(h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY) { lease ->
                firstKind = lease.kind
                delay(10_000)   // 持锁窗口
            }
        }
        runCurrent()
        assertTrue(lock.isLocked)
        assertEquals(ProviderKind.ROOT, firstKind)

        val second = async {
            gate.withLease(h.coordinator, ProviderOwner("listener"), ProviderSource.LISTENER) { lease ->
                secondKind = lease.kind
            }
        }
        runCurrent()
        assertNull("第二个任务必须排队等锁", secondKind)

        // 排队期间选择被切换：拿到锁后必须取最新 provider（§11.3 锁后解析）
        h.raw.emit("shizuku")
        advanceUntilIdle()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(ProviderKind.ROOT, firstKind)
        assertEquals("排队任务不得用排队前的旧通道", ProviderKind.SHIZUKU, secondKind)
        assertEquals("只有切到 Shizuku 的那个任务创建了 provider", 1, h.shizukuFactory.created.size)
    }

    // ─────────────────────────── 异常路径释放 ───────────────────────────

    @Test
    fun blockExceptionClosesLeaseAndReleasesLock() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val lock = Mutex()
        val gate = ApplyProviderGate(lock)
        try {
            gate.withLease(h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY) {
                throw RuntimeException("boom")
            }
            fail("异常必须向上传播")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
        assertFalse("异常后锁必须释放", lock.isLocked)
        assertEquals("异常后 lease 立即 close（计数归零），解绑在空闲窗口之后",
            0, h.shizukuFactory.created.single().unbindCalls.get())
        advanceTimeBy(90_000)
        advanceUntilIdle()
        assertEquals("空闲窗口结束后解绑一次",
            1, h.shizukuFactory.created.single().unbindCalls.get())
    }

    @Test
    fun leaseGrantAndCloseExposeProviderIdentityAndGeneration() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()
        val logs = mutableListOf<String>()
        val gate = ApplyProviderGate(Mutex(), logs::add)
        var observedGeneration = -1L

        gate.withLease(h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY) { lease ->
            observedGeneration = lease.generation
        }

        assertTrue(logs.any {
            it.contains("event=provider_gate_lock owner=apply source=APPLY")
        })
        assertTrue(logs.any {
            it.contains("event=provider_gate_granted owner=apply source=APPLY") &&
                it.contains("kind=SHIZUKU generation=$observedGeneration")
        })
        assertTrue(logs.any {
            it.contains("event=provider_gate_closed owner=apply source=APPLY")
        })
    }

    @Test
    fun coldRootRejectionLogsExactReasonWithoutInteractiveWork() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        advanceUntilIdle()
        val logs = mutableListOf<String>()
        val gate = ApplyProviderGate(Mutex(), logs::add)

        val error = runCatching {
            gate.withLease(h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY) { }
        }.exceptionOrNull()

        assertTrue(error is ProviderAccessException)
        assertEquals(
            ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION,
            (error as ProviderAccessException).reason,
        )
        assertTrue(logs.any {
            it.contains("event=provider_gate_rejected owner=apply source=APPLY") &&
                it.contains("reason=NEEDS_FOREGROUND_REVALIDATION")
        })
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())
        assertEquals(0, h.shizukuFactory.created.size)
    }
}
