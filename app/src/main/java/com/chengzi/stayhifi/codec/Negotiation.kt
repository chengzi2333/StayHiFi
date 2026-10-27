package com.chengzi.stayhifi.codec

/**
 * 降序协商：在 base 配置之下给出候选回退序列。
 * 采样率档位 32(192k) → 16(176.4k) → 8(96k) → 4(88.2k) → 2(48k) → 1(44.1k)，
 * 仅保留不高于 base 的档位，其余字段原样保留。
 * 对抗审查 C3：base 自身恒为首选（修复 176.4k 被静默降档），
 * 且任意 base 至少兜底 base 本身一个候选（修复 44.1k 空候选假报失败）。
 *
 * Task B1：单参 [descending] 新增合法 base 前置检查——rate 必须属于 AOSP 定义的
 * 单比特档位 {1,2,4,8,16,32}、bits 属于 {1,2,4}、channel 属于 {1,2}
 * （AOSP 仅 MONO=1/STEREO=2）；合法 base 的候选顺序和字段与旧实现完全一致，
 * 非法 base（0/多比特/未知值）返回空链，避免把不可写值写进协商序列。
 * 双参 [descending] 为完整能力专用重载：每个候选 rate（含 base 自身）都必须
 * 被 [selectable] 中至少一条原始条目证明同时包含该 rate、base bits、
 * base channel 和 base codecType——杜绝跨条目拼档（如 192+16 与 96+24 拼成 192+24）。
 */
object Negotiation {
    private val RATE_STEPS = listOf(32, 16, 8, 4, 2, 1)

    /** 降序标准档位（供 [com.chengzi.stayhifi.trigger.ApplyPolicy.assessTarget] 从高到低选目标）。 */
    val RATE_STEPS_DESC: List<Int> = RATE_STEPS

    private val VALID_BITS = setOf(1, 2, 4)
    private val VALID_CHANNELS = setOf(1, 2)

    /** 合法 base：rate/bits/channel 均为 AOSP 定义的单比特值（0/多比特/未知值非法）。 */
    fun isValidBase(base: CodecConfig): Boolean =
        base.sampleRate in RATE_STEPS && base.bitsPerSample in VALID_BITS && base.channelMode in VALID_CHANNELS

    fun descending(base: CodecConfig): List<CodecConfig> =
        if (!isValidBase(base)) emptyList()
        else RATE_STEPS.filter { it <= base.sampleRate }.map { base.copy(sampleRate = it) }

    /**
     * 完整能力重载：仅保留被同一条目证明的档位。
     * 条目 [CodecConfig.sampleRate]/[CodecConfig.bitsPerSample]/[CodecConfig.channelMode]
     * 在 selectable 语境是多比特掩码，"包含"用按位与判定。
     */
    fun descending(base: CodecConfig, selectable: List<CodecConfig>): List<CodecConfig> {
        if (!isValidBase(base)) return emptyList()
        val provenBySameEntry: (Int) -> Boolean = { rate ->
            selectable.any {
                it.codecType == base.codecType &&
                    it.sampleRate and rate == rate &&
                    it.bitsPerSample and base.bitsPerSample == base.bitsPerSample &&
                    it.channelMode and base.channelMode == base.channelMode
            }
        }
        // base 自身未被同条目证明 → 整条链为空
        if (!provenBySameEntry(base.sampleRate)) return emptyList()
        return RATE_STEPS.filter { it <= base.sampleRate && provenBySameEntry(it) }
            .map { base.copy(sampleRate = it) }
    }
}
