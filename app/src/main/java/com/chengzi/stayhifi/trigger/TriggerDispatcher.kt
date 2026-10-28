package com.chengzi.stayhifi.trigger

import java.util.Locale

enum class TriggerSource {
    CONNECTION_RECEIVER,
    LISTENER_SERVICE,
    STARTUP,
    MANUAL,
    QS,
}

fun interface TriggerRunner {
    suspend fun run(mac: String, name: String?, force: Boolean)
}

/** 获取 Permit 后执行 runner；成功、异常与协程取消均在同一 finally 中释放。 */
class TriggerDispatcher(
    private val gate: TriggerGate,
    private val runner: TriggerRunner,
    private val log: (String) -> Unit = {},
) {
    suspend fun dispatch(
        mac: String,
        name: String?,
        force: Boolean,
        source: TriggerSource,
    ): Boolean {
        val permit = gate.tryAcquire(mac, force) ?: run {
            log("重复触发已合并: source=$source mac=${mac.trim().uppercase(Locale.ROOT)}")
            return false
        }
        return try {
            runner.run(mac, name, force)
            true
        } finally {
            gate.release(permit)
        }
    }
}
