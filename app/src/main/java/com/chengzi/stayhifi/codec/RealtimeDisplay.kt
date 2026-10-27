package com.chengzi.stayhifi.codec

/**
 * Task 7：首页「实际传输」行的四态枚举，替代旧 boolean 组合裁决（Task 44/46 的
 * realtimeNotStreaming + null 码率推断）。空态拆出 STACK_REPORTS_IDLE：
 * PLK110 实证播放中 LHDC V3 段回报 raw=-1（栈回报 idle），此时 isMusicActive 与
 * 栈回报互相矛盾，既不能显示「未在播放」也不能显示粘性码率，只能说暂无法确认。
 */
enum class RealtimeDisplay {
    /** isMusicActive=false：确定未在播放（Task 46 即时主判定）。 */
    NOT_PLAYING,

    /** 栈段落有合法码率：可展示真实传输实况。仅本态携带 bitrate/quality。 */
    AVAILABLE,

    /** 无段/歧义段/无码率字段：本机暂不支持抓取（旧 unsupported 文案）。 */
    NO_DATA,

    /** 播放中但栈明确回报未流式（bitrate ≤ 0，LDAC -1 与 PLK110 raw=-1 实证）。 */
    STACK_REPORTS_IDLE,
}

/**
 * 首页实际传输的唯一裁决结果。仅 AVAILABLE 携带 bitrateKbps/qualityMode，
 * 其余三态两字段恒为 null，UI 不再靠布尔组合推断空态。
 */
data class RealtimeState(
    val display: RealtimeDisplay,
    val bitrateKbps: Int? = null,
    val qualityMode: String? = null,
) {
    /**
     * 旧源码兼容计算属性（Task 44/46 的 realtimeNotStreaming 字段）：
     * 只在 NOT_PLAYING 为 true；STACK_REPORTS_IDLE 不再汇入本属性，
     * 旧调用方升级后必须改用 [display] 四态裁决。
     */
    val notStreaming: Boolean
        get() = display == RealtimeDisplay.NOT_PLAYING
}
