package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecSnapshotPresenterTest {

    @Test
    fun opdSnapshotUsesResolvedSystemIdentityAndSharedSummary() {
        val snapshot = CodecStateSnapshot(
            current = CodecDeviceFixtures.opdCurrent,
            selectable = listOf(
                CodecDeviceFixtures.selectableCaps(
                    CodecDeviceFixtures.opdType,
                    CodecDeviceFixtures.opdCapsMask,
                ),
            ),
            supportedTypes = listOf(
                SupportedCodecType(CodecDeviceFixtures.opdType, 0x11L, "LHDC V3"),
            ),
        )

        val presentation = CodecSnapshotPresenter.present(snapshot)!!

        assertEquals(CodecDeviceFixtures.opdCurrent, presentation.current)
        assertEquals(
            CodecIdentity(
                nativeType = 18,
                family = CodecFamily.LHDC,
                displayName = "LHDC V3",
                codecId = 0x11L,
                source = CodecIdentitySource.SYSTEM_TYPE,
            ),
            presentation.identity,
        )
        assertEquals("LHDC V3·96kHz·24bit·500kbps", presentation.summary.text())
        assertEquals(emptySet<CodecIdentityIssue>(), presentation.issues)
    }

    @Test
    fun nullCurrentReturnsNullWithoutFakeIdentity() {
        val presentation = CodecSnapshotPresenter.present(CodecStateSnapshot(current = null))

        assertNull(presentation)
    }

    @Test
    fun localOnly192CapabilityCannotCreateLhdcIdentityOrOverride96Summary() {
        val current = CodecDeviceFixtures.unverified(
            codecType = CodecDeviceFixtures.phzType,
            sampleRate = 8,
            bitsPerSample = CodecDeviceFixtures.phzBits,
        )
        val snapshot = CodecStateSnapshot(
            current = current,
            selectable = listOf(CodecDeviceFixtures.selectableCaps(CodecDeviceFixtures.phzType, 10)),
            localCapabilities = listOf(
                CodecDeviceFixtures.selectableCaps(
                    CodecDeviceFixtures.phzType,
                    CodecDeviceFixtures.phzCapsMask,
                ),
            ),
        )

        val presentation = CodecSnapshotPresenter.present(snapshot)!!

        assertEquals(CodecIdentitySource.UNKNOWN, presentation.identity.source)
        assertEquals("vendor(19)", presentation.identity.displayName)
        assertEquals("vendor(19)·96kHz·24bit", presentation.summary.text())
    }
}
