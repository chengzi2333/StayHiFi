package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeDiagnosticDumpTest {

    @Test
    fun parseReadsThreeIndependentSources() {
        val result = RealtimeDiagnosticDump.parse(
            """
            {
              "bluetooth":{"status":"ok","content":"bt"},
              "audio":{"status":"timeout","content":"","error":"3s"},
              "audioFlinger":{"status":"truncated","content":"af"}
            }
            """.trimIndent(),
        )

        assertEquals(DumpStatus.OK, result.bluetooth.status)
        assertEquals("bt", result.bluetooth.content)
        assertEquals(DumpStatus.TIMEOUT, result.audio.status)
        assertEquals("3s", result.audio.error)
        assertEquals(DumpStatus.TRUNCATED, result.audioFlinger.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsMissingSource() {
        RealtimeDiagnosticDump.parse(
            """{"bluetooth":{"status":"ok","content":"bt"},"audio":{"status":"empty","content":""}}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseRejectsUnknownStatus() {
        RealtimeDiagnosticDump.parse(
            """
            {
              "bluetooth":{"status":"supported","content":"bt"},
              "audio":{"status":"empty","content":""},
              "audioFlinger":{"status":"empty","content":""}
            }
            """.trimIndent(),
        )
    }
}
