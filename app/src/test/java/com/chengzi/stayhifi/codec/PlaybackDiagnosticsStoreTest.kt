package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.lang.reflect.Modifier
import org.junit.Test

class PlaybackDiagnosticsStoreTest {

    @Test
    fun replaceKeepsOnlyCurrentWindowAndSnapshotsAreIndependent() {
        val store = PlaybackDiagnosticsStore()
        val generation = store.generation
        store.replace(generation, listOf("first"))
        store.replace(generation, listOf("second"))

        val snapshot = store.snapshot()
        assertEquals(listOf("second"), snapshot)
        assertNotSame(snapshot, store.snapshot())
    }

    @Test
    fun clearRejectsLateResultFromPreviousGeneration() {
        val store = PlaybackDiagnosticsStore()
        val oldGeneration = store.generation
        store.clear()

        store.replace(oldGeneration, listOf("stale"))

        assertEquals(emptyList<String>(), store.snapshot())
        assertEquals(oldGeneration + 1, store.generation)
    }

    @Test
    fun generationReadHasCrossThreadVisibility() {
        val modifiers = PlaybackDiagnosticsStore::class.java.getDeclaredField("generation").modifiers
        assertTrue(Modifier.isVolatile(modifiers))
    }
}
