package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.trigger.ApplyLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    fun isCurrent(): Boolean = !closed.get() && coordinator.isGenerationCurrent(generation)

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
 *
 * Task D7 扩展：有界切换事务（§11.3）。进入 SWITCHING 拒绝新 session→20 秒内有界等待
 * [applyLock]（全局 ApplyLock）空闲与 Root 活动 IPC 归零→作废旧代次→主线程幂等 unbind
 * 旧 Root anchor→持久化新选择（写盘失败/挂起都不算成功）→发布新 generation。
 * 超时回退代次作废、保留原 kind+generation；写盘失败保留原选择并发布失败终态，
 * 旧 Root 已按事务解绑时在结果里明确 NEEDS_FOREGROUND_REVALIDATION。
 */
class ProviderCoordinator(
    applicationScope: CoroutineScope,
    private val store: ProviderSelectionStore,
    private val shizukuFactory: () -> ShellProvider,
    private val rootStatusSource: RootStatusSource = RootStatusSource { null },
    private val log: (String) -> Unit = { LogStore.log(it) },
    private val rootTransport: RootTransport? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rootLogger: RootDiagnosticLogger? = null,
    /** 切换事务有界等待的业务互斥锁（§11.3）；默认全局 ApplyLock，测试注入可控实例。 */
    private val applyLock: Mutex = ApplyLock.mutex,
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

    // ── final review fix：切换写盘的迟到完成防护与补偿写（§11.3 超时/取消保留原选择） ──
    /** 一次切换/补偿写盘；[version] 单调递增（写槽认领顺序），[submitted]=已真正把写交给 store。 */
    private class StoreWrite(val stored: String, val version: Long) {
        @Volatile var submitted = false
    }
    private val storeWriteLock = Any()
    /** 未完成（未确认/未完成补偿）的写：其回读一律不采用——成功由 commitSwitch 直接发布状态。 */
    private val pendingStoreWrites = mutableListOf<StoreWrite>()
    /** 已放弃/已完成补偿的写盘值：其（迟到）回读一律忽略，不得采用为当前选择。 */
    private val staleStoreWrites = mutableSetOf<String>()
    private var storeWriteVersion = 0L
    /** 全部 store 写（事务+补偿）串行：与 DataStore 串行 edit 对齐，保证提交顺序=落盘顺序。 */
    private val storeWriteMutex = Mutex()
    /** 补偿写的独立宿主（非后台轮询）：与调用方/切换事务解耦，取消事务不取消补偿。 */
    private val appScope: CoroutineScope = applicationScope

    private fun beginStoreWriteLocked(stored: String): StoreWrite {
        val write = StoreWrite(stored, version = ++storeWriteVersion)
        pendingStoreWrites.add(write)
        return write
    }

    private fun beginStoreWrite(stored: String): StoreWrite =
        synchronized(storeWriteLock) { beginStoreWriteLocked(stored) }

    /** setRawSelection 正常返回才算写盘成功；同值旧 stale 记录一并清除（值已确认落盘）。 */
    private fun confirmStoreWrite(write: StoreWrite) {
        synchronized(storeWriteLock) {
            pendingStoreWrites.remove(write)
            staleStoreWrites.remove(write.stored)
        }
    }

    /** 超时/失败/取消：底层写可能仍迟完成并回读；该回读不得采用已放弃的 target。
     *  返回是否已真正把写交给 store（未提交则不存在迟写，无需补偿）。 */
    private fun abandonStoreWrite(write: StoreWrite): Boolean {
        synchronized(storeWriteLock) {
            pendingStoreWrites.remove(write)
            staleStoreWrites.add(write.stored)
        }
        return write.submitted
    }

    /** 补偿写结束（成败同口径）：其值是调度时的原选择，回读一律按过期忽略；
     *  其后若有同值合法新写，confirm 会先行移除该 stale 记录。 */
    private fun completeCompensationWrite(write: StoreWrite) {
        synchronized(storeWriteLock) {
            pendingStoreWrites.remove(write)
            staleStoreWrites.add(write.stored)
        }
    }

    /**
     * 切换写盘超时/取消后的补偿：把放弃事务时的原选择重新排队写盘（独立 application scope，
     * 一次性任务，非后台轮询）。DataStore 串行 edit 使补偿排在迟写之后；经 [storeWriteMutex]
     * 与单调版本检查防覆盖更新的合法切换——认领写槽时已有更大 version 的切换写则补偿过时跳过，
     * 新写最终获胜。失败如实记录日志，不吞异常。
     */
    private fun schedulePreviousSelectionRewrite(abandoned: StoreWrite) {
        val previous = _selectedProvider.value ?: return
        val barrier = abandoned.version
        appScope.launch {
            storeWriteMutex.withLock {
                val write = synchronized(storeWriteLock) {
                    if (storeWriteVersion > barrier) null else beginStoreWriteLocked(previous.stored)
                }
                if (write == null) {
                    log("切换补偿写盘跳过：已有更新的切换写（barrier=$barrier），新写最终获胜")
                    return@withLock
                }
                log("切换写盘超时/取消：补偿写回原选择 ${previous.stored}（barrier=$barrier）")
                try {
                    store.setRawSelection(previous.stored)
                } catch (t: Throwable) {
                    // 补偿失败如实记录；遗留迟写回读由 stale 集合继续在本进程忽略
                    log("切换补偿写盘失败: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
                }
                completeCompensationWrite(write)
            }
        }
    }

    // ── Task D7：切换事务状态 ──
    /** 代次水位线：generation <= [staleWatermark] 的 lease 一律视为已作废（事务内先作废旧代次）。 */
    @Volatile private var staleWatermark: Long = -1L
    private val switchMutex = Mutex()

    private val _switching = MutableStateFlow(false)
    /** 切换事务进行中：期间拒绝一切新 session（§11.3）。 */
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    init {
        applicationScope.launch {
            store.rawSelection.collect { raw ->
                // 迟到写盘防护：本事务尚未确认（commitSwitch 成功分支会直接发布状态，回读冗余）
                // 或已放弃的写若稍后完成并回读，一律不得把选择改成该 target（§11.3 超时保留原选择）。
                val adopt = synchronized(storeWriteLock) {
                    when {
                        raw != null && pendingStoreWrites.any { it.stored == raw } -> false
                        raw != null && staleStoreWrites.remove(raw) -> false
                        else -> true
                    }
                }
                if (!adopt) {
                    log("忽略已放弃/未确认切换事务的迟到写盘回读: '$raw'，保留当前选择")
                    return@collect
                }
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

    /** 代次有效性：等于当前代次且未被切换事务作废。 */
    internal fun isGenerationCurrent(gen: Long): Boolean = gen == generation && gen > staleWatermark

    /** 挂起至首读完成并返回所选通道；此后直接返回当前选择（含切换事务发布的新值）。 */
    suspend fun awaitSelection(): ProviderKind {
        firstSelection.await()
        return _selectedProvider.value!!
    }

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
     * Task D7：SWITCHING（切换事务进行中）拒绝签发新 session（§11.3）。
     */
    suspend fun acquire(owner: ProviderOwner, source: ProviderSource): ProviderLease {
        val kind = awaitSelection()
        if (_switching.value) {
            throw ProviderAccessException(
                ProviderAccessReason.SWITCHING, "切换事务进行中，拒绝新 session（§11.3）")
        }
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

    // ─────────────────────────── Task D7：有界切换事务（§11.3） ───────────────────────────

    /**
     * 前台 Root 验证+提交（教程/设置点击）：auth（单飞）→ bind+健康检查 → commit。
     * candidate 只有在三者全部成功后才进入 commit；失败/取消只清 candidate，绝不写盘。
     * 已选 Root 且 READY 时幂等返回 Committed；SWITCHING 期间拒绝（ALREADY_SWITCHING）。
     */
    suspend fun verifyRootFromForeground(
        owner: ProviderOwner,
        source: ProviderSource,
    ): RootVerificationResult {
        val transport = rootTransport
            ?: return RootVerificationResult.Failed(RootStatus.UNVERIFIED, "RootTransport 未接线")
        val current = awaitSelection()
        if (current == ProviderKind.ROOT && transport.state.value.status == RootStatus.READY) {
            return RootVerificationResult.Committed(currentGeneration())
        }
        if (_switching.value) {
            return RootVerificationResult.NotCommitted(SwitchFailureReason.ALREADY_SWITCHING)
        }
        val attempt = transport.beginForegroundAuthorization(owner, source)
        val candidate = try {
            transport.awaitCandidate(attempt)
        } catch (ce: CancellationException) {
            transport.invalidateAttempt(attempt.id)
            throw ce
        } catch (e: RootAccessException) {
            return RootVerificationResult.Failed(e.status, e.message)
        }
        try {
            transport.bindAndCheck(candidate)
        } catch (ce: CancellationException) {
            transport.releaseCandidate(candidate)
            throw ce
        } catch (e: RootAccessException) {
            transport.releaseCandidate(candidate)
            return RootVerificationResult.Failed(e.status, e.message)
        }
        return when (val r = commitSwitch(ProviderKind.ROOT, candidate, source)) {
            is CommitOutcome.Success -> RootVerificationResult.Committed(r.generation)
            is CommitOutcome.Aborted ->
                RootVerificationResult.NotCommitted(r.reason, r.needsRevalidation)
        }
    }

    /**
     * 手动退回 Shizuku（§11.2/§11.3）：任何时候都可手动选择，不检查 Root 可用性。
     * 已是 Shizuku 时幂等返回，不重复写盘。
     */
    suspend fun switchToShizuku(owner: ProviderOwner, source: ProviderSource): ProviderSwitchResult {
        val current = awaitSelection()
        if (current == ProviderKind.SHIZUKU) {
            return ProviderSwitchResult.Committed(ProviderKind.SHIZUKU, currentGeneration())
        }
        return when (val r = commitSwitch(ProviderKind.SHIZUKU, candidate = null, source)) {
            is CommitOutcome.Success ->
                ProviderSwitchResult.Committed(ProviderKind.SHIZUKU, r.generation)
            is CommitOutcome.Aborted ->
                ProviderSwitchResult.NotCommitted(r.reason, r.needsRevalidation)
        }
    }

    /** 离页/改选/手动取消：作废该 owner 全部进行中的前台 attempt，迟到成功不得提交（§11.4.6）。 */
    fun cancelForegroundAttempt(owner: ProviderOwner) {
        rootTransport?.invalidateAttemptsOf(owner)
    }

    private sealed interface CommitOutcome {
        data class Success(val generation: Long) : CommitOutcome
        data class Aborted(val reason: SwitchFailureReason, val needsRevalidation: Boolean) : CommitOutcome
    }

    /**
     * 切换事务（§11.3 顺序）：SWITCHING 阻止新 session → 有界等待 ApplyLock 空闲与
     * Root 活动 IPC 归零（20 秒预算同时覆盖写盘挂起）→ 作废旧代次 → 主线程幂等 unbind
     * 旧 Root anchor → 持久化新选择 → 写盘成功后发布新 generation。
     *
     *  - 超时：回退代次作废（保留原 kind+generation），candidate anchor 回退；
     *  - 写盘失败：不算成功、不采用 candidate、保留原选择，发布新代次承载失败终态；
     *  - 取消：只清 candidate，不改选择/代次；
     *  - 旧 Root 已按事务解绑而保留的选择仍是 Root 时，结果明确 NEEDS_FOREGROUND_REVALIDATION。
     */
    private suspend fun commitSwitch(
        target: ProviderKind,
        candidate: RootCandidate?,
        source: ProviderSource,
    ): CommitOutcome {
        if (!switchMutex.tryLock()) {
            return CommitOutcome.Aborted(SwitchFailureReason.ALREADY_SWITCHING,
                needsRevalidation = false)
        }
        val previousWatermark = staleWatermark
        var invalidated = false
        try {
            _switching.value = true
            var persistFailure: Throwable? = null
            val completed = withTimeoutOrNull(SWITCH_TIMEOUT_MS) {
                // 有界等待：ApplyLock 空闲且 Root 活动 IPC 归零
                while (applyLock.isLocked || (rootTransport?.activeIpcCount ?: 0) > 0) {
                    delay(DRAIN_POLL_MS)
                }
                // 作废旧代次：旧 lease 立即 STALE，事务期间不得再发起 IPC
                staleWatermark = generation
                invalidated = true
                // 主线程幂等 unbind 旧 Root anchor（离开 Root 方向；disconnectAnchor 内部幂等）
                if (target != ProviderKind.ROOT) {
                    rootTransport?.disconnectAnchor()
                }
                val storeWrite = beginStoreWrite(target.stored)
                try {
                    storeWriteMutex.withLock {
                        storeWrite.submitted = true
                        store.setRawSelection(target.stored)
                    }
                    // 正常返回才算写盘成功；随后成功分支直接发布新代次与选择
                    confirmStoreWrite(storeWrite)
                } catch (ce: CancellationException) {
                    // 超时/取消：底层写可能不可取消、稍后仍完成——已提交的写排队补偿写回原选择
                    if (abandonStoreWrite(storeWrite)) {
                        schedulePreviousSelectionRewrite(storeWrite)
                    }
                    throw ce
                } catch (t: Throwable) {
                    // 同步写失败不存在迟写：不补偿
                    abandonStoreWrite(storeWrite)
                    persistFailure = t
                }
            }
            return when {
                completed == null -> {
                    // 超时（含写盘挂起）：回退代次作废，保留原 kind+generation
                    if (invalidated) staleWatermark = previousWatermark
                    rollbackCandidateAnchor(candidate)
                    logSwitchEnd(source, target, candidate, "switch_timeout")
                    CommitOutcome.Aborted(SwitchFailureReason.SWITCH_TIMEOUT, needsRevalidation())
                }
                persistFailure != null -> {
                    // 写盘失败：不采用 candidate、保留原选择；发布新代次承载失败终态
                    rollbackCandidateAnchor(candidate)
                    generation = generationCounter.incrementAndGet()
                    logSwitchEnd(source, target, candidate, "persist_failed", rootCause = persistFailure)
                    CommitOutcome.Aborted(SwitchFailureReason.PERSIST_FAILED, needsRevalidation())
                }
                else -> {
                    // 写盘成功后才发布新 generation
                    generation = generationCounter.incrementAndGet()
                    _selectedProvider.value = target
                    _activeProvider.value = target
                    logSwitchEnd(source, target, candidate, "committed")
                    CommitOutcome.Success(generation)
                }
            }
        } catch (ce: CancellationException) {
            // 取消只清 candidate：回退代次作废，不改选择
            if (invalidated) staleWatermark = previousWatermark
            rollbackCandidateAnchor(candidate)
            throw ce
        } finally {
            _switching.value = false
            switchMutex.unlock()
        }
    }

    /** candidate anchor 回退（写盘失败/超时/取消时不采用 candidate）；disconnectAnchor 幂等。 */
    private suspend fun rollbackCandidateAnchor(candidate: RootCandidate?) {
        if (candidate == null) return
        try {
            rootTransport?.disconnectAnchor()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // 回退解绑失败不影响终态发布
        }
    }

    /** 保留的选择仍是 Root 但 transport 已非 READY（旧 anchor 已按事务解绑）→ 需前台重验。 */
    private fun needsRevalidation(): Boolean =
        _selectedProvider.value == ProviderKind.ROOT &&
            rootTransport?.state?.value?.status != RootStatus.READY

    /** provider 切换终态日志（§11.6：切换另记一条终态）。 */
    private fun logSwitchEnd(
        source: ProviderSource,
        target: ProviderKind,
        candidate: RootCandidate?,
        outcome: String,
        rootCause: Throwable? = null,
    ) {
        val snapshot = rootTransport?.state?.value
        val event = RootLogEvent(
            provider = target,
            event = RootLogEventKind.PROVIDER_SWITCH_END,
            source = source,
            attemptId = candidate?.attemptId?.toString() ?: "-",
            generation = generation,
            selected = _selectedProvider.value,
            active = _activeProvider.value,
            granted = snapshot?.granted,
            shell = snapshot?.shellStatus ?: RootShellStatus.UNKNOWN,
            outcome = outcome,
            observedReason = "switch to ${target.stored}",
            rootCause = rootCause,
        )
        val logger = rootLogger ?: rootTransport?.logger
        if (logger != null) {
            logger.log(event)
        } else {
            log("provider_switch_end outcome=$outcome target=${target.stored} generation=$generation")
        }
    }

    internal suspend fun <T> withService(
        lease: ProviderLease,
        block: suspend (ICodecService) -> T,
    ): T {
        if (lease.isClosed()) {
            throw ProviderAccessException(ProviderAccessReason.LEASE_CLOSED,
                "lease(${lease.owner.id}) 已关闭")
        }
        if (!isGenerationCurrent(lease.generation)) {
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
                        // 锁内复查代次：等待 Mutex 期间可能已切换/作废
                        if (!isGenerationCurrent(lease.generation)) {
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

    companion object {
        /** 切换事务总预算（含 ApplyLock/活动 IPC 等待与写盘），超时保留原选择（§11.3）。 */
        const val SWITCH_TIMEOUT_MS = 20_000L
        /** 有界等待的轮询间隔。 */
        private const val DRAIN_POLL_MS = 100L
    }
}

/** Task D7：切换事务未提交的失败分类（事实命名，无猜测语义）。 */
enum class SwitchFailureReason {
    /** 已有切换事务在进行（SWITCHING 拒绝新事务） */
    ALREADY_SWITCHING,
    /** 20 秒预算超时（ApplyLock/活动 IPC 未归零或写盘挂起）：保留原 kind+generation，稍后重试 */
    SWITCH_TIMEOUT,
    /** DataStore 写盘失败：不算成功、保留原选择、发布失败终态 */
    PERSIST_FAILED,
}

/**
 * Task D7：手动切换通道的终态（§11.3）。
 * [NotCommitted.needsForegroundRevalidation]：保留的选择仍是 Root 但 transport 已非 READY
 * （旧 anchor 已按事务解绑），需前台重验。
 */
sealed interface ProviderSwitchResult {
    data class Committed(val kind: ProviderKind, val generation: Long) : ProviderSwitchResult
    data class NotCommitted(
        val reason: SwitchFailureReason,
        val needsForegroundRevalidation: Boolean = false,
    ) : ProviderSwitchResult
}

/**
 * Task D7：前台 Root 验证+提交的终态（§11.3/§11.4）。
 *  - [Failed]：授权/bind/健康检查失败，candidate 已清理，原选择与原代次不变；
 *  - [NotCommitted]：验证成功但切换事务未提交（冲突/超时/写盘失败），原选择保留。
 */
sealed interface RootVerificationResult {
    data class Committed(val generation: Long) : RootVerificationResult
    data class Failed(val status: RootStatus, val message: String?) : RootVerificationResult
    data class NotCommitted(
        val reason: SwitchFailureReason,
        val needsForegroundRevalidation: Boolean = false,
    ) : RootVerificationResult
}
