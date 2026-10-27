package com.chengzi.stayhifi.codec

/** dumpsys 实况段落的裁决结果；ambiguous 不携带任何猜测出来的状态。 */
enum class BtStateParseStatus { NO_SECTION, AMBIGUOUS_SECTION, NOT_STREAMING, NO_BITRATE, AVAILABLE }

data class BtStateParseResult(
    val status: BtStateParseStatus,
    val state: BtStateParser.BtState? = null,
    val candidateSections: List<String> = emptyList(),
)

/**
 * Task 43：解析 dumpsys bluetooth_manager 中 "A2DP <codec> State:" 段落的传输层实况，
 * 给首页第六格「实际传输」供数。纯函数、无 Android 依赖，单测覆盖（BtStateParserTest）。
 *
 * 实证样本（iQOO 15 / OriginOS 7，2026-10-23 真机取证）：
 *   A2DP LHDC V5 State:
 *     LHDC quality mode            : ABR
 *     LHDC transmission bitrate (Kbps) :400000   ← 字段名标错了，值实为 bps，400000=400kbps
 *     Packet counts (expected/dropped) : 0 / 8077
 *
 * 已知边界（诚实原则）：该段落仅 vivo 栈实证可读；其他厂商栈没有此段落或格式不同 → 返回
 * null，UI 降级显示「—」。bitrate = -1 表示未在流式传输（实证于 LDAC 段）。
 * Task 46：vivo LHDC 的 bitrate 是粘性值（停播仍报 400000），是否播放改由
 * AudioManager.isMusicActive 即时判定；LDAC 的 -1 路径保留兼容。
 */
object BtStateParser {

    /**
     * 一段传输层实况：qualityMode 如 "ABR"/"HIGH"；bitrateKbps 归一化后的 kbps，null=未流式/读不到。
     * Task 44：codec 为 dumpsys 段落头原始名（如 "LHDC V5"），比 Masks.codecName 映射精确，
     * 供首页编码器格展示；拿不到段名时回落为传入的 codecName。
     * Task 44：notStreaming=true 表示栈明确回报未在流式（bitrate ≤ 0，实证 -1），
     * 与「字段缺失/栈不暴露」（bitrateKbps=null 且 notStreaming=false）区分——空态三分的关键。
     */
    data class BtState(
        val codec: String,
        val qualityMode: String?,
        val bitrateKbps: Int?,
        val notStreaming: Boolean = false,
        val rawBitrate: Long? = null,
    )

    private val SECTION_HEADER = Regex("^A2DP\\s+(.+?)\\s+State:")

    private data class RawSection(
        val codec: String,
        val lines: MutableList<String> = mutableListOf(),
    )

    private data class ParsedSection(val state: BtState) {
        val status: BtStateParseStatus
            get() = when {
                state.bitrateKbps != null -> BtStateParseStatus.AVAILABLE
                state.notStreaming -> BtStateParseStatus.NOT_STREAMING
                else -> BtStateParseStatus.NO_BITRATE
            }

        fun result() = BtStateParseResult(status, state)
    }

    /**
     * 按首页读回的 current + selectable 解析：ColorOS vendor 段必须复用经能力佐证的
     * LHDC 名称，不能退回单参 Masks.codecName 映射得到的 vendor(N) 去匹配段落。
     */
    fun parse(dump: String, current: CodecConfig, selectable: List<CodecConfig>): BtState? =
        parse(dump, Masks.codecName(current.codecType, Masks.capsRateMask(current.codecType, selectable)))

    /** 旧字符串入口仅作兼容包装；新调用方应传入已裁决的 [CodecIdentity]。 */
    fun parse(dump: String, codecName: String): BtState? = parseResult(
        dump,
        CodecIdentity(
            nativeType = -1,
            family = codecFamilyOf(codecName),
            displayName = codecName,
            source = CodecIdentitySource.UNKNOWN,
        ),
    ).state

    /** 先拆分并逐段提取，再在 normalized 精确名、同族泛称或旧无版本段之间裁决。 */
    fun parseResult(dump: String, identity: CodecIdentity): BtStateParseResult {
        val wanted = normalize(identity.displayName)
        if (wanted.isEmpty()) return BtStateParseResult(BtStateParseStatus.NO_SECTION)

        val sections = splitSections(dump).map(::parseSection)
        val exact = sections.filter { normalize(it.state.codec) == wanted }
        if (exact.isNotEmpty()) return selectExact(exact)
        if (identity.family == CodecFamily.UNKNOWN) {
            return BtStateParseResult(BtStateParseStatus.NO_SECTION)
        }

        val genericIdentity = wanted == normalize(identity.family.name)
        val candidates = if (genericIdentity) {
            sections.filter { sameFamily(it.state.codec, identity.family) }
        } else {
            // 带版本身份只能回退到旧无版本段，绝不能借用其他版本的字段。
            sections.filter { normalize(it.state.codec) == normalize(identity.family.name) }
        }
        return if (genericIdentity) selectGeneric(candidates) else selectLegacy(candidates)
    }

    private fun splitSections(dump: String): List<RawSection> {
        val sections = mutableListOf<RawSection>()
        var current: RawSection? = null
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val header = SECTION_HEADER.find(line)
            if (header != null) {
                current = RawSection(header.groupValues[1].trim()).also(sections::add)
            } else {
                current?.lines?.add(line)
            }
        }
        return sections
    }

    private fun parseSection(section: RawSection): ParsedSection {
        var qualityMode: String? = null
        var bitrateKbps: Int? = null
        var rawBitrate: Long? = null
        var notStreaming = false
        for (line in section.lines) {
            if (line.contains("quality mode", ignoreCase = true)) {
                val value = line.substringAfterLast(':').trim()
                if (value.isNotEmpty()) qualityMode = value
            } else if (line.contains("transmission bitrate", ignoreCase = true)) {
                // 非数字值不覆盖本段已经读到的数值，也绝不会进入其他段。
                val value = line.substringAfterLast(':').trim().toLongOrNull() ?: continue
                rawBitrate = value
                notStreaming = value <= 0
                bitrateKbps = normalizeBitrate(value)
            }
        }
        return ParsedSection(
            BtState(section.codec, qualityMode, bitrateKbps, notStreaming, rawBitrate),
        )
    }

    private fun selectExact(candidates: List<ParsedSection>): BtStateParseResult {
        val first = candidates.first()
        return if (candidates.size == 1 || candidates.all { it.state == first.state }) {
            first.result()
        } else {
            ambiguous(candidates)
        }
    }

    private fun selectGeneric(candidates: List<ParsedSection>): BtStateParseResult = when {
        candidates.isEmpty() -> BtStateParseResult(BtStateParseStatus.NO_SECTION)
        candidates.size == 1 -> candidates.single().result()
        else -> {
            val available = candidates.filter {
                it.status == BtStateParseStatus.AVAILABLE && (it.state.bitrateKbps ?: 0) > 0
            }
            if (available.size == 1) available.single().result() else ambiguous(candidates)
        }
    }

    private fun selectLegacy(candidates: List<ParsedSection>): BtStateParseResult = when {
        candidates.isEmpty() -> BtStateParseResult(BtStateParseStatus.NO_SECTION)
        candidates.size == 1 -> candidates.single().result()
        else -> ambiguous(candidates)
    }

    private fun ambiguous(candidates: List<ParsedSection>) = BtStateParseResult(
        status = BtStateParseStatus.AMBIGUOUS_SECTION,
        candidateSections = candidates.map { it.state.codec }.take(4),
    )

    private fun sameFamily(sectionName: String, family: CodecFamily): Boolean {
        if (codecFamilyOf(sectionName) == family) return true
        // codecFamilyOf 接受空格版本；这里同时保留旧 parser 对 LHDC-V3/LHDCV3 的兼容。
        return family == CodecFamily.LHDC && normalize(sectionName).startsWith("LHDCV")
    }

    /**
     * Task 7：isMusicActive 驱动的四态裁决，首页实际传输唯一决策入口。
     * 判定顺序固定：
     *   music=false                         → NOT_PLAYING（即时主判定，不用粘性码率）
     *   music=true + AVAILABLE 且有合法码率 → AVAILABLE（唯一携带 bitrate/quality 的态）
     *   music=true + NOT_STREAMING          → STACK_REPORTS_IDLE（栈回报 idle，与 isMusicActive 矛盾 → 暂无法确认）
     *   其余（无段/歧义/无码率字段）          → NO_DATA
     */
    fun realtimeDisplay(isMusicActive: Boolean, result: BtStateParseResult): RealtimeState {
        val state = result.state
        return when {
            !isMusicActive -> RealtimeState(RealtimeDisplay.NOT_PLAYING)
            // normalizeBitrate 已保证非 null 即 >0（raw≤0 归一为 null）
            result.status == BtStateParseStatus.AVAILABLE && state?.bitrateKbps != null ->
                RealtimeState(RealtimeDisplay.AVAILABLE, state.bitrateKbps, state.qualityMode)
            result.status == BtStateParseStatus.NOT_STREAMING ->
                RealtimeState(RealtimeDisplay.STACK_REPORTS_IDLE)
            else -> RealtimeState(RealtimeDisplay.NO_DATA)
        }
    }

    /**
     * 旧 state 参数入口（源码兼容，Task 44/46 调用方/测试）：把裸 BtState 合成等价
     * [BtStateParseResult] 后走同一四态裁决，语义与 result 入口完全一致
     * （旧 LDAC -1 的「未在播放」预期有意改为 STACK_REPORTS_IDLE，其余不变）。
     */
    fun realtimeDisplay(isMusicActive: Boolean, state: BtState?): RealtimeState = realtimeDisplay(
        isMusicActive,
        when {
            state == null -> BtStateParseResult(BtStateParseStatus.NO_SECTION)
            state.notStreaming -> BtStateParseResult(BtStateParseStatus.NOT_STREAMING, state)
            state.bitrateKbps != null -> BtStateParseResult(BtStateParseStatus.AVAILABLE, state)
            else -> BtStateParseResult(BtStateParseStatus.NO_BITRATE, state)
        },
    )

    /**
     * 首页第六格显示文案：读不到/未流式 → null（UI 显示「—」，cell 占位不动）。
     * quality mode 含 ABR 时带后缀（如「400k · ABR」——ABR=自适应调度，是
     * 「设置层拉满但传输层没拉满」的关键证据），否则纯码率。
     */
    fun formatDisplay(state: BtState): String? {
        val kbps = state.bitrateKbps ?: return null
        val qm = state.qualityMode
        return if (qm != null && qm.contains("ABR", ignoreCase = true)) {
            "${kbps}k · $qm"
        } else {
            "${kbps}k"
        }
    }

    /**
     * 归一化为 kbps：值 >10000 判定为 bps 单位 ÷1000（vivo 栈字段名误标 "(Kbps)"，
     * 实测 400000 对应 400kbps）；≤0（如 -1）表示未在流式传输 → null；其余按 kbps 原样。
     */
    private fun normalizeBitrate(raw: Long): Int? {
        if (raw <= 0) return null
        val kbps = if (raw > 10_000) raw / 1000 else raw
        return kbps.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun normalize(s: String): String =
        s.replace(" ", "").replace("-", "").uppercase()
}
