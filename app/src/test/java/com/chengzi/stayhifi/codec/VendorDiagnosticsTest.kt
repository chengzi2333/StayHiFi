package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorDiagnosticsTest {

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
        )
        assertEquals(
            "段=LHDC V5 qualityMode=ABR bitrateKbps=400 notStreaming=false",
            VendorDiagnostics.evidence(state),
        )
    }
}
