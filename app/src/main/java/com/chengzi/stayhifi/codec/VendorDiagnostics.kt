package com.chengzi.stayhifi.codec

/** 轻量厂商诊断：仍走现有 LogStore，不引入持久化或额外采集。 */
object VendorDiagnostics {

    /** 音频状态仅作诊断；厂商运行时读取失败不得中断正常 apply。 */
    fun readMusicActive(read: () -> Boolean): String = try {
        read().toString()
    } catch (_: RuntimeException) {
        "unknown"
    }

    /** 未识别 vendor 保持原诊断；已佐证的 vendor LHDC 也需留 cs1/实况证据供后续回填。 */
    fun shouldLog(codecType: Int, capsRateMask: Int): Boolean {
        val codecName = Masks.codecName(codecType, capsRateMask)
        return codecName.startsWith("vendor(") ||
            (Masks.isVendorLhdcRange(codecType) && codecName == "LHDC")
    }

    /**
     * Task 8：identity 门控——UNKNOWN 或厂商 native 区间内的 LHDC 放行；
     * SBC/AAC/LDAC 等标准家族与既有 type10 保持安静，不再刷实时抓取诊断。
     */
    fun shouldLog(identity: CodecIdentity): Boolean =
        identity.family == CodecFamily.UNKNOWN ||
            (identity.family == CodecFamily.LHDC && Masks.isVendorLhdcRange(identity.nativeType))

    /** 按段落、流式状态与码率字段分类本轮证据。 */
    fun sampleKind(state: BtStateParser.BtState?): String = when {
        state == null -> "no-section"
        state.notStreaming -> "not-streaming"
        state.bitrateKbps == null -> "no-bitrate"
        else -> "available"
    }

    /** Task 8：按本轮解析结果分类证据，新增歧义段一类；五类小写证据名。 */
    fun sampleKind(result: BtStateParseResult): String = when (result.status) {
        BtStateParseStatus.NO_SECTION -> "no-section"
        BtStateParseStatus.AMBIGUOUS_SECTION -> "ambiguous-section"
        BtStateParseStatus.NOT_STREAMING -> "not-streaming"
        BtStateParseStatus.NO_BITRATE -> "no-bitrate"
        BtStateParseStatus.AVAILABLE -> "available"
    }

    /**
     * Task 8：实时抓取诊断的唯一裁决——先过 identity 门控，再仅对无匹配段落/
     * 无码率字段/歧义段返回原因；NOT_STREAMING 与 AVAILABLE 一律 null
     * （NOT_STREAMING 的证据只经现有 gate 走 not-streaming 类别，不走本函数）。
     */
    fun realtimeIssue(identity: CodecIdentity, result: BtStateParseResult): String? {
        if (!shouldLog(identity)) return null
        return when (result.status) {
            BtStateParseStatus.NO_SECTION -> "无匹配段落"
            BtStateParseStatus.NO_BITRATE -> "无码率字段"
            BtStateParseStatus.AMBIGUOUS_SECTION ->
                "歧义段落: 候选=" + result.candidateSections.take(4).joinToString("/")
            else -> null
        }
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
