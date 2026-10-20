# LHDC Auto（暂名）设计文档 —— 蓝牙耳机参数自动拉满

日期：2026-10-20
状态：PoC 已验证，本文档为完整版设计

## 1. 意图与背景

用户的 OPPO Enco X3（LHDC V5 / 1000kbps / 192kHz / 24bit）连接 iQOO 15 后，
高品质参数只能在开发者选项里手动设置，且断连重连、关闭开发者选项都会回滚到
48kHz / 自适应码率。用户希望每天半小时~一小时的听歌时间跑满硬件上限。

PoC（本仓库 `app/` 现状）已在真机验证：

- shell(uid 2000) 身份可直接调用 `BluetoothA2dp.getCodecStatus()` /
  `setCodecConfigPreference()`，无需 root，开发者选项关闭也生效
- Shizuku 与 Stellar（Shizuku 二开）均可作为特权载体；Shizuku 官方版在
  OriginOS 7 上管理器应用识别损坏，但授权链路本身可用（此前失败是 PoC
  少声明 `rikka.shizuku.ShizukuProvider`，已修复）
- 写入后约 1~2 秒生效（读回需延迟 + 重试）
- Enco X3 黄金配置（完整 9 字段）：codecType=10(LHDC V5), priority=1000000,
  sampleRate=32(192k bitmask), bitsPerSample=2(24bit), channelMode=2,
  cs1=50888712(0x03088008), cs2=0, cs3=1543503872, cs4=0
- 能力掩码可读：该耳机 rate mask=43(44.1k~192k)、bits mask=3(16/24bit)

## 2. 目标 / 非目标 / 反目标

**目标（v1）**

- 耳机连上后自动把 A2DP codec 参数拉满，全程无需用户操作
- 通用化：任意"手机+耳机"组合可用。不依赖预知具体机型的参数，
  通过"能力探测 + 引导录制"两条路径适配（见 §4.2）
- 无 root；Shizuku 或 Stellar 任一载体即可
- Material Design 3 + Compose，简洁优雅，带新用户引导页
- 外围功能（常驻参数通知 / 应用结果通知 / QS 磁贴）全部为设置项开关，
  **默认全部关闭**

**非目标（v1）**

- 不突破硬件已协商的能力上限（不 patch 蓝牙栈、不解锁隐藏 codec）
- 不做 EQ / 音频处理
- 不做 LE Audio (LC3) 路径（PoC 未验证，经典 A2DP 先行）
- **不做 OEM 应用商店上架**（华为/小米/OPPO/vivo/应用宝，需软著与隐私
  合规审查）；分发渠道仅：酷安 + GitHub/Gitee 侧载
- 不做周期轮询式"守护"（耗电）；只响应连接事件

**反目标**

- 默认开启任何通知/常驻 UI（骚扰）
- 常驻前台服务保活（耗电、易被 OriginOS 杀且观感差）

## 3. 约束

- OriginOS 7：杀后台严格 → 引导页必须引导用户完成自启动/电池白名单设置
- Shizuku/Stellar 重启手机后服务停止 → 需要"开机检测 + 一键修复引导"
- codecType / cs1 质量档为厂商私有值，无法跨设备预测 → 必须记录-回放
- vivo 安装器每次 adb install 需手动确认（仅开发期烦恼）
- 隐式广播限制：`BluetoothDevice.ACTION_ACL_CONNECTED` 在豁免名单内，
  可在 manifest 注册接收；A2DP profile 级广播不可，只能运行时注册

## 4. 关键设计决策

### 4.1 技术栈

Kotlin + Jetpack Compose + Material 3（动态取色）。`shell` 层继续复用
PoC 验证过的 Shizuku UserService 模式（`ICodecService` AIDL + 反射）。
Shizuku API 依赖 `dev.rikka.shizuku:api:13.1.5` + `:provider`，
manifest 必须声明 `rikka.shizuku.ShizukuProvider`（PoC 踩过的坑）。

### 4.2 通用化适配策略（核心）

每条设备档案 `DeviceProfile` 存一份完整 `BluetoothCodecConfig` 字段。
参数来源按优先级：

1. **用户录制模板**（最可靠）：引导用户手动在开发者选项调好一次 →
   App 抓取 `getCodecStatus().getCodecConfig()` 存档 → 之后自动回放。
   PoC 已验证该路径。
2. **自动拉满**（免录制的快捷路径）：保持系统当前已协商的 codecType 不变，
   从 `getCodecsSelectableCapabilities()` 取该 codec 的能力掩码，
   采样率/位深取最高位；priority 置 1000000(HIGHEST)；cs1 按 codec 分类：
   - LDAC：AOSP 标准质量值（实现时以源码常量为准）
   - 内置已知设备预置表（首个条目即 Enco X3 / LHDC V5 的
     cs1=0x03088008 等，随用户反馈积累）。**匹配规则：codecType +
     设备名模糊匹配双重条件，且首次对某台设备应用预置时必须弹窗
     征得用户同意**（"识别到 Enco X3，应用已知参数？"），防止厂商
     私有值误写到同名 codec 的其他耳机上
   - 未知 codec：cs1 保持当前值不动，只拉采样率/位深，并在 UI 提示
     "码率档位未知，建议使用录制向导"
3. 若耳机当前协商为 SBC/AAC 但能力列表里有更优 vendor codec，
   v1 不主动切换 codec（风险：厂商私有值），仅提示用户。

### 4.2.1 降级协商

"拉满"是上限而非强制：写入后读回不匹配时，按采样率降档重试
（192k → 96k → 48k，位深 24 → 16），每档最多重试 2 次，
直到读回一致或触底报错。防止个别组合被协议栈拒绝后陷入无效重试。

### 4.3 触发与写入流程

```
ACL_CONNECTED 广播 (manifest 注册, 豁免名单内)
  → ApplyWorker (goAsync, 短任务, 无常驻服务)
    → 过滤：该设备 A2DP 已连接? 有 DeviceProfile 且启用自动?
    → 等 Shizuku binder (最多 3s) → bindUserService
    → 延迟 1.5s（等协议栈协商稳定）
    → writeConfig(存档参数)
    → 读回验证，不匹配则 1s 间隔重试，最多 3 次
    → 按设置决定是否发结果通知
    → 进程退出（零驻留耗电）
```

Shizuku 未运行时：发一条"点我一键修复"通知（跳转引导页），不静默失败。

### 4.4 特权载体抽象

`ShellProvider` 接口统一 Shizuku / Stellar。**背景（2026-10 论坛信息 +
实测确认）：各家 OEM 新系统底层升级 Android 17 后 Shizuku 官方版失效；
Stellar 是专门针对 Android 17 兼容的 Shizuku 二开**。因此载体优先级：
**Android 17+ 设备上 Stellar 为主力载体**，Shizuku 为旧系统兼容通道。
开工第一件事：验证 Stellar 的 provider authority 与 API 兼容性
（包名 roro.stellar.manager，Provider 类 StellarManagerProvider，authority
与官方不同）；若 authority 不同，客户端层做双通道探测。

### 4.5.1 常驻参数通知的实现方式

该开关打开时启用一个**可选前台服务（FGS）**：连接/写入/系统回滚等事件
驱动更新 + 低频轮询（30s）兜底，通知实时显示当前 codec/采样率/码率。
开关关闭时服务立即销毁，零驻留。反目标禁止的是"强制常驻保活"，
用户显式开启的可选 FGS 不在其列；开关描述里注明耗电提示。
（Builder check 指出的文档矛盾，以此条为准）

- **Onboarding**（首次启动 / 未完成设置时）：
  欢迎 → 载体检测与安装/启动引导（Shizuku/Stellar 状态卡片 + 图文步骤）
  → 授权 → 连接耳机提示 → 参数来源选择（自动拉满 / 录制向导）
  → 保活设置引导（自启动、电池白名单）→ 完成
- **首页**：当前状态大卡（设备名、codec、采样率、位深、码率档位，实时读）
  + 自动模式总开关 + "立即拉满"手动按钮
- **设备页**：已连接过的设备列表，每设备独立开关与参数档案查看/重录
- **设置页**：三个外围功能开关（默认关）、保活引导入口、运行日志查看、
  关于（含 PoC 验证说明）
- **QS Tile**：点击 = 立即拉满当前设备；长按 = 打开 App（可选开关内启用）

### 4.6 数据层

DataStore Preferences：设置项 + 设备档案（JSON 序列化，设备数量小，
不需要 Room）。黄金配置内置为 Enco X3 默认值，录制后覆盖。

## 5. 错误与边界

| 场景 | 行为 |
|---|---|
| Shizuku/Stellar 未运行 | 通知引导修复；首页状态卡显示红色警示 |
| 写入后读回不匹配 ×3 | 结果通知"拉满失败，点击重试" |
| 连接的是无档案新设备 | 按全局默认策略：自动拉满（cs1 不动）并通知结果 |
| 通话/游戏抢占导致中途回滚 | v1 不管；重连或手动触发恢复（后续版本候选） |
| OTA 后反射签名变化 | 反射调用包 try/catch，失败写入运行日志并在首页提示 |
| 双设备同时连接 | 只对触发事件的设备操作 |

## 6. 测试计划

真机（iQOO 15 + Enco X3）矩阵：
开发者选项开/关 × 断连重连 × 重启手机后 Shizuku 修复流程 ×
Shizuku/Stellar 两种载体 × 杀后台设置前/后。
另需走查：全新安装无权限环境的 Onboarding 每一步。

## 8. 发布与推广（0 → 1 → 上线全程）

这是作者的第一个完整项目，目标是跑通"开发 → 开源 → 发布 → 推广"全流程。
（本节经过独立"发布经理"视角挑刺后修订，原稿的时序与渠道错误已修正）

### 8.1 命名与品牌

- 定位：面向**对蓝牙音质敏感的听音人群**的痛点工具（核心叙事："厂家不
  全开参数、开发者选项每次手动调、断连就回滚"），而非"拉满"噱头
- **正式名称不使用 LHDC / OPPO / vivo 等商标词**（LHDC 为 Savitech 商标）
- **已定稿（2026-10-20 作者拍板）：中文名「音质满格」，英文名 FullBars**。
  包名 `com.chengzi.btmax`（功能词，不嵌品牌——品牌以后可变，包名永久）；
  GitHub 仓库名 `FullBars`（仓库名可改，GitHub 会自动跳转旧名）
- 命名统一后全渠道一致使用——GPL 防不住 APK 被换皮搬运，能守住的是
  名字；README 写明"官方渠道仅此两处（酷安 / GitHub）"
- Icon：MD3 adaptive icon 纯矢量（前景 VectorDrawable + 背景色），
  蓝牙符号 + 上行箭头意象，不用位图素材（版权问题与缩放糊）
- 一句话定位："耳机一连，音质满血"

### 8.2 仓库与开源

- GitHub 公开仓库（chengzi2333）；**Gitee 同步镜像**（国内可达性，
  Release 同步 APK，页面写清与 GitHub 一致的 SHA-256）
- License：**GPL-3.0-or-later**（作者已确认）；仓库加 `NOTICE` 标注
  rikka.shizuku:api 为 Apache-2.0（GPLv3 兼容）
- GPL 义务：**每个 release tag 必须包含构建该 APK 的完整对应源码**
- **设备档案反馈闭环**（产品从"只支持 Enco X3"走向通用的唯一路径，
  §4.2 预置表的来源）：仓库 `device-profiles/` JSON 目录 +
  `CONTRIBUTING.md`（档案 9 字段格式说明）+ GitHub issue 模板
  「设备档案提交」
- 仓库卫生：README 中英双语 + 截图、LICENSE、.gitignore（keystore /
  local.properties 永不入库）、GitHub Actions 仅做 debug 构建检查 +
  **versionCode 单调递增校验**；release 本地签名，密钥不进 CI
- README 声明与 Shizuku / Stellar / OPPO / vivo 无隶属关系；
  隐私"零采集"声明页（社区发帖必被问）

### 8.3 签名与发布

- keystore 生成命令写死在 M5 验收里，必须显式参数：
  `keytool -genkeypair -v -keyalg RSA -keysize 2048 -validity 36500`
  （**不指定 -validity 默认只有 90 天，密钥过期 = 已装用户永远无法覆盖
  更新**，新手最致命的坑）；生成后立即离线双备份 + 密码入密码管理器
- **soak 在打 tag 之前**：M4 通过后以 0.9.x（GitHub pre-release、
  正式 keystore 签名）自用 3 天，无 P0 问题才打 v1.0.0 正式 tag
- `versionCode` 每次 release 单调递增（M5 验收项，覆盖安装只认它）
- 发布前 `apksigner verify --print-certs` 确认 v2+ 签名生效
- 渠道：**酷安**（主）+ GitHub/Gitee Releases（侧载）；
  F-Droid / Google Play / OEM 商店不做

### 8.4 自媒体推广（已确认：酷安 + B站 + 小红书）

- **酷安首发**：图文评测帖；帖子挂**酷安应用页**链接（不是 GitHub
  直链，国内打开率低）；作者**必须亲自逐条回复评论区**（该社区铁律，
  决定评分与二次传播）
- **B站**：1~2 分钟视频而非 30 秒——前半痛点对比录屏（48k 回滚 vs
  192k 拉满），后半 30 秒预回答可预测的质疑（"只写 A2DP 配置、不 patch
  蓝牙栈、不常驻耗电、Shizuku/Stellar 是什么、重启后怎么一键修复"）；
  提前写好 FAQ 置顶评论
- **小红书**：痛点叙事（"买的耳机一直在被手机偷偷降音质？"），**不挂
  外链**（平台不跳转），文案写"酷安搜索『应用名』"
- 所有帖子的 CTA："**提交你的耳机型号帮我适配**"——喂 §8.2 的档案闭环，
  也是首发帖最好的互动引子
- 素材清单：icon、≥4 张截图（首页状态卡 / 引导页 / 写入成功 / 设置）、
  1~2min 录屏、before/after 参数对比图、FAQ 文案
- 预期管理：小红书非玩机主战场，放低预期；真实痛点叙事 > 参数堆砌

### 8.5 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 | 核心链路 App 化（自动触发 + 写入 + 验证） | 插拔耳机自动生效 |
| M2 | Compose M3 UI + Onboarding 完整 | 全新安装走完引导可用 |
| M3 | 三个外围功能开关 | 开关各自生效，默认全关 |
| M3.5 | **酷安开发者实名注册 + 养号**（审核有周期，不能卡在首发当天） | 账号可发帖 |
| M4 | 真机矩阵测试（§6） | 全绿 |
| M4.5 | **soak：0.9.x pre-release（正式签名）自用 3 天** | 无 P0 问题 |
| M5 | 品牌+包名定稿 → 建仓 → keystore（-validity 36500）→ v1.0.0 | Release 可安装、apksigner 验签通过、tag 含完整源码 |
| M6 | 推广发帖 | 酷安+B站+小红书，素材齐备，FAQ 就位 |

## 9. 实现者自行决定

- Compose / AGP / Kotlin 具体版本（仓库现有：AGP 9.4.1 + Gradle 9.8.2 +
  compileSdk 37，compose BOM 取构建期最新稳定版）
- Icon 具体绘制（品牌已定「音质满格 / FullBars」，建议"满格信号 + 蓝牙"
  意象组合）
- 代码内部结构细节、主题色取色方式（动态取色优先）
