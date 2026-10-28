package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task B5：通用原因评估、hex/逐 bit 解码与紧凑格式预算。
 * 正常 SBC/AAC 快照无任何 reason；各异常状态给出可区分的 reason token；
 * 不得出现 aptX 类型判断分支。
 */
class VendorStackDiagnosticsTest {

    private val sbcCurrent = CodecDeviceFixtures.unverified(0, 1, bitsPerSample = 1)
    private val aacCurrent = CodecDeviceFixtures.unverified(1, 2, bitsPerSample = 1)

    private fun readStatus(
        codecStatusNull: Boolean = false,
        currentNull: Boolean = false,
        localCount: Int = 1,
        selectableCount: Int = 1,
        currentSelectable: Boolean? = true,
        bluetoothState: String = "ON",
        a2dpProxyState: String = "CONNECTED",
        codecStatusState: ApiReadState = ApiReadState.OK,
        currentState: ApiReadState = ApiReadState.OK,
        localCapabilitiesState: ApiReadState = ApiReadState.OK,
        selectableState: ApiReadState = ApiReadState.OK,
        errors: Map<String, String> = emptyMap(),
    ) = CodecReadStatus(
        codecStatusNull = codecStatusNull,
        currentNull = currentNull,
        localCount = localCount,
        selectableCount = selectableCount,
        currentSelectable = currentSelectable,
        bluetoothState = bluetoothState,
        a2dpProxyState = a2dpProxyState,
        codecStatusState = codecStatusState,
        currentState = currentState,
        localCapabilitiesState = localCapabilitiesState,
        selectableState = selectableState,
        errors = errors,
    )

    private fun identityDiag(
        supportedTypesStatus: ApiReadState = ApiReadState.OK,
        extendedTypeStatus: ApiReadState = ApiReadState.OK,
        serviceUid: Int = 1002,
        errors: Map<String, String> = emptyMap(),
    ) = IdentityDiagnostics(
        supportedTypesStatus = supportedTypesStatus,
        extendedTypeStatus = extendedTypeStatus,
        serviceUid = serviceUid,
        errors = errors,
    )

    /** 正常快照基线：标准 SBC、能力齐全、各读取阶段 OK。 */
    private fun normalSnapshot(
        current: CodecConfig? = sbcCurrent,
        selectable: List<CodecConfig> = listOf(sbcCurrent),
        localCapabilities: List<CodecConfig> = listOf(sbcCurrent),
        supportedTypes: List<SupportedCodecType> = listOf(SupportedCodecType(0, 1L, "SBC")),
        extendedIdentity: ExtendedCodecIdentity? = null,
        readStatus: CodecReadStatus = readStatus(),
        identityDiagnostics: IdentityDiagnostics = identityDiag(),
    ) = CodecStateSnapshot(
        current = current,
        selectable = selectable,
        localCapabilities = localCapabilities,
        supportedTypes = supportedTypes,
        extendedIdentity = extendedIdentity,
        readStatus = readStatus,
        identityDiagnostics = identityDiagnostics,
    )

    // ===== 原因评估：可区分的 reason token =====

    @Test
    fun codecStatusNullIsDistinguishableReason() {
        val snapshot = normalSnapshot(readStatus = readStatus(codecStatusNull = true))
        assertEquals(setOf(VendorStackDiagnostics.REASON_CODEC_STATUS_NULL), VendorStackDiagnostics.reasons(snapshot))
    }

    @Test
    fun currentNullIsDistinguishableReason() {
        val snapshot = normalSnapshot(
            current = null,
            selectable = listOf(sbcCurrent),
            localCapabilities = listOf(sbcCurrent),
            readStatus = readStatus(currentNull = true, currentSelectable = null),
        )
        assertEquals(setOf(VendorStackDiagnostics.REASON_CURRENT_NULL), VendorStackDiagnostics.reasons(snapshot))
    }

    @Test
    fun emptyLocalAndSelectableAreDistinguishableReasons() {
        val localEmpty = normalSnapshot(localCapabilities = emptyList())
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_LOCAL_CAPABILITIES_EMPTY),
            VendorStackDiagnostics.reasons(localEmpty),
        )
        val selectableEmpty = normalSnapshot(selectable = emptyList())
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_SELECTABLE_EMPTY),
            VendorStackDiagnostics.reasons(selectableEmpty),
        )
    }

    @Test
    fun missingCurrentCodecEntryIsDistinguishableReason() {
        // current=19 但 local/selectable 只有 type 10 条目。
        val phz = CodecDeviceFixtures.unverified(19, 32, bitsPerSample = 2)
        val snapshot = normalSnapshot(
            current = phz,
            selectable = listOf(CodecDeviceFixtures.iqoo15Golden),
            localCapabilities = listOf(CodecDeviceFixtures.iqoo15Golden),
        )
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_CURRENT_CODEC_MISSING),
            VendorStackDiagnostics.reasons(snapshot),
        )
    }

    @Test
    fun currentRateZeroAndMultiBitAreDistinguishableReason() {
        val zero = normalSnapshot(current = sbcCurrent.copy(sampleRate = 0))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_CURRENT_RATE_NOT_SINGLE_BIT),
            VendorStackDiagnostics.reasons(zero),
        )
        val multiBit = normalSnapshot(current = sbcCurrent.copy(sampleRate = 43))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_CURRENT_RATE_NOT_SINGLE_BIT),
            VendorStackDiagnostics.reasons(multiBit),
        )
        val valid = normalSnapshot(current = sbcCurrent.copy(sampleRate = 32))
        assertEquals(emptySet<String>(), VendorStackDiagnostics.reasons(valid))
    }

    @Test
    fun currentBitsZeroAndMultiBitAreDistinguishableReason() {
        val zero = normalSnapshot(current = sbcCurrent.copy(bitsPerSample = 0))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_CURRENT_BITS_NOT_SINGLE_BIT),
            VendorStackDiagnostics.reasons(zero),
        )
        val multiBit = normalSnapshot(current = sbcCurrent.copy(bitsPerSample = 3))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_CURRENT_BITS_NOT_SINGLE_BIT),
            VendorStackDiagnostics.reasons(multiBit),
        )
    }

    @Test
    fun supportedTypesUnavailableEmptyAndErrorAreThreeDistinguishableReasons() {
        val unavailable = normalSnapshot(identityDiagnostics = identityDiag(supportedTypesStatus = ApiReadState.UNAVAILABLE))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_SUPPORTED_TYPES_UNAVAILABLE),
            VendorStackDiagnostics.reasons(unavailable),
        )
        val empty = normalSnapshot(identityDiagnostics = identityDiag(supportedTypesStatus = ApiReadState.EMPTY))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_SUPPORTED_TYPES_EMPTY),
            VendorStackDiagnostics.reasons(empty),
        )
        val error = normalSnapshot(identityDiagnostics = identityDiag(supportedTypesStatus = ApiReadState.ERROR))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_SUPPORTED_TYPES_ERROR),
            VendorStackDiagnostics.reasons(error),
        )
    }

    @Test
    fun extendedIdentityReadErrorIsDistinguishableReason() {
        val snapshot = normalSnapshot(identityDiagnostics = identityDiag(extendedTypeStatus = ApiReadState.ERROR))
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_EXTENDED_IDENTITY_ERROR),
            VendorStackDiagnostics.reasons(snapshot),
        )
    }

    @Test
    fun identityConflictsAreDistinguishableReasons() {
        // supportedTypes 组内两条可信但互冲突的名称。
        val conflict = normalSnapshot(
            current = CodecDeviceFixtures.unverified(50, 8),
            selectable = listOf(CodecDeviceFixtures.unverified(50, 8)),
            localCapabilities = listOf(CodecDeviceFixtures.unverified(50, 8)),
            supportedTypes = listOf(
                SupportedCodecType(50, 1L, "LHDC V3"),
                SupportedCodecType(50, 2L, "LHDC V5"),
            ),
        )
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_IDENTITY_SUPPORTED_CONFLICT),
            VendorStackDiagnostics.reasons(conflict),
        )
        // 不可信系统名（UNKNOWN CODEC 前缀）。
        val invalid = normalSnapshot(
            current = CodecDeviceFixtures.unverified(50, 8),
            selectable = listOf(CodecDeviceFixtures.unverified(50, 8)),
            localCapabilities = listOf(CodecDeviceFixtures.unverified(50, 8)),
            supportedTypes = listOf(SupportedCodecType(50, 1L, "UNKNOWN CODEC(50)")),
        )
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_IDENTITY_SUPPORTED_INVALID),
            VendorStackDiagnostics.reasons(invalid),
        )
        // 系统身份（LDAC）推翻 legacy 映射（type0=SBC）。
        val legacyConflict = normalSnapshot(
            supportedTypes = listOf(SupportedCodecType(0, 1L, "LDAC")),
        )
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_IDENTITY_SYSTEM_LEGACY_CONFLICT),
            VendorStackDiagnostics.reasons(legacyConflict),
        )
        // extended 身份不可信。
        val extendedInvalid = normalSnapshot(
            current = CodecDeviceFixtures.unverified(50, 8),
            selectable = listOf(CodecDeviceFixtures.unverified(50, 8)),
            localCapabilities = listOf(CodecDeviceFixtures.unverified(50, 8)),
            supportedTypes = emptyList(),
            extendedIdentity = ExtendedCodecIdentity(codecName = "UNKNOWN CODEC(50)"),
            identityDiagnostics = identityDiag(supportedTypesStatus = ApiReadState.UNAVAILABLE),
        )
        assertEquals(
            setOf(VendorStackDiagnostics.REASON_SUPPORTED_TYPES_UNAVAILABLE,
                VendorStackDiagnostics.REASON_IDENTITY_EXTENDED_INVALID),
            VendorStackDiagnostics.reasons(extendedInvalid),
        )
    }

    @Test
    fun normalSbcAndAacSnapshotsHaveNoReasons() {
        val sbc = normalSnapshot()
        val aac = normalSnapshot(
            current = aacCurrent,
            selectable = listOf(aacCurrent),
            localCapabilities = listOf(aacCurrent),
            supportedTypes = listOf(SupportedCodecType(1, 2L, "AAC")),
        )
        assertEquals(emptySet<String>(), VendorStackDiagnostics.reasons(sbc))
        assertEquals(emptySet<String>(), VendorStackDiagnostics.reasons(aac))
    }

    // ===== hex / 逐 bit 解码 =====

    @Test
    fun hexAndPerBitDecodingKeepsKnownAndUnknownBits() {
        // 43 = 1|2|8|32 → 44.1/48/96/192。
        assertEquals(
            listOf("44.1kHz", "48kHz", "96kHz", "192kHz"),
            VendorStackDiagnostics.decodeSampleRates(43),
        )
        // bits=3 → 16/24。
        assertEquals(listOf("16bit", "24bit"), VendorStackDiagnostics.decodeBitDepths(3))
        // 未知位保留 unknown；掩码 0 为空表。
        assertEquals(listOf("unknown"), VendorStackDiagnostics.decodeSampleRates(64))
        assertEquals(listOf("96kHz", "unknown"), VendorStackDiagnostics.decodeSampleRates(8 or 64))
        assertEquals(emptyList<String>(), VendorStackDiagnostics.decodeSampleRates(0))
        assertEquals(listOf("unknown"), VendorStackDiagnostics.decodeBitDepths(8))
        assertEquals(listOf("STEREO"), VendorStackDiagnostics.decodeChannelModes(2))
        assertEquals(listOf("MONO"), VendorStackDiagnostics.decodeChannelModes(1))
        assertEquals(listOf("unknown"), VendorStackDiagnostics.decodeChannelModes(4))
    }

    // ===== 紧凑格式：字段齐全 + 预算 =====

    private fun context() = VendorDiagnosticContext(
        mac = "AA:BB:CC:DD:EE:FF",
        trigger = DiagnosticTrigger.CONNECTION,
        source = DiagnosticSource.APPLY,
        timestampMillis = 1_700_000_000_000L,
        providerKind = "shizuku",
        device = DiagnosticDeviceMetadata(
            marketModel = "TestModel",
            androidRelease = "15",
            sdkInt = 35,
            buildDisplay = "display.1",
            manufacturer = "testmfr",
            hardware = "testhw",
            board = "testboard",
            socManufacturer = "testsocm",
            socModel = "testsocx",
        ),
    )

    @Test
    fun formatKeepsCurrentReadStatusAndDecimalPlusHexCodecFields() {
        val golden = CodecDeviceFixtures.iqoo15Golden
        val snapshot = normalSnapshot(
            current = golden,
            selectable = listOf(golden),
            localCapabilities = listOf(golden),
            supportedTypes = listOf(SupportedCodecType(10, 42L, "LHDC V5")),
        )
        val event = VendorStackDiagnostics.format(snapshot, context(), snapshotSeq = 7, suppressedCount = 2)

        assertEquals(1, event.schemaVersion)
        assertEquals(7L, event.snapshotSeq)
        assertEquals(2, event.suppressedCount)
        assertTrue(event.text.contains("v=1"))
        assertTrue(event.text.contains("trigger=connection"))
        assertTrue(event.text.contains("source=apply"))
        assertTrue(event.text.contains("mac=AA:BB:CC:DD:EE:FF"))
        assertTrue(event.text.contains("provider=shizuku"))
        assertTrue(event.text.contains("serviceUid=1002"))
        assertTrue(event.text.contains("device={model=TestModel"))
        assertTrue(event.text.contains("soc=testsocm/testsocx"))
        // current 完整 9 字段：十进制与 hex 并存。
        assertTrue(event.text.contains("t=10 p=1000000"))
        assertTrue(event.text.contains("r=32(0x20)[192kHz]"))
        assertTrue(event.text.contains("b=2(0x2)[24bit]"))
        assertTrue(event.text.contains("ch=2(0x2)[STEREO]"))
        assertTrue(event.text.contains("cs1=${golden.cs1}(0x${golden.cs1.toString(16).uppercase()})"))
        assertTrue(event.text.contains("cs2=${golden.cs2}(0x0)"))
        assertTrue(event.text.contains("cs3=${golden.cs3}(0x${golden.cs3.toString(16).uppercase()})"))
        assertTrue(event.text.contains("cs4=${golden.cs4}(0x0)"))
        // readStatus / supportedTypes / identity / reasons 完整存在。
        assertTrue(event.text.contains("readStatus={codecStatusNull=false currentNull=false"))
        assertTrue(event.text.contains("bluetooth=ON a2dp=CONNECTED"))
        assertTrue(event.text.contains("identity={name=LHDC V5 family=LHDC source=SYSTEM_TYPE codecId=42 nativeType=10 issues=[]}"))
        assertTrue(event.text.contains("supportedTypes=[s{t=10 id=42 name=LHDC V5}]"))
        assertTrue(event.text.contains("identityDiag={supportedTypesStatus=OK extendedTypeStatus=OK"))
        assertTrue(event.text.contains("reasons=[]"))
        assertTrue(event.text.contains("truncatedCount=0"))
        // null 与空列表不被合成同一文案：extended 显式 null，空段显式 []。
        assertTrue(event.text.contains(" extended=null"))
        assertTrue(event.text.contains("dumpClues=[]"))
        assertFalse(event.text.contains("reasons=[currentNull]"))
    }
}
