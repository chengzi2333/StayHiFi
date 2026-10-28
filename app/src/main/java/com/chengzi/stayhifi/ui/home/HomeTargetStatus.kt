package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.trigger.TargetAssessment

/** 首页目标文案唯一入口；fullBars 由 ApplyPolicy.isAtHighest 统一计算。 */
object HomeTargetStatus {
    /**
     * v1.2.2：paramsAvailable=false 表示已连接但特权参数读不到（未授权/读取失败）——
     * 只陈述「已连接，授权后可用」，绝不提示「点下方按钮重试」（按钮此时是禁用的）。
     */
    fun statusLine(
        connected: Boolean,
        status: TargetAssessment.Status?,
        fullBars: Boolean,
        manualActive: Boolean = false,
        paramsAvailable: Boolean = true,
    ): String = when {
        !connected -> "等待耳机连接"
        !paramsAvailable -> "已连接，授权后可用"
        // 自定义会话优先于一切满血判定：手动降档绝不冒充满血/本机最高
        manualActive -> "自定义参数生效中"
        // UX 收敛（主 agent 裁决）：内部枚举名不上面客文案；
        // DEGRADED 只陈述「最高暂无法确认」，不冒明确认最高
        status == TargetAssessment.Status.INCOMPLETE_CURRENT -> "暂无法读取当前参数"
        status == TargetAssessment.Status.CAPABILITY_DEGRADED -> "最高能力暂无法确认"
        status == TargetAssessment.Status.COMPLETE && fullBars -> "已达本机最高音质输出"
        else -> "未拉满，点下方按钮重试"
    }

    fun badgeLabel(
        status: TargetAssessment.Status?,
        fullBars: Boolean,
        manualActive: Boolean = false,
        paramsAvailable: Boolean = true,
    ): String = when {
        !paramsAvailable -> "待确认"
        manualActive -> "自定义"
        status == TargetAssessment.Status.INCOMPLETE_CURRENT ||
            status == TargetAssessment.Status.CAPABILITY_DEGRADED -> "待确认"
        else -> if (status == TargetAssessment.Status.COMPLETE && fullBars) "满血" else "未满血"
    }
}
