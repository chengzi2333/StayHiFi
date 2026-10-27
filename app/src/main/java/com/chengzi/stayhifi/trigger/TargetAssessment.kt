package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig

/**
 * 能力目标裁决结果（Task B1 同条目约束）：
 * - COMPLETE：current 字段合法，且存在同一条目可证明的最高 rate/bits 组合，
 *   [target] 与整条 [candidates] 降档链均逐位来自同一条目（codecType/rate/bits/channel）；
 * - CAPABILITY_DEGRADED：current 合法但无可证明组合，[target]/[candidates] 严格回落
 *   旧 [ApplyPolicy.deriveTarget] + 单参 [com.chengzi.stayhifi.codec.Negotiation.descending]；
 * - INCOMPLETE_CURRENT：current 的 rate/bits/channel 含非法值（非 AOSP 定义的单比特档位），
 *   不可写，[target] 为 null、[candidates] 为空。
 */
data class TargetAssessment(
    val status: Status,
    val target: CodecConfig?,
    val candidates: List<CodecConfig>,
) {
    enum class Status { COMPLETE, CAPABILITY_DEGRADED, INCOMPLETE_CURRENT }
}
