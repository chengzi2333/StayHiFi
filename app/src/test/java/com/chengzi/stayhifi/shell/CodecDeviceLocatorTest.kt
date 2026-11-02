package com.chengzi.stayhifi.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Root 空 connectedDevices 时，已知名 MAC 仍须能直接构造目标设备。 */
class CodecDeviceLocatorTest {

    private data class FakeDevice(val address: String)

    @Test
    fun targetMacMissingFromConnectedListFallsBackToRemoteDevice() {
        val remote = FakeDevice("AA:BB:CC:DD:EE:FF")
        var requestedMac: String? = null

        val selected = CodecDeviceLocator.select(
            mac = "  aa:bb:cc:dd:ee:ff  ",
            connectedDevices = emptyList(),
            addressOf = { it.address },
            remoteDevice = {
                requestedMac = it
                remote
            },
        )

        assertEquals("aa:bb:cc:dd:ee:ff", requestedMac)
        assertEquals(remote, selected)
    }

    @Test
    fun connectedMatchWinsWithoutRemoteLookup() {
        val connected = FakeDevice("AA:BB:CC:DD:EE:FF")

        val selected = CodecDeviceLocator.select(
            mac = "aa:bb:cc:dd:ee:ff",
            connectedDevices = listOf(connected),
            addressOf = { it.address },
            remoteDevice = {
                fail("列表已有目标时不应再创建远程设备")
                null
            },
        )

        assertEquals(connected, selected)
    }

    @Test
    fun blankMacUsesFirstConnectedDeviceAndNeverRemoteLookup() {
        val first = FakeDevice("AA:BB")
        val remoteDevice: (String) -> FakeDevice? = {
            fail("无 MAC 时不能凭空选择远程设备")
            null
        }

        assertEquals(
            first,
            CodecDeviceLocator.select(
                mac = null,
                connectedDevices = listOf(first),
                addressOf = { it.address },
                remoteDevice = remoteDevice,
            ),
        )
        assertNull(
            CodecDeviceLocator.select(
                mac = "  ",
                connectedDevices = emptyList(),
                addressOf = { it.address },
                remoteDevice = remoteDevice,
            ),
        )
    }
}
