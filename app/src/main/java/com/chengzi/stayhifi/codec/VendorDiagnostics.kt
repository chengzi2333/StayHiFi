package com.chengzi.stayhifi.codec

/** 轻量厂商诊断：仍走现有 LogStore，不引入持久化或额外采集。 */
object VendorDiagnostics {

    /** 未识别 vendor 保持原诊断；已佐证的 vendor LHDC 也需留 cs1/实况证据供后续回填。 */
    fun shouldLog(codecType: Int, capsRateMask: Int): Boolean {
        val codecName = Masks.codecName(codecType, capsRateMask)
        return codecName.startsWith("vendor(") ||
            (Masks.isVendorLhdcRange(codecType) && codecName == "LHDC")
    }

    /** 按段落、流式状态与码率字段分类本轮证据。 */
    fun sampleKind(state: BtStateParser.BtState?): String = when {
        state == null -> "no-section"
        state.notStreaming -> "not-streaming"
        state.bitrateKbps == null -> "no-bitrate"
        else -> "available"
    }

    /** 只保留排查所需字段，避免把数百行 dumpsys 全倒进 200 行日志缓冲。 */
    fun evidence(state: BtStateParser.BtState): String =
        "段=${state.codec} qualityMode=${state.qualityMode ?: "—"} " +
            "rawBitrate=${state.rawBitrate ?: "—"} " +
            "bitrateKbps=${state.bitrateKbps ?: "—"} notStreaming=${state.notStreaming}"

    /** 优先保留关键诊断行，再补其他 State 行；不改原有 dumpsys 抓取。 */
    fun dumpClues(dump: String, limit: Int = 24): List<String> {
        val lines = dump.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val (keywordLines, remainingLines) = lines.partition { line ->
            listOf("A2DP", "codec", "quality", "bitrate").any {
                line.contains(it, ignoreCase = true)
            }
        }
        val stateLines = remainingLines.filter { it.contains("State:") }
        return (keywordLines + stateLines).take(limit)
    }
}
