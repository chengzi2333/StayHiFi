# AGENTS.md — StayHiFi 开发约定

面向在本仓库工作的 Agent（以及人类贡献者）。这些规则来自实际返工记录，**不是建议，是验收标准**。

## 构建与验证

```bash
./gradlew :app:testDebugUnitTest          # 单测
./gradlew :app:compileDebugKotlin         # 快速编译
STAYHIFI_KEYSTORE="$HOME/.stayhifi-keys/upload-keystore.jks" \
STAYHIFI_STORE_PASSWORD=... STAYHIFI_KEY_ALIAS=stayhifi-upload \
STAYHIFI_KEY_PASSWORD=... ./gradlew :app:assembleRelease
```

- 设备上装的是**正式签名**的包：debug 包装不上去（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），验证必须出 release 包，别卸载重装（会丢用户档案）。
- UI 改动必须真机截图自检：折行、遮挡、等宽、深浅色对比。截图检查通过后再交付。
- 临时验证代码（强制进入引导、强制弹窗等）用完必须回滚，并在提交前 `grep TEMP-VERIFY` 确认为空。

## 视觉规范

### 尺寸

- 同层级按钮必须**同高同宽**：同一张卡里主操作满宽、高度 ≥48dp；页面主 CTA ≥56dp。
- 一行并排两个按钮时，用 `weight(1f)` 等分，不要一个胶囊一个文字按钮。
- 行内点击热区 ≥44dp；设置行高 ≥56dp。
- 卡片内边距 16dp；页面左右边距 20dp（引导页 24dp）。

### 列表页面

- 设置类页面统一用「**分组标签 + 扁平行 + 0.5dp 分隔线 + 右侧箭头或开关**」（GKD 式），**不要一卡一行**。
- 右箭头统一 `Icons.Default.KeyboardArrowRight` 20dp，次要色；开关用 Material3 `Switch`。
- 二级页顶部用 `SubPageTopBar`，返回箭头与标题同行。

### 层级与信息密度

- 一个页面只有一个视觉主体。状态卡 + 居中提示重复表达同一件事 = 直接删掉一个。
- 空态 = 视觉主体 + 一行说明 + 一个可选动作，**禁止口号**（如「设备已就位」「下一步就开始自动拉满音质」）。
- 不加无功能含义的装饰：徽章（推荐/已选）、角标、说明性小字，除非它表达真实状态或数据。

## 文案规范

- 副标题最多一句，写**事实或价值**；不写解释性旁白。
  - ✅「下拉面板一键拉满」
  - ❌「系统把这些开关藏得比较深，跟着去一趟就行；不设置也能用……」
  - ❌「常驻通知是系统要求的运行凭证，不能关掉；下面只控制它的内容和额外提醒。」
- 二级页正文直接从第一项开始，不要再加导语段落。
- 不用「我们」「大家」「其实」这类填充词；不堆叠同义词。
- 面向用户的所有表述必须一眼能懂，不出现 DSP、码流、BSS 等实现术语（技术细节只进日志与说明页）。
- 机型/能力结论必须有真机证据；没有证据写「还没确认」，不推测。

## 代码约定

- 图标：内联 `ImageVector`（`ui/nav/DockIcons.kt`），**禁止引入 `material-icons-extended`**（包体积 30M→77M）。仅可用 `material-icons-core` 的 `Icons.Default.*`（Home/Settings/KeyboardArrowRight/Refresh 等）。
- 颜色、字号只用 `MaterialTheme` token，禁止硬编码色值（预览与图形除外）。
- 设置项读写统一走 `SettingsRepository`（DataStore），不要在 UI 层直接落盘。
- 新增开关默认值取「最不打扰」的一侧（通知类默认关）；已发布开关不得直接删除，会让老用户无法关闭——收进二级页或保留开关。
- 纯逻辑（解析、判定、格式化）必须可在 JVM 单测，UI 层只做展示与上抛。

## 文档

- 影响用户可见行为的改动，写进 `docs/dev/` 下当天文件；数据/证据类结论附机型与日志字段。

## 交互边界（返工过的坑）

- **不提供任何安装渠道**：App 不跳酷安、不跳应用商店、不给下载链接。授权工具由用户自行安装，页面只给「启动 / 授权」两个动作。
- 检测第三方 App 是否存在时，不要只比对单一包名（Shizuku 生态有 next/Stellar 等分支，包名可能不同）；优先用运行时事实（如 `Shizuku.pingBinder()`）判断。
- 二级页/子页面已经在外层 `Scaffold` 内容区内时，**必须**给内层 `Scaffold` 传 `contentWindowInsets = WindowInsets(0)`，否则状态栏内边距会叠加，标题整体下移。
- `painterResource` 不支持 adaptive-icon XML（会抛 `IllegalArgumentException`）；需要 App 图标时用「背景色 + `ic_launcher_foreground` 位图」拼。
- 图片资源按内容裁掉纯色留白再用，避免「图里一堆黑边、二维码显得很小」。
- 状态信息只出现一次：同一页面里状态卡 + 徽章 + 中心文案不要重复表达同一件事。
- `drawable-nodpi` 里的位图不会随密度缩放：只给 `width()` 会被 `contentScale = Fit` 按「像素高」缩回去，必须用 `aspectRatio(宽/高)` 锁比例再 `fillMaxWidth()`。
- 多级页面（设置 → 关于 → 免责声明）的返回要回到**上一级**，不是直接回主页；用 `backTo` 记录来源页。

## 功耗与日志

- 后台不做重活：任何 dumpsys / 特权采样必须在 App 前台（`StayHiFiApp.isAppForeground`）时进行；后台只允许轻量广播响应。
- 常驻服务（FGS）里不要放"每次重建都跑一遍"的全量任务；必须用持久时间戳节流（参考 `ListenerService.retriggerIfAlreadyConnected` 的 120s 窗口）。
- 日志体积：单行正文 ≤220 字符（超出标注截断字数），连续重复 10s 内折叠为「重复 N 次」，导出报告总预算 6000 字符（超出丢最旧并显式标记）。新增日志前先想想是否该进这个窗口。

## 特权通道与进程（实测踩坑）

- **不要按 lease 生命周期 bind/unbind Shizuku UserService**：每次 `acquire` 新建 provider 再 `unbind(remove=true)` 等于「拉起一个 `:codec` 进程再丢弃」。实测首页 3s 轮询 45 秒新增 15 个孤儿进程（PPID=1，每个约 133MB），是发热/耗电主因。现在 `ProviderCoordinator` 复用单例 provider，最后一个 lease 释放后延迟 90s 解绑。
- **不要假设 `unbindUserService(remove=true)` 会杀进程**：vivo/OriginOS 上只摘服务记录。`CodecService` 带空闲哨兵（120s 无 IPC 自杀）兜底，改这块时别删。
- 判断「有无泄漏」的标准动作：`adb shell ps -A | grep -c ':codec'`，首页停留 90s 与退后台 150s 各看一次（期望 1 和 0）。
- 首页实况刷新是自适应的（播放 3s / 静止 15s）；新增需要高频刷新的状态时，别直接把间隔改成常量。
- 重型 dumpsys（`audio` / `media.audio_flinger`）只在蓝牙段拿不到码率时才跑，见 `CodecService.dumpRealtimeDiagnostics`。
