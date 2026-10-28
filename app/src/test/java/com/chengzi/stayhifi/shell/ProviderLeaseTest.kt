package com.chengzi.stayhifi.shell

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task D6：owner+generation lease、Root IPC 串行与后台非交互（设计 §11.3/§11.4.5）。
 *  - Root 两个 lease 并发 IPC 经独立 Mutex 串行，最大并发为 1；
 *  - Shizuku 每 owner 一个 provider，close 各自 unbind；
 *  - 关闭 Activity owner lease 不解 Root anchor（anchor 属进程级）；
 *  - 新进程持久 Root：acquire/isRunning/hasPermission/bind 零 openShell、零 RootService.bind、
 *    零 Shizuku 回退，只记一次去重的 NEEDS_FOREGROUND_REVALIDATION；
 *  - READY 后后台可复用；DISCONNECTED 后 acquire/withService 均拒绝并要求前台重验；
 *  - 代次失效：selection 变化后旧 lease withService 得 STALE_GENERATION。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderLeaseTest {

    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class RecordingSink : (String) -> Unit {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        override fun invoke(line: String) {
            lines.add(line)
        }
        fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
    }

    private class FakeCodecService : ICodecService {
        override fun dumpDevices(): String = "[]"
        override fun readConfig(mac: String?): String = "{}"
        override fun writeConfig(mac: String?, configJson: String?): String = "{}"
        override fun dumpBtState(): String = "{}"
        override fun destroy() = Unit
        override fun asBinder(): IBinder? = null
    }

    private class FakeRootBackend(
        var granted: Boolean? = null,
    ) : RootBackend {
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        @Volatile var lastCallbacks: RootBindCallbacks? = null
        private val bindingIds = AtomicLong(0)
        val service = FakeCodecService()

        override fun grantedState(): Boolean? = granted
        override fun openShell(): RootShellObservation {
            openShellCalls.incrementAndGet()
            return RootShellObservation(isRoot = true)
        }

        override fun bind(callbacks: RootBindCallbacks): RootBinding {
            bindCalls.incrementAndGet()
            lastCallbacks = callbacks
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
        override val isRunning: Boolean get() = true
        override val hasPermission: Boolean get() = true
        override suspend fun requestPermission(activity: android.app.Activity): Boolean = true
        override fun bind(): ICodecService {
            bindCalls.incrementAndGet()
            return FakeCodecService()
        }
        override fun unbind() {
            unbindCalls.incrementAndGet()
        }
    }

    private class ShizukuFactory {
        val created = Collections.synchronizedList(mutableListOf<FakeShellProvider>())
        fun create(): ShellProvider = FakeShellProvider().also { created.add(it) }
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
        val logger: RootDiagnosticLogger,
        val sink: RecordingSink,
        val raw: MutableSharedFlow<String?>,
        val store: FakeStore,
    )

    private fun TestScope.harness(): Harness {
        val backend = FakeRootBackend()
        val sink = RecordingSink()
        val logger = RootDiagnosticLogger(sink)
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
            rootLogger = logger,
        )
        return Harness(transport, backend, coordinator, factory, logger, sink, raw, store)
    }

    private fun TestScope.driveToReady(h: Harness) {
        val attempt = h.transport.beginForegroundAuthorization(
            ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        var thrown: Throwable? = null
        val waiter = launch {
            try {
                h.transport.bindAndCheck(h.transport.awaitCandidate(attempt))
            } catch (t: Throwable) {
                thrown = t
            }
        }
        advanceUntilIdle()
        assertTrue(waiter.isCompleted)
        assertNull("前台验证应成功", thrown)
        assertEquals(RootStatus.READY, h.transport.state.value.status)
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())
    }

    private suspend fun assertAccessFails(expected: ProviderAccessReason, block: suspend () -> Any?) {
        try {
            block()
            fail("期望 ProviderAccessException($expected)，实际成功返回")
        } catch (e: ProviderAccessException) {
            assertEquals(expected, e.reason)
        }
    }

    // ─────────────────────────── Root IPC 串行 ───────────────────────────

    @Test
    fun rootIpcSerializedAcrossLeases() = runTest {
        val h = harness()
        advanceUntilIdle()   // 启动 collect
        h.raw.emit("root")
        driveToReady(h)

        val lease1 = h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        val lease2 = h.coordinator.acquire(ProviderOwner("listener"), ProviderSource.LISTENER)
        assertEquals(ProviderKind.ROOT, lease1.kind)
        assertTrue(lease1.isCurrent())

        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val calls = AtomicInteger()
        val jobs = (1..20).map {
            launch {
                lease1.withService { _ ->
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { m -> maxOf(m, now) }
                    delay(1)              // 暂停窗口：无 Mutex 时并发 >1
                    calls.incrementAndGet()
                    concurrent.decrementAndGet()
                }
            }
        } + (1..20).map {
            launch {
                lease2.withService { _ ->
                    val now = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { m -> maxOf(m, now) }
                    delay(1)
                    calls.incrementAndGet()
                    concurrent.decrementAndGet()
                }
            }
        }
        advanceUntilIdle()
        jobs.forEach { assertTrue(it.isCompleted) }
        assertEquals(40, calls.get())
        assertEquals("Root IPC 必须经独立 Mutex 串行（§11.3）", 1, maxConcurrent.get())
    }

    // ─────────────────────────── Shizuku 每 owner 绑定 ───────────────────────────

    @Test
    fun shizukuLeasePerOwnerBindsAndUnbindsIndependently() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")

        val l1 = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        val l2 = h.coordinator.acquire(ProviderOwner("qs"), ProviderSource.QS)
        assertEquals("Shizuku 每 owner 一个 provider", 2, h.shizukuFactory.created.size)
        val p1 = h.shizukuFactory.created[0]
        val p2 = h.shizukuFactory.created[1]

        l1.withService { }
        l2.withService { }
        advanceUntilIdle()
        assertEquals(1, p1.bindCalls.get())
        assertEquals(1, p2.bindCalls.get())

        l1.close()
        assertEquals(1, p1.unbindCalls.get())
        assertEquals(0, p2.unbindCalls.get())
        l2.close()
        assertEquals(1, p2.unbindCalls.get())

        assertAccessFails(ProviderAccessReason.LEASE_CLOSED) { l1.withService { } }
    }

    // ─────────────────────────── anchor 与 lease 生命周期分离 ───────────────────────────

    @Test
    fun closingActivityLeaseKeepsRootAnchor() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        val activityLease = h.coordinator.acquire(ProviderOwner("main-activity"), ProviderSource.ONBOARDING)
        activityLease.close()
        advanceUntilIdle()
        assertEquals("Activity owner 的 close 不得解 Root anchor（§11.3）", 0, h.backend.unbindCalls.get())
        assertEquals(RootStatus.READY, h.transport.state.value.status)

        // 后台新 owner 仍可复用 READY anchor
        val applyLease = h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        var ok = false
        applyLease.withService { ok = true }
        assertTrue(ok)
    }

    // ─────────────────────────── 冷进程后台非交互 ───────────────────────────

    @Test
    fun coldProcessRootNeverInteractsAndLogsOnce() = runTest {
        val h = harness()
        // 首读前 noteBackground 静默（不产生日志）
        h.coordinator.noteBackground(ProviderSource.BOOT)
        h.logger.flushSuppressed()
        assertTrue(h.sink.snapshot().isEmpty())

        advanceUntilIdle()   // 启动 collect
        h.raw.emit("root")

        // 新进程持久 Root：acquire 拒绝并要求前台重验，且零交互
        assertAccessFails(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION) {
            h.coordinator.acquire(ProviderOwner("boot"), ProviderSource.BOOT)
        }
        val provider = RootShellProvider(h.transport)
        assertFalse(provider.isRunning)
        assertFalse(provider.hasPermission)
        try {
            provider.bind()
            fail("未 READY 的 bind 必须失败")
        } catch (e: RootAccessException) {
            assertEquals(RootStatus.NEEDS_FOREGROUND_REVALIDATION, e.status)
        }
        // 四种后台 source 重复未就绪：去重后只输出一条
        h.coordinator.noteBackground(ProviderSource.BOOT)
        h.coordinator.noteBackground(ProviderSource.LISTENER)
        h.coordinator.noteBackground(ProviderSource.QS)
        h.coordinator.noteBackground(ProviderSource.APPLY)
        h.logger.flushSuppressed()
        val revalidationLines = h.sink.snapshot().filter { it.contains("needs_foreground_revalidation") }
        assertEquals("后台重复未就绪去重为一条", 1, revalidationLines.size)

        assertEquals("绝不自动 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("绝不自动 RootService.bind", 0, h.backend.bindCalls.get())
        assertEquals("绝不自动回退 Shizuku", 0, h.shizukuFactory.created.size)
        assertEquals("绝不自动切换持久选择", 0, h.store.writes.size)
    }

    @Test
    fun readyReusableThenDisconnectedNotReusable() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        // READY 后后台 lease 可复用（无新增 openShell/bind）
        val lease = h.coordinator.acquire(ProviderOwner("listener"), ProviderSource.LISTENER)
        var ok = false
        lease.withService { ok = true }
        assertTrue(ok)
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())

        // anchor 远端断开：acquire 与存量 lease 均拒绝
        h.backend.lastCallbacks!!.onDisconnected()
        advanceUntilIdle()
        assertEquals(RootStatus.DISCONNECTED, h.transport.state.value.status)
        assertAccessFails(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION) {
            h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        }
        assertAccessFails(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION) {
            lease.withService { }
        }
        assertEquals("断开后也不准再次 openShell", 1, h.backend.openShellCalls.get())
    }

    // ─────────────────────────── 代次失效与首读门控 ───────────────────────────

    @Test
    fun selectionChangeStalesOldLease() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        val lease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        assertTrue(lease.isCurrent())

        h.raw.emit("root")   // 选择变化 → 新代次
        advanceUntilIdle()
        assertFalse(lease.isCurrent())
        assertAccessFails(ProviderAccessReason.STALE_GENERATION) { lease.withService { } }
    }

    @Test
    fun acquireBeforeFirstReadBlocksAndTouchesNothing() = runTest {
        val h = harness()
        val pending = async { h.coordinator.acquire(ProviderOwner("boot"), ProviderSource.BOOT) }
        advanceUntilIdle()
        assertFalse("首读前 acquire 不得返回", pending.isCompleted)
        assertEquals(0, h.shizukuFactory.created.size)
        assertEquals(0, h.backend.openShellCalls.get())

        h.raw.emit("shizuku")
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        assertEquals(ProviderKind.SHIZUKU, pending.getCompleted().kind)
    }
}
