package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 7：RealtimeDisplayFormatter 单测——首页「实际传输」行唯一文案入口。
 * 四态文案逐字固定：NOT_PLAYING=未在播放、STACK_REPORTS_IDLE=暂无法确认、
 * NO_DATA=暂无法读取、AVAILABLE=码率行（ABR 必须带「自适应」三字）。
 */
class RealtimeDisplayFormatterTest {

    @Test
    fun notPlayingShowsNotPlayingText() {
        assertEquals("未在播放", RealtimeDisplayFormatter.text(RealtimeState(RealtimeDisplay.NOT_PLAYING)))
    }

    @Test
    fun stackReportsIdleShowsUnknownText() {
        // PLK110 实证态：播放中但栈回报 -1，不能说未在播放，只能说暂无法确认
        assertEquals(
            "暂无法确认",
            RealtimeDisplayFormatter.text(RealtimeState(RealtimeDisplay.STACK_REPORTS_IDLE)),
        )
    }

    @Test
    fun noDataShowsUnsupportedText() {
        assertEquals(
            "暂无法读取",
            RealtimeDisplayFormatter.text(RealtimeState(RealtimeDisplay.NO_DATA)),
        )
    }

    @Test
    fun availableWithAbrShowsAdaptiveSuffix() {
        val state = RealtimeState(RealtimeDisplay.AVAILABLE, bitrateKbps = 400, qualityMode = "ABR")
        assertEquals("400 kbps · ABR（自适应）", RealtimeDisplayFormatter.text(state))
    }

    @Test
    fun availableWithPlainQualityModeShowsMode() {
        val state = RealtimeState(RealtimeDisplay.AVAILABLE, bitrateKbps = 500, qualityMode = "MID")
        assertEquals("500 kbps · MID", RealtimeDisplayFormatter.text(state))
    }

    @Test
    fun availableWithoutQualityModeShowsBitrateOnly() {
        val state = RealtimeState(RealtimeDisplay.AVAILABLE, bitrateKbps = 990)
        assertEquals("990 kbps", RealtimeDisplayFormatter.text(state))
    }

    @Test
    fun availableWithNullBitrateFallsBackToUnknownText() {
        // 防御：AVAILABLE 构造时缺码率（旧调用直传 null）不得显示 "null kbps"
        val state = RealtimeState(RealtimeDisplay.AVAILABLE, bitrateKbps = null, qualityMode = "ABR")
        assertEquals("暂无法确认", RealtimeDisplayFormatter.text(state))
    }

    @Test
    fun availableWithNonPositiveBitrateFallsBackToUnknownText() {
        for (kbps in listOf(0, -1)) {
            val state = RealtimeState(RealtimeDisplay.AVAILABLE, bitrateKbps = kbps, qualityMode = "ABR")
            assertEquals("暂无法确认", RealtimeDisplayFormatter.text(state))
        }
    }

    @Test
    fun notStreamingComputedPropertyOnlyTrueForNotPlaying() {
        assertTrue(RealtimeState(RealtimeDisplay.NOT_PLAYING).notStreaming)
        assertFalse(RealtimeState(RealtimeDisplay.AVAILABLE, 400, "ABR").notStreaming)
        assertFalse(RealtimeState(RealtimeDisplay.NO_DATA).notStreaming)
        assertFalse(RealtimeState(RealtimeDisplay.STACK_REPORTS_IDLE).notStreaming)
    }
}
