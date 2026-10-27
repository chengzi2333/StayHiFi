package com.chengzi.stayhifi.codec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorDiagnosticGateTest {

    private val current = CodecConfig(19, 0, 32, 2, 2, 32773L, 0, 0, 0)

    @Test
    fun recordsEachMacConfigurationAndSampleKindOnce() {
        val gate = VendorDiagnosticGate()
        assertTrue(gate.shouldRecord("mac-a", current, "not-streaming"))
        assertFalse(gate.shouldRecord("mac-a", current, "not-streaming"))
        assertTrue(gate.shouldRecord("mac-a", current, "available"))
        assertFalse(gate.shouldRecord("mac-a", current, "available")) // 400→500 同类不新增
        assertTrue(gate.shouldRecord("mac-b", current, "available"))
        assertTrue(gate.shouldRecord("mac-a", current.copy(sampleRate = 2), "available"))
        gate.reset()
        assertTrue(gate.shouldRecord("mac-a", current, "available"))
    }

    @Test
    fun recordsNoSectionAndNoBitrateOnceForSameMacAndRawConfiguration() {
        val gate = VendorDiagnosticGate()
        assertTrue(gate.shouldRecord("mac-a", current, "no-section"))
        assertFalse(gate.shouldRecord("mac-a", current, "no-section"))
        assertTrue(gate.shouldRecord("mac-a", current, "no-bitrate"))
        assertFalse(gate.shouldRecord("mac-a", current, "no-bitrate"))
    }

    @Test
    fun recordsAmbiguousSectionAsDistinctEvidenceKind() {
        val gate = VendorDiagnosticGate()
        assertTrue(gate.shouldRecord("mac-a", current, "no-section"))
        assertTrue(gate.shouldRecord("mac-a", current, "ambiguous-section"))
        assertFalse(gate.shouldRecord("mac-a", current, "ambiguous-section"))
    }
}
