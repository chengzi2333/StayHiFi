package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.LogStore

/**
 * Task D3：Root 可观察状态模型（设计 §11.4）。
 * 只表达可观察事实：不区分"用户拒绝/没有 su/管理器不兼容"（无法区分时
 * UI 写"未获得 Root 权限（可能被拒绝，或设备没有可用 Root）"），
 * 不出现 DENIED/NO_ROOT 之类猜测分类。
 */
enum class RootStatus {
    /** 每个新 App 进程从未验证开始（DataStore 只存"希望使用的通道"，不是授权证明，§11.3） */
    UNVERIFIED,
    REQUESTING,
    /** isAppGrantedRoot()==true，但 shell/服务尚未就绪 */
    GRANTED_NOT_READY,
    /** 可观察授权为 false（不等于发起授权的结果语义，§11.4.2） */
    NOT_GRANTED,
    SHELL_FAILURE,
    AUTH_TIMEOUT,
    BINDING,
    /** 最低条件：getShell 非空 + isRoot + 非空 binder + dumpDevices 无 error（§11.4.9） */
    READY,
    BIND_NULL,
    BIND_TIMEOUT,
    BIND_FAILED,
    DISCONNECTED,
    /** 冷进程后台路径：无前台已验证 transport，跳过特权操作并引导前台重验（§11.1） */
    NEEDS_FOREGROUND_REVALIDATION,
}

/** getShell 返回后按 shell.isRoot 判定：libsu 默认 shell 可能回落普通 sh（§11.4.1）。 */
enum class RootShellStatus { UNKNOWN, NON_ROOT_SHELL, ROOT_SHELL }

/** 可观察错误分类（事实命名，无猜测语义）。 */
enum class RootErrorClass {
    NONE,
    AUTH_FALSE,
    AUTH_TIMEOUT,
    SHELL_FAILURE,
    BIND_NULL,
    BIND_TIMEOUT,
    BIND_FAILED,
    HEALTH_FAILURE,
    DISCONNECTED,
}

/** 某一时刻的 Root 可观察状态快照（UI 状态卡数据源）。 */
data class RootSnapshot(
    val status: RootStatus,
    val granted: Boolean?,
    val shellStatus: RootShellStatus,
    /** 可观察事实，只写实际发生/读到的内容 */
    val observedReason: String = "",
    /** 推测原因：必须另列，不与事实混写（§11.6 reasonHint） */
    val reasonHint: String? = null,
)

/** 每次 Root 尝试最多 4 条核心日志；provider 切换另记一条终态（§11.6）。 */
enum class RootLogEventKind {
    ROOT_ATTEMPT_START,
    ROOT_AUTH_END,
    ROOT_BIND_END,
    ROOT_HEALTH_END,
    PROVIDER_SWITCH_END,
    /** Task D6：后台路径发现 Root 未就绪——按状态变化去重记录，绝不刷屏也绝不自动切换（§11.4.5/§11.6） */
    BACKGROUND_REVALIDATION_NEEDED,
}

/**
 * 一条 Root 诊断事件（字段对应设计 §11.6 日志行）。
 * [observedReason] 只写事实；[reasonHint] 是推测，渲染时另列 hint= 字段。
 * [rootCause] 渲染前经 [sanitizeRootCause] 清洗（≤3 层、≤240 字符）。
 */
data class RootLogEvent(
    val provider: ProviderKind,
    val event: RootLogEventKind,
    val source: ProviderSource,
    val attemptId: String,
    val generation: Long,
    val selected: ProviderKind?,
    val active: ProviderKind?,
    val granted: Boolean?,
    val shell: RootShellStatus,
    val outcome: String,
    val observedReason: String = "",
    val reasonHint: String? = null,
    val durationMs: Long? = null,
    val timeoutMs: Long? = null,
    val bindCallback: String? = null,
    val healthOp: String? = null,
    val healthResult: String? = null,
    val serviceUid: Int? = null,
    val errorClass: RootErrorClass = RootErrorClass.NONE,
    val rootCause: Throwable? = null,
    val suppressedCount: Int = 0,
)

/**
 * Task D3：Root 诊断日志 gate（设计 §11.6）。
 *
 * 去重键 = provider+event+outcome+generation+errorClass：同键重发只累计
 * suppressedCount、不输出；下一次状态变化（不同键事件到达）或 [flushSuppressed]
 * （导出/复制日志）时才输出，并附上 suppressedCount。后台重复未就绪按状态跃迁记录，
 * 不按轮询/ACL 刷新刷屏。reset 同时清 gate 与计数，不操作持久存储。
 *
 * 只输出，不采集：su stdout/stderr、Root 管理器日志、环境变量一律不进日志（§11.5）。
 * 默认 sink 为 LogStore（200 行环形缓冲）；其线程安全与环形测试由计划 B 负责，
 * 本类自身用同一把锁串行化 gate。
 */
class RootDiagnosticLogger(
    private val sink: (String) -> Unit = { LogStore.log(it) },
) {

    private data class GateKey(
        val provider: ProviderKind,
        val event: RootLogEventKind,
        val outcome: String,
        val generation: Long,
        val errorClass: RootErrorClass,
    )

    private val lock = Any()
    private var pending: Pair<GateKey, RootLogEvent>? = null

    fun log(event: RootLogEvent) {
        val key = GateKey(
            provider = event.provider,
            event = event.event,
            outcome = event.outcome,
            generation = event.generation,
            errorClass = event.errorClass,
        )
        synchronized(lock) {
            val current = pending
            if (current != null && current.first == key) {
                // 同键重发：只累计 suppressedCount，不输出
                pending = current.first to current.second.copy(
                    suppressedCount = current.second.suppressedCount + 1)
                return
            }
            // 状态变化：先冲刷被抑制的上一条
            current?.let { emitLocked(it.second) }
            pending = key to event
        }
    }

    /** 导出/复制日志时调用：输出待冲刷的最后一条（附 suppressedCount）。 */
    fun flushSuppressed() {
        synchronized(lock) {
            pending?.let { emitLocked(it.second) }
            pending = null
        }
    }

    /** 清空日志时重置 gate 与 suppressedCount；不操作持久存储（§11.6）。 */
    fun reset() {
        synchronized(lock) {
            pending = null
        }
    }

    private fun emitLocked(event: RootLogEvent) {
        sink(render(event))
    }

    private fun render(event: RootLogEvent): String = buildString {
        append("provider=").append(event.provider.stored)
        append(" event=").append(event.event.name.lowercase())
        append(" source=").append(event.source.name.lowercase())
        append(" attemptId=").append(event.attemptId)
        append(" generation=").append(event.generation)
        append(" selected=").append(event.selected?.stored ?: "null")
        append(" active=").append(event.active?.stored ?: "null")
        append(" granted=").append(event.granted?.toString() ?: "null")
        append(" shell=").append(event.shell.name)
        append(" outcome=").append(event.outcome)
        append(" reason=\"").append(event.observedReason).append('"')
        if (event.reasonHint != null) {
            append(" hint=\"").append(event.reasonHint).append('"')
        }
        append(" durationMs=").append(event.durationMs ?: "null")
        append(" timeoutMs=").append(event.timeoutMs ?: "null")
        append(" bindCallback=").append(event.bindCallback ?: "null")
        append(" healthOp=").append(event.healthOp ?: "null")
        append(" healthResult=").append(event.healthResult ?: "null")
        append(" serviceUid=").append(event.serviceUid ?: "null")
        append(" errorClass=").append(event.errorClass.name)
        append(" suppressed=").append(event.suppressedCount)
        val cause = event.rootCause?.let { sanitizeRootCause(it) }
        if (cause != null) {
            append(" rootCause=\"").append(cause).append('"')
        }
    }

    companion object {
        private const val MAX_CAUSE_DEPTH = 3
        private const val MAX_CAUSE_CHARS = 240

        /**
         * 异常清洗：最多 [MAX_CAUSE_DEPTH] 层 cause 链、总长 ≤[MAX_CAUSE_CHARS] 字符。
         * 逐层追加并在每层后检查上限，截断处补省略号语义（直接 take，不吞已写层）。
         */
        internal fun sanitizeRootCause(t: Throwable): String {
            val sb = StringBuilder()
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (depth > 0) sb.append(" <= ")
                sb.append(current.javaClass.simpleName).append(": ").append(current.message)
                if (sb.length > MAX_CAUSE_CHARS) break
                current = current.cause
                depth++
            }
            return sb.toString().take(MAX_CAUSE_CHARS)
        }
    }
}
