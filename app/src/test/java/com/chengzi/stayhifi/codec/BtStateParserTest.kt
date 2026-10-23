package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 43：BtStateParser 单测。fixture 全部来自 2026-10-23 真机 dumpsys 取证片段
 * （vivo 栈 / OriginOS 7）：LHDC V5 ABR/400000、LDAC HIGH/-1、AAC Constant。
 */
class BtStateParserTest {

    // LHDC 段：字段名标 "(Kbps)" 但值实为 bps（400000=400kbps），vivo 栈误标实证
    private val lhdcDump = """
        A2DP LHDC V5 State:
          LHDC quality mode            : ABR
          LHDC transmission bitrate (Kbps) :400000
          Packet counts (expected/dropped) : 0 / 8077
    """.trimIndent()

    // LDAC 段：bitrate=-1 表示未在流式传输
    private val ldacDump = """
        A2DP LDAC State:
          LDAC quality mode : HIGH
          LDAC transmission bitrate (Kbps) : -1
    """.trimIndent()

    // AAC 段：没有 transmission bitrate 字段，只有 bitrate mode 文案
    private val aacDump = """
        A2DP AAC State:
          AAC bitrate mode : Constant
    """.trimIndent()

    @Test
    fun parsesLhdcAbrSection() {
        val s = BtStateParser.parse(lhdcDump, "LHDC")!!
        // Task 44：codec 改为段落头精确名（带 V5 后缀），供首页编码器格展示
        assertEquals("LHDC V5", s.codec)
        assertEquals("ABR", s.qualityMode)
        // 400000 bps 归一化为 400 kbps（vivo 字段名误标修正）
        assertEquals(400, s.bitrateKbps)
    }

    @Test
    fun displayShowsAbrSuffix() {
        val s = BtStateParser.parse(lhdcDump, "LHDC")!!
        assertEquals("400k · ABR", BtStateParser.formatDisplay(s))
    }

    @Test
    fun missingSectionReturnsNull() {
        // dump 里没有目标编码器的段落（未连接该编码器/非 vivo 栈）
        assertNull(BtStateParser.parse(aacDump, "LHDC"))
        assertNull(BtStateParser.parse("", "LDAC"))
    }

    @Test
    fun missingBitrateFieldYieldsNullBitrate() {
        // AAC 段无 transmission bitrate → 段落能找到，但码率读不到 → 显示「—」
        // Task 44：字段缺失 ≠ 未流式，notStreaming 必须为 false（空态三分）
        val s = BtStateParser.parse(aacDump, "AAC")!!
        assertNull(s.bitrateKbps)
        assertFalse(s.notStreaming)
        assertNull(BtStateParser.formatDisplay(s))
    }

    @Test
    fun selectsMatchingSectionAmongMany() {
        // 多段落共存时按 codecName 选对（shell 侧过滤后仍保留全部 State: 段头）
        val combined = lhdcDump + "\n" + ldacDump
        val s = BtStateParser.parse(combined, "LDAC")!!
        // Task 44：段名无后缀时精确名与映射名一致
        assertEquals("LDAC", s.codec)
        assertEquals("HIGH", s.qualityMode)
        assertNull(s.bitrateKbps)   // -1 未流式
    }

    @Test
    fun negativeBitrateMeansNotStreaming() {
        // -1 = 未在流式传输：bitrateKbps 为 null，显示「未在播放」而不是 "-1k"
        // Task 44：notStreaming=true 把「栈明确回报未流式」与「栈不暴露」分开
        val s = BtStateParser.parse(ldacDump, "LDAC")!!
        assertNull(s.bitrateKbps)
        assertTrue(s.notStreaming)
        assertNull(BtStateParser.formatDisplay(s))
    }

    @Test
    fun legacySectionNameMatchesSuffixedCodecName() {
        // Task 44：codecName 映射扩充为 "LHDC V5" 后，旧栈段头无后缀
        // （"A2DP LHDC State:"）也要能命中——双向 contains 反向兜底
        val dump = """
            A2DP LHDC State:
              LHDC quality mode : ABR
              LHDC transmission bitrate (Kbps) : 400000
        """.trimIndent()
        val s = BtStateParser.parse(dump, "LHDC V5")!!
        assertEquals("LHDC", s.codec)
        assertEquals(400, s.bitrateKbps)
    }

    @Test
    fun plainKbpsValueNotRescaled() {
        // 小于 10000 的值按 kbps 原样，不做 ÷1000
        val dump = """
            A2DP LDAC State:
              LDAC quality mode : HIGH
              LDAC transmission bitrate (Kbps) : 990
        """.trimIndent()
        val s = BtStateParser.parse(dump, "LDAC")!!
        assertEquals(990, s.bitrateKbps)
    }

    @Test
    fun nonAbrQualityModeShowsPlainBitrate() {
        // quality mode 不含 ABR 时不带后缀，纯码率
        val dump = """
            A2DP LDAC State:
              LDAC quality mode : HIGH
              LDAC transmission bitrate (Kbps) : 990000
        """.trimIndent()
        val s = BtStateParser.parse(dump, "LDAC")!!
        assertEquals("990k", BtStateParser.formatDisplay(s))
    }
}
