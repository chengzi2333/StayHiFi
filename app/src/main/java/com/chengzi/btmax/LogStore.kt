package com.chengzi.btmax

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 进程内运行日志环形缓冲（设计 §4.5 设置页"运行日志查看"的数据源）。
 * 同时 echo 到 logcat(tag=FullBars)。广播拉起的独立进程也能用它记录完整过程。
 */
object LogStore {
    private const val TAG = "FullBars"
    private const val MAX_LINES = 200

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** 追加一行，返回带时间戳的完整行。 */
    fun log(msg: String): String {
        val line = "[${fmt.format(Date())}] $msg"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.i(TAG, msg)
        return line
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
