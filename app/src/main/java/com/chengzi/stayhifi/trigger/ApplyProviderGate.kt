package com.chengzi.stayhifi.trigger

import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.ProviderLease
import com.chengzi.stayhifi.shell.ProviderOwner
import com.chengzi.stayhifi.shell.ProviderSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Task D7：Apply 路径的 provider 取用闸门（设计 §11.3）。
 *
 * 写入路径（自动拉满/恢复默认）必须在取得 [ApplyLock.mutex] 之后才解析当前 provider：
 * 排队期间选择可能已被切换事务替换，锁前取 lease 会让排队任务用旧通道写入。
 *
 * 顺序契约（测试断言）：lock → awaitSelection/acquire → block(IPC) → close → unlock。
 * lease 在 finally 关闭；SWITCHING 期间 acquire 拒绝（ProviderAccessReason.SWITCHING），
 * 异常向上传播、锁照常释放。与 B4 ApplyConnectionGuard 的锁内断连复查正交、可叠加。
 */
class ApplyProviderGate(private val lock: Mutex = ApplyLock.mutex) {

    suspend fun <T> withLease(
        coordinator: ProviderCoordinator,
        owner: ProviderOwner,
        source: ProviderSource,
        block: suspend (ProviderLease) -> T,
    ): T = lock.withLock {
        val lease = coordinator.acquire(owner, source)
        try {
            block(lease)
        } finally {
            lease.close()
        }
    }
}
