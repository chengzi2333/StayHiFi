# 能力目标、连接触发、通用诊断与反馈日志 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让写入目标与完整降档链始终由同一 selectable 能力条目证明，合并 per-MAC 连接触发并在锁后阻断过期 worker，同时提供有界通用厂商栈诊断和稳定脱敏的反馈日志。

**Architecture:** 以 `TargetAssessment` 统一 ApplyWorker 与首页的目标/满格语义，完整能力走同条目约束链，能力缺失保留旧降档兼容路径，current 不完整则零写入。三条自动触发与两个手动入口共用进程内 `TriggerGate`；ApplyWorker 在 `ApplyLock` 内先用本机蓝牙 API 做 per-MAC 复查。诊断只消费计划 A 的 `CodecStateSnapshot` 与同轮 dump，不新增载体查询；独立纯 Kotlin gate/formatter 控制去重和预算，反馈导出统一映射 MAC 与设备名。

**Tech Stack:** Android、Kotlin、Kotlin coroutines、Jetpack Compose、JUnit4、Gradle/AGP、现有 Shizuku/Stellar 载体。

**Spec:** `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md` §1-5、§7-9、§12-16；本文件仅实施计划 B，前置依赖计划 A 的只读模型、共享解析和统一身份接口。

## Global Constraints

- 先完成计划 A；本计划只消费其 `CodecStateSnapshot(current, localCapabilities, selectable, supportedTypes, extendedIdentity, identityDiagnostics, readStatus)`、共享 JSON 解析、`CodecIdentity`/Resolver/Formatter，不重新创建系统身份 resolver/parser、`CodecStateSnapshot` 或 RealtimeDisplay。
- `CodecConfig` 继续是原 9 字段；不改 `ICodecService`、writeConfig JSON、`ApplyPolicy.matches()` 既有通配语义、`BEST_CS1`/cs1 规则、originConfig 首次写入语义、restoreDefault 或 VerifiedTargetStore 的既有协议。
- 不把不同 selectable 条目的 rate/bits/channel 拼成写入候选；不用诊断或集证明写入有效；不把 0/NONE 当已证明档位，不跨 codecType，不新增 aptX 专属分支、手动档位、Root、机型名功能或 MediaTek 硬编码。
- `CAPABILITY_DEGRADED` 保留旧 `deriveTarget()` + `Negotiation.descending(base)` 兼容路径；`INCOMPLETE_CURRENT` 不写、不记 origin、不记已验证最高、不发成功通知且永远不满格。
- TriggerGate 为进程内 per-MAC、单调时钟、默认 `10_000ms`；活动期或完成后的窗口内合并。force 只绕过已完成窗口，不绕过同 MAC 活动任务、`ApplyLock` 或锁后断连复查。
- 锁后复查只使用本机 `BluetoothManager.getConnectedDevices(BluetoothProfile.A2DP)`；明确断开时在任何 `isRunning/hasPermission/bind/readConfig/writeConfig/dump*` 载体调用前结束。日志只证明合并、排队和跳过，不声称曾发生“断开后错误写入”。
- 保留 5s A2DP attach 轮询与现有 FGS 让位/双触发互斥；不新增 dumpsys、常驻轮询、binder 查询、持久日志、上传或运行时网络用途。
- 通用诊断预算：最多 8 条 local、16 条 selectable、32 条 supportedTypes、每事件含复用 dump 在内最多 `12_000` 字符，root cause 每条最多 240 字符、system toString 最多 300 字符、dump clue 每行最多 240 字符且最多 24 行；gate 最多 64 个 LRU signature。不得为截断丢弃 current，遗漏必须计入 `truncatedCount`。
- LogStore 仍为每进程 200 行内存环形缓冲；反馈报告固定 1 行 header + 最近 199 行。原“复制日志”保留原文；新“复制反馈日志”才脱敏。映射仅单次报告有效，清空日志同时重置所有 gate、suppressedCount 与 sanitizer。
- 已验证 JDK：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home`，并将其 `bin` 放到 PATH 最前。2026-10-27 基线 `./gradlew test --rerun-tasks` 已真实执行通过 119 tests / 0 failures / 0 errors；本计划不要求恢复 JDK，后续不得把未运行测试写成通过。
- 所有命令在仓库根目录执行；每个功能任务先取得新测试在旧实现上的 RED，再实现并跑 GREEN。每次提交末尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`；不 push、不改版本、不发布。

## Review Focus

1. 最高 rate 条目缺 bits、current channel 为 0/多比特、或只有 channel 不匹配的高档能力：不得借另一条目补维度，也不得把不可写 current 送入旧回退（Task 1 Step 1/4）。
2. force 在同 MAC worker 仍活动时到达、disconnect 与 release 交错、旧 Permit 迟到释放：不得叠加 worker 或误释放新任务；force 只在完成后绕过窗口（Task 3 Step 1/4）。
3. BluetoothManager/权限读取异常与明确空连接列表不同：前者返回 UNKNOWN 并保留旧兼容路径，后者必须在锁后零载体调用结束（Task 4 Step 1/4）。
4. 变化只发生在被截断的第 17 条 selectable、current 为 null、或异常消息含设备名：完整原始状态仍须参与签名并留下跃迁，current/readStatus 优先保留且异常不逃逸脱敏（Task 5 Step 1/4、Task 7 Step 1/4）。
5. 两个设备同名、设备名含正则字符/JSON 转义/MAC、报告再次导出顺序改变：当次报告替换须完整稳定，下一次报告重新从 device1 编号且不跨报告跟踪（Task 7 Step 1/4）。

## File Structure

- Create `trigger/TargetAssessment.kt`：三态及 target/candidates 的唯一数据模型。
- Modify `trigger/ApplyPolicy.kt:46-52`、`codec/Negotiation.kt:13-14`：完整能力派生、同条目降档与旧 degraded 路径。
- Create `trigger/ApplyCandidateRunner.kt`：可 fake 的候选执行循环，隔离 write/origin 副作用；Modify `trigger/ApplyWorker.kt:141-196` 接入。
- Create `ui/home/HomeTargetStatus.kt`：首页状态/徽标文案纯函数；Modify `MainActivity.kt:1054-1069`、`ui/home/HomeScreen.kt:39-75,251-258,361-390`。
- Create `trigger/TriggerGate.kt`、`trigger/TriggerDispatcher.kt`、`trigger/ApplyTriggers.kt`：窗口、Permit、统一入口；Modify 三自动路径与 Main/QS force 路径。
- Create `trigger/ApplyConnectionGuard.kt`、`trigger/LocalBluetoothA2dpProbe.kt`：锁内 per-MAC 复查；Modify `ApplyWorker.kt:54-83`，restore 路径不动。
- Create `codec/VendorStackDiagnostics.kt`、`codec/VendorStackDiagnosticGate.kt`、`codec/VendorStackDiagnosticRecorder.kt`、`codec/VendorDiagnosticMetadata.kt`：通用原因、紧凑格式、有界去重和 Android 元数据适配；Modify ApplyWorker/MainActivity 既有读取点。
- Create `LogBuffer.kt` 与 `FeedbackLogSanitizer.kt`：线程安全 200 行缓冲、单次报告脱敏；Modify `LogStore.kt`、`MainActivity.kt:628-661`、`ui/logs/LogsScreen.kt:32-45,101-124`。
- Tests 与主代码按上述组件一一对应；行号是 v1.2.1 基线定位，计划 A 合并后按函数名重新定位，不覆盖计划 A 改动。

---

### Task 1: 同条目能力目标与完整降档链

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/TargetAssessment.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyPolicy.kt:46-52`
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/Negotiation.kt:13-14`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyPolicyTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/NegotiationTest.kt`

**Interfaces:**
- Consumes: 现有 `CodecConfig`、`ApplyPolicy.deriveTarget(current, selectable)`、`ApplyPolicy.matches(target, actual)` 与 `Negotiation.descending(base)`。
- Produces:
  - `TargetAssessment(status: TargetAssessment.Status, target: CodecConfig?, candidates: List<CodecConfig>)`；`Status = COMPLETE | CAPABILITY_DEGRADED | INCOMPLETE_CURRENT`。
  - `ApplyPolicy.assessTarget(current: CodecConfig, selectable: List<CodecConfig>): TargetAssessment`。
  - `ApplyPolicy.isAtHighest(assessment: TargetAssessment, current: CodecConfig): Boolean`。
  - `Negotiation.descending(base: CodecConfig, selectable: List<CodecConfig>): List<CodecConfig>`，为完整能力专用重载；原单参函数对所有合法 base 保持旧结果，非法 base 新增空链保护。

- [ ] **Step 1: 写失败测试，固定跨条目、整条降档链和不完整 current 的边界。**

在 `ApplyPolicyTest` 增加以下测试与断言，fixture 显式使用 rate `32/16/8/2/1`、bits `2/1`、channel `2`：

```kotlin
@Test fun completeTargetNeverCombinesDifferentCapabilityEntries() {
    // 条目 A=192+16，条目 B=96+24，二者 channel 均兼容 current。
    val result = ApplyPolicy.assessTarget(current, listOf(cap192Bits16, cap96Bits24))
    assertEquals(TargetAssessment.Status.COMPLETE, result.status)
    assertEquals(32, result.target?.sampleRate)
    assertEquals(1, result.target?.bitsPerSample)
    assertFalse(result.candidates.any { it.sampleRate == 32 && it.bitsPerSample == 2 })
    assertEquals(listOf(32), result.candidates.map { it.sampleRate })
}

@Test fun completeDescendingContainsOnlySameEntryReportedRates() {
    // 唯一条目报告 192/48 + 24bit；不得从 192 降出 176.4/96/88.2/44.1。
    assertEquals(listOf(32, 2), result.candidates.map { it.sampleRate })
    assertTrue(result.candidates.all { it.bitsPerSample == 2 })
}

@Test fun incompleteCurrentHasNoTargetOrCandidates() {
    listOf(0, 3, 43, 64, -1).forEach { rate ->
        val result = ApplyPolicy.assessTarget(current.copy(sampleRate = rate), selectable)
        assertEquals(TargetAssessment.Status.INCOMPLETE_CURRENT, result.status)
        assertEquals(null, result.target)
        assertTrue(result.candidates.isEmpty())
    }
    // bits=0/3/未知值、channel=0/3/4 同样不可写（AOSP channel 仅 1/2）。
}
```

另加：缺当前 codec、selectable 为空、全 0 能力均为 `CAPABILITY_DEGRADED`，其 candidates 与旧 `deriveTarget` + 单参 `descending` 完全一致且 `isAtHighest=false`；capsRateMask=10 与 11 都只派生 96kHz；channel 不匹配的高档条目不能覆盖较低档的同 channel 完整条目。`NegotiationTest` 保留全部旧合法 base 断言，新增单参/双参对 rate=0、多比特 3/43、未知 64、非法 bits/channel 均返回空链的用例，并验证双参重载拒绝 base 自身未被同条目证明的情况。

- [ ] **Step 2: 运行新测试，确认旧实现缺少新接口而 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.ApplyPolicyTest --tests com.chengzi.stayhifi.codec.NegotiationTest --rerun-tasks`

Expected：因 `TargetAssessment`/`assessTarget`/双参 `descending` 不存在而编译失败，或新断言失败；记录真实 RED 输出。

- [ ] **Step 3: 实现三态派生与同条目校验，不改旧公开路径。**

current 的 rate 必须属于 `{1,2,4,8,16,32}`、bits 属于 `{1,2,4}`、channel 属于 `{1,2}`（AOSP 仅定义 MONO=1/STEREO=2），否则返回 INCOMPLETE_CURRENT。COMPLETE 只从同 codecType 且 `(cap.channelMode and current.channelMode) != 0` 的条目构造组合：先选存在明确 bits 的最高 rate，再从支持该 rate 的条目选最高 bits；rate/bits 必须逐位明确，channel 不改写。target 的 cs1 仍走原 `BEST_CS1` 规则，cs2~cs4 继承 current。

双参 `descending` 对每个不高于 base 的标准 rate，要求至少一条原始条目同时包含该 rate、base bits、base channel 和 base codecType；base 自身也先校验。单参 `descending` 仅增加同样的合法 base 前置检查，合法 base 的候选顺序和字段完全不变，非法 base 返回空链。COMPLETE 返回该约束链。无可证明组合但 current 合法时返回 CAPABILITY_DEGRADED，target/candidates 严格走旧函数；`isAtHighest` 仅在 COMPLETE、target 非空且 `matches(target,current)` 时为 true。诊断聚合不在此函数中代替同条目证明。

- [ ] **Step 4: 运行定向测试与既有全量单测，确认新旧路径同时通过。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.ApplyPolicyTest --tests com.chengzi.stayhifi.codec.NegotiationTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：定向测试 PASS；全量测试 PASS，旧 `matchesIgnoresRateAndBitsWhenTargetMasksAreZero`、cs1 与单参 descending 用例预期未改。

- [ ] **Step 5: 提交 Task 1。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/trigger/TargetAssessment.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyPolicy.kt app/src/main/java/com/chengzi/stayhifi/codec/Negotiation.kt app/src/test/java/com/chengzi/stayhifi/trigger/ApplyPolicyTest.kt app/src/test/java/com/chengzi/stayhifi/codec/NegotiationTest.kt
git commit -m "Task B1: 约束能力目标与完整降档链" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: ApplyWorker 与首页共用目标完整性

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunner.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/ui/home/HomeTargetStatus.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:141-196`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:1054-1069,1150-1163`
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt:39-75,251-258,361-390`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunnerTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/ui/home/HomeTargetStatusTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `TargetAssessment`、`ApplyPolicy.assessTarget/isAtHighest`；计划 A 在首页和 ApplyWorker 提供的共享 snapshot 解析结果，不新增 JSON 解析。
- Produces:
  - `ApplyCandidateRunner.run(current: CodecConfig, assessment: TargetAssessment, beforeFirstWrite: () -> Unit, writeVerified: suspend (CodecConfig) -> Boolean): ApplyRunResult`。
  - `ApplyRunResult = AlreadyAligned(candidate) | Applied(candidate) | Exhausted | IncompleteCurrent`。
  - `HomeTargetStatus.statusLine(connected: Boolean, status: TargetAssessment.Status?, fullBars: Boolean): String` 与 `badgeLabel(status: TargetAssessment.Status?, fullBars: Boolean): String`。

- [ ] **Step 1: 写失败测试，证明不完整状态零副作用且 degraded 不冒充最高。**

`ApplyCandidateRunnerTest` 用 fake 计数器代替 write/origin：

```kotlin
@Test fun incompleteCurrentDoesNotWriteOrRecordOrigin() = runBlocking {
    val result = runner.run(current, incompleteAssessment,
        beforeFirstWrite = { originCalls++ }, writeVerified = { writeCalls++; true })
    assertEquals(ApplyRunResult.IncompleteCurrent, result)
    assertEquals(0, originCalls)
    assertEquals(0, writeCalls)
}

@Test fun degradedStillUsesLegacyCandidatesButIsNeverFullBars() = runBlocking {
    // first candidate 与 current 匹配时返回 AlreadyAligned，且不调用 origin/write；
    // 首页 fullBars 仍由 ApplyPolicy.isAtHighest 得 false。
}
```

另测首个实际写入前 `beforeFirstWrite` 仅调用一次、写失败继续下一个候选、成功立即停止。`HomeTargetStatusTest` 断言：INCOMPLETE_CURRENT=`参数不完整 · 暂无法判定`；CAPABILITY_DEGRADED=`能力未完整暴露 · 暂无法确认最高`；两种徽标均为`待确认`；COMPLETE 才可能出现`已达本机最高音质输出`/`满血`，未连接仍优先`等待耳机连接`。

- [ ] **Step 2: 运行两个新测试类，确认旧实现 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.ApplyCandidateRunnerTest --tests com.chengzi.stayhifi.ui.home.HomeTargetStatusTest --rerun-tasks`

Expected：新类/接口不存在而编译失败；记录真实 RED 输出。

- [ ] **Step 3: 实现纯候选执行器与首页文案函数。**

`ApplyCandidateRunner` 顺序消费 assessment.candidates：匹配 current 返回 AlreadyAligned；第一次真正写之前调用一次 beforeFirstWrite；writeVerified 成功返回 Applied；耗尽返回 Exhausted；INCOMPLETE_CURRENT 在任何回调前返回。它不直接操作 ProfileRepository、Notifier 或 VerifiedTargetStore。

按上述固定文案实现 `HomeTargetStatus`；fullBars 必须由调用方传入 Task 1 的统一判定，文案函数不得自行重写 deriveTarget 或把 degraded 匹配视为满格。

- [ ] **Step 4: 接入 ApplyWorker 与 MainActivity/HomeScreen。**

ApplyWorker 用 `assessTarget` 替代 deriveTarget + 单参 descending，beforeFirstWrite 只调用原 `recordOriginConfig(mac,current)`。IncompleteCurrent 仅记录原始快照/中性跳过日志，不写、不建档、不记 origin/VerifiedTargetStore、不发成功通知。AlreadyAligned/Applied 保留原建档、origin 和漂移基准语义；COMPLETE 保留原成功文案，DEGRADED 改为`当前目标已确认（能力未完整）`，不得出现“已达最高/满血”。

MainActivity 复用计划 A 已解析 snapshot 的 current/selectable，保存 `targetStatus` 与统一 fullBars；HomeScreen 用 `HomeTargetStatus` 渲染，不新增 binder 调用。旧 `matches()` 和 restoreDefault 不改。

- [ ] **Step 5: 运行定向测试和全量单测。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.ApplyCandidateRunnerTest --tests com.chengzi.stayhifi.ui.home.HomeTargetStatusTest --tests com.chengzi.stayhifi.trigger.ApplyPolicyTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：全部 PASS；既有默认策略、origin/restore 相关测试未通过改预期迁就这样实现。

- [ ] **Step 6: 提交 Task 2。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunner.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/main/java/com/chengzi/stayhifi/ui/home/HomeTargetStatus.kt app/src/main/java/com/chengzi/stayhifi/ui/home/HomeScreen.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/test/java/com/chengzi/stayhifi/trigger/ApplyCandidateRunnerTest.kt app/src/test/java/com/chengzi/stayhifi/ui/home/HomeTargetStatusTest.kt
git commit -m "Task B2: 接入目标完整性与中性状态" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: per-MAC 三路径连接触发合并

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/TriggerGate.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/TriggerDispatcher.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyTriggers.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ConnectionReceiver.kt:59-68`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt:65-85,182-269`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:1172-1187`
- Modify: `app/src/main/java/com/chengzi/stayhifi/notify/QsTileService.kt:29-53`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/TriggerGateTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/TriggerDispatcherTest.kt`

**Interfaces:**
- Consumes: 现有 `ApplyWorker.run(mac,name,force)` 与 `ApplyLock`；Task 4 再增加锁后复查，本任务不得提前声称断开后零 IO。
- Produces:
  - `TriggerGate(windowMillis: Long = 10_000L, nowMillis: () -> Long = { System.nanoTime() / 1_000_000L })`。
  - `TriggerGate.tryAcquire(mac: String, force: Boolean = false): TriggerGate.Permit?`、`onDisconnected(mac: String): Unit`、`release(permit: TriggerGate.Permit): Unit`。
  - `TriggerDispatcher.dispatch(mac: String, name: String?, force: Boolean, source: TriggerSource): Boolean`；`TriggerSource = CONNECTION_RECEIVER | LISTENER_SERVICE | STARTUP | MANUAL | QS`。
  - `ApplyTriggers` 的 `suspend fun run(context: Context, mac: String, name: String?, force: Boolean, source: TriggerSource): Boolean`，以及 `ApplyTriggers.onDisconnected(mac: String): Unit`；二者为全进程唯一 shared gate 入口。

- [ ] **Step 1: 用可控时钟和 fake runner 写窗口/释放失败测试。**

覆盖同 MAC 三个自动 source 在窗口内只调用 runner 一次、不同 MAC 互不影响、MAC 大小写归一、窗口边界 `[start,end)`、活动超过 10s 仍合并、完成后 10s 外放行。另写：

```kotlin
@Test fun forceBypassesCompletedWindowButNotActiveWorker() = runBlocking {
    // 第一次完成后 force=true 可立即运行；活动任务未结束时 force=true 仍返回 false，
    // runner 总次数不增加。
}

@Test fun exceptionAndTimeoutReleasePermitInFinally() = runBlocking {
    // fake runner 分别抛 IllegalStateException、在 withTimeout(1) 中 delay(10)；
    // 时钟前进 10s 后同 MAC 可重新获取，异常仍向调用方传播。
}
```

再测 disconnect 清除已完成窗口、活动期 disconnect 不强行并发、disconnect 后 release 不重新制造冷却；旧 Permit 重复/迟到 release 不得清除新 Permit。合并日志只断言含 source/MAC 和“已合并”，不得出现“已阻止错误写入”。

- [ ] **Step 2: 运行 TriggerGate/Dispatcher 测试，确认 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.TriggerGateTest --tests com.chengzi.stayhifi.trigger.TriggerDispatcherTest --rerun-tasks`

Expected：新接口不存在而编译失败；记录真实 RED 输出。

- [ ] **Step 3: 实现线程安全 Permit 闸与统一 dispatcher。**

Gate 内部按规范化 MAC 保存 active token、完成窗口和 disconnected 标记；Permit 带单调 token，release 仅接受当前 token。普通触发在活动期或完成窗口内返回 null；force 仅忽略完成窗口；所有获取/断开/释放同步。Dispatcher 在获取失败时返回 false，获取后 `try { runner.run(...) } finally { gate.release(permit) }`，成功/异常/协程超时均只释放一次。

`ApplyTriggers` 持有唯一 gate，并通过 fake 可替换的 `ApplyTriggerRunner` 调 ApplyWorker；不要在各 receiver 分别 new gate。

- [ ] **Step 4: 接入三自动路径、两个 force 路径和断开通知。**

ConnectionReceiver 保留 FGS 存活让位和 autoEnabled 检查后用 `CONNECTION_RECEIVER`；ListenerService ACL_CONNECTED 用 `LISTENER_SERVICE`，启动补触发用 `STARTUP`。Main 立即拉满与 QS 分别用 `MANUAL`/`QS` 且 force=true。ACL_DISCONNECTED 与 A2DP DISCONNECTED 在能安全读到 EXTRA_DEVICE 地址时调用 `ApplyTriggers.onDisconnected(mac)`；权限异常沿用现有日志，不伪造 MAC。

保持 5s attach 轮询、goAsync pending.finish、HeadsetEvents/通知刷新和原有异常处理；重复触发在启动 worker 前结束，不再进入“等待上一次拉满完成”队列。

- [ ] **Step 5: 运行定向测试、全量单测和 debug 编译。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.TriggerGateTest --tests com.chengzi.stayhifi.trigger.TriggerDispatcherTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test assembleDebug --rerun-tasks`

Expected：测试 PASS 且 assembleDebug 成功；五个入口编译后只经 shared ApplyTriggers，FGS 让位代码仍在。

- [ ] **Step 6: 提交 Task 3。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/trigger/TriggerGate.kt app/src/main/java/com/chengzi/stayhifi/trigger/TriggerDispatcher.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyTriggers.kt app/src/main/java/com/chengzi/stayhifi/trigger/ConnectionReceiver.kt app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/notify/QsTileService.kt app/src/test/java/com/chengzi/stayhifi/trigger/TriggerGateTest.kt app/src/test/java/com/chengzi/stayhifi/trigger/TriggerDispatcherTest.kt
git commit -m "Task B3: 合并 per-MAC 连接触发" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 4: ApplyLock 后 per-MAC A2DP 断连复查

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuard.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/LocalBluetoothA2dpProbe.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:54-83`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuardTest.kt`

**Interfaces:**
- Consumes: Task 3 `TriggerDispatcher`/`force`；现有 `ApplyLock.mutex`；Android 公开 `BluetoothManager.getConnectedDevices(BluetoothProfile.A2DP)`。
- Produces:
  - `LocalA2dpState = CONNECTED | DISCONNECTED | UNKNOWN`；`LocalA2dpProbe.state(mac: String): LocalA2dpState`。
  - `ApplyConnectionGuard(lock: Mutex, probe: LocalA2dpProbe, log: (String) -> Unit)` 的 `suspend fun run(mac: String, carrierBlock: suspend () -> Unit): Boolean`。
  - `LocalBluetoothA2dpProbe(context: Context): LocalA2dpProbe`，只做本机 API 地址匹配，SecurityException/其他读取异常返回 UNKNOWN。

- [ ] **Step 1: 写锁序、零载体调用与 UNKNOWN 失败测试。**

用真实 Mutex、runBlocking 和 fake probe/block：

```kotlin
@Test fun queuedDisconnectedWorkerChecksAfterLockAndDoesNoCarrierIo() = runBlocking {
    // 外层先持有同一 Mutex；启动 guard 后 yield，断言 probeCalls=0。
    // 解锁后 probe 返回 DISCONNECTED；result=false、carrierCalls=0、probeCalls=1，
    // 日志恰为一条“设备已断开，跳过排队任务”。
}

@Test fun forcedTriggerStillCannotBypassDisconnectedGuard() = runBlocking {
    // 经 Task 3 dispatcher force=true 进入 fake runner，runner 内仍调用 guard；
    // DISCONNECTED 时 carrierCalls=0。
}
```

另测 CONNECTED 执行一次 block；空地址列表为 DISCONNECTED；probe 抛 SecurityException/返回 UNKNOWN 时按旧路径执行并记录一次“本机 A2DP 状态不可用”；block 抛异常后 Mutex 释放，下一次可运行。`restoreDefault()` 也必须覆盖：排队后 DISCONNECTED 时零载体调用、不得写 origin/发成功通知，CONNECTED 时仍走原恢复与读回流程。

- [ ] **Step 2: 运行 guard 测试，确认 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.ApplyConnectionGuardTest --rerun-tasks`

Expected：新接口不存在而编译失败；记录真实 RED 输出。

- [ ] **Step 3: 实现本机 probe 与锁内 guard。**

`ApplyConnectionGuard.run` 必须先 `lock.withLock`，再读 probe；DISCONNECTED 只写一条结束日志并返回 false，CONNECTED/UNKNOWN 才调用 carrierBlock。UNKNOWN 是兼容回退，不得伪装成“已确认连接”。Android probe 用 BluetoothManager 的 A2DP connectedDevices 做忽略大小写 MAC 匹配，不创建 BluetoothA2dp proxy、不增加等待或轮询。

- [ ] **Step 4: 将 ApplyWorker 的 apply 与 restoreDefault 路径都移入 guard，保持恢复语义。**

`run()` 保留连接/等待日志，以 shared `ApplyLock.mutex` 和本机 probe 包裹 `runLocked()`；`waitForShell/hasPermission/bind/pollForDevice` 全部位于 guard 内。force 仍只传给 runLocked 的 autoApply 判断，不作为 guard 参数。`restoreDefault()` 同样先取得 ApplyLock 再做 per-MAC 断连复查，DISCONNECTED 时零载体调用、不得写 origin/发成功通知；CONNECTED 时继续使用原写入/读回、关闭 autoApply 和通知流程，不新增 TriggerGate 或改变恢复目标。

- [ ] **Step 5: 运行定向测试、触发回归、全量单测与 debug 编译。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.ApplyConnectionGuardTest --tests com.chengzi.stayhifi.trigger.TriggerDispatcherTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test assembleDebug --rerun-tasks`

Expected：全部 PASS、assembleDebug 成功；fake 证明断开时零载体调用，日志未声称旧版本实际发生过错误写入。

- [ ] **Step 6: 提交 Task 4。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuard.kt app/src/main/java/com/chengzi/stayhifi/trigger/LocalBluetoothA2dpProbe.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/test/java/com/chengzi/stayhifi/trigger/ApplyConnectionGuardTest.kt
git commit -m "Task B4: 锁后复查本机 A2DP 连接" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 5: 通用厂商栈只读诊断与状态跃迁留证

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/VendorStackDiagnostics.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticGate.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticRecorder.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnosticMetadata.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:86-139,336-390`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:628-661,1003-1168`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticsTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticGateTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticRecorderTest.kt`

**Interfaces:**
- Consumes: 计划 A 的 `CodecStateSnapshot` 全字段、`CodecIdentity`/identityDiagnostics/readStatus、共享 JSON 解析结果，以及 MainActivity/ApplyWorker 已经取得的同轮 dump；本任务不调用系统身份 resolver、不改 CodecService。
- Produces:
  - `DiagnosticTrigger = CONNECTION | CODEC_UPDATE | PLAYING | MANUAL`；`DiagnosticSource = HOME | APPLY | MANUAL`。
  - `VendorDiagnosticContext(mac, trigger, source, timestampMillis, providerKind, device: DiagnosticDeviceMetadata, dumpClues: List<String> = emptyList())`。
  - `VendorStackDiagnostics.reasons(snapshot: CodecStateSnapshot): Set<String>` 与 `format(snapshot, context, snapshotSeq: Long, suppressedCount: Int): VendorStackDiagnosticEvent`。
  - `VendorStackDiagnosticGate.record(signature: String, force: Boolean = false): Decision(shouldRecord: Boolean, suppressedCount: Int)`、`drainSuppressedCount(): Int`、`reset(): Unit`。
  - `VendorStackDiagnosticRecorder.record(snapshot, context): Int`、`recordFailure(context, stage: String, error: Throwable): Int`、`drainSuppressedCount(): Int`、`reset(): Unit`；返回本次写入 LogStore 的行数。
  - `VendorDiagnosticMetadata.current(): DiagnosticDeviceMetadata`，API 31+ 读取 `Build.SOC_MANUFACTURER/SOC_MODEL`，否则填 `unknown`。

- [ ] **Step 1: 写通用原因、跃迁、预算和 suppressedCount 失败测试。**

构造计划 A snapshot fixture，分别断言 `codecStatusNull`、currentNull、local 空、selectable 空、缺当前 codec、current rate/bits 0/多比特、supportedTypes API 不存在/空表/异常、extended 读取异常、身份冲突是可区分 reason；正常 SBC/AAC 不因标准身份被排除，正常快照无 reason 不记录。不得出现 aptX 类型判断分支。

Gate/recorder 测试必须含：

```kotlin
@Test fun emptyToNonEmptyAndNullToValueTransitionsProduceNewEvidence() {
    // 同 mac/reason 的 selectable=[] → [cap]、current=null → valid 均各记录一次；
    // 完全相同快照重复 N 次只首条留证，下一跃迁行含 suppressedCount=N。
}

@Test fun signatureUsesUntruncatedCapabilitiesAndOutputStaysBounded() {
    // 仅第 17 条 selectable 改变也产生新 signature；渲染仍最多 16 条。
    // 8 local/16 selectable/32 supportedTypes/24 dump clues 为上限；
    // 总字符<=12000，current 与 readStatus 完整存在，truncatedCount 准确。
}
```

另测 64 signature 后第 65 个触发 LRU 有界淘汰、reset 后同签名可再记且计数清零；manual 即使无异常也每次留快照；failure 的 root cause 解包最多 3 层并截断 240 字符。hex/逐 bit 测试断言 43 解码为 44.1/48/96/192、bits=3 解码为 16/24、未知位保留 `unknown`，cs1~cs4 同时保留十进制和 hex。

- [ ] **Step 2: 运行三个诊断测试类，确认 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticsTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticGateTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticRecorderTest --rerun-tasks`

Expected：新接口不存在而编译失败，或旧 VendorDiagnosticGate 不满足跃迁/预算断言；记录真实 RED 输出。

- [ ] **Step 3: 实现纯原因评估、紧凑格式和有界 recorder。**

signature 使用 `mac + current + 完整未截断 local/selectable 的 SHA-256 + readStatus + 排序后的 reason`，使空到非空、null 到有值及截断外变化都可留证。重复 signature 增加 pending suppressedCount；下一接受事件或导出 drain 时附总数；manual force 记录但不错误增加重复计数。64 项 LinkedHashMap LRU 仅限制内存，不宣称持久去重。

输出含 schemaVersion=1、appVersion、递增 snapshotSeq、trigger/source/timestamp、Build 元数据、providerKind/serviceUid、蓝牙/A2DP/readStatus、current/local/selectable 完整原 9 字段、hex/bit 解码、supportedTypes/extendedIdentity/读取状态、可用 system toString 和分阶段 root cause。按 Global Constraints 预算优先保留 current/readStatus，再装完整条目；不得把 null、空列表、异常合成同一文案。复用 dump clues 只有调用方本来就有 dump 时才传入。

- [ ] **Step 4: 在既有读取点接入，不新增任何载体 IO。**

ApplyWorker 在计划 A 统一解析后记录 CONNECTION/APPLY；解析 Throwable 走 recordFailure，正常 settled snapshot 进入通用 recorder。MainActivity 在同一轮 readConfig 后以 PLAYING/HOME 或 CODEC_UPDATE/HOME 记录，dumpClues 只复用本轮已取 dump；缓存最近一次 snapshot+mac+source 上下文供 MANUAL 复制使用，没有同轮数据时只记“无同轮快照”，不得为复制重新 bind/readConfig/dump。

通用 recorder 不再用 `VendorDiagnostics.shouldLog(codecType,capsMask)` 作为已知 codec 的排除条件；既有 dumpsys 证据/24 行线索和 ApplyWorker 专用 gate 可继续负责原实时诊断，避免重复 raw current 块。复制原始/反馈日志前都先 drain suppressedCount 为一行；清空日志调用 recorder.reset 并清缓存。元数据只读 Build/现有 DeviceNames，不收集序列号、IMEI、shell 环境或新 MAC 来源。

- [ ] **Step 5: 运行诊断测试、既有诊断回归和全量单测。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticsTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticGateTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticRecorderTest --tests com.chengzi.stayhifi.codec.VendorDiagnosticGateTest --tests com.chengzi.stayhifi.codec.VendorDiagnosticsTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：全部 PASS；空到非空跃迁可留证，重复轮询被抑制，旧 VendorDiagnostics 实时证据语义不变。

- [ ] **Step 6: 提交 Task 5。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/VendorStackDiagnostics.kt app/src/main/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticGate.kt app/src/main/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticRecorder.kt app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnosticMetadata.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/test/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticsTest.kt app/src/test/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticGateTest.kt app/src/test/java/com/chengzi/stayhifi/codec/VendorStackDiagnosticRecorderTest.kt
git commit -m "Task B5: 增加通用厂商栈只读诊断" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 6: LogStore 线程安全时间与 200 行预算

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/LogBuffer.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/LogStore.kt:12-34`
- Test: `app/src/test/java/com/chengzi/stayhifi/LogBufferTest.kt`

**Interfaces:**
- Consumes: 现有 `LogStore.log/snapshot/clear` 调用方；不改其公开签名。
- Produces: `LogBuffer(maxLines: Int = 200, nowMillis: () -> Long = { System.currentTimeMillis() }).append(msg: String): String`、`snapshot(): List<String>`、`clear(): Unit`；LogStore 继续负责 logcat echo。

- [ ] **Step 1: 写并发格式化与环形缓冲失败测试。**

用 8 线程、每线程 500 次 append、两个 CountDownLatch 同时起跑，时钟用 AtomicLong 单调递增；收集所有 Future 异常，断言无异常、最终恰好 200 行、每行匹配 `[HH:mm:ss]`、时间戳非递减、保留的是最后 200 个 append 且无重复/破损行。另测 clear 后为空、后续 append 可重用，以及单线程旧格式不变。测试只依赖纯 JVM LogBuffer，不调用 Android `Log.i`。

- [ ] **Step 2: 运行 LogBufferTest，确认 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.LogBufferTest --rerun-tasks`

Expected：LogBuffer 不存在而编译失败；记录真实 RED 输出。

- [ ] **Step 3: 实现同步区内的日期创建、格式化与 append。**

LogBuffer 使用同一 lock 包住 nowMillis、`SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(...))`、addLast 和移除旧行；snapshot/clear 也同步。不得把共享 SimpleDateFormat 留在锁外。LogStore.log 委托 buffer 后仍按原方式 echo 原始 msg 并返回完整行，公开行为不变。

- [ ] **Step 4: 运行并发测试和全量单测。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.LogBufferTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：LogBufferTest 每次运行均 PASS，无格式化异常/乱序；全量测试 PASS。

- [ ] **Step 5: 提交 Task 6。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/LogBuffer.kt app/src/main/java/com/chengzi/stayhifi/LogStore.kt app/src/test/java/com/chengzi/stayhifi/LogBufferTest.kt
git commit -m "Task B6: 修复日志时间格式化并发" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 7: 单次报告稳定脱敏的反馈日志

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/FeedbackLogSanitizer.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:628-661`
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/logs/LogsScreen.kt:32-45,101-124`
- Test: `app/src/test/java/com/chengzi/stayhifi/FeedbackLogSanitizerTest.kt`

**Interfaces:**
- Consumes: Task 5 `VendorStackDiagnosticRecorder.record(...MANUAL...)`/drain/reset、Task 6 LogStore 200 行 snapshot、现有 LogsAction/ClipboardManager。
- Produces（后续 Root 计划直接复用，不另建脱敏器）:
  - `class FeedbackLogSanitizer { @Synchronized fun sanitize(lines: List<String>): String; @Synchronized fun reset(): Unit }`。
  - `LogsAction.CopyFeedback`；MainActivity 新增私有 `copyFeedbackLogs()`，保留原 `copyLogs()` 原文导出。

- [ ] **Step 1: 写 MAC、设备名、异常内嵌副本和报告边界失败测试。**

至少覆盖：两个 MAC/两个自定义名在普通日志、通知文案和 `IllegalStateException: ...` 内嵌副本中都映射为对应 device1/device2；大小写 MAC 同 alias；同名设备 deterministic 替换；设备名含 `[](){}.*+?^$|\\`、引号、反斜杠、JSON 转义和 MAC 子串时无原文残留。另写：

```kotlin
@Test fun aliasesAreStableWithinReportAndResetBetweenReports() {
    val first = sanitizer.sanitize(linesWithAandB)
    assertTrue(Regex("device1").findAll(first).count() >= 1) // 同时做逐项精确断言
    assertFalse(first.contains(rawMacA)); assertFalse(first.contains(rawNameA))
    val second = sanitizer.sanitize(linesWithBbeforeA)
    // 新报告按首次出现重新编号，B 成为 device1；不沿用上一报告映射。
}

@Test fun feedbackUsesMostRecent199LinesAndKeepsSuppressedCount() {
    val output = sanitizer.sanitize((1..250).map { "line-$it" })
    assertEquals(199, output.lines().size)
    assertTrue(output.startsWith("line-52")); assertTrue(output.endsWith("line-250"))
    // 含 suppressedCount=37 的原始行仍保留该字段与数值。
}
```

清空/reset 后同 sanitizer 不保留中间映射；空输入返回空字符串。原复制按钮对应测试/代码路径不得调用 sanitizer。

- [ ] **Step 2: 运行 sanitizer 测试，确认 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.FeedbackLogSanitizerTest --rerun-tasks`

Expected：FeedbackLogSanitizer/CopyFeedback 不存在而编译失败；记录真实 RED 输出。

- [ ] **Step 3: 实现两阶段、报告内稳定的纯文本脱敏。**

每次 `sanitize` 开始即清空上一次映射，只处理最近 199 行。第一遍以忽略大小写的 Bluetooth MAC 正则按首次出现建立 LinkedHashMap，并从同一行 `名称 (MAC)`、既有连接/建档/恢复/QS 格式及 name/mac 字段发现名称与 alias 的关联；空名、`?` 和 MAC 本身不作为名称。第二遍先按长度从长到短替换名称的字面形式及 JSON 转义形式（Regex.escape，不解释用户正则），再替换全部 MAC，使异常消息和重复副本同样脱敏。重复设备名也必须确定性地映射，不能泄露原文。`reset()` 仅清内存状态，不写日志、不持久化。

- [ ] **Step 4: 接入新复制动作和清空重置，保留原日志入口。**

日志页同时显示`复制日志`、`复制反馈日志`与`清空`，小屏不得裁掉动作。`copyFeedbackLogs()` 先复用 Task 5 的同轮 snapshot 记录 MANUAL（无缓存则不读载体）并 drain suppressedCount，再取 `LogStore.snapshot()`；sanitizer 只接收日志行，输出前添加 1 行含 logSchemaVersion=1、appVersion、机型/Android/Build.DISPLAY 的 header，总行数最多 200。剪贴板 label 用`StayHiFi 反馈日志`，Toast 明确“已复制脱敏反馈日志”。原 `copyLogs()` 保持未脱敏内容与 label。

Clear 同时调用 LogStore.clear、feedbackLogSanitizer.reset、通用 recorder.reset/drain 清零、旧 VendorDiagnosticGate reset 和 btDiagLast/缓存清理；映射不写入 DataStore/文件，不跨报告跟踪。

- [ ] **Step 5: 运行 sanitizer、日志与全量测试，并编译 UI。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.FeedbackLogSanitizerTest --tests com.chengzi.stayhifi.LogBufferTest --tests com.chengzi.stayhifi.codec.VendorStackDiagnosticRecorderTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test assembleDebug --rerun-tasks`

Expected：全部 PASS、assembleDebug 成功；反馈报告最多 200 行，异常内嵌 MAC/名称无原文，原复制日志未脱敏。

- [ ] **Step 6: 提交 Task 7。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/FeedbackLogSanitizer.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/ui/logs/LogsScreen.kt app/src/test/java/com/chengzi/stayhifi/FeedbackLogSanitizerTest.kt
git commit -m "Task B7: 新增脱敏反馈日志复制" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **全量 JVM 测试（最终实际输出必须重新记录）。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：PASS；报告新的 tests/failures/errors 数量，不沿用 119/119 作为本分支结果。

- [ ] **Lint 与 debug 构建。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew lint assembleDebug --rerun-tasks`

Expected：两者 BUILD SUCCESSFUL；无新增权限、AIDL、网络、持久化或 exported 组件。

- [ ] **范围与差异审查。**

核对 7 个任务提交及整分支 diff：计划 A 的 identity/parser/RealtimeDisplay 未被重写，无 aptX/Root/机型名/手动档位实现；BEST_CS1、restore、原始复制日志、现有 dump 次数不变。审查日志措辞只能宣称“重复触发已合并/排队任务因当前断开而跳过”，不能反推旧日志已证明错误写入。

- [ ] **真机/外部验收（不得以 fake 结果替代）。**

iQOO 15 同秒多设备 ACL 风暴：每 MAC 每窗口最多一次 worker，无同 MAC 排队风暴；已断开排队任务零载体 IO。PHZ110 既有 192 写入/读回、cs1、restore 回归；NX caps=11 与 OPD caps=10 仍最高 96。MediaTek 只验收三阶段 raw 证据完整、null/空/异常可辨与跃迁留证，不宣称“已修复联发科”。复制原始日志仍含原文，反馈日志在异常内嵌副本中也只剩 deviceN；清空后同证据可重新采集。
