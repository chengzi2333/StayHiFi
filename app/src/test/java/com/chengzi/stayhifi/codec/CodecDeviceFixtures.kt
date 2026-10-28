package com.chengzi.stayhifi.codec

/**
 * Task 1：编码器身份/实况各任务共享的真机 fixture。
 *
 * 只固定 spec 已给出的字段（type / currentRate / bits / caps / cs1 / dump 事实）；
 * priority、channelMode、cs2..cs4 无出处，一律走 [unverified] 的显式默认值——
 * 这些默认值不代表真机原始日志，调用方不得把它们当原始事实引用或展示。
 */
object CodecDeviceFixtures {

    /**
     * 未证实字段的显式默认（priority=0、channelMode=0、cs2..cs4=0）。
     * 参数只接收有出处的字段；不得在本 helper 里新增「看起来像原始值」的数字。
     */
    fun unverified(
        codecType: Int,
        sampleRate: Int,
        bitsPerSample: Int = 1,
        cs1: Long = 0L,
    ): CodecConfig = CodecConfig(
        codecType = codecType,
        priority = 0,
        sampleRate = sampleRate,
        bitsPerSample = bitsPerSample,
        channelMode = 0,
        cs1 = cs1,
        cs2 = 0L,
        cs3 = 0L,
        cs4 = 0L,
    )

    /** 由 spec 给出的 caps 位或集构造单条目 selectable（sampleRate 字段在 selectable 语境即多比特掩码）。 */
    fun selectableCaps(codecType: Int, capsMask: Int): CodecConfig =
        unverified(codecType, capsMask)

    // NX：type=14，当前 96kHz；selectable caps 位或集 11（44.1+48+96）。
    val nxType = 14
    val nxCurrentRate = 8
    val nxCapsMask = 11
    val nxCurrent = unverified(nxType, nxCurrentRate)

    // OPD：type=18，当前 96kHz/24bit，cs1=0x8006（magic 0x8000|6=500kbps 档）；caps 位或集 10（48+96）。
    val opdType = 18
    val opdCurrentRate = 8
    val opdBits = 2
    val opdCapsMask = 10
    val opdCs1 = 0x8006L
    val opdCurrent = unverified(opdType, opdCurrentRate, bitsPerSample = opdBits, cs1 = opdCs1)

    // PHZ：type=19，当前 192kHz/24bit；caps 位或集 42（多档叠加）。
    val phzType = 19
    val phzCurrentRate = 32
    val phzBits = 2
    val phzCapsMask = 42
    val phzCurrent = unverified(phzType, phzCurrentRate, bitsPerSample = phzBits)

    // iQOO Pad：type=8，当前 48kHz/16bit；caps 位或集 2。
    val iqooPadType = 8
    val iqooPadCurrentRate = 2
    val iqooPadBits = 1
    val iqooPadCapsMask = 2
    val iqooPadCurrent = unverified(iqooPadType, iqooPadCurrentRate, bitsPerSample = iqooPadBits)

    // iQOO 15：沿用既有 type10 golden（Enco X3 实测捕获，见 CodecConfigTest）。
    val iqoo15Golden = CodecConfig(10, 1000000, 32, 2, 2, 50888712L, 0L, 1543503872L, 0L)

    // OPD dumpsys 实况事实：V3 段 quality mode=MID、原始码率 500000；V5 段无任何字段（"{}"）。
    val opdV3QualityMode = "MID"
    val opdV3RawBitrate = 500000L
    val opdDumpV3 = """
        A2DP LHDC V3 State:
          LHDC quality mode : $opdV3QualityMode
          LHDC transmission bitrate (Kbps) :$opdV3RawBitrate
    """.trimIndent()
    val opdDumpV5Empty = "{}"

    // PLK110 dumpsys 实况事实：V3 段播放中回报原始码率 -1（栈回报 idle）。
    val plkV3RawBitrate = -1L
    val plkDumpV3 = """
        A2DP LHDC V3 State:
          LHDC transmission bitrate (Kbps) :$plkV3RawBitrate
    """.trimIndent()
}
