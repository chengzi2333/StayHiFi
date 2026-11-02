package com.chengzi.stayhifi.codec

import org.json.JSONException
import org.json.JSONObject

enum class DumpStatus { OK, EMPTY, TIMEOUT, ERROR, TRUNCATED }

data class RealtimeDiagnosticDump(
    val bluetooth: Source,
    val audio: Source,
    val audioFlinger: Source,
) {
    data class Source(
        val status: DumpStatus,
        val content: String = "",
        val error: String? = null,
    )

    companion object {
        fun parse(raw: String): RealtimeDiagnosticDump = try {
            val root = JSONObject(raw)
            RealtimeDiagnosticDump(
                bluetooth = parseSource(root.getJSONObject("bluetooth")),
                audio = parseSource(root.getJSONObject("audio")),
                audioFlinger = parseSource(root.getJSONObject("audioFlinger")),
            )
        } catch (e: JSONException) {
            throw IllegalArgumentException("Invalid realtime diagnostics JSON", e)
        }

        private fun parseSource(obj: JSONObject): Source {
            val status = DumpStatus.valueOf(obj.getString("status").uppercase())
            return Source(
                status = status,
                content = obj.optString("content"),
                error = if (obj.has("error") && !obj.isNull("error")) obj.getString("error") else null,
            )
        }
    }
}
