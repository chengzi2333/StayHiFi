package com.chengzi.stayhifi.trigger

import kotlinx.coroutines.sync.Mutex

/**
 * 常驻通知刷新单飞闸：已有刷新仍在执行时直接合并，不排队。
 *
 * 协程超时不能中断已经阻塞的 Binder transact；本闸只保证旧调用未物理返回前，
 * 不再叠加新的读取/通知任务，不宣称终止或修复底层阻塞。
 */
class NotificationRefreshGate {
    private val mutex = Mutex()

    suspend fun run(block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            block()
            true
        } finally {
            mutex.unlock()
        }
    }
}
