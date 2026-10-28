package com.chengzi.stayhifi.codec

/**
 * 首页「自定义音质」手工目标构造（纯逻辑，JVM 可测）。
 *
 * 边界（用户拍板 + 同条目证明规则）：
 * - 可选项只来自当前连接的 selectable；codecType 可跨类型切换，但目标五字段
 *   codecType/rate/bits/channel/cs1 必须可由同一条能力条目证明，绝不跨条目拼档
 *   （192+16 与 96+24 不许拼成 192+24，与 [Negotiation.descending] 双参版同口径）；
 * - 当前生效配置本身即证据：selectable 未暴露时，写回与 live 完全一致的
 *   codec/rate/bits 组合仍允许（cs1 可用已知家族档位覆盖）；
 * - 码率档只开放有可信来源的家族：LDAC 用 AOSP 标准值（1000~1003），LHDC 用
 *   magic 0x8000 + 低字节档（0~8 固定档、9 自适应）；同 codec 时保留 current.cs1
 *   高位（Enco X3 golden 0x03088008 实证栈会回填高位），跨 codec 用 OPD 真机
 *   实证过的裸 0x8000|idx 形态；其余家族不臆造码率档；
 * - [adaptiveTarget] 恢复系统自适应：rate/bits/channel 通配（0=交回协议栈自选），
 *   priority=0（CODEC_PRIORITY_DEFAULT），cs1 取已知家族自适应档，未知家族保持
 *   current.cs1——该目标不含任何具体档位，天然不违反同条目规则。
 *
 * 本对象不触碰自动拉满默认目标（[com.chengzi.stayhifi.trigger.ApplyPolicy] 不变），
 * 也不做持久化：手动配置不跨重连重放。
 */
object ManualSelection {

    /** 采样率全档（192k→44.1k 降序），置灰展示由调用方按 [availableRates] 判定。 */
    val RATE_OPTIONS: List<Int> = Negotiation.RATE_STEPS_DESC

    /** 位深全档（32/24/16bit 降序）。 */
    val BITS_OPTIONS: List<Int> = listOf(4, 2, 1)

    /** 码率档选项：label 走 [Masks.qualityLabel] 唯一解码入口，cs1 为可写入值。 */
    data class QualityOption(val label: String, val cs1: Long)

    /** 可选编码器：仅 selectable 出现过的 codecType，保持首现顺序去重。 */
    fun codecOptions(selectable: List<CodecConfig>): List<Int> =
        selectable.map { it.codecType }.distinct()

    /**
     * 编码器显示名：当前 codec 优先用系统已裁决身份（如 "LHDC V3"，与首页同源）；
     * 其余 codec 走 caps 或集佐证版。身份只属于其 nativeType，绝不借给它 codec 裸猜。
     */
    fun codecLabel(
        codecType: Int,
        selectable: List<CodecConfig>,
        identity: CodecIdentity? = null,
    ): String =
        if (identity != null && codecType == identity.nativeType) identity.displayName
        else Masks.codecName(codecType, Masks.capsRateMask(codecType, selectable))

    /**
     * 码率档家族判定：LDAC 为 AOSP 公共编号；LHDC V5（10）有真机实证；
     * 厂商段（0x10..0x1F）必须 caps 含 176.4/192k 位才认 LHDC——与
     * [Masks.codecName] 佐证版同一证据标准，不裸认厂商编号。
     * Fix review：[identity] 为当前 codec 的系统已裁决身份（如 OPD 的 LHDC V3，
     * caps 仅 48/96），可佐证当前 codec 归入 LDAC/LHDC 家族——身份只作增量佐证
     * （只升不认的情况回落既有规则），且只对 identity.nativeType 生效。
     */
    fun familyOf(
        codecType: Int,
        selectable: List<CodecConfig>,
        identity: CodecIdentity? = null,
    ): CodecFamily = when {
        identity != null && codecType == identity.nativeType &&
            identity.family in IDENTITY_PROVEN_FAMILIES -> identity.family
        codecType == LDAC_CODEC_TYPE -> CodecFamily.LDAC
        codecType == LHDC_VIVO_CODEC_TYPE -> CodecFamily.LHDC
        codecLabel(codecType, selectable) == "LHDC" -> CodecFamily.LHDC
        else -> CodecFamily.UNKNOWN
    }

    /** 某编码器被 selectable 证明的采样率档位（条目字段为多比特掩码，按位与解码）。 */
    fun availableRates(codecType: Int, selectable: List<CodecConfig>): Set<Int> =
        selectable.filter { it.codecType == codecType }
            .flatMap { entry -> RATE_OPTIONS.filter { entry.sampleRate and it == it } }
            .toSet()

    /**
     * rate 已定后被同一条目证明的位深档位。channel 不在此处约束——
     * 声道由 [buildTarget] 按「current 优先、立体声次之」解析，
     * 位深只要有任意声道下的同条目证明即视为可选。
     */
    fun availableBits(codecType: Int, rate: Int, selectable: List<CodecConfig>): Set<Int> =
        selectable.filter { it.codecType == codecType && it.sampleRate and rate == rate }
            .flatMap { entry -> BITS_OPTIONS.filter { entry.bitsPerSample and it == it } }
            .toSet()

    /**
     * LHDC 码率档 cs1 构造：清 magic 区（0xC000）与低字节（0xFF）后置
     * 0x8000|qualityIndex，其余高位原样保留。base=0 时得到裸 0x8000|idx
     * （OPD 真机 current cs1=0x8006 即此形态）；base=golden 时精确还原
     * 50888712（0x03088008）。
     */
    fun lhdcQualityCs1(base: Long, qualityIndex: Long): Long =
        (base and LHDC_KEEP_MASK) or LHDC_MAGIC or (qualityIndex and 0xFFL)

    /**
     * 码率档选项：LDAC 四档（AOSP 标准值，跨厂商通用，现有拉满路径已无条件写 1000）；
     * LHDC 十档（0~8 固定 + 9 自适应），同 codec 保留 current.cs1 高位；
     * 其余家族空表（无可信档位数据，不臆造）。
     */
    fun qualityOptions(
        codecType: Int,
        selectable: List<CodecConfig>,
        current: CodecConfig,
        identity: CodecIdentity? = null,
    ): List<QualityOption> = when (familyOf(codecType, selectable, identity)) {
        CodecFamily.LDAC -> LDAC_QUALITY_VALUES.map { value ->
            QualityOption(Masks.qualityLabel(CodecFamily.LDAC, value) ?: value.toString(), value)
        }
        CodecFamily.LHDC -> {
            val base = if (codecType == current.codecType) current.cs1 else 0L
            (0L..9L).map { idx ->
                QualityOption(
                    Masks.qualityLabel(CodecFamily.LHDC, LHDC_MAGIC or idx) ?: idx.toString(),
                    lhdcQualityCs1(base, idx),
                )
            }
        }
        else -> emptyList()
    }

    /**
     * 组手工候选：rate/bits/channel 必须被同一条 selectable 条目证明；
     * channel 解析顺序：current.channelMode（合法且被证明）→ 立体声 2 → 单声道 1。
     * 与 current 完全一致的 codec/rate/bits 组合以 live 配置为证据直接放行
     * （写回现状永远安全）。cs1：显式 qualityCs1 优先，否则同 codec 继承 current、
     * 跨 codec 置 0（AOSP 默认）；cs2~cs4 同 codec 继承、跨 codec 置 0。
     * 无法证明返回 null，调用方不得生成不可写组合。
     */
    fun buildTarget(
        current: CodecConfig,
        codecType: Int,
        rate: Int,
        bits: Int,
        qualityCs1: Long?,
        selectable: List<CodecConfig>,
    ): CodecConfig? {
        // 0=通配、多比特=叠加，都不是可写入单档位（0 会被位与检查永远通过，必须前置拒绝）
        if (rate !in RATE_OPTIONS || bits !in BITS_OPTIONS) return null
        val sameCodec = codecType == current.codecType
        val sameAsLive = sameCodec && rate == current.sampleRate && bits == current.bitsPerSample
        val channel = if (sameAsLive) {
            current.channelMode
        } else {
            val entries = selectable.filter {
                it.codecType == codecType &&
                    it.sampleRate and rate == rate &&
                    it.bitsPerSample and bits == bits
            }
            listOfNotNull(
                current.channelMode.takeIf { it in VALID_CHANNELS },
                CHANNEL_STEREO,
                CHANNEL_MONO,
            ).firstOrNull { ch -> entries.any { it.channelMode and ch == ch } } ?: return null
        }
        return CodecConfig(
            codecType = codecType,
            priority = MANUAL_PRIORITY,
            sampleRate = rate,
            bitsPerSample = bits,
            channelMode = channel,
            cs1 = qualityCs1 ?: if (sameCodec) current.cs1 else 0L,
            cs2 = if (sameCodec) current.cs2 else 0L,
            cs3 = if (sameCodec) current.cs3 else 0L,
            cs4 = if (sameCodec) current.cs4 else 0L,
        )
    }

    /**
     * 码率档默认选中（Fix review：切 codec 后必须显式选中一个展示档位，
     * 不得让 cs1 落到档位表之外）：同 codec 保留当前档（不可识别则回落自适应），
     * 跨 codec 选该家族自适应档（两族阶梯末位恒为自适应）；无梯家族返回 null
     * （UI 无展示档位，buildTarget 按同 codec 继承 / 跨 codec 置 0 的既有规则）。
     */
    fun defaultQualityCs1(
        codecType: Int,
        selectable: List<CodecConfig>,
        current: CodecConfig,
        identity: CodecIdentity? = null,
    ): Long? {
        val options = qualityOptions(codecType, selectable, current, identity)
        if (options.isEmpty()) return null
        if (codecType == current.codecType) {
            options.firstOrNull { it.cs1 == current.cs1 }?.let { return it.cs1 }
        }
        return options.last().cs1
    }

    /**
     * 恢复系统自适应：rate/bits/channel 全通配（0=协议栈自选），priority=0
     * （CODEC_PRIORITY_DEFAULT），cs1 取已知家族自适应档（LDAC=1003、
     * LHDC=保留高位的 9 档），未知家族保持 current.cs1 不臆造。
     * 目标不含具体档位，天然不违反同条目证明规则。
     */
    fun adaptiveTarget(
        current: CodecConfig,
        selectable: List<CodecConfig>,
        identity: CodecIdentity? = null,
    ): CodecConfig {
        val cs1 = when (familyOf(current.codecType, selectable, identity)) {
            CodecFamily.LDAC -> LDAC_QUALITY_ADAPTIVE
            CodecFamily.LHDC -> lhdcQualityCs1(current.cs1, LHDC_QUALITY_ADAPTIVE_INDEX)
            else -> current.cs1
        }
        return CodecConfig(
            codecType = current.codecType,
            priority = 0,
            sampleRate = 0,
            bitsPerSample = 0,
            channelMode = 0,
            cs1 = cs1,
            cs2 = 0L,
            cs3 = 0L,
            cs4 = 0L,
        )
    }

    private const val LDAC_CODEC_TYPE = 4
    private const val LHDC_VIVO_CODEC_TYPE = 10
    private const val LDAC_QUALITY_ADAPTIVE = 1003L
    private const val LHDC_MAGIC = 0x8000L
    private const val LHDC_QUALITY_ADAPTIVE_INDEX = 9L
    /** 保留 magic 区（0xC000）与低字节（0xFF）之外的全部高位（= ~0xC0FFL）。 */
    private const val LHDC_KEEP_MASK = -0xC100L
    private const val CHANNEL_MONO = 1
    private const val CHANNEL_STEREO = 2
    private val VALID_CHANNELS = setOf(CHANNEL_MONO, CHANNEL_STEREO)
    private val LDAC_QUALITY_VALUES = listOf(1000L, 1001L, 1002L, 1003L)
    /** 身份佐证只接纳码率梯相关家族（LDAC/LHDC），其余家族不因此获得档位。 */
    private val IDENTITY_PROVEN_FAMILIES = setOf(CodecFamily.LDAC, CodecFamily.LHDC)
    /** 与拉满写入同一 priority 语义（最高偏好），读回判定不看 priority。 */
    private const val MANUAL_PRIORITY = 1_000_000
}
