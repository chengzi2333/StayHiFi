package com.chengzi.btmax.codec

/**
 * 降序协商：在 base 配置之下给出候选回退序列。
 * 采样率档位 32(192k) → 8(96k) → 2(48k)，仅保留不高于 base 的档位，
 * 其余字段原样保留。base 已在最低档时唯一候选即 base 本身。
 */
object Negotiation {
    private val RATE_STEPS = listOf(32, 8, 2)

    fun descending(base: CodecConfig): List<CodecConfig> =
        RATE_STEPS.filter { it <= base.sampleRate }.map { base.copy(sampleRate = it) }
}
