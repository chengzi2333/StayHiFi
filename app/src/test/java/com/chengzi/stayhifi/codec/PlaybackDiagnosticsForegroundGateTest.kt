package com.chengzi.stayhifi.codec

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.2.5 功耗治理：播放诊断的 dumpsys 采样只允许在 App 前台打开采集窗口。
 * 后台被系统级播放回调唤醒时必须直接跳过，且回到前台后能补上这次采样。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackDiagnosticsForegroundGateTest {

    private val signature = PlaybackDiagnosticsController.CaptureSignature(
        mac = "AA:BB:CC:DD:EE:01",
        current = CodecConfig(
            codecType = 10, priority = 0, sampleRate = 192_000, bitsPerSample = 24,
            channelMode = 0, cs1 = 0, cs2 = 0, cs3 = 0, cs4 = 0,
        ),
        providerGeneration = 1L,
    )

    @Test
    fun backgroundDoesNotOpenCaptureButForegroundDoes() = runTest {
        var foreground = false
        var openAttempts = 0
        // Unconfined：信号在 trySend 时立即被消费，断言不依赖调度顺序。
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = PlaybackDiagnosticsController(
            scope = scope,
            store = PlaybackDiagnosticsStore(),
            resolveConnection = {
                PlaybackDiagnosticsController.Connection(signature.mac, signature.providerGeneration)
            },
            openCapture = {
                openAttempts += 1
                null
            },
            sampleDelay = { },
            canCapture = { foreground },
            log = { },
        )

        controller.onPlaybackSignal(signature)
        assertEquals("后台不应打开采集窗口", 0, openAttempts)

        foreground = true
        controller.onPlaybackSignal(signature)
        assertEquals("回到前台后应补上采样", 1, openAttempts)
        scope.cancel()
    }
}
