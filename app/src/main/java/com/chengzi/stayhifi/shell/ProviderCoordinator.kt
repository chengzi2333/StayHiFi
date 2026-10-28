package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.LogStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 探测/日志来源（设计 §11.6）。 */
enum class ProviderSource { ONBOARDING, SETTINGS, BOOT, LISTENER, QS, APPLY }

/**
 * 通道选择的只读存储面（设计 §11.3）：[rawSelection] 异步恢复持久值，
 * [setRawSelection] 提交新选择。由 SettingsRepository 实现。
 */
interface ProviderSelectionStore {
    val rawSelection: kotlinx.coroutines.flow.Flow<String?>
    suspend fun setRawSelection(value: String)
}

/** 一次通道探测的结果。Root 通道额外带回可观察授权三态（只读，未验证为 null）。 */
data class ProviderProbe(
    val kind: ProviderKind,
    val isRunning: Boolean,
    val hasPermission: Boolean,
    /** Root 通道：isAppGrantedRoot 的可观察三态；非 Root 探测为 null（§11.4.2）。 */
    val rootGranted: Boolean? = null,
)

/**
 * Task D6：owner+generation 的特权通道 lease（设计 §11.3）。
 *
 *  - [isCurrent] = 未 close 且代次仍是 Coordinator 当前 generation；
 *  - [withService]：SHIZUKU 在注入 IO dispatcher lazy bind 本 owner 的 provider；
 *    ROOT 在 RootTransport 的独立 IPC Mutex 内复查代次/READY 后复用进程级 anchor；
 *  - [close] 只释放 owner 资源（Shizuku provider unbind）；Root anchor 不属于任何 lease。
 */
class ProviderLease internal constructor(
    val owner: ProviderOwner,
    val kind: ProviderKind,
    val generation: Long,
    private val coordinator: ProviderCoordinator,
    internal val shizukuProvider: ShellProvider?,
) {
    private val closed = AtomicBoolean(false)

    fun isCurrent(): Boolean = !closed.get() && generation == coordinator.currentGeneration()

    internal fun isClosed(): Boolean = closed.get()

    suspend fun <T> withService(block: suspend (ICodecService) -> T): T =
        coordinator.withService(this, block)

    fun close() {
        if (closed.compareAndSet(false, true)) coordinator.release(this)
    }
}

/**
 * Task D2：进程级通道协调器（设计 §11.3/§11.4.5）。
 *
 * 在注入的 application scope 异步 collect 持久值：首读完成前 [selectedProvider] /
 * [activeProvider] 均为 null，并阻止一切特权 IPC——不得先构造默认 Shizuku 再"纠正"，
 * 也不得在 Application/BootReceiver 主线程 runBlocking 首读。
 *
 * 探测边界：[probeSelected] 必须先 [awaitSelection]；
 * Shizuku 探测由 [shizukuFactory] 创建的 provider 回答 isRunning/hasPermission（绝不 bind）；
 * Root 探测只读注入的 [RootStatusSource]/[rootTransport] 状态，业务层拿不到 factory，
 * 绝不创建 shell 或弹窗。candidate 提交与切换事务由后续任务在此之上扩展。
 *
 * Task D6 扩展：每次选择变化发布新 generation；[acquire] 首读后签发带代次的 lease；
 * 后台未就绪只经 [noteBackground] 去重记录 NEEDS_FOREGROUND_REVALIDATION，
 * 绝不自动切换、绝不自动 openShell/bind。
 */
class ProviderCoordinator(
    applicationScope: CoroutineScope,
    store: ProviderSelectionStore,
    private val shizukuFactory: () -> ShellProvider,
    private val rootStatusSource: RootStatusSource = RootStatusSource { null },
    private val log: (String) -> Unit = { LogStore.log(it) },
    private val rootTransport: RootTransport? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rootLogger: RootDiagnosticLogger? = null,
) {

    /** Root 通道的可观察授权状态源：只读，绝不发起授权或创建 shell（§11.4.5）。 */
    fun interface RootStatusSource {
        fun granted(): Boolean?
    }

    private val _selectedProvider = MutableStateFlow<ProviderKind?>(null)
    val selectedProvider: StateFlow<ProviderKind?> = _selectedProvider.asStateFlow()

    private val _activeProvider = MutableStateFlow<ProviderKind?>(null)
    val activeProvider: StateFlow<ProviderKind?> = _activeProvider.asStateFlow()

    private val firstSelection = CompletableDeferred<ProviderKind>()
    private val loggedUnknownValues = mutableSetOf<String>()

    private val generationCounter = AtomicLong(0)
    @Volatile private var generation: Long = 0

    init {
        applicationScope.launch {
            store.rawSelection.collect { raw ->
                val kind = ProviderKind.fromStored(raw)
                if (raw != null && raw != ProviderKind.SHIZUKU.stored &&
                    raw != ProviderKind.ROOT.stored && loggedUnknownValues.add(raw)) {
                    log("未知 provider 值 '$raw'，回退 ${ProviderKind.SHIZUKU.stored}")
                }
                if (_selectedProvider.value != kind) {
                    // 选择变化发布新代次：旧 generation 的 lease 全部失效（§11.3）
                    generation = generationCounter.incrementAndGet()
                }
                _selectedProvider.value = kind
                _activeProvider.value = kind
                if (!firstSelection.isCompleted) firstSelection.complete(kind)
            }
        }
    }

    /** 当前代次（lease 签发/校验用）。 */
    internal fun currentGeneration(): Long = generation

    /** 挂起至首读完成并返回所选通道；此后直接返回当前选择。 */
    suspend fun awaitSelection(): ProviderKind = firstSelection.await()

    /**
     * 探测当前所选通道的就绪态。必须先完成首读；
     * 返回前绝不 bind/创建 shell（§11.4.5 后台不得制造授权弹窗）。
     */
    suspend fun probeSelected(source: ProviderSource): ProviderProbe {
        val kind = awaitSelection()
        return when (kind) {
            ProviderKind.SHIZUKU -> {
                val provider = shizukuFactory()
                ProviderProbe(
                    kind = kind,
                    isRunning = provider.isRunning,
                    hasPermission = provider.hasPermission,
                )
            }
            ProviderKind.ROOT -> {
                val granted = rootStatusSource.granted()
                val ready = rootTransport?.state?.value?.status == RootStatus.READY
                ProviderProbe(
                    kind = kind,
                    isRunning = ready,
                    hasPermission = ready,
                    rootGranted = granted,
                )
            }
        }
    }

    /**
     * Task D6：为 [owner] 签发带当前 generation 的 lease。首读前挂起（零 IPC）；
     * Root 仅在当前进程 READY 时签发，否则去重记录并抛 NEEDS_FOREGROUND_REVALIDATION。
     */
    suspend fun acquire(owner: ProviderOwner, source: ProviderSource): ProviderLease {
        val kind = awaitSelection()
        if (kind == ProviderKind.ROOT) {
            val ready = rootTransport?.state?.value?.status == RootStatus.READY
            if (!ready) {
                noteBackground(source)
                throw ProviderAccessException(
                    ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION,
                    "Root 未在当前进程就绪，需前台重验（§11.4.5）")
            }
        }
        return ProviderLease(
            owner = owner,
            kind = kind,
            generation = currentGeneration(),
            coordinator = this,
            shizukuProvider = if (kind == ProviderKind.SHIZUKU) shizukuFactory() else null,
        )
    }

    /**
     * 后台路径登记（Boot/Listener/QS/ApplyWorker）：Root 未就绪时只按状态变化去重记录，
     * 绝不自动请求授权、绝不自动切换通道（§11.4.5/§11.6）。
     */
    fun noteBackground(source: ProviderSource) {
        val selected = _selectedProvider.value ?: return
        if (selected != ProviderKind.ROOT) return
        val transport = rootTransport ?: return
        val snapshot = transport.state.value
        if (snapshot.status == RootStatus.READY) return
        (rootLogger ?: transport.logger).log(
            RootLogEvent(
                provider = ProviderKind.ROOT,
                event = RootLogEventKind.BACKGROUND_REVALIDATION_NEEDED,
                source = source,
                attemptId = "-",
                generation = generation,
                selected = selected,
                active = _activeProvider.value,
                granted = snapshot.granted,
                shell = snapshot.shellStatus,
                outcome = "needs_foreground_revalidation",
                observedReason = snapshot.observedReason,
            )
        )
    }

    internal suspend fun <T> withService(
        lease: ProviderLease,
        block: suspend (ICodecService) -> T,
    ): T {
        if (lease.isClosed()) {
            throw ProviderAccessException(ProviderAccessReason.LEASE_CLOSED,
                "lease(${lease.owner.id}) 已关闭")
        }
        if (lease.generation != currentGeneration()) {
            throw ProviderAccessException(ProviderAccessReason.STALE_GENERATION,
                "lease(${lease.owner.id}) 代次 ${lease.generation} 已过期（当前 $generation）")
        }
        return when (lease.kind) {
            ProviderKind.SHIZUKU -> {
                val provider = lease.shizukuProvider
                    ?: throw IllegalStateException("Shizuku lease 缺少 provider")
                val service = withContext(ioDispatcher) { provider.bind() }
                block(service)
            }
            ProviderKind.ROOT -> {
                val transport = rootTransport
                    ?: throw ProviderAccessException(
                        ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION, "RootTransport 未接线")
                try {
                    transport.withReadyAnchor { service ->
                        // 锁内复查代次：等待 Mutex 期间可能已切换
                        if (lease.generation != currentGeneration()) {
                            throw ProviderAccessException(ProviderAccessReason.STALE_GENERATION,
                                "lease(${lease.owner.id}) 代次已过期")
                        }
                        block(service)
                    }
                } catch (e: RootAccessException) {
                    throw ProviderAccessException(
                        ProviderAccessReason.NEEDS_FOREGROUND_REVALIDATION, e.message, e)
                }
            }
        }
    }

    /** close 只释放 owner 资源：Shizuku 解绑本 owner 的 provider；Root anchor 不在此解绑（§11.3）。 */
    internal fun release(lease: ProviderLease) {
        lease.shizukuProvider?.unbind()
    }
}
