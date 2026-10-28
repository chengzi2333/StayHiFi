package com.chengzi.stayhifi.codec

/**
 * Task B5：通用厂商栈诊断的有界去重闸。
 *
 * 64 项 LinkedHashMap LRU 只限制内存占用，不宣称持久去重；同一 signature 重复出现
 * 增加 pending suppressedCount，下一接受事件或导出 drain 时附总数。manual force
 * 每次记录，但不增加、也不吞掉重复计数。清空日志（reset）后同签名可再记且计数清零。
 */
class VendorStackDiagnosticGate(private val maxSignatures: Int = 64) {

    data class Decision(val shouldRecord: Boolean, val suppressedCount: Int)

    /**
     * 插入序 LinkedHashMap：命中即移到热端（LRU touch）；插入后超容量手动淘汰
     * 最久未访问项。不依赖 removeEldestEntry 受 Kotlin LinkedHashMap 包装影响的行为。
     */
    private val signatures = LinkedHashMap<String, Unit>(16, 0.75f)

    private var pendingSuppressed = 0

    @Synchronized
    fun record(signature: String, force: Boolean = false): Decision {
        if (signatures.remove(signature) != null) {
            // 命中：移到 LRU 热端。
            signatures[signature] = Unit
            if (force) return Decision(shouldRecord = true, suppressedCount = 0)
            pendingSuppressed++
            return Decision(shouldRecord = false, suppressedCount = pendingSuppressed)
        }
        signatures[signature] = Unit
        while (signatures.size > maxSignatures) {
            val eldest = signatures.keys.first()
            signatures.remove(eldest)
        }
        val suppressed = pendingSuppressed
        pendingSuppressed = 0
        return Decision(shouldRecord = true, suppressedCount = suppressed)
    }

    /** 取出并清零待汇报的重复计数（复制日志前的 drain 一行汇总）。 */
    @Synchronized
    fun drainSuppressedCount(): Int {
        val drained = pendingSuppressed
        pendingSuppressed = 0
        return drained
    }

    @Synchronized
    fun reset() {
        signatures.clear()
        pendingSuppressed = 0
    }
}
