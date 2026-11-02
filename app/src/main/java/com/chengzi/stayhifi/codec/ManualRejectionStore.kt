package com.chengzi.stayhifi.codec

/**
 * 当前进程内的手动组合拒绝证据。只记录 readConfig/writeConfig 已明确读回不匹配
 * 的目标；不得写入 Root/权限/超时等普通失败，也不得持久化或跨重连重放。
 */
internal class ManualRejectionStore {

    private val rejected = mutableSetOf<Pair<String, CodecConfig>>()

    /** 连接会话代次：断连/换机递增，旧异步手动结果不得写入新会话。 */
    var generation: Long = 0L
        private set

    fun isCurrent(candidate: Long): Boolean = candidate == generation

    @Synchronized
    fun record(mac: String, target: CodecConfig) {
        rejected += normalize(mac) to target
    }

    @Synchronized
    fun remove(mac: String, target: CodecConfig) {
        rejected -= normalize(mac) to target
    }

    /** 当前 MAC 的只读快照；完整 CodecConfig 不同即属于另一组合。 */
    @Synchronized
    fun forMac(mac: String): Set<CodecConfig> {
        val normalized = normalize(mac)
        return rejected.filter { it.first == normalized }.map { it.second }.toSet()
    }

    @Synchronized
    fun clear(mac: String) {
        val normalized = normalize(mac)
        rejected.removeAll { it.first == normalized }
    }

    @Synchronized
    fun clearAll() {
        generation++
        rejected.clear()
    }

    private fun normalize(mac: String): String = mac.trim().uppercase()
}
