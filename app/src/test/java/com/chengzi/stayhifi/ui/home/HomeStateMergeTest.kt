package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.RealtimeDisplay
import com.chengzi.stayhifi.device.LocalHeadset
import com.chengzi.stayhifi.device.LocalHeadsetResult
import com.chengzi.stayhifi.trigger.TargetAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.2 边界修复回归：首页连接状态合并/降级的纯规则。
 * 覆盖 code review 三要点：A→B 立即作废旧参数、读取/解析失败统一降级、
 * 本机未知 + 特权超时不清连接。
 */
class HomeStateMergeTest {

    private val deviceA = LocalHeadset("OPPO Enco X3", "AA:BB:CC:DD:EE:01")
    private val deviceBSameName = LocalHeadset("OPPO Enco X3", "AA:BB:CC:DD:EE:02")
    private val deviceC = LocalHeadset("Sony WF-1000XM5", "AA:BB:CC:DD:EE:03")

    private val currentConfig = CodecConfig(10, 1000000, 32, 2, 2, 50888712L, 0L, 1543503872L, 0L)

    /** 模拟 A 设备读回成功后的满展示状态。 */
    private fun deviceAFullState() = HomeUiState(
        headsetName = deviceA.name,
        codec = "LHDC V5", rate = "192kHz", bits = "24bit", quality = "1000kbps",
        realtimeKbps = 400, realtimeQualityMode = "ABR",
        realtimeDisplay = RealtimeDisplay.AVAILABLE,
        fullBars = true, targetStatus = TargetAssessment.Status.COMPLETE,
        manualEditor = ManualEditorInput(currentConfig, listOf(currentConfig)),
        paramsAvailable = true,
    )

    // ---- 要点 1：设备变化立即作废旧参数 ----

    @Test
    fun firstConnectInvalidatesParamsAndFlagsDeviceChanged() {
        val result = HomeStateMerge.onLocalConnected(HomeUiState(), deviceA, connectedMac = null)
        assertTrue(result.deviceChanged)
        assertEquals(deviceA.name, result.state.headsetName)
        assertFalse(result.state.paramsAvailable)
        assertNull(result.state.codec)
        assertNull(result.state.manualEditor)
        assertEquals(RealtimeDisplay.NO_DATA, result.state.realtimeDisplay)
    }

    @Test
    fun sameDeviceRefreshKeepsParamsToAvoidPollingFlicker() {
        val previous = deviceAFullState()
        val result = HomeStateMerge.onLocalConnected(previous, deviceA, connectedMac = deviceA.mac)
        assertFalse(result.deviceChanged)
        // 同一设备 3s 轮询：展示参数原样保留，只同步名字
        assertEquals("LHDC V5", result.state.codec)
        assertEquals(400, result.state.realtimeKbps)
        assertTrue(result.state.paramsAvailable)
        assertTrue(result.state.fullBars)
        assertEquals(previous.manualEditor, result.state.manualEditor)
    }

    @Test
    fun sameDeviceRenameKeepsParams() {
        val renamed = deviceA.copy(name = "Enco X3 改名")
        val result = HomeStateMerge.onLocalConnected(deviceAFullState(), renamed, deviceA.mac)
        assertFalse(result.deviceChanged)
        assertEquals("Enco X3 改名", result.state.headsetName)
        assertTrue(result.state.paramsAvailable)
    }

    @Test
    fun deviceSwitchToDifferentNameInvalidatesEverything() {
        val result = HomeStateMerge.onLocalConnected(deviceAFullState(), deviceC, deviceA.mac)
        assertTrue(result.deviceChanged)
        assertEquals(deviceC.name, result.state.headsetName)
        assertFalse(result.state.paramsAvailable)
        assertNull(result.state.codec)
        assertNull(result.state.rate)
        assertNull(result.state.bits)
        assertNull(result.state.quality)
        assertNull(result.state.realtimeKbps)
        assertNull(result.state.realtimeQualityMode)
        assertEquals(RealtimeDisplay.NO_DATA, result.state.realtimeDisplay)
        assertFalse(result.state.fullBars)
        assertNull(result.state.targetStatus)
        assertFalse(result.state.manualActive)
        assertNull(result.state.manualEditor)
    }

    @Test
    fun deviceSwitchSameNameDifferentMacInvalidatesEverything() {
        // 同名不同 MAC：名字相同不足以判定同一设备，必须同样作废——
        // 否则新设备读回前会展示 A 的参数、且 Apply/自定义对 B 可用
        val result = HomeStateMerge.onLocalConnected(
            deviceAFullState(), deviceBSameName, deviceA.mac,
        )
        assertTrue(result.deviceChanged)
        assertEquals(deviceBSameName.name, result.state.headsetName)
        assertFalse(result.state.paramsAvailable)
        assertNull(result.state.codec)
        assertNull(result.state.manualEditor)
        assertNull(result.state.targetStatus)
    }

    @Test
    fun macComparisonIgnoresCase() {
        val result = HomeStateMerge.onLocalConnected(
            deviceAFullState(), deviceA, connectedMac = deviceA.mac.lowercase(),
        )
        assertFalse(result.deviceChanged)
        assertTrue(result.state.paramsAvailable)
    }

    // ---- 要点 2：读取/解析失败的统一降级 ----

    @Test
    fun paramsUnavailableKeepsHeadsetNameAndClearsAllParamFaces() {
        val degraded = HomeStateMerge.paramsUnavailable(deviceAFullState())
        assertEquals(deviceA.name, degraded.headsetName)
        assertFalse(degraded.paramsAvailable)
        assertNull(degraded.codec)
        assertNull(degraded.rate)
        assertNull(degraded.bits)
        assertNull(degraded.quality)
        assertNull(degraded.realtimeKbps)
        assertNull(degraded.realtimeQualityMode)
        assertEquals(RealtimeDisplay.NO_DATA, degraded.realtimeDisplay)
        assertFalse(degraded.fullBars)
        assertNull(degraded.targetStatus)
        assertFalse(degraded.manualActive)
        assertNull(degraded.manualEditor)
    }

    @Test
    fun paramsUnavailableDoesNotTouchCarrierAlert() {
        val withAlert = deviceAFullState().copy(carrierAlert = "Shizuku/Stellar 未运行")
        val degraded = HomeStateMerge.paramsUnavailable(withAlert)
        assertEquals("Shizuku/Stellar 未运行", degraded.carrierAlert)
    }

    // ---- 要点 3：本机未知 + 特权超时不清连接 ----

    @Test
    fun providerTimeoutWithUnknownLocalOnlyDegradesParams() {
        // UNKNOWN 不等于断开：本机读不到时特权 dump 超时只降级参数，保留既有连接态
        val previous = deviceAFullState()
        val degraded = HomeStateMerge.providerTimeoutDegrade(LocalHeadsetResult.Unknown, previous)
        assertEquals(deviceA.name, degraded.headsetName)
        assertFalse(degraded.paramsAvailable)
        assertNull(degraded.codec)
    }

    @Test
    fun providerTimeoutWithSecurityBlockedOnlyDegradesParams() {
        val previous = deviceAFullState()
        val degraded = HomeStateMerge.providerTimeoutDegrade(
            LocalHeadsetResult.SecurityBlocked, previous,
        )
        assertEquals(deviceA.name, degraded.headsetName)
        assertFalse(degraded.paramsAvailable)
    }

    @Test
    fun providerTimeoutWithLocalVerdictLeavesStateUntouched() {
        // 本机已裁决（连接/断开）时，特权超时不得动状态——防御未来路由改动
        val connectedLocal = LocalHeadsetResult.Connected(deviceA)
        val previous = deviceAFullState()
        assertTrue(
            HomeStateMerge.providerTimeoutDegrade(connectedLocal, previous) === previous,
        )
        assertTrue(
            HomeStateMerge.providerTimeoutDegrade(
                LocalHeadsetResult.Disconnected, previous,
            ) === previous,
        )
    }
}
