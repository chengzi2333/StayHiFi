package com.chengzi.stayhifi.codec

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodecStateSnapshotJsonTest {

    private val golden = CodecDeviceFixtures.iqoo15Golden

    /** 旧两字段 JSON：与 v1.2.1 CodecService.readConfig 输出格式一致。 */
    private fun oldTwoFieldJson(
        current: CodecConfig = golden,
        selectable: List<CodecConfig> = listOf(
            CodecDeviceFixtures.opdCurrent,
            CodecDeviceFixtures.opdCurrent.copy(sampleRate = CodecDeviceFixtures.opdCapsMask),
        ),
    ): String = buildJsonObject {
        put("current", Json.parseToJsonElement(current.toJson()))
        putJsonArray("selectable") {
            for (c in selectable) add(Json.parseToJsonElement(c.toJson()))
        }
    }.toString()

    @Test
    fun oldTwoFieldJsonPreservesCurrentAndSelectable() {
        val s = CodecStateSnapshotJson.parse(oldTwoFieldJson())
        assertEquals(golden, s.current)
        assertEquals(2, s.selectable.size)
        assertEquals(CodecDeviceFixtures.opdCurrent, s.selectable[0])
    }

    @Test
    fun oldTwoFieldJsonKeepsNewIdentitySourcesUnavailable() {
        val s = CodecStateSnapshotJson.parse(oldTwoFieldJson())
        assertTrue(s.localCapabilities.isEmpty())
        assertTrue(s.supportedTypes.isEmpty())
        assertNull(s.extendedIdentity)
        assertEquals(ApiReadState.UNAVAILABLE, s.readStatus.localCapabilitiesState)
        assertEquals(ApiReadState.UNAVAILABLE, s.identityDiagnostics.supportedTypesStatus)
        assertEquals(ApiReadState.UNAVAILABLE, s.identityDiagnostics.extendedTypeStatus)
        // 旧 JSON 没有状态事实，读状态保持默认；不从 current 推断任何状态
        assertEquals(ApiReadState.OK, s.readStatus.codecStatusState)
        assertEquals(ApiReadState.OK, s.readStatus.currentState)
        assertEquals(ApiReadState.OK, s.readStatus.selectableState)
        assertTrue(s.readStatus.errors.isEmpty())
        assertEquals("UNKNOWN", s.readStatus.bluetoothState)
        assertEquals("UNKNOWN", s.readStatus.a2dpProxyState)
        assertNull(s.identityDiagnostics.serviceUid)
    }

    @Test
    fun parseNormalizesCountsToActualArraySizes() {
        val s = CodecStateSnapshotJson.parse(oldTwoFieldJson())
        assertEquals(s.selectable.size, s.readStatus.selectableCount)
        assertEquals(2, s.readStatus.selectableCount)
        assertEquals(s.localCapabilities.size, s.readStatus.localCount)
        assertEquals(0, s.readStatus.localCount)
    }

    @Test
    fun fullFieldEncodeParseRoundTrip() {
        val snapshot = CodecStateSnapshot(
            current = CodecDeviceFixtures.opdCurrent,
            selectable = listOf(
                CodecDeviceFixtures.opdCurrent,
                CodecDeviceFixtures.selectableCaps(
                    CodecDeviceFixtures.opdType,
                    CodecDeviceFixtures.opdCapsMask,
                ),
            ),
            localCapabilities = listOf(
                CodecDeviceFixtures.phzCurrent.copy(sampleRate = CodecDeviceFixtures.phzCapsMask),
            ),
            supportedTypes = listOf(
                SupportedCodecType(
                    nativeType = CodecDeviceFixtures.opdType,
                    codecId = 1000000L,
                    codecName = "LHDC V3",
                ),
            ),
            extendedIdentity = ExtendedCodecIdentity(codecId = 12345L, codecName = "LHDC V3"),
            readStatus = CodecReadStatus(
                codecStatusNull = false,
                currentNull = false,
                localCount = 1,
                selectableCount = 2,
                currentSelectable = true,
                bluetoothState = "ON",
                a2dpProxyState = "CONNECTED",
                codecStatusState = ApiReadState.OK,
                currentState = ApiReadState.OK,
                localCapabilitiesState = ApiReadState.OK,
                selectableState = ApiReadState.OK,
                errors = mapOf("extendedIdentity" to "NoSuchMethodException"),
            ),
            identityDiagnostics = IdentityDiagnostics(
                supportedTypesStatus = ApiReadState.OK,
                extendedTypeStatus = ApiReadState.OK,
                serviceUid = 10088,
                errors = emptyMap(),
            ),
        )
        val parsed = CodecStateSnapshotJson.parse(CodecStateSnapshotJson.encode(snapshot))
        assertEquals(snapshot, parsed)
        // local、supportedTypes、extendedIdentity、serviceUid、currentSelectable 均保留
        assertEquals(1, parsed.localCapabilities.size)
        assertEquals(1, parsed.supportedTypes.size)
        assertEquals(1000000L, parsed.supportedTypes[0].codecId)
        assertEquals("LHDC V3", parsed.supportedTypes[0].codecName)
        assertEquals(ExtendedCodecIdentity(12345L, "LHDC V3"), parsed.extendedIdentity)
        assertEquals(10088, parsed.identityDiagnostics.serviceUid)
        assertEquals(true, parsed.readStatus.currentSelectable)
        // parse 后 count 与实际数组大小一致
        assertEquals(parsed.localCapabilities.size, parsed.readStatus.localCount)
        assertEquals(parsed.selectable.size, parsed.readStatus.selectableCount)
    }

    @Test
    fun nullEmptyErrorStatesAndExplicitNullCurrentRoundTrip() {
        val snapshot = CodecStateSnapshot(
            current = null,
            selectable = emptyList(),
            localCapabilities = emptyList(),
            readStatus = CodecReadStatus(
                codecStatusNull = true,
                currentNull = true,
                currentSelectable = null,
                codecStatusState = ApiReadState.NULL,
                currentState = ApiReadState.NULL,
                localCapabilitiesState = ApiReadState.EMPTY,
                selectableState = ApiReadState.ERROR,
                errors = mapOf(
                    "selectable" to "SecurityException: need BLUETOOTH_PRIVILEGED",
                    "localCapabilities" to "NoSuchMethodException",
                ),
            ),
            identityDiagnostics = IdentityDiagnostics(
                supportedTypesStatus = ApiReadState.ERROR,
                extendedTypeStatus = ApiReadState.NULL,
                errors = mapOf("supportedTypes" to "SecurityException"),
            ),
        )
        val encoded = CodecStateSnapshotJson.encode(snapshot)
        // explicitNulls=true：current:null 显式写入，可保留「读到了空」这一事实
        assertTrue(encoded.contains("\"current\":null"))
        val parsed = CodecStateSnapshotJson.parse(encoded)
        assertNull(parsed.current)
        assertTrue(parsed.selectable.isEmpty())
        assertTrue(parsed.localCapabilities.isEmpty())
        // NULL / EMPTY / ERROR 各自保留，不得归一化成 OK
        assertEquals(ApiReadState.NULL, parsed.readStatus.codecStatusState)
        assertEquals(ApiReadState.NULL, parsed.readStatus.currentState)
        assertEquals(ApiReadState.EMPTY, parsed.readStatus.localCapabilitiesState)
        assertEquals(ApiReadState.ERROR, parsed.readStatus.selectableState)
        assertEquals(ApiReadState.ERROR, parsed.identityDiagnostics.supportedTypesStatus)
        assertEquals(ApiReadState.NULL, parsed.identityDiagnostics.extendedTypeStatus)
        assertEquals(true, parsed.readStatus.codecStatusNull)
        assertEquals(true, parsed.readStatus.currentNull)
        assertNull(parsed.readStatus.currentSelectable)
        // ERROR 的 stage key 不丢
        assertEquals(
            "SecurityException: need BLUETOOTH_PRIVILEGED",
            parsed.readStatus.errors["selectable"],
        )
        assertEquals("NoSuchMethodException", parsed.readStatus.errors["localCapabilities"])
        assertEquals("SecurityException", parsed.identityDiagnostics.errors["supportedTypes"])
        // EMPTY 不被改成 OK，也不被实际数组长度掩盖：状态原样、count 按数组归一
        assertEquals(0, parsed.readStatus.selectableCount)
        assertEquals(0, parsed.readStatus.localCount)
    }

    @Test
    fun explicitNullCurrentInJsonParsesToNull() {
        val s = CodecStateSnapshotJson.parse("""{"current":null,"selectable":[]}""")
        assertNull(s.current)
        assertTrue(s.selectable.isEmpty())
    }

    @Test
    fun unknownExtraFieldsAreIgnored() {
        val s = CodecStateSnapshotJson.parse(
            buildJsonObject {
                put("current", Json.parseToJsonElement(golden.toJson()))
                putJsonArray("selectable") { add(Json.parseToJsonElement(golden.toJson())) }
                putJsonObject("futureSection") { put("futureField", 123) }
                put("futureScalar", "x")
            }.toString(),
        )
        assertEquals(golden, s.current)
        assertEquals(1, s.selectable.size)
    }

    @Test
    fun errorJsonThrowsBecauseCurrentIsMissing() {
        try {
            CodecStateSnapshotJson.parse("""{"error":"readConfig: SecurityException: denied"}""")
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            // {error:...} 缺 current，不得伪装成有效 snapshot
        }
    }

    @Test
    fun missingCurrentThrows() {
        try {
            CodecStateSnapshotJson.parse("""{"selectable":[]}""")
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            // current 无默认值，缺失即失败
        }
    }

    @Test
    fun corruptJsonThrows() {
        try {
            CodecStateSnapshotJson.parse("{not json")
            fail("expected SerializationException")
        } catch (e: SerializationException) {
            // 损坏 JSON 必须抛异常
        }
    }
}
