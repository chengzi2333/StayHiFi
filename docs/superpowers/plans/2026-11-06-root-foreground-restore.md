# Root Foreground Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已持久选择 Root 的用户打开 App 时，在每个冷进程的前台自动恢复一次特权通道，不再要求重复进入设置点击验证。

**Architecture:** `ProviderCoordinator` 增加进程级自动恢复门闩，只从 `MainActivity.onStart` 进入；仍复用既有 candidate、健康检查和切换提交，不改后台路径。自动恢复与手动验证共享互斥，避免并发提交；`GRANTED_NOT_READY` 仅修正事实文案。

**Tech Stack:** Android/Kotlin、协程 StateFlow/Mutex、libsu 6.0.0、JUnit4、kotlinx-coroutines-test。

**Spec:** `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md` §11，以及 router 在 v1.2.4 编排中批准的“前台每进程自动恢复一次”行为修订。

## Global Constraints

- 基线为 master `e0e0ee1`，分支 `fix/1.2.4-root-auth`；不改版本号、不打包 release、不碰 keystore。
- 自动恢复只允许 `selected=ROOT && status=UNVERIFIED`，全进程最多一次，失败不自动重试；Boot/Listener/QS/ApplyWorker 保持零自动 shell。
- 日志来源必须真实使用 `APP_FOREGROUND`；Shizuku 选择不得产生 Root IO，不静默切换通道。
- 仅修改获批的 `shell/`、`MainActivity.kt` 的 `onStart` 接线和 `RootStatusCard.kt`；不修改 `strings.xml`、`SettingsRepository`、`build.gradle`。
- 本地结论是 JVM/静态构建证据；Root 管理器弹窗、撤权和真机 RootService 仍属外部灰度。
- 按当前任务纪律不创建 git commit，完成后将 diff 与验证证据交给 router 集成。

## Review Focus

- 首读未完成：自动恢复必须等待选择恢复，等待期间不得先构造 Root shell；由 Task 1 首读测试覆盖。
- 自动与手动并发：不得重复 openShell/bind/commit，也不得在 candidate 尚未提交时提前返回 Committed；由 Task 1 并发测试覆盖。
- 旋转/重复 `onStart`：不得重复自动恢复；由 Task 1 重入测试覆盖。
- 授权成功但 A2DP 健康失败：不得自动循环，不得改写为未授权；由 Task 1 失败测试与 Task 2 文案测试覆盖。
- Shizuku/后台路径：不得因新增前台入口而产生 Root IO；由 Task 1 Shizuku 与后台测试覆盖。

---

### Task 1: 前台单进程自动恢复

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/shell/ProviderCoordinator.kt`
- Modify: `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt`（仅新增 `onStart` 接线）
- Test: `app/src/test/java/com/chengzi/stayhifi/shell/RootForegroundRestoreTest.kt`

**Interfaces:**
- Produces: `ProviderSource.APP_FOREGROUND`。
- Produces: `suspend fun ProviderCoordinator.restoreRootFromAppForeground(owner: ProviderOwner): RootVerificationResult?`；`null` 表示不满足恢复条件或自动额度已使用。
- Consumes: 既有 `verifyRootFromForeground` candidate/auth/bind/health/commit 流程；手动入口签名不变。

- [ ] **Step 1: 写失败测试**

覆盖：冷 Root 成功且 source=`app_foreground`；重复/并发调用只恢复一次；首读前零 IO；Shizuku 零 Root IO；READY/非 UNVERIFIED 不恢复；健康失败后无自动重试但显式手动重试可用；后台 acquire 不交互；自动与手动并发只有一次物理验证和一次提交。

- [ ] **Step 2: 运行测试确认旧实现失败**

Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.RootForegroundRestoreTest'`
Expected: 编译失败，`APP_FOREGROUND` / `restoreRootFromAppForeground` 尚不存在。

- [ ] **Step 3: 实现最小恢复策略与互斥**

在 `ProviderCoordinator` 增加 `AtomicBoolean` 进程门闩和 `Mutex` 验证互斥。自动入口先认领唯一额度并等待首读，再在互斥内复查 ROOT/UNVERIFIED/SWITCHING，调用既有验证流程；手动与自动共用该互斥。`MainActivity.onStart` 用 `lifecycleScope` 调用并只记录非空结果。

- [ ] **Step 4: 运行新测试与既有 shell 回归**

Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.shell.*'`
Expected: PASS，既有授权、绑定、切换、后台零交互测试不回归。

### Task 2: GRANTED_NOT_READY 事实文案

**Files:**
- Modify: `app/src/main/java/com/chengzi/stayhifi/ui/common/RootStatusCard.kt:123-132,175-183`
- Test: `app/src/test/java/com/chengzi/stayhifi/ui/common/RootStatusCardTest.kt`

**Interfaces:**
- Consumes: `RootSnapshot(status=GRANTED_NOT_READY, granted=true, shellStatus=ROOT_SHELL, observedReason=...)`。
- Produces: 主状态“Root 已授权，服务未就绪”；最近错误展示实际 `observedReason`，不再显示“无”。

- [ ] **Step 1: 写失败测试**

断言 `rootStatusLine` 不再回落“尚未验证”，`rootLastErrorText` 返回健康失败事实；为纯 JVM 断言将后者提升为 `internal`。

- [ ] **Step 2: 运行测试确认旧实现失败**

Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.ui.common.RootStatusCardTest'`
Expected: 编译失败或断言显示旧文案/“无”。

- [ ] **Step 3: 实现两个条件分支**

仅为 `GRANTED_NOT_READY` 增加主状态与错误展示分支，不改其他 Root 状态文案、按钮动作或资源文件。

- [ ] **Step 4: 运行文案测试**

Run: `./gradlew testDebugUnitTest --tests 'com.chengzi.stayhifi.ui.common.RootStatusCardTest'`
Expected: PASS。

### Task 3: 完整验证与交付

**Files:**
- Verify only: Task 1-2 的全部修改。

- [ ] **Step 1: 检查 diff 与文件边界**

Run: `git status --short && git diff --check && git diff --stat`
Expected: 仅计划、获批源码与测试文件，无共享配置/版本号/APK。

- [ ] **Step 2: 全量 JVM 与 lint**

Run: `./gradlew testDebugUnitTest --rerun-tasks lint`
Expected: BUILD SUCCESSFUL；记录测试数量及既有 warning。

- [ ] **Step 3: minified release 构建验证**

Run: `./gradlew assembleRelease`
Expected: minified release BUILD SUCCESSFUL；不复制、不重命名、不打包发布 APK。

- [ ] **Step 4: 向 router 交付证据**

报告根因对应修改、失败→通过测试证据、完整命令结果、文件清单、未做的真机验收与 Root 管理器弹窗边界。
