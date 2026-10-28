package com.chengzi.stayhifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task B7：脱敏反馈日志。单次报告内 MAC/设备名稳定映射 deviceN，
 * 跨报告重新编号；只取最近 199 行；空输入返回空串；reset 不保留中间映射。
 */
class FeedbackLogSanitizerTest {

    private val sanitizer = FeedbackLogSanitizer()

    private val macA = "AA:BB:CC:DD:EE:01"
    private val macB = "AA:BB:CC:DD:EE:02"
    private val nameA = "My Buds"
    private val nameB = "Aria Pro"

    private fun linesAFirst() = listOf(
        "[12:00:01] 已连接: $nameA ($macA)，当前 LHDC·96kHz·24bit",
        "[12:00:02] QS 磁贴触发拉满: $nameA ($macA)",
        "[12:00:03] 已保存录制档案: $nameB ($macB) LHDC·192kHz·24bit",
        "[12:00:04] 检测到参数漂移: $nameB ($macB) 目标 LHDC·192kHz·24bit，读回 LHDC·96kHz·24bit",
    )

    private fun linesBFirst() = listOf(
        "[12:01:01] 已连接: $nameB ($macB)，当前 LHDC·192kHz·24bit",
        "[12:01:02] 已连接: $nameA ($macA)，当前 LHDC·96kHz·24bit",
    )

    @Test
    fun macsAndNamesInAllFormatsMapToStableDeviceAliases() {
        val out = sanitizer.sanitize(linesAFirst())

        // A 首次出现 → device1，B → device2；名称与 MAC 同一编号。
        assertTrue(Regex("""已连接: device1 \(device1\)""").containsMatchIn(out))
        assertTrue(out.contains("QS 磁贴触发拉满: device1 (device1)"))
        assertTrue(out.contains("已保存录制档案: device2 (device2)"))
        assertTrue(Regex("""检测到参数漂移: device2 \(device2\)""").containsMatchIn(out))

        // 无原文残留：MAC（含大小写变体）与自定义名都不出现。
        assertFalse(out.contains(macA))
        assertFalse(out.contains(macB))
        assertFalse(out.contains(nameA))
        assertFalse(out.contains(nameB))
    }

    @Test
    fun notificationStyleLineAndExceptionEmbeddedCopiesAreSanitized() {
        // 通知/异常文案可能不带「名称 (MAC)」配对格式；名称关联由同报告其他行发现，
        // 第二遍全局替换同样覆盖这些行（含重复副本）。
        val lines = listOf(
            "[12:00:01] 已连接: $nameA ($macA)，当前 LHDC·96kHz·24bit",
            "[12:00:02] 通知: StayHiFi 已为 $nameA 拉满至 LHDC·96kHz·24bit",
            "[12:00:03] java.lang.IllegalStateException: 设备 $nameA ($macA) 无响应，" +
                "重复副本 $nameA ($macA) 再次失败",
            "[12:00:04] 已保存录制档案: $nameB ($macB) LHDC·192kHz·24bit",
            "[12:00:05] 通知: $nameB 参数已恢复与拉满目标一致",
        )
        val out = sanitizer.sanitize(lines)

        assertTrue(out.contains("通知: StayHiFi 已为 device1 拉满至 LHDC·96kHz·24bit"))
        assertTrue(out.contains("重复副本 device1 (device1) 再次失败"))
        assertTrue(out.contains("通知: device2 参数已恢复与拉满目标一致"))
        assertFalse(out.contains(macA))
        assertFalse(out.contains(macB))
        assertFalse(out.contains(nameA))
        assertFalse(out.contains(nameB))
        // 异常行不残留任何原始名称/MAC（含「设备 My Buds」这种内嵌前缀副本）。
        assertFalse(out.lines()[2].contains("My Buds"))
        assertFalse(out.lines()[2].contains("AA:BB"))
    }

    @Test
    fun macMatchingIsCaseInsensitiveButAliasIsStable() {
        val lower = "aa:bb:cc:dd:ee:01"
        val mixed = "Aa:Bb:Cc:Dd:Ee:02"
        val out = sanitizer.sanitize(
            listOf(
                "[12:00:01] 已连接: $nameA ($lower)",
                "[12:00:02] 检测到参数漂移: $nameB ($mixed)",
            ),
        )
        assertTrue(out.contains("已连接: device1 (device1)"))
        assertTrue(out.contains("检测到参数漂移: device2 (device2)"))
        assertFalse(out.contains(lower, ignoreCase = true))
        assertFalse(out.contains("aa:bb"))
    }

    @Test
    fun duplicateDeviceNameMapsDeterministicallyWithoutLeak() {
        val dup = "Same Buds"
        val out = sanitizer.sanitize(
            listOf(
                "[12:00:01] 已连接: $dup ($macA)",
                "[12:00:02] 检测到参数漂移: $dup ($macB)",
            ),
        )
        // 同名稳定映射同一别名，不泄露原文，也不会随机漂移。
        assertTrue(out.contains("已连接: device1 (device1)"))
        assertTrue(out.contains("检测到参数漂移: device1 (device2)"))
        assertFalse(out.contains(dup))
        assertFalse(out.contains(macA))
        assertFalse(out.contains(macB))
    }

    @Test
    fun hostileNamesWithRegexCharsQuotesBackslashJsonEscapeAndMacSubstringLeaveNoResidue() {
        // 含正则元字符、引号、反斜杠，且内嵌一个 MAC 子串。
        val hostile = """Buds [x] (y) {z} .* + ? ^ $ | \ "quoted" \n\t AA:BB:CC:DD:EE:09 tail"""
        val macC = "AA:BB:CC:DD:EE:03"
        // JSON 字符串不转义 $；字面与转义两种形式都要无痕替换。
        val jsonEscaped = """Buds [x] (y) {z} .* + ? ^ $ | \\ \"quoted\" \\n\\t AA:BB:CC:DD:EE:09 tail"""
        val out = sanitizer.sanitize(
            listOf(
                "[12:00:01] 已连接: $hostile ($macC)",
                "[12:00:02] 建档: {\"deviceName\":\"$jsonEscaped\",\"mac\":\"$macC\"}",
            ),
        )
        assertFalse(out.contains("quoted"))
        assertFalse(out.contains("AA:BB:CC:DD:EE:09"))
        assertFalse(out.contains(macC))
        assertFalse(out.contains(hostile))
        // 名称在发现阶段先命中内嵌 MAC 子串（编号 1），行尾配对 MAC 为 device2；
        // 两处副本（字面与 JSON 转义）都只剩别名，无原文残留。
        assertTrue(out.lines()[0].contains("已连接: device2 (device2)"))
        assertTrue(out.lines()[1].contains("\"deviceName\":\"device2\""))
        assertTrue(out.lines()[1].contains("\"mac\":\"device2\""))
    }

    @Test
    fun aliasesAreStableWithinReportAndResetBetweenReports() {
        val first = sanitizer.sanitize(linesAFirst())
        assertTrue(Regex("device1").findAll(first).count() >= 1)
        assertTrue(first.contains("device1"))
        assertTrue(first.contains("device2"))
        assertFalse(first.contains(macA))
        assertFalse(first.contains(nameA))
        val second = sanitizer.sanitize(linesBFirst())
        // 新报告按首次出现重新编号，B 成为 device1；不沿用上一报告映射。
        assertTrue(second.contains("已连接: device1 (device1)"))
        assertTrue(second.contains("已连接: device2 (device2)"))
        assertFalse(second.contains("device3"))
    }

    @Test
    fun resetClearsMappingsAndEmptyInputReturnsEmptyString() {
        assertEquals("", sanitizer.sanitize(emptyList()))
        sanitizer.sanitize(linesAFirst())
        sanitizer.reset()
        val out = sanitizer.sanitize(linesBFirst())
        // reset 后不保留中间映射：B 重新从 device1 开始编号。
        assertTrue(out.contains("已连接: device1 (device1)"))
        assertTrue(out.contains("已连接: device2 (device2)"))
        assertFalse(out.contains("device3"))
    }

    @Test
    fun feedbackUsesMostRecent199LinesAndKeepsSuppressedCount() {
        val lines = (1..250).map { "line-$it" }.toMutableList()
        lines[99] = "line-100 VendorStackDiag v=1 seq=9 suppressedCount=37 mac=11:22:33:44:55:66"
        val output = sanitizer.sanitize(lines)
        assertEquals(199, output.lines().size)
        assertTrue(output.startsWith("line-52"))
        assertTrue(output.endsWith("line-250"))
        // 含 suppressedCount=37 的原始行仍保留该字段与数值。
        assertTrue(output.contains("suppressedCount=37"))
    }

    @Test
    fun unknownMacWithoutNameStillGetsAliasAndMacFieldIsReplaced() {
        val out = sanitizer.sanitize(
            listOf(
                "[12:00:01] VendorStackDiag v=1 seq=1 mac=$macA suppressedCount=0",
            ),
        )
        assertTrue(out.contains("mac=device1"))
        assertFalse(out.contains(macA))
    }
}
