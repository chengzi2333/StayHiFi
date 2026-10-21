package com.chengzi.stayhifi.codec

/**
 * 降序协商：在 base 配置之下给出候选回退序列。
 * 采样率档位 32(192k) → 16(176.4k) → 8(96k) → 4(88.2k) → 2(48k) → 1(44.1k)，
 * 仅保留不高于 base 的档位，其余字段原样保留。
 * 对抗审查 C3：base 自身恒为首选（修复 176.4k 被静默降档），
 * 且任意 base 至少兜底 base 本身一个候选（修复 44.1k 空候选假报失败）。
 */
object Negotiation {
    private val RATE_STEPS = listOf(32, 16, 8, 4, 2, 1)

    fun descending(base: CodecConfig): List<CodecConfig> =
        RATE_STEPS.filter { it <= base.sampleRate }.map { base.copy(sampleRate = it) }
}
