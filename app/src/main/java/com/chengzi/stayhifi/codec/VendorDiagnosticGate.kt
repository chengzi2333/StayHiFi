package com.chengzi.stayhifi.codec

/** 进程内诊断证据去重：同一设备、配置与证据类别只记录一次。 */
class VendorDiagnosticGate {
    private val signatures = mutableSetOf<String>()

    @Synchronized
    fun shouldRecord(mac: String, current: CodecConfig, sampleKind: String): Boolean =
        signatures.add("$mac|${current.toJson()}|$sampleKind")

    @Synchronized
    fun reset(): Unit {
        signatures.clear()
    }
}
