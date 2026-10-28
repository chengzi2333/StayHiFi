package com.chengzi.stayhifi.trigger

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.shell.ProviderAccessException
import com.chengzi.stayhifi.shell.ProviderAccessReason
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
import com.chengzi.stayhifi.shell.RootStatus
import com.chengzi.stayhifi.shell.RootTransport
import com.chengzi.stayhifi.shell.ShellProvider
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task D8：后台路径（ApplyWorker/Listener/QS/Boot）迁到 Coordinator 的调用契约（设计 §11.3/§11.4.5）。
 *
 *  - ApplyWorker.run/restoreDefault：共用入口锁（ApplyConnectionGuard 持 ApplyLock.mutex）
 *    后才 acquire，lease finally close 且先于解锁；本机 A2DP 预检不等待 provider 首读；
 *  - BOOT/LISTENER/QS/APPLY 四种 source 冷 Root：零 openShell、零 RootService.bind、
 *    零 Shizuku 静默回退，只产生一条按状态去重的 NEEDS_FOREGROUND_REVALIDATION 记录；
 *  - Root READY 后后台复用进程级 anchor：零新增 openShell/bind，lease close 不解绑 anchor。
 *
 * ListenerService/QS/BootReceiver 是 Android Service/Receiver，JVM 单测无法实例化；
 * 此处以 Task 7 同款 fake 装配直接驱动它们迁移后调用的 Coordinator 公共入口
 * （probeSelected/acquire/noteBackground），静态搜索（rg）保证四处不再直接构造 provider。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundProviderContractTest {

    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler))

    private class FakeCodecService(
        private val writeResult: String = """{"applied":true}""",
        private val readResult: String = """{"error":"fake read unavailable"}""",
    ) : ICodecService {
        val writeCalls = AtomicInteger()
        var onRead: (() -> Unit)? = null
        override fun dumpDevices(): String = "[]"
        override fun readConfig(mac: String?): String {
            onRead?.invoke()
            return readResult
        }
        override fun writeConfig(mac: String?, configJson: String?): String {
            writeCalls.incrementAndGet()
            return writeResult
        }
        override fun dumpBtState(): String = """{"dump":""}"""
        override fun destroy() = Unit
        override fun asBinder(): IBinder? = null
    }

    private class FakeRootBackend(private val service: FakeCodecService) : RootBackend {
        val openShellCalls = AtomicInteger()
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
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

    private class FakeShellProvider(private val service: FakeCodecService) : ShellProvider {
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
            return service
        }
        override fun unbind() {
            unbindCalls.incrementAndGet()
            onUnbind?.invoke()
        }
    }

    private class ShizukuFactory(private val service: FakeCodecService) {
        val created = Collections.synchronizedList(mutableListOf<FakeShellProvider>())
        var onCreate: ((FakeShellProvider) -> Unit)? = null
        fun create(): ShellProvider = FakeShellProvider(service).also {
            created.add(it)
            onCreate?.invoke(it)
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
        val calls = AtomicInteger()
        override fun state(mac: String): LocalA2dpState {
            calls.incrementAndGet()
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
        val transport: RootTransport,
        val coordinator: ProviderCoordinator,
        val factory: ShizukuFactory,
        val raw: MutableSharedFlow<String?>,
        val logger: RootDiagnosticLogger,
        val logLines: MutableList<String>,
    )

    private fun TestScope.harness(): Harness {
        val service = FakeCodecService()
        val backend = FakeRootBackend(service)
        // 注入内存 sink：默认 RootDiagnosticLogger 落 LogStore（android.util.Log）在 JVM 单测未 mock
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        val logger = RootDiagnosticLogger { logLines += it }
        val transport = RootTransport(
            applicationScope = appScope(),
            backend = backend,
            authDispatcher = StandardTestDispatcher(testScheduler),
            mainDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = logger,
        )
        val factory = ShizukuFactory(service)
        val raw = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val coordinator = ProviderCoordinator(
            applicationScope = appScope(),
            store = FakeStore(raw),
            shizukuFactory = factory::create,
            log = { },
            rootTransport = transport,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            rootLogger = logger,
        )
        return Harness(service, backend, transport, coordinator, factory, raw, logger, logLines)
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

    private fun recordingWorker(
        h: Harness,
        probeState: LocalA2dpState,
        events: MutableList<String>,
    ): ApplyWorker {
        val worker = ApplyWorker(FakeContext())
        worker.logSink = { }
        worker.connectionGuard = ApplyConnectionGuard(
            ApplyLock.mutex,
            FakeProbe(probeState) { events += "probe locked=${ApplyLock.mutex.isLocked}" },
        ) { }
        worker.coordinator = h.coordinator
        return worker
    }

    // ─────────────────────── ApplyWorker 入口锁契约 ───────────────────────

    @Test
    fun applyPrecheckRunsBeforeProviderFirstRead() = runTest {
        val h = harness()
        advanceUntilIdle()   // 启动 collect，但不发射首值：provider 首读悬置
        val events = Collections.synchronizedList(mutableListOf<String>())
        h.factory.onCreate = { events += "acquire locked=${ApplyLock.mutex.isLocked}" }
        val worker = recordingWorker(h, LocalA2dpState.CONNECTED, events)

        val pending = async { worker.run(MAC, "buds") }
        runCurrent()

        // 本机 A2DP 预检已执行，但 acquire 仍挂起在首读上：零 provider 创建
        assertEquals("probe locked=true", events.single())
        assertTrue("预检后任务持 ApplyLock 等待 provider 首读", ApplyLock.mutex.isLocked)
        assertEquals("首读完成前不得创建任何 provider", 0, h.factory.created.size)
        assertFalse(pending.isCompleted)

        h.raw.emit("shizuku")
        pending.await()   // await 让 runTest 驱动虚拟时间与真实 IO 往返直至完成
        assertEquals("首读后只创建一个 owner provider", 1, h.factory.created.size)
        assertEquals("lease 必须 finally close（unbind owner provider）",
            1, h.factory.created.single().unbindCalls.get())
        assertFalse("run 结束后 ApplyLock 必须释放", ApplyLock.mutex.isLocked)
    }

    @Test
    fun applyRunOrdersPrecheckAcquireIpcCloseInsideSharedLock() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val events = Collections.synchronizedList(mutableListOf<String>())
        h.factory.onCreate = { provider ->
            events += "acquire locked=${ApplyLock.mutex.isLocked}"
            provider.onBind = { events += "bind locked=${ApplyLock.mutex.isLocked}" }
            provider.onUnbind = { events += "close locked=${ApplyLock.mutex.isLocked}" }
        }
        h.service.onRead = { events += "ipc locked=${ApplyLock.mutex.isLocked}" }
        val worker = recordingWorker(h, LocalA2dpState.CONNECTED, events)

        worker.run(MAC, "buds")
        events += "returned locked=${ApplyLock.mutex.isLocked}"

        assertEquals("预检必须最先且在锁内", "probe locked=true", events[0])
        assertEquals("acquire 必须在共用入口锁后", "acquire locked=true", events[1])
        assertEquals("bind locked=true", events[2])
        assertEquals("ipc locked=true", events[3])
        assertTrue("全部 IPC 必须在锁内", events.filter { it.startsWith("ipc") }
            .all { it == "ipc locked=true" })
        assertEquals("lease close 必须先于解锁", "close locked=true", events[events.size - 2])
        assertEquals("returned locked=false", events.last())
        assertEquals(1, h.factory.created.single().unbindCalls.get())
    }

    @Test
    fun restoreDefaultSharesEntryLockAndClosesLease() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("shizuku")
        advanceUntilIdle()

        val origin = CodecConfig(4, 0, 32, 2, 2, 1000L, 0L, 0L, 0L)
        val readBack = FakeCodecService(
            readResult = """{"current":${origin.toJson()},"selectable":[]}""")
        val events = Collections.synchronizedList(mutableListOf<String>())
        val notifications = mutableListOf<Pair<String, String>>()
        h.factory.onCreate = { provider ->
            events += "acquire locked=${ApplyLock.mutex.isLocked}"
            provider.onUnbind = { events += "close locked=${ApplyLock.mutex.isLocked}" }
        }
        val worker = recordingWorker(h, LocalA2dpState.CONNECTED, events)
        worker.restoreEnv = ApplyWorker.RestoreEnv(
            shellReady = { true },
            hasPrivilege = { true },
            bind = { events += "bind locked=${ApplyLock.mutex.isLocked}"; readBack },
            unbind = { events += "env-unbind locked=${ApplyLock.mutex.isLocked}" },
            notifyFix = { },
            notify = { t, c -> notifications += t to c },
            notifyResultIfEnabled = { t, c -> notifications += t to c },
        )

        assertTrue(worker.restoreDefault(MAC, "buds", origin))
        events += "returned locked=${ApplyLock.mutex.isLocked}"

        assertEquals("预检必须最先且在锁内", "probe locked=true", events[0])
        assertEquals("acquire 必须在共用入口锁后", "acquire locked=true", events[1])
        assertEquals("bind locked=true", events[2])
        assertEquals("env-unbind locked=true", events[3])
        assertEquals("lease close 必须先于解锁", "close locked=true", events[4])
        assertEquals("returned locked=false", events[5])
        assertEquals("restore 只创建一个 owner provider 且恰 close 一次",
            1, h.factory.created.single().unbindCalls.get())
        // B4 既有载体语义不变：写回 origin + 读回一致 + 成功通知
        assertEquals(1, readBack.writeCalls.get())
        assertEquals(listOf("已恢复默认参数" to "buds → ${origin.summary()}"), notifications)
    }

    // ─────────────────────── 冷 Root 后台非交互契约 ───────────────────────

    @Test
    fun coldRootBackgroundPathsNeverOpenShellBindOrFallBackToShizuku() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        advanceUntilIdle()
        assertEquals("新进程 Root 从未验证开始", RootStatus.UNVERIFIED, h.transport.state.value.status)

        // Boot：仅异步登记
        h.coordinator.noteBackground(ProviderSource.BOOT)
        // Listener 启动补触发 / 30s 通知：探测未就绪 → 登记跳过，不 acquire
        repeat(2) {
            val probe = h.coordinator.probeSelected(ProviderSource.LISTENER)
            assertEquals(ProviderKind.ROOT, probe.kind)
            assertFalse(probe.isRunning)
            assertFalse(probe.hasPermission)
            h.coordinator.noteBackground(ProviderSource.LISTENER)
        }
        // QS：未 READY 只登记 + 打开 App（打开 App 是 UI 侧行为，此处验证登记边界）
        val qsProbe = h.coordinator.probeSelected(ProviderSource.QS)
        assertFalse(qsProbe.isRunning)
        h.coordinator.noteBackground(ProviderSource.QS)
        // Apply：共用入口锁后 acquire 被拒（NEEDS_FOREGROUND_REVALIDATION）
        try {
            ApplyProviderGate().withLease(
                h.coordinator, ProviderOwner("apply"), ProviderSource.APPLY,
            ) { fail("冷 Root 不得签发 lease") }
            fail("冷 Root acquire 必须抛 NEEDS_FOREGROUND_REVALIDATION")
        } catch (e: ProviderAccessException) {
            assertEquals(ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION, e.reason)
        }

        assertEquals("冷 Root 零 openShell", 0, h.backend.openShellCalls.get())
        assertEquals("冷 Root 零 RootService.bind", 0, h.backend.bindCalls.get())
        assertEquals("零 Shizuku 静默回退（factory 零调用）", 0, h.factory.created.size)

        h.logger.flushSuppressed()
        val lines = h.logLines.filter { it.contains("background_revalidation_needed") }
        assertEquals("五种后台路径只产生一条按状态去重的记录", 1, lines.size)
        assertTrue("其余 4 次被去重计数", lines.single().contains("suppressed=4"))
    }

    @Test
    fun readyRootLeaseReusesAnchorWithoutRebindOrShell() = runTest {
        val h = harness()
        advanceUntilIdle()
        h.raw.emit("root")
        driveToReady(h)
        assertEquals(1, h.backend.openShellCalls.get())
        assertEquals(1, h.backend.bindCalls.get())

        // 模拟 Listener 30s 通知读取：probe READY → acquire → withService 两次 IPC → close
        val probe = h.coordinator.probeSelected(ProviderSource.LISTENER)
        assertTrue(probe.isRunning)
        val lease = h.coordinator.acquire(ProviderOwner("listener-notify"), ProviderSource.LISTENER)
        assertEquals(ProviderKind.ROOT, lease.kind)
        try {
            assertEquals("[]", lease.withService { svc -> svc.dumpDevices() })
            lease.withService { svc -> svc.readConfig(MAC) }
        } finally {
            lease.close()
        }

        assertEquals("READY 后后台零新增 openShell", 1, h.backend.openShellCalls.get())
        assertEquals("复用进程级 anchor，零新增 RootService.bind", 1, h.backend.bindCalls.get())
        assertEquals("lease close 不解绑 Root anchor（§11.3）", 0, h.backend.unbindCalls.get())
        assertEquals("Root 通道零 Shizuku provider 创建", 0, h.factory.created.size)
    }

    private companion object {
        private const val MAC = "AA:BB:CC:DD:EE:FF"
    }
}
