package com.chengzi.stayhifi.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D3：Root 可观察状态、错误分类与分阶段去重日志（设计 §11.4/§11.6）。
 *  - granted 三态（true/false/null）、shellStatus、observedReason 与 reasonHint 分字段渲染；
 *  - 同一 provider+event+outcome+generation+errorClass 重发只累计 suppressedCount，
 *    状态变化或 flushSuppressed/reset 才输出；
 *  - 每次 attempt 仅四条核心日志 + 切换一条终态日志；
 *  - 异常链最多 3 层、总长 ≤240 字符；无 DENIED/NO_ROOT 猜测分类。
 */
class RootDiagnosticsTest {

    /** 200 行内存环形 sink（模拟 LogStore 容量；线程安全由 LogStore 负责，见计划 B）。 */
    private class RingSink(private val capacity: Int) : (String) -> Unit {
        private val lines = ArrayDeque<String>()
        override fun invoke(line: String) {
            lines.addLast(line)
            while (lines.size > capacity) lines.removeFirst()
        }
        fun snapshot(): List<String> = lines.toList()
    }

    private fun event(
        kind: RootLogEventKind = RootLogEventKind.ROOT_ATTEMPT_START,
        outcome: String = "start",
        granted: Boolean? = null,
        shell: RootShellStatus = RootShellStatus.UNKNOWN,
        reason: String = "",
        hint: String? = null,
        generation: Long = 0L,
        errorClass: RootErrorClass = RootErrorClass.NONE,
        rootCause: Throwable? = null,
        bindCallback: String? = null,
        healthResult: String? = null,
        attemptId: String = "a-1",
    ) = RootLogEvent(
        provider = ProviderKind.ROOT,
        event = kind,
        source = ProviderSource.ONBOARDING,
        attemptId = attemptId,
        generation = generation,
        selected = ProviderKind.ROOT,
        active = ProviderKind.SHIZUKU,
        granted = granted,
        shell = shell,
        outcome = outcome,
        observedReason = reason,
        reasonHint = hint,
        durationMs = null,
        timeoutMs = null,
        bindCallback = bindCallback,
        healthOp = null,
        healthResult = healthResult,
        serviceUid = null,
        errorClass = errorClass,
        rootCause = rootCause,
    )

    // ─────────────────────────── 三态与分字段 ───────────────────────────

    @Test
    fun grantedTriStateRenderedVerbatim() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event(granted = null, attemptId = "a-null"))
        logger.log(event(granted = true, attemptId = "a-true", outcome = "ok"))
        logger.log(event(granted = false, attemptId = "a-false", outcome = "auth_false"))
        logger.flushSuppressed()
        val lines = sink.snapshot()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("granted=null"))
        assertTrue(lines[1].contains("granted=true"))
        assertTrue(lines[2].contains("granted=false"))
    }

    @Test
    fun shellStatusRendered() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event(shell = RootShellStatus.ROOT_SHELL, attemptId = "a1"))
        logger.log(event(shell = RootShellStatus.NON_ROOT_SHELL, attemptId = "a2", outcome = "shell_fallback"))
        logger.log(event(shell = RootShellStatus.UNKNOWN, attemptId = "a3", outcome = "unverified"))
        logger.flushSuppressed()
        val lines = sink.snapshot()
        assertTrue(lines[0].contains("shell=ROOT_SHELL"))
        assertTrue(lines[1].contains("shell=NON_ROOT_SHELL"))
        assertTrue(lines[2].contains("shell=UNKNOWN"))
    }

    @Test
    fun observedReasonAndHintAreSeparateFields() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event(
            outcome = "not_granted",
            reason = "isAppGrantedRoot()=false",
            hint = "可能被拒绝，或设备没有可用 Root",
        ))
        logger.flushSuppressed()
        val line = sink.snapshot().single()
        assertTrue("事实与推测必须分字段", line.contains("reason=\"isAppGrantedRoot()=false\""))
        assertTrue("推测原因必须另列 hint", line.contains("hint=\"可能被拒绝，或设备没有可用 Root\""))
    }

    @Test
    fun noDeniedOrNoRootGuessClassification() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event(outcome = "not_granted", reason = "isAppGrantedRoot()=false"))
        logger.flushSuppressed()
        val line = sink.snapshot().single()
        assertFalse("不得出现 DENIED 猜测分类", line.uppercase().contains("DENIED"))
        assertFalse("不得出现 NO_ROOT 猜测分类", line.uppercase().contains("NO_ROOT"))
    }

    // ─────────────────────────── 去重 gate ───────────────────────────

    @Test
    fun duplicateEventOnlyIncrementsSuppressedCount() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        repeat(3) { logger.log(event()) }   // 同 key 重发 3 次
        assertTrue("重发不得输出", sink.snapshot().isEmpty())
        logger.log(event(outcome = "next"))  // 状态变化 → 冲刷上一条
        val flushed = sink.snapshot()
        assertEquals(1, flushed.size)
        assertTrue(" suppressedCount=2（两次重发）", flushed.single().contains("suppressed=2"))
        assertTrue(flushed.single().contains("outcome=start"))
    }

    @Test
    fun sameKeyDifferentGenerationIsNewEvent() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event(generation = 0, attemptId = "g0"))
        logger.log(event(generation = 1, attemptId = "g1"))  // generation 不同 → 新 key
        logger.flushSuppressed()
        assertEquals(2, sink.snapshot().size)
    }

    @Test
    fun flushSuppressedOutputsPendingOnce() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event())
        logger.log(event())
        logger.flushSuppressed()
        logger.flushSuppressed()   // 第二次 flush 无 pending
        val lines = sink.snapshot()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("suppressed=1"))
    }

    @Test
    fun resetClearsGateAndCounts() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        logger.log(event())
        logger.log(event())
        logger.reset()
        logger.flushSuppressed()
        assertTrue("reset 后不得再冲刷旧 pending", sink.snapshot().isEmpty())
        logger.log(event())   // gate 已清：同 key 重新计为首个
        logger.flushSuppressed()
        val lines = sink.snapshot()
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("suppressed=0"))
    }

    // ─────────────────────────── 分阶段日志 ───────────────────────────

    @Test
    fun oneAttemptEmitsExactlyFourCoreEventsPlusOneSwitch() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        val g = 0L
        logger.log(event(RootLogEventKind.ROOT_ATTEMPT_START, outcome = "start", generation = g, attemptId = "at-1"))
        logger.log(event(RootLogEventKind.ROOT_AUTH_END, outcome = "granted", granted = true, generation = g, attemptId = "at-1"))
        logger.log(event(RootLogEventKind.ROOT_BIND_END, outcome = "connected", generation = g, attemptId = "at-1", bindCallback = "connected"))
        logger.log(event(RootLogEventKind.ROOT_HEALTH_END, outcome = "healthy", generation = g, attemptId = "at-1", healthResult = "ok"))
        logger.log(event(RootLogEventKind.PROVIDER_SWITCH_END, outcome = "committed", generation = g, attemptId = "at-1"))
        logger.flushSuppressed()
        val lines = sink.snapshot()
        assertEquals("一次 attempt 4 条核心 + 1 条切换终态，flush 全部输出", 5, lines.size)
        assertTrue(lines[0].contains("event=root_attempt_start"))
        assertTrue(lines[1].contains("event=root_auth_end"))
        assertTrue(lines[2].contains("event=root_bind_end"))
        assertTrue(lines[3].contains("event=root_health_end"))
        assertTrue(lines[4].contains("event=provider_switch_end"))
    }

    // ─────────────────────────── 异常清洗 ───────────────────────────

    @Test
    fun rootCauseLimitedToThreeLevels() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        val deep = Exception("L5", RuntimeException("L4", IllegalStateException("L3",
            IllegalArgumentException("L2", NullPointerException("L1")))))
        logger.log(event(outcome = "failed", errorClass = RootErrorClass.SHELL_FAILURE, rootCause = deep))
        logger.flushSuppressed()
        val line = sink.snapshot().single()
        val cause = line.substringAfter("rootCause=\"").substringBeforeLast("\"")
        assertEquals("最多 3 层", 3, cause.split(" <= ").size)
        assertTrue(cause.contains("L5"))
        assertTrue(cause.contains("L3"))
        assertFalse(cause.contains("L2"))
    }

    @Test
    fun rootCauseTruncatedTo240Chars() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        val longMsg = "x".repeat(500)
        logger.log(event(outcome = "failed", errorClass = RootErrorClass.SHELL_FAILURE,
            rootCause = Exception(longMsg)))
        logger.flushSuppressed()
        val line = sink.snapshot().single()
        val cause = line.substringAfter("rootCause=\"").substringBeforeLast("\"")
        assertTrue("异常清洗 ≤240 字符（实际 ${cause.length}）", cause.length <= 240)
    }

    // ─────────────────────────── 环形 sink 预算 ───────────────────────────

    @Test
    fun ringSinkKeepsLast200Of250DistinctEvents() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        repeat(250) { i ->
            logger.log(event(outcome = "e$i", attemptId = "a-$i"))
        }
        logger.flushSuppressed()
        val lines = sink.snapshot()
        assertEquals(200, lines.size)
        assertTrue("保留最近 200 行：首行是第 51 个事件", lines.first().contains("outcome=e50"))
        assertTrue("末行是第 250 个事件", lines.last().contains("outcome=e249"))
    }

    @Test
    fun duplicatesAggregateViaSuppressedCount() {
        val sink = RingSink(200)
        val logger = RootDiagnosticLogger(sink = sink)
        // 100 个事件各重发 9 次：去重后仅 100 行，行数不随重发膨胀
        repeat(100) { i ->
            repeat(10) {
                logger.log(event(outcome = "burst-$i", attemptId = "b-$i", generation = i.toLong()))
            }
        }
        logger.flushSuppressed()
        val lines = sink.snapshot()
        assertEquals(100, lines.size)
        assertTrue("每个事件 suppressed=9", lines.all { it.contains("suppressed=9") })
    }
}
