package com.chengzi.stayhifi.device

import org.junit.Assert.assertEquals
import org.junit.Test

/** 本机读取诊断只在事实变化时落行，供灰度日志区分 Connected/Unknown/权限缺失。 */
class LocalHeadsetDiagnosticLoggerTest {

    @Test
    fun repeatedStateIsSuppressedAndStateChangeIsLogged() {
        val lines = mutableListOf<String>()
        val logger = LocalHeadsetDiagnosticLogger(lines::add)
        val connected = LocalHeadsetResult.Connected(LocalHeadset(" buds ", "AA:BB"))

        logger.log(connected)
        logger.log(connected)
        logger.log(LocalHeadsetResult.Unknown)
        logger.log(LocalHeadsetResult.Unknown)
        logger.log(LocalHeadsetResult.SecurityBlocked)
        logger.log(LocalHeadsetResult.Disconnected)

        assertEquals(
            listOf(
                "本机 A2DP 读取: CONNECTED",
                "本机 A2DP 读取: UNKNOWN",
                "本机 A2DP 读取: SECURITY_BLOCKED",
                "本机 A2DP 读取: DISCONNECTED",
            ),
            lines,
        )
    }
}
