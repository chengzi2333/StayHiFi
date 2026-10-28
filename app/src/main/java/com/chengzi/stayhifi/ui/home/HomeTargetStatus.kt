package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.trigger.TargetAssessment

/** 首页目标文案唯一入口；fullBars 由 ApplyPolicy.isAtHighest 统一计算。 */
object HomeTargetStatus {
    fun statusLine(
        connected: Boolean,
        status: TargetAssessment.Status?,
        fullBars: Boolean,
    ): String = when {
        !connected -> "等待耳机连接"
        status == TargetAssessment.Status.INCOMPLETE_CURRENT -> "参数不完整 · 暂无法判定"
        status == TargetAssessment.Status.CAPABILITY_DEGRADED -> "能力未完整暴露 · 暂无法确认最高"
        status == TargetAssessment.Status.COMPLETE && fullBars -> "已达本机最高音质输出"
        else -> "未拉满 · 点击重试"
    }

    fun badgeLabel(status: TargetAssessment.Status?, fullBars: Boolean): String = when (status) {
        TargetAssessment.Status.INCOMPLETE_CURRENT,
        TargetAssessment.Status.CAPABILITY_DEGRADED -> "待确认"
        else -> if (status == TargetAssessment.Status.COMPLETE && fullBars) "满血" else "未满血"
    }
}
