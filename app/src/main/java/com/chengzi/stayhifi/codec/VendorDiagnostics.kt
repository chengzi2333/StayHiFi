package com.chengzi.stayhifi.codec

/** 轻量厂商诊断：仍走现有 LogStore，不引入持久化或额外采集。 */
object VendorDiagnostics {

    /** 未识别 vendor 保持原诊断；已佐证的 vendor LHDC 也需留 cs1/实况证据供后续回填。 */
    fun shouldLog(codecType: Int, capsRateMask: Int): Boolean {
        val codecName = Masks.codecName(codecType, capsRateMask)
        return codecName.startsWith("vendor(") ||
            (Masks.isVendorLhdcRange(codecType) && codecName == "LHDC")
    }

    /** 只保留排查所需字段，避免把数百行 dumpsys 全倒进 200 行日志缓冲。 */
    fun evidence(state: BtStateParser.BtState): String =
        "段=${state.codec} qualityMode=${state.qualityMode ?: "—"} " +
            "bitrateKbps=${state.bitrateKbps ?: "—"} notStreaming=${state.notStreaming}"
}
