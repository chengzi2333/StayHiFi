package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.codec.DumpStatus
import com.chengzi.stayhifi.codec.RealtimeDiagnosticDump
import java.util.concurrent.TimeUnit

class RealtimeDumpRunner(
    private val startProcess: (String) -> Process = { service ->
        Runtime.getRuntime().exec(arrayOf("/system/bin/dumpsys", service))
    },
) {
    /** 全量：蓝牙 + audio + audio_flinger（三个独立 dumpsys，其中 audio_flinger 最重）。 */
    fun run(codecName: String): RealtimeDiagnosticDump {
        val bluetooth = runBluetooth(codecName)
        val (audio, audioFlinger) = runAux()
        return RealtimeDiagnosticDump(bluetooth, audio, audioFlinger)
    }

    /** 只跑蓝牙 dumpsys：调用方先看这一步能不能拿到码率，够用就不用再跑两个媒体 dump。 */
    fun runBluetooth(codecName: String): RealtimeDiagnosticDump.Source =
        runSource("bluetooth_manager", bluetoothFilter(codecName))

    /** 蓝牙 dump 缺码率时的补充证据（AudioTrack 采样率 / 混音线程格式）。 */
    fun runAux(): Pair<RealtimeDiagnosticDump.Source, RealtimeDiagnosticDump.Source> =
        runSource("audio", ::isAudioLine) to runSource("media.audio_flinger", ::isAudioLine)

    private fun runSource(
        service: String,
        filter: (String) -> Boolean,
    ): RealtimeDiagnosticDump.Source {
        var proc: Process? = null
        val text = LimitedText()
        val error = StringBuilder()
        return try {
            proc = startProcess(service)
            val process = proc
            val reader = Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) if (filter(line)) text.append(line)
                }
            }.apply { isDaemon = true; start() }
            val errDrainer = Thread {
                process.errorStream.bufferedReader().use { input ->
                    val buffer = CharArray(512)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(error) {
                            val remaining = MAX_ERROR_CHARS - error.length
                            if (remaining > 0) error.append(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(JOIN_TIMEOUT_MS)
                errDrainer.join(JOIN_TIMEOUT_MS)
                return RealtimeDiagnosticDump.Source(
                    DumpStatus.TIMEOUT,
                    text.content(),
                    "dumpsys $service 超时(${TIMEOUT_MS}ms)",
                )
            }
            reader.join(JOIN_TIMEOUT_MS)
            errDrainer.join(JOIN_TIMEOUT_MS)
            val exit = process.exitValue()
            if (exit != 0) {
                return RealtimeDiagnosticDump.Source(
                    DumpStatus.ERROR,
                    text.content(),
                    "exit=$exit: ${synchronized(error) { error.toString() }}",
                )
            }
            when {
                text.isTruncated() -> RealtimeDiagnosticDump.Source(DumpStatus.TRUNCATED, text.content())
                text.isEmpty() -> RealtimeDiagnosticDump.Source(DumpStatus.EMPTY)
                else -> RealtimeDiagnosticDump.Source(DumpStatus.OK, text.content())
            }
        } catch (t: Throwable) {
            RealtimeDiagnosticDump.Source(
                DumpStatus.ERROR,
                text.content(),
                t.toString().take(MAX_ERROR_CHARS),
            )
        } finally {
            proc?.destroy()
        }
    }

    private fun bluetoothFilter(codecName: String): (String) -> Boolean {
        val wanted = normalize(codecName)
        var inTarget = false
        return { raw ->
            val line = raw.trim()
            val header = SECTION_HEADER.matchEntire(line)
            if (header != null) {
                inTarget = normalize(header.groupValues[1]) == wanted
            }
            inTarget
        }
    }

    private fun isAudioLine(line: String): Boolean = AUDIO_KEYWORDS.any {
        line.contains(it, ignoreCase = true)
    }

    private fun normalize(value: String): String =
        value.replace(" ", "").replace("-", "").uppercase()

    private class LimitedText {
        private val text = StringBuilder()
        private var lines = 0
        private var bytes = 0
        private var truncated = false

        @Synchronized
        fun append(line: String) {
            if (lines >= MAX_LINES || bytes >= MAX_BYTES) {
                truncated = true
                return
            }
            val accepted = StringBuilder()
            for (char in line) {
                val size = char.toString().toByteArray(Charsets.UTF_8).size
                if (bytes + size + 1 > MAX_BYTES) {
                    truncated = true
                    break
                }
                accepted.append(char)
                bytes += size
            }
            if (accepted.isNotEmpty()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(accepted)
                bytes += 1
                lines++
            }
            if (accepted.length < line.length) truncated = true
        }

        @Synchronized
        fun content(): String = text.toString()

        @Synchronized
        fun isEmpty(): Boolean = text.isEmpty()

        @Synchronized
        fun isTruncated(): Boolean = truncated
    }

    companion object {
        private const val TIMEOUT_MS = 3_000L
        private const val JOIN_TIMEOUT_MS = 500L
        private const val MAX_LINES = 120
        private const val MAX_BYTES = 16 * 1024
        private const val MAX_ERROR_CHARS = 180
        private val SECTION_HEADER = Regex("A2DP\\s+(.+?)\\s+State:")
        private val AUDIO_KEYWORDS = listOf(
            "sample rate", "AudioTrack", "PlaybackThread", "MixerThread", "Output",
            "A2DP", "Bluetooth", "format", "channel", "frame", "stream", "device",
        )
    }
}
