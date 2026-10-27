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

    private val unknownBitrateDump = """
        A2DP AAC State:
          AAC transmission bitrate (Kbps) : unknown
    """.trimIndent()

    @Test
    fun parsesLhdcAbrSection() {
        val s = BtStateParser.parse(lhdcDump, "LHDC")!!
        // Task 44：codec 改为段落头精确名（带 V5 后缀），供首页编码器格展示
        assertEquals("LHDC V5", s.codec)
        assertEquals("ABR", s.qualityMode)
        // 400000 bps 归一化为 400 kbps（vivo 字段名误标修正）
        assertEquals(400000L, s.rawBitrate)
        assertEquals(400, s.bitrateKbps)
    }

    @Test
    fun parsesCapabilityBackedColorOsLhdcSection() {
        // ColorOS 19 只有经 selectable 高采样率佐证后才认 LHDC；
        // 若解析仍走单参 Masks.codecName(19)=vendor(19)，这里会找不到段落。
        val current = CodecConfig(0x13, 0, 32, 2, 2, 0L, 0L, 0L, 0L)
        val selectable = listOf(CodecConfig(0x13, 0, 32, 3, 2, 0L, 0L, 0L, 0L))
        val s = BtStateParser.parse(lhdcDump, current, selectable)!!
        assertEquals("LHDC V5", s.codec)
        assertEquals("ABR", s.qualityMode)
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
        assertEquals(null, s.rawBitrate)
        assertNull(s.bitrateKbps)
        assertFalse(s.notStreaming)
        assertNull(BtStateParser.formatDisplay(s))
    }

    @Test
    fun nonNumericBitrateYieldsNullWithoutMarkingNotStreaming() {
        val s = BtStateParser.parse(unknownBitrateDump, "AAC")!!
        assertEquals(null, s.rawBitrate)
        assertEquals(null, s.bitrateKbps)
        assertFalse(s.notStreaming)
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
        assertEquals(-1L, s.rawBitrate)
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

    // ── Task 6：先拆段再裁决，禁止 LHDC 版本段之间拼字段 ──

    private fun identity(name: String) = CodecIdentity(
        nativeType = 18,
        family = codecFamilyOf(name),
        displayName = name,
        source = CodecIdentitySource.SYSTEM_TYPE,
    )

    private val opdDump = CodecDeviceFixtures.opdDumpV3 + "\n" + """
        A2DP LHDC V5 State:
          ${CodecDeviceFixtures.opdDumpV5Empty}
    """.trimIndent()

    @Test
    fun exactLhdcV3ReturnsOnlyOpdV3State() {
        val result = BtStateParser.parseResult(opdDump, identity("LHDC V3"))

        assertEquals(BtStateParseStatus.AVAILABLE, result.status)
        assertEquals("LHDC V3", result.state?.codec)
        assertEquals("MID", result.state?.qualityMode)
        assertEquals(500000L, result.state?.rawBitrate)
        assertEquals(500, result.state?.bitrateKbps)
    }

    @Test
    fun genericLhdcSelectsOnlyAvailableOpdSectionWithoutV5Leak() {
        val result = BtStateParser.parseResult(opdDump, identity("LHDC"))

        assertEquals(BtStateParseStatus.AVAILABLE, result.status)
        assertEquals("LHDC V3", result.state?.codec)
        assertEquals("MID", result.state?.qualityMode)
        assertEquals(500000L, result.state?.rawBitrate)
        assertEquals(500, result.state?.bitrateKbps)
    }

    @Test
    fun genericLhdcWithTwoAvailableSectionsIsAmbiguous() {
        val dump = CodecDeviceFixtures.opdDumpV3 + "\n" + """
            A2DP LHDC V5 State:
              LHDC quality mode : HIGH
              LHDC transmission bitrate (Kbps) : 900000
        """.trimIndent()

        val result = BtStateParser.parseResult(dump, identity("LHDC"))

        assertEquals(BtStateParseStatus.AMBIGUOUS_SECTION, result.status)
        assertNull(result.state)
        assertEquals(listOf("LHDC V3", "LHDC V5"), result.candidateSections)
    }

    @Test
    fun genericLhdcWithMultipleInvalidBitrateSectionsIsAmbiguousAndCapsHeaders() {
        val dump = (1..5).joinToString("\n") { version ->
            "A2DP LHDC V$version State:\n  {}"
        }

        val result = BtStateParser.parseResult(dump, identity("LHDC"))

        assertEquals(BtStateParseStatus.AMBIGUOUS_SECTION, result.status)
        assertNull(result.state)
        assertEquals(
            listOf("LHDC V1", "LHDC V2", "LHDC V3", "LHDC V4"),
            result.candidateSections,
        )
    }

    @Test
    fun exactLhdcV3DoesNotBorrowAvailableV5Fields() {
        val dump = """
            A2DP LHDC V5 State:
              LHDC quality mode : HIGH
              LHDC transmission bitrate (Kbps) : 900000
        """.trimIndent()

        val result = BtStateParser.parseResult(dump, identity("LHDC V3"))

        assertEquals(BtStateParseStatus.NO_SECTION, result.status)
        assertNull(result.state)
    }

    @Test
    fun exactLhdcV3AcceptsUniqueUnversionedLegacySection() {
        val dump = """
            A2DP LHDC State:
              LHDC quality mode : ABR
              LHDC transmission bitrate (Kbps) : 400000
        """.trimIndent()

        val result = BtStateParser.parseResult(dump, identity("LHDC V3"))

        assertEquals(BtStateParseStatus.AVAILABLE, result.status)
        assertEquals("LHDC", result.state?.codec)
        assertEquals("ABR", result.state?.qualityMode)
        assertEquals(400, result.state?.bitrateKbps)
    }

    @Test
    fun conflictingDuplicateExactSectionsAreAmbiguous() {
        val dump = CodecDeviceFixtures.opdDumpV3 + "\n" + """
            A2DP LHDC V3 State:
              LHDC quality mode : HIGH
              LHDC transmission bitrate (Kbps) : 900000
        """.trimIndent()

        val result = BtStateParser.parseResult(dump, identity("LHDC V3"))

        assertEquals(BtStateParseStatus.AMBIGUOUS_SECTION, result.status)
        assertNull(result.state)
        assertEquals(listOf("LHDC V3", "LHDC V3"), result.candidateSections)
    }

    @Test
    fun overflowingBitrateReturnsNoBitrateInsteadOfNegativeKbps() {
        val dump = """
            A2DP LDAC State:
              LDAC transmission bitrate (Kbps) : ${Long.MAX_VALUE}
        """.trimIndent()

        val result = BtStateParser.parseResult(dump, identity("LDAC"))

        assertEquals(BtStateParseStatus.NO_BITRATE, result.status)
        assertEquals(Long.MAX_VALUE, result.state?.rawBitrate)
        assertNull(result.state?.bitrateKbps)
        assertFalse(result.state!!.notStreaming)
    }

    // ── Task 46：isMusicActive 驱动的实际传输三态决策（不再依赖 PCM 两轮增量） ──
    // ── Task 7：升级 RealtimeDisplay 四态枚举；旧 state 参数 overload 保留但走同一裁决 ──

    @Test
    fun realtimeDisplayWhenMusicInactiveImmediatelyShowsNotPlaying() {
        // 粘性 400k 即使仍由栈返回，未播放时也必须首轮直接隐藏，避免闪旧值
        val sticky = BtStateParser.parse(lhdcDump, "LHDC")!!
        val display = BtStateParser.realtimeDisplay(isMusicActive = false, state = sticky)
        assertEquals(RealtimeDisplay.NOT_PLAYING, display.display)
        assertTrue(display.notStreaming)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
    }

    @Test
    fun realtimeDisplayWhenMusicActiveShowsAvailableBitrate() {
        val state = BtStateParser.parse(lhdcDump, "LHDC")!!
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, state = state)
        assertEquals(RealtimeDisplay.AVAILABLE, display.display)
        assertFalse(display.notStreaming)
        assertEquals(400, display.bitrateKbps)
        assertEquals("ABR", display.qualityMode)
    }

    @Test
    fun realtimeDisplayWhenMusicActiveButStackUnavailableShowsNoData() {
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, state = null)
        assertEquals(RealtimeDisplay.NO_DATA, display.display)
        assertFalse(display.notStreaming)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
    }

    @Test
    fun realtimeDisplayWhenMusicActiveButStackReportsNegativeShowsStackReportsIdle() {
        // Task 7：LDAC -1 旧预期由「未在播放」改为「栈回报空闲」——播放中但栈回报 -1
        // 不能下「未在播放」结论（与 isMusicActive 即时判定矛盾），只能说暂无法确认。
        val result = BtStateParser.parseResult(ldacDump, identity("LDAC"))
        assertEquals(BtStateParseStatus.NOT_STREAMING, result.status)
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = result)
        assertEquals(RealtimeDisplay.STACK_REPORTS_IDLE, display.display)
        assertFalse(display.notStreaming)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
        assertEquals("暂无法确认", RealtimeDisplayFormatter.text(display))
    }

    // ── Task 7：PLK110 实证——播放中 LHDC V3 段回报 raw=-1（栈回报 idle） ──

    @Test
    fun plkExactV3ParsesAsNotStreaming() {
        // PLK110 实况事实：V3 段播放中原始码率 -1 → 精确解析必须为 NOT_STREAMING
        val result = BtStateParser.parseResult(CodecDeviceFixtures.plkDumpV3, identity("LHDC V3"))

        assertEquals(BtStateParseStatus.NOT_STREAMING, result.status)
        assertEquals(CodecDeviceFixtures.plkV3RawBitrate, result.state?.rawBitrate)
        assertNull(result.state?.bitrateKbps)
        assertTrue(result.state!!.notStreaming)
    }

    @Test
    fun plkMusicActiveWithNegativeRawShowsStackReportsIdle() {
        // isMusicActive=true + raw=-1 → 暂无法确认，绝不显示「未在播放」
        val result = BtStateParser.parseResult(CodecDeviceFixtures.plkDumpV3, identity("LHDC V3"))
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = result)

        assertEquals(RealtimeDisplay.STACK_REPORTS_IDLE, display.display)
        assertEquals("暂无法确认", RealtimeDisplayFormatter.text(display))
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
    }

    @Test
    fun plkMusicInactiveShowsNotPlaying() {
        // isMusicActive=false 仍是「未在播放」主判定，不被栈 -1 干扰
        val result = BtStateParser.parseResult(CodecDeviceFixtures.plkDumpV3, identity("LHDC V3"))
        val display = BtStateParser.realtimeDisplay(isMusicActive = false, result = result)

        assertEquals(RealtimeDisplay.NOT_PLAYING, display.display)
        assertEquals("未在播放", RealtimeDisplayFormatter.text(display))
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
    }

    @Test
    fun musicActiveWithPositiveBitrateShowsAvailableKeepingQualityMode() {
        // 正码率 → AVAILABLE，ABR/码率原样保留
        val result = BtStateParser.parseResult(CodecDeviceFixtures.opdDumpV3, identity("LHDC V3"))
        assertEquals(BtStateParseStatus.AVAILABLE, result.status)
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = result)

        assertEquals(RealtimeDisplay.AVAILABLE, display.display)
        assertEquals(500, display.bitrateKbps)
        assertEquals("MID", display.qualityMode)
        assertEquals("500 kbps · MID", RealtimeDisplayFormatter.text(display))
    }

    @Test
    fun noSectionAmbiguousAndNoBitrateAllMapToNoData() {
        // 抓取不可用的三种裁决（无段/歧义/无码率）统一映射 NO_DATA，由文案层区分解释
        val noSection = BtStateParser.parseResult("", identity("LHDC V3"))
        assertEquals(BtStateParseStatus.NO_SECTION, noSection.status)
        assertEquals(
            RealtimeDisplay.NO_DATA,
            BtStateParser.realtimeDisplay(isMusicActive = true, result = noSection).display,
        )

        val ambiguousDump = CodecDeviceFixtures.opdDumpV3 + "\n" + """
            A2DP LHDC V5 State:
              LHDC quality mode : HIGH
              LHDC transmission bitrate (Kbps) : 900000
        """.trimIndent()
        val ambiguous = BtStateParser.parseResult(ambiguousDump, identity("LHDC"))
        assertEquals(BtStateParseStatus.AMBIGUOUS_SECTION, ambiguous.status)
        assertEquals(
            RealtimeDisplay.NO_DATA,
            BtStateParser.realtimeDisplay(isMusicActive = true, result = ambiguous).display,
        )

        val noBitrate = BtStateParser.parseResult(aacDump, identity("AAC"))
        assertEquals(BtStateParseStatus.NO_BITRATE, noBitrate.status)
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = noBitrate)
        assertEquals(RealtimeDisplay.NO_DATA, display.display)
        assertEquals("—（本机暂不支持抓取）", RealtimeDisplayFormatter.text(display))
    }

    @Test
    fun stackReportsIdleStateDoesNotCarryBitrateOrQuality() {
        // 仅 AVAILABLE 携带码率/quality；STACK_REPORTS_IDLE 两字段必须为 null
        val result = BtStateParser.parseResult(CodecDeviceFixtures.plkDumpV3, identity("LHDC V3"))
        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = result)

        assertEquals(RealtimeDisplay.STACK_REPORTS_IDLE, display.display)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
    }

    @Test
    fun genericIdentityRefinesToUniqueVersionedSectionName() {
        val generic = identity("LHDC")
        val result = BtStateParser.parseResult(CodecDeviceFixtures.opdDumpV3, generic)

        assertEquals("LHDC V3", BtStateParser.presentedCodecName(generic, result))
    }

    @Test
    fun versionedIdentityNeverDowngradesThroughLegacySectionName() {
        val versioned = identity("LHDC V3")
        val legacyDump = """
            A2DP LHDC State:
              LHDC quality mode : ABR
              LHDC transmission bitrate (Kbps) : 400000
        """.trimIndent()
        val result = BtStateParser.parseResult(legacyDump, versioned)

        assertEquals("LHDC V3", BtStateParser.presentedCodecName(versioned, result))
    }

    @Test
    fun unresolvedOrAmbiguousResultsKeepPresentationName() {
        val generic = identity("LHDC")
        val noSection = BtStateParser.parseResult("", generic)
        val ambiguousDump = CodecDeviceFixtures.opdDumpV3 + "\n" + """
            A2DP LHDC V5 State:
              LHDC transmission bitrate (Kbps) : 900000
        """.trimIndent()
        val ambiguous = BtStateParser.parseResult(ambiguousDump, generic)

        assertEquals("LHDC", BtStateParser.presentedCodecName(generic, noSection))
        assertEquals("LHDC", BtStateParser.presentedCodecName(generic, ambiguous))
    }

    @Test
    fun syntheticAvailableWithZeroBitrateShowsNoDataAndNoFormattedBitrate() {
        val state = BtStateParser.BtState("LDAC", "HIGH", bitrateKbps = 0)
        val result = BtStateParseResult(BtStateParseStatus.AVAILABLE, state)

        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = result)

        assertEquals(RealtimeDisplay.NO_DATA, display.display)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
        assertNull(BtStateParser.formatDisplay(state))
    }

    @Test
    fun syntheticAvailableWithNegativeIdleBitrateShowsStackReportsIdle() {
        val state = BtStateParser.BtState(
            "LDAC", "HIGH", bitrateKbps = -1, notStreaming = true, rawBitrate = -1L,
        )
        val result = BtStateParseResult(BtStateParseStatus.AVAILABLE, state)

        val display = BtStateParser.realtimeDisplay(isMusicActive = true, result = result)

        assertEquals(RealtimeDisplay.STACK_REPORTS_IDLE, display.display)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
        assertNull(BtStateParser.formatDisplay(state))
    }

    @Test
    fun legacyStateOverloadKeepsNotStreamingPrecedence() {
        val state = BtStateParser.BtState(
            "LHDC", "ABR", bitrateKbps = 400, notStreaming = true, rawBitrate = -1L,
        )

        val display = BtStateParser.realtimeDisplay(isMusicActive = true, state = state)

        assertEquals(RealtimeDisplay.STACK_REPORTS_IDLE, display.display)
        assertNull(display.bitrateKbps)
        assertNull(display.qualityMode)
    }
}
