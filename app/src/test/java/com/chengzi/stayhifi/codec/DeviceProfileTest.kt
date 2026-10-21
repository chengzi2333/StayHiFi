package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProfileTest {

    private val golden = CodecConfig(
        codecType = 10,
        priority = 1000000,
        sampleRate = 32,
        bitsPerSample = 2,
        channelMode = 2,
        cs1 = 50888712L,
        cs2 = 0L,
        cs3 = 1543503872L,
        cs4 = 0L,
    )

    @Test
    fun jsonRoundTripPreservesAllFields() {
        val profile = DeviceProfile(
            mac = "66:7D:8D:11:22:33",
            name = "OPPO Enco X3",
            config = golden,
            source = DeviceProfile.Source.PRESET,
            autoApply = true,
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val restored = json.decodeFromString<DeviceProfile>(
            json.encodeToString(DeviceProfile.serializer(), profile),
        )
        assertEquals(profile, restored)
    }

    @Test
    fun allSourceValuesRoundTrip() {
        val json = kotlinx.serialization.json.Json
        for (source in DeviceProfile.Source.entries) {
            val profile = DeviceProfile("mac", "name", golden, source, false)
            val restored = json.decodeFromString<DeviceProfile>(
                json.encodeToString(DeviceProfile.serializer(), profile),
            )
            assertEquals(source, restored.source)
        }
    }
}
