package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig

/** 候选执行结果；只有真正发生首次写入前才触发调用方的 origin 记录。 */
sealed interface ApplyRunResult {
    data class AlreadyAligned(val candidate: CodecConfig) : ApplyRunResult
    data class Applied(val candidate: CodecConfig) : ApplyRunResult
    data object Exhausted : ApplyRunResult
    data object IncompleteCurrent : ApplyRunResult
}

/** 顺序执行同一目标裁决产生的候选，不直接持有档案、通知或漂移基准仓库。 */
class ApplyCandidateRunner {
    suspend fun run(
        current: CodecConfig,
        assessment: TargetAssessment,
        beforeFirstWrite: () -> Unit,
        writeVerified: suspend (CodecConfig) -> Boolean,
    ): ApplyRunResult {
        if (assessment.status == TargetAssessment.Status.INCOMPLETE_CURRENT) {
            return ApplyRunResult.IncompleteCurrent
        }
        var originRecorded = false
        for (candidate in assessment.candidates) {
            if (ApplyPolicy.matches(candidate, current)) {
                return ApplyRunResult.AlreadyAligned(candidate)
            }
            if (!originRecorded) {
                beforeFirstWrite()
                originRecorded = true
            }
            if (writeVerified(candidate)) {
                return ApplyRunResult.Applied(candidate)
            }
        }
        return ApplyRunResult.Exhausted
    }
}
