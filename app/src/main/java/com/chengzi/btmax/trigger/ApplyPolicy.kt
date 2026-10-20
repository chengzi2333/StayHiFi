package com.chengzi.btmax.trigger

import com.chengzi.btmax.codec.CodecConfig

/**
 * 自动拉满决策策略（设计 §5 错误与边界）：
 * - [decide]：当前与目标已一致则跳过写入（省电）；
 * - [autoTarget]：连接的是无档案新设备时的全局默认策略。
 */
object ApplyPolicy {

    /** 是否需要写入：current 与 target 不一致才返回 true。 */
    fun decide(current: CodecConfig, target: CodecConfig): Boolean = current != target

    /**
     * 无档案新设备的全局默认策略：codecType / channelMode / cs1~cs4 保持 current 不变，
     * rate / bits 取 [selectable] 中当前 codec 能力掩码的最高位，priority 置 1000000。
     * [selectable] 中没有当前 codec 的能力条目时，rate / bits 保持 current。
     */
    fun autoTarget(current: CodecConfig, selectable: List<CodecConfig>): CodecConfig {
        val cap = selectable.firstOrNull { it.codecType == current.codecType }
        val rate = cap?.sampleRate?.let { highestBit(it) } ?: current.sampleRate
        val bits = cap?.bitsPerSample?.let { highestBit(it) } ?: current.bitsPerSample
        return current.copy(priority = 1_000_000, sampleRate = rate, bitsPerSample = bits)
    }

    /** 掩码最高位（43 = 32+8+2+1 → 32）。掩码 ≤ 0 时原样返回（调用方兜底）。 */
    private fun highestBit(mask: Int): Int {
        if (mask <= 0) return mask
        var m = mask
        while (m and (m - 1) != 0) m = m and (m - 1)
        return m
    }
}
