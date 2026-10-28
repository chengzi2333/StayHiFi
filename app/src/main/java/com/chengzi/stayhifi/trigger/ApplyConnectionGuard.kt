package com.chengzi.stayhifi.trigger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Task B4：排队任务真正拿到 [ApplyLock.mutex] 后，先用本机 A2DP 状态按 MAC 复查
 * 设备是否仍连接——从 ACL_CONNECTED 排队到实际执行之间，设备可能已断开或换连；
 * 此时一切载体 IO 都是对一台不在场设备的误写/误报（旧版本误报「拉满失败」的来源）。
 *
 * force（手动入口）只影响 autoApply 判断，不能绕过本复查。
 */
class ApplyConnectionGuard(
    private val lock: Mutex,
    private val probe: LocalA2dpProbe,
    private val log: (String) -> Unit,
) {
    /**
     * 持锁后复查并执行 [carrierBlock]：
     * DISCONNECTED → 零载体调用，只记一条结束日志，返回 false；
     * CONNECTED → 执行 [carrierBlock]，返回 true；
     * UNKNOWN（含 probe 读取抛异常）→ 记一条「状态不可用」后按旧路径执行，返回 true——
     * UNKNOWN 是兼容回退，绝不伪装成「已确认连接」。
     *
     * [carrierBlock] 抛异常时锁照常释放（withLock 语义），异常向上传播。
     */
    suspend fun run(mac: String, carrierBlock: suspend () -> Unit): Boolean =
        lock.withLock {
            when (probeState(mac)) {
                LocalA2dpState.CONNECTED -> {
                    carrierBlock()
                    true
                }
                LocalA2dpState.UNKNOWN -> {
                    log("本机 A2DP 状态不可用，按旧路径执行: mac=$mac")
                    carrierBlock()
                    true
                }
                LocalA2dpState.DISCONNECTED -> {
                    log("设备已断开，跳过排队任务: mac=$mac")
                    false
                }
            }
        }

    /** probe 自身抛异常（如 SecurityException 未被实现侧吞掉）同样按 UNKNOWN 兼容回退。 */
    private fun probeState(mac: String): LocalA2dpState = try {
        probe.state(mac)
    } catch (t: Throwable) {
        LocalA2dpState.UNKNOWN
    }
}
