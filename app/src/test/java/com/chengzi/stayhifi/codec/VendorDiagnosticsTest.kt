package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorDiagnosticsTest {

    @Test
    fun musicActiveTrueUsesTrueLogValue() {
        assertEquals("true", VendorDiagnostics.readMusicActive { true })
    }

    @Test
    fun musicActiveFalseUsesFalseLogValue() {
        assertEquals("false", VendorDiagnostics.readMusicActive { false })
    }

    @Test
    fun musicActiveReadFailureIsContained() {
        var callerContinued = false

        val value = VendorDiagnostics.readMusicActive {
            throw RuntimeException("vendor audio read failed")
        }
        callerContinued = true

        assertEquals("unknown", value)
        assertTrue(callerContinued)
    }

    @Test
    fun recognizedVendorLhdcStillNeedsCs1Evidence() {
        // 19 + 192kHz 能力可显示 LHDC，但 cs1 质量档尚未回填，不能因已识别就跳过诊断。
        assertTrue(VendorDiagnostics.shouldLog(0x13, 32))
    }

    @Test
    fun unresolvedVendorKeepsExistingRawDiagnostics() {
        assertTrue(VendorDiagnostics.shouldLog(0x13, 8))
        assertTrue(VendorDiagnostics.shouldLog(99, 0))
    }

    @Test
    fun knownNonVendorCodecsStayQuiet() {
        assertFalse(VendorDiagnostics.shouldLog(10, 32))
        assertFalse(VendorDiagnostics.shouldLog(4, 43))
    }

    // ===== Task 8：identity 门控的实时诊断收敛 =====

    private fun identity(
        nativeType: Int,
        family: CodecFamily,
        displayName: String,
        source: CodecIdentitySource = CodecIdentitySource.SYSTEM_TYPE,
    ) = CodecIdentity(nativeType, family, displayName, source = source)

    private val sbcIdentity = identity(0, CodecFamily.SBC, "SBC")
    private val aacIdentity = identity(2, CodecFamily.AAC, "AAC")
    private val ldacType10Identity = identity(10, CodecFamily.LDAC, "LDAC")
    private val vendorLhdcIdentity = identity(0x13, CodecFamily.LHDC, "LHDC V3")
    private val heuristicLhdcIdentity = identity(
        0x14, CodecFamily.LHDC, "LHDC", CodecIdentitySource.CAPABILITY_HEURISTIC)
    private val unknownIdentity = identity(99, CodecFamily.UNKNOWN, "vendor(99)")

    private fun result(
        status: BtStateParseStatus,
        state: BtStateParser.BtState? = null,
        candidates: List<String> = emptyList(),
    ) = BtStateParseResult(status, state, candidates)

    @Test
    fun standardIdentitiesStayQuietForRealtimeIssues() {
        val noSection = result(BtStateParseStatus.NO_SECTION)
        val noBitrate = result(
            BtStateParseStatus.NO_BITRATE,
            BtStateParser.BtState("LDAC", "ABR", null, false, null),
        )
        for (id in listOf(sbcIdentity, aacIdentity, ldacType10Identity)) {
            assertFalse(VendorDiagnostics.shouldLog(id))
            assertEquals(null, VendorDiagnostics.realtimeIssue(id, noSection))
            assertEquals(null, VendorDiagnostics.realtimeIssue(id, noBitrate))
        }
    }

    @Test
    fun vendorNativeLhdcIdentityKeepsRealtimeDiagnostics() {
        assertTrue(VendorDiagnostics.shouldLog(vendorLhdcIdentity))
        assertTrue(VendorDiagnostics.shouldLog(heuristicLhdcIdentity))
        assertEquals(
            "无匹配段落",
            VendorDiagnostics.realtimeIssue(vendorLhdcIdentity, result(BtStateParseStatus.NO_SECTION)),
        )
        assertEquals(
            "无码率字段",
            VendorDiagnostics.realtimeIssue(
                vendorLhdcIdentity,
                result(BtStateParseStatus.NO_BITRATE, BtStateParser.BtState("LHDC V3", "ABR", null, false, null)),
            ),
        )
    }

    @Test
    fun unknownIdentityKeepsNoSectionAndNoBitrateIssues() {
        assertTrue(VendorDiagnostics.shouldLog(unknownIdentity))
        assertEquals(
            "无匹配段落",
            VendorDiagnostics.realtimeIssue(unknownIdentity, result(BtStateParseStatus.NO_SECTION)),
        )
        assertEquals(
            "无码率字段",
            VendorDiagnostics.realtimeIssue(
                unknownIdentity,
                result(BtStateParseStatus.NO_BITRATE, BtStateParser.BtState("vendor(99)", null, null, false, null)),
            ),
        )
    }

    @Test
    fun notStreamingNeverGeneratesRealtimeIssue() {
        val notStreaming = result(
            BtStateParseStatus.NOT_STREAMING,
            BtStateParser.BtState("LHDC V3", null, null, true, -1L),
        )
        assertEquals(null, VendorDiagnostics.realtimeIssue(vendorLhdcIdentity, notStreaming))
        assertEquals(null, VendorDiagnostics.realtimeIssue(unknownIdentity, notStreaming))

        val available = result(
            BtStateParseStatus.AVAILABLE,
            BtStateParser.BtState("LHDC V3", "ABR", 400, false, 400000L),
        )
        assertEquals(null, VendorDiagnostics.realtimeIssue(vendorLhdcIdentity, available))
    }

    @Test
    fun sampleKindResultMapsFiveLowercaseEvidenceCategories() {
        assertEquals("no-section", VendorDiagnostics.sampleKind(result(BtStateParseStatus.NO_SECTION)))
        assertEquals(
            "ambiguous-section",
            VendorDiagnostics.sampleKind(
                result(BtStateParseStatus.AMBIGUOUS_SECTION, candidates = listOf("LHDC V3", "LHDC V5")),
            ),
        )
        assertEquals(
            "not-streaming",
            VendorDiagnostics.sampleKind(
                result(BtStateParseStatus.NOT_STREAMING, BtStateParser.BtState("LHDC", null, null, true, -1L)),
            ),
        )
        assertEquals(
            "no-bitrate",
            VendorDiagnostics.sampleKind(
                result(BtStateParseStatus.NO_BITRATE, BtStateParser.BtState("LHDC", "ABR", null, false, null)),
            ),
        )
        assertEquals(
            "available",
            VendorDiagnostics.sampleKind(
                result(BtStateParseStatus.AVAILABLE, BtStateParser.BtState("LHDC", "ABR", 400, false, 400000L)),
            ),
        )
    }

    @Test
    fun ambiguousIssueListsAtMostFourCandidateHeaders() {
        val fiveCandidates = (1..5).map { "LHDC V$it" }
        val issue = VendorDiagnostics.realtimeIssue(
            vendorLhdcIdentity,
            result(BtStateParseStatus.AMBIGUOUS_SECTION, candidates = fiveCandidates),
        )
        assertTrue(issue != null)
        for (i in 1..4) assertTrue(issue!!.contains("LHDC V$i"))
        assertFalse(issue!!.contains("LHDC V5"))
    }

    @Test
    fun formatsDumpsysEvidenceWithCs1CorrelationFields() {
        val state = BtStateParser.BtState(
            codec = "LHDC V5", qualityMode = "ABR", bitrateKbps = 400,
            rawBitrate = 400000L,
        )
        assertEquals(
            "段=LHDC V5 qualityMode=ABR rawBitrate=400000 bitrateKbps=400 notStreaming=false",
            VendorDiagnostics.evidence(state),
        )
    }

    @Test
    fun sampleKindClassifiesFourEvidenceStates() {
        assertEquals("no-section", VendorDiagnostics.sampleKind(null))
        assertEquals("not-streaming", VendorDiagnostics.sampleKind(
            BtStateParser.BtState("LHDC", null, null, true, -1L)))
        assertEquals("no-bitrate", VendorDiagnostics.sampleKind(
            BtStateParser.BtState("LHDC", "ABR", null, false, null)))
        assertEquals("available", VendorDiagnostics.sampleKind(
            BtStateParser.BtState("LHDC", "ABR", 400, false, 400000L)))
    }

    @Test
    fun sampleKindRemainsAvailableWhenBitrateAndQualityChange() {
        val abr = BtStateParser.BtState("LHDC", "ABR", 400, false, 400000L)
        val high = BtStateParser.BtState("LHDC", "HIGH", 500, false, 500000L)
        assertEquals("available", VendorDiagnostics.sampleKind(abr))
        assertEquals("available", VendorDiagnostics.sampleKind(high))
    }

    @Test
    fun evidenceUsesEmDashForMissingValues() {
        val state = BtStateParser.BtState("AAC", null, null)
        assertEquals(
            "段=AAC qualityMode=— rawBitrate=— bitrateKbps=— notStreaming=false",
            VendorDiagnostics.evidence(state),
        )
    }

    @Test
    fun dumpCluesPrioritizesA2dpCodecQualityAndBitrateLines() {
        val generic = (1..25).joinToString("\n") { "  State: generic-$it" }
        val dump = generic + "\n" + listOf(
            "  A2DP LHDC V5 State:",
            "    LHDC quality mode : ABR",
            "    LHDC transmission bitrate (Kbps) : 400000",
        ).joinToString("\n")

        val clues = VendorDiagnostics.dumpClues(dump)

        assertEquals("  A2DP LHDC V5 State:", clues[0])
        assertEquals("    LHDC quality mode : ABR", clues[1])
        assertEquals("    LHDC transmission bitrate (Kbps) : 400000", clues[2])
        assertTrue(clues.contains("  State: generic-1"))
    }

    @Test
    fun dumpCluesTrimsOnlyLineEndsAndRemovesBlankAndDuplicateLines() {
        val dump = "\n  A2DP LHDC State:   \n  A2DP LHDC State:\n\t\n" +
            "  quality mode : ABR  \n  quality mode : ABR\n   \n"

        assertEquals(
            listOf("  A2DP LHDC State:", "  quality mode : ABR"),
            VendorDiagnostics.dumpClues(dump),
        )
    }

    @Test
    fun dumpCluesKeepsKeywordAndStateGroupsInStableOrder() {
        val dump = listOf(
            "  State: generic-1",
            "  A2DP LHDC State:",
            "  State: generic-2",
            "  QuAlItY mode : ABR",
            "  CoDeC: LHDC",
            "  State: generic-3",
        ).joinToString("\n")

        assertEquals(
            listOf(
                "  A2DP LHDC State:",
                "  QuAlItY mode : ABR",
                "  CoDeC: LHDC",
                "  State: generic-1",
                "  State: generic-2",
                "  State: generic-3",
            ),
            VendorDiagnostics.dumpClues(dump),
        )
    }

    @Test
    fun dumpCluesLimitsDefaultResultTo24Lines() {
        val dump = (1..30).joinToString("\n") { "State: $it" }

        val clues = VendorDiagnostics.dumpClues(dump)

        assertEquals(24, clues.size)
        assertEquals("State: 1", clues.first())
        assertEquals("State: 24", clues.last())
    }
}
