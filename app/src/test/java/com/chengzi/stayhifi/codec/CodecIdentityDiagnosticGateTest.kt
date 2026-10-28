package com.chengzi.stayhifi.codec

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CodecIdentityDiagnosticGateTest {

    private val current = CodecDeviceFixtures.iqoo15Golden
    private val systemV3 = CodecIdentity(
        nativeType = 10,
        family = CodecFamily.LHDC,
        displayName = "LHDC V3",
        codecId = 0x11L,
        source = CodecIdentitySource.SYSTEM_TYPE,
    )

    @Before
    fun resetGate() {
        CodecIdentityDiagnosticGate.reset()
    }

    @Test
    fun sameConflictIsSharedAcrossCallersAndIdentityChangeOrResetAllowsNewRecord() {
        assertTrue(CodecIdentityDiagnosticGate.shouldRecord("mac-a", current, systemV3))
        assertFalse(CodecIdentityDiagnosticGate.shouldRecord("mac-a", current, systemV3))
        assertFalse(CodecIdentityDiagnosticGate.shouldRecord("mac-a", current, systemV3))

        assertTrue(CodecIdentityDiagnosticGate.shouldRecord("mac-b", current, systemV3))
        assertTrue(CodecIdentityDiagnosticGate.shouldRecord("mac-a", current.copy(sampleRate = 8), systemV3))
        assertTrue(
            CodecIdentityDiagnosticGate.shouldRecord(
                "mac-a",
                current,
                systemV3.copy(displayName = "LHDC V5"),
            ),
        )

        CodecIdentityDiagnosticGate.reset()
        assertTrue(CodecIdentityDiagnosticGate.shouldRecord("mac-a", current, systemV3))
    }
}
