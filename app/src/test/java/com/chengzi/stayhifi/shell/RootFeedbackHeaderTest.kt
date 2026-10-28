package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.FeedbackLogSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D12：Root 反馈导出集成（设计 §11.6/§12）。
 *  - 切换失败态（selected=root / active=shizuku / BIND_TIMEOUT）在 header 原样可辨，
 *    且 header 含 logSchemaVersion；
 *  - template 只含「Root 实现/版本、su 版本、ROM、Android、SELinux」手填占位，
 *    不自动识别/上报 Root 管理器类型；
 *  - header+template 合并后仍只有一行；最终报告 = 1 行 header + 最近 199 行
 *    （经计划 B [FeedbackLogSanitizer.sanitize]，本任务不另建脱敏器）；
 *  - 导出前 flush 输出 suppressedCount；清空同时 reset Root gate 与 sanitizer。
 */
class RootFeedbackHeaderTest {

    private fun failureSnapshot(
        status: RootStatus = RootStatus.BIND_TIMEOUT,
        granted: Boolean? = true,
        shell: RootShellStatus = RootShellStatus.ROOT_SHELL,
    ) = RootSnapshot(
        status = status,
        granted = granted,
        shellStatus = shell,
        observedReason = "bind timeout after 10000ms",
        reasonHint = null,
    )

    // ─────────────────────────── header 字段 ───────────────────────────

    @Test
    fun headerDistinguishesSelectedActiveAndTransportVerbatim() {
        val header = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.SHIZUKU,
            root = failureSnapshot(),
        )
        assertTrue(header.contains("logSchemaVersion="))
        // 三者原样区分：持久选择 root、当前生效 shizuku、transport 处于 BIND_TIMEOUT
        assertTrue(header.contains("selectedProvider=root"))
        assertTrue(header.contains("activeProvider=shizuku"))
        assertTrue(header.contains("BIND_TIMEOUT"))
        assertTrue(header.contains("needsForegroundRevalidation=false"))
    }

    @Test
    fun headerReflectsNeedsForegroundRevalidation() {
        val header = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.ROOT,
            root = failureSnapshot(status = RootStatus.NEEDS_FOREGROUND_REVALIDATION),
        )
        assertTrue(header.contains("needsForegroundRevalidation=true"))
        assertTrue(header.contains("NEEDS_FOREGROUND_REVALIDATION"))
    }

    @Test
    fun headerRendersNullProvidersBeforeFirstSelectionRead() {
        val header = RootFeedbackFields.header(
            selectedProvider = null,
            activeProvider = null,
            root = failureSnapshot(status = RootStatus.UNVERIFIED, granted = null),
        )
        assertTrue(header.contains("selectedProvider=null"))
        assertTrue(header.contains("activeProvider=null"))
        assertTrue(header.contains("UNVERIFIED"))
    }

    // ─────────────────────────── needsForegroundRevalidation 语义（final review fix） ───────────────────────────

    @Test
    fun coldRootWithActiveRootNeedsRevalidation() {
        val header = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.ROOT,
            root = failureSnapshot(status = RootStatus.UNVERIFIED, granted = null),
        )
        assertTrue("active=root 且 transport 冷启动 UNVERIFIED（非 READY）必须表达需前台重验",
            header.contains("needsForegroundRevalidation=true"))
    }

    @Test
    fun nonReadyRootTransportWithActiveRootNeedsRevalidation() {
        for (status in listOf(
            RootStatus.DISCONNECTED, RootStatus.BIND_TIMEOUT, RootStatus.BIND_FAILED,
            RootStatus.BIND_NULL, RootStatus.GRANTED_NOT_READY,
        )) {
            val header = RootFeedbackFields.header(
                selectedProvider = ProviderKind.ROOT,
                activeProvider = ProviderKind.ROOT,
                root = failureSnapshot(status = status),
            )
            assertTrue("active=root 且 transport=$status 非 READY 必须表达需前台重验",
                header.contains("needsForegroundRevalidation=true"))
        }
    }

    @Test
    fun readyRootTransportDoesNotNeedRevalidation() {
        val header = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.ROOT,
            root = failureSnapshot(status = RootStatus.READY),
        )
        assertTrue("active=root 且 transport READY 不需重验",
            header.contains("needsForegroundRevalidation=false"))
    }

    @Test
    fun activeShizukuNeverNeedsRevalidationEvenWithStaleRootStatus() {
        val header = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.SHIZUKU,
            root = failureSnapshot(status = RootStatus.NEEDS_FOREGROUND_REVALIDATION),
        )
        assertTrue("active=shizuku 时恒为 false（当前通道不依赖 Root transport）",
            header.contains("needsForegroundRevalidation=false"))
    }

    // ─────────────────────────── template 手填项 ───────────────────────────

    @Test
    fun templateContainsOnlyManualFields() {
        val template = RootFeedbackFields.template()
        // Root 实现/版本、su 版本、ROM、Android、SELinux 六个手填占位
        assertTrue(template.contains("rootImpl="))
        assertTrue(template.contains("rootImplVersion="))
        assertTrue(template.contains("suVersion="))
        assertTrue(template.contains("rom="))
        assertTrue(template.contains("android="))
        assertTrue(template.contains("selinux="))
    }

    @Test
    fun templateDoesNotAutoDetectRootManager() {
        val template = RootFeedbackFields.template()
        // 不自动识别管理器：模板不得内置任何管理器名作为已识别值
        assertFalse(template.contains("Magisk"))
        assertFalse(template.contains("KernelSU"))
        assertFalse(template.contains("APatch"))
    }

    // ─────────────────────────── 单行与预算 ───────────────────────────

    @Test
    fun headerPlusTemplateStaysSingleLine() {
        val merged = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.SHIZUKU,
            root = failureSnapshot(),
        ) + " " + RootFeedbackFields.template()
        assertFalse(merged.contains('\n'))
        assertFalse(merged.contains('\r'))
    }

    @Test
    fun reportStaysOneHeaderPlusAtMost199SanitizedLines() {
        // 250 行日志 → 计划 B sanitize 只保留最近 199 行；加 1 行 header 总共 ≤200 行
        val logs = (1..250).map { "[12:00:%02d] 日志行 $it".format(it % 60) }
        val sanitized = FeedbackLogSanitizer().sanitize(logs)
        val logLines = sanitized.split("\n")
        assertEquals(199, logLines.size)
        val header = RootFeedbackFields.header(
            selectedProvider = ProviderKind.ROOT,
            activeProvider = ProviderKind.SHIZUKU,
            root = failureSnapshot(),
        ) + " " + RootFeedbackFields.template()
        val report = "$header\n$sanitized"
        assertEquals(200, report.split("\n").size)
        // 最近 199 行：最早的第 1 行被窗口丢弃
        assertFalse(report.contains("日志行 1 "))
        assertTrue(report.contains("日志行 250"))
    }

    // ─────────────────────────── flush / reset ───────────────────────────

    @Test
    fun flushBeforeExportEmitsSuppressedCount() {
        val out = mutableListOf<String>()
        val logger = RootDiagnosticLogger(sink = { out.add(it) })
        val event = RootLogEvent(
            provider = ProviderKind.ROOT,
            event = RootLogEventKind.ROOT_BIND_END,
            source = ProviderSource.SETTINGS,
            attemptId = "a-1",
            generation = 3L,
            selected = ProviderKind.ROOT,
            active = ProviderKind.SHIZUKU,
            granted = true,
            shell = RootShellStatus.ROOT_SHELL,
            outcome = "bind_timeout",
            errorClass = RootErrorClass.BIND_TIMEOUT,
        )
        // 同键重发两次：第一条待冲刷，第二条只累计 suppressedCount
        logger.log(event)
        logger.log(event)
        assertTrue(out.isEmpty())
        // 导出前 flush：输出一条且带 suppressed=1
        logger.flushSuppressed()
        assertEquals(1, out.size)
        assertTrue(out[0].contains("suppressed=1"))
        assertTrue(out[0].contains("BIND_TIMEOUT"))
    }

    @Test
    fun clearResetsRootGateAndSanitizer() {
        val out = mutableListOf<String>()
        val logger = RootDiagnosticLogger(sink = { out.add(it) })
        logger.log(
            RootLogEvent(
                provider = ProviderKind.ROOT,
                event = RootLogEventKind.ROOT_ATTEMPT_START,
                source = ProviderSource.SETTINGS,
                attemptId = "a-2",
                generation = 1L,
                selected = ProviderKind.ROOT,
                active = ProviderKind.SHIZUKU,
                granted = null,
                shell = RootShellStatus.UNKNOWN,
                outcome = "start",
            )
        )
        // 清空：reset 后 flush 不再输出被 gate 挂起的事件
        logger.reset()
        logger.flushSuppressed()
        assertTrue(out.isEmpty())

        // sanitizer reset 后下一份报告重新从 device1 编号，不沿用旧映射
        val sanitizer = FeedbackLogSanitizer()
        val first = sanitizer.sanitize(listOf("[12:00:01] 已连接: Buds (AA:BB:CC:DD:EE:01)"))
        assertTrue(first.contains("device1"))
        sanitizer.reset()
        val second = sanitizer.sanitize(listOf("[12:00:02] 已连接: Other (AA:BB:CC:DD:EE:02)"))
        assertTrue(second.contains("device1"))
        assertFalse(second.contains("device2"))
    }
}
