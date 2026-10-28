package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.ICodecService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 特权通道的使用方身份（owner+generation lease 语义由 Task D6 补全）。 */
data class ProviderOwner(val id: String)

/** 一次前台 Root 授权尝试的句柄；id 单调分配，用于迟到结果失效判定（§11.4.6）。 */
data class RootAttempt(val id: Long, val owner: ProviderOwner, val source: ProviderSource)

/**
 * 未提交的 Root candidate：root shell 已验证（isRoot=true），但尚未 bind/健康检查/提交。
 * candidate 存在期间 activeProvider 保持原值（§11.3）；bind/健康检查见 [RootTransport.bindAndCheck]。
 */
class RootCandidate internal constructor(
    val attemptId: Long,
    val owner: ProviderOwner,
    val observation: RootShellObservation,
) {
    internal var binding: RootBinding? = null
    internal var service: ICodecService? = null
}

/** 授权/绑定阶段失败：status 取可观察终态（事实命名，§11.4.3），不含"用户拒绝/没有 su"等猜测语义。 */
class RootAccessException(
    val status: RootStatus,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: status.name, cause)

/**
 * Task D4：前台 Root 授权状态机（设计 §11.3/§11.4/§11.6）。
 *
 * 不变式：
 *  - 每个新进程从 UNVERIFIED 开始；持久选择不等于授权，无显式 begin 绝不 openShell（§11.3）。
 *  - [RootBackend.grantedState] 三态原样保留：false → NOT_GRANTED（不创建 shell）；
 *    null 是"未知"，保持 REQUESTING 继续走 shell 验证，不得当作未授权（§11.4.2）。
 *  - 授权物理任务单飞且由 supervisor 持有：等待中再次点击复用同一 attempt；
 *    上次等待已结束（超时/失效）而底层未归还时，新点击只挂新等待者到同一物理任务，
 *    绝不并发第二次 openShell（§11.4.6）。
 *  - 30 秒 UI watchdog（[awaitCandidate]）只代表自有等待结束，不取消底层任务；
 *    迟到结果只做 attemptId/失效检查，已失效 attempt 的成功不得成为 candidate、不得 READY。
 *  - 只有 root shell（isRoot=true）才进入 GRANTED_NOT_READY 并构造未提交 candidate。
 */
class RootTransport(
    applicationScope: CoroutineScope,
    private val backend: RootBackend,
    private val authDispatcher: CoroutineDispatcher,
    internal val logger: RootDiagnosticLogger = RootDiagnosticLogger(),
    private val authTimeoutMs: Long = DEFAULT_AUTH_TIMEOUT_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** RootService.bind/unbind 的调用线程（§11.4.7）；未注入时退化到 authDispatcher（仅测试路径）。 */
    mainDispatcher: CoroutineDispatcher? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bindTimeoutMs: Long = DEFAULT_BIND_TIMEOUT_MS,
    private val healthTimeoutMs: Long = DEFAULT_HEALTH_TIMEOUT_MS,
) {

    private val _state = MutableStateFlow(
        RootSnapshot(
            status = RootStatus.UNVERIFIED,
            granted = null,
            shellStatus = RootShellStatus.UNKNOWN,
            observedReason = "cold start",
        )
    )
    val state: StateFlow<RootSnapshot> = _state.asStateFlow()

    /** 只读透传可观察授权三态（§11.4.2）；供 Coordinator RootStatusSource 接线，绝不发起授权。 */
    fun grantedState(): Boolean? = backend.grantedState()

    /** 物理任务由独立 supervisor 持有：watchdog/调用方取消都不冒充底层取消（§11.4.6）。 */
    private val supervisor = SupervisorJob(applicationScope.coroutineContext[Job])
    private val authScope = CoroutineScope(applicationScope.coroutineContext + supervisor)

    private class AttemptRecord(
        val attempt: RootAttempt,
        val result: CompletableDeferred<RootCandidate>,
        val startedAtMs: Long,
    ) {
        var expired = false
    }

    private sealed interface AuthOutcome {
        data class RootShell(val observation: RootShellObservation, val granted: Boolean?) : AuthOutcome
        data class NonRootShell(val observation: RootShellObservation, val granted: Boolean?) : AuthOutcome
        data class Failure(val error: Throwable) : AuthOutcome
    }

    private val lock = Any()
    private val records = mutableMapOf<Long, AttemptRecord>()
    private var current: AttemptRecord? = null
    private var nextAttemptId = 0L
    private var physicalJob: Job? = null

    private val mainOrAuth: CoroutineDispatcher = mainDispatcher ?: authDispatcher

    // ── Task D5：bind/anchor 注册表（独立锁，不与授权 attempt 锁混用） ──
    private class BindingRecord(
        val id: Long,
        val connected: CompletableDeferred<ICodecService?>,
        val candidate: RootCandidate,
    ) {
        @Volatile var binding: RootBinding? = null
        var expired = false            // bind watchdog 过期：迟到连接必须主线程立即 unbind
        var cleanedUp = false          // unbind 幂等
        var remoteDisconnected = false
        var localDisconnect = false    // 主动解绑标记：区别于远端断开（§11.4.8）
        var isAnchor = false           // READY 后提升为进程级 anchor
    }

    private val bindLock = Any()
    private val bindings = mutableMapOf<Long, BindingRecord>()
    private var nextBindingId = 0L
    private var anchorCandidate: RootCandidate? = null

    /**
     * 显式前台授权入口（教程/设置点击）。单飞； granted=false 直接终态 NOT_GRANTED。
     * 返回的 attempt 交给 [awaitCandidate] 等待。
     */
    fun beginForegroundAuthorization(owner: ProviderOwner, source: ProviderSource): RootAttempt {
        val granted = backend.grantedState()
        synchronized(lock) {
            if (_state.value.status == RootStatus.READY) {
                val rec = newRecordLocked(owner, source)
                rec.expired = true
                rec.result.completeExceptionally(
                    RootAccessException(RootStatus.READY, "Root anchor 已就绪，无需重复授权"))
                return rec.attempt
            }
            if (granted == false) {
                val rec = newRecordLocked(owner, source)
                rec.expired = true
                publishLocked(snapshot(RootStatus.NOT_GRANTED, granted = false,
                    reason = "isAppGrantedRoot=false"))
                logLocked(rec, RootLogEventKind.ROOT_ATTEMPT_START, "start", granted = false)
                logLocked(rec, RootLogEventKind.ROOT_AUTH_END, "auth_false",
                    granted = false, errorClass = RootErrorClass.AUTH_FALSE)
                rec.result.completeExceptionally(
                    RootAccessException(RootStatus.NOT_GRANTED, "isAppGrantedRoot=false"))
                return rec.attempt
            }
            val job = physicalJob
            if (job != null) {
                val cur = current
                if (cur != null && !cur.expired) return cur.attempt   // 等待中再点击：同一 attempt
                // 上次等待已超时/失效而底层任务未归还：同一物理任务挂新等待者（不二次 openShell）
                val rec = newRecordLocked(owner, source)
                current = rec
                publishLocked(snapshot(RootStatus.REQUESTING, granted = granted,
                    reason = "awaiting pending shell"))
                logLocked(rec, RootLogEventKind.ROOT_ATTEMPT_START, "start_reuse_pending",
                    granted = granted)
                return rec.attempt
            }
            val rec = newRecordLocked(owner, source)
            current = rec
            publishLocked(snapshot(RootStatus.REQUESTING, granted = granted))
            logLocked(rec, RootLogEventKind.ROOT_ATTEMPT_START, "start", granted = granted)
            physicalJob = authScope.launch(authDispatcher) { runAuth() }
            return rec.attempt
        }
    }

    /**
     * UI 侧等待 attempt 结果；[authTimeoutMs]（默认 30 秒）watchdog 只结束自有等待，
     * 底层物理任务继续（§11.4.6）。成功返回未提交 candidate；失败/超时/失效抛 [RootAccessException]。
     */
    suspend fun awaitCandidate(attempt: RootAttempt): RootCandidate {
        val rec = synchronized(lock) { records[attempt.id] }
            ?: throw RootAccessException(RootStatus.UNVERIFIED, "未知 attempt ${attempt.id}")
        val result = withTimeoutOrNull(authTimeoutMs) { rec.result.await() }
        if (result != null) return result
        expireRecord(
            rec,
            status = RootStatus.AUTH_TIMEOUT,
            errorClass = RootErrorClass.AUTH_TIMEOUT,
            outcome = "auth_timeout",
            timeoutMs = authTimeoutMs,
            reason = "授权等待超过 ${authTimeoutMs}ms（底层任务可能仍在进行）",
        )
        throw RootAccessException(RootStatus.AUTH_TIMEOUT,
            "授权等待超过 ${authTimeoutMs}ms（底层任务可能仍在进行）")
    }

    /** 离页/改选/重新发起：旧 attempt 失效，迟到成功不得成为 candidate（§11.4.6）。 */
    fun invalidateAttempt(attemptId: Long) {
        val rec = synchronized(lock) { records[attemptId] } ?: return
        expireRecord(
            rec,
            status = RootStatus.UNVERIFIED,
            errorClass = RootErrorClass.NONE,
            outcome = "invalidated",
            reason = "attempt invalidated（离页/改选/再次发起）",
        )
    }

    /** Task D7：按 owner 作废全部进行中的前台 attempt（离页/改选/手动取消；§11.4.6）。 */
    fun invalidateAttemptsOf(owner: ProviderOwner) {
        val targets = synchronized(lock) {
            records.values.filter { it.attempt.owner == owner && !it.expired }
        }
        targets.forEach {
            expireRecord(
                it,
                status = RootStatus.UNVERIFIED,
                errorClass = RootErrorClass.NONE,
                outcome = "invalidated",
                reason = "owner(${owner.id}) 取消（离页/改选/手动取消）",
            )
        }
    }

    // ─────────────────────────── Task D5：bind / 健康检查 / anchor ───────────────────────────

    /** READY anchor 的当前 IPC 接口；仅当前进程 READY 时非空（供 RootShellProvider/lease 只读复用）。 */
    internal fun readyService(): ICodecService? =
        if (_state.value.status == RootStatus.READY) anchorCandidate?.service else null

    // ── Task D6：Root IPC 串行（与 ApplyLock 职责分离，§11.3） ──

    /** Root transport 的全部 IPC 经此独立协程 Mutex 串行，防并发 bind/重复回调/A2DP 初始化竞争。 */
    private val ipcMutex = Mutex()
    private val activeIpc = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当前进行中的 Root IPC 数（供切换事务有界等待，Task D7 使用）。 */
    val activeIpcCount: Int get() = activeIpc.get()

    /**
     * READY anchor 上的串行 IPC：进入前与锁内都复查 READY——anchor 断开（DISCONNECTED）
     * 后不可复用，抛 NEEDS_FOREGROUND_REVALIDATION 引导前台重验（§11.4.5）。
     */
    suspend fun <T> withReadyAnchor(block: suspend (ICodecService) -> T): T {
        if (_state.value.status != RootStatus.READY) {
            throw RootAccessException(RootStatus.NEEDS_FOREGROUND_REVALIDATION,
                "Root 未在当前进程就绪（status=${_state.value.status}），需前台重验")
        }
        return ipcMutex.withLock {
            val service = readyService() ?: throw RootAccessException(
                RootStatus.NEEDS_FOREGROUND_REVALIDATION, "anchor 已断开")
            activeIpc.incrementAndGet()
            try {
                block(service)
            } finally {
                activeIpc.decrementAndGet()
            }
        }
    }

    /**
     * 绑定 RootCodecService 并做 dumpDevices 健康检查（设计 §11.4.7-9）。
     * READY 的最低条件三者齐：root shell（授权阶段已验证）+ 非空 binder + dumpDevices 无 error。
     * 10 秒 bind watchdog 与 8 秒 health watchdog 都只标记自有等待结束，不冒充底层取消；
     * 迟到的连接/IPC 归还在回调里完成清理（主线程幂等 unbind）。成功后 candidate 提升为进程级 anchor。
     */
    suspend fun bindAndCheck(candidate: RootCandidate): RootCandidate {
        candidate.service?.let { return candidate }
        ensureCandidateCurrent(candidate)
        publish(RootStatus.BINDING, reason = "bind RootCodecService")

        val connected = CompletableDeferred<ICodecService?>()
        val record = synchronized(bindLock) {
            BindingRecord(++nextBindingId, connected, candidate).also { bindings[it.id] = it }
        }
        val callbacks = object : RootBindCallbacks {
            override fun onConnected(service: ICodecService?) = handleConnected(record, service)
            override fun onDisconnected() = handleRemoteDisconnected(record)
        }
        val bindStartedMs = clock()
        record.binding = try {
            withContext(mainOrAuth) { backend.bind(callbacks) }
        } catch (t: Throwable) {
            synchronized(bindLock) { bindings.remove(record.id) }
            publish(RootStatus.BIND_FAILED, reason = t.message ?: "")
            logPhase(RootLogEventKind.ROOT_BIND_END, "bind_failed", candidate,
                errorClass = RootErrorClass.BIND_FAILED,
                durationMs = clock() - bindStartedMs, rootCause = t)
            throw RootAccessException(RootStatus.BIND_FAILED, cause = t)
        }

        val service = withTimeoutOrNull(bindTimeoutMs) { connected.await() }
        if (!connected.isCompleted) {
            // bind watchdog：只标记过期，底层 pending bind 继续；迟到连接由回调在主线程 unbind（§11.4.8）
            synchronized(bindLock) { record.expired = true }
            publish(RootStatus.BIND_TIMEOUT, reason = "bind 等待超过 ${bindTimeoutMs}ms")
            logPhase(RootLogEventKind.ROOT_BIND_END, "bind_timeout", candidate,
                errorClass = RootErrorClass.BIND_TIMEOUT, timeoutMs = bindTimeoutMs)
            throw RootAccessException(RootStatus.BIND_TIMEOUT,
                "bind 等待超过 ${bindTimeoutMs}ms（底层 bind 可能仍在进行）")
        }
        if (service == null) {
            publish(RootStatus.BIND_NULL, reason = "onServiceConnected binder=null")
            logPhase(RootLogEventKind.ROOT_BIND_END, "bind_null", candidate,
                errorClass = RootErrorClass.BIND_NULL, bindCallback = "connected_null")
            cleanupBinding(record)
            throw RootAccessException(RootStatus.BIND_NULL, "onServiceConnected binder 为 null")
        }

        // 健康检查在 IO；watchdog 只标记等待结束，实际 IPC 归还后才完成清理
        val healthDeferred = authScope.async(ioDispatcher) {
            try {
                RootHealthChecker.checkDumpDevices(service.dumpDevices())
            } catch (t: Throwable) {
                RootHealthResult(RootHealthOutcome.MALFORMED,
                    detail = t.message?.take(120) ?: "dumpDevices 异常")
            }
        }
        val healthStartedMs = clock()
        var health = withTimeoutOrNull(healthTimeoutMs) { healthDeferred.await() }
        if (health == null) {
            publish(RootStatus.GRANTED_NOT_READY, reason = "health 等待超过 ${healthTimeoutMs}ms")
            logPhase(RootLogEventKind.ROOT_HEALTH_END, "health_timeout", candidate,
                errorClass = RootErrorClass.HEALTH_FAILURE, healthOp = "dumpDevices",
                healthResult = "timeout", timeoutMs = healthTimeoutMs,
                durationMs = clock() - healthStartedMs)
            // 非取消等待：IPC 实际归还后才清理 connection
            try {
                healthDeferred.await()
            } catch (t: Throwable) {
                // 已取消/失败不影响清理路径
            }
            cleanupBinding(record)
            throw RootAccessException(RootStatus.GRANTED_NOT_READY,
                "dumpDevices 健康检查等待超过 ${healthTimeoutMs}ms")
        }
        val result = health!!
        if (!result.isReady) {
            publish(RootStatus.GRANTED_NOT_READY, reason = "health ${result.outcome}: ${result.detail}")
            logPhase(RootLogEventKind.ROOT_HEALTH_END, "health_failed", candidate,
                errorClass = RootErrorClass.HEALTH_FAILURE, healthOp = "dumpDevices",
                healthResult = "${result.outcome}: ${result.detail}".take(160),
                durationMs = clock() - healthStartedMs)
            cleanupBinding(record)
            throw RootAccessException(RootStatus.GRANTED_NOT_READY,
                "dumpDevices 健康检查未通过: ${result.outcome}")
        }

        candidate.binding = record.binding
        candidate.service = service
        synchronized(bindLock) { record.isAnchor = true }
        anchorCandidate = candidate
        publish(RootStatus.READY, reason = "ready")
        logPhase(RootLogEventKind.ROOT_BIND_END, "ready", candidate,
            bindCallback = "connected", durationMs = clock() - bindStartedMs)
        logPhase(RootLogEventKind.ROOT_HEALTH_END, "health_ok", candidate,
            healthOp = "dumpDevices", healthResult = "ok devices=${result.deviceCount}",
            durationMs = clock() - healthStartedMs)
        return candidate
    }

    /** 失败/取消时清理未提交 candidate（§11.3）；已提升为 anchor 的不在此释放（用 [disconnectAnchor]）。 */
    suspend fun releaseCandidate(candidate: RootCandidate) {
        val owned = synchronized(bindLock) {
            bindings.values.filter { it.candidate === candidate && !it.isAnchor }
        }
        owned.forEach { cleanupBinding(it) }
        candidate.service = null
        candidate.binding = null
        invalidateAttempt(candidate.attemptId)
    }

    /**
     * 显式断开进程级 anchor（手动切换/显式断开路径；§11.3 Activity 销毁不得走这里）。
     * 主线程幂等 unbind，重复调用只执行一次。
     */
    suspend fun disconnectAnchor() {
        val anchor = synchronized(bindLock) { bindings.values.firstOrNull { it.isAnchor } }
        anchorCandidate = null
        if (anchor != null) {
            synchronized(bindLock) { anchor.localDisconnect = true }
            cleanupBinding(anchor)
            publish(RootStatus.DISCONNECTED, reason = "主动断开 anchor")
            logPhase(RootLogEventKind.ROOT_BIND_END, "disconnected", anchor.candidate,
                errorClass = RootErrorClass.DISCONNECTED, bindCallback = "local_disconnect")
        } else if (_state.value.status == RootStatus.READY) {
            publish(RootStatus.DISCONNECTED, reason = "主动断开 anchor")
        }
    }

    private fun ensureCandidateCurrent(candidate: RootCandidate) {
        synchronized(lock) {
            val rec = records[candidate.attemptId]
            if (rec == null || rec.expired || current !== rec) {
                throw RootAccessException(RootStatus.UNVERIFIED,
                    "candidate attempt ${candidate.attemptId} 已失效")
            }
        }
    }

    private fun handleConnected(record: BindingRecord, service: ICodecService?) {
        val late = synchronized(bindLock) {
            if (!bindings.containsKey(record.id)) return   // bind 抛异常路径已移除
            if (record.expired) {
                record.cleanedUp = true
                bindings.remove(record.id)
                true
            } else {
                record.connected.complete(service)
                false
            }
        }
        if (late) {
            // 迟到连接：不得 READY，必须在主线程立即 unbind（§11.4.8）；清理幂等
            logPhase(RootLogEventKind.ROOT_BIND_END, "late_connect_unbound", record.candidate,
                bindCallback = "late_connected")
            record.binding?.let { unbindOnMain(it) }
        }
    }

    private fun handleRemoteDisconnected(record: BindingRecord) {
        val wasAnchor: Boolean
        synchronized(bindLock) {
            // 主动解绑引发的回调与重复回调都只处理一次
            if (record.remoteDisconnected || record.localDisconnect || record.cleanedUp) return
            record.remoteDisconnected = true
            wasAnchor = record.isAnchor
        }
        if (record.expired) return   // bind 已超时的迟到断开：只去重，不再记录/改状态
        if (wasAnchor) {
            anchorCandidate = null
            publish(RootStatus.DISCONNECTED, reason = "远端服务断开")
            logPhase(RootLogEventKind.ROOT_BIND_END, "disconnected", record.candidate,
                errorClass = RootErrorClass.DISCONNECTED, bindCallback = "remote_disconnect")
        } else {
            publish(RootStatus.DISCONNECTED, reason = "bind 期间远端断开")
            logPhase(RootLogEventKind.ROOT_BIND_END, "disconnected", record.candidate,
                errorClass = RootErrorClass.DISCONNECTED, bindCallback = "remote_disconnect")
            record.connected.completeExceptionally(
                RootAccessException(RootStatus.DISCONNECTED, "远端服务在 bind 期间断开"))
        }
    }

    /** 幂等解绑：同一 binding 只执行一次；unbind 调用经注入 mainDispatcher（§11.4.7）。 */
    private fun cleanupBinding(record: BindingRecord) {
        val binding = synchronized(bindLock) {
            if (record.cleanedUp) return
            record.cleanedUp = true
            bindings.remove(record.id)
            record.binding
        } ?: return
        unbindOnMain(binding)
    }

    private fun unbindOnMain(binding: RootBinding) {
        authScope.launch(mainOrAuth) { backend.unbind(binding) }
    }

    private fun publish(
        status: RootStatus,
        reason: String,
        granted: Boolean? = _state.value.granted,
        shell: RootShellStatus = _state.value.shellStatus,
    ) {
        _state.value = RootSnapshot(status = status, granted = granted, shellStatus = shell,
            observedReason = reason)
    }

    private fun logPhase(
        kind: RootLogEventKind,
        outcome: String,
        candidate: RootCandidate,
        errorClass: RootErrorClass = RootErrorClass.NONE,
        durationMs: Long? = null,
        timeoutMs: Long? = null,
        bindCallback: String? = null,
        healthOp: String? = null,
        healthResult: String? = null,
        rootCause: Throwable? = null,
    ) {
        val source = synchronized(lock) {
            records[candidate.attemptId]?.attempt?.source
        } ?: ProviderSource.SETTINGS
        logger.log(
            RootLogEvent(
                provider = ProviderKind.ROOT,
                event = kind,
                source = source,
                attemptId = candidate.attemptId.toString(),
                generation = 0L,
                selected = null,
                active = null,
                granted = _state.value.granted,
                shell = _state.value.shellStatus,
                outcome = outcome,
                durationMs = durationMs,
                timeoutMs = timeoutMs,
                bindCallback = bindCallback,
                healthOp = healthOp,
                healthResult = healthResult,
                errorClass = errorClass,
                rootCause = rootCause,
            )
        )
    }

    // ─────────────────────────── 内部 ───────────────────────────

    private suspend fun runAuth() {
        val startedMs = clock()
        val outcome: AuthOutcome = try {
            val obs = backend.openShell()
            val granted = backend.grantedState()
            if (obs.isRoot) AuthOutcome.RootShell(obs, granted) else AuthOutcome.NonRootShell(obs, granted)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AuthOutcome.Failure(t)
        }
        val durationMs = clock() - startedMs
        val rec = synchronized(lock) {
            physicalJob = null
            val c = current
            if (c == null || c.expired) null else c
        } ?: return   // 迟到结果：attempt 已超时/失效，丢弃，不提交（§11.4.6）

        when (outcome) {
            is AuthOutcome.RootShell -> {
                val candidate = RootCandidate(rec.attempt.id, rec.attempt.owner, outcome.observation)
                synchronized(lock) {
                    publishLocked(snapshot(RootStatus.GRANTED_NOT_READY, granted = outcome.granted,
                        shell = RootShellStatus.ROOT_SHELL))
                    logLocked(rec, RootLogEventKind.ROOT_AUTH_END, "root_shell",
                        granted = outcome.granted, shell = RootShellStatus.ROOT_SHELL,
                        durationMs = durationMs)
                }
                rec.result.complete(candidate)
            }
            is AuthOutcome.NonRootShell -> {
                synchronized(lock) {
                    publishLocked(snapshot(RootStatus.NOT_GRANTED, granted = outcome.granted,
                        shell = RootShellStatus.NON_ROOT_SHELL, reason = "shell.isRoot=false"))
                    logLocked(rec, RootLogEventKind.ROOT_AUTH_END, "non_root_shell",
                        granted = outcome.granted, shell = RootShellStatus.NON_ROOT_SHELL,
                        durationMs = durationMs)
                }
                rec.result.completeExceptionally(RootAccessException(
                    RootStatus.NOT_GRANTED, "shell.isRoot=false（libsu 回落普通 sh）"))
            }
            is AuthOutcome.Failure -> {
                synchronized(lock) {
                    publishLocked(snapshot(RootStatus.SHELL_FAILURE, granted = null,
                        reason = outcome.error.message ?: ""))
                    logLocked(rec, RootLogEventKind.ROOT_AUTH_END, "shell_failure",
                        errorClass = RootErrorClass.SHELL_FAILURE,
                        durationMs = durationMs, rootCause = outcome.error)
                }
                rec.result.completeExceptionally(RootAccessException(
                    RootStatus.SHELL_FAILURE, cause = outcome.error))
            }
        }
    }

    private fun expireRecord(
        rec: AttemptRecord,
        status: RootStatus,
        errorClass: RootErrorClass,
        outcome: String,
        timeoutMs: Long? = null,
        reason: String,
    ) {
        synchronized(lock) {
            rec.expired = true
            rec.result.completeExceptionally(RootAccessException(status, reason))
            // 只有仍在 REQUESTING 的当前 attempt 才回写状态；已产生 candidate 的由后续任务接管
            if (current === rec && _state.value.status == RootStatus.REQUESTING) {
                publishLocked(snapshot(status, granted = _state.value.granted,
                    shell = _state.value.shellStatus, reason = reason))
                logLocked(rec, RootLogEventKind.ROOT_AUTH_END, outcome,
                    errorClass = errorClass, timeoutMs = timeoutMs)
            }
        }
    }

    private fun newRecordLocked(owner: ProviderOwner, source: ProviderSource): AttemptRecord {
        val id = ++nextAttemptId
        val rec = AttemptRecord(RootAttempt(id, owner, source), CompletableDeferred(), clock())
        records[id] = rec
        return rec
    }

    private fun snapshot(
        status: RootStatus,
        granted: Boolean?,
        shell: RootShellStatus = RootShellStatus.UNKNOWN,
        reason: String = "",
    ) = RootSnapshot(status = status, granted = granted, shellStatus = shell, observedReason = reason)

    private fun publishLocked(snapshot: RootSnapshot) {
        _state.value = snapshot
    }

    private fun logLocked(
        rec: AttemptRecord,
        kind: RootLogEventKind,
        outcome: String,
        granted: Boolean? = _state.value.granted,
        shell: RootShellStatus = _state.value.shellStatus,
        errorClass: RootErrorClass = RootErrorClass.NONE,
        durationMs: Long? = null,
        timeoutMs: Long? = null,
        rootCause: Throwable? = null,
    ) {
        logger.log(
            RootLogEvent(
                provider = ProviderKind.ROOT,
                event = kind,
                source = rec.attempt.source,
                attemptId = rec.attempt.id.toString(),
                generation = 0L,
                selected = null,
                active = null,
                granted = granted,
                shell = shell,
                outcome = outcome,
                durationMs = durationMs,
                timeoutMs = timeoutMs,
                errorClass = errorClass,
                rootCause = rootCause,
            )
        )
    }

    companion object {
        const val DEFAULT_AUTH_TIMEOUT_MS = 30_000L
        const val DEFAULT_BIND_TIMEOUT_MS = 10_000L
        const val DEFAULT_HEALTH_TIMEOUT_MS = 8_000L
    }
}

/** dumpDevices 健康检查结论（设计 §11.4.9）。 */
enum class RootHealthOutcome { OK, ERROR_REPORTED, MALFORMED }

data class RootHealthResult(
    val outcome: RootHealthOutcome,
    val deviceCount: Int = 0,
    val detail: String = "",
) {
    val isReady: Boolean get() = outcome == RootHealthOutcome.OK
}

/**
 * Task D5：dumpDevices JSON 健康判定。合法设备数组（含空数组）健康；
 * {"error":...}、畸形 JSON、非数组形状均不 READY（§11.4.9）。
 */
object RootHealthChecker {

    private val parser = kotlinx.serialization.json.Json

    fun checkDumpDevices(raw: String): RootHealthResult {
        val element = try {
            parser.parseToJsonElement(raw)
        } catch (t: Throwable) {
            return RootHealthResult(RootHealthOutcome.MALFORMED,
                detail = t.message?.take(120) ?: "parse error")
        }
        return when {
            element is JsonArray ->
                RootHealthResult(RootHealthOutcome.OK, deviceCount = element.size)
            element is JsonObject && element["error"] != null -> {
                val detail = (element["error"] as? JsonPrimitive)?.let {
                    runCatching { it.content }.getOrNull()
                } ?: element["error"].toString()
                RootHealthResult(RootHealthOutcome.ERROR_REPORTED, detail = detail.take(120))
            }
            else -> RootHealthResult(RootHealthOutcome.MALFORMED, detail = "unexpected json shape")
        }
    }
}
