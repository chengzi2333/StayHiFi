package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页「自定义音质」手工目标的纯逻辑契约：
 * - 可选项只来自当前连接的 selectable（跨 codecType 允许，但目标五字段
 *   codecType/rate/bits/channel/cs1 必须由同一条能力条目证明，绝不跨条目拼档）；
 * - 当前生效配置本身即证据（selectable 缺失时仍允许写回 live 组合）；
 * - 码率档只开放有可信来源的家族（LDAC=AOSP 标准值；LHDC=magic 0x8000+低字节档，
 *   同 codec 保留当前 cs1 高位，跨 codec 用真机实证过的裸 0x8000|idx 形态）；
 * - 恢复自适应 = rate/bits/channel 通配交回协议栈，cs1 取已知家族自适应档。
 */
class ManualSelectionTest {

    private fun caps(
        codecType: Int,
        rateMask: Int,
        bitsMask: Int = 2,
        channelMask: Int = 2,
    ): CodecConfig = CodecConfig(codecType, 0, rateMask, bitsMask, channelMask, 0L, 0L, 0L, 0L)

    // 跨条目拼档经典反例（设计文档 192+16 / 96+24 案例）：
    // 条目 A 证明 192/96k + 16bit，条目 B 证明 96k + 24/16bit——192k+24bit 无任何条目证明。
    private val ldacSplitSelectable = listOf(
        caps(codecType = 4, rateMask = 32 or 8, bitsMask = 1),
        caps(codecType = 4, rateMask = 8, bitsMask = 2 or 1),
    )
    private val ldacCurrent = CodecConfig(4, 0, 8, 1, 2, 1001L, 0L, 0L, 0L)

    @Test
    fun codecOptionsComeOnlyFromSelectableInFirstSeenOrder() {
        val selectable = listOf(
            caps(codecType = 10, rateMask = 43),
            caps(codecType = 10, rateMask = 3),
            caps(codecType = 0, rateMask = 3),
        )
        assertEquals(listOf(10, 0), ManualSelection.codecOptions(selectable))
    }

    @Test
    fun availableRatesDecodeMultiBitMasksPerCodec() {
        val selectable = listOf(
            caps(codecType = 10, rateMask = 43),   // 32|8|2|1
            caps(codecType = 0, rateMask = 3),     // 2|1
        )
        assertEquals(setOf(32, 8, 2, 1), ManualSelection.availableRates(10, selectable))
        assertEquals(setOf(2, 1), ManualSelection.availableRates(0, selectable))
        assertTrue(ManualSelection.availableRates(4, selectable).isEmpty())
    }

    @Test
    fun availableBitsAreGatedBySameEntryRate() {
        assertEquals(setOf(1), ManualSelection.availableBits(4, rate = 32, ldacSplitSelectable))
        assertEquals(setOf(2, 1), ManualSelection.availableBits(4, rate = 8, ldacSplitSelectable))
        assertTrue(ManualSelection.availableBits(4, rate = 2, ldacSplitSelectable).isEmpty())
    }

    @Test
    fun buildTargetRejectsCrossEntryAssembly() {
        // 192k+24bit 没有同一条目证明 → 不可写
        assertNull(
            ManualSelection.buildTarget(
                current = ldacCurrent, codecType = 4, rate = 32, bits = 2,
                qualityCs1 = null, selectable = ldacSplitSelectable,
            )
        )
    }

    @Test
    fun buildTargetUsesSameEntryProofAndEntryChannel() {
        val target = ManualSelection.buildTarget(
            current = ldacCurrent, codecType = 4, rate = 8, bits = 2,
            qualityCs1 = null, selectable = ldacSplitSelectable,
        )!!
        assertEquals(4, target.codecType)
        assertEquals(8, target.sampleRate)
        assertEquals(2, target.bitsPerSample)
        assertEquals(2, target.channelMode)
        assertEquals(1_000_000, target.priority)
        // 与 current 同 codecType：cs1/cs2~cs4 继承当前连接（不臆造）
        assertEquals(1001L, target.cs1)
    }

    @Test
    fun buildTargetAcrossCodecTypesZeroesCodecSpecifics() {
        val current = CodecDeviceFixtures.iqoo15Golden   // type=10, cs1=50888712, cs3=1543503872
        val selectable = listOf(
            caps(codecType = 10, rateMask = 32 or 8, bitsMask = 2),
            caps(codecType = 0, rateMask = 3, bitsMask = 1),
        )
        val target = ManualSelection.buildTarget(
            current = current, codecType = 0, rate = 2, bits = 1,
            qualityCs1 = null, selectable = selectable,
        )!!
        assertEquals(0, target.codecType)
        assertEquals(0L, target.cs1)
        assertEquals(0L, target.cs3)
    }

    @Test
    fun buildTargetAcceptsLiveComboEvenWithoutSelectableProof() {
        // selectable 缺失/未暴露时，当前生效组合本身就是证据（写回 live 值是安全的）
        val current = CodecConfig(19, 0, 8, 2, 2, 0x8006L, 0L, 0L, 0L)
        val target = ManualSelection.buildTarget(
            current = current, codecType = 19, rate = 8, bits = 2,
            qualityCs1 = null, selectable = emptyList(),
        )!!
        assertEquals(2, target.channelMode)
        assertEquals(0x8006L, target.cs1)
    }

    @Test
    fun buildTargetQualityOverrideReplacesCs1() {
        val target = ManualSelection.buildTarget(
            current = ldacCurrent, codecType = 4, rate = 8, bits = 1,
            qualityCs1 = 1000L, selectable = ldacSplitSelectable,
        )!!
        assertEquals(1000L, target.cs1)
    }

    @Test
    fun buildTargetRejectsRateBelowCurrentWithoutProof() {
        // 降档同样要证明：selectable 只证明 96k 时，48k 候选不可写
        val selectable = listOf(caps(codecType = 4, rateMask = 8, bitsMask = 2))
        assertNull(
            ManualSelection.buildTarget(
                current = ldacCurrent.copy(sampleRate = 8, bitsPerSample = 2),
                codecType = 4, rate = 2, bits = 2,
                qualityCs1 = null, selectable = selectable,
            )
        )
    }

    @Test
    fun buildTargetRejectsNonLadderValues() {
        // 0 是通配语义、3 是多比特：都不是可写入的单档位，必须拒绝
        // （0 若放行会被位与检查 `x and 0 == 0` 永远通过而漏进候选）
        assertNull(
            ManualSelection.buildTarget(
                current = ldacCurrent, codecType = 4, rate = 0, bits = 1,
                qualityCs1 = null, selectable = ldacSplitSelectable,
            )
        )
        assertNull(
            ManualSelection.buildTarget(
                current = ldacCurrent, codecType = 4, rate = 8, bits = 0,
                qualityCs1 = null, selectable = ldacSplitSelectable,
            )
        )
        assertNull(
            ManualSelection.buildTarget(
                current = ldacCurrent, codecType = 4, rate = 3, bits = 1,
                qualityCs1 = null, selectable = ldacSplitSelectable,
            )
        )
    }

    @Test
    fun familyOfNeedsCapsProofForVendorLhdc() {
        assertEquals(CodecFamily.LDAC, ManualSelection.familyOf(4, emptyList()))
        assertEquals(CodecFamily.LHDC, ManualSelection.familyOf(10, emptyList()))
        // ColorOS 19 号：caps 含 176.4/192 位才可认 LHDC
        assertEquals(
            CodecFamily.LHDC,
            ManualSelection.familyOf(19, listOf(caps(19, CodecDeviceFixtures.phzCapsMask))),
        )
        assertEquals(
            CodecFamily.UNKNOWN,
            ManualSelection.familyOf(19, listOf(caps(19, CodecDeviceFixtures.nxCapsMask))),
        )
        assertEquals(CodecFamily.UNKNOWN, ManualSelection.familyOf(0, emptyList()))
    }

    @Test
    fun ldacQualityOptionsFollowAospStandardValues() {
        val options = ManualSelection.qualityOptions(4, emptyList(), ldacCurrent)
        assertEquals(listOf(1000L, 1001L, 1002L, 1003L), options.map { it.cs1 })
        assertEquals(listOf("990kbps", "660kbps", "330kbps", "自适应"), options.map { it.label })
    }

    @Test
    fun lhdcQualityOptionsPreserveCurrentHighBits() {
        val current = CodecDeviceFixtures.iqoo15Golden   // cs1=50888712 (0x03088008)
        val options = ManualSelection.qualityOptions(10, emptyList(), current)
        assertEquals(10, options.size)
        // 1000kbps 档必须精确还原 golden 值（高位 0x0308 保留，不是裸 0x8008）
        assertEquals(50888712L, options.first { it.label == "1000kbps" }.cs1)
        assertEquals(50888713L, options.first { it.label == "自适应" }.cs1)   // 0x03088009
        assertEquals(50888704L, options.first { it.label == "64kbps" }.cs1)   // 0x03088000
    }

    @Test
    fun lhdcQualityOptionsCrossCodecUseBareProvenShape() {
        // 跨 codecType 无 current.cs1 高位可继承 → 裸 0x8000|idx（OPD 真机 0x8006 实证形态）
        val sbcCurrent = CodecConfig(0, 0, 2, 1, 2, 0L, 0L, 0L, 0L)
        val options = ManualSelection.qualityOptions(10, emptyList(), sbcCurrent)
        assertEquals(0x8008L, options.first { it.label == "1000kbps" }.cs1)
        assertEquals(0x8009L, options.first { it.label == "自适应" }.cs1)
    }

    @Test
    fun qualityOptionsEmptyForCodecsWithoutKnownLadder() {
        val sbcCurrent = CodecConfig(0, 0, 2, 1, 2, 0L, 0L, 0L, 0L)
        assertTrue(ManualSelection.qualityOptions(0, emptyList(), sbcCurrent).isEmpty())
        assertTrue(ManualSelection.qualityOptions(1, emptyList(), sbcCurrent).isEmpty())
    }

    @Test
    fun adaptiveTargetReturnsWildcardWithFamilyAdaptiveCs1() {
        val ldac = ManualSelection.adaptiveTarget(ldacCurrent, emptyList())
        assertEquals(4, ldac.codecType)
        assertEquals(0, ldac.priority)
        assertEquals(0, ldac.sampleRate)
        assertEquals(0, ldac.bitsPerSample)
        assertEquals(0, ldac.channelMode)
        assertEquals(1003L, ldac.cs1)   // LDAC_QUALITY_ADAPTIVE

        val lhdc = ManualSelection.adaptiveTarget(CodecDeviceFixtures.iqoo15Golden, emptyList())
        assertEquals(10, lhdc.codecType)
        assertEquals(0, lhdc.sampleRate)
        assertEquals(50888713L, lhdc.cs1)   // 保留高位 + 自适应档

        // 未知家族不臆造自适应值：保持当前 cs1，仅 rate/bits/channel 通配
        val sbcCurrent = CodecConfig(0, 0, 2, 1, 2, 0L, 0L, 0L, 0L)
        val sbc = ManualSelection.adaptiveTarget(sbcCurrent, emptyList())
        assertEquals(0L, sbc.cs1)
        assertEquals(0, sbc.sampleRate)
    }
}
