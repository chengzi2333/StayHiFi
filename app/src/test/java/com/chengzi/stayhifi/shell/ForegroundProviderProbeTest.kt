package com.chengzi.stayhifi.shell

import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.foregroundCheck
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task D9：前台公共 probe/ensure 边界（设计 §11.2/§11.4.5/§11.6）。
 * ensure/首页/耳机刷新/录制向导的共同入口是 `Coordinator.probeSelected` + `foregroundCheck`：
 *  - Root 已选但未 READY 时，连续 probe 只读 RootSnapshot/grantedState，
 *    绝不 requestPermission、绝不 openShell、绝不 RootService.bind、绝不回退 Shizuku；
 *  - Root 未 READY 时 ensure（acquire）只抛 NEEDS_FOREGROUND_REVALIDATION，不制造授权交互；
 *  - Shizuku 的显式授权按钮仍走 lease 携带的原 provider（factory 创建的唯一实例）；
 *  - 旧 generation 的 lease 返回时不得覆盖新 UI（lease.isCurrent()==false 且 withService
 *    抛 STALE_GENERATION），供 MainActivity 在 refreshGeneration 之上加代次检查；
 *  - foregroundCheck 只翻译可观察事实：Root 未就绪出事实红条，Shizuku 未授权不出红条
 *    （保持原首页行为），可 bind 时无文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundProviderProbeTest {

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
        val grantedStateCalls = AtomicInteger()
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        private val bindingIds = AtomicLong(0)

        override fun grantedState(): Boolean? {
            grantedStateCalls.incrementAndGet()
            return granted
        }

        override fun openShell(): RootShellObservation {
            openShellCalls.incrementAndGet()
            return RootShellObservation(isRoot = true)
        }

        override fun bind(callbacks: RootBindCallbacks): RootBinding {
            bindCalls.incrementAndGet()
            return FakeBinding(bindingIds.incrementAndGet())
        }

        override fun unbind(binding: RootBinding) {
            unbindCalls.incrementAndGet()
        }

        private class FakeBinding(override val id: Long) : RootBinding
    }

    private class FakeShellProvider(
        private val running: Boolean = true,
        private val granted: Boolean = true,
    ) : ShellProvider {
        val requestPermissionCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        override val isRunning: Boolean get() = running
        override val hasPermission: Boolean get() = granted
        override suspend fun requestPermission(activity: android.app.Activity): Boolean {
            requestPermissionCalls.incrementAndGet()
            return granted
        }
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
        val factory: ShizukuFactory,
        val raw: MutableSharedFlow<String?>,
        val store: FakeStore,
    )

    private fun TestScope.harness(): Harness {
        val backend = FakeRootBackend()
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

    // ─────────── Root 已选但未 READY：公共前台入口零交互（§11.2/§11.4.5） ───────────

    @Test
    fun rootSelectedNotReady_repeatedProbe_neverRequestsOrOpensShell() = runTest {
        val h = harness()
        h.backend.granted = false
        advanceUntilIdle()   // 启动 collect
        h.raw.emit("root")

        // ensure / 首页 / 耳机事件刷新 / 录制向导的共同入口：同一轮连续 probe
        val probes = listOf(
            h.coordinator.probeSelected(ProviderSource.ONBOARDING),   // 公共 ensure
            h.coordinator.probeSelected(ProviderSource.SETTINGS),     // 首页刷新
            h.coordinator.probeSelected(ProviderSource.SETTINGS),     // 耳机刷新
            h.coordinator.probeSelected(ProviderSource.ONBOARDING),   // 录制向导
        )
        probes.forEach { probe ->
            assertEquals(ProviderKind.ROOT, probe.kind)
            assertFalse("Root 未 READY 时 probe 不得报运行中", probe.isRunning)
            assertFalse("Root 未 READY 时 probe 不得报已授权", probe.hasPermission)
            assertEquals("rootGranted 透传可观察三态", false, probe.rootGranted)
        }
        assertEquals("probe 只读 grantedState（每轮一次）", 4, h.backend.grantedStateCalls.get())
        assertEquals("公共前台路径绝不 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("公共前台路径绝不 RootService.bind", 0, h.backend.bindCalls.get())
        assertEquals("Root probe 不触碰 Shizuku factory（无 requestPermission 可能）",
            0, h.factory.created.size)
        assertEquals("probe 不改持久选择", 0, h.store.writes.size)
    }

    @Test
    fun rootNotReady_foregroundEnsureBlockedWithoutInteraction() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")

        val check = h.coordinator.probeSelected(ProviderSource.ONBOARDING).foregroundCheck()
        assertFalse("Root 未 READY 时 ensure 不得放行 bind", check.bindable)
        assertEquals("Root 未就绪出事实红条", "Root 通道未就绪", check.carrierAlert)

        // ensure 之后的 lease 获取同样拒绝：唯一允许的后台/公共终态是 NEEDS_FOREGROUND_REVALIDATION
        try {
            h.coordinator.acquire(ProviderOwner("main-activity"), ProviderSource.ONBOARDING)
            fail("Root 未 READY 时 acquire 必须拒绝")
        } catch (e: ProviderAccessException) {
            assertEquals(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION, e.reason)
        }
        assertEquals("ensure/acquire 全程零 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("ensure/acquire 全程零 Shizuku 构造", 0, h.factory.created.size)
    }

    // ─────────── Shizuku 显式授权按钮仍走原 provider ───────────

    @Test
    fun shizukuExplicitGrantUsesOriginalLeaseProvider() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")

        // probe 本身不创建授权交互；显式按钮 acquire 后拿到 factory 创建的原 provider
        h.coordinator.probeSelected(ProviderSource.ONBOARDING)
        assertEquals("Shizuku probe 只读状态", 1, h.factory.created.size)
        assertEquals("probe 不触发授权请求", 0, h.factory.created[0].requestPermissionCalls.get())
        assertEquals("probe 绝不 bind", 0, h.factory.created[0].bindCalls.get())

        val lease = h.coordinator.acquire(ProviderOwner("main-activity"), ProviderSource.ONBOARDING)
        assertEquals(2, h.factory.created.size)
        assertSame("显式授权按钮仍走 lease 携带的原 provider",
            h.factory.created[1], lease.shizukuProvider)
        lease.close()
        assertEquals("close 释放本 owner 的 Shizuku provider", 1, h.factory.created[1].unbindCalls.get())
    }

    // ─────────── 旧 generation 不得覆盖新 UI（refreshGeneration + lease.generation） ───────────

    @Test
    fun staleGenerationLeaseCannotOverwriteNewUi() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        val lease = h.coordinator.acquire(ProviderOwner("main-activity"), ProviderSource.SETTINGS)
        assertTrue(lease.isCurrent())

        h.raw.emit("root")   // 选择变化 → 新代次
        advanceUntilIdle()
        assertFalse("旧 generation lease 不得再写新 UI", lease.isCurrent())
        try {
            lease.withService { }
            fail("旧代次 lease 的 withService 必须抛 STALE_GENERATION")
        } catch (e: ProviderAccessException) {
            assertEquals(ProviderAccessReason.STALE_GENERATION, e.reason)
        }
    }

    // ─────────── foregroundCheck：只翻译可观察事实 ───────────

    @Test
    fun foregroundCheckMapsObservableFactsOnly() {
        // Root 未 READY：不可 bind + 事实红条 + 需前台重验日志
        val rootDown = ProviderProbe(
            ProviderKind.ROOT, isRunning = false, hasPermission = false, rootGranted = false,
        ).foregroundCheck()
        assertFalse(rootDown.bindable)
        assertEquals("Root 通道未就绪", rootDown.carrierAlert)
        assertEquals("Root 未在当前进程就绪，需前台重验", rootDown.notReadyLog)

        // Shizuku 未运行：保持原红条文案
        val shizukuDown = ProviderProbe(
            ProviderKind.SHIZUKU, isRunning = false, hasPermission = false,
        ).foregroundCheck()
        assertFalse(shizukuDown.bindable)
        assertEquals("Shizuku/Stellar 未运行", shizukuDown.carrierAlert)
        assertEquals("特权载体未运行", shizukuDown.notReadyLog)

        // Shizuku 运行但未授权：不出红条（保持原首页行为），只供 ensure/日志使用
        val shizukuNoGrant = ProviderProbe(
            ProviderKind.SHIZUKU, isRunning = true, hasPermission = false,
        ).foregroundCheck()
        assertFalse(shizukuNoGrant.bindable)
        assertNull("未授权不出红条", shizukuNoGrant.carrierAlert)
        assertEquals("特权未授予", shizukuNoGrant.notReadyLog)

        // 可 bind：两通道就绪时无文案
        val shizukuReady = ProviderProbe(
            ProviderKind.SHIZUKU, isRunning = true, hasPermission = true,
        ).foregroundCheck()
        assertTrue(shizukuReady.bindable)
        assertNull(shizukuReady.carrierAlert)
        assertNull(shizukuReady.notReadyLog)
        val rootReady = ProviderProbe(
            ProviderKind.ROOT, isRunning = true, hasPermission = true, rootGranted = true,
        ).foregroundCheck()
        assertTrue(rootReady.bindable)
        assertNull(rootReady.carrierAlert)
    }
}
