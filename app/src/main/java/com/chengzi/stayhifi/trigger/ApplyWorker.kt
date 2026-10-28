package com.chengzi.stayhifi.trigger

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.StayHiFiApp
import com.chengzi.stayhifi.codec.BtStateParser
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.CodecFamily
import com.chengzi.stayhifi.codec.CodecIdentity
import com.chengzi.stayhifi.codec.CodecIdentityDiagnosticGate
import com.chengzi.stayhifi.codec.CodecIdentityIssue
import com.chengzi.stayhifi.codec.CodecIdentitySource
import com.chengzi.stayhifi.codec.CodecOperationLog
import com.chengzi.stayhifi.codec.CodecPresentation
import com.chengzi.stayhifi.codec.CodecSnapshotPresenter
import com.chengzi.stayhifi.codec.CodecStateSnapshot
import com.chengzi.stayhifi.codec.CodecStateSnapshotJson
import com.chengzi.stayhifi.codec.DeviceProfile
import com.chengzi.stayhifi.codec.DiagnosticSource
import com.chengzi.stayhifi.codec.DiagnosticTrigger
import com.chengzi.stayhifi.codec.Masks
import com.chengzi.stayhifi.codec.Negotiation
import com.chengzi.stayhifi.codec.VendorDiagnosticContext
import com.chengzi.stayhifi.codec.VendorDiagnosticMetadata
import com.chengzi.stayhifi.codec.VendorDiagnostics
import com.chengzi.stayhifi.codec.VendorDiagnosticGate
import com.chengzi.stayhifi.codec.VendorStackDiagnosticRecorder
import com.chengzi.stayhifi.data.ProfileRepository
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.notify.Notifier
import com.chengzi.stayhifi.shell.ProviderAccessException
import com.chengzi.stayhifi.shell.ProviderCoordinator
import com.chengzi.stayhifi.shell.ProviderLease
import com.chengzi.stayhifi.shell.ProviderOwner
import com.chengzi.stayhifi.shell.ProviderSource
import com.chengzi.stayhifi.util.DeviceNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 自动拉满执行器（设计 §4.3 的 goAsync 流程）：
 *
 * 等 Shizuku binder（≤3s）→ bindUserService → 轮询设备出现在 A2DP（≤5s）
 * → 延迟 1.5s 等协议栈协商稳定 → 写入 → 读回验证（writeConfig 内部 500ms×8，
 * 外层再 1s×3 兜底重试）→ 按 notifyResult 发通知 → unbind，零驻留。
 *
 * Task B2：目标由 [ApplyPolicy.assessTarget] 统一裁决，候选由 [ApplyCandidateRunner] 执行；
 * 完整能力使用同条目约束链，能力缺失保留 [Negotiation.descending] 旧兼容路径，
 * current 不完整则零写入。档案不参与拉满决策（仅设备页展示），仅保留 autoApply=false 的自动触发拦截。
 *
 * 对抗审查 I3：run 主体持 [ApplyLock.mutex]，三路触发互斥串行。
 * 对抗审查 I2：force=true（手动入口）跳过 autoApply=false 的自动触发拦截。
 *
 * Task D8：provider 访问统一走进程级 [ProviderCoordinator]——[connectionGuard]
 * （B4 互斥 + 锁内断连复查）持 ApplyLock.mutex 后，经 [applyGate] acquire 带代次
 * lease，read/write/dump helper 各以 lease.withService 包住单次 IPC，lease 由
 * gate finally close（Shizuku 解绑本 owner provider；Root anchor 进程级不解绑）。
 * 冷 Root/切换事务进行中非交互跳过：只记日志，不弹授权、不发修复通知、不回退 Shizuku。
 *
 * Task 35：写入+读回验证通过后，该 MAC 无档案则自动建档（source=AUTO、autoApply=true、
 * 记录读回的实际生效配置），档案仅供设备页展示与按设备开关，仍不参与拉满决策。
 */
class ApplyWorker(context: Context) {

    private val appContext = context.applicationContext
    private val settings by lazy { SettingsRepository(appContext) }
    private val profiles by lazy { ProfileRepository(appContext) }
    private val notifier by lazy { Notifier(appContext) }
    private val audioManager by lazy { appContext.getSystemService(AudioManager::class.java) }

    /**
     * Task D8：进程级 [ProviderCoordinator] 接缝——生产取 StayHiFiApp 单例（构造即异步
     * 首读持久选择，此后按选择签发 lease），JVM 单测注入 fake 装配；本类不再构造/持有
     * 任何 provider（设计 §11.3/§11.4.5）。
     */
    internal var coordinator: ProviderCoordinator? = null

    /**
     * 生产恒非 null（Application 即 StayHiFiApp）；null 只出现在未注入 Coordinator 的
     * B4 遗留 JVM 接缝测试（FakeContext）。
     */
    private fun requireCoordinator(): ProviderCoordinator? =
        coordinator ?: (appContext as? StayHiFiApp)?.providerCoordinator

    /**
     * Task D8：写入路径的 provider 取用闸门（设计 §11.3）。外层 [connectionGuard] 已持
     * ApplyLock.mutex（B4 互斥与锁内断连复查不变，本机 A2DP 预检先于一切 provider
     * 首读/acquire），故闸门持实例内锁，只固定「锁后 acquire → withService IPC →
     * finally close」顺序，避免与外层全局锁重入死锁；跨入口互斥仍由外层全局锁保证。
     */
    internal var applyGate: ApplyProviderGate = ApplyProviderGate(Mutex())

    /**
     * Task D8：restoreEnv 默认实现桥接的当前 lease（仅在 restoreWithLease 的 gate
     * 作用域内非空）。lease 的 close 由 gate finally 负责，restoreEnv.unbind 不再
     * 解绑载体、仅保留为既有单测接缝。
     */
    private var activeRestoreLease: ProviderLease? = null

    /** Task B4：日志落点接缝（默认 LogStore），JVM 单测注入收集器避免 android.util.Log stub。 */
    internal var logSink: (String) -> Unit = { LogStore.log(it) }
    private fun log(msg: String) = logSink(msg)

    /**
     * Task B4：锁后 per-MAC 本机 A2DP 断连复查。internal 以便单测注入 fake probe；
     * force 不传入——手动入口不能绕过复查，只影响 runLocked 的 autoApply 判断。
     */
    internal var connectionGuard = ApplyConnectionGuard(
        ApplyLock.mutex, LocalBluetoothA2dpProbe(appContext), ::log,
    )

    /** 恢复默认载体的环境接缝（Task B4，JVM 单测注入 fake；Task D8：默认实现桥接当前 lease）。 */
    internal var restoreEnv = RestoreEnv(
        shellReady = { waitForShell(activeRestoreLease) },
        hasPrivilege = { hasPrivilege(activeRestoreLease) },
        bind = {
            val lease = activeRestoreLease
                ?: throw IllegalStateException("restore lease 缺失（gate 作用域外调用）")
            withContext(Dispatchers.IO) { lease.withService { it } }
        },
        withCarrier = { block ->
            // final review fix：写回+读回整体留在一次 lease.withService 内——Root 路径因此
            // 落在 RootTransport.withReadyAnchor 的 IPC Mutex 串行边界与锁内代次复查里，
            // 不再取出 service 后在锁外直接 IPC。
            val lease = activeRestoreLease
                ?: throw IllegalStateException("restore lease 缺失（gate 作用域外调用）")
            lease.withService { svc -> block(svc) }
        },
        unbind = { },
        notifyFix = { notifier.notifyFixRequired(it) },
        notify = { t, c -> notifier.notifyResult(t, c) },
        notifyResultIfEnabled = { t, c -> notifyResult(t, c) },
    )

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

    /**
     * Task B5：通用厂商栈诊断上下文（CONNECTION/APPLY）。只读 Build/现有 DeviceNames，
     * 单 rikka 通道（Stellar 透明兼容），不新增任何载体 IO；dumpClues 留空——
     * ApplyWorker 的 dumpsys 证据/24 行线索仍由旧专用 gate 负责，避免重复 raw 块。
     * Task D8：providerKind 如实记录实际 lease 通道（Shizuku=shizuku 与旧值一致）。
     */
    private fun diagnosticContext(mac: String, lease: ProviderLease) = VendorDiagnosticContext(
        mac = mac,
        trigger = DiagnosticTrigger.CONNECTION,
        source = DiagnosticSource.APPLY,
        timestampMillis = System.currentTimeMillis(),
        providerKind = lease.kind.stored,
        device = VendorDiagnosticMetadata.current(),
    )

    suspend fun run(mac: String?, name: String?, force: Boolean = false) {
        val target = mac?.takeIf { it.isNotBlank() } ?: run {
            log("ACL_CONNECTED 无设备地址，跳过")
            return
        }
        log("设备已连接: ${name ?: "?"} ($target)")
        if (ApplyLock.mutex.isLocked) {
            log("等待上一次拉满完成…")
        }
        connectionGuard.run(target) {
            runWithLease(target, name, force)
        }
    }

    /**
     * Task D8：共用入口锁（[connectionGuard] 持 ApplyLock.mutex）内经 [applyGate]
     * 取 lease——锁后才 acquire，lease finally close。冷 Root/切换事务进行中
     * （[ProviderAccessException]）非交互跳过：只记一条日志，不发修复通知、不弹授权、
     * 不回退 Shizuku（§11.4.5）；NEEDS_FOREGROUND_REVALIDATION 已由 Coordinator
     * 按状态变化去重记录。
     */
    private suspend fun runWithLease(mac: String, name: String?, force: Boolean) {
        val coordinator = requireCoordinator() ?: run {
            // 防御不可达路径：宁可跳过本轮触发也不在后台炸出未捕获异常
            log("ProviderCoordinator 不可用，跳过本次触发")
            return
        }
        try {
            applyGate.withLease(
                coordinator, ProviderOwner("apply"), ProviderSource.APPLY,
            ) { lease ->
                runLocked(mac, name, force, lease)
            }
        } catch (e: ProviderAccessException) {
            log("特权通道不可用（${e.reason}），跳过本次触发，需前台确认通道状态")
        }
    }

    private suspend fun runLocked(mac: String, name: String?, force: Boolean, lease: ProviderLease) {
        try {
            if (!waitForShell(lease)) {
                log("Shizuku 未运行，发一键修复通知")
                notifier.notifyFixRequired("Shizuku 未运行")
                return
            }
            // 对抗审查 I5：pingBinder 只证明 server 活着，不证明本 App 仍被授权。
            // 授权被撤后 bind 会抛异常并被误报成「拉满失败」，先查 hasPermission 引导修复。
            // Root 通道的 READY（授权+bind+健康检查）已由 acquire 保证，无这两项检查。
            if (!hasPrivilege(lease)) {
                log("特权授权已被撤销，发引导修复通知")
                notifier.notifyFixRequired("特权授权已被撤销")
                return
            }
            val initial = pollForDevice(lease, mac) ?: run {
                log("设备 5s 内未出现在 A2DP，放弃本次触发")
                return
            }
            delay(SETTLE_MS)
            // Task B5：settled 快照在统一解析点留证——解析 Throwable 走 recordFailure，
            // error JSON 同样留失败证据；正常 settled snapshot 进入通用 recorder
            // （有 reason 或状态跃迁才留行，重复轮询由 gate 抑制），不新增任何载体 IO。
            val diagContext = diagnosticContext(mac, lease)
            val settledJson = lease.withService { svc ->
                withContext(Dispatchers.IO) { svc.readConfig(mac) }
            }
            val settledSnapshot = parseSettledSnapshot(settledJson, diagContext) ?: return
            val settledPresentation = CodecSnapshotPresenter.present(settledSnapshot) ?: run {
                // current 缺失（null/解析降级）：currentNull 等 reason 照常留证。
                vendorStackRecorder.record(settledSnapshot, diagContext)
                return
            }
            val settled = DeviceState(settledPresentation, settledSnapshot)
            vendorStackRecorder.record(settledSnapshot, diagContext)
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
                try {
                    val dumpJson = lease.withService { svc ->
                        withContext(Dispatchers.IO) { svc.dumpBtState() }
                    }
                    val dumpObj = JSONObject(dumpJson)
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

            val profile = profiles.profileFor(mac)
            if (!force && profile != null && !profile.autoApply) {
                log("已有档案但 autoApply=false，跳过写入")
            } else {
                applyDerived(
                    lease, mac, name, settled.current, settled.selectable,
                    settled.presentation.identity,
                )
            }
        } catch (t: Throwable) {
            log("自动拉满失败: $t")
            notifier.notifyResult("音质拉满失败", "${name ?: mac}: ${t.message}")
        }
        // Task D8：lease 由 applyGate finally close（Shizuku 解绑本 owner provider；
        // Root anchor 进程级、不属于任何 lease，不在此解绑）。
    }

    /**
     * 首页「自定义音质」写入口径：与自动拉满同走 [connectionGuard] 入口锁 +
     * 锁后断连复查 + [applyGate] lease（Root 路径落在 RootTransport IPC Mutex 内）。
     * 目标由调用方按 [com.chengzi.stayhifi.codec.ManualSelection] 同条目证明规则构造，
     * 本方法原样透传不改写用户选择；不写档案、不改 autoApply、不触碰自动拉满
     * 默认目标，手动配置也不持久化（不跨重连重放）。
     *
     * 读回验证沿用 [writeVerified] 语义（codecType/rate/bits 一致为准，cs1 被栈
     * 规范化只告警）。成功目标记入 [VerifiedTargetStore]——它同样是已验证生效
     * 目标，否则 30s 轮询会把用户主动降档误报成「系统改回参数」；失败不记。
     * 冷 Root/通道不可用非交互跳过：只记日志返回 false（前台 UI 给反馈）。
     */
    suspend fun applyManual(mac: String, name: String?, target: CodecConfig): Boolean {
        log("手动应用自定义音质: ${name ?: "?"} ($mac) → ${target.summary()}")
        var ok = false
        connectionGuard.run(mac) {
            ok = manualWithLease(mac, name, target)
        }
        return ok
    }

    /** 与 [runWithLease] 同一入口契约：锁后才 acquire，lease finally close，冷 Root 非交互跳过。 */
    private suspend fun manualWithLease(mac: String, name: String?, target: CodecConfig): Boolean {
        val coordinator = requireCoordinator() ?: run {
            log("ProviderCoordinator 不可用，跳过手动应用")
            return false
        }
        return try {
            var applied = false
            applyGate.withLease(
                coordinator, ProviderOwner("apply-manual"), ProviderSource.APPLY,
            ) { lease ->
                applied = manualLocked(mac, name, target, lease)
            }
            applied
        } catch (e: ProviderAccessException) {
            log("特权通道不可用（${e.reason}），手动应用跳过，需前台确认通道状态")
            false
        }
    }

    private suspend fun manualLocked(
        mac: String, name: String?, target: CodecConfig, lease: ProviderLease,
    ): Boolean {
        return try {
            if (!waitForShell(lease)) {
                log("Shizuku 未运行，手动应用跳过")
                return false
            }
            if (!hasPrivilege(lease)) {
                log("特权授权已被撤销，手动应用跳过")
                return false
            }
            // 写前读一次 current：既作 origin 回填（后悔药数据源）又供日志身份；
            // 读失败只跳过回填/退回旧摘要，不阻断用户显式发起的写入。
            val before = readState(lease, mac)
            before?.current?.let { recordOriginConfig(mac, it) }
            val identity = before?.presentation?.identity
                ?: CodecIdentity(
                    nativeType = target.codecType,
                    family = CodecFamily.UNKNOWN,
                    displayName = Masks.codecName(target.codecType),
                    source = CodecIdentitySource.UNKNOWN,
                )
            // 手动显式档位（rate/bits 非 0）必须严格校验 cs1：applied 只证明
            // codecType/rate/bits，用户明确选的码率档不能被栈静默换掉；
            // 恢复自适应等通配目标（rate/bits=0）保持宽松语义，不被严格校验锁死。
            val requireExactCs1 = target.sampleRate != 0 && target.bitsPerSample != 0
            if (writeVerified(lease, mac, target, identity, requireExactCs1)) {
                log("手动应用成功 ✓ ${summary(target, identity)}")
                VerifiedTargetStore.record(mac, target)
                true
            } else {
                log("手动应用失败: 读回与目标不一致 ${summary(target, identity)}")
                false
            }
        } catch (t: Throwable) {
            log("手动应用失败: $t")
            false
        }
    }

    /** 统一路径：三态目标裁决 + 候选执行器；不完整 current 在任何写入副作用前结束。 */
    private suspend fun applyDerived(
        lease: ProviderLease, mac: String, name: String?,
        current: CodecConfig, selectable: List<CodecConfig>, identity: CodecIdentity,
    ) {
        val assessment = ApplyPolicy.assessTarget(current, selectable)
        when (assessment.status) {
            TargetAssessment.Status.COMPLETE -> log("派生本机最高目标")
            TargetAssessment.Status.CAPABILITY_DEGRADED -> log("能力未完整暴露，按兼容目标重试")
            TargetAssessment.Status.INCOMPLETE_CURRENT -> Unit
        }
        val result = ApplyCandidateRunner().run(
            current = current,
            assessment = assessment,
            beforeFirstWrite = { recordOriginConfig(mac, current) },
            writeVerified = { candidate ->
                log("写入候选: ${summary(candidate, identity)}")
                val verified = writeVerified(lease, mac, candidate, identity)
                if (!verified) {
                    log("候选 ${summary(candidate, identity)} 读回不一致，降档重试")
                }
                verified
            },
        )
        when (result) {
            is ApplyRunResult.AlreadyAligned -> {
                val candidate = result.candidate
                if (assessment.status == TargetAssessment.Status.COMPLETE) {
                    log("当前已是本机最高 ${summary(candidate, identity)}，跳过写入")
                    notifyResult("音质已拉满", "${name ?: mac} 已是本机最高音质输出")
                } else {
                    log("当前目标已确认（能力未完整）: ${summary(candidate, identity)}")
                    notifyResult("目标已确认", "${name ?: mac} 当前目标已确认（能力未完整）")
                }
                // 早退同样是已读回验证：保留原漂移基准与自动建档语义，不回填 origin。
                VerifiedTargetStore.record(mac, candidate)
                autoCreateProfile(mac, name, current, origin = null, identity = identity)
            }
            is ApplyRunResult.Applied -> {
                val candidate = result.candidate
                if (assessment.status == TargetAssessment.Status.COMPLETE) {
                    notifyResult("音质拉满成功", "${name ?: mac} → ${summary(candidate, identity)}")
                } else {
                    notifyResult("目标已确认", "${name ?: mac} 当前目标已确认（能力未完整）")
                }
                // 写入+读回验证通过：保留原漂移基准、首次 origin 与自动建档语义。
                VerifiedTargetStore.record(mac, candidate)
                autoCreateProfile(
                    mac, name, readState(lease, mac)?.current ?: candidate,
                    origin = current, identity = identity,
                )
            }
            ApplyRunResult.Exhausted -> {
                log("所有候选均失败")
                notifier.notifyResult("音质拉满失败", "${name ?: mac}：所有候选读回不一致")
            }
            ApplyRunResult.IncompleteCurrent -> {
                log("参数不完整 · 暂无法判定，跳过写入: mac=$mac current=${current.toJson()}")
            }
        }
    }

    /**
     * writeConfig（内部 500ms×8 读回轮询）+ 外层 1s×3 重试。返回目标是否生效。
     * [requireExactCs1]（手动显式档位专用，自动路径恒 false 不改语义）：
     * 服务端 applied 只证明 codecType/rate/bits，cs1 必须由 final 读回证明——
     * final 缺失（服务端给不出读回证据）或 final 与目标不符（栈静默落档）都判未生效，
     * 绝不把别的码率档冒充成用户所选档位。
     */
    private suspend fun writeVerified(
        lease: ProviderLease, mac: String, target: CodecConfig, identity: CodecIdentity,
        requireExactCs1: Boolean = false,
    ): Boolean {
        repeat(OUTER_RETRIES) { attempt ->
            val res = lease.withService { svc ->
                withContext(Dispatchers.IO) { svc.writeConfig(mac, target.toJson()) }
            }
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
                        if (requireExactCs1) {
                            log(
                                "读回落档: final=${summary(final, identity)} " +
                                    "与手动目标 ${summary(target, identity)} 不符，判定未生效"
                            )
                        } else {
                            log("applied=true 但 final=${summary(final, identity)} 三项与目标不符（以 applied 为准）")
                            return true
                        }
                    } else if (final == null && requireExactCs1) {
                        log("读回缺失 final，无法确认手动码率档已生效，按未生效处理")
                    } else {
                        log("读回一致 ✓ final=${final?.let { summary(it, identity) }}")
                        return true
                    }
                } else {
                    log("读回不一致: ${final?.let { summary(it, identity) } ?: "无 final 字段"}")
                }
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
     * Task 42：恢复默认参数（设备页「后悔药」入口）——把首次拉满前的原始协商配置写回设备。
     * Task B4：与自动拉满同走 [connectionGuard]——排队后 DISCONNECTED 时零载体调用、
     * 不向设备写 origin、不发成功通知；CONNECTED/UNKNOWN 才执行原绑定→写入→读回流程，
     * 通知与读回语义不变，不新增 TriggerGate、不改变恢复目标。
     */
    suspend fun restoreDefault(mac: String, name: String, origin: CodecConfig): Boolean {
        log("恢复默认参数: $name ($mac) → ${origin.summary()}")
        var ok = false
        connectionGuard.run(mac) {
            ok = restoreWithLease(mac, name, origin)
        }
        return ok
    }

    /** 与 [runWithLease] 同一入口契约：共用入口锁后才 acquire，lease finally close，冷 Root 非交互跳过。 */
    private suspend fun restoreWithLease(mac: String, name: String, origin: CodecConfig): Boolean {
        // B4 遗留 JVM 接缝测试（FakeContext 无进程级 Coordinator）直接走注入载体；
        // 生产恒走 gate（null 时默认 restoreEnv.bind 也会因 lease 缺失即抛，不会静默写设备）。
        val coordinator = requireCoordinator()
            ?: return restoreDefaultCarrier(mac, name, origin)
        return try {
            applyGate.withLease(
                coordinator, ProviderOwner("apply-restore"), ProviderSource.APPLY,
            ) { lease ->
                activeRestoreLease = lease
                try {
                    restoreDefaultCarrier(mac, name, origin)
                } finally {
                    activeRestoreLease = null
                }
            }
        } catch (e: ProviderAccessException) {
            log("特权通道不可用（${e.reason}），恢复默认跳过，需前台确认通道状态")
            false
        }
    }

    /** 恢复默认载体（锁内、复查后）：绑定 → writeConfig(origin) → 读回验证 → 成功/失败通知。 */
    private suspend fun restoreDefaultCarrier(
        mac: String, name: String, origin: CodecConfig,
    ): Boolean {
        return try {
            if (!restoreEnv.shellReady()) {
                log("Shizuku 未运行，发一键修复通知")
                restoreEnv.notifyFix("Shizuku 未运行")
                return false
            }
            if (!restoreEnv.hasPrivilege()) {
                log("特权授权已被撤销，发引导修复通知")
                restoreEnv.notifyFix("特权授权已被撤销")
                return false
            }
            val withCarrier = restoreEnv.withCarrier
            if (withCarrier != null) {
                // 生产路径：写回+读回整体留在一次 lease.withService 串行边界内（§11.3 Root IPC Mutex）
                withCarrier { service -> restoreWriteAndVerify(mac, name, origin, service) }
            } else {
                // B4 遗留接缝：bind 单趟 + 原写读语义（既有 JVM 单测）
                restoreWriteAndVerify(mac, name, origin, restoreEnv.bind())
            }
        } catch (t: Throwable) {
            log("恢复默认参数失败: $t")
            restoreEnv.notify("恢复默认参数失败", "$name: ${t.message}")
            false
        } finally {
            restoreEnv.unbind()
        }
    }

    /** 写回 origin → 读回验证 → 成功/失败通知；与原 restoreDefaultCarrier 内联块语义一致。 */
    private suspend fun restoreWriteAndVerify(
        mac: String, name: String, origin: CodecConfig, service: ICodecService,
    ): Boolean {
        val res = withContext(Dispatchers.IO) { service.writeConfig(mac, origin.toJson()) }
        val err = parseError(res)
        if (err != null) {
            log("恢复默认写入失败: $err")
            restoreEnv.notify("恢复默认参数失败", "$name: $err")
            return false
        }
        // 读回验证：与拉满同一套 matches 生效语义（codecType/rate/bits/cs1）
        val actual = readState(service, mac)?.current
        return if (actual != null && ApplyPolicy.matches(origin, actual)) {
            log("恢复默认成功 ✓ 读回=${actual.summary()}")
            // Task 43：后悔药生效后旧的拉满目标作废，否则轮询立刻把默认参数误报成漂移
            VerifiedTargetStore.clear(mac)
            restoreEnv.notifyResultIfEnabled("已恢复默认参数", "$name → ${actual.summary()}")
            true
        } else {
            log("恢复默认读回不一致: ${actual?.summary() ?: "读取失败"}")
            restoreEnv.notify("恢复默认参数失败", "$name：读回与原始配置不一致")
            false
        }
    }

    /**
     * 轮询 Shizuku binder，最多 3s。Task D8：只读 lease 内 Shizuku provider；
     * Root 通道（shizukuProvider 为 null）READY 已由 acquire 保证，直接放行。
     */
    private suspend fun waitForShell(lease: ProviderLease?): Boolean {
        val provider = lease?.shizukuProvider ?: return true
        repeat(SHELL_WAIT_STEPS) {
            if (provider.isRunning) return true
            delay(POLL_DELAY_MS)
        }
        return provider.isRunning
    }

    /** Shizuku 通道查 hasPermission（授权撤销引导修复）；Root READY 已由 acquire 保证。 */
    private fun hasPrivilege(lease: ProviderLease?): Boolean =
        lease?.shizukuProvider?.hasPermission ?: true

    /** ACL_CONNECTED 先于 A2DP attach（PoC 实测最多 ~2s）：轮询 readConfig 直到设备出现，最多 5s。 */
    private suspend fun pollForDevice(lease: ProviderLease, mac: String): DeviceState? {
        repeat(DEVICE_POLL_STEPS) {
            val res = lease.withService { svc ->
                withContext(Dispatchers.IO) { svc.readConfig(mac) }
            }
            readState(res)?.let { return it }
            delay(POLL_DELAY_MS)
        }
        return readState(lease.withService { svc ->
            withContext(Dispatchers.IO) { svc.readConfig(mac) }
        })
    }

    /** Task D8：lease 版读快照——单次读取独立 withService；codec 数据类型与返回语义不变。 */
    private suspend fun readState(lease: ProviderLease, mac: String): DeviceState? =
        readState(lease.withService { svc ->
            withContext(Dispatchers.IO) { svc.readConfig(mac) }
        })

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
                DeviceState(presentation, snapshot)
            }
        }
    } catch (t: Throwable) {
        log("readConfig 结果解析失败: $t")
        null
    }

    /**
     * Task B5：settled 快照的解析与失败留证。error JSON 与解析 Throwable 都走
     * recordFailure（分阶段 root cause，重复失败被 gate 抑制），返回 null 结束本轮；
     * 正常解析返回完整 snapshot 供通用 recorder 与后续主链路共用（不新增读取）。
     */
    private fun parseSettledSnapshot(
        json: String, context: VendorDiagnosticContext,
    ): CodecStateSnapshot? = try {
        val obj = JSONObject(json)
        if (obj.has("error")) {
            vendorStackRecorder.recordFailure(
                context, "readConfig", IllegalStateException(obj.optString("error")),
            )
            null
        } else {
            CodecStateSnapshotJson.parse(json)
        }
    } catch (t: Throwable) {
        vendorStackRecorder.recordFailure(context, "readConfig 解析", t)
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
        val snapshot: CodecStateSnapshot,
    ) {
        val current: CodecConfig get() = presentation.current
        val selectable: List<CodecConfig> get() = snapshot.selectable
    }

    /** 恢复默认载体的环境接缝（Task B4，JVM 单测注入 fake；默认即生产实现，见 [restoreEnv]）。 */
    internal class RestoreEnv(
        val shellReady: suspend () -> Boolean,
        val hasPrivilege: () -> Boolean,
        val bind: suspend () -> ICodecService,
        val unbind: () -> Unit,
        val notifyFix: (String) -> Unit,
        /** 直接发结果通知（失败路径，与生产 notifier.notifyResult 等价）。 */
        val notify: (String, String) -> Unit,
        /** 经 notifyResult 开关门控的结果通知（成功路径，与生产私有 notifyResult 等价）。 */
        val notifyResultIfEnabled: suspend (String, String) -> Unit,
        /**
         * final review fix：载体事务边界。非 null 时写回+读回整体在该边界内执行
         * （生产默认=一次 lease.withService，Root 路径落在 RootTransport IPC Mutex
         * 串行边界与锁内代次复查里）；null 时回退 [bind] 单趟（B4 遗留接缝语义）。
         */
        val withCarrier: (suspend (suspend (ICodecService) -> Boolean) -> Boolean)? = null,
    )

    companion object {
        private const val SHELL_WAIT_STEPS = 6          // 3s
        private const val DEVICE_POLL_STEPS = 10        // 5s
        private const val POLL_DELAY_MS = 500L
        private const val SETTLE_MS = 1500L             // 等 A2DP 协商稳定
        private const val OUTER_RETRIES = 3             // 读回不一致重试次数
        private const val RETRY_DELAY_MS = 1000L

        /** Task B5：ApplyWorker 与 MainActivity 共用的通用厂商栈 recorder。 */
        private val vendorStackRecorder = VendorStackDiagnosticRecorder.shared

        /** Task 53：ApplyWorker 只用固定 apply-dump 类别做首次配置去重。 */
        private val vendorDiagnosticGate = VendorDiagnosticGate()

        private fun recordUnknownVendor(
            mac: String, current: CodecConfig, sampleKind: String,
        ): Boolean = vendorDiagnosticGate.shouldRecord(mac, current, sampleKind)

        /** 日志页清空后允许「重连一次」重新留下同配置证据。 */
        internal fun resetVendorDiagnostics() {
            vendorDiagnosticGate.reset()
            vendorStackRecorder.reset()
        }
    }
}
