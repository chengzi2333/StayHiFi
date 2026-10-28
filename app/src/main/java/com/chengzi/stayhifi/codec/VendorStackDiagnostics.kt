package com.chengzi.stayhifi.codec

import com.chengzi.stayhifi.BuildConfig
import java.security.MessageDigest

/** 通用厂商栈只读诊断：纯函数原因评估、状态跃迁签名与紧凑有界格式。不做任何 binder/系统调用。 */

/** 留证触发：连接、编码器更新、播放中、手动复制。 */
enum class DiagnosticTrigger { CONNECTION, CODEC_UPDATE, PLAYING, MANUAL }

/** 留证来源：首页轮询、自动拉满、手动复制。 */
enum class DiagnosticSource { HOME, APPLY, MANUAL }

/** 单次留证的调用方上下文；dumpClues 只接收调用方本轮本来就读到的 dump 线索。 */
data class VendorDiagnosticContext(
    val mac: String,
    val trigger: DiagnosticTrigger,
    val source: DiagnosticSource,
    val timestampMillis: Long,
    val providerKind: String,
    val device: DiagnosticDeviceMetadata,
    val dumpClues: List<String> = emptyList(),
)

/** 格式化后的一条诊断事件；text 为写入 LogStore 的单行内容。 */
data class VendorStackDiagnosticEvent(
    val schemaVersion: Int,
    val signature: String,
    val snapshotSeq: Long,
    val suppressedCount: Int,
    val truncatedCount: Int,
    val text: String,
)

object VendorStackDiagnostics {

    const val SCHEMA_VERSION = 1

    // ---- 预算（Global Constraints §诊断预算）----
    const val MAX_TOTAL_CHARS = 12_000
    const val MAX_LOCAL_ENTRIES = 8
    const val MAX_SELECTABLE_ENTRIES = 16
    const val MAX_SUPPORTED_TYPES = 32
    const val MAX_DUMP_CLUES = 24
    const val MAX_CLUE_CHARS = 240
    const val MAX_SYSTEM_TOSTRING_CHARS = 300
    const val MAX_ROOT_CAUSE_CHARS = 240
    const val MAX_ROOT_CAUSE_DEPTH = 3

    // ---- 原因 token：相互可区分，不得把 null、空列表、异常合成同一文案 ----
    const val REASON_CODEC_STATUS_NULL = "codecStatusNull"
    const val REASON_CURRENT_NULL = "currentNull"
    const val REASON_LOCAL_CAPABILITIES_EMPTY = "localCapabilitiesEmpty"
    const val REASON_SELECTABLE_EMPTY = "selectableEmpty"
    const val REASON_CURRENT_CODEC_MISSING = "currentCodecMissing"
    const val REASON_CURRENT_RATE_NOT_SINGLE_BIT = "currentRateNotSingleBit"
    const val REASON_CURRENT_BITS_NOT_SINGLE_BIT = "currentBitsNotSingleBit"
    const val REASON_BLUETOOTH_OFF = "bluetoothOff"
    const val REASON_A2DP_PROXY_DISCONNECTED = "a2dpProxyDisconnected"
    const val REASON_SUPPORTED_TYPES_UNAVAILABLE = "supportedTypesUnavailable"
    const val REASON_SUPPORTED_TYPES_EMPTY = "supportedTypesEmpty"
    const val REASON_SUPPORTED_TYPES_ERROR = "supportedTypesError"
    const val REASON_EXTENDED_IDENTITY_ERROR = "extendedIdentityError"
    const val REASON_IDENTITY_SUPPORTED_CONFLICT = "identitySupportedTypeConflict"
    const val REASON_IDENTITY_SUPPORTED_INVALID = "identitySupportedTypeInvalid"
    const val REASON_IDENTITY_SYSTEM_LEGACY_CONFLICT = "identitySystemLegacyConflict"
    const val REASON_IDENTITY_EXTENDED_INVALID = "identityExtendedInvalid"

    private val RATE_BIT_NAMES = listOf(
        1 to "44.1kHz", 2 to "48kHz", 4 to "88.2kHz",
        8 to "96kHz", 16 to "176.4kHz", 32 to "192kHz",
    )
    private val BITS_BIT_NAMES = listOf(1 to "16bit", 2 to "24bit", 4 to "32bit")
    private val CHANNEL_BIT_NAMES = listOf(1 to "MONO", 2 to "STEREO")
    private const val UNKNOWN_TOKEN = "unknown"
    private const val NONE_TOKEN = "none"

    /** 逐 bit 解码采样率掩码；无法识别的位保留 `unknown`（掩码 0 返回空表）。 */
    fun decodeSampleRates(mask: Int): List<String> =
        decodeBits(mask, RATE_BIT_NAMES)

    /** 逐 bit 解码采样位深掩码；bits=3 → 16/24，未知位保留 `unknown`。 */
    fun decodeBitDepths(mask: Int): List<String> =
        decodeBits(mask, BITS_BIT_NAMES)

    /** 逐 bit 解码声道掩码；AOSP 仅 MONO=1/STEREO=2，其余位保留 `unknown`。 */
    fun decodeChannelModes(mask: Int): List<String> =
        decodeBits(mask, CHANNEL_BIT_NAMES)

    private fun decodeBits(mask: Int, names: List<Pair<Int, String>>): List<String> {
        val out = names.filter { mask and it.first != 0 }.map { it.second }.toMutableList()
        val knownMask = names.fold(0) { acc, entry -> acc or entry.first }
        if (mask and knownMask.inv() != 0) out += UNKNOWN_TOKEN
        return out
    }

    /**
     * 通用原因评估（设计 §9.1）：即使 codecType 落在旧"已知"表内，下列状态也各自给出
     * 可区分 reason。标准 SBC/AAC 正常快照没有任何 reason，不因标准身份被排除或误报。
     * 只消费计划 A 的 snapshot 字段与纯 Kotlin resolver，无任何 aptX 专属分支。
     */
    fun reasons(snapshot: CodecStateSnapshot): Set<String> {
        val out = sortedSetOf<String>()
        val readStatus = snapshot.readStatus
        val current = snapshot.current

        if (readStatus.codecStatusNull) out += REASON_CODEC_STATUS_NULL
        if (current == null) {
            out += REASON_CURRENT_NULL
        } else {
            // current rate/bits 必须逐位明确；0、多比特叠加与未知位都不可写。
            if (decodeSampleRates(current.sampleRate).size != 1) out += REASON_CURRENT_RATE_NOT_SINGLE_BIT
            if (decodeBitDepths(current.bitsPerSample).size != 1) out += REASON_CURRENT_BITS_NOT_SINGLE_BIT
            // 身份冲突（supportedTypes 组内冲突/不可信、与 legacy 映射冲突、extended 不可信）。
            CodecIdentityResolver.resolve(snapshot)?.issues?.forEach { issue ->
                out += when (issue) {
                    CodecIdentityIssue.SUPPORTED_TYPE_CONFLICT -> REASON_IDENTITY_SUPPORTED_CONFLICT
                    CodecIdentityIssue.SUPPORTED_TYPE_INVALID -> REASON_IDENTITY_SUPPORTED_INVALID
                    CodecIdentityIssue.SYSTEM_LEGACY_CONFLICT -> REASON_IDENTITY_SYSTEM_LEGACY_CONFLICT
                    CodecIdentityIssue.EXTENDED_INVALID -> REASON_IDENTITY_EXTENDED_INVALID
                }
            }
        }
        if (snapshot.localCapabilities.isEmpty()) out += REASON_LOCAL_CAPABILITIES_EMPTY
        if (snapshot.selectable.isEmpty()) out += REASON_SELECTABLE_EMPTY
        if (current != null &&
            snapshot.localCapabilities.none { it.codecType == current.codecType } &&
            snapshot.selectable.none { it.codecType == current.codecType }
        ) {
            out += REASON_CURRENT_CODEC_MISSING
        }
        // 蓝牙/A2DP 代理明确异常才留证；UNKNOWN 表示源不可用，不作为异常。
        if (readStatus.bluetoothState == "OFF") out += REASON_BLUETOOTH_OFF
        if (readStatus.a2dpProxyState == "DISCONNECTED") out += REASON_A2DP_PROXY_DISCONNECTED
        when (snapshot.identityDiagnostics.supportedTypesStatus) {
            ApiReadState.UNAVAILABLE -> out += REASON_SUPPORTED_TYPES_UNAVAILABLE
            ApiReadState.EMPTY -> out += REASON_SUPPORTED_TYPES_EMPTY
            ApiReadState.ERROR -> out += REASON_SUPPORTED_TYPES_ERROR
            else -> Unit
        }
        if (snapshot.identityDiagnostics.extendedTypeStatus == ApiReadState.ERROR) {
            out += REASON_EXTENDED_IDENTITY_ERROR
        }
        return out
    }

    /**
     * 状态跃迁签名：mac + current + 完整未截断 local/selectable（SHA-256）+ readStatus
     * + 排序后 reason。空→非空、null→有值、以及发生在截断渲染边界之外的变化都会得到
     * 不同签名，从而留下跃迁证据。
     */
    fun signature(snapshot: CodecStateSnapshot, mac: String, reasons: Set<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun upd(s: String) = md.update(s.toByteArray(Charsets.UTF_8))
        upd(mac); upd("\u0000")
        upd(snapshot.current?.toJson() ?: "null"); upd("\u0000")
        snapshot.localCapabilities.forEach { upd(it.toJson()); upd("\u0001") }
        upd("\u0000")
        snapshot.selectable.forEach { upd(it.toJson()); upd("\u0001") }
        upd("\u0000")
        upd(renderReadStatus(snapshot.readStatus)); upd("\u0000")
        reasons.sorted().forEach { upd(it); upd("\u0001") }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 失败事件签名：同一 mac/stage/rootCause 链只留证一次，重复被抑制。 */
    fun failureSignature(mac: String, stage: String, rootCauseChain: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$mac\u0000$stage\u0000${rootCauseChain.joinToString("\u0001")}".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 紧凑有界格式（schemaVersion=1）。预算内优先保留 current 与 readStatus 的完整原文，
     * 再按 local(8)/selectable(16)/supportedTypes(32)/dumpClues(24) 装完整条目；
     * 超出条数或总字符（12000）预算的遗漏全部计入 truncatedCount，不静默丢弃。
     */
    fun format(
        snapshot: CodecStateSnapshot,
        context: VendorDiagnosticContext,
        snapshotSeq: Long,
        suppressedCount: Int,
    ): VendorStackDiagnosticEvent {
        val reasons = reasons(snapshot)
        val signature = signature(snapshot, context.mac, reasons)
        val identityDiag = snapshot.identityDiagnostics
        val core = buildString {
            append("VendorStackDiag v=").append(SCHEMA_VERSION)
            append(" app=").append(BuildConfig.VERSION_NAME)
            append(" seq=").append(snapshotSeq)
            append(" trigger=").append(triggerToken(context.trigger))
            append(" source=").append(context.source.name.lowercase())
            append(" ts=").append(context.timestampMillis)
            append(" mac=").append(context.mac)
            append(" provider=").append(context.providerKind)
            append(" suppressedCount=").append(suppressedCount)
            append(" device=").append(renderDevice(context.device))
            append(" serviceUid=").append(identityDiag.serviceUid)
            append(" readStatus=").append(renderReadStatus(snapshot.readStatus))
            append(" identity=").append(renderIdentity(snapshot))
            append(" current=").append(snapshot.current?.let { renderConfig(it) } ?: "null")
            append(" reasons=[").append(reasons.joinToString(",")).append(']')
            append(" identityDiag={supportedTypesStatus=").append(identityDiag.supportedTypesStatus)
            append(" extendedTypeStatus=").append(identityDiag.extendedTypeStatus)
            append(" errors=").append(renderErrors(identityDiag.errors)).append('}')
            append(" extended=").append(
                snapshot.extendedIdentity
                    ?.let { "{codecId=${it.codecId} codecName=${it.codecName}}" } ?: "null",
            )
        }
        var truncated = 0
        val sb = StringBuilder(core)
        // 总字符预算预留 truncatedCount 后缀空间。
        val budget = MAX_TOTAL_CHARS - 32
        fun addSection(name: String, entries: List<String>) {
            if (entries.isEmpty()) {
                sb.append(' ').append(name).append("=[]")
                return
            }
            sb.append(' ').append(name).append("=[")
            var first = true
            for (entry in entries) {
                val piece = if (first) entry else ",$entry"
                if (sb.length + piece.length + 1 > budget) {
                    truncated += 1
                    continue
                }
                sb.append(piece)
                first = false
            }
            sb.append(']')
        }

        val localShown = snapshot.localCapabilities.take(MAX_LOCAL_ENTRIES)
        truncated += snapshot.localCapabilities.size - localShown.size
        addSection("local", localShown.map { renderConfig(it) })

        val selectableShown = snapshot.selectable.take(MAX_SELECTABLE_ENTRIES)
        truncated += snapshot.selectable.size - selectableShown.size
        addSection("selectable", selectableShown.map { renderConfig(it) })

        val supportedShown = snapshot.supportedTypes.take(MAX_SUPPORTED_TYPES)
        truncated += snapshot.supportedTypes.size - supportedShown.size
        addSection("supportedTypes", supportedShown.map { renderSupportedType(it) })

        // 系统 toString 交叉证据（identity 相关），每条截断 300 字符。
        val systemStrings = buildList {
            snapshot.extendedIdentity?.let { add(truncate(it.toString(), MAX_SYSTEM_TOSTRING_CHARS)) }
            identityDiag.errors.forEach { (k, v) -> add(truncate("$k=$v", MAX_SYSTEM_TOSTRING_CHARS)) }
        }
        addSection("systemToString", systemStrings)

        val clues = context.dumpClues.take(MAX_DUMP_CLUES).map { truncate(it, MAX_CLUE_CHARS) }
        truncated += context.dumpClues.size - clues.size
        addSection("dumpClues", clues)

        sb.append(" truncatedCount=").append(truncated)
        val text = sb.toString()
        return VendorStackDiagnosticEvent(
            schemaVersion = SCHEMA_VERSION,
            signature = signature,
            snapshotSeq = snapshotSeq,
            suppressedCount = suppressedCount,
            truncatedCount = truncated,
            text = text,
        )
    }

    /**
     * 分阶段 root cause 解包：最多 [MAX_ROOT_CAUSE_DEPTH] 层，每层截断
     * [MAX_ROOT_CAUSE_CHARS] 字符；cause 自引用等环由层数上限自然截断。
     */
    fun rootCauseChain(error: Throwable): List<String> {
        val out = ArrayList<String>(MAX_ROOT_CAUSE_DEPTH)
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < MAX_ROOT_CAUSE_DEPTH) {
            out += truncate("${cur.javaClass.simpleName}: ${cur.message}", MAX_ROOT_CAUSE_CHARS)
            cur = cur.cause
            depth++
        }
        return out
    }

    /** 触发/来源的 wire 文案：connection/codec-update/playing/manual 与 home/apply/manual。 */
    fun triggerToken(trigger: DiagnosticTrigger): String =
        trigger.name.lowercase().replace('_', '-')

    fun renderDevice(device: DiagnosticDeviceMetadata): String =
        "{model=${device.marketModel} android=${device.androidRelease} sdk=${device.sdkInt} " +
            "display=${device.buildDisplay} manufacturer=${device.manufacturer} " +
            "hardware=${device.hardware} board=${device.board} " +
            "soc=${device.socManufacturer}/${device.socModel}}"

    private fun renderReadStatus(rs: CodecReadStatus): String =
        "{codecStatusNull=${rs.codecStatusNull} currentNull=${rs.currentNull} " +
            "localCount=${rs.localCount} selectableCount=${rs.selectableCount} " +
            "currentSelectable=${rs.currentSelectable} bluetooth=${rs.bluetoothState} " +
            "a2dp=${rs.a2dpProxyState} codecStatus=${rs.codecStatusState} " +
            "current=${rs.currentState} localCapabilities=${rs.localCapabilitiesState} " +
            "selectable=${rs.selectableState} errors=${renderErrors(rs.errors)}}"

    private fun renderIdentity(snapshot: CodecStateSnapshot): String {
        val resolution = CodecIdentityResolver.resolve(snapshot) ?: return "null"
        val identity = resolution.identity
        return "{name=${identity.displayName} family=${identity.family} " +
            "source=${identity.source} codecId=${identity.codecId} " +
            "nativeType=${identity.nativeType} issues=[" +
            resolution.issues.joinToString(",") { it.name } + "]}"
    }

    /** 单条 9 字段原始配置：rate/bits/channel 带 hex 与逐 bit 解码，cs1~cs4 十进制+hex 并存。 */
    internal fun renderConfig(c: CodecConfig): String = buildString {
        append("c{t=").append(c.codecType)
        append(" p=").append(c.priority)
        append(" r=").append(c.sampleRate).append("(0x").append(hex(c.sampleRate)).append(")[")
        append(decodeSampleRates(c.sampleRate).joinToString("/").ifEmpty { NONE_TOKEN }).append(']')
        append(" b=").append(c.bitsPerSample).append("(0x").append(hex(c.bitsPerSample)).append(")[")
        append(decodeBitDepths(c.bitsPerSample).joinToString("/").ifEmpty { NONE_TOKEN }).append(']')
        append(" ch=").append(c.channelMode).append("(0x").append(hex(c.channelMode)).append(")[")
        append(decodeChannelModes(c.channelMode).joinToString("/").ifEmpty { NONE_TOKEN }).append(']')
        append(" cs1=").append(c.cs1).append("(0x").append(hex(c.cs1)).append(')')
        append(" cs2=").append(c.cs2).append("(0x").append(hex(c.cs2)).append(')')
        append(" cs3=").append(c.cs3).append("(0x").append(hex(c.cs3)).append(')')
        append(" cs4=").append(c.cs4).append("(0x").append(hex(c.cs4)).append(')')
        append("}")
    }

    private fun renderSupportedType(t: SupportedCodecType): String =
        "s{t=${t.nativeType} id=${t.codecId} name=${t.codecName}}"

    private fun renderErrors(errors: Map<String, String>): String =
        errors.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "$k=${truncate(v, MAX_SYSTEM_TOSTRING_CHARS)}"
        }

    internal fun truncate(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.take(maxChars - 1) + "…"

    private fun hex(value: Int): String = value.toString(16).uppercase()

    private fun hex(value: Long): String = value.toString(16).uppercase()
}
