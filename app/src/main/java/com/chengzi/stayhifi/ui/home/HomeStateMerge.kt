package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.codec.RealtimeDisplay
import com.chengzi.stayhifi.device.LocalHeadset
import com.chengzi.stayhifi.device.LocalHeadsetResult

/**
 * v1.2.2 边界修复：首页连接状态合并/降级的纯规则（无 Android 依赖，JVM 直测）。
 * 连接事实来自本机 API；特权读取失败只降级参数，绝不回写断开（UNKNOWN 不等于断开）。
 */
object HomeStateMerge {

    /** 本机已确认连接落入首页状态的合并结果。 */
    data class LocalConnectResult(
        val state: HomeUiState,
        /** 设备相对上一轮已变化（换机/同名不同 MAC/首次连接）；Activity 须同时清 manualSession。 */
        val deviceChanged: Boolean,
    )

    /**
     * 本机连接落首页状态：
     *  - 设备变化（换机、同名不同 MAC、首次连接）必须立即作废旧参数——codec/realtime/
     *    fullBars/targetStatus/manual* 全清、paramsAvailable=false：新设备读回完成前
     *    不得展示上一台的参数，也不得让 Apply/自定义写入对新设备可用；
     *  - 同一设备轮询刷新保持既有展示，避免 3s 轮询期间闪烁。
     */
    fun onLocalConnected(
        previous: HomeUiState,
        headset: LocalHeadset,
        connectedMac: String?,
    ): LocalConnectResult {
        val deviceChanged = connectedMac == null ||
            !connectedMac.equals(headset.mac, ignoreCase = true)
        if (!deviceChanged) {
            // 同一设备：仅同步展示名（重命名），参数面保持上一轮读回值
            return LocalConnectResult(previous.copy(headsetName = headset.name), deviceChanged = false)
        }
        return LocalConnectResult(
            previous.copy(
                headsetName = headset.name,
                codec = null, rate = null, bits = null, quality = null,
                realtimeKbps = null, realtimeQualityMode = null,
                realtimeDisplay = RealtimeDisplay.NO_DATA,
                fullBars = false, targetStatus = null,
                manualActive = false, manualEditor = null,
                paramsAvailable = false,
            ),
            deviceChanged = true,
        )
    }

    /** 特权参数读取/解析失败的统一降级：保留耳机名，参数全置不可用。 */
    fun paramsUnavailable(state: HomeUiState): HomeUiState = state.copy(
        codec = null, rate = null, bits = null, quality = null,
        realtimeKbps = null, realtimeQualityMode = null,
        realtimeDisplay = RealtimeDisplay.NO_DATA,
        fullBars = false, targetStatus = null,
        manualActive = false, manualEditor = null,
        paramsAvailable = false,
    )

    /**
     * 特权 dump 超时后的状态处置：超时不是「无设备」的正面证据。本机读不到
     * （Unknown/SecurityBlocked，UNKNOWN 不等于断开）时只降级参数、保留既有连接态；
     * 本机已裁决时特权超时不得动状态（路由保证不到达，防御未来改动覆盖本机裁决）。
     */
    fun providerTimeoutDegrade(local: LocalHeadsetResult, previous: HomeUiState): HomeUiState =
        when (local) {
            is LocalHeadsetResult.Unknown,
            is LocalHeadsetResult.SecurityBlocked -> paramsUnavailable(previous)
            else -> previous
        }
}
