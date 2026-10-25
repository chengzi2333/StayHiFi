# StayHiFi：QQ 交流群入口设计补充

- 日期：2026-10-25
- 归属：StayHiFi 1.2.1，追加到 `2026-10-25-lhdc-quality-realtime-diagnostics-design.md`
- 状态：用户已选定布局与行为，待实施
- 实施任务：Task 54；原版本/Release 任务顺延为 Task 55

## 1. 用户明确要求

- 群名称：StayHiFi 交流群。
- 群号：`1131911367`，直接显示在设置页。
- 除复制群号外，提供点击唤起 QQ 群资料/加群入口的方式。
- 随 1.2.1 一起交付，不另起版本。
- 布局由用户在三个 ASCII 候选中选择：**并入现有「关于」卡**，不新增「交流反馈」区或独立卡片。

## 2. 已验证与未验证

公开 QQ Scheme 资料给出的群名片格式为：

```text
mqqapi://card/show_pslcard?src_type=internal&version=1&uin=<群号>&card_type=group&source=qrcode
```

2026-10-25 在当前 V2505A / Android 17 真机上执行只读检查：

- `pm path com.tencent.mobileqq`：QQ 已安装。
- `cmd package resolve-activity ... -d '<上述 scheme，uin=1131911367>'`：解析到 `com.tencent.mobileqq/.activity.JumpActivity`。

这证明当前设备能把该 Intent 路由给 QQ，**不等于已经证明群资料页内容、所有 QQ 版本或其他 ROM 都可用**。最终装机仍需实际点击验证；失败路径必须保留。

群号本身不能推导出 `qm.qq.com/q/<key>` 或 `jq.qq.com/?...k=<key>` 中的官方邀请 key。本任务不伪造短链，也不要求用户提供 key；使用上面的群名片 Scheme，能力边界如实保留。

## 3. 最终布局（用户拍板）

在设置页现有「关于」卡内，位于 StayHiFi/GPL 说明之后：

```text
关于
┌──────────────────────────┐
│ StayHiFi 音质常驻        │
│ GPL-3.0 开源软件…        │
│                          │
│ QQ 交流群：1131911367    │
│             复制  打开 QQ │
└──────────────────────────┘
```

实现约束：

- 不新增 SectionTitle、卡片、图标或第三方库。
- 群号单独一行；操作按钮一行右对齐，避免窄屏把长群号与两个按钮挤在一行。
- 使用现有 Material3 `TextButton`/`Text` 和主题颜色，保留默认最小触控区域。
- 设置页既有 Preview 同步可见，浅色/深色不另造颜色。

## 4. 点击行为

### 复制

- 动作：`SettingsAction.CopyQqGroupNumber`。
- 写入剪贴板：`1131911367`。
- Toast：`QQ 群号已复制`。

### 打开 QQ

- 动作：`SettingsAction.OpenQqGroup`。
- 使用 `Intent.ACTION_VIEW` + §2 的完整 URI，`setPackage("com.tencent.mobileqq")`，避免落到无关应用。
- 直接 `startActivity` 并捕获异常；不调用 `resolveActivity`，因此不需要新增 `<queries>` 或包可见性声明。
- 成功只表示 Intent 已发出，日志写「已请求打开 QQ 群资料页」，不得写成「已加群」。
- `ActivityNotFoundException` 与 `SecurityException`：复用复制动作，Toast「打开 QQ 失败，群号已复制」。
- 不做浏览器回退、不自动发送入群申请、不自动点击确认、不读取 QQ 内容。

## 5. 文件与测试

- 新建 `util/QqGroup.kt`：集中群号、QQ 包名和群名片 URI，避免 UI 与 Activity 各写一份。
- 修改 `SettingsScreen.kt`：两个 SettingsAction + 关于卡内的选定布局。
- 修改 `MainActivity.kt`：复制/打开与失败回退。
- 新建 `QqGroupTest.kt`：先验证旧代码缺少该组件，再验证群号、包名和完整 URI 精确匹配。
- 运行全量 JVM 单测和 `assembleDebug`；主 agent 最终真机点击验证成功路径。
- 没有卸载/禁用 QQ 来制造失败环境；失败回退由代码审查覆盖，不能声称已做无 QQ 真机验证。

## 6. 非目标与边界

- 不增加网络权限、QQ SDK、官方邀请 key、浏览器短链或自动加群能力。
- 不改变现有「零数据采集、零网络上传」声明：只有用户主动点击时才把公开群号通过 Intent 交给 QQ；App 自身不发送网络请求。
- 不修改正在进行的 Task 52/53 功能逻辑；Task 54 必须等 Task 53 审查完成后才施工，避免并发修改 MainActivity。
- Release 文案最多三条，合并为：

```text
- 修复部分手机不显示码率档位的问题。
- 新增 QQ 交流群入口，方便反馈问题。
- 改进诊断日志；实时码率仍取决于系统支持，部分机型暂无法显示。
```
