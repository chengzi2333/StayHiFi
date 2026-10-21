package com.chengzi.stayhifi.codec

/**
 * 内置已知设备预设。唯一条目：Enco X3 + LHDC（codecType 10）→ 黄金配置。
 * 常量来自 Enco X3 真机捕获，勿改。
 */
object Presets {
    private val ENCO_X3_LHDC = CodecConfig(
        codecType = 10,
        priority = 1000000,
        sampleRate = 32,
        bitsPerSample = 2,
        channelMode = 2,
        cs1 = 50888712L,
        cs2 = 0L,
        cs3 = 1543503872L,
        cs4 = 0L,
    )

    fun match(name: String, codecType: Int): CodecConfig? =
        if (name.contains("Enco X3") && codecType == 10) ENCO_X3_LHDC else null
}
