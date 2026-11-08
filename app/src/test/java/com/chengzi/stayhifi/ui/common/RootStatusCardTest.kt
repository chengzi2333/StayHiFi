package com.chengzi.stayhifi.ui.common

import com.chengzi.stayhifi.shell.RootShellStatus
import com.chengzi.stayhifi.shell.RootSnapshot
import com.chengzi.stayhifi.shell.RootStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RootStatusCardTest {

    @Test
    fun grantedNotReadyReportsServiceStateInsteadOfRequestingAuthorization() {
        val snapshot = RootSnapshot(
            status = RootStatus.GRANTED_NOT_READY,
            granted = true,
            shellStatus = RootShellStatus.ROOT_SHELL,
            observedReason = "health ERROR_REPORTED: A2DP 代理不可用 enabled=false",
        )

        assertEquals("Root 已授权，服务未就绪", rootStatusLine(snapshot))
    }

    @Test
    fun grantedNotReadyShowsObservedHealthFailureAsLastError() {
        val reason = "health ERROR_REPORTED: A2DP 代理不可用 enabled=false"
        val snapshot = RootSnapshot(
            status = RootStatus.GRANTED_NOT_READY,
            granted = true,
            shellStatus = RootShellStatus.ROOT_SHELL,
            observedReason = reason,
        )

        assertEquals(reason, rootLastErrorText(snapshot))
    }
}
