package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.codec.CodecConfig

/**
 * Task 43：参数漂移提醒的数据底座——内存态 per-mac 记录「最近一次已验证生效的目标」
 * （拉满写入+读回验证通过 / 早退分支读回已是最高，两种都算已验证）。
 * 纯内存、进程重启即清零：不记得就当没拉满过，宁可漏报不可误报。
 */
object VerifiedTargetStore {

    private data class Entry(val target: CodecConfig, var notified: Boolean)

    private val map = HashMap<String, Entry>()

    /** 验证通过时记录新目标，并重置漂移通知标志（重新拉满 = 上一轮漂移期结束）。 */
    @Synchronized
    fun record(mac: String, target: CodecConfig) {
        map[mac] = Entry(target, notified = false)
    }

    /** 恢复默认参数成功后调用：旧的拉满目标作废，否则轮询会立刻把默认参数误报成漂移。 */
    @Synchronized
    fun clear(mac: String) {
        map.remove(mac)
    }

    @Synchronized
    fun targetFor(mac: String): CodecConfig? = map[mac]?.target

    /** 本轮漂移期是否已提醒过（每轮只弹一次）。 */
    @Synchronized
    fun isNotified(mac: String): Boolean = map[mac]?.notified == true

    @Synchronized
    fun markNotified(mac: String) {
        map[mac]?.notified = true
    }

    /** 读回与目标重新一致（系统协商回来/用户重新拉满）→ 重置标志，下次漂移可再提醒。 */
    @Synchronized
    fun resetNotified(mac: String) {
        map[mac]?.notified = false
    }
}
