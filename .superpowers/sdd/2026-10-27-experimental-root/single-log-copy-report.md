# 统一排障日志复制入口 — 修正报告

日期：2026-10-28
分支：worktree-stayhifi-next-codec-root（基线 d5466f1）

## 背景与第一性原理

日志唯一用途是让用户复制给作者排障。用户不应面对任何选择或疑问，因此产品上只保留
一个 UI 按钮「复制日志」，点击直接生成脱敏且排障信息完整的日志。旧的并列「复制反馈
日志」与原始未脱敏复制入口删除。

## 改动

### 代码

- `app/src/main/java/com/chengzi/stayhifi/ui/logs/LogsScreen.kt`
  - `LogsAction` 移除 `CopyFeedback`，只保留 `Copy / Clear / Back`。
  - 日志页删除「复制反馈日志」按钮，只剩「复制日志」+「清空」；底部 Row 仍为
    `horizontalScroll`，小屏不裁切动作。注释同步更新（说明单一复制入口的产品理由）。
- `app/src/main/java/com/chengzi/stayhifi/MainActivity.kt`
  - `onLogsAction` 删除 `CopyFeedback` 分支。
  - 删除旧的未脱敏 `copyLogs()`（MANUAL 留证 + drain + Root flush + 原文 header +
    原文复制的整段路径），避免死代码/未来误接。
  - 原 `copyFeedbackLogs()`（Task B7 脱敏路径）更名为 `copyLogs()`，成为唯一复制
    实现：MANUAL 留证、suppressed drain、`rootTransport.logger.flushSuppressed()`、
    `FeedbackLogSanitizer`、`RootFeedbackFields.header` + app/device/display +
    `RootFeedbackFields.template()` 单行 header、1 header + 最多 199 行预算全部保留。
  - Clipboard label 改为「StayHiFi 日志」，Toast 改为「已复制日志，可直接粘贴给作者
    排障」，错误日志统一「复制日志失败」——不再出现原始/反馈之分。

### Spec

- `docs/superpowers/specs/2026-10-27-codec-identity-aptx-root-design.md` §12 第 7 条：
  由「保留现有复制日志 + 新增复制反馈日志」改为「日志页只有『复制日志』一个复制
  入口，输出即统一脱敏且排障信息完整」，并标注 2026-10-28 修订理由。
  历史 plan（`docs/superpowers/plans/`）按边界未重写。

### 测试

- 全仓 grep 确认：`app/src/test` 与 `app/src/androidTest` 无任何用例引用
  `LogsAction` / `CopyFeedback` / `copyLogs` / 「复制反馈日志」，也无
  「原始复制保持不脱敏」类断言，故无测试需要修改；`CopyFeedback` 的消亡体现为
  编译期封闭（sealed 分支穷举的 `when` 已同步删分支）。

### 明确未改（边界）

- `LogStore`、诊断字段、Root 字段、日志预算（200 行 / 199 行窗口）、
  `FeedbackLogSanitizer`、`Provider/Root` 其他行为均未动。

## 验证

- 相关测试（变更前基线绿 → 变更后仍绿）：
  `FeedbackLogSanitizerTest`、`LogBufferTest` — BUILD SUCCESSFUL。
- 全量：`JAVA_HOME=/opt/homebrew/opt/openjdk@27 ./gradlew test assembleDebug`
  — BUILD SUCCESSFUL；testDebugUnitTest 共 468 个用例，0 失败；
  `app-debug.apk` 于构建时刻（13:14）新生成。

## Concerns

- Toast 不再回显复制条数（原脱敏路径本就如此，保持一致）；用户如需确认条数可看
  日志页「N 条」。
- 历史 plan 文档仍描述两个入口，属按边界保留的历史记录，不影响实现。
