package com.chengzi.stayhifi.codec

/** 进程级系统/legacy 冲突去重：同一设备、当前配置与系统身份只放行一次。 */
object CodecIdentityDiagnosticGate {
    private data class Signature(
        val mac: String,
        val current: CodecConfig,
        val systemIdentity: CodecIdentity,
    )

    private val signatures = mutableSetOf<Signature>()

    @Synchronized
    fun shouldRecord(mac: String, current: CodecConfig, systemIdentity: CodecIdentity): Boolean =
        signatures.add(Signature(mac, current, systemIdentity))

    @Synchronized
    fun reset() {
        signatures.clear()
    }
}
