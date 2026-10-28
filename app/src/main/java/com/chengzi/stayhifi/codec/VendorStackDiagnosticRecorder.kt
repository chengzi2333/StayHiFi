package com.chengzi.stayhifi.codec

import com.chengzi.stayhifi.BuildConfig
import com.chengzi.stayhifi.LogStore

/**
 * Task B5：通用厂商栈诊断 recorder。把 plan A 快照与调用方上下文留证进现有 LogStore
 * （每进程 200 行环形缓冲），不新增持久化、上传或任何载体 IO。
 *
 * 记录规则：
 * - 有 reason 的快照进入通用评估；正常（无 reason）快照不记录，标准 SBC/AAC 不被排除。
 * - 同一 signature 的轮询重复只首条留证，重复次数累计为 suppressedCount，
 *   附在下一接受事件或复制前 drain 的一行汇总上。
 * - MANUAL（手动复制）即使无异常也每次留快照（gate force），但不增加重复计数。
 * - 解析/读取 Throwable 走 [recordFailure]，分阶段 root cause 解包最多 3 层、每层 ≤240 字符。
 *
 * 不再用旧 VendorDiagnostics.shouldLog 作为已知 codec 的排除条件；既有 dumpsys 证据、
 * 24 行线索与 ApplyWorker 专用 gate 继续负责原实时诊断。
 */
class VendorStackDiagnosticRecorder(
    private val log: (String) -> Unit = { LogStore.log(it) },
) {

    private val gate = VendorStackDiagnosticGate()
    private var snapshotSeq = 0L

    /** 返回本次写入 LogStore 的行数（0 = 被去重或无 reason 跳过）。 */
    fun record(snapshot: CodecStateSnapshot, context: VendorDiagnosticContext): Int {
        val reasons = VendorStackDiagnostics.reasons(snapshot)
        val manual = context.trigger == DiagnosticTrigger.MANUAL
        if (reasons.isEmpty() && !manual) return 0
        val signature = VendorStackDiagnostics.signature(snapshot, context.mac, reasons)
        val decision = gate.record(signature, force = manual)
        if (!decision.shouldRecord) return 0
        val event = VendorStackDiagnostics.format(
            snapshot, context, ++snapshotSeq, decision.suppressedCount,
        )
        log(event.text)
        return 1
    }

    /** 读取/解析失败留证：stage + 分阶段 root cause；同一签名重复失败被抑制。 */
    fun recordFailure(context: VendorDiagnosticContext, stage: String, error: Throwable): Int {
        val chain = VendorStackDiagnostics.rootCauseChain(error)
        val signature = VendorStackDiagnostics.failureSignature(context.mac, stage, chain)
        val decision = gate.record(signature)
        if (!decision.shouldRecord) return 0
        val text = buildString {
            append("VendorStackDiagFailure v=").append(VendorStackDiagnostics.SCHEMA_VERSION)
            append(" app=").append(BuildConfig.VERSION_NAME)
            append(" seq=").append(++snapshotSeq)
            append(" trigger=").append(VendorStackDiagnostics.triggerToken(context.trigger))
            append(" source=").append(context.source.name.lowercase())
            append(" ts=").append(context.timestampMillis)
            append(" mac=").append(context.mac)
            append(" provider=").append(context.providerKind)
            append(" stage=").append(stage)
            append(" suppressedCount=").append(decision.suppressedCount)
            append(" device=").append(VendorStackDiagnostics.renderDevice(context.device))
            append(" rootCause=[").append(chain.joinToString(" <- ")).append(']')
            append(" systemToString=")
            append(VendorStackDiagnostics.truncate(error.toString(), VendorStackDiagnostics.MAX_SYSTEM_TOSTRING_CHARS))
        }
        log(text)
        return 1
    }

    /** 取出并清零待汇报的重复计数；由调用方决定写一行汇总（复制前 drain）。 */
    fun drainSuppressedCount(): Int = gate.drainSuppressedCount()

    /** 清空日志后调用：gate、suppressedCount 清零；snapshotSeq 保持递增不回退。 */
    fun reset() {
        gate.reset()
    }

    companion object {
        /**
         * 全进程共享实例：ApplyWorker 与 MainActivity 走同一 gate、suppressedCount
         * 与递增 snapshotSeq，避免两处各记一套。
         */
        val shared = VendorStackDiagnosticRecorder()
    }
}
