package com.chengzi.stayhifi.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QsTileCollapseDecisionTest {

    @Test
    fun api34UsesDeprecatedIntentOverload() {
        assertFalse(QsTileCollapseDecision.usePendingIntent(sdkInt = 34))
    }

    @Test
    fun api35UsesPendingIntentOverload() {
        assertTrue(QsTileCollapseDecision.usePendingIntent(sdkInt = 35))
    }
}
