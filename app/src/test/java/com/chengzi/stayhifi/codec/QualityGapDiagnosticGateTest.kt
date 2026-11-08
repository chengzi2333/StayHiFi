package com.chengzi.stayhifi.codec

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityGapDiagnosticGateTest {

    @After
    fun tearDown() = QualityGapDiagnosticGate.reset()

    private fun presentation(
        codecType: Int,
        cs1: Long,
        family: CodecFamily,
        quality: String?,
    ) = CodecPresentation(
        current = CodecConfig(codecType, 1000, 2, 2, 2, cs1, 0L, 0L, 0L),
        identity = CodecIdentity(
            nativeType = codecType,
            family = family,
            displayName = "X",
            source = CodecIdentitySource.SYSTEM_TYPE,
        ),
        summary = CodecSummary("X", "48kHz", "24bit", quality),
        issues = emptySet(),
    )

    @Test
    fun qualityPresentNeverLogs() {
        val p = presentation(codecType = 10, cs1 = 0x8009, family = CodecFamily.LHDC, quality = "自适应")
        assertNull(QualityGapDiagnosticGate.reasonToLog("AA", p))
    }

    @Test
    fun ldacUnrecognizedValueLogsOncePerCs1() {
        val unknown = presentation(codecType = 4, cs1 = 9999, family = CodecFamily.LDAC, quality = null)
        assertEquals(
            QualityGapDiagnosticGate.Reason.UNRECOGNIZED_QUALITY_VALUE,
            QualityGapDiagnosticGate.reasonToLog("AA", unknown),
        )
        // 同一 mac+codecType+cs1 重复轮询不再记
        assertNull(QualityGapDiagnosticGate.reasonToLog("AA", unknown))
        // 新 cs1 值是新证据，要再记
        val other = presentation(codecType = 4, cs1 = 8888, family = CodecFamily.LDAC, quality = null)
        assertEquals(
            QualityGapDiagnosticGate.Reason.UNRECOGNIZED_QUALITY_VALUE,
            QualityGapDiagnosticGate.reasonToLog("AA", other),
        )
    }

    @Test
    fun familyWithoutQualityTierLogsOncePerCodec() {
        val aptx = presentation(codecType = 7, cs1 = 0, family = CodecFamily.APTX_ADAPTIVE, quality = null)
        assertEquals(
            QualityGapDiagnosticGate.Reason.FAMILY_WITHOUT_QUALITY_TIER,
            QualityGapDiagnosticGate.reasonToLog("AA", aptx),
        )
        // 无档位概念族：cs1 变化不是新证据（aptX Adaptive 的 cs 字段语义未收录），不重复记
        val aptxOther = presentation(codecType = 7, cs1 = 12345, family = CodecFamily.APTX_ADAPTIVE, quality = null)
        assertNull(QualityGapDiagnosticGate.reasonToLog("AA", aptxOther))
        // 换设备是新证据
        assertEquals(
            QualityGapDiagnosticGate.Reason.FAMILY_WITHOUT_QUALITY_TIER,
            QualityGapDiagnosticGate.reasonToLog("BB", aptx),
        )
    }

    @Test
    fun unknownFamilyCountsAsNoQualityTier() {
        val p = presentation(codecType = 19, cs1 = 42, family = CodecFamily.UNKNOWN, quality = null)
        assertEquals(
            QualityGapDiagnosticGate.Reason.FAMILY_WITHOUT_QUALITY_TIER,
            QualityGapDiagnosticGate.reasonToLog("AA", p),
        )
    }
}
