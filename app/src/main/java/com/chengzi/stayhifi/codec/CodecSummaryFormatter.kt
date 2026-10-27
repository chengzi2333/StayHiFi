package com.chengzi.stayhifi.codec

/**
 * Task 4：共享编码器摘要格式化。首页、ApplyWorker、常驻通知与实时日志共用；
 * 历史档案没有身份上下文时保留旧 [CodecConfig.summary] 兜底，不伪造新身份。
 *
 * 质量档使用已裁决 identity.family（[Masks.qualityLabel] family 版），
 * 不重新用 caps 推导身份；不读取 localCapabilities。
 */

/** 四段式摘要；quality 为 null 时 [text] 只输出三段。 */
data class CodecSummary(
    val codec: String,
    val rate: String,
    val bits: String,
    val quality: String?,
) {
    fun text(): String =
        if (quality == null) "$codec·$rate·$bits" else "$codec·$rate·$bits·$quality"
}

object CodecSummaryFormatter {

    fun format(config: CodecConfig, identity: CodecIdentity): CodecSummary =
        CodecSummary(
            codec = identity.displayName,
            rate = Masks.rateHz(config.sampleRate),
            bits = Masks.bitsDepth(config.bitsPerSample),
            quality = Masks.qualityLabel(identity.family, config.cs1),
        )

    fun formatText(config: CodecConfig, identity: CodecIdentity): String =
        format(config, identity).text()
}
