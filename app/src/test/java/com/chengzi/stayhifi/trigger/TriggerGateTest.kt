package com.chengzi.stayhifi.trigger

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerGateTest {

    @Test
    fun disconnectClearsCompletedWindow() {
        var now = 0L
        val gate = TriggerGate(nowMillis = { now })
        val permit = gate.tryAcquire("AA:BB")!!
        gate.release(permit)

        assertNull(gate.tryAcquire("AA:BB"))
        gate.onDisconnected("AA:BB")

        assertNotNull(gate.tryAcquire("AA:BB"))
    }

    @Test
    fun disconnectDuringActiveDoesNotAllowConcurrencyOrCreateCooldown() {
        val gate = TriggerGate(nowMillis = { 0L })
        val active = gate.tryAcquire("AA:BB")!!

        gate.onDisconnected("AA:BB")
        assertNull(gate.tryAcquire("AA:BB", force = true))
        gate.release(active)

        assertNotNull(gate.tryAcquire("AA:BB"))
    }

    @Test
    fun duplicateAndLateReleaseCannotClearNewPermit() {
        var now = 0L
        val gate = TriggerGate(nowMillis = { now })
        val old = gate.tryAcquire("AA:BB")!!
        gate.release(old)
        now = 10_000L
        val current = gate.tryAcquire("AA:BB")!!

        gate.release(old)
        gate.release(old)

        assertNull(gate.tryAcquire("AA:BB", force = true))
        gate.release(current)
        assertNotNull(gate.tryAcquire("AA:BB", force = true))
    }

    @Test
    fun macNormalizationAppliesToGateOperations() {
        val gate = TriggerGate(nowMillis = { 0L })
        val permit = gate.tryAcquire("aa:bb:cc")!!

        assertNull(gate.tryAcquire("AA:BB:CC"))
        gate.onDisconnected("Aa:Bb:Cc")
        gate.release(permit)

        assertNotNull(gate.tryAcquire("AA:BB:CC"))
    }
}
