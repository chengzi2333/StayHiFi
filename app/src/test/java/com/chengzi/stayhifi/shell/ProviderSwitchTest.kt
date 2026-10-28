package com.chengzi.stayhifi.shell

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Task D7：有界 provider 切换事务（设计 §11.3）。
 *  - 20 秒有界等待：ApplyLock 被占 / Root 活动 IPC 未归零 / DataStore 写盘挂起均超时，
 *    保留原 kind+generation，提示稍后重试；
 *  - SWITCHING 期间拒绝新 session（acquire/verify/二次切换）；
 *  - 成功路径顺序：作废旧代次 → 主线程幂等 unbind 旧 Root anchor → 写盘 → 发布新 generation；
 *  - 写盘失败：不显示成功、不采用 candidate、保留原选择；旧 Root 已按事务解绑时
 *    明确 NEEDS_FOREGROUND_REVALIDATION；旧 lease 后续 withService 得 STALE_GENERATION；
 *  - 手动退回 Shizuku 不检查 Root 可用性；取消的 attempt 迟到成功不得提交。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSwitchTest {

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
        val grantedCalls = AtomicInteger()
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        @Volatile var lastCallbacks: RootBindCallbacks? = null
        private val bindingIds = AtomicLong(0)
        val service = FakeCodecService()

        override fun grantedState(): Boolean? {
            grantedCalls.incrementAndGet()
            return granted
        }

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

    /**
     * 不可取消底层写模型（final review fix）：setRawSelection 的等待可被 20s 切换超时或
     * 调用方取消打断，但底层落盘在独立协程继续，[commit] 放行时仍完成并回读 target——
     * 复现「超时/取消已返回，迟写仍把 rawSelection 改成 target」的审查发现。
     */
    private class LateWrite(val scope: CoroutineScope) {
        val started = CompletableDeferred<String>()
        val commit = CompletableDeferred<Unit>()
    }

    /** 可控写盘：writeGate 挂起、writeFailure 抛错、onWrite 在写盘时点观察事务状态。
     *  [persisted] 模拟落盘后的持久值（下次进程启动会恢复它）；
     *  [diskMutex] 模拟 DataStore 串行 edit actor——按 edit 提交顺序落盘（迟写先提交先落盘，
     *  补偿写后提交排在其后）。 */
    private class FakeStore(val raw: MutableSharedFlow<String?>) : ProviderSelectionStore {
        val writes = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var persisted: String? = null
        @Volatile var writeGate: CompletableDeferred<Unit>? = null
        @Volatile var writeFailure: Throwable? = null
        @Volatile var onWrite: (() -> Unit)? = null
        @Volatile var lateWrite: LateWrite? = null
        val diskMutex = Mutex()
        override val rawSelection: Flow<String?> = raw
        override suspend fun setRawSelection(value: String) {
            writeFailure?.let { throw it }
            val lw = lateWrite
            if (lw != null) {
                writes.add(value)
                lw.started.complete(value)
                // 底层落盘在独立协程但按提交顺序占用串行 actor：本函数被取消后，
                // commit 放行时仍完成并回读；排在其后提交的补偿写必须等它落盘
                lw.scope.launch {
                    diskMutex.withLock {
                        lw.commit.await()
                        persisted = value   // 迟写最终落盘
                        raw.emit(value)
                    }
                }
                // 挂起等待底层归还；切换超时/调用方取消会取消此等待，但底层写不受 cancel 影响
                kotlinx.coroutines.awaitCancellation()
            }
            diskMutex.withLock {
                writeGate?.await()
                writes.add(value)
                persisted = value
                onWrite?.invoke()
            }
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
        val applyLock: Mutex,
        val coordLogs: MutableList<String>,
    )

    private fun TestScope.harness(granted: Boolean? = true): Harness {
        val backend = FakeRootBackend(granted = granted)
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
        val applyLock = Mutex()
        val coordLogs = Collections.synchronizedList(mutableListOf<String>())
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = store,
            shizukuFactory = factory::create,
            log = { coordLogs += it },   // JVM 单测无 android.util.Log；记录以便断言补偿写行为
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            rootLogger = logger,
            applyLock = applyLock,
        )
        return Harness(transport, backend, coordinator, factory, logger, sink, raw, store, applyLock,
            coordLogs)
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
    }

    private suspend fun assertAccessFails(expected: ProviderAccessReason, block: suspend () -> Any?) {
        try {
            block()
            fail("期望 ProviderAccessException($expected)，实际成功返回")
        } catch (e: ProviderAccessException) {
            assertEquals(expected, e.reason)
        }
    }

    // ─────────────────────────── 成功路径顺序（Root→Shizuku） ───────────────────────────

    @Test
    fun switchToShizukuCommitsInOrder() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)
        val oldLease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        val genBefore = oldLease.generation

        // 写盘时点观察：旧代次必须已作废、旧 anchor 已解绑、新选择尚未发布、仍在 SWITCHING
        val writeObs = mutableListOf<String>()
        h.store.onWrite = {
            writeObs += "anchor=${h.transport.state.value.status}"
            writeObs += "stale=${!oldLease.isCurrent()}"
            writeObs += "selected=${h.coordinator.selectedProvider.value}"
            writeObs += "switching=${h.coordinator.switching.value}"
        }

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue("期望 Committed，实际 $result", result is ProviderSwitchResult.Committed)
        assertEquals(
            "成功路径顺序：作废旧代次→解绑旧 anchor→写盘→发布新 generation",
            listOf("anchor=DISCONNECTED", "stale=true", "selected=ROOT", "switching=true"),
            writeObs,
        )
        advanceUntilIdle()
        assertEquals("旧 Root anchor 主线程幂等 unbind 恰一次", 1, h.backend.unbindCalls.get())
        assertEquals(listOf("shizuku"), h.store.writes)

        // 写盘成功后新代次已发布
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.activeProvider.value)
        assertFalse(h.coordinator.switching.value)
        assertEquals(RootStatus.DISCONNECTED, h.transport.state.value.status)

        // 旧 lease 作废旧代次：withService 得 STALE_GENERATION
        assertFalse(oldLease.isCurrent())
        assertAccessFails(ProviderAccessReason.STALE_GENERATION) { oldLease.withService { } }

        val newLease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        assertEquals(ProviderKind.SHIZUKU, newLease.kind)
        assertTrue("新 lease 代次必须推进", newLease.generation > genBefore)

        // 切换终态日志（§11.6：provider 切换另记一条终态）
        h.logger.flushSuppressed()
        assertTrue(h.sink.snapshot().any {
            it.contains("provider_switch_end") && it.contains("committed")
        })
    }

    @Test
    fun switchToShizukuAlreadyShizukuIsNoOp() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()
        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue(result is ProviderSwitchResult.Committed)
        assertEquals("已是 Shizuku：不得重复写盘", 0, h.store.writes.size)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
    }

    // ─────────────────────────── 手动退回不检查 Root 可用性 ───────────────────────────

    @Test
    fun manualRetreatFromUnverifiedRootNeverTouchesBackend() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")   // 持久选择 Root，但新进程从未验证（Root 当前不可用）
        advanceUntilIdle()
        assertEquals(RootStatus.UNVERIFIED, h.transport.state.value.status)

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue("手动退回必须成功，实际 $result", result is ProviderSwitchResult.Committed)
        assertEquals(listOf("shizuku"), h.store.writes)
        assertEquals("手动 Shizuku 不检查 Root 可用性", 0, h.backend.grantedCalls.get())
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())
        assertEquals(0, h.backend.unbindCalls.get())
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
    }

    // ─────────────────────────── 20 秒有界等待超时 ───────────────────────────

    @Test
    fun applyLockHeldBeyondBudgetTimesOutAndPreservesSelection() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)
        val oldLease = h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)

        // 占用 ApplyLock 60 秒（虚拟时钟）：切换预算 20 秒先到期
        launch { h.applyLock.withLock { delay(60_000) } }
        runCurrent()
        assertTrue(h.applyLock.isLocked)

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue("期望 NotCommitted，实际 $result", result is ProviderSwitchResult.NotCommitted)
        result as ProviderSwitchResult.NotCommitted
        assertEquals(SwitchFailureReason.SWITCH_TIMEOUT, result.reason)
        assertFalse("anchor 未解绑，无需前台重验", result.needsForegroundRevalidation)

        // 超时保留原 kind+generation
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.ROOT, h.coordinator.activeProvider.value)
        assertTrue("旧代次必须保留", oldLease.isCurrent())
        assertEquals("超时不得写盘", 0, h.store.writes.size)
        assertEquals("超时不得解绑 anchor", 0, h.backend.unbindCalls.get())
        assertEquals(RootStatus.READY, h.transport.state.value.status)
        assertFalse(h.coordinator.switching.value)
    }

    @Test
    fun activeRootIpcBeyondBudgetTimesOutAndPreservesSelection() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)
        val lease = h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)

        // 60 秒活动 Root IPC：activeIpc 不归零，切换 20 秒预算先到期
        launch { lease.withService { delay(60_000) } }
        runCurrent()
        assertEquals(1, h.transport.activeIpcCount)

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue(result is ProviderSwitchResult.NotCommitted)
        result as ProviderSwitchResult.NotCommitted
        assertEquals(SwitchFailureReason.SWITCH_TIMEOUT, result.reason)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertTrue(lease.isCurrent())
        assertEquals(0, h.store.writes.size)
        assertEquals(0, h.backend.unbindCalls.get())
        assertEquals(RootStatus.READY, h.transport.state.value.status)

        // IPC 归还后 anchor 仍可复用（超时回退无副作用）
        var ok = false
        lease.withService { ok = true }
        assertTrue(ok)
    }

    @Test
    fun dataStoreHangBeyondBudgetTimesOutAndPreservesGeneration() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)
        val oldLease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)

        h.store.writeGate = CompletableDeferred()   // 写盘永不归还
        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue(result is ProviderSwitchResult.NotCommitted)
        result as ProviderSwitchResult.NotCommitted
        assertEquals(SwitchFailureReason.SWITCH_TIMEOUT, result.reason)
        // 写盘挂起超时时旧 Root 已按事务解绑：保留的选择是 Root 但 transport 不再 READY
        assertTrue(result.needsForegroundRevalidation)

        // 保留原 kind+generation（旧 lease 代次重新有效）
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertTrue("超时必须回退代次作废", oldLease.isCurrent())
        assertEquals("写盘未归还不得留下记录", 0, h.store.writes.size)
        advanceUntilIdle()
        assertEquals("旧 anchor 已按事务解绑一次", 1, h.backend.unbindCalls.get())
        assertEquals(RootStatus.DISCONNECTED, h.transport.state.value.status)
        assertFalse(h.coordinator.switching.value)
    }

    // ─────────────────────────── SWITCHING 拒绝新 session ───────────────────────────

    @Test
    fun switchingRejectsNewSessionsUntilCommitted() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        val gate = CompletableDeferred<Unit>()
        h.store.writeGate = gate
        // Unconfined：同步推进到写盘挂起点，使 SWITCHING 窗口可观察
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        }
        assertFalse(pending.isCompleted)
        assertTrue("切换事务必须置 SWITCHING", h.coordinator.switching.value)

        // SWITCHING 拒绝新 session
        assertAccessFails(ProviderAccessReason.SWITCHING) {
            h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        }
        val verify = h.coordinator.verifyRootFromForeground(
            ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        assertTrue(verify is RootVerificationResult.NotCommitted)
        assertEquals(SwitchFailureReason.ALREADY_SWITCHING,
            (verify as RootVerificationResult.NotCommitted).reason)
        val second = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue(second is ProviderSwitchResult.NotCommitted)
        assertEquals(SwitchFailureReason.ALREADY_SWITCHING,
            (second as ProviderSwitchResult.NotCommitted).reason)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        assertTrue(pending.getCompleted() is ProviderSwitchResult.Committed)
        assertEquals(listOf("shizuku"), h.store.writes)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertFalse(h.coordinator.switching.value)
        assertEquals("旧 anchor 幂等解绑恰一次", 1, h.backend.unbindCalls.get())
    }

    // ─────────────────────────── 前台 Root 验证 + 提交 ───────────────────────────

    @Test
    fun verifyRootFromForegroundCommitsOnlyAfterPersist() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()
        val oldShizukuLease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        assertEquals(ProviderKind.SHIZUKU, oldShizukuLease.kind)

        val gate = CompletableDeferred<Unit>()
        h.store.writeGate = gate
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.verifyRootFromForeground(
                ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        }
        // 逐步推进到写盘挂起点：runCurrent 不推进虚拟时钟，20 秒切换预算不会提前耗尽
        repeat(10) {
            if (h.coordinator.switching.value || pending.isCompleted) return@repeat
            runCurrent()
        }
        assertFalse("事务必须在写盘点挂起", pending.isCompleted)

        // auth+bind+health 已成功但写盘未完成：candidate 不得被采用
        assertEquals(RootStatus.READY, h.transport.state.value.status)
        assertEquals("candidate 期间 activeProvider 保持原值（§11.3）",
            ProviderKind.SHIZUKU, h.coordinator.activeProvider.value)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertTrue(h.coordinator.switching.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        val result = pending.getCompleted()
        assertTrue("期望 Committed，实际 $result", result is RootVerificationResult.Committed)
        assertEquals(listOf("root"), h.store.writes)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.ROOT, h.coordinator.activeProvider.value)
        assertFalse(h.coordinator.switching.value)

        // 旧 Shizuku 代次作废；新 Root lease 可用
        assertFalse(oldShizukuLease.isCurrent())
        assertAccessFails(ProviderAccessReason.STALE_GENERATION) { oldShizukuLease.withService { } }
        val rootLease = h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        assertEquals(ProviderKind.ROOT, rootLease.kind)
        var ok = false
        rootLease.withService { ok = true }
        assertTrue(ok)
    }

    @Test
    fun verifyRootAuthFailureKeepsSelectionAndWritesNothing() = runTest {
        val h = harness(granted = false)
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val result = h.coordinator.verifyRootFromForeground(
            ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue(result is RootVerificationResult.Failed)
        assertEquals(RootStatus.NOT_GRANTED, (result as RootVerificationResult.Failed).status)
        assertEquals("授权失败绝不写盘", 0, h.store.writes.size)
        assertEquals(0, h.backend.bindCalls.get())
        assertEquals(0, h.backend.openShellCalls.get())   // granted=false 短路，不开 shell
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertFalse(h.coordinator.switching.value)
    }

    @Test
    fun verifyRootPersistFailureDiscardsCandidate() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()
        h.store.writeFailure = IOException("disk full")

        val result = h.coordinator.verifyRootFromForeground(
            ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue("期望 NotCommitted，实际 $result", result is RootVerificationResult.NotCommitted)
        result as RootVerificationResult.NotCommitted
        assertEquals(SwitchFailureReason.PERSIST_FAILED, result.reason)
        assertFalse("保留的选择是 Shizuku，无需前台重验", result.needsForegroundRevalidation)

        // 写盘失败：不显示成功、不采用 candidate、保留原选择
        assertEquals(0, h.store.writes.size)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.activeProvider.value)
        assertEquals("candidate anchor 必须按事务回退", RootStatus.DISCONNECTED,
            h.transport.state.value.status)
        assertEquals("candidate anchor 幂等解绑恰一次", 1, h.backend.unbindCalls.get())
        assertFalse(h.coordinator.switching.value)

        // 原通道仍可用（失败终态发布了新代次承载原选择）
        val lease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        assertEquals(ProviderKind.SHIZUKU, lease.kind)
        assertTrue(lease.isCurrent())

        h.logger.flushSuppressed()
        assertTrue(h.sink.snapshot().any {
            it.contains("provider_switch_end") && it.contains("persist_failed")
        })
    }

    @Test
    fun switchPersistFailureKeepsRootAndFlagsRevalidation() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)
        val oldLease = h.coordinator.acquire(ProviderOwner("main"), ProviderSource.ONBOARDING)
        h.store.writeFailure = IOException("read-only fs")

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue(result is ProviderSwitchResult.NotCommitted)
        result as ProviderSwitchResult.NotCommitted
        assertEquals(SwitchFailureReason.PERSIST_FAILED, result.reason)
        assertTrue("旧 Root 已按事务解绑，保留的 Root 选择需前台重验",
            result.needsForegroundRevalidation)

        // 保留原选择（不写盘），但旧代次已作废、旧 anchor 已解绑
        assertEquals(0, h.store.writes.size)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertFalse(oldLease.isCurrent())
        assertAccessFails(ProviderAccessReason.STALE_GENERATION) { oldLease.withService { } }
        assertEquals(1, h.backend.unbindCalls.get())
        assertEquals(RootStatus.DISCONNECTED, h.transport.state.value.status)

        // 保留的 Root 选择在新代次下明确 NEEDS_FOREGROUND_REVALIDATION
        assertAccessFails(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION) {
            h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        }
        assertFalse(h.coordinator.switching.value)
    }

    // ─────────────────────────── 迟到写盘不得采用已放弃 target（final review fix） ───────────────────────────

    @Test
    fun lateStoreWriteAfterTimeoutNeverAdoptsTarget() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        // 底层写盘不可取消：20s 超时先返回 SWITCH_TIMEOUT，写入稍后仍完成并回读 target
        val lw = LateWrite(appScope())
        h.store.lateWrite = lw

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue("期望超时未提交，实际 $result", result is ProviderSwitchResult.NotCommitted)
        result as ProviderSwitchResult.NotCommitted
        assertEquals(SwitchFailureReason.SWITCH_TIMEOUT, result.reason)
        assertEquals(listOf("shizuku"), h.store.writes)
        assertTrue("写盘挂起超时前旧 Root anchor 已按事务解绑", result.needsForegroundRevalidation)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)

        // 迟到的底层写完成并回读 target：超时已放弃的切换不得采用 target（§11.3 超时保留原选择）
        h.store.lateWrite = null   // 迟写放行后，补偿写走正常路径
        lw.commit.complete(Unit)
        advanceUntilIdle()
        assertEquals("迟到写盘回读不得把选择改成已放弃的 target",
            ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.ROOT, h.coordinator.activeProvider.value)
        // 跨进程闭环：补偿写把原选择重新排队落盘（DataStore 串行 edit 排在迟写之后），
        // 下次进程启动恢复的仍是 previous
        assertEquals("补偿写必须在迟写后把原选择重新落盘",
            listOf("shizuku", "root"), h.store.writes)
        assertEquals("最终持久 raw 仍为 previous", "root", h.store.persisted)
    }

    @Test
    fun lateStoreWriteAfterCancellationNeverAdoptsTarget() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        val lw = LateWrite(appScope())
        h.store.lateWrite = lw
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        }
        runCurrent()
        assertTrue("事务必须推进到底层写盘点", lw.started.isCompleted)
        assertTrue(h.coordinator.switching.value)

        // 调用方取消（离开设置页/进程退场）：只清事务，不改选择
        pending.cancel()
        advanceUntilIdle()
        assertTrue(pending.isCancelled)
        assertFalse(h.coordinator.switching.value)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)

        // 不可取消的底层写稍后仍完成并回读：取消的切换同样不得采用 target
        h.store.lateWrite = null   // 迟写放行后，补偿写走正常路径
        lw.commit.complete(Unit)
        advanceUntilIdle()
        assertEquals("取消后的迟到写盘回读不得采用 target",
            ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.ROOT, h.coordinator.activeProvider.value)
        // 取消路径同样补偿写回原选择，跨进程闭环
        assertEquals("取消后补偿写必须把原选择重新落盘",
            listOf("shizuku", "root"), h.store.writes)
        assertEquals("root", h.store.persisted)
    }

    @Test
    fun newerSwitchWinsOverStaleCompensationWrite() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        // 第一次切换：写盘挂起超时（writeGate 永不归还）→ 原选择 root 保留，补偿写排队
        // （尚未认领写槽）；补偿与迟写回读的落盘交互由 lateStoreWriteAfterTimeout 覆盖
        h.store.writeGate = CompletableDeferred()
        val first = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue(first is ProviderSwitchResult.NotCommitted)
        assertEquals(SwitchFailureReason.SWITCH_TIMEOUT,
            (first as ProviderSwitchResult.NotCommitted).reason)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)

        // 更新的合法切换随即发起（写版本已递增），并停在自己的写盘点
        val gate = CompletableDeferred<Unit>()
        h.store.writeGate = gate
        val second = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        }
        assertFalse(second.isCompleted)
        assertTrue("新事务必须已发起", h.coordinator.switching.value)

        // 补偿写被写互斥挡在新事务之后；新写先完成后，补偿认领时才做版本检查 → 过时跳过
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(second.isCompleted)
        assertTrue(second.getCompleted() is ProviderSwitchResult.Committed)
        assertTrue("旧补偿必须因版本过时而跳过", h.coordLogs.any { it.contains("补偿写盘跳过") })
        assertEquals("过时补偿不得写盘，新写最终获胜", listOf("shizuku"), h.store.writes)
        assertEquals("最终持久值为新选择", "shizuku", h.store.persisted)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.activeProvider.value)
    }

    // ─────────────────────────── 取消的 attempt 迟到成功不得提交 ───────────────────────────

    @Test
    fun cancelledAttemptLateSuccessNeverCommits() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        // Unconfined：同步推进到 awaitCandidate 挂起（物理 openShell 排队未跑）
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.verifyRootFromForeground(
                ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        }
        assertFalse(pending.isCompleted)

        // 离页/改选取消：attempt 作废
        h.coordinator.cancelForegroundAttempt(ProviderOwner("settings-ui"))
        assertTrue(pending.isCompleted)
        val result = pending.getCompleted()
        assertTrue(result is RootVerificationResult.Failed)

        advanceUntilIdle()   // 迟到的 openShell 成功必须被丢弃
        assertEquals("物理任务仍可能完成", 1, h.backend.openShellCalls.get())
        assertEquals("迟到成功不得 bind", 0, h.backend.bindCalls.get())
        assertEquals("迟到成功不得写盘提交", 0, h.store.writes.size)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertTrue("迟到成功不得 READY", h.transport.state.value.status != RootStatus.READY)
        assertFalse(h.coordinator.switching.value)
    }
}
