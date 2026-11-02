package com.chengzi.stayhifi.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.2.2.1：本机 A2DP proxy 查询契约。
 *
 * 旧实现把 BluetoothManager.getConnectedDevices(A2DP) 的 IllegalArgumentException
 * 吞成 Unknown，导致本机解耦永远落空。这里用真实查询协调器 + fake 平台回调，
 * 锁定设备返回、权限异常、代理断开、超时与迟到 proxy 的资源释放。
 */
class LocalA2dpDeviceQueryTest {

    private val device = RawBtDevice(name = "Enco X3", address = "AA:BB:CC:DD:EE:01")

    @Test
    fun connectedProxyReturnsDevicesAndClosesProxy() {
        var closeCalls = 0
        val query = A2dpProxyDeviceQuery(
            request = { callback ->
                callback.onConnected(
                    A2dpProxyHandle(
                        readDevices = { listOf(device) },
                        close = { closeCalls++ },
                    ),
                )
                true
            },
            await = { latch -> latch.count == 0L },
        )

        assertEquals(listOf(device), query.connectedDevices())
        assertEquals(1, closeCalls)
    }

    @Test
    fun proxyPermissionFailureKeepsSecurityBlockedAndClosesProxy() {
        var closeCalls = 0
        val query = A2dpProxyDeviceQuery(
            request = { callback ->
                callback.onConnected(
                    A2dpProxyHandle(
                        readDevices = { throw SecurityException("need BLUETOOTH_CONNECT") },
                        close = { closeCalls++ },
                    ),
                )
                true
            },
            await = { true },
        )

        assertEquals(LocalHeadsetResult.SecurityBlocked, LocalHeadsetSource(query).current())
        assertEquals(1, closeCalls)
    }

    @Test
    fun rejectedProxyRequestMeansUnknownWithoutWaiting() {
        val query = A2dpProxyDeviceQuery(
            request = { false },
            await = {
                fail("request=false 时不应等待 proxy 回调")
                true
            },
        )

        assertNull(query.connectedDevices())
        assertEquals(LocalHeadsetResult.Unknown, LocalHeadsetSource(query).current())
    }

    @Test
    fun disconnectedProxyMeansUnknown() {
        val query = A2dpProxyDeviceQuery(
            request = { callback ->
                callback.onDisconnected()
                true
            },
            await = { true },
        )

        assertEquals(LocalHeadsetResult.Unknown, LocalHeadsetSource(query).current())
    }

    @Test
    fun timeoutMeansUnknownAndLateProxyIsStillClosed() {
        lateinit var pending: A2dpProxyCallback
        var closeCalls = 0
        val query = A2dpProxyDeviceQuery(
            request = { callback ->
                pending = callback
                true
            },
            await = { false },
        )

        assertEquals(LocalHeadsetResult.Unknown, LocalHeadsetSource(query).current())
        assertEquals(0, closeCalls)

        pending.onConnected(
            A2dpProxyHandle(readDevices = { listOf(device) }, close = { closeCalls++ }),
        )
        assertEquals(1, closeCalls)
    }
}
