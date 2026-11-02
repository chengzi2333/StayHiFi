package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.device.LocalHeadset

/** 本机与特权回退共用的连接迁移裁决：只有真实 MAC 变化才开启新会话。 */
internal object HomeConnectionMigration {

    fun migrate(
        state: HomeUiState,
        headset: LocalHeadset,
        connectedMac: String?,
        resetSession: () -> Unit,
    ): HomeStateMerge.LocalConnectResult {
        val result = HomeStateMerge.onLocalConnected(state, headset, connectedMac)
        if (result.deviceChanged) resetSession()
        return result
    }
}
