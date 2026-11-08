package com.chengzi.stayhifi.codec

/**
 * v1.2.4 问题#4：首页码率格「—」的取证门（用户裁决：本期只加日志监控，不改文案）。
 *
 * summary.quality 为 null 时分两类原因，每进程去重后各记一条：
 *  - [Reason.FAMILY_WITHOUT_QUALITY_TIER]：该族无码率档位概念（aptX/AAC/SBC/LC3 等，
 *    [Masks.qualityLabel] 本就只对 LDAC/LHDC 解码，设计如此）。cs1 语义未收录，
 *    其变化不是新证据，按 mac+codecType 去重。
 *  - [Reason.UNRECOGNIZED_QUALITY_VALUE]：LDAC/LHDC 的 cs1 出现未收录值，
 *    是补映射的直接样本，按 mac+codecType+cs1 去重。
 */
object QualityGapDiagnosticGate {

    enum class Reason { FAMILY_WITHOUT_QUALITY_TIER, UNRECOGNIZED_QUALITY_VALUE }

    private data class Signature(val mac: String, val codecType: Int, val cs1: Long)

    private val signatures = mutableSetOf<Signature>()

    /** quality 非 null 或可推断的旧证据重复出现时返回 null（不记）。 */
    @Synchronized
    fun reasonToLog(mac: String, presentation: CodecPresentation): Reason? {
        if (presentation.summary.quality != null) return null
        val reason = when (presentation.identity.family) {
            CodecFamily.LDAC, CodecFamily.LHDC -> Reason.UNRECOGNIZED_QUALITY_VALUE
            else -> Reason.FAMILY_WITHOUT_QUALITY_TIER
        }
        val cs1Key = if (reason == Reason.UNRECOGNIZED_QUALITY_VALUE) presentation.current.cs1 else 0L
        val added = signatures.add(Signature(mac, presentation.current.codecType, cs1Key))
        return if (added) reason else null
    }

    @Synchronized
    fun reset() {
        signatures.clear()
    }
}
