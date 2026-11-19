package com.chengzi.stayhifi

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 线程安全的进程内日志环形缓冲。时间读取、格式化与写入必须在同一把锁内完成：
 * SimpleDateFormat 非线程安全，且时间戳必须反映进入临界区的时刻，避免跨线程乱序。
 * logcat echo 由 LogStore 负责，这里保持纯 JVM 以便单测。
 *
 * v1.2.5 体积治理（用户反馈「日志几十 K，翻不动」）：
 *  - 单行正文超过 [maxMessageChars] 个字符就截断，并在行尾标注被截掉的字数——
 *    dump/JSON 之类的超长负载不会再一行占掉半屏，但「被截断」这件事本身可见；
 *  - 同一条消息在 [collapseWindowMs] 内连续重复，合并成一行并标注「重复 N 次」，
 *    轮询/重连风暴不会把 200 行窗口挤满；
 *  - 仍保留 [maxLines] 行，信息密度不变的情况下整份日志体积明显下降。
 */
class LogBuffer(
    private val maxLines: Int = 200,
    private val maxMessageChars: Int = 220,
    private val collapseWindowMs: Long = 10_000L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>()

    /** 上一条消息（已截断的正文）与它的时间，用于重复折叠；仅在同一把锁内读写。 */
    private var lastMessage: String? = null
    private var lastAtMs = 0L
    private var repeatCount = 1

    /** 追加一行，返回该消息在缓冲区里的最终行（重复折叠时返回合并后的行）。 */
    fun append(msg: String): String {
        val text = clamp(msg)
        synchronized(lock) {
            val now = nowMillis()
            val stamp = FORMAT.get().format(Date(now))
            val previous = lastMessage
            if (previous != null && previous == text && now - lastAtMs <= collapseWindowMs &&
                lines.isNotEmpty()
            ) {
                repeatCount += 1
                val merged = "[$stamp] $text （重复 ${repeatCount} 次）"
                lines.removeLast()
                lines.addLast(merged)
                lastAtMs = now
                return merged
            }
            val line = "[$stamp] $text"
            lines.addLast(line)
            while (lines.size > maxLines) lines.removeFirst()
            lastMessage = text
            lastAtMs = now
            repeatCount = 1
            return line
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) {
        lines.clear()
        lastMessage = null
        lastAtMs = 0L
        repeatCount = 1
    }

    /**
     * 超长正文按字符截断，并显式标出截掉多少字，避免误读成「原文就这么短」。
     * 厂商诊断行（VendorStackDiag / VendorStackDiagFailure）自带约 1500 字符预算且不可拆分，
     * 单独放宽到同一上限，否则关键证据会被截成看不出结论的半句。
     */
    private fun clamp(msg: String): String {
        val limit = if (msg.startsWith(DIAG_LINE_PREFIX)) maxOf(maxMessageChars, DIAG_LINE_CHARS) else maxMessageChars
        if (msg.length <= limit) return msg
        val cut = msg.length - limit
        return msg.take(limit) + " …(截断 $cut 字)"
    }

    private fun String.startsWith(prefixPair: Pair<String, String>): Boolean =
        startsWith(prefixPair.first) || startsWith(prefixPair.second)

    private companion object {
        /** 厂商诊断行的两个前缀（正常快照 / 读取失败）。 */
        val DIAG_LINE_PREFIX = "VendorStackDiag" to "VendorStackDiagFailure"
        const val DIAG_LINE_CHARS = 1_600

        /** SimpleDateFormat 构造有成本，按线程复用；时间格式化仍在锁内完成。 */
        val FORMAT: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.US)
        }
    }
}
