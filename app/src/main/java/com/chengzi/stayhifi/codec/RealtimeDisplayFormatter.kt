package com.chengzi.stayhifi.codec

/**
 * Task 7：首页「实际传输」行唯一文案入口。四态文案逐字固定在这里，
 * RealtimeSection（HomeScreen）与各测试共用，UI 不再自行拼装空态文案。
 * 判定本身见 [BtStateParser.realtimeDisplay]。
 */
object RealtimeDisplayFormatter {

    fun text(state: RealtimeState): String = when (state.display) {
        RealtimeDisplay.NOT_PLAYING -> "未在播放"
        // PLK110：播放中但栈回报 -1——暂无法确认，绝不显示「未在播放」
        RealtimeDisplay.STACK_REPORTS_IDLE -> "暂无法确认"
        // UX 收敛（主 agent 裁决）：只陈述「暂读取不到」，不断言本机不支持
        RealtimeDisplay.NO_DATA -> "暂无法读取"
        RealtimeDisplay.AVAILABLE -> {
            val kbps = state.bitrateKbps?.takeIf { it > 0 } ?: return "暂无法确认"
            val qm = state.qualityMode
            when {
                // ABR 必须带「自适应」三字——别人不认识缩写（Task 45 既定文案）
                qm != null && qm.contains("ABR", ignoreCase = true) ->
                    "$kbps kbps · ABR（自适应）"
                qm != null -> "$kbps kbps · $qm"
                else -> "$kbps kbps"
            }
        }
    }

    /**
     * v1.2.4 分层说明（用户拍板）：NO_DATA 的解释行按证据分级，全部指向设置页说明；
     * UNKNOWN（无 dump/歧义）保留反馈引导。文案逐字固定于此，UI 不自拼装。
     */
    fun noDataHint(reason: RealtimeNoDataReason): String = when (reason) {
        RealtimeNoDataReason.OFFLOAD_DSP ->
            "该机 DSP 硬件解码，系统自身看不到码流 · 详见 设置 › 实际传输说明"
        RealtimeNoDataReason.SECTION_EMPTY ->
            "该机型系统未回报传输数据 · 详见 设置 › 实际传输说明"
        RealtimeNoDataReason.NO_SECTION ->
            "该机型系统不提供传输数据回报 · 详见 设置 › 实际传输说明"
        RealtimeNoDataReason.UNKNOWN ->
            "该机型系统未回报传输码率，可到 设置 › 运行日志 复制日志反馈"
    }
}
