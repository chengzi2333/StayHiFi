package com.chengzi.stayhifi.shell

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 冷进程前台自动恢复：自动路径不得绕过既有授权、健康检查与提交边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootForegroundRestoreTest {

    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class FakeCodecService(
        var dumpDevicesRaw: String,
        private val dumpDevicesGate: CountDownLatch? = null,
    ) : ICodecService {
        val dumpDevicesCalls = AtomicInteger()
        override fun dumpDevices(): String {
            dumpDevicesCalls.incrementAndGet()
            dumpDevicesGate?.await(30, TimeUnit.SECONDS)
            return dumpDevicesRaw
        }
        override fun readConfig(mac: String?): String = "{}"
        override fun writeConfig(mac: String?, configJson: String?): String = "{}"
        override fun dumpBtState(): String = "{}"
        override fun dumpRealtimeDiagnostics(codecName: String?): String = EMPTY_REALTIME_DUMP
        override fun destroy() = Unit
        override fun asBinder(): IBinder? = null
    }

    private class FakeRootBackend(
        var granted: Boolean? = null,
        dumpDevicesRaw: String = "[]",
        dumpDevicesGate: CountDownLatch? = null,
    ) : RootBackend {
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        private val bindingIds = AtomicLong(0)
        val service = FakeCodecService(dumpDevicesRaw, dumpDevicesGate)

        override fun grantedState(): Boolean? = granted

        override fun openShell(): RootShellObservation {
            openShellCalls.incrementAndGet()
            granted = true
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
        override val isRunning: Boolean get() = true
        override val hasPermission: Boolean get() = true
        override suspend fun requestPermission(activity: android.app.Activity): Boolean = true
        override fun bind(): ICodecService = error("本测试不应绑定 Shizuku")
        override fun unbind() = Unit
    }

    private class FakeStore(val raw: MutableSharedFlow<String?>) : ProviderSelectionStore {
        val writes = Collections.synchronizedList(mutableListOf<String>())
        var writeGate: CompletableDeferred<Unit>? = null
        override val rawSelection: Flow<String?> = raw
        override suspend fun setRawSelection(value: String) {
            writeGate?.await()
            writes.add(value)
        }
    }

    private class Harness(
        val backend: FakeRootBackend,
        val transport: RootTransport,
        val coordinator: ProviderCoordinator,
        val raw: MutableSharedFlow<String?>,
        val store: FakeStore,
        val logger: RootDiagnosticLogger,
        val sink: MutableList<String>,
    )

    private fun TestScope.harness(
        dumpDevicesRaw: String = "[]",
        dumpDevicesGate: CountDownLatch? = null,
        realHealthIo: Boolean = false,
    ): Harness {
        val backend = FakeRootBackend(
            dumpDevicesRaw = dumpDevicesRaw,
            dumpDevicesGate = dumpDevicesGate,
        )
        val sink = Collections.synchronizedList(mutableListOf<String>())
        val logger = RootDiagnosticLogger { line -> sink.add(line) }
        val transport = RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = if (realHealthIo) Dispatchers.IO else StandardTestDispatcher(testScheduler),
            logger = logger,
        )
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val store = FakeStore(raw)
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = store,
            shizukuFactory = { FakeShellProvider() },
            rootStatusSource = ProviderCoordinator.RootStatusSource { backend.grantedState() },
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            rootLogger = logger,
            log = {},
            applyLock = Mutex(),
        )
        return Harness(backend, transport, coordinator, raw, store, logger, sink)
    }

    private val owner = ProviderOwner("main-activity")

    private suspend fun TestScope.select(h: Harness, stored: String) {
        advanceUntilIdle()   // 先启动 Coordinator collect，避免无 replay 的选择事件在订阅前丢失
        h.raw.emit(stored)
        advanceUntilIdle()
        assertEquals(stored, h.coordinator.selectedProvider.value?.stored)
    }

    private suspend fun TestScope.restore(h: Harness): RootVerificationResult? {
        val pending = async { h.coordinator.restoreRootFromAppForeground(owner) }
        advanceUntilIdle()
        assertTrue("自动恢复调用应已结束", pending.isCompleted)
        return pending.await()
    }

    private suspend fun TestScope.verifyManually(h: Harness): RootVerificationResult {
        val pending = async {
            h.coordinator.verifyRootFromForeground(owner, ProviderSource.SETTINGS)
        }
        advanceUntilIdle()
        assertTrue("手动验证调用应已结束", pending.isCompleted)
        return pending.await()
    }

    private fun TestScope.pumpUntilReal(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        assertTrue("真实健康检查线程条件未在 5s 内满足", condition())
    }

    @Test
    fun coldRootRestoresOnceWithAppForegroundSource() = runTest {
        val h = harness()
        select(h, "root")

        val result = restore(h)

        assertTrue("冷 Root 应自动恢复，实际 $result", result is RootVerificationResult.Committed)
        assertEquals(RootStatus.READY, h.transport.state.value.status)
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())
        assertEquals(listOf("root"), h.store.writes)

        assertNull("同一进程不得再次自动恢复", restore(h))
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())

        h.logger.flushSuppressed()
        val lines = synchronized(h.sink) { h.sink.toList() }
        assertTrue(lines.any {
            it.contains("event=root_attempt_start") && it.contains("source=app_foreground")
        })
        assertFalse(lines.any {
            it.contains("event=root_attempt_start") && it.contains("source=settings")
        })
    }

    @Test
    fun concurrentForegroundCallsHaveSinglePhysicalAttempt() = runTest {
        val h = harness()
        select(h, "root")

        val first = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.restoreRootFromAppForeground(owner)
        }
        val second = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.restoreRootFromAppForeground(owner)
        }
        advanceUntilIdle()

        assertTrue(first.getCompleted() is RootVerificationResult.Committed)
        assertNull(second.getCompleted())
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())
        assertEquals(listOf("root"), h.store.writes)
    }

    @Test
    fun selectionReadIsAwaitedAndShizukuNeverTouchesRoot() = runTest {
        val h = harness()
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.restoreRootFromAppForeground(owner)
        }
        runCurrent()
        assertFalse("首读前必须等待", pending.isCompleted)
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())

        h.raw.emit("shizuku")
        advanceUntilIdle()

        assertNull(pending.getCompleted())
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())
        assertTrue(h.store.writes.isEmpty())
    }

    @Test
    fun readyRootDoesNotStartAutomaticRestore() = runTest {
        val h = harness()
        select(h, "root")
        assertTrue(verifyManually(h) is RootVerificationResult.Committed)
        val writes = h.store.writes.toList()

        assertNull(restore(h))
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())
        assertEquals(writes, h.store.writes)
    }

    @Test
    fun healthFailureDoesNotAutoRetryButManualRetryRemainsAvailable() = runTest {
        val h = harness(dumpDevicesRaw = "{\"error\":\"A2DP 代理不可用 enabled=false\"}")
        select(h, "root")

        val failed = restore(h)
        assertTrue(failed is RootVerificationResult.Failed)
        assertEquals(RootStatus.GRANTED_NOT_READY, (failed as RootVerificationResult.Failed).status)
        assertEquals(true, h.transport.state.value.granted)
        assertEquals(RootShellStatus.ROOT_SHELL, h.transport.state.value.shellStatus)
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())

        assertNull("健康失败不得形成自动重试循环", restore(h))
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())

        h.backend.service.dumpDevicesRaw = "[]"
        assertTrue("显式手动重试必须仍可用", verifyManually(h) is RootVerificationResult.Committed)
        assertEquals(RootStatus.READY, h.transport.state.value.status)
        assertEquals(2, h.backend.openShellCalls.get())
        assertEquals(2, h.backend.bindCalls.get())
        assertEquals("失败不提交，手动成功只写一次", listOf("root"), h.store.writes)
    }

    @Test
    fun manualHealthFailureIsNotRetriedByFirstAutomaticCall() = runTest {
        val h = harness(dumpDevicesRaw = "{\"error\":\"health failed\"}")
        select(h, "root")
        assertTrue(verifyManually(h) is RootVerificationResult.Failed)
        assertEquals(RootStatus.GRANTED_NOT_READY, h.transport.state.value.status)

        assertNull(restore(h))
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())
    }

    @Test
    fun backgroundAcquireDoesNotOpenShellOrConsumeAutomaticRecovery() = runTest {
        val h = harness()
        select(h, "root")

        try {
            h.coordinator.acquire(ProviderOwner("listener"), ProviderSource.LISTENER)
            fail("后台冷 Root 不得直接获得 lease")
        } catch (e: ProviderAccessException) {
            assertEquals(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION, e.reason)
        }
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())

        assertTrue(restore(h) is RootVerificationResult.Committed)
        assertEquals(1, h.backend.openShellCalls.get())
    }

    @Test
    fun manualVerifyIsRejectedDuringAutomaticCommitWithoutDuplicateWork() = runTest {
        val h = harness()
        select(h, "root")
        val writeGate = CompletableDeferred<Unit>()
        h.store.writeGate = writeGate
        val automatic = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.restoreRootFromAppForeground(owner)
        }
        repeat(20) {
            if (h.coordinator.switching.value) return@repeat
            runCurrent()
        }
        assertTrue("自动恢复应停在提交写盘点", h.coordinator.switching.value)
        assertEquals(RootStatus.READY, h.transport.state.value.status)

        val manual = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.verifyRootFromForeground(owner, ProviderSource.SETTINGS)
        }
        assertTrue("SWITCHING 必须立即拒绝手动重入", manual.isCompleted)
        val manualResult = manual.getCompleted()
        assertTrue(manualResult is RootVerificationResult.NotCommitted)
        assertEquals(
            SwitchFailureReason.ALREADY_SWITCHING,
            (manualResult as RootVerificationResult.NotCommitted).reason,
        )

        writeGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(automatic.getCompleted() is RootVerificationResult.Committed)
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())
        assertEquals(listOf("root"), h.store.writes)
    }

    @Test
    fun cancelledAutomaticCandidateCannotOverrideShizukuSwitch() = runTest {
        val healthGate = CountDownLatch(1)
        val h = harness(dumpDevicesGate = healthGate, realHealthIo = true)
        select(h, "root")
        val automatic = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.restoreRootFromAppForeground(owner)
        }
        pumpUntilReal { h.backend.service.dumpDevicesCalls.get() == 1 }
        assertEquals(RootStatus.BINDING, h.transport.state.value.status)

        // 用户在健康检查仍未归还时明确改选 Shizuku：作废旧 attempt 并等待切换终态。
        val switching = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.switchToShizuku(owner, ProviderSource.SETTINGS)
        }
        runCurrent()
        healthGate.countDown()
        pumpUntilReal { automatic.isCompleted && switching.isCompleted }
        advanceUntilIdle()

        val autoResult = automatic.getCompleted()
        assertTrue("已取消 candidate 不得提交，实际 $autoResult", autoResult is RootVerificationResult.Failed)
        assertEquals(RootStatus.UNVERIFIED, (autoResult as RootVerificationResult.Failed).status)
        assertTrue(switching.getCompleted() is ProviderSwitchResult.Committed)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.activeProvider.value)
        assertEquals("不得把 Root 迟到写回覆盖显式 Shizuku", listOf("shizuku"), h.store.writes)
        assertFalse("已取消 candidate 不得留下 READY anchor", h.transport.state.value.status == RootStatus.READY)
        assertEquals("迟到/取消 binding 必须解绑", 1, h.backend.unbindCalls.get())
        healthGate.countDown()
    }
}
