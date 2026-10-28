package com.chengzi.stayhifi

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 线程安全的进程内日志环形缓冲。时间读取、格式化与写入必须在同一把锁内完成：
 * SimpleDateFormat 非线程安全，且时间戳必须反映进入临界区的时刻，避免跨线程乱序。
 * logcat echo 由 LogStore 负责，这里保持纯 JVM 以便单测。
 */
class LogBuffer(
    private val maxLines: Int = 200,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /** 追加一行，返回带时间戳的完整行。 */
    fun append(msg: String): String {
        val line: String
        synchronized(lock) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            line = "[${fmt.format(Date(nowMillis()))}] $msg"
            lines.addLast(line)
            while (lines.size > maxLines) lines.removeFirst()
        }
        return line
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
