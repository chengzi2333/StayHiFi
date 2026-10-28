package com.chengzi.stayhifi.shell

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.MainActivity
import com.chengzi.stayhifi.RootRepairDestination
import com.chengzi.stayhifi.ui.settings.SettingsAction
import com.chengzi.stayhifi.ui.settings.SettingsUiState
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task D11：设置手动退回与 Root 修复入口（设计 §11.2/§11.3）。
 *  - Root 处于 NOT_GRANTED / BIND_TIMEOUT / DISCONNECTED / NEEDS_FOREGROUND_REVALIDATION 时，
 *    「改用 Shizuku/Stellar」都可提交且退回本身绝不要求 openShell（不检查 Root 可用性）；
 *  - 切换期间重复点击只产生一个事务（第二次得 ALREADY_SWITCHING，写盘恰一次）；
 *  - EXTRA_OPEN_ROOT_SETTINGS 精确字符串与 Root 修复目的地判定；
 *  - 设置 UI 状态携带 provider 修复字段与显式动作；Shizuku 原管理器指引不变。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootRepairFlowTest {

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
        /** false 时 bind 永不回调 onConnected（驱动 BIND_TIMEOUT）。 */
        private val connectOnBind: Boolean = true,
    ) : RootBackend {
        val grantedCalls = AtomicInteger()
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
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
            if (connectOnBind) callbacks.onConnected(service)
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
        override fun bind(): ICodecService = FakeCodecService()
        override fun unbind() = Unit
    }

    private class FakeStore(val raw: MutableSharedFlow<String?>) : ProviderSelectionStore {
        val writes = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var writeGate: CompletableDeferred<Unit>? = null
        override val rawSelection: Flow<String?> = raw
        override suspend fun setRawSelection(value: String) {
            writeGate?.await()
            writes.add(value)
        }
    }

    private class Harness(
        val transport: RootTransport,
        val backend: FakeRootBackend,
        val coordinator: ProviderCoordinator,
        val logger: RootDiagnosticLogger,
        val sink: RecordingSink,
        val raw: MutableSharedFlow<String?>,
        val store: FakeStore,
    )

    private fun TestScope.harness(
        granted: Boolean? = true,
        connectOnBind: Boolean = true,
    ): Harness {
        val backend = FakeRootBackend(granted = granted, connectOnBind = connectOnBind)
        val sink = RecordingSink()
        val logger = RootDiagnosticLogger(sink)
        val transport = RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = logger,
            bindTimeoutMs = 1_000L,
        )
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val store = FakeStore(raw)
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = store,
            shizukuFactory = { FakeShellProvider() },
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            rootLogger = logger,
            applyLock = Mutex(),
        )
        return Harness(transport, backend, coordinator, logger, sink, raw, store)
    }

    /** 显式前台验证驱动 transport 到 READY（教程/设置点击的既有路径）。 */
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

    // ─────────────────────────── Root 四种不可用终态下的手动退回 ───────────────────────────

    @Test
    fun retreatFromNotGrantedCommitsWithoutOpenShell() = runTest {
        val h = harness(granted = false)
        advanceUntilIdle()
        h.raw.emit("root")
        advanceUntilIdle()

        // 显式授权尝试落到 NOT_GRANTED（isAppGrantedRoot=false，不开 shell）
        val verify = h.coordinator.verifyRootFromForeground(
            ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue(verify is RootVerificationResult.Failed)
        assertEquals(RootStatus.NOT_GRANTED, (verify as RootVerificationResult.Failed).status)
        assertEquals(RootStatus.NOT_GRANTED, h.transport.state.value.status)
        assertEquals("持久选择保留 Root，不静默回退", ProviderKind.ROOT,
            h.coordinator.selectedProvider.value)

        // 「改用 Shizuku/Stellar」可提交且不要求 openShell
        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue("Root 不可用时手动退回必须可提交，实际 $result",
            result is ProviderSwitchResult.Committed)
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())
        assertEquals(listOf("shizuku"), h.store.writes)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
    }

    @Test
    fun retreatFromBindTimeoutCommitsWithoutReopeningShell() = runTest {
        val h = harness(granted = true, connectOnBind = false)
        advanceUntilIdle()
        h.raw.emit("root")
        advanceUntilIdle()

        // 显式授权成功但 bind 超时（1s 虚拟 watchdog）→ BIND_TIMEOUT，提交失败
        val pending = async {
            h.coordinator.verifyRootFromForeground(
                ProviderOwner("settings-ui"), ProviderSource.SETTINGS)
        }
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        val verify = pending.getCompleted()
        assertTrue(verify is RootVerificationResult.Failed)
        assertEquals(RootStatus.BIND_TIMEOUT, (verify as RootVerificationResult.Failed).status)
        assertEquals(RootStatus.BIND_TIMEOUT, h.transport.state.value.status)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)

        val shellsBefore = h.backend.openShellCalls.get()
        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue("BIND_TIMEOUT 下手动退回必须可提交，实际 $result",
            result is ProviderSwitchResult.Committed)
        assertEquals("退回本身不得再开 shell", shellsBefore, h.backend.openShellCalls.get())
        assertEquals(listOf("shizuku"), h.store.writes)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
    }

    @Test
    fun retreatFromDisconnectedAnchorCommitsIdempotently() = runTest {
        val h = harness(granted = true)
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)

        // anchor 断开（远端/显式）→ DISCONNECTED；持久选择仍是 Root
        h.transport.disconnectAnchor()
        advanceUntilIdle()
        assertEquals(RootStatus.DISCONNECTED, h.transport.state.value.status)
        assertEquals(ProviderKind.ROOT, h.coordinator.selectedProvider.value)
        assertEquals(1, h.backend.unbindCalls.get())

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue("DISCONNECTED 下手动退回必须可提交，实际 $result",
            result is ProviderSwitchResult.Committed)
        assertEquals("anchor 已断开，切换事务解绑幂等不再执行", 1, h.backend.unbindCalls.get())
        assertEquals(listOf("shizuku"), h.store.writes)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
    }

    @Test
    fun retreatWhenNeedsForegroundRevalidationNeverTouchesBackend() = runTest {
        val h = harness(granted = true)
        advanceUntilIdle()
        h.raw.emit("root")   // 持久选择 Root，冷进程从未验证
        advanceUntilIdle()
        assertEquals(RootStatus.UNVERIFIED, h.transport.state.value.status)

        // 后台/前台读取一律得 NEEDS_FOREGROUND_REVALIDATION，不制造弹窗
        assertAccessFails(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION) {
            h.coordinator.acquire(ProviderOwner("apply"), ProviderSource.APPLY)
        }

        val result = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        advanceUntilIdle()
        assertTrue("NEEDS_FOREGROUND_REVALIDATION 下手动退回必须可提交，实际 $result",
            result is ProviderSwitchResult.Committed)
        assertEquals("手动退回不检查 Root 可用性", 0, h.backend.grantedCalls.get())
        assertEquals(0, h.backend.openShellCalls.get())
        assertEquals(0, h.backend.bindCalls.get())
        assertEquals(0, h.backend.unbindCalls.get())
        assertEquals(listOf("shizuku"), h.store.writes)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
    }

    // ─────────────────────────── 切换防重入 ───────────────────────────

    @Test
    fun repeatedClickDuringSwitchYieldsSingleTransaction() = runTest {
        val h = harness(granted = true)
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

        // 重复点击：不得产生第二个事务
        val second = h.coordinator.switchToShizuku(ProviderOwner("settings"), ProviderSource.SETTINGS)
        assertTrue(second is ProviderSwitchResult.NotCommitted)
        assertEquals(SwitchFailureReason.ALREADY_SWITCHING,
            (second as ProviderSwitchResult.NotCommitted).reason)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.isCompleted)
        assertTrue(pending.getCompleted() is ProviderSwitchResult.Committed)
        assertEquals("重复点击只产生一个事务（写盘恰一次）", listOf("shizuku"), h.store.writes)
        assertEquals(ProviderKind.SHIZUKU, h.coordinator.selectedProvider.value)
        assertFalse(h.coordinator.switching.value)
    }

    // ─────────────────────────── Root 修复入口接线（Intent 由编译与静态搜索验收） ───────────────────────────

    @Test
    fun extraOpenRootSettingsExactString() {
        assertEquals(
            "com.chengzi.stayhifi.extra.OPEN_ROOT_SETTINGS",
            MainActivity.EXTRA_OPEN_ROOT_SETTINGS,
        )
    }

    @Test
    fun rootRepairDestinationRouting() {
        // Root 修复目的地 = 设置页 Root 区（携带 EXTRA_OPEN_ROOT_SETTINGS）；
        // Shizuku/未读首读保持原管理器指引，不携带 extra。
        assertTrue(RootRepairDestination.opensRootSettings(ProviderKind.ROOT))
        assertFalse(RootRepairDestination.opensRootSettings(ProviderKind.SHIZUKU))
        assertFalse(RootRepairDestination.opensRootSettings(null))
    }

    @Test
    fun settingsUiStateCarriesProviderRepairFields() {
        val state = SettingsUiState()
        assertNull(state.selectedProvider)
        assertNull(state.activeProvider)
        assertEquals(RootStatus.UNVERIFIED, state.root.status)
        assertFalse(state.providerSwitching)
        assertFalse(state.rootSectionExpanded)
        // 显式动作类型存在：通道选择与 Root 授权请求
        val select: SettingsAction = SettingsAction.SelectProvider(ProviderKind.ROOT)
        val auth: SettingsAction = SettingsAction.RequestRootAuthorization
        assertTrue(select is SettingsAction.SelectProvider)
        assertEquals(SettingsAction.RequestRootAuthorization, auth)
    }
}
