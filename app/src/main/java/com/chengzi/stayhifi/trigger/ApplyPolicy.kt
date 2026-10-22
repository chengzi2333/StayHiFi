package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig

/**
 * 自动拉满决策策略（设计 §5 错误与边界；Task 27 两态裁决修订）：
 * - [decide]：当前与目标已一致则跳过写入（省电）；
 * - [deriveTarget]：**唯一目标来源**——所有设备（有无档案）统一现场派生"本机最高音质输出"，
 *   档案不再参与拉满决策，仅在设备页作本机记录展示。
 */
object ApplyPolicy {

    /**
     * 是否需要写入：与 [matches] 同一生效语义（codecType + rate + bits + cs1）取反。
     * 对抗审查 I4：协议栈会规范化 priority/cs2~cs4，全字段相等使跳过分支成为死代码，
     * 每次重连都无谓重协商；改用 matches 语义后栈读回值可正确命中跳过。
     */
    fun decide(current: CodecConfig, target: CodecConfig): Boolean = !matches(target, current)

    /**
     * 生效判定（与服务端 CodecService.writeConfig 的 match 语义一致）：
     * codecType 相等 &&（target 采样率为 0 或相等）&&（target 位深为 0 或相等）&& cs1 相等。
     * 档案/PRESET 目标的 cs1 是用户指定的音质档，栈未套用则视为未生效；
     * autoTarget 的 cs1 继承 current，比对天然通过。priority/cs2~cs4 等栈管理字段
     * 可能被栈规范化，不参与判定。
     */
    fun matches(target: CodecConfig, actual: CodecConfig): Boolean =
        actual.codecType == target.codecType &&
            (target.sampleRate == 0 || actual.sampleRate == target.sampleRate) &&
            (target.bitsPerSample == 0 || actual.bitsPerSample == target.bitsPerSample) &&
            actual.cs1 == target.cs1

    /**
     * 本机最高派生目标（唯一目标来源）：rate / bits 取 [selectable] 中当前 codec
     * 能力掩码的最高位；cs1 取内置「各编码器已知最佳档位表」；
     * [selectable] 中没有当前 codec 的能力条目时，rate / bits 保持 current。
     *
     * 档位表（写入被拒由 Negotiation 降级链兜底退回现状）：
     * - LDAC（codecType=4）→ 1000：AOSP 标准 LDAC_QUALITY_HIGH（990kbps 最高音质档），
     *   跨厂商通用，不必保持系统默认的自适应档（1003）；
     * - LHDC-vivo（codecType=10）→ 50888712：黄金配置真机实证值（1000kbps 音质优先），勿改；
     * - 其余编码器 → 保持当前 cs1（无可信档位数据，不臆造）。
     */
    fun deriveTarget(current: CodecConfig, selectable: List<CodecConfig>): CodecConfig {
        val cap = selectable.firstOrNull { it.codecType == current.codecType }
        val rate = cap?.sampleRate?.let { highestBit(it) } ?: current.sampleRate
        val bits = cap?.bitsPerSample?.let { highestBit(it) } ?: current.bitsPerSample
        val cs1 = BEST_CS1[current.codecType] ?: current.cs1
        return current.copy(priority = 1_000_000, sampleRate = rate, bitsPerSample = bits, cs1 = cs1)
    }

    private const val LDAC_CODEC_TYPE = 4
    private const val LDAC_QUALITY_HIGH = 1000L
    private const val LHDC_VIVO_CODEC_TYPE = 10
    private const val LHDC_VIVO_BEST_CS1 = 50888712L

    /** 各编码器已知最佳 cs1 档位表。 */
    private val BEST_CS1 = mapOf(
        LDAC_CODEC_TYPE to LDAC_QUALITY_HIGH,
        LHDC_VIVO_CODEC_TYPE to LHDC_VIVO_BEST_CS1,
    )

    /** 掩码最高位（43 = 32+8+2+1 → 32）。掩码 ≤ 0 时原样返回（调用方兜底）。 */
    private fun highestBit(mask: Int): Int {
        if (mask <= 0) return mask
        var m = mask
        while (m and (m - 1) != 0) m = m and (m - 1)
        return m
    }
}
