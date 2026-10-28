package com.chengzi.stayhifi.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 特权通道的使用方身份（owner+generation lease 语义由 Task D6 补全）。 */
data class ProviderOwner(val id: String)

/** 一次前台 Root 授权尝试的句柄；id 单调分配，用于迟到结果失效判定（§11.4.6）。 */
data class RootAttempt(val id: Long, val owner: ProviderOwner, val source: ProviderSource)

/**
 * 未提交的 Root candidate：root shell 已验证（isRoot=true），但尚未 bind/健康检查/提交。
 * candidate 存在期间 activeProvider 保持原值（§11.3）；bind/健康检查由 Task D5 扩展。
 */
class RootCandidate internal constructor(
    val attemptId: Long,
    val owner: ProviderOwner,
    val observation: RootShellObservation,
)

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
    }
}
