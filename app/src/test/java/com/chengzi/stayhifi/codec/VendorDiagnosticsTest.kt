package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
