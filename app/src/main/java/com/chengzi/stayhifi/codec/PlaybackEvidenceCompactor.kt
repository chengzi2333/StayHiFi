package com.chengzi.stayhifi.codec

data class TimedDiagnosticDump(
    val atMs: Long,
    val dump: RealtimeDiagnosticDump,
)

object PlaybackEvidenceCompactor {
    private const val MAX_REPORT_LINES = 60
    private val macRegex = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    fun compact(
        windowId: Long,
        mac: String,
        samples: List<TimedDiagnosticDump>,
    ): List<String> {
        val observations = mutableListOf<Observation>()
        val statuses = linkedSetOf<String>()
        for (sample in samples) {
            for ((sourceName, source) in sources(sample.dump)) {
                if (source.status != DumpStatus.OK && source.status != DumpStatus.EMPTY) {
                    val detail = source.error?.let { safeError(it) }?.let { " error=$it" }.orEmpty()
                    statuses += "source=$sourceName status=${source.status.name.lowercase()}$detail"
                }
                source.content.lineSequence().forEach { line ->
                    sanitize(line)?.let { observations += Observation(sourceName, sample.atMs, it) }
                }
            }
        }

        val lines = mutableListOf(
            "RealtimeDiag v=1 window=$windowId mac=$mac samples=${samples.size}",
        )
        lines += statuses
        lines += compactObservations(observations)
        return if (lines.size <= MAX_REPORT_LINES) lines else {
            lines.take(MAX_REPORT_LINES - 1) + "truncated=true"
        }
    }

    private fun compactObservations(observations: List<Observation>): List<String> {
        val groups = LinkedHashMap<String, MutableList<Observation>>()
        for (observation in observations) {
            groups.getOrPut(fieldKey(observation.text)) { mutableListOf() }.add(observation)
        }
        return groups.values.map { group ->
            val first = group.first()
            val values = group.map { fieldValue(it.text) }.distinct()
            val sources = group.map { it.source }.distinct().joinToString(",")
            val times = group.map { it.atMs }.distinct().joinToString(",")
            val value = if (values.size == 1) {
                first.text
            } else {
                val separator = if ('=' in first.text) '=' else ':'
                "${fieldKey(first.text)}$separator${values.joinToString(" → ")}"
            }
            "$value [sources=$sources times=$times]"
        }
    }

    private fun fieldKey(text: String): String {
        val equals = text.indexOf('=')
        val colon = text.indexOf(':')
        val index = listOf(equals, colon).filter { it > 0 }.minOrNull() ?: return text
        return text.substring(0, index).trim()
    }

    private fun fieldValue(text: String): String {
        val key = fieldKey(text)
        if (key == text) return text
        return text.substring(key.length).trimStart().removePrefix("=").removePrefix(":").trim()
    }

    private fun sanitize(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        if (line.contains("bdaddr", ignoreCase = true)) {
            val mac = macRegex.find(line)?.value ?: return null
            return "bdaddr=$mac"
        }
        if (SENSITIVE_MARKERS.any { line.contains(it, ignoreCase = true) }) return null
        if (line.contains("device=", ignoreCase = true)) {
            val value = line.substringAfter("device=").trim()
            if (!SAFE_DEVICE_VALUE.matches(value) && !macRegex.matches(value)) return null
        }
        return line
    }

    private fun safeError(raw: String): String {
        if (SENSITIVE_MARKERS.any { raw.contains(it, ignoreCase = true) }) return "details-dropped"
        return raw.take(120)
    }

    private fun sources(dump: RealtimeDiagnosticDump) = listOf(
        "bluetooth" to dump.bluetooth,
        "audio" to dump.audio,
        "audioFlinger" to dump.audioFlinger,
    )

    private data class Observation(val source: String, val atMs: Long, val text: String)

    private val SENSITIVE_MARKERS = listOf("name:", "package", "process", "path=", "/data/", "/storage/")
    private val SAFE_DEVICE_VALUE = Regex("(?i)(a2dp|bluetooth|bt|ble|[0-9x]+)")
}
