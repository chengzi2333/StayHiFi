package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.codec.CodecConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualEditorInputTest {

    private val rejected = CodecConfig(
        codecType = 19,
        priority = 1_000_000,
        sampleRate = 32,
        bitsPerSample = 2,
        channelMode = 2,
        cs1 = 0x8007L,
        cs2 = 0L,
        cs3 = 0L,
        cs4 = 0L,
    )

    @Test
    fun rejectionSnapshotUpdateBlocksImmediately() {
        val input = ManualEditorInput(current = rejected, selectable = emptyList())

        val updated = input.withRejectedTargets(setOf(rejected))

        assertTrue(updated.isRejected(rejected))
        assertFalse("旧快照不应被就地修改", input.isRejected(rejected))
    }

    @Test
    fun onlyExactRejectedTargetIsBlocked() {
        val input = ManualEditorInput(
            current = rejected,
            selectable = emptyList(),
            rejectedTargets = setOf(rejected),
        )

        assertTrue(input.isRejected(rejected))
        assertFalse(input.isRejected(null))
        assertFalse(input.isRejected(rejected.copy(sampleRate = 8)))
        assertFalse(input.isRejected(rejected.copy(cs1 = 0x8008L)))

        val withoutEvidence = input.copy(rejectedTargets = emptySet())
        assertFalse(withoutEvidence.isRejected(rejected))
    }

    @Test
    fun preferredTargetSeedsEditorWithoutChangingLiveCurrent() {
        val preferred = rejected.copy(sampleRate = 2)
        val input = ManualEditorInput(
            current = rejected,
            selectable = emptyList(),
            preferredTarget = preferred,
        )

        assertEquals(preferred, input.editorTarget())
        assertEquals(rejected, input.current)
        assertEquals(rejected, input.copy(preferredTarget = null).editorTarget())
    }
}
