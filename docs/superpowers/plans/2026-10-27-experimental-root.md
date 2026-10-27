# 实验 Root 授权 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 Shizuku/Stellar 默认路径与 codec 写入语义的前提下，增加仅前台显式授权、可安全切换且可诊断的实验 Root 特权通道。

**Architecture:** `ProviderCoordinator` 异步恢复字符串选择，并以 owner+generation lease 统一所有载体访问；Shizuku 继续按 owner 绑定，Root 则由前台验证后建立唯一非 daemon anchor。`RootCodecService` 只复用现有 `CodecService` binder，Root 的授权、绑定、健康检查、IPC 串行化、代次失效与切换事务分别由可 fake 的小型组件实现。

**Tech Stack:** Android/Kotlin、协程 StateFlow/Mutex、DataStore Preferences、Jetpack Compose、libsu `service:6.0.0`（传递依赖 core）、现有 AIDL/Shizuku、JUnit4、kotlinx-coroutines-test、R8。

**Spec:** `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md` §11、§12、§13.1 Root/日志、§13.2、§14.2、§15-16；本计划仅实施计划 D。

## Global Constraints

- 基线为 master `d3adf81`；2026-10-27 已真实重跑 119 tests / 0 failures / 0 errors。所有后续结论以新输出为准，不沿用旧测试计数冒充新增测试结果。
- 所有 Gradle 命令使用：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH`；全量基线命令固定为上述前缀加 `./gradlew test --rerun-tasks`。
- 默认及缺省持久值均为字符串 `shizuku`，Root 为 `root`；未知值回退 `shizuku` 并记录，不存 enum ordinal。老用户不迁移，Stellar 仍共用 rikka 通道。
- `selectedProvider: StateFlow<ProviderKind?>` 首值为 null；首读前零特权 IPC。不得新增 Application/BootReceiver 主线程 `runBlocking { provider.first() }`，不得为 Root 预热 shell；既有非 Root 的启动逻辑不在本计划中顺手重写。
- Root 只能由教程/设置的显式点击触发；Boot/Listener/QS/ApplyWorker 及公共 ensure/2.5 秒轮询只探测或复用 READY lease，绝不创建 shell、弹授权或自动回退 Shizuku。
- Root anchor 是进程级、非 daemon transport；Activity 销毁不 unbind anchor，只有手动切换或显式断开才 unbind。后台只复用当前进程 READY lease；冷启动/断开后为 `NEEDS_FOREGROUND_REVALIDATION`。
- `ApplyLock` 只负责写入业务互斥；Root IPC mutex 只负责 Root transport/AIDL 串行。ApplyWorker 与 restoreDefault 都必须先拿 ApplyLock，再读取最新 provider。
- 切换为有界事务：`SWITCH_TIMEOUT_MS=20_000L`；阻止新 session，等待 ApplyLock 与活动 IPC，超时保留原选择；旧代次失效后再主线程幂等 unbind，写盘失败不发布成功。
- 授权 watchdog `30_000L`、bind watchdog `10_000L`、健康检查 watchdog `8_000L` 都只结束自己的等待，不宣称取消底层 shell/bind/binder；授权单飞，迟到结果/绑定必须按 attempt+generation 失效并清理。
- 只使用可观察错误分类；禁止把未知原因写成 `DENIED` 或 `NO_ROOT`。未获授权统一文案为“未获得 Root 权限（可能被拒绝，或设备没有可用 Root）”；授权成功但 bind/health 失败不得提示重新 Root。
- 依赖固定 `com.github.topjohnwu.libsu:service:6.0.0`，只在 `settings.gradle` 增加 JitPack；不另声明 core/nio/io，不使用动态版本、SNAPSHOT、master、`FLAG_MOUNT_MASTER`、daemon、`RootService.stop()` 或通用 shell。
- 只允许现有固定 AIDL 和 `CodecService` 已固定的 `/system/bin/dumpsys bluetooth_manager`；不新增命令桥、脚本、文件/getprop/setprop/mount/网络能力，不把 UI/档案/MAC/JSON/日志拼成 shell 命令。
- 不给 `RootCodecService` 添加普通 Android `<service>`、`android:process` 或 exported 声明；现有 `CodecService`、`rikka.shizuku.ShizukuProvider`、权限和 package queries 保持不变，不新增“Root 权限”或 Root 管理器查询。
- 依赖 libsu 6.0.0 consumer ProGuard rules；不做全量 `keep com.topjohnwu.superuser.**`，保留现有 AIDL/Shizuku 最小 keep。最终 minified release 的 RootService 启动必须外部实测。
- 日志仍是每进程 200 行内存环形缓冲、无上传/持久化。复用计划 B 的 `FeedbackLogSanitizer.sanitize(lines: List<String>): String` 与 `reset()`，以及其 LogStore 并发格式化修复；本计划不重复实现脱敏器或修改 codec 诊断接口。
- 本计划不实施 codec 身份/能力、TriggerGate、机型名、aptX、手动档位，不改变 `ICodecService`、9 字段写入、`ApplyPolicy`/`Negotiation`/`Masks`/`BtStateParser` 语义。`serviceUid` 只透传可观察值，缺失记 `unknown`，不硬编码 uid 0 为已验证权限。
- 无 Root 本地机只能形成 fake/JVM/build/minified 静态证据；Magisk/KernelSU 仅按外部具体“管理器版本+ROM+Android”样本限定宣称。
- 每个任务独立 RED→GREEN→回归→commit；所有 commit 末尾带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。不授权 push、发布或版本号修改。

## Review Focus

1. DataStore 首读很慢而 Listener/QS 同时冷启动：可以启动非特权 FGS/本机蓝牙检查，但不得先构造 Shizuku 或尝试 Root；首读后才按持久选择行动（Task 2 Step 1，Task 8 Step 1）。
2. 30 秒授权超时后底层 `getShell` 才返回，或 10 秒 bind 超时后连接才到：旧 attempt 不得提交/READY，不得并发第二次授权，迟到 connection 必须在主线程 unbind（Task 4 Step 1，Task 5 Step 1）。
3. 切换时旧写入/读取尚未结束或 DataStore 写盘失败：20 秒内有界等待，超时保留原代次；写盘失败不展示切换成功，旧回调不能写新 UI（Task 7 Step 1/4）。
4. 持久选择是 Root 但新进程尚未验证，或 anchor 意外断开：后台 `isRunning/hasPermission/bind` 均不得制造弹窗，必须可手动退回 Shizuku（Task 6 Step 1，Task 7 Step 1，Task 11 Step 1）。
5. `isAppGrantedRoot()` 三态与普通 `sh` fallback、空设备列表/error JSON/异常混合出现：READY 只能由 root shell+非空 binder+无 error 的 `dumpDevices()` 共同产生，空设备数组本身健康（Task 4 Step 1，Task 5 Step 1）。

## 文件结构与职责

| 文件 | 动作 | 单一职责 |
|---|---|---|
| `app/src/main/java/com/chengzi/stayhifi/shell/RootCodecService.kt` | Create | libsu RootService 薄 wrapper；lazy 复用唯一 `CodecService` binder，无蓝牙业务副本 |
| `shell/ProviderKind.kt` | Create | 字符串选择解析、owner/source、可观察 probe/access 类型 |
| `shell/ProviderCoordinator.kt` | Create | 异步首读、lease/generation、活动 IPC 计数、切换事务；不直接实现 Root 状态机 |
| `shell/RootTransport.kt` | Create | Root 状态机、单飞授权、bind/health、anchor 与迟到回调 |
| `shell/RootBackend.kt` | Create | 可 fake 的 backend/绑定句柄接口，不含任意 shell 命令 |
| `shell/LibsuRootBackend.kt` | Create | libsu 6.0.0 固定 API、专用 auth executor、主线程 bind/unbind 适配 |
| `shell/RootShellProvider.kt` | Create | 现有 `ShellProvider` 的 Root 适配；未 READY 时明确拒绝 bind |
| `shell/RootDiagnostics.kt` | Create | Root 状态/错误模型、分阶段日志、去重、suppressedCount 与反馈字段 |
| `trigger/ApplyProviderGate.kt` | Create | 把 ApplyLock→acquire lease 的固定顺序封装给 run/restoreDefault |
| `data/SettingsRepository.kt` | Modify | 只持久化原始 provider 字符串并实现 selection store |
| `StayHiFiApp.kt` | Modify | 提供 application-scope Coordinator；异步首读，不预热 shell |
| `MainActivity.kt`、`trigger/ApplyWorker.kt`、`trigger/ListenerService.kt`、`notify/QsTileService.kt` | Modify | 全部删除直接 provider 创建/绑定，改走 lease/probe |
| `trigger/BootReceiver.kt`、`notify/Notifier.kt` | Modify | 仅增加非交互后台来源/Root 修复 Intent，不新增主线程等待 |
| `ui/onboarding/OnboardingScreen.kt`、`ui/settings/SettingsScreen.kt` | Modify | 两组 provider 选择、Root 独立状态卡、当前 provider ready 门槛及手动退回 |
| `app/build.gradle`、`settings.gradle` | Modify | 固定 libsu 与 coroutines-test 依赖、JitPack |
| `shell/CodecService.kt` | Modify | 仅修正文档中的 uid 假设并记录可观察 `Process.myUid()`；AIDL/蓝牙逻辑不变 |
| `AndroidManifest.xml`、`app/proguard-rules.pro`、`ICodecService.aidl`、`LogStore.kt`、codec/trigger 策略文件 | Read-only | 分别保持组件/keep/AIDL/计划 B 日志修复及非 Root 行为不变 |

测试按组件放在同名 `app/src/test/java/com/chengzi/stayhifi/{shell,trigger,ui.onboarding}` 路径；fake RootBackend、fake ShellProvider、fake ICodecService 和 `StandardTestDispatcher` 均在测试内定义，不依赖真实 su。

---

### Task 1: 固定 libsu 工程边界与 RootCodecService

**Files:**
- Modify: `settings.gradle:8-14`、`app/build.gradle:79-98`
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/RootCodecService.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt:27-35,448-469`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootServiceContractTest.kt`
- Read-only: `app/src/main/AndroidManifest.xml:87-102`、`app/proguard-rules.pro:1-14`

**Interfaces:**
- Consumes: 现有 `CodecService : ICodecService.Stub`；`ICodecService` 五方法保持不变。
- Produces: `RootCodecService : RootService`，`onBind(intent: Intent): IBinder` 返回 lazy 单例 `CodecService` binder；后续只能通过显式 `Intent(context, RootCodecService::class.java)` 绑定。

- [ ] **Step 1: 写失败契约测试。** 断言 `RootService` 是 `RootCodecService`  superclass，并断言 `ICodecService` 方法名集合仍严格等于 `dumpDevices/readConfig/writeConfig/dumpBtState/destroy`；旧代码因 RootCodecService 不存在而编译失败。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootServiceContractTest --rerun-tasks`

Expected：FAIL（unresolved `RootCodecService`），记录真实输出。
- [ ] **Step 3: 最小实现工程与 wrapper。** 在 dependencyResolutionManagement 加 `maven { url 'https://jitpack.io' }`；只加 `implementation 'com.github.topjohnwu.libsu:service:6.0.0'` 和 `testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2'`。wrapper 只 lazy 持有一个 `CodecService` 并返回它；不修改 Manifest/ProGuard。CodecService KDoc 改为同时支持 Shizuku shell/Root 可诊断身份，并在服务侧首条 read/write 诊断记录实际 `Process.myUid()`，不假设 uid 0 拥有 shell 权限。
- [ ] **Step 4: 运行 GREEN 与依赖/组件静态检查。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootServiceContractTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:dependencies --configuration debugRuntimeClasspath`

Expected：测试 PASS；依赖树只有 service:6.0.0 传递带入 core:6.0.0，无 nio/io；Manifest/ProGuard diff 为空。
- [ ] **Step 5: 提交。**

```bash
git add settings.gradle app/build.gradle app/src/main/java/com/chengzi/stayhifi/shell/RootCodecService.kt app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt app/src/test/java/com/chengzi/stayhifi/shell/RootServiceContractTest.kt
git commit -m "Task D1: 添加固定 libsu RootService 薄通道" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: 字符串选择与 Coordinator 异步首读

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/ProviderKind.kt`、`shell/ProviderCoordinator.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/data/SettingsRepository.kt:26-68`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/ProviderKindTest.kt`、`shell/ProviderCoordinatorTest.kt`

**Interfaces:**
- Consumes: DataStore Preferences、fake `ShellProvider`。
- Produces: `ProviderKind.SHIZUKU("shizuku")/ROOT("root")`、`ProviderKind.fromStored(raw: String?): ProviderKind`；`ProviderSelectionStore(rawSelection: Flow<String?>, setRawSelection(value: String))`；`ProviderCoordinator.selectedProvider: StateFlow<ProviderKind?>`、`activeProvider: StateFlow<ProviderKind?>`、`suspend awaitSelection(): ProviderKind`、`suspend probeSelected(source: ProviderSource): ProviderProbe`。

- [ ] **Step 1: 写失败测试。** 覆盖 null/缺失→SHIZUKU、精确 `root`→ROOT、未知/大小写不符→SHIZUKU；用 `MutableSharedFlow` 暂停首读，断言 selected/active 初值均 null 且 fake factory 的 `isRunning/hasPermission/bind` 调用均为 0，发射首值后 `awaitSelection()` 才返回。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.Provider*Test' --rerun-tasks`

Expected：FAIL（类型/函数不存在）。
- [ ] **Step 3: 实现只读选择层。** SettingsRepository 增加私有 `stringPreferencesKey("provider")` 并实现 ProviderSelectionStore；Coordinator 在注入的 application scope 异步 collect，未知值只记一条回退日志。probe 必须等待首读；Root probe 只读状态源，Shizuku probe 由 factory 提供，业务层拿不到 factory。
- [ ] **Step 4: 运行 GREEN 与全量回归。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：新增测试及既有 119 项全部 PASS，无首读前 factory/IPC 调用。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/ProviderKind.kt app/src/main/java/com/chengzi/stayhifi/shell/ProviderCoordinator.kt app/src/main/java/com/chengzi/stayhifi/data/SettingsRepository.kt app/src/test/java/com/chengzi/stayhifi/shell/ProviderKindTest.kt app/src/test/java/com/chengzi/stayhifi/shell/ProviderCoordinatorTest.kt
git commit -m "Task D2: 异步恢复持久特权通道选择" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: Root 可观察状态、错误与分阶段日志

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/RootDiagnostics.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootDiagnosticsTest.kt`

**Interfaces:**
- Consumes: `LogStore.log`（默认 sink，测试注入内存 sink）；不消费/复制 FeedbackLogSanitizer。
- Produces: `RootStatus`（`UNVERIFIED/REQUESTING/GRANTED_NOT_READY/NOT_GRANTED/SHELL_FAILURE/AUTH_TIMEOUT/BINDING/READY/BIND_NULL/BIND_TIMEOUT/BIND_FAILED/DISCONNECTED/NEEDS_FOREGROUND_REVALIDATION`）；`RootShellStatus.UNKNOWN/NON_ROOT_SHELL/ROOT_SHELL`；`RootSnapshot`、`RootErrorClass`、`RootLogEvent`、`RootDiagnosticLogger.log(event)/flushSuppressed()/reset()`。

- [ ] **Step 1: 写失败测试。** 验证三态 granted、shellStatus、observedReason 与 reasonHint 分字段；同一 `provider+event+outcome+generation+errorClass` 重发只增加 suppressedCount，状态变化/flush 才输出；每次 attempt 仅 `root_attempt_start/root_auth_end/root_bind_end/root_health_end` 四条核心日志，切换仅一条 `provider_switch_end`；异常最多 3 层、240 字符。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootDiagnosticsTest --rerun-tasks`

Expected：FAIL（Root 状态/日志类型不存在）。
- [ ] **Step 3: 实现事实模型与 gate。** RootLogEvent 包含 spec §11.6 的 provider/event/source/attemptId/generation/selected/active/granted/shell/outcome/reason/hint/duration/timeout/callback/health/serviceUid/suppressed/rootCause 字段；后台未就绪也按状态跃迁去重。reset 同时清 gate 和计数，不操作持久存储，不采集 su stdout/stderr、管理器日志或环境变量。
- [ ] **Step 4: 运行 GREEN。** 另以注入的 200 行内存 sink 连续写入 250 个不同事件，确认保留最近 200 且重复事件由 suppressedCount 汇总；真实 LogStore 的线程安全与环形测试由计划 B 所有。

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootDiagnosticsTest --rerun-tasks`

Expected：PASS；日志无 `DENIED/NO_ROOT` 猜测分类。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/RootDiagnostics.kt app/src/test/java/com/chengzi/stayhifi/shell/RootDiagnosticsTest.kt
git commit -m "Task D3: 增加 Root 可观察状态与去重日志" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 4: 前台 Root 授权单飞与 30 秒 watchdog

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/RootBackend.kt`、`shell/RootTransport.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootTransportAuthTest.kt`

**Interfaces:**
- Consumes: Task 3 `RootSnapshot/RootDiagnosticLogger`；`StandardTestDispatcher`。
- Produces: `RootBackend.grantedState(): Boolean?`、`RootBackend.openShell(): RootShellObservation`；`RootTransport.state: StateFlow<RootSnapshot>`、`beginForegroundAuthorization(owner: ProviderOwner, source: ProviderSource): RootAttempt`、`suspend awaitCandidate(attempt: RootAttempt): RootCandidate`、`invalidateAttempt(attemptId: Long)`。

- [ ] **Step 1: 写失败 fake 测试。** 覆盖冷启动 UNVERIFIED；持久 Root 不自动 openShell；`grantedState=false` 才可映射 NOT_GRANTED，`grantedState=null` 必须保持 UNVERIFIED/REQUESTING 的未知语义，不得直接当作未授权；只有显式 begin 才允许继续 openShell。openShell 异常→SHELL_FAILURE；`isRoot=false`→NON_ROOT_SHELL 且永不 READY；虚拟时间 30 秒→AUTH_TIMEOUT 后底层仍 pending，再次点击不二次 openShell；离页/改选使迟到成功失效且不能成为 candidate。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootTransportAuthTest --rerun-tasks`

Expected：FAIL（backend/transport 不存在）。
- [ ] **Step 3: 实现授权阶段。** 注入 application scope、专用 auth dispatcher 与 clock/scheduler；`openShell` 只在显式 begin 后单飞执行。`grantedState()` 的 true/false/null 三态原样保留：false 可进入 NOT_GRANTED，null 不得据此定因，也不允许后台借此自动 openShell。UI 等待用 30 秒 watchdog，物理任务由 supervisor 持有且不被冒充取消；每次状态变化检查 attemptId，只有 root shell 才进入 GRANTED_NOT_READY 并构造未提交 candidate，activeProvider 不变。
- [ ] **Step 4: 运行 GREEN。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootTransportAuthTest --rerun-tasks`

Expected：全部 PASS，fake 证明 timeout/取消均未产生第二次 shell 或持久提交。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/RootBackend.kt app/src/main/java/com/chengzi/stayhifi/shell/RootTransport.kt app/src/test/java/com/chengzi/stayhifi/shell/RootTransportAuthTest.kt
git commit -m "Task D4: 实现前台 Root 授权单飞状态机" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 5: libsu bind、健康检查与 READY anchor

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/LibsuRootBackend.kt`、`shell/RootShellProvider.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/RootBackend.kt`、`shell/RootTransport.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootTransportBindTest.kt`、`shell/RootHealthCheckerTest.kt`

**Interfaces:**
- Consumes: Task 1 RootCodecService、Task 4 RootCandidate；fake ICodecService。
- Produces: `RootBackend.bind(callbacks: RootBindCallbacks): RootBinding`、`RootBackend.unbind(binding: RootBinding)`；`RootHealthChecker.checkDumpDevices(json: String): RootHealthResult`；`RootShellProvider : ShellProvider`；`suspend RootTransport.bindAndCheck(candidate): RootCandidate`、`suspend releaseCandidate(candidate)`、`suspend disconnectAnchor()`。

- [ ] **Step 1: 写失败 bind/health 测试。** 覆盖 null binder→BIND_NULL、异常→BIND_FAILED、10 秒→BIND_TIMEOUT、主动解绑与远端断开分别记录；timeout 后迟到 onServiceConnected 不 READY 且在 Main unbind，重复 disconnect/unbind 只执行一次。健康测试断言 `[]`/合法设备数组成功，`{error}`、畸形 JSON、binder 异常均不 READY；binder 非空但 health 失败停留 GRANTED_NOT_READY。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.Root*Test' --rerun-tasks`

Expected：新 bind/health 测试 FAIL，既有 Root 测试不回归。
- [ ] **Step 3: 实现真实 backend 与 anchor。** 使用显式 Intent 和 `RootService.bind/unbind`，两者经注入 Main dispatcher 调用；AIDL/health 在 IO，主线程不等待 latch、不跨 bind 持 synchronized monitor。8 秒 health watchdog只标记等待结束，实际 IPC 归还后才完成清理。RootShellProvider 的 `isRunning/hasPermission` 仅在当前进程 READY 时为 true，未 READY 的 bind 抛 `NEEDS_FOREGROUND_REVALIDATION`；不调用 daemon/stop。
- [ ] **Step 4: 运行 GREEN。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.Root*Test' --rerun-tasks`

Expected：PASS；ready 必须同时经过 ROOT_SHELL、非空 binder、dumpDevices 无 error，迟到连接清理次数恰为 1。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/LibsuRootBackend.kt app/src/main/java/com/chengzi/stayhifi/shell/RootShellProvider.kt app/src/main/java/com/chengzi/stayhifi/shell/RootBackend.kt app/src/main/java/com/chengzi/stayhifi/shell/RootTransport.kt app/src/test/java/com/chengzi/stayhifi/shell/RootTransportBindTest.kt app/src/test/java/com/chengzi/stayhifi/shell/RootHealthCheckerTest.kt
git commit -m "Task D5: 建立带健康检查的 Root 前台 anchor" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 6: owner+generation lease、Root IPC mutex 与后台非交互

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/ProviderKind.kt`、`shell/ProviderCoordinator.kt`、`shell/RootTransport.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/ProviderLeaseTest.kt`

**Interfaces:**
- Consumes: Task 2 Coordinator、Task 5 RootShellProvider/anchor。
- Produces: `ProviderOwner(id: String)`；`ProviderLease.owner/kind/generation`、`ProviderLease.isCurrent(): Boolean`、`suspend fun <T> ProviderLease.withService(block: suspend (ICodecService) -> T): T`、`close()`；`suspend ProviderCoordinator.acquire(owner, source): ProviderLease`、`noteBackground(source)`；`ProviderAccessException(reason)`。

- [ ] **Step 1: 写失败并发/冷启动测试。** 两个 Root lease 各执行 20 次带暂停 fake IPC，断言最大并发为 1；Shizuku 仍每 owner 一个 provider 并各自 close/unbind。关闭 Activity owner lease 后 Root anchor 未 unbind；新进程持久 Root 的 acquire/isRunning/hasPermission/bind 均零 openShell/zero bind 并返回 NEEDS_FOREGROUND_REVALIDATION；READY 后后台可复用，DISCONNECTED 后不可复用。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.ProviderLeaseTest --rerun-tasks`

Expected：FAIL（lease/acquire 尚不存在）。
- [ ] **Step 3: 实现 lease。** Coordinator 首读后、非 SWITCHING 才签发带当前 generation 的 lease；Root withService 在独立 Mutex 内检查 generation/READY 并统计活动 IPC，Shizuku withService 在注入 IO dispatcher lazy bind。close 只释放 owner 资源/计数；Root anchor 不属于任何 Activity lease。后台未就绪经 RootDiagnosticLogger 状态去重，绝不自动切换。
- [ ] **Step 4: 运行 GREEN 与全量回归。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks`

Expected：全部 PASS；fake 证明 Root IPC 串行且后台无交互调用。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/ProviderKind.kt app/src/main/java/com/chengzi/stayhifi/shell/ProviderCoordinator.kt app/src/main/java/com/chengzi/stayhifi/shell/RootTransport.kt app/src/test/java/com/chengzi/stayhifi/shell/ProviderLeaseTest.kt
git commit -m "Task D6: 统一带代次的特权 lease 与 Root IPC 串行" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 7: 有界切换事务与 ApplyLock 后取 provider

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/ProviderCoordinator.kt`、`shell/RootTransport.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyProviderGate.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/StayHiFiApp.kt:22-53`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/ProviderSwitchTest.kt`、`trigger/ApplyProviderGateTest.kt`

**Interfaces:**
- Consumes: Task 4-6 candidate/lease、现有 `ApplyLock.mutex`、ProviderSelectionStore。
- Produces: `ProviderCoordinator.switching: StateFlow<Boolean>`、`suspend verifyRootFromForeground(owner, source): RootVerificationResult`、`suspend switchToShizuku(owner, source): ProviderSwitchResult`、`cancelForegroundAttempt(owner)`；`suspend fun <T> ApplyProviderGate.withLease(coordinator, owner, source, block: suspend (ProviderLease) -> T): T`。

- [ ] **Step 1: 写失败事务测试。** 用 controllable dispatcher 分别挂起 ApplyLock/活动 IPC/DataStore：20 秒超时保留原 kind+generation；SWITCHING 拒绝新 session；成功路径先作废旧代次、Main 幂等 unbind 旧 Root、写盘成功后才发布新 generation。写盘失败不显示成功且不采用 candidate；旧 lease 后续 withService 得 STALE_GENERATION。ApplyProviderGateTest 记录调用顺序必须为 lock→awaitSelection/acquire→IPC→close→unlock，并证明排队期间选择改变后取的是最新 provider。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.ProviderSwitchTest' --tests 'com.chengzi.stayhifi.trigger.ApplyProviderGateTest' --rerun-tasks`

Expected：FAIL（切换/gate 接口不存在）。
- [ ] **Step 3: 实现提交与回退。** Root candidate 只有在 auth+bind+health 全部成功后进入 commit；commit 先置 SWITCHING，再有界等待 ApplyLock 和 activeIpc=0。失败/取消只清 candidate；手动 Shizuku 不检查 Root 可用性。DataStore 失败保留原选择并发布失败终态，若旧 Root 已按事务解绑则明确 NEEDS_FOREGROUND_REVALIDATION。Application 仅创建 Coordinator 和异步首读，不新增 runBlocking/shell 预热。
- [ ] **Step 4: 运行 GREEN。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.ProviderSwitchTest' --tests 'com.chengzi.stayhifi.trigger.ApplyProviderGateTest' --rerun-tasks`

Expected：全部 PASS；锁顺序、写盘失败、旧代次、迟到 candidate 和手动退回均有断言。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/ProviderCoordinator.kt app/src/main/java/com/chengzi/stayhifi/shell/RootTransport.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyProviderGate.kt app/src/main/java/com/chengzi/stayhifi/StayHiFiApp.kt app/src/test/java/com/chengzi/stayhifi/shell/ProviderSwitchTest.kt app/src/test/java/com/chengzi/stayhifi/trigger/ApplyProviderGateTest.kt
git commit -m "Task D7: 实现有界 provider 切换与写入锁顺序" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 8: 迁移 ApplyWorker、Listener、QS 与 Boot 后台路径

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:43-153,277-343`、`trigger/ListenerService.kt:53-97,168-172,232-269,329-362`、`notify/QsTileService.kt:23-58`、`trigger/BootReceiver.kt:17-25`
- Test: `app/src/test/java/com/chengzi/stayhifi/trigger/BackgroundProviderContractTest.kt`

**Interfaces:**
- Consumes: Task 6 `acquire/withService`、Task 7 ApplyProviderGate。
- Produces: 后台类不再构造/持有 `ShizukuShellProvider` 或 `RootShellProvider`；ApplyWorker 的 read/write/dump helper 改接收 ProviderLease 而不改变 codec 数据类型与返回语义。

- [ ] **Step 1: 写失败调用契约测试。** 以 Task 7 的 ApplyProviderGate fake 验证 run/restoreDefault 共用入口锁后才 acquire、lease finally close；再对 BOOT/LISTENER/QS/APPLY 四种 source 调用 Coordinator.noteBackground/acquire，模拟 Listener 启动补触发、30 秒通知、QS、Apply 和 Boot：冷 Root 均零 openShell/zero RootService.bind/zero Shizuku fallback，只产生一次去重 NEEDS_FOREGROUND_REVALIDATION。既有本机 A2DP 预检不等待 provider 首读。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.trigger.BackgroundProviderContractTest --rerun-tasks`

Expected：FAIL（直接 provider/旧 helper 不满足契约）。
- [ ] **Step 3: 做机械迁移。** 删除四处直接创建与 unbind；每次操作 acquire lease 并以 `withService` 包住单个 dump/read/write/dumpBtState。ApplyWorker run/restoreDefault 改走 ApplyProviderGate，既有 settle/retry/profile/notification/TriggerGate 预留逻辑不改；Listener onDestroy 不再关闭 Root anchor；QS 未 READY 只打开 App（Task 11 再定位 Root 区）；Boot 仅异步 noteBackground，不新增主线程 runBlocking。
- [ ] **Step 4: 运行 GREEN、静态搜索与回归。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks && rg -n 'ShizukuShellProvider\s*\(|RootShellProvider\s*\(' app/src/main/java/com/chengzi/stayhifi/trigger app/src/main/java/com/chengzi/stayhifi/notify`

Expected：测试 PASS；rg 无输出，证明后台业务不再直接创建 provider。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt app/src/main/java/com/chengzi/stayhifi/notify/QsTileService.kt app/src/main/java/com/chengzi/stayhifi/trigger/BootReceiver.kt app/src/test/java/com/chengzi/stayhifi/trigger/BackgroundProviderContractTest.kt
git commit -m "Task D8: 迁移全部后台特权调用到 Coordinator" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 9: 迁移 MainActivity 全部 IPC 与非交互 ensure

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:108-216,711-782,836-898,934-1220`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/ForegroundProviderProbeTest.kt`

**Interfaces:**
- Consumes: Coordinator `probeSelected/acquire`、ProviderLease。
- Produces: `ensureCarrierReady()` 只返回当前选择是否可 bind；不再有 Activity 级 ShizukuShellProvider 字段，onDestroy 不解绑 Root anchor。

- [ ] **Step 1: 写失败前台 probe 测试。** Root 已选但未 READY 时，连续调用 probeSelected（ensure/首页/耳机刷新/录制向导的共同入口）只读 RootSnapshot，不调用 requestPermission/openShell；Shizuku 的显式授权按钮仍走原 provider。旧 generation 返回时不能覆盖新 UI（沿用 refreshGeneration 并增加 lease.generation 检查）。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.ForegroundProviderProbeTest --rerun-tasks`

Expected：FAIL（公共路径仍会自动 requestPermission）。
- [ ] **Step 3: 迁移 Activity。** refreshCarriers、onRefreshConnection、onWizardCapture、refreshHome、实时 dump 均走 Coordinator probe/lease；公共 ensure 对 Root 永不 requestPermission，录制向导也不借通用路径触发 Root。首页 carrierAlert 按当前 provider 显示事实错误；onDestroy 只取消 UI 工作，Root anchor 归 Application。
- [ ] **Step 4: 运行 GREEN 与直接创建搜索。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks && rg -n 'ShizukuShellProvider\s*\(|RootShellProvider\s*\(|provider\.(bind|unbind)\(' app/src/main/java/com/chengzi/stayhifi/MainActivity.kt`

Expected：测试 PASS；rg 无输出；首页 3 秒 dump 次数和既有 8 秒读取超时不变。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/test/java/com/chengzi/stayhifi/shell/ForegroundProviderProbeTest.kt
git commit -m "Task D9: 迁移前台 IPC 并禁止隐式 Root 授权" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 10: Onboarding 双选择、Root 状态卡与当前 provider ready 门槛

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/onboarding/OnboardingScreen.kt:49-174,218-321`、`MainActivity.kt:663-751,809-821`
- Test: `app/src/test/java/com/chengzi/stayhifi/ui/onboarding/OnboardingProviderGateTest.kt`

**Interfaces:**
- Consumes: Task 7 `verifyRootFromForeground/switchToShizuku/cancelForegroundAttempt`、Task 9 probe。
- Produces: `OnboardingUiState.providerChoice: ProviderKind = SHIZUKU`、`root: RootSnapshot`、`providerOperationInFlight: Boolean`；`OnboardingAction.SelectProvider/RequestRootAuthorization`；`carrierPageReady(state): Boolean`。

- [ ] **Step 1: 写失败门槛测试。** SHIZUKU 选择时沿用 `shizuku.ready || stellar.ready`；ROOT 选择时只有 selected=active=ROOT 且 RootSnapshot=READY 才放行，Shizuku ready 不得放行，candidate 健康但尚未 commit 也不放行。仅选择/查看 Root 不写 DataStore；2.5 秒 RefreshConnection 重复 100 次仍零 openShell。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.ui.onboarding.OnboardingProviderGateTest --rerun-tasks`

Expected：FAIL（新状态/门槛不存在）。
- [ ] **Step 3: 实现教程。** 第 2 页顶部提供“Shizuku / Stellar（默认）”和“Root（实验）”，选择前者显示原两卡，选择后者显示独立 Root 可用性/授权/服务连接/最近错误卡，不按谁先 ready 自动改选。Root 卡使用 spec 原文：“适用于已经 Root 的设备。仅提供系统权限通道，不会提高耳机或编码器能力上限。兼容性仍在收集反馈，可随时手动切回 Shizuku/Stellar。”只有“请求 Root 授权”调用前台验证，离页/改选使 attempt 失效；成功提交后下一步只看当前选择。
- [ ] **Step 4: 运行 GREEN 与 Compose 编译。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.ui.onboarding.OnboardingProviderGateTest --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:compileDebugKotlin`

Expected：测试与编译 PASS；默认预览仍为 Shizuku/Stellar，Root 卡无自动持久化。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/ui/onboarding/OnboardingScreen.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/test/java/com/chengzi/stayhifi/ui/onboarding/OnboardingProviderGateTest.kt
git commit -m "Task D10: 在首次教程加入实验 Root 授权" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 11: 设置手动退回与 Root 修复入口

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/settings/SettingsScreen.kt:36-109,221-230`、`MainActivity.kt:331-342,409-460,934-938,1190-1202`、`notify/Notifier.kt:38-55`、`notify/QsTileService.kt:29-77`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootRepairFlowTest.kt`

**Interfaces:**
- Consumes: Coordinator RootSnapshot/switch/verify、Task 10 provider UI 类型。
- Produces: `SettingsUiState.selectedProvider/activeProvider/root/providerSwitching/rootSectionExpanded`；`SettingsAction.SelectProvider/RequestRootAuthorization`；`MainActivity.EXTRA_OPEN_ROOT_SETTINGS = "com.chengzi.stayhifi.extra.OPEN_ROOT_SETTINGS"`。

- [ ] **Step 1: 写失败修复流测试。** Root 处于 NOT_GRANTED/BIND_TIMEOUT/DISCONNECTED/NEEDS_FOREGROUND_REVALIDATION 时，“改用 Shizuku/Stellar”都可提交且不要求 openShell；切换期间重复点击只产生一个事务。另断言 EXTRA_OPEN_ROOT_SETTINGS 的精确字符串与 Root repair destination；Android Intent 的三处接线由 Step 4 编译和静态搜索验收，Shizuku 原管理器指引不变。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootRepairFlowTest --rerun-tasks`

Expected：FAIL（设置动作/Root repair Intent 不存在）。
- [ ] **Step 3: 实现设置与入口。** 设置增加同一“特权方式”区和 Root 独立状态/最近错误，Root 卡明确实验与能力上限边界；授权按钮仅显式动作可用，重复操作在 switching/requesting 时禁用。已持久 Root 撤权后保留选择并显示修复，不静默回退。MainActivity 在 onCreate/onNewIntent 消费 extra，打开设置 tab 并展开 Root 区；Root 红条不再跳管理器。
- [ ] **Step 4: 运行 GREEN 与全量回归。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks && rg -n 'EXTRA_OPEN_ROOT_SETTINGS' app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/notify/QsTileService.kt app/src/main/java/com/chengzi/stayhifi/notify/Notifier.kt`

Expected：全部 PASS；三处 Root repair Intent 接线可见，Root 不可用仍能退回，Shizuku/Stellar 旧入口回归测试不变。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/ui/settings/SettingsScreen.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/main/java/com/chengzi/stayhifi/notify/Notifier.kt app/src/main/java/com/chengzi/stayhifi/notify/QsTileService.kt app/src/test/java/com/chengzi/stayhifi/shell/RootRepairFlowTest.kt
git commit -m "Task D11: 增加设置 Root 修复与手动退回" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 12: Root 反馈导出集成与构建验收

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/RootDiagnostics.kt`、`MainActivity.kt:628-661`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootFeedbackHeaderTest.kt`
- Consumes/read-only: 计划 B 的 `FeedbackLogSanitizer`、`LogStore.kt:12-34` 及其“复制反馈日志”UI，不重复创建这些组件。

**Interfaces:**
- Consumes: `FeedbackLogSanitizer.sanitize(lines: List<String>): String`、`FeedbackLogSanitizer.reset()`；Coordinator 当前 selected/active/RootSnapshot。
- Produces: `RootDiagnosticLogger.flushSuppressed()` 的导出调用点；`RootFeedbackFields.header(...): String` 与 `RootFeedbackFields.template(): String`，只提供 `logSchemaVersion`、持久 provider、active provider、transport 状态、needsForegroundRevalidation 及用户手填模板。**template 是追加到计划 B 同一行 header 的单行后缀，不新增报告行。**

- [ ] **Step 1: 写失败导出测试。** 构造 selected=root、active=shizuku、BIND_TIMEOUT 的切换失败态，header 必须原样区分三者且包含 logSchemaVersion；template 只含“Root 实现/版本、su 版本、ROM、Android、SELinux”手填项且不自动识别管理器。header+template 合并后必须只有一行，最终报告仍为 1 行 header + 最近 199 行日志。导出前 flush 输出 suppressedCount；反馈导出必须调用计划 B sanitize，原始复制保持不脱敏；清空同时调用 RootDiagnosticLogger.reset 与 FeedbackLogSanitizer.reset。
- [ ] **Step 2: 运行 RED。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootFeedbackHeaderTest --rerun-tasks`

Expected：FAIL（Root header/导出接线不存在）；若计划 B 尚未合入，先合入其接口，不在本任务另写 sanitizer。
- [ ] **Step 3: 接入既有导出。** 在计划 B 的原始/反馈两个导出函数生成快照前 flush Root gate；将 `RootFeedbackFields.header(...) + RootFeedbackFields.template()` 合并进计划 B 的**同一行 header**，再对最近 199 行日志调用 `FeedbackLogSanitizer.sanitize(lines)`，最终总行数最多 200。原始“复制日志”保持现状；不得另附多行模板或突破预算，日志不新增持久化/上传；既有 readConfig/writeConfig/dumpBtState 结果仍保留，serviceUid 有观察值才记录。
- [ ] **Step 4: 运行全量、lint、debug 与签名 release 验收。**

Run：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew test --rerun-tasks && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew lint assembleDebug && JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home/bin:$PATH ./gradlew :app:assembleRelease :app:dependencies --configuration releaseRuntimeClasspath`

Expected：全部 PASS；release 使用既有四个 STAYHIFI 签名环境变量，不跳过签名；service/core 均 6.0.0 且无 nio/io。随后运行：

Run：`rg -n 'RootCodecService|RootService|Initializer' app/build/outputs/mapping/release/mapping.txt && rg -n 'RootCodecService|moe\.shizuku|roro\.stellar|Magisk|KernelSU' app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml && git diff --exit-code d3adf81 -- app/src/main/AndroidManifest.xml app/proguard-rules.pro app/src/main/aidl/com/chengzi/stayhifi/ICodecService.aidl`

Expected：mapping 能看到 RootService/Initializer consumer 保留链及我方入口；merged Manifest 无 RootCodecService、无 Root manager 查询/新 exported Service；既有 Manifest、ProGuard、AIDL 相对基线零 diff。若路径随 AGP 变化，先定位对应 release 产物并记录实际路径，不改验收语义。
- [ ] **Step 5: 提交。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/shell/RootDiagnostics.kt app/src/main/java/com/chengzi/stayhifi/MainActivity.kt app/src/test/java/com/chengzi/stayhifi/shell/RootFeedbackHeaderTest.kt
git commit -m "Task D12: 完成 Root 反馈字段与构建验收" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

## 外部验收（不是本地通过声明）

1. 至少一台 Magisk 与一台 KernelSU 的最终 minified release 样本，分别记录管理器/su 版本、ROM、Android、SELinux；完成授权→bind→dumpDevices/readConfig/dumpBtState→一次安全写入/读回，binder 连通不能替代任何单项。
2. 覆盖拒绝/撤权、无 su、普通 sh fallback、null binder、bind timeout、迟到 bind、服务断开/管理器冻结；确认状态文案只讲事实、迟到连接被 unbind、无重复弹窗且随时可手动退回。
3. 同一耳机做 Shizuku/Stellar 与 Root 对照，current/selectable 不得因通道改变凭空增加；默认选择和既有 9 字段/恢复默认行为不回归。
4. SELinux enforcing 重点 ROM 分别验证 uid/serviceUid、隐藏 API、dumpsys 权限；任何 uid 0 成功都不能外推到其他 ROM。重启后 OEM 自启动限制不得包装成 RootService 已解决。
5. 冷进程执行 Boot/Listener/QS/Apply：Root 管理器界面不得出现、shell/bind 不得发起，只留去重的 NEEDS_FOREGROUND_REVALIDATION；前台重验后才恢复 READY。
6. 外部样本不足时继续标“实验”，不宣称全面支持 Magisk/KernelSU；发布说明只能列实际通过的具体组合，且不阻塞其他已验证修复。

## 计划自审

- **Spec coverage：** §11.1-11.6 分别映射 Task 1/2/4-12；§12 的 200 行、无上传、计划 B 脱敏/LogStore 边界映射 Task 3/12；§13 Root fake/构建映射各任务与 Task 12；§14.2 全七项在外部验收保留；§15-16 的 Coordinator 自由度与安全边界已固定。
- **Step scan：** 每任务均有独立失败断言、精确 RED/GREEN 命令、最小实现范围与独立 commit；没有把架构总装留到最后一个任务。
- **Type consistency：** ProviderKind/ProviderSource、ProviderOwner、RootAttempt/RootCandidate、ProviderLease、RootSnapshot 与 Coordinator 方法在生产和后续 UI 任务中使用同名同返回语义；ICodecService 不扩展。
- **Review Focus：** 五项高风险输入均有 owning task 的 fake/虚拟时间/写盘失败/迟到回调测试；本地无 Root 不作为真机证据。
- **Proportion：** 计划只固定跨任务接口、看门狗/锁/代次和验收决策；方法体、Compose 布局与 fake 细节由实现者完成，不包含实现 transcript。
