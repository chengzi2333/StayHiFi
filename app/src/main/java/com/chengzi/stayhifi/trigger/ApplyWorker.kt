package com.chengzi.stayhifi.trigger

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.codec.BtStateParser
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.CodecIdentity
import com.chengzi.stayhifi.codec.CodecIdentityDiagnosticGate
import com.chengzi.stayhifi.codec.CodecIdentityIssue
import com.chengzi.stayhifi.codec.CodecOperationLog
import com.chengzi.stayhifi.codec.CodecPresentation
import com.chengzi.stayhifi.codec.CodecSnapshotPresenter
import com.chengzi.stayhifi.codec.CodecStateSnapshotJson
import com.chengzi.stayhifi.codec.DeviceProfile
import com.chengzi.stayhifi.codec.Masks
import com.chengzi.stayhifi.codec.Negotiation
import com.chengzi.stayhifi.codec.VendorDiagnostics
import com.chengzi.stayhifi.codec.VendorDiagnosticGate
import com.chengzi.stayhifi.data.ProfileRepository
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.notify.Notifier
import com.chengzi.stayhifi.shell.ShizukuShellProvider
import com.chengzi.stayhifi.util.DeviceNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 自动拉满执行器（设计 §4.3 的 goAsync 流程）：
 *
 * 等 Shizuku binder（≤3s）→ bindUserService → 轮询设备出现在 A2DP（≤5s）
 * → 延迟 1.5s 等协议栈协商稳定 → 写入 → 读回验证（writeConfig 内部 500ms×8，
 * 外层再 1s×3 兜底重试）→ 按 notifyResult 发通知 → unbind，零驻留。
 *
 * Task 27 两态裁决：目标唯一来源 = [ApplyPolicy.deriveTarget]（现场派生本机最高），
 * 所有设备统一路径，按 [Negotiation.descending] 逐候选写入直到读回一致；
 * 档案不参与拉满决策（仅设备页展示），仅保留 autoApply=false 的自动触发拦截。
 *
 * 对抗审查 I3：run 主体持 [ApplyLock.mutex]，三路触发互斥串行。
 * 对抗审查 I2：force=true（手动入口）跳过 autoApply=false 的自动触发拦截。
 *
 * Task 35：写入+读回验证通过后，该 MAC 无档案则自动建档（source=AUTO、autoApply=true、
 * 记录读回的实际生效配置），档案仅供设备页展示与按设备开关，仍不参与拉满决策。
 */
class ApplyWorker(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsRepository(appContext)
    private val profiles = ProfileRepository(appContext)
    private val notifier = Notifier(appContext)
    private val provider = ShizukuShellProvider(appContext)
    private val audioManager by lazy { appContext.getSystemService(AudioManager::class.java) }

    private fun log(msg: String) = LogStore.log(msg)

    private fun recordIdentityConflict(mac: String, presentation: CodecPresentation) {
        if (CodecIdentityIssue.SYSTEM_LEGACY_CONFLICT !in presentation.issues) return
        val identity = presentation.identity
        if (CodecIdentityDiagnosticGate.shouldRecord(mac, presentation.current, identity)) {
            log(
                "IDENTITY_CONFLICT: nativeType=${identity.nativeType} " +
                    "system=${identity.displayName} legacy=${Masks.codecName(identity.nativeType)}"
            )
        }
    }

    /** settled identity 只用于同 codecType 的派生候选与读回；跨类型保留旧摘要。 */
    private fun summary(config: CodecConfig, identity: CodecIdentity): String =
        CodecOperationLog.resolvedSummary(config, identity)

    suspend fun run(mac: String?, name: String?, force: Boolean = false) {
        val target = mac?.takeIf { it.isNotBlank() } ?: run {
            log("ACL_CONNECTED 无设备地址，跳过")
            return
        }
        log("设备已连接: ${name ?: "?"} ($target)")
        if (ApplyLock.mutex.isLocked) {
            log("等待上一次拉满完成…")
        }
        ApplyLock.mutex.withLock {
            runLocked(target, name, force)
        }
    }

    private suspend fun runLocked(mac: String, name: String?, force: Boolean) {
        try {
            if (!waitForShell()) {
                log("Shizuku 未运行，发一键修复通知")
                notifier.notifyFixRequired("Shizuku 未运行")
                return
            }
            // 对抗审查 I5：pingBinder 只证明 server 活着，不证明本 App 仍被授权。
            // 授权被撤后 bind 会抛异常并被误报成「拉满失败」，先查 hasPermission 引导修复。
            if (!provider.hasPermission) {
                log("特权授权已被撤销，发引导修复通知")
                notifier.notifyFixRequired("特权授权已被撤销")
                return
            }
            val service = withContext(Dispatchers.IO) { provider.bind() }
            val initial = pollForDevice(service, mac) ?: run {
                log("设备 5s 内未出现在 A2DP，放弃本次触发")
                return
            }
            delay(SETTLE_MS)
            val settled = readState(service, mac) ?: return
            recordIdentityConflict(mac, settled.presentation)
            log("当前配置: ${settled.presentation.summary.text()}")
            // Task 53：轻量厂商诊断（仅现有 LogStore，不改写入/映射行为）——
            // 未识别 vendor 保持原日志；已佐证的 vendor LHDC（含 19 号）也记录 raw 9 字段。
            // dump 前固定使用 apply-dump 去重，真实 sampleKind 只在 dump 后标注本次证据。
            // Task 8：门控改用 identity——标准编码器不进入本块，不为其额外发起 dump。
            val capsMask = Masks.capsRateMask(settled.current.codecType, settled.selectable)
            if (VendorDiagnostics.shouldLog(settled.presentation.identity) &&
                recordUnknownVendor(mac, settled.current, "apply-dump")
            ) {
                log(
                    "厂商编码器诊断: 机型=${DeviceNames.marketModel()} " +
                        "Android=${Build.VERSION.RELEASE} 构建=${Build.DISPLAY} " +
                        "mac=$mac capsRateMask=$capsMask current=${settled.current.toJson()}"
                )
                val isMusicActive = VendorDiagnostics.readMusicActive { audioManager.isMusicActive }
                withContext(Dispatchers.IO) {
                    try {
                        val dumpObj = JSONObject(service.dumpBtState())
                        if (dumpObj.has("error")) {
                            log(
                                "dumpsys诊断失败: ${dumpObj.optString("error")} " +
                                    "source=apply isMusicActive=$isMusicActive sample=dump-error"
                            )
                        } else {
                            val dump = dumpObj.optString("dump")
                            val parseResult = BtStateParser.parseResult(
                                dump, settled.presentation.identity,
                            )
                            val state = parseResult.state
                            val kind = VendorDiagnostics.sampleKind(parseResult)
                            if (state != null) {
                                log(
                                    "dumpsys证据: mac=$mac source=apply isMusicActive=$isMusicActive " +
                                        "sample=$kind ${VendorDiagnostics.evidence(state)}"
                                )
                            } else {
                                log(
                                    "dumpsys证据: mac=$mac source=apply isMusicActive=$isMusicActive " +
                                        "sample=$kind ${VendorDiagnostics.missingStateEvidence(parseResult)}"
                                )
                                VendorDiagnostics.dumpClues(dump)
                                    .forEach { log("dumpsys线索: $it") }
                            }
                        }
                    } catch (t: Throwable) {
                        log(
                            "dumpsys诊断异常: $t source=apply isMusicActive=$isMusicActive " +
                                "sample=dump-exception"
                        )
                    }
                }
            }

            val profile = profiles.profileFor(mac)
            if (!force && profile != null && !profile.autoApply) {
                log("已有档案但 autoApply=false，跳过写入")
            } else {
                log("派生本机最高目标")
                applyDerived(
                    service, mac, name, settled.current, settled.selectable,
                    settled.presentation.identity,
                )
            }
        } catch (t: Throwable) {
            log("自动拉满失败: $t")
            notifier.notifyResult("音质拉满失败", "${name ?: mac}: ${t.message}")
        } finally {
            provider.unbind()
        }
    }

    /** 统一路径：deriveTarget 派生本机最高目标，按降档序列逐候选写入，直到读回一致（设计 §4.2.1）。 */
    private suspend fun applyDerived(
        service: ICodecService, mac: String, name: String?,
        current: CodecConfig, selectable: List<CodecConfig>, identity: CodecIdentity,
    ) {
        val golden = ApplyPolicy.deriveTarget(current, selectable)
        for (candidate in Negotiation.descending(golden)) {
            if (!ApplyPolicy.decide(current, candidate)) {
                log("当前已是本机最高 ${summary(candidate, identity)}，跳过写入")
                notifyResult("音质已拉满", "${name ?: mac} 已是本机最高音质输出")
                // Task 43：早退=读回已验证与本机最高一致，记入漂移基准（漂移提醒用）。
                VerifiedTargetStore.record(mac, candidate)
                // Task 36 Bug C：老用户重装后参数已被上次装机拉满（蓝牙栈保存），必命中此早退分支，
                // 此前直接 return 不走 autoCreateProfile，设备页永远空白。
                // 读回即验证：current 是 settled 阶段读回的真实生效配置，
                // 早退=已验证与本机最高一致，同样够建档资格。
                // Task 42：早退分支不回填 originConfig——写入前的状态就是最高，不是「默认」，
                // 把它当原始配置记下来会让「恢复默认参数」变成再拉满一次，失去后悔药意义。
                autoCreateProfile(mac, name, current, origin = null, identity = identity)
                return
            }
            // Task 42：写入第一个候选之前，把写入前原始协商配置（settled.current）记入档案，
            // 供设备页「恢复默认参数」写回。只记第一次，后续触发不覆盖。
            recordOriginConfig(mac, current)
            log("写入候选: ${summary(candidate, identity)}")
            if (writeVerified(service, mac, candidate, identity)) {
                notifyResult("音质拉满成功", "${name ?: mac} → ${summary(candidate, identity)}")
                // Task 43：写入+读回验证通过 → 记入漂移基准（漂移提醒用）。
                // 注意：漂移比对用 ApplyPolicy.matches（含 cs1），而服务端 applied 判定
                // 不含 cs1——栈未套用 cs1 的罕见情况会被判成漂移，属可接受的保守误报。
                VerifiedTargetStore.record(mac, candidate)
                // Task 35：确认实际生效（写入+读回验证通过）后才建档，不是写完就建；
                // 档案 config 记录读回的真实落点（非派生目标）。
                autoCreateProfile(
                    mac, name, readState(service, mac)?.current ?: candidate,
                    origin = current, identity = identity,
                )
                return
            }
            log("候选 ${summary(candidate, identity)} 读回不一致，降档重试")
        }
        log("所有候选均失败")
        notifier.notifyResult("音质拉满失败", "${name ?: mac}：所有候选读回不一致")
    }

    /** writeConfig（内部 500ms×8 读回轮询）+ 外层 1s×3 重试。返回目标是否生效。 */
    private suspend fun writeVerified(
        service: ICodecService, mac: String, target: CodecConfig, identity: CodecIdentity,
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
                        log("applied=true 但 final=${summary(final, identity)} 三项与目标不符（以 applied 为准）")
                    } else {
                        log("读回一致 ✓ final=${final?.let { summary(it, identity) }}")
                    }
                    return true
                }
                log("读回不一致: ${final?.let { summary(it, identity) } ?: "无 final 字段"}")
            }
            if (attempt < OUTER_RETRIES - 1) {
                delay(RETRY_DELAY_MS)
            }
        }
        return false
    }

    /**
     * Task 35：拉满成功后自动建档——该 MAC 已有档案（CAPTURED/PRESET/旧 AUTO）一律不碰不覆盖，
     * 重录覆盖只走向导。出处四字段照抄 MainActivity onWizardSave 的写法。
     * 建档只作记录 + 提供按设备 autoApply 开关，不参与拉满决策；失败只记日志，绝不影响主链路。
     *
     * Task 42：[origin] = 首次写入前的原始协商配置（走写入分支时传入 settled.current）；
     * 早退分支传 null——那时的状态已是最高，不是「默认」。
     */
    private fun autoCreateProfile(
        mac: String, name: String?, applied: CodecConfig, origin: CodecConfig?, identity: CodecIdentity,
    ) {
        try {
            if (profiles.profileFor(mac) != null) return
            profiles.save(DeviceProfile(
                mac = mac,
                name = name ?: mac,
                config = applied,
                source = DeviceProfile.Source.AUTO,
                autoApply = true,
                // 出处四字段（Task 27 形状，Task 35 复用）：录制设备 / Android 版本 / ROM 构建号 / 时间。
                recordedOnModel = DeviceNames.marketModel(),
                recordedAndroidVersion = Build.VERSION.RELEASE,
                recordedBuildDisplay = Build.DISPLAY,
                recordedAtMillis = System.currentTimeMillis(),
                originConfig = origin,
            ))
            log(CodecOperationLog.autoCreatedProfile(name ?: mac, mac, applied, identity))
        } catch (t: Throwable) {
            log("自动建档失败（不影响拉满主链路）: $t")
        }
    }

    /**
     * Task 42：首次写入前把原始协商配置记入档案（后悔药的数据来源）。
     * 已有档案（含向导录的）且 originConfig==null → 回填 copy(originConfig=…)；
     * 已有 originConfig → 不覆盖（永远记第一次的）；无档案 → 由成功后的 autoCreateProfile 带上。
     * 失败只记日志，绝不影响主链路。
     */
    private fun recordOriginConfig(mac: String, current: CodecConfig) {
        try {
            val profile = profiles.profileFor(mac) ?: return
            if (profile.originConfig == null) {
                profiles.save(profile.copy(originConfig = current))
                log("已回填原始配置: ${profile.name} ($mac) ${current.summary()}")
            }
        } catch (t: Throwable) {
            log("记录原始配置失败（不影响拉满主链路）: $t")
        }
    }

    /**
     * Task 42：恢复默认参数（设备页「后悔药」入口）——把首次拉满前的原始协商配置写回设备：
     * 绑定载体 → writeConfig(origin) → 读回验证（ApplyPolicy.matches(origin, actual)）→ 成功/失败通知。
     * 与自动拉满同一把 [ApplyLock.mutex] 互斥串行；失败不碰档案，返回是否成功（供调用方决定是否连带关自动拉满）。
     */
    suspend fun restoreDefault(mac: String, name: String, origin: CodecConfig): Boolean {
        log("恢复默认参数: $name ($mac) → ${origin.summary()}")
        return ApplyLock.mutex.withLock {
            try {
                if (!waitForShell()) {
                    log("Shizuku 未运行，发一键修复通知")
                    notifier.notifyFixRequired("Shizuku 未运行")
                    return@withLock false
                }
                if (!provider.hasPermission) {
                    log("特权授权已被撤销，发引导修复通知")
                    notifier.notifyFixRequired("特权授权已被撤销")
                    return@withLock false
                }
                val service = withContext(Dispatchers.IO) { provider.bind() }
                val res = withContext(Dispatchers.IO) { service.writeConfig(mac, origin.toJson()) }
                val err = parseError(res)
                if (err != null) {
                    log("恢复默认写入失败: $err")
                    notifier.notifyResult("恢复默认参数失败", "$name: $err")
                    return@withLock false
                }
                // 读回验证：与拉满同一套 matches 生效语义（codecType/rate/bits/cs1）
                val actual = readState(service, mac)?.current
                if (actual != null && ApplyPolicy.matches(origin, actual)) {
                    log("恢复默认成功 ✓ 读回=${actual.summary()}")
                    // Task 43：后悔药生效后旧的拉满目标作废，否则轮询立刻把默认参数误报成漂移
                    VerifiedTargetStore.clear(mac)
                    notifyResult("已恢复默认参数", "$name → ${actual.summary()}")
                    true
                } else {
                    log("恢复默认读回不一致: ${actual?.summary() ?: "读取失败"}")
                    notifier.notifyResult("恢复默认参数失败", "$name：读回与原始配置不一致")
                    false
                }
            } catch (t: Throwable) {
                log("恢复默认参数失败: $t")
                notifier.notifyResult("恢复默认参数失败", "$name: ${t.message}")
                false
            } finally {
                provider.unbind()
            }
        }
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

    /** 解析 readConfig 快照；error、current:null 或解析失败返回 null。 */
    private fun readState(json: String): DeviceState? = try {
        val obj = JSONObject(json)
        if (obj.has("error")) {
            null
        } else {
            val snapshot = CodecStateSnapshotJson.parse(json)
            CodecSnapshotPresenter.present(snapshot)?.let { presentation ->
                DeviceState(presentation, snapshot.selectable)
            }
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

    private data class DeviceState(
        val presentation: CodecPresentation,
        val selectable: List<CodecConfig>,
    ) {
        val current: CodecConfig get() = presentation.current
    }

    companion object {
        private const val SHELL_WAIT_STEPS = 6          // 3s
        private const val DEVICE_POLL_STEPS = 10        // 5s
        private const val POLL_DELAY_MS = 500L
        private const val SETTLE_MS = 1500L             // 等 A2DP 协商稳定
        private const val OUTER_RETRIES = 3             // 读回不一致重试次数
        private const val RETRY_DELAY_MS = 1000L

        /** Task 53：ApplyWorker 只用固定 apply-dump 类别做首次配置去重。 */
        private val vendorDiagnosticGate = VendorDiagnosticGate()

        private fun recordUnknownVendor(
            mac: String, current: CodecConfig, sampleKind: String,
        ): Boolean = vendorDiagnosticGate.shouldRecord(mac, current, sampleKind)

        /** 日志页清空后允许「重连一次」重新留下同配置证据。 */
        internal fun resetVendorDiagnostics() {
            vendorDiagnosticGate.reset()
        }
    }
}
