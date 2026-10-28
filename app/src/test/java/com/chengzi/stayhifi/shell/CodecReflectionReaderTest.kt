package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.codec.ApiReadState
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.CodecDeviceFixtures
import com.chengzi.stayhifi.codec.CodecStateSnapshot
import com.chengzi.stayhifi.codec.ExtendedCodecIdentity
import com.chengzi.stayhifi.codec.SupportedCodecType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 2：`CodecReflectionReader` 分阶段反射读取与 OEM 失败隔离。
 * 全部用带真实方法的 fake 对象（Android 类型只以 `Any` 进入 reader），
 * 反射路径与真机一致；异常经反射 invoke 自动包 InvocationTargetException，
 * 因此 fake 直接抛出的异常即覆盖「解包 ITE」分支。
 */
class CodecReflectionReaderTest {

    // ─────────────────────────── fakes ───────────────────────────

    private class FakeDevice

    private open class FakeConfig(
        private val cfgType: Int, private val cfgPriority: Int, private val cfgRate: Int,
        private val cfgBits: Int, private val cfgChannel: Int,
        private val cfgCs1: Long, private val cfgCs2: Long,
        private val cfgCs3: Long, private val cfgCs4: Long,
    ) {
        constructor(cfg: CodecConfig) : this(
            cfg.codecType, cfg.priority, cfg.sampleRate, cfg.bitsPerSample,
            cfg.channelMode, cfg.cs1, cfg.cs2, cfg.cs3, cfg.cs4,
        )

        fun getCodecType(): Int = cfgType
        fun getCodecPriority(): Int = cfgPriority
        fun getSampleRate(): Int = cfgRate
        fun getBitsPerSample(): Int = cfgBits
        fun getChannelMode(): Int = cfgChannel
        fun getCodecSpecific1(): Long = cfgCs1
        fun getCodecSpecific2(): Long = cfgCs2
        fun getCodecSpecific3(): Long = cfgCs3
        fun getCodecSpecific4(): Long = cfgCs4
    }

    /** 带 `getExtendedCodecType()` 的 config；FakeConfig 不带该方法就是「API 不存在」。 */
    private class FakeConfigWithExtended(
        cfg: CodecConfig,
        var extended: Any? = null,
        var extendedError: Throwable? = null,
    ) : FakeConfig(cfg) {
        fun getExtendedCodecType(): Any? {
            extendedError?.let { throw it }
            return extended
        }
    }

    private class FakeCodecType(
        private val typeNative: Int, private val typeId: Long, private val typeName: String,
    ) {
        fun getNativeCodecType(): Int = typeNative
        fun getCodecId(): Long = typeId
        fun getCodecName(): String = typeName
    }

    private open class FakeStatus(
        var config: Any? = null,
        var configError: Throwable? = null,
        var local: List<Any>? = null,
        var localError: Throwable? = null,
        var selectable: List<Any>? = null,
        var selectableError: Throwable? = null,
    ) {
        fun getCodecConfig(): Any? {
            configError?.let { throw it }
            return config
        }

        fun getCodecsLocalCapabilities(): List<Any>? {
            localError?.let { throw it }
            return local
        }

        fun getCodecsSelectableCapabilities(): List<Any>? {
            selectableError?.let { throw it }
            return selectable
        }
    }

    /** 没有 `getCodecsLocalCapabilities()` 的 status：local 阶段 API 不存在。 */
    private class FakeStatusWithoutLocalApi(
        var config: Any? = null,
        var selectable: List<Any>? = null,
    ) {
        fun getCodecConfig(): Any? = config
        fun getCodecsSelectableCapabilities(): List<Any>? = selectable
    }

    private open class FakeA2dp(
        var status: Any? = null,
        var statusError: Throwable? = null,
        var supported: List<FakeCodecType>? = null,
        var supportedError: Throwable? = null,
    ) {
        fun getCodecStatus(device: Any?): Any? {
            statusError?.let { throw it }
            return status
        }

        fun getSupportedCodecTypes(): List<FakeCodecType>? {
            supportedError?.let { throw it }
            return supported
        }
    }

    /** 没有 `getSupportedCodecTypes()` 的 a2dp：supported 阶段 API 不存在。 */
    private class FakeA2dpWithoutSupportedApi(var status: Any? = null) {
        fun getCodecStatus(device: Any?): Any? = status
    }

    /** 没有 `getCodecStatus()` 的 a2dp：codecStatus 阶段 API 不存在。 */
    private class FakeA2dpWithoutCodecStatusApi {
        fun getSupportedCodecTypes(): List<FakeCodecType>? = null
    }

    /** `getSupportedCodecTypes()` 返回非 List 的 Collection（AOSP 实际返回 Collection）。 */
    private class FakeA2dpWithCollectionCatalog(
        var status: Any? = null,
        var catalog: Collection<FakeCodecType>? = null,
    ) {
        fun getCodecStatus(device: Any?): Any? = status
        fun getSupportedCodecTypes(): Collection<FakeCodecType>? = catalog
    }

    /** 9 字段 getter 之一抛异常的 config 本体（getCodecConfig 本身正常返回）。 */
    private class FakeConfigWithBrokenField {
        fun getCodecType(): Int = CodecDeviceFixtures.opdType
        fun getCodecPriority(): Int = 0
        fun getSampleRate(): Int = CodecDeviceFixtures.opdCurrentRate
        fun getBitsPerSample(): Int = CodecDeviceFixtures.opdBits
        fun getChannelMode(): Int = 0
        fun getCodecSpecific1(): Long = throw SecurityException("cs1 denied")
        fun getCodecSpecific2(): Long = 0L
        fun getCodecSpecific3(): Long = 0L
        fun getCodecSpecific4(): Long = 0L
    }

    // ─────────────────────────── helpers ───────────────────────────

    private val device = FakeDevice()

    private fun read(
        a2dp: Any,
        status: ReflectedValue<Any>? = null,
        supported: ReflectedValue<List<SupportedCodecType>>? = null,
    ): CodecStateSnapshot {
        val st = status ?: CodecReflectionReader.codecStatus(a2dp, device)
        val sup = supported ?: CodecReflectionReader.supportedTypes(a2dp)
        return CodecReflectionReader.readSnapshot(st, a2dp, sup, 2000, "ON", "CONNECTED")
    }

    private fun happyStatus(
        config: Any? = FakeConfigWithExtended(CodecDeviceFixtures.opdCurrent),
        local: List<Any>? = listOf(FakeConfig(CodecDeviceFixtures.opdCurrent)),
        selectable: List<Any>? = listOf(
            FakeConfig(CodecDeviceFixtures.opdCurrent),
            FakeConfig(CodecDeviceFixtures.selectableCaps(
                CodecDeviceFixtures.opdType, CodecDeviceFixtures.opdCapsMask)),
        ),
    ) = FakeStatus(config = config, local = local, selectable = selectable)

    // ─────────────────────────── codecStatus / current ───────────────────────────

    @Test
    fun codecStatusNullYieldsExplicitNullCurrentWithStates() {
        val s = read(FakeA2dp(status = null))
        assertNull(s.current)
        assertTrue(s.readStatus.codecStatusNull)
        assertTrue(s.readStatus.currentNull)
        assertEquals(ApiReadState.NULL, s.readStatus.codecStatusState)
        assertEquals(ApiReadState.NULL, s.readStatus.currentState)
        assertNull(s.readStatus.currentSelectable)
        assertEquals(2000, s.identityDiagnostics.serviceUid)
        assertEquals("ON", s.readStatus.bluetoothState)
        assertEquals("CONNECTED", s.readStatus.a2dpProxyState)
    }

    @Test
    fun codecStatusExceptionIsIsolatedWithStageKey() {
        val s = read(FakeA2dp(statusError = SecurityException("need BLUETOOTH_PRIVILEGED")))
        assertNull(s.current)
        assertFalse(s.readStatus.codecStatusNull)
        assertTrue(s.readStatus.currentNull)
        assertEquals(ApiReadState.ERROR, s.readStatus.codecStatusState)
        assertEquals(ApiReadState.NULL, s.readStatus.currentState)
        assertEquals(
            "SecurityException: need BLUETOOTH_PRIVILEGED",
            s.readStatus.errors["codecStatus"],
        )
        assertNull(s.readStatus.currentSelectable)
    }

    @Test
    fun codecStatusApiMissingIsUnavailableNotError() {
        val s = read(FakeA2dpWithoutCodecStatusApi())
        assertNull(s.current)
        assertFalse(s.readStatus.codecStatusNull)
        assertEquals(ApiReadState.UNAVAILABLE, s.readStatus.codecStatusState)
        assertEquals(ApiReadState.NULL, s.readStatus.currentState)
        assertFalse(s.readStatus.errors.containsKey("codecStatus"))
    }

    @Test
    fun codecStatusInvocationTargetExceptionIsUnwrapped() {
        val s = read(FakeA2dp(statusError = RuntimeException("binder dead")))
        assertEquals("RuntimeException: binder dead", s.readStatus.errors["codecStatus"])
        assertEquals(ApiReadState.ERROR, s.readStatus.codecStatusState)
    }

    @Test
    fun currentNullPreservesSelectable() {
        val status = FakeStatus(
            config = null,
            selectable = listOf(
                FakeConfig(CodecDeviceFixtures.opdCurrent),
                FakeConfig(CodecDeviceFixtures.selectableCaps(
                    CodecDeviceFixtures.opdType, CodecDeviceFixtures.opdCapsMask)),
            ),
        )
        val s = read(FakeA2dp(status = status))
        assertNull(s.current)
        assertTrue(s.readStatus.currentNull)
        assertEquals(ApiReadState.NULL, s.readStatus.currentState)
        assertEquals(2, s.selectable.size)
        assertEquals(CodecDeviceFixtures.opdCurrent, s.selectable[0])
        assertEquals(ApiReadState.OK, s.readStatus.selectableState)
        assertEquals(2, s.readStatus.selectableCount)
        assertNull(s.readStatus.currentSelectable)
    }

    @Test
    fun currentExceptionKeepsSelectableLocalAndIsolatesError() {
        val status = FakeStatus(
            configError = IllegalStateException("oem frozen"),
            local = listOf(FakeConfig(CodecDeviceFixtures.opdCurrent)),
            selectable = listOf(FakeConfig(CodecDeviceFixtures.opdCurrent)),
        )
        val s = read(FakeA2dp(status = status))
        assertNull(s.current)
        assertTrue(s.readStatus.currentNull)
        assertEquals(ApiReadState.ERROR, s.readStatus.currentState)
        assertEquals("IllegalStateException: oem frozen", s.readStatus.errors["current"])
        assertEquals(ApiReadState.OK, s.readStatus.selectableState)
        assertEquals(ApiReadState.OK, s.readStatus.localCapabilitiesState)
        assertEquals(1, s.selectable.size)
        // current 不可读 → 无法尝试 extended，保持 UNAVAILABLE，不产生误导性 NULL
        assertEquals(ApiReadState.UNAVAILABLE, s.identityDiagnostics.extendedTypeStatus)
        assertNull(s.readStatus.currentSelectable)
    }

    @Test
    fun currentFieldConversionFailureStaysInCurrentStage() {
        // getCodecConfig() 正常返回，但 9 字段 getter 之一抛异常：
        // 必须隔离在 current 阶段，不得中断 local/selectable，也不得逃出 readSnapshot
        val status = happyStatus(config = FakeConfigWithBrokenField())
        val s = read(FakeA2dp(status = status))
        assertNull(s.current)
        assertTrue(s.readStatus.currentNull)
        assertEquals(ApiReadState.ERROR, s.readStatus.currentState)
        assertEquals("SecurityException: cs1 denied", s.readStatus.errors["current"])
        assertEquals(ApiReadState.OK, s.readStatus.localCapabilitiesState)
        assertEquals(1, s.localCapabilities.size)
        assertEquals(ApiReadState.OK, s.readStatus.selectableState)
        assertEquals(2, s.selectable.size)
        assertNull(s.readStatus.currentSelectable)
    }

    // ─────────────────────────── local / selectable 各自隔离 ───────────────────────────

    @Test
    fun localNullEmptyErrorAndNormalAreDistinctStates() {
        // null → NULL
        val sNull = read(FakeA2dp(status = happyStatus(local = null)))
        assertEquals(ApiReadState.NULL, sNull.readStatus.localCapabilitiesState)
        assertTrue(sNull.localCapabilities.isEmpty())
        assertEquals(0, sNull.readStatus.localCount)

        // 空 → EMPTY，不与 null 合并
        val sEmpty = read(FakeA2dp(status = happyStatus(local = emptyList())))
        assertEquals(ApiReadState.EMPTY, sEmpty.readStatus.localCapabilitiesState)
        assertTrue(sEmpty.localCapabilities.isEmpty())

        // 异常 → ERROR + 阶段 key
        val sErr = read(FakeA2dp(status = happyStatus().apply {
            localError = SecurityException("local denied")
        }))
        assertEquals(ApiReadState.ERROR, sErr.readStatus.localCapabilitiesState)
        assertEquals("SecurityException: local denied", sErr.readStatus.errors["localCapabilities"])
        // metadata 失败绝不清空已成功 current/selectable
        assertEquals(CodecDeviceFixtures.opdCurrent, sErr.current)
        assertEquals(2, sErr.selectable.size)

        // 正常 → OK，9 字段完整映射
        val sOk = read(FakeA2dp(status = happyStatus()))
        assertEquals(ApiReadState.OK, sOk.readStatus.localCapabilitiesState)
        assertEquals(1, sOk.localCapabilities.size)
        assertEquals(CodecDeviceFixtures.opdCurrent, sOk.localCapabilities[0])
        assertEquals(1, sOk.readStatus.localCount)
    }

    @Test
    fun localApiMissingIsUnavailable() {
        val status = FakeStatusWithoutLocalApi(
            config = FakeConfig(CodecDeviceFixtures.opdCurrent),
            selectable = listOf(FakeConfig(CodecDeviceFixtures.opdCurrent)),
        )
        val s = read(FakeA2dpWithoutSupportedApi(status = status))
        assertEquals(ApiReadState.UNAVAILABLE, s.readStatus.localCapabilitiesState)
        assertTrue(s.localCapabilities.isEmpty())
        assertFalse(s.readStatus.errors.containsKey("localCapabilities"))
    }

    @Test
    fun selectableNullEmptyErrorAndNormalAreDistinctStates() {
        // null → NULL
        val sNull = read(FakeA2dp(status = happyStatus(selectable = null)))
        assertEquals(ApiReadState.NULL, sNull.readStatus.selectableState)
        assertTrue(sNull.selectable.isEmpty())
        assertNull(sNull.readStatus.currentSelectable)

        // 空 → EMPTY
        val sEmpty = read(FakeA2dp(status = happyStatus(selectable = emptyList())))
        assertEquals(ApiReadState.EMPTY, sEmpty.readStatus.selectableState)
        assertTrue(sEmpty.selectable.isEmpty())

        // 异常 → ERROR + 阶段 key
        val sErr = read(FakeA2dp(status = happyStatus().apply {
            selectableError = SecurityException("selectable denied")
        }))
        assertEquals(ApiReadState.ERROR, sErr.readStatus.selectableState)
        assertEquals("SecurityException: selectable denied", sErr.readStatus.errors["selectable"])
        assertEquals(CodecDeviceFixtures.opdCurrent, sErr.current)

        // 正常 → OK，count 与实际一致
        val sOk = read(FakeA2dp(status = happyStatus()))
        assertEquals(ApiReadState.OK, sOk.readStatus.selectableState)
        assertEquals(2, sOk.selectable.size)
        assertEquals(2, sOk.readStatus.selectableCount)
    }

    @Test
    fun selectableNullElementFailsWholeStage() {
        @Suppress("UNCHECKED_CAST")
        val withNullElement = listOf<Any?>(null) as List<Any>
        val s = read(FakeA2dp(status = happyStatus(selectable = withNullElement)))
        assertEquals(ApiReadState.ERROR, s.readStatus.selectableState)
        assertTrue(s.readStatus.errors["selectable"]!!.contains("null element"))
        assertTrue(s.selectable.isEmpty())
    }

    // ─────────────────────────── currentSelectable 交叉检查 ───────────────────────────

    @Test
    fun currentSelectableTrueWhenTypeInSelectable() {
        val s = read(FakeA2dp(status = happyStatus()))
        assertEquals(true, s.readStatus.currentSelectable)
    }

    @Test
    fun currentSelectableFalseWhenTypeAbsentFromSelectable() {
        val status = happyStatus(
            config = FakeConfigWithExtended(CodecDeviceFixtures.nxCurrent),
            selectable = listOf(FakeConfig(CodecDeviceFixtures.selectableCaps(
                CodecDeviceFixtures.opdType, CodecDeviceFixtures.opdCapsMask))),
        )
        val s = read(FakeA2dp(status = status))
        assertEquals(ApiReadState.OK, s.readStatus.selectableState)
        assertEquals(false, s.readStatus.currentSelectable)
    }

    @Test
    fun currentSelectableNullWhenSelectableUnreadable() {
        val sErr = read(FakeA2dp(status = happyStatus().apply {
            selectableError = RuntimeException("x")
        }))
        assertNull(sErr.readStatus.currentSelectable)
        val sNull = read(FakeA2dp(status = happyStatus(selectable = null)))
        assertNull(sNull.readStatus.currentSelectable)
    }

    @Test
    fun localIsNeverMixedIntoCurrentSelectable() {
        // local 含同 type 条目、selectable 为空：交叉检查只看 selectable → false
        val status = happyStatus(
            local = listOf(FakeConfig(CodecDeviceFixtures.opdCurrent)),
            selectable = emptyList(),
        )
        val s = read(FakeA2dp(status = status))
        assertEquals(ApiReadState.EMPTY, s.readStatus.selectableState)
        assertEquals(1, s.localCapabilities.size)
        assertEquals(false, s.readStatus.currentSelectable)
    }

    // ─────────────────────────── extendedIdentity ───────────────────────────

    @Test
    fun extendedHappyPathReadsCodecIdAndName() {
        val status = happyStatus(config = FakeConfigWithExtended(
            CodecDeviceFixtures.opdCurrent,
            extended = FakeCodecType(18, 1000000L, "LHDC V3"),
        ))
        val s = read(FakeA2dp(status = status))
        assertEquals(ExtendedCodecIdentity(1000000L, "LHDC V3"), s.extendedIdentity)
        assertEquals(ApiReadState.OK, s.identityDiagnostics.extendedTypeStatus)
    }

    @Test
    fun extendedNullKeepsIdentityNullButCurrentIntact() {
        val s = read(FakeA2dp(status = happyStatus()))   // FakeConfigWithExtended 默认 extended=null
        assertNull(s.extendedIdentity)
        assertEquals(ApiReadState.NULL, s.identityDiagnostics.extendedTypeStatus)
        assertEquals(CodecDeviceFixtures.opdCurrent, s.current)
        assertEquals(2, s.selectable.size)
    }

    @Test
    fun extendedApiMissingIsUnavailable() {
        // FakeConfig 不带 getExtendedCodecType()
        val status = happyStatus(config = FakeConfig(CodecDeviceFixtures.opdCurrent))
        val s = read(FakeA2dp(status = status))
        assertNull(s.extendedIdentity)
        assertEquals(ApiReadState.UNAVAILABLE, s.identityDiagnostics.extendedTypeStatus)
        assertEquals(CodecDeviceFixtures.opdCurrent, s.current)
    }

    @Test
    fun extendedFailureKeepsCurrentAndSelectableComplete() {
        val status = happyStatus(config = FakeConfigWithExtended(
            CodecDeviceFixtures.opdCurrent,
            extendedError = SecurityException("extended denied"),
        ))
        val s = read(FakeA2dp(status = status))
        assertEquals(ApiReadState.ERROR, s.identityDiagnostics.extendedTypeStatus)
        assertEquals("SecurityException: extended denied", s.readStatus.errors["extendedIdentity"])
        assertEquals(CodecDeviceFixtures.opdCurrent, s.current)
        assertEquals(2, s.selectable.size)
        assertEquals(1, s.localCapabilities.size)
    }

    // ─────────────────────────── supportedTypes ───────────────────────────

    @Test
    fun supportedTypesApiMissingIsUnavailable() {
        val s = read(FakeA2dpWithoutSupportedApi(status = happyStatus()))
        assertEquals(ApiReadState.UNAVAILABLE, s.identityDiagnostics.supportedTypesStatus)
        assertTrue(s.supportedTypes.isEmpty())
        assertTrue(s.identityDiagnostics.errors.isEmpty())
        // supported 失败绝不影响 current/selectable
        assertEquals(CodecDeviceFixtures.opdCurrent, s.current)
        assertEquals(2, s.selectable.size)
    }

    @Test
    fun supportedTypesNullAndEmptyAreDistinct() {
        val sNull = read(FakeA2dp(status = happyStatus(), supported = null))
        assertEquals(ApiReadState.NULL, sNull.identityDiagnostics.supportedTypesStatus)
        assertTrue(sNull.supportedTypes.isEmpty())

        val sEmpty = read(FakeA2dp(status = happyStatus(), supported = emptyList()))
        assertEquals(ApiReadState.EMPTY, sEmpty.identityDiagnostics.supportedTypesStatus)
        assertTrue(sEmpty.supportedTypes.isEmpty())
    }

    @Test
    fun supportedTypesSecurityExceptionIsErrorWithStageKey() {
        val a2dp = FakeA2dp(status = happyStatus(), supportedError = SecurityException("catalog denied"))
        val s = read(a2dp)
        assertEquals(ApiReadState.ERROR, s.identityDiagnostics.supportedTypesStatus)
        assertEquals("SecurityException: catalog denied", s.identityDiagnostics.errors["supportedTypes"])
        assertEquals(CodecDeviceFixtures.opdCurrent, s.current)
    }

    @Test
    fun supportedTypesCatalogUsesNativeCodecType() {
        val a2dp = FakeA2dp(status = happyStatus(), supported = listOf(
            FakeCodecType(18, 1000000L, "LHDC V3"),
            FakeCodecType(8, 2L, "SBC"),
        ))
        val s = read(a2dp)
        assertEquals(ApiReadState.OK, s.identityDiagnostics.supportedTypesStatus)
        assertEquals(
            listOf(
                SupportedCodecType(nativeType = 18, codecId = 1000000L, codecName = "LHDC V3"),
                SupportedCodecType(nativeType = 8, codecId = 2L, codecName = "SBC"),
            ),
            s.supportedTypes,
        )
    }

    @Test
    fun supportedTypesAcceptsNonListCollection() {
        // AOSP getSupportedCodecTypes() 声明返回 Collection：非 List 的合法目录不得误报 ERROR
        val catalog = LinkedHashSet(listOf(FakeCodecType(18, 1000000L, "LHDC V3")))
        val a2dp = FakeA2dpWithCollectionCatalog(status = happyStatus(), catalog = catalog)
        val s = read(a2dp)
        assertEquals(ApiReadState.OK, s.identityDiagnostics.supportedTypesStatus)
        assertEquals(
            listOf(SupportedCodecType(nativeType = 18, codecId = 1000000L, codecName = "LHDC V3")),
            s.supportedTypes,
        )
        assertEquals(CodecDeviceFixtures.opdCurrent, s.current)
    }

    // ─────────────────────────── 错误文本约束 ───────────────────────────

    @Test
    fun errorTextsAreRootCauseTypedAndCappedAt160Chars() {
        val longMsg = "x".repeat(500)
        val status = happyStatus().apply { configError = IllegalStateException(longMsg) }
        val s = read(FakeA2dp(status = status))
        val err = s.readStatus.errors["current"]!!
        assertTrue(err.length <= 160)
        assertTrue(err.startsWith("IllegalStateException"))
    }

    @Test
    fun invocationTargetExceptionCauseChainIsUnwrapped() {
        // fake 方法体内抛出的异常经反射包成 ITE，reader 必须解到根因类型
        val a2dp = FakeA2dp(
            status = happyStatus(),
            supportedError = RuntimeException("wrapped by reflection"),
        )
        val s = read(a2dp)
        assertEquals("RuntimeException: wrapped by reflection", s.identityDiagnostics.errors["supportedTypes"])
    }
}
