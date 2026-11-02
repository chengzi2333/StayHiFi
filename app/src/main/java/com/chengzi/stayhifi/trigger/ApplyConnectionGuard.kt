package com.chengzi.stayhifi.trigger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /** 生产固定 IO；测试可注入同一 TestScheduler，保留确定性调用顺序断言。 */
    internal var probeDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * 持锁后复查并执行 [carrierBlock]：
     * DISCONNECTED → 默认零载体调用并结束；[awaitA2dp]=true（ACL 连接触发）时，
     * 先覆盖 ACL→A2DP attach 的已知时序，每 500ms 复查；真实等待预算 5s
     * （含 probe 耗时，单个已在执行的 proxy 查询仍受其自身 2s 上界约束）。
     * 预算耗尽后交回原特权 readConfig 设备等待链，不让本机空列表永久阻断 Root；
     * 特权链仍须读到有效 current 才写入，真实断连不会被当成已连接。
     * CONNECTED → 执行 [carrierBlock]，返回 true；
     * UNKNOWN（含 probe 读取抛异常）→ 记一条「状态不可用」后按旧路径执行，返回 true——
     * UNKNOWN 是兼容回退，绝不伪装成「已确认连接」。
     *
     * [carrierBlock] 抛异常时锁照常释放（withLock 语义），异常向上传播。
     */
    suspend fun run(
        mac: String,
        awaitA2dp: Boolean = false,
        carrierBlock: suspend () -> Unit,
    ): Boolean = lock.withLock {
        val startedAt = nowMillis()
        val first = probeState(mac)
        val state = if (awaitA2dp && first == LocalA2dpState.DISCONNECTED) {
            // ACL_CONNECTED 只证明物理链路建立；A2DP attach 实测可晚约 2s。
            // 不得把这段正常空窗判成“设备已断开”并堵死后面的既有设备等待链。
            log("等待本机 A2DP 就绪: mac=$mac")
            probeUntilA2dp(mac, startedAt)
        } else {
            first
        }
        when (state) {
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
                if (awaitA2dp) {
                    // 新日志已证明存在「系统 A2DP 已连接、载体/本机列表为空」的 ROM 路径。
                    // 此处只放行原有 readConfig 确认链；没有有效 current 仍不会写入。
                    log("本机 A2DP 5s 内未确认，按原设备等待链处理: mac=$mac")
                    carrierBlock()
                    true
                } else {
                    log("设备已断开，跳过排队任务: mac=$mac")
                    false
                }
            }
        }
    }

    /** 仅连接触发使用：deadline 包含慢 probe；UNKNOWN 立即交回原回退。 */
    private suspend fun probeUntilA2dp(mac: String, startedAt: Long): LocalA2dpState {
        while (nowMillis() - startedAt < A2DP_ATTACH_TIMEOUT_MS) {
            delay(A2DP_ATTACH_POLL_MS)
            val state = probeState(mac)
            if (state != LocalA2dpState.DISCONNECTED) return state
        }
        return LocalA2dpState.DISCONNECTED
    }

    /** proxy 查询可能等待系统回调，必须离开 UI/Listener 调用线程；异常仍按 UNKNOWN 回退。 */
    private suspend fun probeState(mac: String): LocalA2dpState = withContext(probeDispatcher) {
        try {
            probe.state(mac)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            LocalA2dpState.UNKNOWN
        }
    }

    private companion object {
        const val A2DP_ATTACH_TIMEOUT_MS = 5_000L
        const val A2DP_ATTACH_POLL_MS = 500L
    }
}
