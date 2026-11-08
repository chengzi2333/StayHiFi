package com.chengzi.stayhifi.trigger

/**
 * v1.2.4 问题#6 配套：LHDC 码率档探测的进程级「定档」记忆。
 *
 * 逐级探测（1000→64）完成后记录本机定档结果（成功的固定档，或固定档全被拒后的
 * 原 cs1 含自适应 0x8009），后续自动触发以此为探测上限，不再每次重连都从 1000
 * 全量重探（每次重探约十秒重协商抖动）。纯内存、进程重启清零（宁可重探一次，
 * 不持久化可能过期的能力结论）。手动「重新拉满」与「恢复默认参数」由调用方清除。
 */
object LhdcQualityCeilingStore {

    private data class Key(val mac: String, val codecType: Int)

    private val ceilings = HashMap<Key, Long>()

    @Synchronized
    fun record(mac: String, codecType: Int, cs1: Long) {
        ceilings[Key(mac, codecType)] = cs1
    }

    @Synchronized
    fun ceilingFor(mac: String, codecType: Int): Long? = ceilings[Key(mac, codecType)]

    @Synchronized
    fun clear(mac: String) {
        ceilings.keys.removeAll { it.mac == mac }
    }

    @Synchronized
    fun clearAll() {
        ceilings.clear()
    }
}
