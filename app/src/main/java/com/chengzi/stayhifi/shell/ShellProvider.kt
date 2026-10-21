package com.chengzi.stayhifi.shell

import android.app.Activity
import com.chengzi.stayhifi.ICodecService

/**
 * 特权载体抽象（设计 §4.4）：统一 Shizuku / Stellar 两种载体。
 * App 进程通过它绑定 shell 进程中的 [CodecService]。
 */
interface ShellProvider {
    /** 载体服务是否在运行（Shizuku: pingBinder；Stellar 见 FIXME(stellar)） */
    val isRunning: Boolean

    /** 是否已持有特权授权 */
    val hasPermission: Boolean

    /** 请求授权（流程同 PoC：Shizuku.requestPermission + 结果回调） */
    suspend fun requestPermission(activity: Activity): Boolean

    /** 绑定 shell 进程的 UserService，返回 IPC 接口（未运行/未授权时抛异常） */
    fun bind(): ICodecService

    /** 解绑并销毁 UserService */
    fun unbind()
}
