package com.chengzi.stayhifi.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本机连接事实与特权读取的合并规则：特权任何失败都不得覆盖本机已连接。 */
class ConnectionPresentationTest {

    private val connected = LocalHeadsetResult.Connected(LocalHeadset("Enco X3", "38:F8:89:01:23:45"))

    @Test
    fun localConnectedIsConfirmedConnection() {
        assertTrue(ConnectionPresentation.isLocallyConnected(connected))
    }

    @Test
    fun localDisconnectedIsNotConfirmedConnection() {
        assertFalse(ConnectionPresentation.isLocallyConnected(LocalHeadsetResult.Disconnected))
    }

    @Test
    fun localUnreadableIsNotConfirmedConnection() {
        // 读不到 ≠ 断开：UNKNOWN/SecurityBlocked 都不是「已连接」的正面证据
        assertFalse(ConnectionPresentation.isLocallyConnected(LocalHeadsetResult.Unknown))
        assertFalse(ConnectionPresentation.isLocallyConnected(LocalHeadsetResult.SecurityBlocked))
    }

    @Test
    fun providerEmptyListCannotOverrideLocalConnected() {
        // 核心规则：本机已确认连接时，特权空列表无权改判为未连接
        assertFalse(ConnectionPresentation.providerEmptyListAuthoritative(connected))
    }

    @Test
    fun providerEmptyListCannotOverrideLocalDisconnected() {
        assertFalse(ConnectionPresentation.providerEmptyListAuthoritative(LocalHeadsetResult.Disconnected))
    }

    @Test
    fun providerEmptyListOnlyAuthoritativeWhenLocalUnreadable() {
        // 只有本机读不到时，特权空列表才可作为未连接的正面证据
        assertTrue(ConnectionPresentation.providerEmptyListAuthoritative(LocalHeadsetResult.Unknown))
        assertTrue(ConnectionPresentation.providerEmptyListAuthoritative(LocalHeadsetResult.SecurityBlocked))
    }
}
