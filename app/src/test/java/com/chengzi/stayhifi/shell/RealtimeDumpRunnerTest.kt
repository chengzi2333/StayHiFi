package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.codec.DumpStatus
import com.chengzi.stayhifi.codec.RealtimeDiagnosticDump
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeDumpRunnerTest {

    @Test
    fun targetSectionKeepsAllLinesAndStopsAtNextCodec() {
        val result = runner(
            "bluetooth_manager" to FakeProcess(
                stdout = """
                    A2DP LHDC V5 State:
                      LHDC quality mode : ABR
                      custom future field : 12345
                    A2DP LDAC State:
                      LDAC transmission bitrate : -1
                """.trimIndent(),
            ),
        ).run("LHDC V5")

        assertEquals(DumpStatus.OK, result.bluetooth.status)
        assertTrue(result.bluetooth.content.contains("custom future field : 12345"))
        assertFalse(result.bluetooth.content.contains("LDAC transmission bitrate"))
    }

    @Test
    fun lineLimitIsMarkedTruncated() {
        val output = (1..121).joinToString("\n") { "sample rate: $it" }
        val result = runner("audio" to FakeProcess(stdout = output)).run("LHDC V5")

        assertEquals(DumpStatus.TRUNCATED, result.audio.status)
        assertEquals(120, result.audio.content.split('\n').count { it.isNotEmpty() })
    }

    @Test
    fun byteLimitIsMarkedTruncated() {
        val result = runner(
            "audio" to FakeProcess(stdout = "sample rate: " + "8".repeat(20_000)),
        ).run("LHDC V5")

        assertEquals(DumpStatus.TRUNCATED, result.audio.status)
        assertTrue(result.audio.content.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
    }

    @Test
    fun audioTimeoutDoesNotBlockOtherSources() {
        val result = runner(
            "bluetooth_manager" to FakeProcess(stdout = "A2DP LHDC V5 State:\n  field: 1"),
            "audio" to FakeProcess(stdout = "sample rate: 48000", waitForResult = false),
            "media.audio_flinger" to FakeProcess(stdout = "sample rate: 192000"),
        ).run("LHDC V5")

        assertEquals(DumpStatus.OK, result.bluetooth.status)
        assertEquals(DumpStatus.TIMEOUT, result.audio.status)
        assertEquals(DumpStatus.OK, result.audioFlinger.status)
        assertTrue(result.audioFlinger.content.contains("192000"))
    }

    @Test
    fun emptyOutputsAreReportedIndividually() {
        val result = runner().run("LHDC V5")

        assertEquals(DumpStatus.EMPTY, result.bluetooth.status)
        assertEquals(DumpStatus.EMPTY, result.audio.status)
        assertEquals(DumpStatus.EMPTY, result.audioFlinger.status)
    }

    @Test
    fun errorIsShortAndOtherSourcesRemainReadable() {
        val result = runner(
            "bluetooth_manager" to FakeProcess(stdout = "A2DP LHDC V5 State:\n  field: 1"),
            "audio" to FakeProcess(
                stdout = "sample rate: 48000",
                stderr = "permission denied " + "x".repeat(5_000) + "END_OF_STDERR",
                exitCode = 1,
            ),
            "media.audio_flinger" to FakeProcess(stdout = "sample rate: 192000"),
        ).run("LHDC V5")

        assertEquals(DumpStatus.ERROR, result.audio.status)
        assertTrue(result.audio.error!!.length <= 240)
        assertFalse(result.audio.error!!.contains("END_OF_STDERR"))
        assertEquals(DumpStatus.OK, result.bluetooth.status)
        assertEquals(DumpStatus.OK, result.audioFlinger.status)
    }

    private fun runner(vararg processes: Pair<String, Process>): RealtimeDumpRunner {
        val byService = processes.toMap()
        return RealtimeDumpRunner { service ->
            byService[service] ?: FakeProcess()
        }
    }

    private class FakeProcess(
        stdout: String = "",
        stderr: String = "",
        private val exitCode: Int = 0,
        private val waitForResult: Boolean = true,
    ) : Process() {
        private val stdoutBytes = ByteArrayInputStream(stdout.toByteArray())
        private val stderrBytes = ByteArrayInputStream(stderr.toByteArray())
        private val stdin = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutBytes
        override fun getErrorStream(): InputStream = stderrBytes
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = waitForResult
        override fun exitValue(): Int = exitCode
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
    }
}
