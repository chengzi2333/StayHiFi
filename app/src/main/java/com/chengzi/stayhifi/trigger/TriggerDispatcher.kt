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
        val key = mac.trim().uppercase(Locale.ROOT)
        when (val acquisition = gate.acquire(key, force)) {
            is TriggerGate.Acquisition.Rejected -> {
                log(
                    "重复触发已合并: trigger_decision decision=REJECTED " +
                        "reason=${acquisition.reason} source=$source mac=$key force=$force " +
                        "elapsedMs=${acquisition.elapsedMillis ?: "—"} " +
                        "remainingMs=${acquisition.remainingMillis ?: "—"}"
                )
                return false
            }
            is TriggerGate.Acquisition.Granted -> {
                val permit = acquisition.permit
                return try {
                    log("触发闸放行: trigger_decision decision=GRANTED source=$source mac=$key force=$force")
                    runner.run(mac, name, force)
                    log("trigger_runner event=RETURNED source=$source mac=$key")
                    true
                } catch (t: Throwable) {
                    log(
                        "trigger_runner event=EXCEPTION source=$source mac=$key " +
                            "error=${t.javaClass.simpleName}"
                    )
                    throw t
                } finally {
                    gate.release(permit)
                    log("trigger_gate event=RELEASED source=$source mac=$key")
                }
            }
        }
    }
}
