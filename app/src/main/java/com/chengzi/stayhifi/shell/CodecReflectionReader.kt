package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.codec.ApiReadState
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.CodecReadStatus
import com.chengzi.stayhifi.codec.CodecStateSnapshot
import com.chengzi.stayhifi.codec.ExtendedCodecIdentity
import com.chengzi.stayhifi.codec.IdentityDiagnostics
import com.chengzi.stayhifi.codec.SupportedCodecType
import java.lang.reflect.InvocationTargetException

/**
 * Task 2：分阶段反射读取与 OEM 失败隔离。
 *
 * Android 类型只以 [Any] 进入，便于纯 JUnit fake；每个阶段独立记录
 * [ApiReadState]（OK / NULL / EMPTY / ERROR / UNAVAILABLE），
 * null/empty/error 不合并，单阶段失败不清空其它阶段的成果。
 * 异常文本为解包 InvocationTargetException 后的根因（`SimpleName: message`），
 * 截断到 [MAX_ERROR_CHARS]；errors map 的 key 为阶段名：
 * `codecStatus` / `current` / `localCapabilities` / `selectable` /
 * `extendedIdentity`（前四者在 [CodecReadStatus.errors]，
 * `supportedTypes` 在 [IdentityDiagnostics.errors]）。
 */

internal data class ReflectedValue<T>(
    val state: ApiReadState, val value: T? = null, val error: String? = null,
)

internal object CodecReflectionReader {

    private const val MAX_ERROR_CHARS = 160

    // ─────────────────────────── 单阶段读取 ───────────────────────────

    /** `BluetoothA2dp.getCodecStatus(device)`；方法不存在记 UNAVAILABLE，null 记 NULL。 */
    fun codecStatus(a2dp: Any, device: Any): ReflectedValue<Any> = readStage {
        findMethod(a2dp, "getCodecStatus", device).invoke(a2dp, device)
    }

    /** `BluetoothA2dp.getSupportedCodecTypes()` 目录；条目读 `getNativeCodecType`（非 getCodecType）、`getCodecId`、`getCodecName`；空目录记 EMPTY。 */
    fun supportedTypes(a2dp: Any): ReflectedValue<List<SupportedCodecType>> {
        val r = readStage {
            val raw = a2dp.javaClass.getMethod("getSupportedCodecTypes").invoke(a2dp) as List<*>?
            raw?.map { entry ->
                val codecType = entry ?: throw IllegalStateException("null element in supportedTypes")
                SupportedCodecType(
                    nativeType = codecType.javaClass.getMethod("getNativeCodecType").invoke(codecType) as Int,
                    codecId = codecType.javaClass.getMethod("getCodecId").invoke(codecType) as Long,
                    codecName = codecType.javaClass.getMethod("getCodecName").invoke(codecType) as String,
                )
            }
        }
        val state = if (r.state == ApiReadState.OK && (r.value?.isEmpty() != false)) {
            ApiReadState.EMPTY
        } else {
            r.state
        }
        return ReflectedValue(state, r.value, r.error)
    }

    // ─────────────────────────── 快照组装 ───────────────────────────

    /**
     * 由已读取的 codecStatus 与 supportedTypes 组装 [CodecStateSnapshot]。
     * local/selectable/extended 在此从 status 逐项读取；任一阶段失败只影响自身状态。
     *
     * @param a2dp 调用上下文保留参数；当前各阶段均从 status/config 读取，未使用。
     */
    fun readSnapshot(
        status: ReflectedValue<Any>, a2dp: Any,
        supportedTypes: ReflectedValue<List<SupportedCodecType>>,
        serviceUid: Int, bluetoothState: String, a2dpProxyState: String,
    ): CodecStateSnapshot {
        val errors = mutableMapOf<String, String>()
        status.error?.let { errors["codecStatus"] = it }

        var current: CodecConfig? = null
        var currentState = ApiReadState.NULL
        var currentRaw: Any? = null
        var selectable: List<CodecConfig> = emptyList()
        var selectableState = ApiReadState.NULL
        var local: List<CodecConfig> = emptyList()
        var localState = ApiReadState.UNAVAILABLE
        var extended: ExtendedCodecIdentity? = null
        var extendedState = ApiReadState.UNAVAILABLE

        if (status.state == ApiReadState.OK && status.value != null) {
            val s = status.value

            val currentR = readStage { s.javaClass.getMethod("getCodecConfig").invoke(s) }
            currentState = currentR.state
            if (currentR.state == ApiReadState.OK && currentR.value != null) {
                currentRaw = currentR.value
                current = configFromReflected(currentR.value)
            } else {
                // codecStatus 可读但 current 不可读：NULL/EMPTY/ERROR/UNAVAILABLE 如实保留
                currentState = currentR.state
                currentR.error?.let { errors["current"] = it }
            }

            localState = readCapabilities(s, "getCodecsLocalCapabilities", "localCapabilities", errors)
                .also { local = it.value ?: emptyList() }.state

            val selectableR = readCapabilities(s, "getCodecsSelectableCapabilities", "selectable", errors)
            selectable = selectableR.value ?: emptyList()
            selectableState = selectableR.state

            // extended 只作辅助证据，仅在 current 本体读到后才尝试；其失败不影响 current/selectable
            if (currentRaw != null) {
                val extR = readExtended(currentRaw)
                extendedState = extR.state
                extended = extR.value
                extR.error?.let { errors["extendedIdentity"] = it }
            }
        }

        // 交叉检查：current 与 selectable 均可读（OK 或 EMPTY）时给 true/false，否则 null。
        // 只对照 selectable，绝不借 localCapabilities 提升判定。
        val currentSelectable: Boolean? = when {
            current != null && selectableState in listOf(ApiReadState.OK, ApiReadState.EMPTY) ->
                selectable.any { it.codecType == current.codecType }
            else -> null
        }

        return CodecStateSnapshot(
            current = current,
            selectable = selectable,
            localCapabilities = local,
            supportedTypes = supportedTypes.value ?: emptyList(),
            extendedIdentity = extended,
            readStatus = CodecReadStatus(
                codecStatusNull = status.state == ApiReadState.NULL,
                currentNull = current == null,
                localCount = local.size,
                selectableCount = selectable.size,
                currentSelectable = currentSelectable,
                bluetoothState = bluetoothState,
                a2dpProxyState = a2dpProxyState,
                codecStatusState = status.state,
                currentState = currentState,
                localCapabilitiesState = localState,
                selectableState = selectableState,
                errors = errors,
            ),
            identityDiagnostics = IdentityDiagnostics(
                supportedTypesStatus = supportedTypes.state,
                extendedTypeStatus = extendedState,
                serviceUid = serviceUid,
                errors = supportedTypes.error?.let { mapOf("supportedTypes" to it) } ?: emptyMap(),
            ),
        )
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /**
     * 执行一个反射读取阶段：返回 null → NULL；返回空集合 → EMPTY（value 仍给出）；
     * NoSuchMethod/ClassNotFound（方法本身不存在）→ UNAVAILABLE；
     * 其余异常（含被 invoke 包成 ITE 的目标异常）→ ERROR，error 为根因文本。
     */
    private inline fun <T> readStage(block: () -> T?): ReflectedValue<T> = try {
        when (val v = block()) {
            null -> ReflectedValue(ApiReadState.NULL)
            else -> ReflectedValue(ApiReadState.OK, v)
        }
    } catch (e: NoSuchMethodException) {
        ReflectedValue(ApiReadState.UNAVAILABLE)
    } catch (e: ClassNotFoundException) {
        ReflectedValue(ApiReadState.UNAVAILABLE)
    } catch (e: InvocationTargetException) {
        ReflectedValue(ApiReadState.ERROR, error = shorten(rootCauseText(e.cause ?: e)))
    } catch (t: Throwable) {
        ReflectedValue(ApiReadState.ERROR, error = shorten(rootCauseText(t)))
    }

    /** local/selectable 列表读取：null/EMPTY/ERROR 各自保留，元素为 null 或字段反射失败则整阶段 ERROR。 */
    private fun readCapabilities(
        status: Any, getter: String, stageKey: String, errors: MutableMap<String, String>,
    ): ReflectedValue<List<CodecConfig>> {
        val r = readStage {
            val raw = status.javaClass.getMethod(getter).invoke(status) as List<*>?
            raw?.map { entry ->
                configFromReflected(
                    entry ?: throw IllegalStateException("null element in $stageKey"),
                )
            }
        }
        val state = when {
            r.state == ApiReadState.OK && (r.value?.isEmpty() != false) -> ApiReadState.EMPTY
            else -> r.state
        }
        r.error?.let { errors[stageKey] = it }
        return ReflectedValue(state, r.value)
    }

    /** `BluetoothCodecConfig.getExtendedCodecType()` → codecId/codecName；extended 对象为 null 记 NULL。 */
    private fun readExtended(config: Any): ReflectedValue<ExtendedCodecIdentity> = readStage {
        val codecType = config.javaClass.getMethod("getExtendedCodecType").invoke(config) ?: return@readStage null
        ExtendedCodecIdentity(
            codecId = codecType.javaClass.getMethod("getCodecId").invoke(codecType) as Long,
            codecName = codecType.javaClass.getMethod("getCodecName").invoke(codecType) as String,
        )
    }

    /** 9 字段反射转换（自 CodecService 移入）；字段反射异常向上抛给阶段读取处理。 */
    internal fun configFromReflected(cfg: Any): CodecConfig = CodecConfig(
        codecType = getInt(cfg, "getCodecType"),
        priority = getInt(cfg, "getCodecPriority"),
        sampleRate = getInt(cfg, "getSampleRate"),
        bitsPerSample = getInt(cfg, "getBitsPerSample"),
        channelMode = getInt(cfg, "getChannelMode"),
        cs1 = getLong(cfg, "getCodecSpecific1"),
        cs2 = getLong(cfg, "getCodecSpecific2"),
        cs3 = getLong(cfg, "getCodecSpecific3"),
        cs4 = getLong(cfg, "getCodecSpecific4"),
    )

    private fun getInt(cfg: Any, getter: String): Int =
        cfg.javaClass.getMethod(getter).invoke(cfg) as Int

    private fun getLong(cfg: Any, getter: String): Long =
        cfg.javaClass.getMethod(getter).invoke(cfg) as Long

    /**
     * 按名查找方法并 invoke 前取值：`Class.getMethod` 要求形参类型精确相等，
     * fake/代理对象的具体类是声明形参的子类型时会误抛 NoSuchMethodException，
     * 故精确查找失败时退化为「形参可赋值」匹配。
     */
    private fun findMethod(target: Any, name: String, vararg args: Any): java.lang.reflect.Method {
        val cls = target.javaClass
        try {
            return cls.getMethod(name, *args.map { it.javaClass }.toTypedArray())
        } catch (e: NoSuchMethodException) {
            for (m in cls.methods) {
                if (m.name != name || m.parameterCount != args.size) continue
                val paramsAssignable = m.parameterTypes.withIndex().all { (i, p) ->
                    p.isAssignableFrom(args[i].javaClass)
                }
                if (paramsAssignable) {
                    m.isAccessible = true
                    return m
                }
            }
            throw e
        }
    }

    private fun rootCauseText(t: Throwable): String {
        var cause = t
        while (cause is InvocationTargetException && cause.cause != null) {
            cause = cause.cause!!
        }
        val message = cause.message
        return if (message.isNullOrEmpty()) cause.javaClass.simpleName
        else "${cause.javaClass.simpleName}: $message"
    }

    private fun shorten(text: String): String =
        if (text.length > MAX_ERROR_CHARS) text.take(MAX_ERROR_CHARS) else text
}
