package com.chengzi.stayhifi.ui.onboarding

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.ProviderKind
import com.chengzi.stayhifi.shell.ProviderOwner
import com.chengzi.stayhifi.shell.ProviderSelectionStore
import com.chengzi.stayhifi.shell.ProviderSource
import com.chengzi.stayhifi.shell.RootBackend
import com.chengzi.stayhifi.shell.RootBindCallbacks
import com.chengzi.stayhifi.shell.RootBinding
import com.chengzi.stayhifi.shell.RootDiagnosticLogger
import com.chengzi.stayhifi.shell.RootShellObservation
import com.chengzi.stayhifi.shell.RootShellStatus
import com.chengzi.stayhifi.shell.RootSnapshot
import com.chengzi.stayhifi.shell.RootStatus
import com.chengzi.stayhifi.shell.RootTransport
import com.chengzi.stayhifi.shell.RootVerificationResult
import com.chengzi.stayhifi.shell.ShellProvider
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D10：教程载体页门槛与 Root 实验边界（设计 §11.2/§11.3/§11.4.6）。
 *  - Shizuku/Stellar 组沿用「任一载体三灯就绪」；
 *  - Root 组只有 持久选择=生效通道=ROOT 且 RootSnapshot=READY 才放行：
 *    Shizuku ready 不放行；candidate 健康（transport READY）但尚未 commit 也不放行；
 *  - 仅选择/查看 Root 不写 DataStore、不 openShell；
 *  - 教程耳机页 2.5 秒轮询（RefreshConnection 的共同入口 probeSelected）重复 100 次仍零
 *    openShell / 零 bind / 零 Shizuku 构造 / 零写盘；
 *  - 离页/改选取消的 attempt 迟到成功不得把门槛放行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingProviderGateTest {

    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

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
            callbacks.onConnected(service)
            return FakeBinding(bindingIds.incrementAndGet())
        }

        override fun unbind(binding: RootBinding) {
            unbindCalls.incrementAndGet()
        }

        private class FakeBinding(override val id: Long) : RootBinding
    }

    private class FakeShellProvider : ShellProvider {
        val requestPermissionCalls = AtomicInteger()
        override val isRunning: Boolean get() = true
        override val hasPermission: Boolean get() = true
        override suspend fun requestPermission(activity: android.app.Activity): Boolean {
            requestPermissionCalls.incrementAndGet()
            return true
        }
        override fun bind(): ICodecService = FakeCodecService()
        override fun unbind() = Unit
    }

    private class ShizukuFactory {
        val created = Collections.synchronizedList(mutableListOf<FakeShellProvider>())
        fun create(): ShellProvider = FakeShellProvider().also { created.add(it) }
    }

    /** 可控写盘：writeGate 挂起时事务停在「candidate 健康但未 commit」点。 */
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
        val factory: ShizukuFactory,
        val raw: MutableSharedFlow<String?>,
        val store: FakeStore,
    )

    private fun TestScope.harness(granted: Boolean? = null): Harness {
        val backend = FakeRootBackend(granted = granted)
        val transport = RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = RootDiagnosticLogger {},
        )
        val factory = ShizukuFactory()
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val store = FakeStore(raw)
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = store,
            shizukuFactory = factory::create,
            rootStatusSource = ProviderCoordinator.RootStatusSource { backend.grantedState() },
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            rootLogger = null,
            log = {},
        )
        return Harness(transport, backend, coordinator, factory, raw, store)
    }

    /** 从 Coordinator/Transport 只读流组装 UI 态（与 MainActivity 的接线同形）。 */
    private fun Harness.uiState(choice: ProviderKind): OnboardingUiState = OnboardingUiState(
        providerChoice = choice,
        selectedProvider = coordinator.selectedProvider.value,
        activeProvider = coordinator.activeProvider.value,
        root = transport.state.value,
    )

    private fun readyCarrier(kind: CarrierKind) =
        CarrierUiState(kind, installed = true, running = true, granted = true)

    private fun readyRootSnapshot() = RootSnapshot(
        status = RootStatus.READY,
        granted = true,
        shellStatus = RootShellStatus.ROOT_SHELL,
        observedReason = "health ok",
    )

    // ─────────── Shizuku/Stellar 组：沿用任一载体就绪规则 ───────────

    @Test
    fun shizukuChoiceKeepsEitherCarrierReadyRule() {
        val base = OnboardingUiState()   // 默认选择必须是 Shizuku/Stellar 组
        assertEquals(ProviderKind.SHIZUKU, base.providerChoice)
        assertFalse("默认无任何就绪", carrierPageReady(base))
        assertTrue("Shizuku 就绪放行", carrierPageReady(base.copy(shizuku = readyCarrier(CarrierKind.SHIZUKU))))
        assertTrue("Stellar 就绪放行", carrierPageReady(base.copy(stellar = readyCarrier(CarrierKind.STELLAR))))
        assertFalse("仅安装未运行不放行", carrierPageReady(
            base.copy(shizuku = CarrierUiState(CarrierKind.SHIZUKU, installed = true))))
    }

    // ─────────── Root 组：只有 selected=active=ROOT 且快照 READY 才放行 ───────────

    @Test
    fun rootChoiceShizukuReadyNeverPassesGate() {
        val state = OnboardingUiState(
            providerChoice = ProviderKind.ROOT,
            shizuku = readyCarrier(CarrierKind.SHIZUKU),
            stellar = readyCarrier(CarrierKind.STELLAR),
            selectedProvider = ProviderKind.SHIZUKU,
            activeProvider = ProviderKind.SHIZUKU,
            root = readyRootSnapshot(),
        )
        assertFalse("Root 已选时 Shizuku ready 不得放行（§11.2）", carrierPageReady(state))
    }

    @Test
    fun rootChoiceRequiresCommittedSelectionAndReadySnapshot() {
        val ready = readyRootSnapshot()
        // candidate 健康（快照 READY）但持久选择仍是 Shizuku：未 commit 不放行
        assertFalse("candidate 健康但未 commit 不放行", carrierPageReady(OnboardingUiState(
            providerChoice = ProviderKind.ROOT,
            selectedProvider = ProviderKind.SHIZUKU,
            activeProvider = ProviderKind.SHIZUKU,
            root = ready,
        )))
        // selected=ROOT 但 active 未跟上：不放行
        assertFalse("selected=ROOT 但 active!=ROOT 不放行", carrierPageReady(OnboardingUiState(
            providerChoice = ProviderKind.ROOT,
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.SHIZUKU,
            root = ready,
        )))
        // 已提交 Root 但快照未 READY（撤权/断开/未验证）：不放行
        listOf(
            RootStatus.UNVERIFIED, RootStatus.REQUESTING, RootStatus.GRANTED_NOT_READY,
            RootStatus.NOT_GRANTED, RootStatus.BINDING, RootStatus.BIND_TIMEOUT,
            RootStatus.DISCONNECTED, RootStatus.NEEDS_FOREGROUND_REVALIDATION,
        ).forEach { status ->
            assertFalse("快照 $status 不得放行", carrierPageReady(OnboardingUiState(
                providerChoice = ProviderKind.ROOT,
                selectedProvider = ProviderKind.ROOT,
                activeProvider = ProviderKind.ROOT,
                root = ready.copy(status = status),
            )))
        }
        // 全部满足才放行
        assertTrue("selected=active=ROOT 且 READY 才放行", carrierPageReady(OnboardingUiState(
            providerChoice = ProviderKind.ROOT,
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.ROOT,
            root = ready,
        )))
    }

    // ─────────── 仅选择/查看 Root：零写盘、零交互 ───────────

    @Test
    fun viewingOrSelectingRootNeverPersistsOrOpensShell() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        // 查看 Root 卡：只读 selected/active/RootSnapshot 流 + probe（UI 选择是纯 UI 态拷贝）
        val viewing = h.uiState(ProviderKind.ROOT)
        repeat(3) { h.coordinator.probeSelected(ProviderSource.ONBOARDING) }
        val selected = viewing.copy(providerChoice = ProviderKind.ROOT)

        assertEquals("查看/选择 Root 不写 DataStore", 0, h.store.writes.size)
        assertEquals("查看/选择 Root 不 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("查看/选择 Root 不 bind", 0, h.backend.bindCalls.get())
        assertTrue("Shizuku probe 不触发授权请求",
            h.factory.created.all { it.requestPermissionCalls.get() == 0 })
        assertEquals("选择本身不提交：门槛保持关闭", ProviderKind.SHIZUKU,
            selected.selectedProvider)
        assertFalse(carrierPageReady(selected))
    }

    // ─────────── candidate 健康但尚未 commit：门槛实时关闭，commit 后实时打开 ───────────

    @Test
    fun candidateHealthyButNotCommittedKeepsGateClosed() = runTest {
        val h = harness(granted = true)
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        h.store.writeGate = gate
        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.verifyRootFromForeground(
                ProviderOwner("main-activity"), ProviderSource.ONBOARDING)
        }
        // 推进到写盘挂起点：auth+bind+健康检查已成功，commit 未完成
        repeat(10) {
            if (h.coordinator.switching.value || pending.isCompleted) return@repeat
            runCurrent()
        }
        assertFalse("事务必须在写盘点挂起", pending.isCompleted)
        assertEquals("candidate 已健康（transport READY）", RootStatus.READY,
            h.transport.state.value.status)

        val midState = h.uiState(ProviderKind.ROOT).copy(
            shizuku = readyCarrier(CarrierKind.SHIZUKU))
        assertFalse("candidate 健康但尚未 commit：门槛必须关闭", carrierPageReady(midState))
        assertEquals("未 commit 不写盘", 0, h.store.writes.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(pending.getCompleted() is RootVerificationResult.Committed)
        assertEquals(listOf("root"), h.store.writes)

        val committed = h.uiState(ProviderKind.ROOT)
        assertTrue("成功提交后只看当前选择：门槛打开", carrierPageReady(committed))
    }

    // ─────────── 2.5 秒轮询（RefreshConnection 共同入口）重复 100 次：零 openShell ───────────

    @Test
    fun repeatedRefreshConnectionProbeNeverOpensShell() = runTest {
        val h = harness(granted = false)   // Root 已持久选择但本进程未验证/未授权
        advanceUntilIdle()
        h.raw.emit("root")
        advanceUntilIdle()

        // 教程耳机页 2.5s 轮询 ×100：每轮 ensure→probeSelected（未就绪即返回，不 acquire）
        repeat(100) {
            val probe = h.coordinator.probeSelected(ProviderSource.ONBOARDING)
            assertEquals(ProviderKind.ROOT, probe.kind)
            assertFalse(probe.isRunning)
            assertFalse("轮询期间门槛不得放行", carrierPageReady(h.uiState(ProviderKind.ROOT)))
        }
        assertEquals("100 轮轮询零 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("100 轮轮询零 bind", 0, h.backend.bindCalls.get())
        assertEquals("Root 轮询不触碰 Shizuku factory", 0, h.factory.created.size)
        assertEquals("轮询不写盘", 0, h.store.writes.size)
    }

    // ─────────── 离页/改选取消的 attempt：迟到成功不得把门槛放行 ───────────

    @Test
    fun cancelledAttemptLateSuccessKeepsGateClosed() = runTest {
        val h = harness(granted = true)
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val pending = async(UnconfinedTestDispatcher(testScheduler)) {
            h.coordinator.verifyRootFromForeground(
                ProviderOwner("main-activity"), ProviderSource.ONBOARDING)
        }
        assertFalse(pending.isCompleted)

        // 离页/改选：作废进行中 attempt
        h.coordinator.cancelForegroundAttempt(ProviderOwner("main-activity"))
        assertTrue(pending.isCompleted)
        assertTrue(pending.getCompleted() is RootVerificationResult.Failed)

        advanceUntilIdle()   // 底层物理任务的迟到成功必须被丢弃
        assertEquals("迟到成功不得 bind", 0, h.backend.bindCalls.get())
        assertEquals("迟到成功不得写盘", 0, h.store.writes.size)
        assertTrue(h.transport.state.value.status != RootStatus.READY)
        assertFalse("取消后门槛保持关闭", carrierPageReady(h.uiState(ProviderKind.ROOT)))
    }
}
