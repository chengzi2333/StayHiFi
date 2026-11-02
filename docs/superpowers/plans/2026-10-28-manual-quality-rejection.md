# LHDC 手动档位拒绝反馈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 LHDC 手动码率档被系统明确拒绝后，用户只能看到泛化报错且可反复提交同一失败组合的问题，并把版本推进到 1.2.3。

**Architecture:** 保留现有 `applyManual(): Boolean` 兼容入口，新增详细结果区分“已生效 / 栈明确读回拒绝 / 其他失败”。仅“读回 final 存在且与显式目标不符”形成拒绝证据；首页用进程内 store 按 MAC+完整 CodecConfig 记录，当前会话再次打开编辑器时禁用同一组合，其他组合和机型不受影响。

**Tech Stack:** Android/Kotlin、Jetpack Compose、JUnit4、kotlinx-coroutines-test；不新增依赖。

**Spec:** `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md` 的能力与事实边界；新增外部证据为一加 12 + X3、codec19、192kHz/24bit，手动 900kbps（目标 0x8007）连续三次读回 0x8009（自适应）。

## Global Constraints

- 版本修改为 `versionName='1.2.3'`、`versionCode=9`；保留当前 hotfix 的 A2DP、Root、Honor 改动，不回滚、不发布。
- 不全局删除 900kbps，不修改自动拉满 `BEST_CS1`，不把单样本扩大为机型黑名单。
- 仅当前进程、当前连接会话记录失败组合；断连/换机清空，不写 DataStore，不跨重连重放。
- `final == null`、error JSON、Root/权限/超时/IPC 失败都不是“设备不支持”的证据，只能算普通失败。
- 自动拉满与现有 `applyManual(): Boolean` 的调用语义保持兼容；严格读回不得放宽，不得把自适应冒充满血或用户目标。
- 不加入 LC3/LE Audio 调参，不修改 Root 状态机、AIDL 或 codec 自动写入策略。

## Review Focus

- 显式目标与 final 的 codec/rate/bits/cs1 不符：重试后仍不符才返回 `REJECTED_BY_STACK`，任一尝试成功立即返回 `APPLIED`（Task 1）。
- final 缺失或 Root/IPC 失败：返回 `FAILED`，不得写入拒绝 store（Task 1、Task 3）。
- 同一 MAC 下不同 rate/bits/codec/cs1：不得被同一失败记录误伤；不同 MAC 隔离（Task 2）。
- 断连、换机、恢复自适应成功、同组合后来成功：按对应边界清理记录（Task 2、Task 3）。
- 被拒绝组合仍可切换到其他组合或恢复自适应：只禁用重复“应用”，不锁死整个编辑器（Task 3）。

---

### Task 1: 区分手动写入的栈拒绝与普通失败

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/trigger/ApplyWorker.kt`
- Modify: `app/src/test/java/com/chengzi/stayhifi/trigger/ApplyWorkerManualTest.kt`

**Interfaces:**
- Produces: `enum class ManualApplyResult { APPLIED, REJECTED_BY_STACK, FAILED }`。
- Produces: `suspend fun applyManualDetailed(mac: String, name: String?, target: CodecConfig): ManualApplyResult`。
- Preserves: `suspend fun applyManual(mac: String, name: String?, target: CodecConfig): Boolean`，返回 `applyManualDetailed(...) == APPLIED`。

- [x] **Step 1: 写失败测试**
  - `stackReadbackMismatchReturnsRejected`：fake write 连续返回 `applied=true`、final 的 cs1 与显式目标不符，断言详细结果为 `REJECTED_BY_STACK`。
  - `missingFinalDoesNotClaimStackRejected`：显式目标读回缺 final，断言 `FAILED`。
  - `booleanCompatibilityEntryStillMapsOnlyAppliedToTrue`：现有 Boolean 入口行为不变。

- [x] **Step 2: 运行测试确认 RED**
  Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.trigger.ApplyWorkerManualTest'`
  Expected: 新 API 不存在/断言失败；既有测试不被人为放宽。

- [x] **Step 3: 实现详细结果与兼容包装**
  - 将现有 `writeVerified` 主体迁入详细实现；自动路径仍用 Boolean 包装。
  - 详细实现记录“至少一次 final 存在且与显式目标不符”；重试结束以此区分 `REJECTED_BY_STACK` 与 `FAILED`。
  - `manualLocked/manualWithLease` 透传详细结果；成功日志、`VerifiedTargetStore.record` 只在 `APPLIED` 执行。

- [x] **Step 4: 运行测试确认 GREEN**
  Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.trigger.ApplyWorkerManualTest' --tests 'com.chengzi.stayhifi.trigger.ApplyPolicyTest'`
  Expected: 全部通过，自动拉满语义不变。

### Task 2: 当前会话的手动失败组合存储

**Files:**
- Create: `app/src/main/java/com/chengzi/stayhifi/codec/ManualRejectionStore.kt`
- Create: `app/src/test/java/com/chengzi/stayhifi/codec/ManualRejectionStoreTest.kt`

**Interfaces:**
- Produces: `record(mac: String, target: CodecConfig)`、`remove(mac: String, target: CodecConfig)`、`forMac(mac: String): Set<CodecConfig>`、`clear(mac: String)`、`clearAll()`。
- `forMac` 返回只读快照；MAC 忽略大小写，target 使用完整数据类相等语义。

- [x] **Step 1: 写失败测试**
  - `rejectionIsExactPerMacAndCompleteTarget`：192/24+900 被拒绝，不影响 96/24+900、192/24+1000 或另一 MAC。
  - `removeAndClearScopes`：同组合后来成功只移除该组合；恢复自适应 `clear(mac)`；断连 `clearAll()`。

- [x] **Step 2: 运行测试确认 RED**
  Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.codec.ManualRejectionStoreTest'`
  Expected: store 不存在。

- [x] **Step 3: 实现最小进程内 store**
  使用 `Pair<String, CodecConfig>` 集合，MAC 统一大写；不提供持久化或 Android 依赖。

- [x] **Step 4: 运行测试确认 GREEN**
  Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.codec.ManualRejectionStoreTest'`
  Expected: 全部通过。

### Task 3: 首页拒绝反馈、恢复边界与 1.2.3 定版

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/home/ManualEditorSheet.kt`
- Modify: `app/src/test/java/com/chengzi/stayhifi/ui/home/ManualEditorInputTest.kt`（不存在则创建）
- Modify: `app/build.gradle`

**Interfaces:**
- Consumes: Task 1 `applyManualDetailed` / `ManualApplyResult`；Task 2 `ManualRejectionStore`。
- Produces: `ManualEditorInput.rejectedTargets: Set<CodecConfig> = emptySet()` 与 `isRejected(target: CodecConfig?): Boolean`。
- UI 文案：拒绝时“当前连接未接受该组合，参数未变。请换档或恢复自适应”。

- [x] **Step 1: 写失败测试**
  - `onlyExactRejectedTargetIsBlocked`：输入包含一条失败目标时，仅该完整目标返回 true；null、不同码率或不同 MAC 的输入不受影响。
  - 保持现有 `ManualEditorInput` 默认构造/预览兼容。

- [x] **Step 2: 运行测试确认 RED**
  Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.ui.home.ManualEditorInputTest'`
  Expected: 新属性/方法不存在。

- [x] **Step 3: 接入首页与编辑器**
  - `onApplyManual` 改用详细结果：成功维持原逻辑并 `remove`；`REJECTED_BY_STACK` 记录并显示专用 Toast/日志；`FAILED` 保持泛化失败且不记录。
  - 恢复自适应成功 `clear(mac)`；断连/换机 `clearAll()`。
  - 构造 `ManualEditorInput` 时传入 `forMac(mac)`；编辑器仅对 `isRejected(target)` 的组合禁用“应用”并显示专用文案，其他组合及“恢复自适应”保持可用。
  - 版本改为 1.2.3(9)。

- [x] **Step 4: 全量验证与构建**
  Run: `./gradlew test --rerun-tasks lint assembleRelease`
  Expected: 全部通过；真实一加 900kbps 场景仍需外部验收，不宣称本地已覆盖。
