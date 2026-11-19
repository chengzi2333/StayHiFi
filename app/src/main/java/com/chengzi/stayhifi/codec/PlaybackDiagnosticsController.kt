package com.chengzi.stayhifi.codec

import com.chengzi.stayhifi.LogStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackDiagnosticsController(
    private val scope: CoroutineScope,
    val store: PlaybackDiagnosticsStore,
    private val resolveConnection: suspend () -> Connection?,
    private val openCapture: suspend (Connection) -> Capture?,
    private val sampleDelay: suspend (Long) -> Unit = { delay(it) },
    /**
     * v1.2.5 功耗治理：采样窗口是否允许（生产=App 在前台）。
     * 后台被系统级播放回调唤醒时不采样——一次窗口最坏 3 采样 × 3 个 dumpsys，
     * 其中 media.audio_flinger 极重，是后台发热的主要来源之一。
     */
    private val canCapture: () -> Boolean = { true },
    private val log: (String) -> Unit = { LogStore.log(it) },
) {
    data class Connection(val mac: String, val providerGeneration: Long)
    data class CaptureSignature(val mac: String, val current: CodecConfig, val providerGeneration: Long)

    interface Capture {
        var signature: CaptureSignature
        suspend fun sample(index: Int): RealtimeDiagnosticDump
        fun close()
    }

    private sealed interface Event {
        data class Signal(val observed: CaptureSignature?) : Event
        data object Stopped : Event
        data object Clear : Event
    }

    private val events = Channel<Event>(Channel.CONFLATED)
    private val clearRequested = AtomicBoolean(false)
    @Volatile private var playing = false
    /** 后台跳过采样只记一次，避免每首歌都刷一行。 */
    private var captureBlockedLogged = false
    private var needsResolution = true
    private var currentSignature: CaptureSignature? = null
    private val completed = mutableSetOf<CaptureSignature>()
    private val failedConnections = mutableSetOf<Connection>()

    init {
        scope.launch {
            for (event in events) {
                if (clearRequested.getAndSet(false)) onClear()
                when (event) {
                    is Event.Signal -> onSignal(event.observed)
                    Event.Stopped -> onStopped()
                    Event.Clear -> Unit
                }
            }
        }
    }

    fun onPlaybackSignal(observed: CaptureSignature? = null) {
        playing = true
        events.trySend(Event.Signal(observed))
    }

    fun onPlaybackStopped() {
        playing = false
        events.trySend(Event.Stopped)
    }

    fun clear() {
        clearRequested.set(true)
        store.clear()
        events.trySend(Event.Clear)
    }

    private suspend fun onSignal(observed: CaptureSignature?) {
        if (observed != null) {
            when {
                observed in completed -> {
                    currentSignature = observed
                    needsResolution = false
                }
                currentSignature != null && currentSignature != observed -> {
                    store.clear()
                    currentSignature = observed
                    needsResolution = true
                    failedConnections.remove(Connection(observed.mac, observed.providerGeneration))
                }
            }
        }
        captureIfNeeded()
    }

    private fun onStopped() {
        failedConnections.clear()
        if (currentSignature !in completed) needsResolution = true
    }

    private suspend fun onClear() {
        completed.clear()
        failedConnections.clear()
        currentSignature = null
        needsResolution = true
        // 清空只失效旧证据；下一次明确的播放/配置信号才允许新窗口。
    }

    private suspend fun captureIfNeeded() {
        if (!playing || !needsResolution) return
        if (!canCapture()) {
            // 保持 needsResolution=true：回到前台后的下一次播放信号会补上这次采样。
            if (!captureBlockedLogged) {
                captureBlockedLogged = true
                log("diagnostic capture skipped: app in background")
            }
            return
        }
        captureBlockedLogged = false
        val connection = try {
            resolveConnection() ?: return
        } catch (t: Throwable) {
            log("diagnostic connection error: $t")
            return
        }
        if (connection in failedConnections) return
        val active = currentSignature
        if (active != null &&
            (active.mac != connection.mac || active.providerGeneration != connection.providerGeneration)
        ) {
            store.clear()
            currentSignature = null
            needsResolution = true
        }
        if (!needsResolution) return

        val capture = try {
            withTimeoutOrNull(12_000) { openCapture(connection) }
        } catch (t: Throwable) {
            failedConnections += connection
            log("diagnostic open error: $t")
            return
        }
        if (capture == null) {
            failedConnections += connection
            log("diagnostic open timeout or capture unavailable mac=${connection.mac}")
            return
        }

        try {
            val initial = capture.signature
            if (initial in completed) {
                currentSignature = initial
                needsResolution = false
                return
            }
            currentSignature = initial
            val windowGeneration = store.generation
            val samples = mutableListOf<TimedDiagnosticDump>()
            var attempted = false
            var configChanged = false
            for (index in 0..2) {
                if (index > 0) sampleDelay(if (index == 1) 3_000 else 5_000)
                if (!playing || windowGeneration != store.generation) break
                attempted = true
                val dump = withTimeoutOrNull(12_000) { capture.sample(index) }
                if (dump == null) {
                    log("diagnostic timeout sample=$index mac=${initial.mac}")
                    break
                }
                // 暂停/清空期间返回的旧样本不得进入新证据，也不得占用其时间点。
                if (!playing || windowGeneration != store.generation) break
                samples += TimedDiagnosticDump(SAMPLE_TIMES[index], dump)
                if (capture.signature != initial) {
                    currentSignature = capture.signature
                    needsResolution = true
                    configChanged = true
                    store.clear()
                    break
                }
            }
            if (!configChanged) {
                if (samples.isNotEmpty() && windowGeneration == store.generation) {
                    store.replace(
                        windowGeneration,
                        PlaybackEvidenceCompactor.compact(windowGeneration, initial.mac, samples),
                    )
                }
                if (samples.isNotEmpty()) {
                    completed += initial
                }
                currentSignature = initial
                if (attempted) needsResolution = false
            }
        } catch (t: Throwable) {
            log("diagnostic capture error: $t")
        } finally {
            capture.close()
        }
    }

    companion object {
        private val SAMPLE_TIMES = longArrayOf(0, 3_000, 8_000)
    }
}
