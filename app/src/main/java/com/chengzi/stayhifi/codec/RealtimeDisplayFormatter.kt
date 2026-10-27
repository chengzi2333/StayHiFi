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
        RealtimeDisplay.NO_DATA -> "—（本机暂不支持抓取）"
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
}
