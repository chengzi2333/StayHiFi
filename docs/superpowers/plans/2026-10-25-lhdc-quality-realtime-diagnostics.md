# LHDC 设置层码率与实际传输诊断增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 发布候选项 StayHiFi 1.2.1：修复已佐证 LHDC 的设置层码率显示，补足实际传输诊断证据，并提供三条以内的普通用户 Release 说明。

**Architecture:** 只在现有 `Masks` 显示层增加带能力门控的 LHDC cs1 解码；实时显示链路和写入策略不动。诊断复用现有 dump，以四类 `sampleKind` 去重；首页负责状态跃迁，ApplyWorker 仍只采首次配置，最后独立修改版本元数据。

**Tech Stack:** Android、Kotlin、Jetpack Compose、JUnit4、Gradle/AGP、Shizuku/Stellar。

**Spec:** `docs/superpowers/specs/2026-10-25-lhdc-quality-realtime-diagnostics-design.md`

## Global Constraints

- 基线：master `ef13067`，101/101 单测；所有任务在同一隔离 worktree/分支依次完成，禁止 Task 52/53 并行修改 `MainActivity.kt`。
- JDK：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home`，并将其 `bin` 放到 `PATH` 最前。
- 只读显示：不得修改 `ApplyPolicy.BEST_CS1`、不得写 19 号 cs1、不得改变拉满目标或协商逻辑。
- LHDC 显示必须先有身份佐证，再验证 `(cs1 and 0xC000L) == 0x8000L`；不得裸认 codecType 19。
- 实际传输不得由 cs1/PCM/理论压缩率推算；首页现有三态与段名匹配行为不变。
- dumpsys 总次数、shell 过滤条件、binder 返回文本均不得增加；不得新增 AIDL、持久日志、网络上传或 ListenerService dumpsys。
- MainActivity 用真实 `sampleKind` 采集状态跃迁；ApplyWorker 仅以固定 `apply-dump` 进入自己的 gate，后续运行不得为发现新类别增加 dump。
- available 的码率变化可继续更新 UI，但不得重复写 vendor 证据块；无匹配段线索每块最多 24 行。
- 每个功能任务由 fresh fable implementer 以 TDD 开发，fresh fable reviewer 审查，主 agent 再复审。
- 每次本地提交末尾必须带：`Co-Authored-By: Claude Code <noreply@anthropic.com>`；不得 push 或发布。
- Task 54 固定为 `versionCode 6 / versionName '1.2.1'`；Release 正文最多三条用户语言更新点。
- 完成声明必须有最终提交、全量测试、构建和本机装机证据；外部 Xiaomi/OPlus 实际传输不得凭本机结果宣称已修复。

## Review Focus

1. 合法 cs1 低字节落在未佐证 vendor、区间外 codec 或错误 magic 上：应返回 null，而不是误标（Task 52 Step 1/2）。
2. codecType 10 的旧值和其他合法档位：旧结果不能回归，非法 magic/低字节仍为空（Task 52 Step 1/4）。
3. available 从 400 变为 500：UI 可更新，但 gate 不新增日志；ApplyWorker 同 raw 后续运行仍不新增 dump（Task 53 Step 4/8/10）。
4. 超过 24 个通用 State 行后才出现 quality/bitrate：线索函数必须优先保留后者（Task 53 Step 4/6）。
5. raw bitrate 为 -1、缺字段或非数字：原始证据与归一化/未流式语义必须区分，不能混成同一种空值（Task 53 Step 1/3）。

---

### Setup：隔离执行与文档提交（主 agent）

**Files:**
- Copy: `docs/superpowers/specs/2026-10-25-lhdc-quality-realtime-diagnostics-design.md`
- Copy: `docs/superpowers/plans/2026-10-25-lhdc-quality-realtime-diagnostics.md`

- [ ] **Step 1: 通过 using-git-worktrees 创建 `task/lhdc-quality-realtime-diagnostics` 隔离工作区，并把 spec/plan 复制到相同相对路径。**
- [ ] **Step 2: 提交两份文档，提交信息为 `Docs: StayHiFi 1.2.1 LHDC 修复设计与实施计划`，末尾附全局规定的 Co-Authored-By。**
- [ ] **Step 3: 在 `.superpowers/sdd/2026-10-25-lhdc-quality-realtime-diagnostics/` 建立任务简报/报告/进度账本；Task 52/53/54 共用同一分支，依次执行。**

### Task 52：已佐证 LHDC 的设置层码率显示

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/CodecConfig.kt:90-120`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:1129`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/MasksTest.kt`

**Interfaces:**
- Consumes: 现有 `Masks.codecName(type, capsRateMask)` 与 `capsMask`。
- Produces: `Masks.qualityLabel(codecType: Int, cs1: Long, capsRateMask: Int = 0): String?`；Task 53 不改变此接口。

- [ ] **Step 1: 先写失败测试，覆盖反馈 fixture、完整档位和身份/magic 边界。**

在 `MasksTest.kt` 增加语义如下的测试（可用 `assertEquals(null, ...)` 保持现有风格）：

```kotlin
@Test fun qualityLabelDecodesFeedbackVendorLhdcFixtures() {
    assertEquals("自适应", Masks.qualityLabel(19, 32777L, 42))
    assertEquals("400kbps", Masks.qualityLabel(19, 32773L, 43))
}

@Test fun qualityLabelDecodesAllLhdcTiersForVerifiedVendor() {
    val labels = listOf(
        "64kbps", "128kbps", "192kbps", "256kbps", "320kbps",
        "400kbps", "500kbps", "900kbps", "1000kbps", "自适应",
    )
    labels.forEachIndexed { index, label ->
        assertEquals(label, Masks.qualityLabel(19, 0x8000L or index.toLong(), 42))
    }
}

@Test fun qualityLabelRequiresLhdcIdentityAndMagic() {
    assertEquals(null, Masks.qualityLabel(19, 32773L))       // 无能力上下文
    assertEquals(null, Masks.qualityLabel(19, 32773L, 8))   // 最高 96k，不足以佐证
    assertEquals(null, Masks.qualityLabel(0x20, 0x8005L, 32))
    assertEquals(null, Masks.qualityLabel(99, 0x8005L, 32))
    assertEquals(null, Masks.qualityLabel(19, 0xC005L, 43)) // 错误 magic
    assertEquals(null, Masks.qualityLabel(19, 0x800AL, 43))
    assertEquals(null, Masks.qualityLabel(19, 0x80FFL, 43))
}

@Test fun qualityLabelKeepsVivoLhdcAndRejectsInvalidValues() {
    assertEquals("1000kbps", Masks.qualityLabel(10, 50888712L))
    assertEquals("自适应", Masks.qualityLabel(10, 33914889L))
    assertEquals("400kbps", Masks.qualityLabel(10, 0x03088005L))
    assertEquals(null, Masks.qualityLabel(10, 5L))
    assertEquals(null, Masks.qualityLabel(10, 0x800AL))
}
```

- [ ] **Step 2: 运行 MasksTest，确认旧实现不能满足新接口/预期。**

Run：`./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.MasksTest`

Expected：编译或测试失败；记录实际输出，不能把未运行的测试写成 RED 证据。

- [ ] **Step 3: 实现带默认能力掩码的 qualityLabel，并接入首页。**

在 `Masks` 中将签名改为 `qualityLabel(codecType: Int, cs1: Long, capsRateMask: Int = 0): String?`。LDAC 原逻辑不变；codecType 10 或 `codecName(codecType, capsRateMask) == "LHDC"` 才进入 LHDC 解码。LHDC 先要求 magic `0x8000`，再按低字节 0..9 返回 spec §6.2 的标签，其他值 null。

在 `MainActivity.refreshHome()` 将首页赋值改为 `Masks.qualityLabel(current.codecType, current.cs1, capsMask)`。`CodecConfig.summary()` 保持两参调用；不得修改 ApplyPolicy。

- [ ] **Step 4: 运行 MasksTest 与完整单测。**

Run：`./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.MasksTest && ./gradlew test`

Expected：新测试和既有测试全部 PASS；记录最终测试数。

- [ ] **Step 5: 提交 Task 52。**

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/CodecConfig.kt \
  app/src/main/java/com/chengzi/stayhifi/MainActivity.kt \
  app/src/test/java/com/chengzi/stayhifi/codec/MasksTest.kt
git commit -m "Task 52: 修复已佐证 LHDC 设置层码率显示" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 53：实际传输诊断证据增强

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/BtStateParser.kt:27-96`
- Modify: `app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnostics.kt`
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnosticGate.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt:919-934,1057-1120,1246-1257`
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt:89-124,377-397`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/BtStateParserTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorDiagnosticsTest.kt`
- Test: `app/src/test/java/com/chengzi/stayhifi/codec/VendorDiagnosticGateTest.kt`

**Interfaces:**
- Consumes: Task 52 已接入的 `capsMask`；不改变 `Masks.qualityLabel`。
- Produces:
  - `BtState(..., notStreaming: Boolean = false, rawBitrate: Long? = null)`（rawBitrate 放最后，兼容现有构造调用）。
  - `VendorDiagnostics.sampleKind(state: BtStateParser.BtState?): String`
  - `VendorDiagnostics.evidence(state: BtStateParser.BtState): String`
  - `VendorDiagnostics.dumpClues(dump: String, limit: Int = 24): List<String>`
  - `VendorDiagnosticGate.shouldRecord(mac: String, current: CodecConfig, sampleKind: String): Boolean`
  - `VendorDiagnosticGate.reset(): Unit`

- [ ] **Step 1: 先为 rawBitrate 写失败测试。**

在 `BtStateParserTest` 增加/扩展断言：

```kotlin
// LHDC 400000 样本
assertEquals(400000L, s.rawBitrate)
assertEquals(400, s.bitrateKbps)

// LDAC -1 样本
assertEquals(-1L, s.rawBitrate)
assertEquals(null, s.bitrateKbps)
assertTrue(s.notStreaming)

// AAC 缺 bitrate 字段样本
assertEquals(null, s.rawBitrate)
assertFalse(s.notStreaming)
```

另加非数字 `transmission bitrate: unknown` fixture，断言 `rawBitrate=null`、`bitrateKbps=null`、`notStreaming=false`。

- [ ] **Step 2: 运行 BtStateParserTest，确认旧实现失败。**

Run：`./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.BtStateParserTest`

Expected：因 rawBitrate 尚不存在而编译失败，或断言失败；记录真实输出。

- [ ] **Step 3: 最小实现 rawBitrate 并跑通解析测试。**

在 `BtState` 最后增加 `rawBitrate: Long? = null`；解析到 Long 时同时保存原值和现有归一化结果，非数字/缺字段保持 null。不得改变 `realtimeDisplay()`。

Run：`./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.BtStateParserTest`

Expected：PASS。

- [ ] **Step 4: 先写 sampleKind/evidence/dumpClues/Gate 失败测试。**

`VendorDiagnosticsTest` 增加：

```kotlin
@Test fun sampleKindClassifiesFourEvidenceStates() {
    assertEquals("no-section", VendorDiagnostics.sampleKind(null))
    assertEquals("not-streaming", VendorDiagnostics.sampleKind(
        BtStateParser.BtState("LHDC", null, null, true, -1L)))
    assertEquals("no-bitrate", VendorDiagnostics.sampleKind(
        BtStateParser.BtState("LHDC", "ABR", null, false, null)))
    assertEquals("available", VendorDiagnostics.sampleKind(
        BtStateParser.BtState("LHDC", "ABR", 400, false, 400000L)))
}
```

另补：available 从 400/ABR 变为 500/HIGH 仍为 `available`；evidence 同时含 `rawBitrate=400000`、`bitrateKbps=400`，缺值为 `—`。把既有 `formatsDumpsysEvidenceWithCs1CorrelationFields` 的 fixture 补上 `rawBitrate=400000L`，预期改为 `段=LHDC V5 qualityMode=ABR rawBitrate=400000 bitrateKbps=400 notStreaming=false`。

`dumpClues` 测试构造 25 个通用 State 行后再放 A2DP/quality/bitrate 行，断言这些关键行仍被保留；另测空行/重复行、两组相对顺序和最多 24 行。

新建 `VendorDiagnosticGateTest`，使用 `CodecConfig(19, 0, 32, 2, 2, 32773L, 0, 0, 0)`：

```kotlin
val gate = VendorDiagnosticGate()
assertTrue(gate.shouldRecord("mac-a", current, "not-streaming"))
assertFalse(gate.shouldRecord("mac-a", current, "not-streaming"))
assertTrue(gate.shouldRecord("mac-a", current, "available"))
assertFalse(gate.shouldRecord("mac-a", current, "available")) // 400→500 同类不新增
assertTrue(gate.shouldRecord("mac-b", current, "available"))
assertTrue(gate.shouldRecord("mac-a", current.copy(sampleRate = 2), "available"))
gate.reset()
assertTrue(gate.shouldRecord("mac-a", current, "available"))
```

另测同 mac+raw 的 `no-section`、`no-bitrate` 也可各记一次。

- [ ] **Step 5: 运行两个诊断测试类，确认旧实现失败。**

Run：`./gradlew :app:testDebugUnitTest --tests com.chengzi.stayhifi.codec.VendorDiagnosticsTest --tests com.chengzi.stayhifi.codec.VendorDiagnosticGateTest`

Expected：新接口不存在或断言失败；记录真实输出。

- [ ] **Step 6: 实现纯诊断函数与 Gate。**

- `sampleKind` 严格按 `null → notStreaming → bitrateKbps==null → available` 的顺序分类。
- `evidence` 保留原字段并加入 rawBitrate。
- `dumpClues`：仅 `trimEnd` 并去空行，保留前导缩进；先取含 A2DP/codec/quality/bitrate（忽略大小写）的行，再补其他 `State:` 行；按内容去重，组内顺序稳定，最多 `limit` 行。
- `VendorDiagnosticGate` 内部保存 `mac|current.toJson()|sampleKind` 集合；`shouldRecord`/`reset` 使用 `@Synchronized`，不引入 Android/持久化依赖。

Run：同 Step 5。

Expected：PASS。

- [ ] **Step 7: 接入 MainActivity，保持现有 dump 节奏。**

- 在 `btDump` 旁保存本轮 parsed state，vendor 块复用它，不再次 parse。
- `recordUnknownVendor(mac, current, sampleKind)` 改用真实 `VendorDiagnostics.sampleKind(state)` 和独立 `VendorDiagnosticGate`。
- 证据前缀加入 `source=home isMusicActive=$isMusicActive sample=$kind`；evidence 使用 Step 6 新格式。
- 无匹配段改用 `VendorDiagnostics.dumpClues(btDump)`，标签为 `dumpsys线索`。
- companion 的旧 Set 替换为 gate；`resetVendorDiagnostics()` 改调 `reset()`。首页 `homeState.copy` 不得受 gate 返回值影响。

- [ ] **Step 8: 接入 ApplyWorker，严格保持首次配置才 dump。**

- 现有 dump 前的去重调用改为 `recordUnknownVendor(mac, settled.current, "apply-dump")`；只有首次命中才进入原有 dump 块。不得把真实 sampleKind 提到 dump 前去计算。
- 读取并记录同一名义的 `AudioManager.isMusicActive`；证据前缀加入 `source=apply ... sample=<实际kind>`。
- dump error/exception 可标为 `dump-error`/`dump-exception`，但不得因此再试一次 dump。
- 无匹配段同样改用 `dumpClues`；companion 旧 Set 改 gate，清空日志行为不变。

- [ ] **Step 9: 运行完整单测与静态边界检查。**

Run：`./gradlew test && git diff --check`

Expected：全部测试 PASS，diff check 无输出。

Run：`git diff -- app/src/main/java/com/chengzi/stayhifi/shell/CodecService.kt app/src/main/java/com/chengzi/stayhifi/trigger/ApplyPolicy.kt`

Expected：无 diff；若有，停止并回退越界修改。

- [ ] **Step 10: 主流程自审并提交 Task 53。**

确认：MainActivity 复用 parsed、UI 更新不依赖 gate、ApplyWorker 后续同 raw 不新增 dump、两条无匹配段路径都使用 24 行共享线索函数。

```bash
git add app/src/main/java/com/chengzi/stayhifi/codec/BtStateParser.kt \
  app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnostics.kt \
  app/src/main/java/com/chengzi/stayhifi/codec/VendorDiagnosticGate.kt \
  app/src/main/java/com/chengzi/stayhifi/MainActivity.kt \
  app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt \
  app/src/test/java/com/chengzi/stayhifi/codec
git commit -m "Task 53: 增强 LHDC 实际传输诊断证据" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 54：1.2.1 版本与简短 Release 说明

**Files:**
- Modify: `app/build.gradle:16-17`
- Create (gitignored，不提交): `.superpowers/sdd/2026-10-25-lhdc-quality-realtime-diagnostics/release-1.2.1.md`

**Interfaces:**
- Consumes: Task 52/53 最终代码。
- Produces: `versionCode 6 / versionName '1.2.1'` 的 APK 与三条 Release 正文。

- [ ] **Step 1: 修改用户指定的版本元数据，并写 Release 文案。**

`app/build.gradle` 仅改 `versionCode 6`、`versionName '1.2.1'`。这是配置验收任务，不编写人为失败的逻辑测试。

`release-1.2.1.md` 只写：

```text
- 修复部分手机不显示码率档位的问题。
- 改进运行日志，方便反馈实际传输问题。
- 实时码率仍取决于手机系统支持，部分机型暂无法显示。
```

不得加入 Task、cs1、codecType、dumpsys、gate、commit、解析器或测试流水账。

- [ ] **Step 2: 构建并运行全量测试。**

Run：`./gradlew test assembleDebug`

Expected：BUILD SUCCESSFUL，所有测试 PASS。

- [ ] **Step 3: 直接验证 APK 版本和文案边界。**

Run：`/Users/chengzi/Library/Android/sdk/build-tools/37.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk`

Expected：package 行包含 `versionCode='6'` 与 `versionName='1.2.1'`。

Run：`rg -n 'Task|cs1|codecType|dumpsys|gate|commit|解析器' .superpowers/sdd/2026-10-25-lhdc-quality-realtime-diagnostics/release-1.2.1.md`

Expected：无匹配；文案仍为上述三条。

- [ ] **Step 4: 提交版本元数据（gitignored 文案不提交）。**

```bash
git add app/build.gradle
git commit -m "Task 54: 版本更新至 1.2.1" \
  -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Final Verification（主 agent，不另起施工任务）

- [ ] fresh fable 对 Task 52→54 完整 diff 终审；主 agent 逐条处理发现，不盲目接受。
- [ ] 使用最终提交重新运行 `./gradlew test assembleDebug`，统计真实测试数并检查 `git diff --check`。
- [ ] 构建可覆盖安装的包并在 V2505A 真机验证：codecType 10 既有四格、播放/暂停三态、清空后重记、复制日志；记录包版本与最终 commit。
- [ ] 对照 spec §10.4 准备外部采样指引，明确本机无法证明 Xiaomi/OPlus 实际传输已修复。
- [ ] 进入 finishing-a-development-branch；merge/push/tag/Release 均另行确认，Release 正文只能使用 Task 54 的三条用户文案。
