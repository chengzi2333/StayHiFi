package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.data.ApplyMode
import com.chengzi.stayhifi.data.ApplyPreferences

/** 自定义恢复结果；普通失败保留偏好，只有明确栈拒绝才持久回退系统自适应。 */
enum class PreferredApplyResult {
    SKIP_ADAPTIVE,
    CUSTOM_ALREADY_ALIGNED,
    CUSTOM_APPLIED,
    CUSTOM_REJECTED,
    CUSTOM_FAILED,
    AUTO_MAX,
}

class PreferredApplyRunner(
    private val preferences: ApplyPreferences,
) {
    suspend fun run(
        mac: String,
        current: CodecConfig,
        applyAuto: suspend () -> Unit,
        applyCustom: suspend (CodecConfig) -> ManualApplyResult,
    ): PreferredApplyResult {
        val preference = preferences.get(mac)
        return when (preference.mode) {
            ApplyMode.SYSTEM_ADAPTIVE -> PreferredApplyResult.SKIP_ADAPTIVE
            ApplyMode.AUTO_MAX -> {
                applyAuto()
                PreferredApplyResult.AUTO_MAX
            }
            ApplyMode.CUSTOM -> {
                val target = preference.lastTarget ?: run {
                    applyAuto()
                    return PreferredApplyResult.AUTO_MAX
                }
                if (ApplyPolicy.matches(target, current)) {
                    return PreferredApplyResult.CUSTOM_ALREADY_ALIGNED
                }
                when (applyCustom(target)) {
                    ManualApplyResult.APPLIED -> PreferredApplyResult.CUSTOM_APPLIED
                    ManualApplyResult.REJECTED_BY_STACK -> {
                        preferences.setSystemAdaptive(mac, target)
                        PreferredApplyResult.CUSTOM_REJECTED
                    }
                    ManualApplyResult.FAILED -> PreferredApplyResult.CUSTOM_FAILED
                }
            }
        }
    }
}
