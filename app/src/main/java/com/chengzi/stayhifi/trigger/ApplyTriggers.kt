package com.chengzi.stayhifi.trigger

import android.content.Context
import com.chengzi.stayhifi.LogStore

fun interface ApplyTriggerRunner {
    suspend fun run(context: Context, mac: String, name: String?, force: Boolean)
}

/** 五个触发入口共享的唯一进程内 gate；各 receiver 不单独持有窗口状态。 */
object ApplyTriggers {
    private val gate = TriggerGate()

    @Volatile
    internal var runner: ApplyTriggerRunner = ApplyTriggerRunner { context, mac, name, force ->
        ApplyWorker(context).run(mac, name, force)
    }

    suspend fun run(
        context: Context,
        mac: String,
        name: String?,
        force: Boolean,
        source: TriggerSource,
    ): Boolean {
        val currentRunner = runner
        return TriggerDispatcher(
            gate = gate,
            runner = TriggerRunner { dispatchMac, dispatchName, dispatchForce ->
                currentRunner.run(context, dispatchMac, dispatchName, dispatchForce)
            },
            log = { LogStore.log(it) },
        ).dispatch(mac, name, force, source)
    }

    fun onDisconnected(mac: String) = gate.onDisconnected(mac)
}
