package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.data.ApplyMode
import com.chengzi.stayhifi.data.ApplyPreference
import com.chengzi.stayhifi.data.ApplyPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferredApplyRunnerTest {

    private val mac = "AA:BB:CC:DD:EE:01"
    private val target = CodecConfig(19, 1_000_000, 32, 2, 2, 0x8008, 0, 0, 0)

    @Test
    fun systemAdaptiveSkipsAutomaticMax() = runTest {
        val store = FakePreferences(ApplyPreference(ApplyMode.SYSTEM_ADAPTIVE, target))
        var autoCalls = 0

        val result = PreferredApplyRunner(store).run(
            mac = mac,
            current = target,
            applyAuto = { autoCalls++ },
            applyCustom = { ManualApplyResult.APPLIED },
        )

        assertEquals(PreferredApplyResult.SKIP_ADAPTIVE, result)
        assertEquals(0, autoCalls)
    }

    @Test
    fun matchingCustomDoesNotWriteAgain() = runTest {
        val store = FakePreferences(ApplyPreference(ApplyMode.CUSTOM, target))
        var customCalls = 0

        val result = PreferredApplyRunner(store).run(
            mac = mac,
            current = target,
            applyAuto = { customCalls++ },
            applyCustom = { ManualApplyResult.APPLIED },
        )

        assertEquals(PreferredApplyResult.CUSTOM_ALREADY_ALIGNED, result)
        assertEquals(0, customCalls)
    }

    @Test
    fun rejectedCustomFallsBackToAdaptiveAndRetainsTargetForRetry() = runTest {
        val store = FakePreferences(ApplyPreference(ApplyMode.CUSTOM, target))
        val current = target.copy(sampleRate = 2)

        val result = PreferredApplyRunner(store).run(
            mac = mac,
            current = current,
            applyAuto = {},
            applyCustom = { ManualApplyResult.REJECTED_BY_STACK },
        )

        assertEquals(PreferredApplyResult.CUSTOM_REJECTED, result)
        assertEquals(ApplyMode.SYSTEM_ADAPTIVE, store.value.mode)
        assertEquals(target, store.value.lastTarget)
    }

    @Test
    fun ordinaryFailureKeepsCustomForNextConnection() = runTest {
        val store = FakePreferences(ApplyPreference(ApplyMode.CUSTOM, target))
        val current = target.copy(sampleRate = 2)

        val result = PreferredApplyRunner(store).run(
            mac = mac,
            current = current,
            applyAuto = {},
            applyCustom = { ManualApplyResult.FAILED },
        )

        assertEquals(PreferredApplyResult.CUSTOM_FAILED, result)
        assertEquals(ApplyMode.CUSTOM, store.value.mode)
        assertEquals(target, store.value.lastTarget)
    }

    @Test
    fun autoMaxUsesExistingDerivedPath() = runTest {
        val store = FakePreferences(ApplyPreference(ApplyMode.AUTO_MAX, null))
        var autoCalls = 0

        val result = PreferredApplyRunner(store).run(
            mac = mac,
            current = target,
            applyAuto = { autoCalls++ },
            applyCustom = { ManualApplyResult.APPLIED },
        )

        assertEquals(PreferredApplyResult.AUTO_MAX, result)
        assertEquals(1, autoCalls)
    }

    private class FakePreferences(var value: ApplyPreference) : ApplyPreferences {
        override suspend fun get(mac: String): ApplyPreference = value
        override suspend fun setCustom(mac: String, target: CodecConfig) {
            value = ApplyPreference(ApplyMode.CUSTOM, target)
        }
        override suspend fun setSystemAdaptive(mac: String, lastTarget: CodecConfig?) {
            value = ApplyPreference(ApplyMode.SYSTEM_ADAPTIVE, lastTarget)
        }
        override suspend fun setAutoMax(mac: String) {
            value = ApplyPreference(ApplyMode.AUTO_MAX, null)
        }
    }
}
