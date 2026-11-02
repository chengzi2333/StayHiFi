package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.device.LocalHeadset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeConnectionMigrationTest {

    @Test
    fun deviceChangeStartsNewSessionOnlyOncePerActualMigration() {
        var resets = 0
        val first = LocalHeadset("X3", "AA:BB:CC:DD:EE:01")
        val second = LocalHeadset("Other", "AA:BB:CC:DD:EE:02")

        val migrated = HomeConnectionMigration.migrate(
            state = HomeUiState(),
            headset = first,
            connectedMac = second.mac,
            resetSession = { resets++ },
        )
        val repeated = HomeConnectionMigration.migrate(
            state = migrated.state,
            headset = first.copy(name = "X3 renamed"),
            connectedMac = first.mac,
            resetSession = { resets++ },
        )

        assertEquals("换机必须开启新会话", 1, resets)
        assertTrue(migrated.deviceChanged)
        assertEquals("同 MAC 重命名/轮询不得重复清会话", 1, resets)
        assertEquals("X3 renamed", repeated.state.headsetName)
    }
}
