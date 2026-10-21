package com.chengzi.stayhifi.trigger

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 进程内耳机事件总线：ListenerService 收到 ACL_CONNECTED / ACL_DISCONNECTED、
 * 以及 ApplyWorker 拉满跑完时 bump 版本号；MainActivity 首页 collect 版本变化
 * 触发 refreshHome()。修复「首页停留在前台时连接/断开/拉满完成 UI 不刷新」
 * （refreshHome 此前只在 onResume 调用）。version=0 为初始值，订阅方必须跳过，
 * 避免首帧重复刷。
 */
object HeadsetEvents {
    val version = MutableStateFlow(0L)
    fun bump() {
        version.value++
    }
}
