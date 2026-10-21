package com.chengzi.stayhifi.trigger

import kotlinx.coroutines.sync.Mutex

/**
 * 对抗审查 I3：FGS 自动触发 / 首页「立即满血」/ QS 磁贴三路各自 new 一个 ApplyWorker，
 * 全程无互斥——并发写入互相把对方读回判成失败（多余重协商 + 误报），
 * ShizukuShellProvider.bind 的 check-then-act 还会泄漏 ServiceConnection。
 * 全局单锁串行化 [ApplyWorker.run]，持锁期间他路在 withLock 处排队。
 */
object ApplyLock {
    val mutex = Mutex()
}
