package com.chengzi.stayhifi.trigger

import java.util.Locale

/** 进程内 per-MAC 触发闸：活动期始终合并，完成后仅在 [start, end) 窗口内合并。 */
class TriggerGate(
    private val windowMillis: Long = 10_000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(windowMillis >= 0) { "windowMillis must be non-negative" }
    }

    class Permit internal constructor(
        internal val mac: String,
        internal val token: Long,
    )

    private data class State(
        var activeToken: Long? = null,
        var completedAt: Long? = null,
        var disconnected: Boolean = false,
    )

    private val states = mutableMapOf<String, State>()
    private var nextToken = 0L

    @Synchronized
    fun tryAcquire(mac: String, force: Boolean = false): Permit? {
        val key = normalize(mac)
        val existing = states[key]
        if (existing?.activeToken != null) return null
        val now = nowMillis()
        val completedAt = existing?.completedAt
        if (!force && existing?.disconnected != true && completedAt != null &&
            now >= completedAt && now - completedAt < windowMillis
        ) {
            return null
        }
        val state = existing ?: State().also { states[key] = it }
        state.disconnected = false
        state.completedAt = null
        val token = ++nextToken
        state.activeToken = token
        return Permit(key, token)
    }

    @Synchronized
    fun onDisconnected(mac: String) {
        val key = normalize(mac)
        val state = states[key] ?: State().also { states[key] = it }
        state.completedAt = null
        state.disconnected = true
    }

    @Synchronized
    fun release(permit: Permit) {
        val state = states[permit.mac] ?: return
        if (state.activeToken != permit.token) return
        state.activeToken = null
        if (state.disconnected) {
            states.remove(permit.mac)
        } else {
            state.completedAt = nowMillis()
        }
    }

    private companion object {
        fun normalize(mac: String): String = mac.trim().uppercase(Locale.ROOT)
    }
}
