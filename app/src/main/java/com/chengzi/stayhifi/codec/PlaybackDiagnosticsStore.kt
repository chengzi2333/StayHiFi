package com.chengzi.stayhifi.codec

class PlaybackDiagnosticsStore {
    @Volatile
    var generation: Long = 0L
        private set

    private var lines: List<String> = emptyList()

    @Synchronized
    fun replace(windowId: Long, lines: List<String>) {
        if (windowId != generation) return
        this.lines = lines.toList()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        generation++
        lines = emptyList()
    }
}
