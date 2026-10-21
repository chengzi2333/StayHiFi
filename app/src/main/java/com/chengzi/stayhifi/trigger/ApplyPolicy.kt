package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig

/**
 * 自动拉满决策策略（设计 §5 错误与边界）：
 * - [decide]：当前与目标已一致则跳过写入（省电）；
 * - [autoTarget]：连接的是无档案新设备时的全局默认策略。
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
     * 档案/PRESET 目标的 cs1 是用户指定的音质档，栈未套用则视为未生效；
     * autoTarget 的 cs1 继承 current，比对天然通过。priority/cs2~cs4 等栈管理字段
     * 可能被栈规范化，不参与判定。
     */
    fun matches(target: CodecConfig, actual: CodecConfig): Boolean =
        actual.codecType == target.codecType &&
            (target.sampleRate == 0 || actual.sampleRate == target.sampleRate) &&
            (target.bitsPerSample == 0 || actual.bitsPerSample == target.bitsPerSample) &&
            actual.cs1 == target.cs1

    /**
     * 无档案新设备的全局默认策略：codecType / channelMode / cs1~cs4 保持 current 不变，
     * rate / bits 取 [selectable] 中当前 codec 能力掩码的最高位，priority 置 1000000。
     * [selectable] 中没有当前 codec 的能力条目时，rate / bits 保持 current。
     *
     * LDAC 例外（codecType == 4）：cs1 强制为 1000L（AOSP 标准 BluetoothCodecConfig
     * LDAC_QUALITY_HIGH = 990kbps 最高音质档）。LDAC 质量档取值是 AOSP 标准、跨厂商通用，
     * 无档案新设备可直接自动拉满码率，不必保持系统默认的自适应档（1003）。
     */
    fun autoTarget(current: CodecConfig, selectable: List<CodecConfig>): CodecConfig {
        val cap = selectable.firstOrNull { it.codecType == current.codecType }
        val rate = cap?.sampleRate?.let { highestBit(it) } ?: current.sampleRate
        val bits = cap?.bitsPerSample?.let { highestBit(it) } ?: current.bitsPerSample
        val cs1 = if (current.codecType == LDAC_CODEC_TYPE) LDAC_QUALITY_HIGH else current.cs1
        return current.copy(priority = 1_000_000, sampleRate = rate, bitsPerSample = bits, cs1 = cs1)
    }

    private const val LDAC_CODEC_TYPE = 4
    private const val LDAC_QUALITY_HIGH = 1000L

    /** 掩码最高位（43 = 32+8+2+1 → 32）。掩码 ≤ 0 时原样返回（调用方兜底）。 */
    private fun highestBit(mask: Int): Int {
        if (mask <= 0) return mask
        var m = mask
        while (m and (m - 1) != 0) m = m and (m - 1)
        return m
    }
}
