package com.chengzi.stayhifi.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.chengzi.stayhifi.codec.CodecConfig
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplyPreferenceStoreTest {

    private val mac = "AA:BB:CC:DD:EE:01"
    private val target = CodecConfig(19, 1_000_000, 32, 2, 2, 0x8008, 0, 0, 0)

    @Test
    fun customPreferenceSurvivesStoreRecreation() = runTest {
        val file = Files.createTempFile("stayhifi-prefs", ".preferences_pb").toFile()
        val dataStore = PreferenceDataStoreFactory.create { file }
        val store = ApplyPreferenceStore(dataStore)

        store.setCustom(mac, target)
        val recreated = ApplyPreferenceStore(dataStore).get(mac)

        assertEquals(ApplyMode.CUSTOM, recreated.mode)
        assertEquals(target, recreated.lastTarget)
    }

    @Test
    fun adaptiveKeepsRejectedTargetOnlyForManualRetryAndAutoMaxClearsIt() = runTest {
        val file = Files.createTempFile("stayhifi-prefs", ".preferences_pb").toFile()
        val store = ApplyPreferenceStore(PreferenceDataStoreFactory.create { file })
        store.setSystemAdaptive(mac, target)

        val adaptive = store.get(mac)
        assertEquals(ApplyMode.SYSTEM_ADAPTIVE, adaptive.mode)
        assertEquals(target, adaptive.lastTarget)

        store.setAutoMax(mac)
        assertEquals(ApplyMode.AUTO_MAX, store.get(mac).mode)
        assertNull(store.get(mac).lastTarget)
    }
}
