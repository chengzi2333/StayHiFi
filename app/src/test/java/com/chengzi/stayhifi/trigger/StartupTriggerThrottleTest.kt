package com.chengzi.stayhifi.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.2.5 功耗治理：FGS 重启风暴下「启动补触发」的节流判定。 */
class StartupTriggerThrottleTest {

    private val window = 120_000L

    @Test
    fun firstEverRunIsAllowed() {
        assertTrue(ListenerService.shouldRunStartupTrigger(now = 1_000_000L, last = 0L, windowMillis = window))
    }

    @Test
    fun withinWindowIsRejected() {
        val now = 1_000_000L
        assertFalse(ListenerService.shouldRunStartupTrigger(now, last = now - 1_000L, windowMillis = window))
        assertFalse(ListenerService.shouldRunStartupTrigger(now, last = now - 119_999L, windowMillis = window))
    }

    @Test
    fun windowBoundaryIsAllowed() {
        val now = 1_000_000L
        assertTrue(ListenerService.shouldRunStartupTrigger(now, last = now - window, windowMillis = window))
    }

    @Test
    fun clockGoingBackwardsIsTreatedAsRecent() {
        val now = 1_000_000L
        assertFalse(ListenerService.shouldRunStartupTrigger(now, last = now + 5_000L, windowMillis = window))
    }
}
