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

    @Test
    fun provenanceFieldsRoundTrip() {
        // Task 27 出处四字段：写出后能完整读回
        val profile = DeviceProfile(
            mac = "66:7D:8D:11:22:33",
            name = "OPPO Enco X3",
            config = golden,
            source = DeviceProfile.Source.CAPTURED,
            autoApply = true,
            recordedOnModel = "V2505A",
            recordedAndroidVersion = "15",
            recordedBuildDisplay = "PD2505_A_15.1.23.2",
            recordedAtMillis = 1761000000000L,
        )
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val restored = json.decodeFromString<DeviceProfile>(
            json.encodeToString(DeviceProfile.serializer(), profile),
        )
        assertEquals("V2505A", restored.recordedOnModel)
        assertEquals("15", restored.recordedAndroidVersion)
        assertEquals("PD2505_A_15.1.23.2", restored.recordedBuildDisplay)
        assertEquals(1761000000000L, restored.recordedAtMillis)
    }

    @Test
    fun legacyJsonWithoutProvenanceDefaults() {
        // 旧档案文件（Task 27 前写入，无出处四字段）必须能读，默认值兜底
        val legacy = """
            {"mac":"66:7D:8D:11:22:33","name":"OPPO Enco X3",
             "config":{"codecType":10,"priority":1000000,"sampleRate":32,"bitsPerSample":2,
                       "channelMode":2,"cs1":50888712,"cs2":0,"cs3":1543503872,"cs4":0},
             "source":"PRESET","autoApply":true}
        """.trimIndent()
        val restored = kotlinx.serialization.json.Json.decodeFromString<DeviceProfile>(legacy)
        assertEquals("66:7D:8D:11:22:33", restored.mac)
        assertEquals("", restored.recordedOnModel)
        assertEquals("", restored.recordedAndroidVersion)
        assertEquals("", restored.recordedBuildDisplay)
        assertEquals(0L, restored.recordedAtMillis)
    }

    @Test
    fun legacyJsonWithoutOriginConfigDefaults() {
        // Task 42：旧档案文件（Task 42 前写入，无 originConfig 字段）必须能读，
        // kotlinx.serialization 按默认值 null 兜底（ProfileRepository 的 Json 配置允许缺省字段）。
        val legacy = """
            {"mac":"66:7D:8D:11:22:33","name":"OPPO Enco X3",
             "config":{"codecType":10,"priority":1000000,"sampleRate":32,"bitsPerSample":2,
                       "channelMode":2,"cs1":50888712,"cs2":0,"cs3":1543503872,"cs4":0},
             "source":"AUTO","autoApply":true,
             "recordedOnModel":"V2505A","recordedAndroidVersion":"15",
             "recordedBuildDisplay":"PD2505_A_15.1.23.2","recordedAtMillis":1761000000000}
        """.trimIndent()
        // 用与 ProfileRepository 相同的 Json 配置验证
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val restored = json.decodeFromString<DeviceProfile>(legacy)
        assertEquals("66:7D:8D:11:22:33", restored.mac)
        assertEquals(null, restored.originConfig)
    }

    @Test
    fun originConfigRoundTrip() {
        // Task 42：originConfig 写出后能完整读回
        val origin = golden.copy(sampleRate = 16, bitsPerSample = 1, cs1 = 1003L)
        val profile = DeviceProfile(
            mac = "66:7D:8D:11:22:33",
            name = "OPPO Enco X3",
            config = golden,
            source = DeviceProfile.Source.AUTO,
            autoApply = true,
            originConfig = origin,
        )
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val restored = json.decodeFromString<DeviceProfile>(
            json.encodeToString(DeviceProfile.serializer(), profile),
        )
        assertEquals(origin, restored.originConfig)
    }
}
