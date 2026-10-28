package com.chengzi.stayhifi.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task D5：dumpDevices 健康检查判定（设计 §11.4.9）。
 * READY 最低条件之一是 dumpDevices 返回无 error 的 JSON 数组；空数组本身健康。
 */
class RootHealthCheckerTest {

    @Test
    fun emptyArrayIsHealthy() {
        val r = RootHealthChecker.checkDumpDevices("[]")
        assertEquals(RootHealthOutcome.OK, r.outcome)
        assertEquals(0, r.deviceCount)
        assertTrue(r.isReady)
    }

    @Test
    fun deviceArrayIsHealthy() {
        val r = RootHealthChecker.checkDumpDevices(
            """[{"name":"耳机","mac":"00:11:22:33:44:55"},{"name":"B","mac":"66:77:88:99:AA:BB"}]""")
        assertEquals(RootHealthOutcome.OK, r.outcome)
        assertEquals(2, r.deviceCount)
        assertTrue(r.isReady)
    }

    @Test
    fun errorJsonIsNotReady() {
        val r = RootHealthChecker.checkDumpDevices("""{"error":"dumpDevices: boom"}""")
        assertEquals(RootHealthOutcome.ERROR_REPORTED, r.outcome)
        assertFalse(r.isReady)
        assertTrue(r.detail.contains("boom"))
    }

    @Test
    fun malformedJsonIsNotReady() {
        for (raw in listOf("not-json", "", "123", "\"str\"", "null", "{}")) {
            val r = RootHealthChecker.checkDumpDevices(raw)
            assertEquals("'$raw' 应判定 MALFORMED", RootHealthOutcome.MALFORMED, r.outcome)
            assertFalse(r.isReady)
        }
    }

    @Test
    fun objectWithoutErrorIsMalformed() {
        val r = RootHealthChecker.checkDumpDevices("""{"devices":[]}""")
        assertEquals(RootHealthOutcome.MALFORMED, r.outcome)
        assertFalse(r.isReady)
    }
}
