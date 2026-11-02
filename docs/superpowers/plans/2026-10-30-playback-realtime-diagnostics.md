# Playback Realtime Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在播放开始后有界、静默地收集一次紧凑的蓝牙与音频诊断，并让现有复制反馈日志只读导出这些证据。

**Architecture:** Root/Shizuku 共用一个批量诊断 IPC，服务侧过滤并限制三类 dumpsys；App 侧用独立 store 做字段级去重、隐私清洗与变化合并。Application 的播放回调与既有 `isMusicActive` 事件点进入同一个单飞窗口控制器，同一 MAC+完整 CodecConfig+provider generation 每进程只采一次；复制只组合现有日志与最多 60 行诊断，整份报告仍不超过 200 行。

**Tech Stack:** Kotlin、Android AIDL、Root/Shizuku、`dumpsys`、kotlinx-coroutines、JUnit4、kotlinx-coroutines-test、既有 `FeedbackLogSanitizer`。

**Spec:** `docs/superpowers/specs/2026-10-30-playback-realtime-diagnostics-design.md`

## Global Constraints

- 不新增设置项、开关、弹窗、提示或采集入口；`autoEnabled` 不参与诊断判断，不因此唤醒进程或新拉 FGS。
- 同一 MAC + 完整 `CodecConfig` + provider generation 每进程最多完成一个窗口；暂停/换歌不重复，清空后允许新会话重采。
- 每个窗口最多 0s/3s/8s 三个样本，随后停止；不新增常驻 ticker 或周期性 `readConfig`。
- 复制反馈日志不得新增 provider/dumpsys/Root/授权 I/O 或等待；无诊断时保持 header + 199 行普通日志。
- 有诊断时最多 139 行普通日志 + 60 行诊断 + 1 行 header，总计最多 200 行；不得保存三份原始 dump。
- 不修改首页、`BtStateParser` 的现有语义、自动/手动写入目标、现有 `dumpBtState()` 过滤或 1.2.3 其他修复。
- 不新增网络、持久化、音频内容、媒体标题、麦克风、HCI snoop 或完整 logcat 采集。
- Root 复用既有 READY anchor/IPC Mutex；首个诊断 IPC 超时后取消剩余样本，不另开 Root 通道。
- 未经用户后续明确要求，不 commit、不 merge、不 push、不发布；每个任务以 RED→GREEN 测试和账本记录收尾。

## Review Focus

- 卡死的 Root transact 让 app 协程超时后仍占用 Mutex：首个超时必须终止窗口，不能继续启动 3s/8s 样本（Task 3）。
- 播放回调风暴、callback 缺失、非 A2DP 播放与 codec 进程重复初始化：不得重复采集或在非主 Application 进程注册（Task 3）。
- `name:`、`bdaddr=`、包名、进程名、路径和非目标设备名混杂：不得泄露，也不得误删采样率/codec/bitrate（Task 2）。
- 大输出、慢进程、截断和单源失败：必须保留独立状态，不能把 timeout/empty/truncated 当成“系统不支持”（Task 1、Task 2）。
- 清空/换机后旧 IPC 返回、复制时无播放、报告行数与别名：旧结果不得复活，复制零 I/O，报告最多 200 行且 deviceN 一致（Task 3、Task 4）。

---

### Task 1: 批量诊断 IPC 与有界服务侧采集

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/RealtimeDiagnosticDump.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/shell/RealtimeDumpRunner.kt`
- Modify: `app/src/main/aidl/com/chengzi/stayhifi/ICodecService.aidl`
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt`
- Modify: `app/src/test/java/com/chengzi/stayhifi/shell/RootServiceContractTest.kt`
- Modify: 现有实现 `ICodecService.Stub` 的测试 fake（统一返回空的三源 JSON）
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RealtimeDumpRunnerTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/RealtimeDiagnosticDumpTest.kt`

**Interfaces:**
- Produces: `ICodecService.dumpRealtimeDiagnostics(String codecName): String`。
- Produces: `RealtimeDiagnosticDump(bluetooth: Source, audio: Source, audioFlinger: Source)`；`Source(status, content, error)`，`status ∈ ok/empty/timeout/error/truncated`。
- Produces: `RealtimeDiagnosticDump.parse(String): RealtimeDiagnosticDump`，供 Task 2/3 使用。
- Produces: 服务侧每源最多 120 行、16 KiB；每源 dumpsys 超时 3 秒，批量 App 等待预算由 Task 3 定为 12 秒。

- [ ] **Step 1: 先写合同、过滤与 JSON 失败测试**

覆盖：`RootServiceContractTest` 的既有方法集缺少 `dumpRealtimeDiagnostics`；目标 LHDC 段保留下一段之前全部相关行而不借其他 codec 段；每源 121 行/超过 16 KiB 标 `truncated`；audio 超时不妨碍 bluetooth/audioFlinger；空输出为 `empty`；错误摘要非空且不带整份 stderr。JSON 解析必须拒绝缺失三源或未知 status。

- [ ] **Step 2: 运行测试并确认按预期失败**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.shell.RootServiceContractTest --tests com.chengzi.stayhifi.shell.RealtimeDumpRunnerTest --tests com.chengzi.stayhifi.codec.RealtimeDiagnosticDumpTest`

Expected: FAIL，原因是 AIDL 方法、runner 与数据模型尚不存在，而不是测试拼写错误。

- [ ] **Step 3: 实现最小批量接口与 runner**

AIDL 增加 `String dumpRealtimeDiagnostics(String codecName);`。`CodecService` 委托 `RealtimeDumpRunner`，分别执行 `/system/bin/dumpsys bluetooth_manager`、`audio`、`media.audio_flinger`，流式读取 stdout/stderr，保留目标段/相关行并执行上述上限；不把整份输出交给 App。`RealtimeDiagnosticDump` 只负责三源模型与严格 JSON 解析，既有 `dumpBtState()` 不变。

- [ ] **Step 4: 运行合同与 runner 测试**

Run: 同 Step 2。

Expected: PASS；既有 fake 仅补空三源响应，不改变其他合同断言。

- [ ] **Step 5: 跑受影响回归并记录账本**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.shell.*`

Expected: PASS。记录接口、输出上限、RED/GREEN 结果；不提交。

### Task 2: 字段压缩、隐私清洗与独立诊断 Store

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/PlaybackDiagnosticsStore.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/PlaybackEvidenceCompactor.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/PlaybackEvidenceCompactorTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/PlaybackDiagnosticsStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 `RealtimeDiagnosticDump` 与 `Source`。
- Produces: `PlaybackEvidenceCompactor.compact(windowId: Long, mac: String, samples: List<TimedDiagnosticDump>): List<String>`；`TimedDiagnosticDump(atMs: Long, dump: RealtimeDiagnosticDump)`。current/selectable 已由既有诊断持有，本压缩器不重复输出。
- Produces: `PlaybackDiagnosticsStore.replace(windowId: Long, lines: List<String>)`、`snapshot(): List<String>`、`clear()`、`generation: Long`；最终报告行由 Task 4 使用。
- Produces: 单窗口诊断最多 60 行，字段变化写为 `old → new`，相同字段跨样本/来源只保留一次并标注来源。

- [ ] **Step 1: 写压缩与隐私失败测试**

使用三份含重复值的 dump：只输出一次相同字段，变化合并为 `48000 → 192000`；不重复 current/selectable；`name:`、包名、进程名、路径和无法识别的设备名行被丢弃，`bdaddr` 中 MAC 保留给后续统一别名；采样率、codec、bitrate、timeout/truncated 状态保留。61 行以上截断到 60 且最后仍保留显式 `truncated`。Store 覆盖旧窗口、clear 递增 generation 并使旧 `replace` 无效。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.codec.PlaybackEvidenceCompactorTest --tests com.chengzi.stayhifi.codec.PlaybackDiagnosticsStoreTest`

Expected: FAIL，原因是 compactor/store 不存在。

- [ ] **Step 3: 实现最小压缩和线程安全 store**

按“来源+字段原文”去重，以时间点记录差异；不对未知字段猜语义。隐私采用默认丢弃原则，只保留已证明安全的字段/MAC，原始设备名不进入 store。Store 提交前校验 windowId 与当前 generation。

- [ ] **Step 4: 运行测试并记录账本**

Run: 同 Step 2。

Expected: PASS，且 60 行上限、去重、隐私和代次断言全部成立；不提交。

### Task 3: 播放单飞窗口与 Android 接线

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/PlaybackDiagnosticsController.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/StayHiFiApp.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt`（仅既有播放事件点补同一 controller 信号，不改 UI）
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/ProviderCoordinator.kt`（增加 `ProviderSource.DIAGNOSTIC`，让后台日志保持真实来源）
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/PlaybackDiagnosticsControllerTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/BackgroundProviderContractTest.kt`（仅必要合同更新）

**Interfaces:**
- Consumes: Task 1 `dumpRealtimeDiagnostics(codecName)` 与 Task 2 compactor/store。
- Produces: `PlaybackDiagnosticsController.onPlaybackSignal()`、`onPlaybackStopped()`、`clear()`。
- Produces: 单飞解析本机 A2DP → probe/acquire → readConfig → 签名去重 → 0/3/8s 采样 → compact/replace；所有 I/O 注入 dispatcher。
- Produces: Application 暴露只读 `playbackDiagnostics: PlaybackDiagnosticsController`，供 Task 4 清空；只在 `Application.getProcessName() == packageName` 时初始化/注册。

- [ ] **Step 1: 写状态机失败测试**

使用 `StandardTestDispatcher` 与 fake collector：连续 100 次播放信号只启动一个窗口；0/3/8s 后无第四次；暂停再播放同签名不重启；MAC/完整 current/provider generation 变化作废旧窗口；clear 后允许新会话；非 A2DP、provider 未就绪、readConfig 失败不触发 dump 且不改连接事实；首个 IPC 超时后不再取后续样本；旧样本在 clear/换机后不能入库。callback 缺失只走既有 MainActivity 事件点，不新增 ticker。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.codec.PlaybackDiagnosticsControllerTest`

Expected: FAIL，原因是 controller 不存在。

- [ ] **Step 3: 实现单飞 controller 与 Application 接线**

controller 内部串行解析连接/provider/current，签名完整后才允许新窗口；lease 在窗口结束/finally 关闭。批量调用 `withTimeoutOrNull(12_000)`，首超时即终止窗口。Application 在主进程创建 controller、注册 `AudioPlaybackCallback`；MainActivity 既有 `isMusicActive=true` 事件仅调用 `onPlaybackSignal()`，不得形成第二条采集链。不得读取 `autoEnabled`，不得创建新的服务或授权交互。

- [ ] **Step 4: 运行 controller 与受影响 provider 回归**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.codec.PlaybackDiagnosticsControllerTest --tests com.chengzi.stayhifi.shell.BackgroundProviderContractTest --tests com.chengzi.stayhifi.trigger.ApplyWorkerManualTest`

Expected: PASS；现有写入/Root/连接测试没有行为变化。

- [ ] **Step 5: 记录账本**

记录单飞签名、三个时间点、超时停止与主进程边界；不提交。

### Task 4: 复制导出、清空失效与整体验证

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/FeedbackLogSanitizer.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/FeedbackLogSanitizerTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootFeedbackHeaderTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/PlaybackDiagnosticsControllerTest.kt`（补复制/清空真实接缝需要的用例）

**Interfaces:**
- Consumes: Task 2 `PlaybackDiagnosticsStore.snapshot()` 与 Task 3 `PlaybackDiagnosticsController.clear()`。
- Produces: `FeedbackLogSanitizer.sanitize(lines: List<String>, diagnosticLines: List<String>): String` 重载；既有 `sanitize(lines)` 保持原语义。
- Produces: 复制只组合普通日志与诊断；清空同时使窗口代次/store/旧 sanitizer 映射失效。

- [ ] **Step 1: 写报告与复制失败测试**

无诊断时输出仍最多 header+199 行；有诊断时普通日志 139 + 诊断 60 + header=200，且普通/附录中同一 MAC/设备名均为同一 deviceN。含包名/路径的诊断不得进入报告。复制路径调用 fake 计数必须为 0 次新 I/O；清空后旧窗口迟到结果不得出现在下一份报告。

- [ ] **Step 2: 运行测试并确认失败**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.FeedbackLogSanitizerTest --tests com.chengzi.stayhifi.shell.RootFeedbackHeaderTest`

Expected: FAIL，原因是双源 sanitize 与 store 导出尚未接入。

- [ ] **Step 3: 实现最小报告组合与清空接线**

sanitizer 在两阶段中共享普通/诊断行的别名发现；无诊断走原 199 行窗口，有诊断使用 139/60 预算。`copyLogs()` 只读取 store 快照；LogsAction.Clear 先使 controller/store 失效，再清现有日志与 sanitizer，不能引入同步 provider I/O。

- [ ] **Step 4: 运行报告与 controller 回归**

Run: `./gradlew testDebugUnitTest --tests com.chengzi.stayhifi.FeedbackLogSanitizerTest --tests com.chengzi.stayhifi.shell.RootFeedbackHeaderTest --tests com.chengzi.stayhifi.codec.PlaybackDiagnosticsControllerTest`

Expected: PASS。

- [ ] **Step 5: 跑全量测试、lint 与 release 构建**

Run: `./gradlew test lint assembleRelease`

Expected: 全部成功；记录实际测试总数，不预写通过数量，不提交、不发布。

- [ ] **Step 6: 做一次 fresh 全分支审查并处理 Critical/Important**

审查完整 worktree 相对 8c39ba0 的 staged+unstaged+untracked 差异，重点核对本计划 Review Focus 与 spec 全约束；Minor 记录延期，Critical/Important 每项 RED→GREEN 后重跑全量。外部 Xiaomi/一加证据收集能力只能标为待灰度，不宣称实际码率/采样率已支持。
