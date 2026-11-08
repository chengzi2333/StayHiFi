package com.chengzi.stayhifi.trigger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LhdcQualityCeilingStoreTest {

    @After
    fun tearDown() = LhdcQualityCeilingStore.clearAll()

    @Test
    fun ceilingRecordedPerMacAndCodecType() {
        LhdcQualityCeilingStore.record("AA", 19, 0x8006L)

        assertEquals(0x8006L, LhdcQualityCeilingStore.ceilingFor("AA", 19))
        // 换 codec / 换设备互不影响
        assertNull(LhdcQualityCeilingStore.ceilingFor("AA", 18))
        assertNull(LhdcQualityCeilingStore.ceilingFor("BB", 19))
    }

    @Test
    fun ceilingOverwrittenByNewerVerdict() {
        LhdcQualityCeilingStore.record("AA", 19, 0x8006L)
        LhdcQualityCeilingStore.record("AA", 19, 0x8009L)

        assertEquals(0x8009L, LhdcQualityCeilingStore.ceilingFor("AA", 19))
    }

    @Test
    fun clearRemovesCeiling() {
        LhdcQualityCeilingStore.record("AA", 19, 0x8006L)
        LhdcQualityCeilingStore.clear("AA")

        assertNull(LhdcQualityCeilingStore.ceilingFor("AA", 19))
    }
}
