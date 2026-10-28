package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.ICodecService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D2：ProviderCoordinator 异步首读与探测边界（设计 §11.3/§11.4.5）。
 *  - 首读完成前 selected/active 均 null，且不得触碰 factory（不得先构造默认 Shizuku 再"纠正"）；
 *  - 首值发射后 awaitSelection 才返回；
 *  - Shizuku probe 由 factory 提供（isRunning/hasPermission，绝不 bind）；
 *  - Root probe 只读注入的状态源，业务层拿不到 factory，探测绝不创建 shell/弹窗。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderCoordinatorTest {

    /**
     * 独立 application scope：runTest 的 backgroundScope 子协程不被 advanceUntilIdle
     * 启动（kotlinx-coroutines-test 实测），无法模拟"首读前"窗口；自带 StandardTestDispatcher
     * 挂同一 testScheduler，advanceUntilIdle 可精确推进 collect。
     */
    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class FakeShellProvider(
        private val running: Boolean = true,
        private val granted: Boolean = true,
    ) : ShellProvider {
        val isRunningCalls = java.util.concurrent.atomic.AtomicInteger()
        val hasPermissionCalls = java.util.concurrent.atomic.AtomicInteger()
        val bindCalls = java.util.concurrent.atomic.AtomicInteger()

        override val isRunning: Boolean
            get() {
                isRunningCalls.incrementAndGet()
                return running
            }
        override val hasPermission: Boolean
            get() {
                hasPermissionCalls.incrementAndGet()
                return granted
            }

        override suspend fun requestPermission(activity: android.app.Activity): Boolean = granted
        override fun bind(): ICodecService {
            bindCalls.incrementAndGet()
            throw IllegalStateException("测试不允许 bind")
        }

        override fun unbind() = Unit
    }

    private class CountingFactory(private val provider: FakeShellProvider) {
        val createCalls = java.util.concurrent.atomic.AtomicInteger()
        fun create(): ShellProvider {
            createCalls.incrementAndGet()
            return provider
        }
    }

    private class FakeSelectionStore(
        override val rawSelection: Flow<String?>,
    ) : ProviderSelectionStore {
        override suspend fun setRawSelection(value: String) = Unit
    }

    private class RecordingLog {
        val lines = mutableListOf<String>()
        operator fun invoke(line: String) {
            lines.add(line)
        }
    }

    @Test
    fun firstReadIsAsyncAndBlocksAllPrivilegeAccess() = runTest {
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val factory = CountingFactory(FakeShellProvider())
        val log = RecordingLog()
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeSelectionStore(raw),
            shizukuFactory = factory::create,
            log = log::invoke,
        )

        // 首读完成前：selected/active 均 null，factory 零调用
        assertNull(coordinator.selectedProvider.value)
        assertNull(coordinator.activeProvider.value)
        assertEquals(0, factory.createCalls.get())

        // awaitSelection 挂起至首值发射
        val awaited = async { coordinator.awaitSelection() }
        advanceUntilIdle()
        assertFalse("首值未发射前 awaitSelection 不得返回", awaited.isCompleted)

        raw.emit(null)
        assertEquals(ProviderKind.SHIZUKU, awaited.await())
        assertEquals(ProviderKind.SHIZUKU, coordinator.selectedProvider.value)
        assertEquals(ProviderKind.SHIZUKU, coordinator.activeProvider.value)
        // 恢复选择全程零 factory 调用
        assertEquals(0, factory.createCalls.get())
    }

    @Test
    fun exactRootSelectionRestoresRoot() = runTest {
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val factory = CountingFactory(FakeShellProvider())
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeSelectionStore(raw),
            shizukuFactory = factory::create,
        )
        advanceUntilIdle()   // 启动 collect（replay=0 时先于订阅的 emit 会被丢弃，必须先订阅）
        raw.emit("root")
        assertEquals(ProviderKind.ROOT, coordinator.awaitSelection())
        assertEquals(ProviderKind.ROOT, coordinator.activeProvider.value)
        assertEquals(0, factory.createCalls.get())
    }

    @Test
    fun unknownValueFallsBackAndLogsOnce() = runTest {
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 2)
        val factory = CountingFactory(FakeShellProvider())
        val log = RecordingLog()
        ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeSelectionStore(raw),
            shizukuFactory = factory::create,
            log = log::invoke,
        )
        advanceUntilIdle()   // 先启动 collect，否则 replay=0 下 emit 被丢弃
        raw.emit("magisk")
        raw.emit("magisk")
        advanceUntilIdle()
        assertEquals(1, log.lines.size)
        assertTrue(log.lines.single().contains("magisk"))
    }

    @Test
    fun shizukuProbeUsesFactoryWithoutBind() = runTest {
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val fake = FakeShellProvider(running = true, granted = false)
        val factory = CountingFactory(fake)
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeSelectionStore(raw),
            shizukuFactory = factory::create,
        )
        advanceUntilIdle()   // 先启动 collect
        raw.emit("shizuku")

        val probe = coordinator.probeSelected(ProviderSource.APPLY)
        assertEquals(ProviderKind.SHIZUKU, probe.kind)
        assertTrue(probe.isRunning)
        assertFalse(probe.hasPermission)
        assertNull(probe.rootGranted)
        assertEquals("probe 只读 isRunning/hasPermission 各一次", 1, fake.isRunningCalls.get())
        assertEquals(1, fake.hasPermissionCalls.get())
        assertEquals("probe 绝不 bind", 0, fake.bindCalls.get())
    }

    @Test
    fun rootProbeReadsOnlyStatusSourceAndNeverTouchesFactory() = runTest {
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val factory = CountingFactory(FakeShellProvider())
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeSelectionStore(raw),
            shizukuFactory = factory::create,
            rootStatusSource = ProviderCoordinator.RootStatusSource { false },
        )
        advanceUntilIdle()   // 先启动 collect
        raw.emit("root")

        val probe = coordinator.probeSelected(ProviderSource.SETTINGS)
        assertEquals(ProviderKind.ROOT, probe.kind)
        assertEquals(false, probe.rootGranted)
        assertFalse(probe.hasPermission)
        assertEquals("Root probe 不得触碰 Shizuku factory", 0, factory.createCalls.get())
    }

    @Test
    fun probeBeforeFirstReadTouchesNothing() = runTest {
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val factory = CountingFactory(FakeShellProvider())
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeSelectionStore(raw),
            shizukuFactory = factory::create,
        )
        val probeDeferred = async { coordinator.probeSelected(ProviderSource.BOOT) }
        advanceUntilIdle()
        assertFalse("首读前 probe 不得返回", probeDeferred.isCompleted)
        assertEquals(0, factory.createCalls.get())

        raw.emit("shizuku")
        assertEquals(ProviderKind.SHIZUKU, probeDeferred.await().kind)
    }
}
