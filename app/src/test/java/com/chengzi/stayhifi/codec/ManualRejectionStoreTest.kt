package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualRejectionStoreTest {

    private val mac = "AA:BB:CC:DD:EE:FF"
    private val otherMac = "11:22:33:44:55:66"
    private val target900 = CodecConfig(
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
    fun rejectionIsExactPerMacAndCompleteTarget() {
        val store = ManualRejectionStore()
        store.record(mac, target900)

        assertEquals(setOf(target900), store.forMac(mac.lowercase()))
        assertFalse(store.forMac(mac).contains(target900.copy(sampleRate = 8)))
        assertFalse(store.forMac(mac).contains(target900.copy(cs1 = 0x8008L)))
        assertTrue(store.forMac(otherMac).isEmpty())

        val snapshot = store.forMac(mac)
        store.record(mac, target900.copy(cs1 = 0x8008L))
        assertEquals("forMac 必须是当时快照而非实时可变视图", setOf(target900), snapshot)
    }

    @Test
    fun clearAllStartsNewSessionAndRejectsStaleResult() {
        val store = ManualRejectionStore()
        val stale = store.generation
        assertTrue(store.isCurrent(stale))

        store.clearAll()

        assertFalse("同 MAC 重连也必须让旧任务失效", store.isCurrent(stale))
        assertTrue(store.isCurrent(store.generation))
    }

    @Test
    fun removeAndClearScopes() {
        val store = ManualRejectionStore()
        val target1000 = target900.copy(cs1 = 0x8008L)
        store.record(mac, target900)
        store.record(mac, target1000)
        store.record(otherMac, target900)

        store.remove(mac, target900)
        assertEquals(setOf(target1000), store.forMac(mac))
        assertEquals(setOf(target900), store.forMac(otherMac))

        store.clear(mac)
        assertTrue(store.forMac(mac).isEmpty())
        assertEquals("恢复自适应只清当前 MAC", setOf(target900), store.forMac(otherMac))

        store.record(mac, target900)
        store.clearAll()
        assertTrue(store.forMac(mac).isEmpty())
        assertTrue(store.forMac(otherMac).isEmpty())
    }
}
