package com.chengzi.stayhifi.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LocalHeadsetSource.classify 的 fake 测试：以 lambda 伪造本机蓝牙查询的
 * 全部返回面（空列表/有设备/null/SecurityException/其他异常），
 * 锁定「空列表、SecurityException、其他异常分开，UNKNOWN 不等于断开」的裁决。
 */
class LocalHeadsetSourceTest {

    @Test
    fun emptyListMeansConfirmedDisconnected() {
        assertEquals(
            LocalHeadsetResult.Disconnected,
            LocalHeadsetSource.classify { emptyList() },
        )
    }

    @Test
    fun connectedDeviceExposesNameAndAddress() {
        assertEquals(
            LocalHeadsetResult.Connected(LocalHeadset("OPPO Enco X3", "38:F8:89:01:23:45")),
            LocalHeadsetSource.classify {
                listOf(RawBtDevice(name = "OPPO Enco X3", address = "38:F8:89:01:23:45"))
            },
        )
    }

    @Test
    fun firstConnectedDeviceWins() {
        val result = LocalHeadsetSource.classify {
            listOf(
                RawBtDevice(name = "First", address = "AA:BB:CC:DD:EE:01"),
                RawBtDevice(name = "Second", address = "AA:BB:CC:DD:EE:02"),
            )
        }
        assertEquals(
            LocalHeadsetResult.Connected(LocalHeadset("First", "AA:BB:CC:DD:EE:01")),
            result,
        )
    }

    @Test
    fun nullNameFallsBackToAddressSoDisplayNeverBlank() {
        assertEquals(
            LocalHeadsetResult.Connected(LocalHeadset("38:F8:89:01:23:45", "38:F8:89:01:23:45")),
            LocalHeadsetSource.classify { listOf(RawBtDevice(name = null, address = "38:F8:89:01:23:45")) },
        )
    }

    @Test
    fun nullQueryResultMeansUnknownNotDisconnected() {
        // 读不到 ≠ 断开：null（蓝牙关闭/服务不可得）不得改判为未连接
        assertEquals(LocalHeadsetResult.Unknown, LocalHeadsetSource.classify { null })
    }

    @Test
    fun securityExceptionMeansSecurityBlockedNotUnknown() {
        assertEquals(
            LocalHeadsetResult.SecurityBlocked,
            LocalHeadsetSource.classify { throw SecurityException("need BLUETOOTH_CONNECT") },
        )
    }

    @Test
    fun otherExceptionMeansUnknownNotDisconnected() {
        // 任意读取异常都按 UNKNOWN 处理，绝不把连接改判为断开，也不伪造连接
        assertEquals(
            LocalHeadsetResult.Unknown,
            LocalHeadsetSource.classify { throw IllegalStateException("binder died") },
        )
    }
}
