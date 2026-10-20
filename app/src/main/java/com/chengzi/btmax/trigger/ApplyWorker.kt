package com.chengzi.btmax.trigger

import android.content.Context
import com.chengzi.btmax.ICodecService
import com.chengzi.btmax.LogStore
import com.chengzi.btmax.codec.CodecConfig
import com.chengzi.btmax.codec.Negotiation
import com.chengzi.btmax.data.ProfileRepository
import com.chengzi.btmax.data.SettingsRepository
import com.chengzi.btmax.notify.Notifier
import com.chengzi.btmax.shell.ShizukuShellProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 自动拉满执行器（设计 §4.3 的 goAsync 流程）：
 *
 * 等 Shizuku binder（≤3s）→ bindUserService → 轮询设备出现在 A2DP（≤5s）
 * → 延迟 1.5s 等协议栈协商稳定 → 写入 → 读回验证（writeConfig 内部 500ms×8，
 * 外层再 1s×3 兜底重试）→ 按 notifyResult 发通知 → unbind，零驻留。
 *
 * 有档案：按 [Negotiation.descending] 逐候选写入，直到读回一致；
 * 无档案：[ApplyPolicy.autoTarget] 全局默认策略写入一次并验证。
 */
class ApplyWorker(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsRepository(appContext)
    private val profiles = ProfileRepository(appContext)
    private val notifier = Notifier(appContext)
    private val provider = ShizukuShellProvider(appContext)

    private fun log(msg: String) = LogStore.log(msg)

    suspend fun run(mac: String?, name: String?) {
        if (mac.isNullOrBlank()) {
            log("ACL_CONNECTED 无设备地址，跳过")
            return
        }
        log("设备已连接: ${name ?: "?"} ($mac)")
        try {
            if (!waitForShell()) {
                log("Shizuku 未运行，发一键修复通知")
                notifier.notifyFixRequired("Shizuku 未运行")
                return
            }
            val service = withContext(Dispatchers.IO) { provider.bind() }
            val initial = pollForDevice(service, mac) ?: run {
                log("设备 5s 内未出现在 A2DP，放弃本次触发")
                return
            }
            delay(SETTLE_MS)
            val settled = readState(service, mac) ?: run {
                log("协商稳定后读取失败，放弃")
                return
            }
            log("当前配置: ${settled.current.summary()}")

            val profile = profiles.profileFor(mac)
            when {
                profile != null && !profile.autoApply ->
                    log("已有档案但 autoApply=false，跳过写入")
                profile != null ->
                    applyProfile(service, mac, name, settled.current, profile.config)
                else -> {
                    log("无档案新设备，走全局默认策略")
                    applyDefault(service, mac, name, settled.current, settled.selectable)
                }
            }
        } catch (t: Throwable) {
            log("自动拉满失败: $t")
            notifier.notifyResult("音质拉满失败", "${name ?: mac}: ${t.message}")
        } finally {
            provider.unbind()
        }
    }

    /** 有档案：按降档序列逐候选写入，直到读回一致（设计 §4.2.1）。 */
    private suspend fun applyProfile(
        service: ICodecService, mac: String, name: String?,
        current: CodecConfig, golden: CodecConfig,
    ) {
        for (candidate in Negotiation.descending(golden)) {
            if (!ApplyPolicy.decide(current, candidate)) {
                log("当前已是目标配置 ${candidate.summary()}，跳过写入")
                notifyResult("音质已拉满", "${name ?: mac} 无需写入")
                return
            }
            log("写入候选: ${candidate.summary()}")
            if (writeVerified(service, mac, candidate)) {
                notifyResult("音质拉满成功", "${name ?: mac} → ${candidate.summary()}")
                return
            }
            log("候选 ${candidate.summary()} 读回不一致，降档重试")
        }
        log("所有候选均失败")
        notifier.notifyResult("音质拉满失败", "${name ?: mac}：所有候选读回不一致")
    }

    /** 无档案：全局默认策略写入一次并验证。 */
    private suspend fun applyDefault(
        service: ICodecService, mac: String, name: String?,
        current: CodecConfig, selectable: List<CodecConfig>,
    ) {
        val target = ApplyPolicy.autoTarget(current, selectable)
        if (!ApplyPolicy.decide(current, target)) {
            log("当前已符合默认策略 ${target.summary()}，跳过写入")
            notifyResult("音质已拉满", "${name ?: mac} 已符合默认策略")
            return
        }
        log("按默认策略写入: ${target.summary()}")
        if (writeVerified(service, mac, target)) {
            notifyResult("音质拉满成功", "${name ?: mac} → ${target.summary()}")
        } else {
            notifier.notifyResult("音质拉满失败", "${name ?: mac}：读回不一致")
        }
    }

    /** writeConfig（内部 500ms×8 读回轮询）+ 外层 1s×3 重试。返回目标是否生效。 */
    private suspend fun writeVerified(
        service: ICodecService, mac: String, target: CodecConfig,
    ): Boolean {
        repeat(OUTER_RETRIES) { attempt ->
            val res = withContext(Dispatchers.IO) { service.writeConfig(mac, target.toJson()) }
            val err = parseError(res)
            if (err != null) {
                log("writeConfig 返回 error: $err")
            } else {
                val applied = parseApplied(res)
                val final = parseFinal(res)
                if (applied) {
                    // applied 是服务端按 codecType+rate+bits 语义判定的，直接采信；
                    // final 仅做交叉验证（栈可能规范化 priority/cs1~cs4 等字段）
                    if (final != null && !ApplyPolicy.matches(target, final)) {
                        log("applied=true 但 final=${final.summary()} 三项与目标不符（以 applied 为准）")
                    } else {
                        log("读回一致 ✓ final=${final?.summary()}")
                    }
                    return true
                }
                log("读回不一致: ${final?.summary() ?: "无 final 字段"}")
            }
            if (attempt < OUTER_RETRIES - 1) {
                delay(RETRY_DELAY_MS)
            }
        }
        return false
    }

    /** 轮询 Shizuku binder，最多 3s。 */
    private suspend fun waitForShell(): Boolean {
        repeat(SHELL_WAIT_STEPS) {
            if (provider.isRunning) return true
            delay(POLL_DELAY_MS)
        }
        return provider.isRunning
    }

    /** ACL_CONNECTED 先于 A2DP attach（PoC 实测最多 ~2s）：轮询 readConfig 直到设备出现，最多 5s。 */
    private suspend fun pollForDevice(service: ICodecService, mac: String): DeviceState? {
        repeat(DEVICE_POLL_STEPS) {
            val res = withContext(Dispatchers.IO) { service.readConfig(mac) }
            readState(res)?.let { return it }
            delay(POLL_DELAY_MS)
        }
        return readState(withContext(Dispatchers.IO) { service.readConfig(mac) })
    }

    private fun readState(service: ICodecService, mac: String): DeviceState? =
        readState(service.readConfig(mac))

    /** 解析 readConfig 结果；{error:...} 或解析失败返回 null。 */
    private fun readState(json: String): DeviceState? = try {
        val obj = JSONObject(json)
        if (obj.has("error")) null
        else {
            val current = CodecConfig.fromJson(obj.getJSONObject("current").toString())
            val selectable = obj.optJSONArray("selectable")?.let { arr ->
                (0 until arr.length()).map { CodecConfig.fromJson(arr.getJSONObject(it).toString()) }
            } ?: emptyList()
            DeviceState(current, selectable)
        }
    } catch (t: Throwable) {
        log("readConfig 结果解析失败: $t")
        null
    }

    private fun parseError(json: String): String? = try {
        JSONObject(json).optString("error").takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        "结果解析失败: $t"
    }

    private fun parseApplied(json: String): Boolean = try {
        JSONObject(json).optBoolean("applied", false)
    } catch (t: Throwable) {
        false
    }

    private fun parseFinal(json: String): CodecConfig? = try {
        val obj = JSONObject(json)
        if (obj.has("final")) CodecConfig.fromJson(obj.getJSONObject("final").toString()) else null
    } catch (t: Throwable) {
        null
    }

    private suspend fun notifyResult(title: String, content: String) {
        if (settings.isNotifyResult()) {
            notifier.notifyResult(title, content)
        }
    }

    private data class DeviceState(val current: CodecConfig, val selectable: List<CodecConfig>)

    companion object {
        private const val SHELL_WAIT_STEPS = 6          // 3s
        private const val DEVICE_POLL_STEPS = 10        // 5s
        private const val POLL_DELAY_MS = 500L
        private const val SETTLE_MS = 1500L             // 等 A2DP 协商稳定
        private const val OUTER_RETRIES = 3             // 读回不一致重试次数
        private const val RETRY_DELAY_MS = 1000L
    }
}
