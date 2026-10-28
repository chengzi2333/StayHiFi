package com.chengzi.stayhifi.trigger

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.ProviderOwner
import com.chengzi.stayhifi.shell.ProviderSelectionStore
import com.chengzi.stayhifi.shell.ProviderSource
import com.chengzi.stayhifi.shell.RootBackend
import com.chengzi.stayhifi.shell.RootBindCallbacks
import com.chengzi.stayhifi.shell.RootBinding
import com.chengzi.stayhifi.shell.RootDiagnosticLogger
import com.chengzi.stayhifi.shell.RootShellObservation
import com.chengzi.stayhifi.shell.RootTransport
import com.chengzi.stayhifi.shell.ShellProvider
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「自定义音质」写入口径（与 BackgroundProviderContractTest 同款 fake 装配）：
 * - 与自动拉满同走 ApplyConnectionGuard 入口锁 + 锁前断连复查 + applyGate lease，
 *   lease finally close，锁随 run 结束释放；
 * - 写入目标原样透传（不在 Worker 内改写用户选择），读回 codecType/rate/bits 一致
 *   才算成功；成功目标记入 VerifiedTargetStore（它同样是已验证生效目标，否则 30s
 *   轮询会把用户主动降档误报成「系统改回参数」）；
 * - 冷 Root / 断连：零 provider 创建、零写入，只记日志返回 false；
 * - 不写档案、不改 autoApply、不触碰自动拉满默认目标。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplyWorkerManualTest {

    private val mac = "AA:BB:CC:DD:EE:FF"

    @After
    fun tearDown() {
        VerifiedTargetStore.clear(mac)
    }

    private class FakeCodecService(
        private val writeResult: String = """{"applied":true}""",
    ) : ICodecService {
        val writeCalls = AtomicInteger()
        val writtenConfigs = Collections.synchronizedList(mutableListOf<String>())
        var onBindScopeWrite: (() -> Unit)? = null
        override fun dumpDevices(): String = "[]"
        override fun readConfig(mac: String?): String = """{"error":"fake read unavailable"}"""
        override fun writeConfig(mac: String?, configJson: String?): String {
            writeCalls.incrementAndGet()
            writtenConfigs += configJson ?: ""
            onBindScopeWrite?.invoke()
            return writeResult
        }
        override fun dumpBtState(): String = """{"dump":""}"""
        override fun destroy() = Unit
        override fun asBinder(): IBinder? = null
    }

    private class FakeRootBackend(private val service: FakeCodecService) : RootBackend {
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
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
        override fun unbind(binding: RootBinding) = Unit
        private class FakeBinding(override val id: Long) : RootBinding
    }

    private class FakeShellProvider(private val service: FakeCodecService) : ShellProvider {
        val unbindCalls = AtomicInteger()
        override val isRunning: Boolean get() = true
        override val hasPermission: Boolean get() = true
        override suspend fun requestPermission(activity: android.app.Activity): Boolean = true
        override fun bind(): ICodecService = service
        override fun unbind() {
            unbindCalls.incrementAndGet()
        }
    }

    private class FakeStore(val raw: MutableSharedFlow<String?>) : ProviderSelectionStore {
        override val rawSelection: Flow<String?> = raw
        override suspend fun setRawSelection(value: String) = Unit
    }

    private class FakeProbe(
        private val result: LocalA2dpState,
        private val onCall: (() -> Unit)? = null,
    ) : LocalA2dpProbe {
        override fun state(mac: String): LocalA2dpState {
            onCall?.invoke()
            return result
        }
    }

    /** 与 Task B4 测试相同：仅支撑构造 ApplyWorker 的最小 Context，其余方法一律不触碰。 */
    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private class Harness(
        val service: FakeCodecService,
        val backend: FakeRootBackend,
        val coordinator: ProviderCoordinator,
        val providers: MutableList<FakeShellProvider>,
        val raw: MutableSharedFlow<String?>,
        val logLines: MutableList<String>,
    )

    private fun TestScope.harness(service: FakeCodecService): Harness {
        val backend = FakeRootBackend(service)
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val logger = RootDiagnosticLogger { logLines += it }
        val scope = { CoroutineScope(StandardTestDispatcher(testScheduler)) }
        val transport = RootTransport(
            applicationScope = scope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = logger,
        )
        val providers = Collections.synchronizedList(mutableListOf<FakeShellProvider>())
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val coordinator = ProviderCoordinator(
            applicationScope = scope(),
            store = FakeStore(raw),
            shizukuFactory = { FakeShellProvider(service).also { providers += it } },
            log = { },
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            rootLogger = logger,
        )
        return Harness(service, backend, coordinator, providers, raw, logLines)
    }

    private fun worker(
        h: Harness,
        probeState: LocalA2dpState,
        events: MutableList<String>,
    ): ApplyWorker {
        val w = ApplyWorker(FakeContext())
        w.logSink = { }
        w.connectionGuard = ApplyConnectionGuard(
            ApplyLock.mutex,
            FakeProbe(probeState) { events += "probe locked=${ApplyLock.mutex.isLocked}" },
        ) { }
        w.coordinator = h.coordinator
        return w
    }

    @Test
    fun manualApplyWritesChosenTargetInsideSharedLockAndRecordsVerifiedTarget() = runTest {
        val h = harness(FakeCodecService())
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val events = Collections.synchronizedList(mutableListOf<String>())
        h.service.onBindScopeWrite = { events += "write locked=${ApplyLock.mutex.isLocked}" }
        val target = CodecConfig(4, 1_000_000, 8, 1, 2, 1002L, 0L, 0L, 0L)

        val ok = worker(h, LocalA2dpState.CONNECTED, events)
            .applyManual(mac, "buds", target)

        assertTrue(ok)
        assertEquals(1, h.service.writeCalls.get())
        // 用户选择原样透传，Worker 不改写
        assertEquals(target, CodecConfig.fromJson(h.service.writtenConfigs.single()))
        // 读回验证通过的目标必须成为漂移判定基准
        assertEquals(target, VerifiedTargetStore.targetFor(mac))
        assertEquals("probe locked=true", events.first())
        assertTrue(events.contains("write locked=true"))
        assertEquals("lease 必须 finally close", 1, h.providers.single().unbindCalls.get())
        assertFalse("结束后 ApplyLock 必须释放", ApplyLock.mutex.isLocked)
    }

    @Test
    fun manualApplySkipsCarrierEntirelyWhenDisconnected() = runTest {
        val h = harness(FakeCodecService())
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val ok = worker(h, LocalA2dpState.DISCONNECTED, mutableListOf())
            .applyManual(mac, "buds", CodecConfig(4, 1_000_000, 8, 1, 2, 1002L, 0L, 0L, 0L))

        assertFalse(ok)
        assertEquals("断连时零载体调用", 0, h.service.writeCalls.get())
        assertEquals("断连时不得创建 provider", 0, h.providers.size)
        assertNull(VerifiedTargetStore.targetFor(mac))
        assertFalse(ApplyLock.mutex.isLocked)
    }

    @Test
    fun manualApplyReturnsFalseWhenReadbackRejects() = runTest {
        val h = harness(FakeCodecService(writeResult = """{"applied":false}"""))
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val ok = worker(h, LocalA2dpState.CONNECTED, mutableListOf())
            .applyManual(mac, "buds", CodecConfig(4, 1_000_000, 8, 1, 2, 1002L, 0L, 0L, 0L))

        assertFalse(ok)
        assertEquals("读回不一致按既有口径重试 3 次", 3, h.service.writeCalls.get())
        assertNull("失败目标不得成为漂移基准", VerifiedTargetStore.targetFor(mac))
        assertFalse(ApplyLock.mutex.isLocked)
    }

    @Test
    fun manualApplySkipsSilentlyOnColdRoot() = runTest {
        val h = harness(FakeCodecService())
        advanceUntilIdle()
        h.raw.emit("root")   // 持久选择 Root 但本进程从未前台验证（冷 Root）
        advanceUntilIdle()

        val ok = worker(h, LocalA2dpState.CONNECTED, mutableListOf())
            .applyManual(mac, "buds", CodecConfig(4, 1_000_000, 8, 1, 2, 1002L, 0L, 0L, 0L))

        assertFalse(ok)
        assertEquals(0, h.service.writeCalls.get())
        assertEquals("冷 Root 零 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("冷 Root 零 RootService.bind", 0, h.backend.bindCalls.get())
        assertEquals("冷 Root 不得静默回退 Shizuku", 0, h.providers.size)
        assertNull(VerifiedTargetStore.targetFor(mac))
        assertFalse(ApplyLock.mutex.isLocked)
    }
}
