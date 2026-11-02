package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEvidenceCompactorTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    @Test
    fun duplicateFieldsCollapseAndChangesUseTimeline() {
        val first = TimedDiagnosticDump(0, dump(
            audio = "sampleRate=48000\ncustom field: stable",
            audioFlinger = "sampleRate=48000",
        ))
        val second = TimedDiagnosticDump(3_000, dump(
            audio = "sampleRate=192000\ncustom field: stable",
            audioFlinger = "sampleRate=192000",
        ))
        val third = TimedDiagnosticDump(8_000, dump(
            audio = "sampleRate=192000\ncustom field: stable",
            audioFlinger = "sampleRate=192000",
        ))

        val result = PlaybackEvidenceCompactor.compact(7, mac, listOf(first, second, third))
        val text = result.joinToString("\n")

        assertTrue(text.contains("sampleRate=48000 → 192000"))
        assertEquals(1, result.count { it.contains("custom field: stable") })
        assertTrue(result.single { it.contains("custom field: stable") }.contains("audio"))
        assertTrue(result.first().contains("window=7"))
        assertFalse(text.contains("selectable"))
    }

    @Test
    fun sensitiveFragmentsAreDroppedButEvidenceSurvives() {
        val sample = TimedDiagnosticDump(
            0,
            RealtimeDiagnosticDump(
                bluetooth = RealtimeDiagnosticDump.Source(
                    DumpStatus.OK,
                    "name: Enco X3\nbdaddr=AA:BB:CC:DD:EE:01\nLHDC transmission bitrate: 900000",
                ),
                audio = RealtimeDiagnosticDump.Source(
                    DumpStatus.TIMEOUT,
                    "package=com.example.music\nsample rate: 48000",
                    "3s",
                ),
                audioFlinger = RealtimeDiagnosticDump.Source(
                    DumpStatus.TRUNCATED,
                    "process=com.example.music\npath=/data/user/0/com.example.music/cache\n" +
                        "device=Enco X3\nsample rate: 192000",
                ),
            ),
        )

        val result = PlaybackEvidenceCompactor.compact(1, mac, listOf(sample))
        val text = result.joinToString("\n")

        assertFalse(text.contains("Enco X3"))
        assertFalse(text.contains("com.example.music"))
        assertFalse(text.contains("/data/"))
        assertTrue(text.contains(mac))
        assertTrue(text.contains("LHDC transmission bitrate: 900000"))
        assertTrue(text.contains("48000 → 192000"))
        assertTrue(text.contains("status=timeout"))
        assertTrue(text.contains("status=truncated"))
    }

    @Test
    fun outputIsCappedAtSixtyLines() {
        val content = (1..80).joinToString("\n") { "sample rate field$it: $it" }
        val sample = TimedDiagnosticDump(0, dump(audio = content))

        val result = PlaybackEvidenceCompactor.compact(1, mac, listOf(sample))

        assertEquals(60, result.size)
        assertEquals("truncated=true", result.last())
    }

    private fun dump(
        bluetooth: String = "",
        audio: String = "",
        audioFlinger: String = "",
    ) = RealtimeDiagnosticDump(
        bluetooth = RealtimeDiagnosticDump.Source(DumpStatus.OK, bluetooth),
        audio = RealtimeDiagnosticDump.Source(DumpStatus.OK, audio),
        audioFlinger = RealtimeDiagnosticDump.Source(DumpStatus.OK, audioFlinger),
    )
}
