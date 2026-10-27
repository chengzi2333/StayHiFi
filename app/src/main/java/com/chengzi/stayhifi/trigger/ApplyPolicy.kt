package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.Negotiation

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
     * 目标一律由 [deriveTarget] 现场派生（Task 27 口径，档案不参与拉满决策）：cs1 继承 current
     * 或取内置最佳档位表，栈未套用则视为未生效。priority/cs2~cs4 等栈管理字段
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
     * 能力字段为 AOSP NONE(0)（栈未暴露该维度）时同样保持 current——
     * 0 是通配语义，不是可写入的最高档位。
     *
     * 档位表（写入被拒由 Negotiation 降级链兜底退回现状）：
     * - LDAC（codecType=4）→ 1000：AOSP 标准 LDAC_QUALITY_HIGH（990kbps 最高音质档），
     *   跨厂商通用，不必保持系统默认的自适应档（1003）；
     * - LHDC-vivo（codecType=10）→ 50888712：黄金配置真机实证值（1000kbps 音质优先），勿改；
     * - 其余编码器 → 保持当前 cs1（无可信档位数据，不臆造）。
     */
    fun deriveTarget(current: CodecConfig, selectable: List<CodecConfig>): CodecConfig {
        val cap = selectable.firstOrNull { it.codecType == current.codecType }
        val rate = cap?.sampleRate?.takeIf { it > 0 }?.let { highestBit(it) } ?: current.sampleRate
        val bits = cap?.bitsPerSample?.takeIf { it > 0 }?.let { highestBit(it) } ?: current.bitsPerSample
        val cs1 = BEST_CS1[current.codecType] ?: current.cs1
        return current.copy(priority = 1_000_000, sampleRate = rate, bitsPerSample = bits, cs1 = cs1)
    }

    /**
     * 三态能力裁决（Task B1）：
     * - current 的 rate/bits/channel 非法（非 AOSP 单比特档位）→ INCOMPLETE_CURRENT，无可写目标；
     * - 存在同 codecType、channel 兼容、同一条目同时证明的最高 rate/bits → COMPLETE，
     *   target 的 rate/bits 逐位来自同一条目（绝不跨条目拼档），candidates 为
     *   [Negotiation.descending] 双参同条目约束链；
     * - 否则 → CAPABILITY_DEGRADED，target/candidates 严格走旧 [deriveTarget] + 单参降档链。
     * cs1 规则与 [deriveTarget] 一致（BEST_CS1 表），channel/cs2~cs4 继承 current。
     */
    fun assessTarget(current: CodecConfig, selectable: List<CodecConfig>): TargetAssessment {
        if (!Negotiation.isValidBase(current)) {
            return TargetAssessment(TargetAssessment.Status.INCOMPLETE_CURRENT, null, emptyList())
        }
        val compatible = selectable.filter {
            it.codecType == current.codecType && (it.channelMode and current.channelMode) != 0
        }
        // 先选"存在明确 bits 的最高 rate"，再从支持该 rate 的条目选最高 bits——
        // 二者必须出自同一条目，杜绝 192+16 与 96+24 拼成 192+24
        for (rate in Negotiation.RATE_STEPS_DESC) {
            val supporting = compatible.filter { it.sampleRate and rate == rate && it.bitsPerSample > 0 }
            if (supporting.isEmpty()) continue
            val bits = supporting.maxOf { highestBit(it.bitsPerSample) }
            val target = current.copy(
                priority = 1_000_000,
                sampleRate = rate,
                bitsPerSample = bits,
                cs1 = BEST_CS1[current.codecType] ?: current.cs1,
            )
            return TargetAssessment(
                TargetAssessment.Status.COMPLETE,
                target,
                Negotiation.descending(target, selectable),
            )
        }
        val degradedTarget = deriveTarget(current, selectable)
        return TargetAssessment(
            TargetAssessment.Status.CAPABILITY_DEGRADED,
            degradedTarget,
            Negotiation.descending(degradedTarget),
        )
    }

    /**
     * 是否已达本机最高：仅 COMPLETE、target 非空且 [matches](target, current) 时为 true；
     * 能力降档与 current 非法时一律 false（调用方不得据此宣称已拉满）。
     */
    fun isAtHighest(assessment: TargetAssessment, current: CodecConfig): Boolean =
        assessment.status == TargetAssessment.Status.COMPLETE &&
            assessment.target != null &&
            matches(assessment.target, current)

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
