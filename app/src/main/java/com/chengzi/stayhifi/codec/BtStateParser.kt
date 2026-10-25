package com.chengzi.stayhifi.codec

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

    /** Task 46：实际传输行三态结果；默认值表示「本机暂不支持抓取」。 */
    data class RealtimeDisplay(
        val bitrateKbps: Int? = null,
        val qualityMode: String? = null,
        val notStreaming: Boolean = false,
    )

    private val SECTION_HEADER = Regex("^A2DP\\s+(.+?)\\s+State:")

    /**
     * 按首页读回的 current + selectable 解析：ColorOS vendor 段必须复用经能力佐证的
     * LHDC 名称，不能退回单参 Masks.codecName 映射得到的 vendor(N) 去匹配段落。
     */
    fun parse(dump: String, current: CodecConfig, selectable: List<CodecConfig>): BtState? =
        parse(dump, Masks.codecName(current.codecType, Masks.capsRateMask(current.codecType, selectable)))

    /**
     * 从 dumpsys 文本中找 [codecName]（如 "LHDC"/"LDAC"，与 Masks.codecName 输出一致）对应段落，
     * 提取 quality mode 与 transmission bitrate；段落不存在 → null。
     * 段名匹配用「去空格/连字符后双向 contains」：覆盖 "LHDC V5" 这类带后缀的段名，
     * 也兼容 "aptX HD" 与 codecName "aptX-HD" 的写法差异；
     * Task 44 反向包含兜底：codecName 为 "LHDC V5" 时旧栈段头 "LHDC"（无后缀）仍命中。
     */
    fun parse(dump: String, codecName: String): BtState? {
        val wanted = normalize(codecName)
        if (wanted.isEmpty()) return null
        var inSection = false
        var found = false
        // Task 44：命中段落的段头原始名（如 "LHDC V5"），带回给 UI 精确展示
        var sectionName: String? = null
        var qualityMode: String? = null
        var bitrateKbps: Int? = null
        var rawBitrate: Long? = null
        var notStreaming = false
        for (raw in dump.lineSequence()) {
            val line = raw.trim()
            val header = SECTION_HEADER.find(line)
            if (header != null) {
                val norm = normalize(header.groupValues[1])
                // Task 44：双向 contains——codecName 扩充为 "LHDC V5" 后，
                // 旧栈段头 "A2DP LHDC State:"（无后缀）也要能命中（反向包含兜底）
                inSection = norm.contains(wanted) || wanted.contains(norm)
                if (inSection) {
                    found = true
                    sectionName = header.groupValues[1].trim()
                }
                continue
            }
            if (!inSection) continue
            if (line.contains("quality mode", ignoreCase = true)) {
                val v = line.substringAfterLast(':').trim()
                if (v.isNotEmpty()) qualityMode = v
            } else if (line.contains("transmission bitrate", ignoreCase = true)) {
                // 非数字值（如 AAC 的 "bitrate mode : Constant" 不匹配本分支）跳过；
                // 数字非法也跳过，不污染已解析到的值
                val v = line.substringAfterLast(':').trim().toLongOrNull() ?: continue
                rawBitrate = v
                // Task 44：≤0（实证 -1）= 栈明确回报未在流式，单独标记
                notStreaming = v <= 0
                bitrateKbps = normalizeBitrate(v)
            }
        }
        if (!found) return null
        return BtState(sectionName ?: codecName, qualityMode, bitrateKbps, notStreaming, rawBitrate)
    }

    /**
     * Task 46：isMusicActive 驱动的三态决策。未播放立即返回「未在播放」，不使用粘性码率；
     * 播放中保留栈返回值；栈不暴露与栈明确回报 -1 分别映射为「暂不支持抓取」和「未在播放」。
     */
    fun realtimeDisplay(isMusicActive: Boolean, state: BtState?): RealtimeDisplay = when {
        !isMusicActive -> RealtimeDisplay(notStreaming = true)
        state == null -> RealtimeDisplay()
        state.notStreaming -> RealtimeDisplay(notStreaming = true)
        else -> RealtimeDisplay(state.bitrateKbps, state.qualityMode)
    }

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
    private fun normalizeBitrate(raw: Long): Int? = when {
        raw <= 0 -> null
        raw > 10_000 -> (raw / 1000).toInt()
        else -> raw.toInt()
    }

    private fun normalize(s: String): String =
        s.replace(" ", "").replace("-", "").uppercase()
}
