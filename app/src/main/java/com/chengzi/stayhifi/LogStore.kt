package com.chengzi.stayhifi

import android.util.Log

/**
 * 进程内运行日志环形缓冲（设计 §4.5 设置页"运行日志查看"的数据源）。
 * 同时 echo 到 logcat(tag=StayHiFi)。广播拉起的独立进程也能用它记录完整过程。
 * 缓冲本体在 [LogBuffer]：时间格式化与 append 在同一把锁内完成，避免并发乱序。
 */
object LogStore {
    private const val TAG = "StayHiFi"

    private val buffer = LogBuffer()

    /** 追加一行，返回带时间戳的完整行。 */
    fun log(msg: String): String {
        val line = buffer.append(msg)
        Log.i(TAG, msg)
        return line
    }

    fun snapshot(): List<String> = buffer.snapshot()

    fun clear() = buffer.clear()
}
