# Task 19：FGS 常驻通知静态文案修复

## 问题
耳机已连接、首页正常，但「常驻通知显示参数详情」开关**关闭**时，常驻通知正文永远显示「等待耳机连接」。

## 根因
`app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt` 的 `refreshNotification()`：`!notifyPersistent` 分支错误复用了 `TEXT_WAITING`（「等待耳机连接」），与 A8 验收（静默通知应显示「音质满格运行中」）不符。

## 修改
文件：`app/src/main/java/com/chengzi/stayhifi/trigger/ListenerService.kt`

1. 新增常量 `TEXT_RUNNING = "音质满格运行中"`；`refreshNotification()` 中 `!notifyPersistent` 分支改用 `TEXT_RUNNING`。`TEXT_WAITING` 仅保留给「开关开但当前无连接/读不到参数」场景（`readConnectedSummary() ?: TEXT_WAITING`）。
2. 类头注释（37-41 行）同步重写：开关关 = 静态「音质满格运行中」不轮询；开关开 = 事件驱动 + 30s 轮询，已连接显示参数摘要、未连接显示「等待耳机连接」。

## 审查记录（任务第 3、4 点）
- **onConnected / ACL_DISCONNECTED 中的 refreshNotification() 调用**：开关关闭时走 `!notifyPersistent` 分支，只产出同一静态文案 `TEXT_RUNNING` 并重复 `startForeground`（同一 ID、同内容），无副作用，未改动。
- **同文件其他文案/状态一致性**：
  - `onCreate()` 首次 `startForeground` 仍用 `TEXT_WAITING`（第 80 行）。由于 `@Volatile notifyPersistent` 默认值即 `false`，且 `settings.notifyPersistent` 的 collect 会在服务创建后立即触发并刷新为正确文案，该初始值只是极短暂瞬态。保守起见未改，如需彻底消除瞬态可把初始值也换成 `TEXT_RUNNING`。
  - 其余日志文案与行为一致，未发现其他不一致。

## 验证
- `JAVA_HOME=/opt/homebrew/opt/openjdk@27 ./gradlew testDebugUnitTest`：53/53 全绿（10+12+15+2+4+5+5，0 failures / 0 errors）
- `assembleDebug`：BUILD SUCCESSFUL

## Commit
见 git log（单 commit，中文信息）。
