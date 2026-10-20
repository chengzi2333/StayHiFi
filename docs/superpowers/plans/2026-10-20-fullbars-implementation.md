# 「音质满格 FullBars」Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 PoC 验证过的"蓝牙 codec 参数自动拉满"能力做成一个 MD3 通用 Android App（com.chengzi.btmax），含自动触发、Onboarding、三个可选外围功能。

**Architecture:** Kotlin + Compose M3 单模块 App。四层：`codec`（纯逻辑，JVM 可测）→ `shell`（Shizuku/Stellar UserService，反射调 `BluetoothA2dp` 隐藏 API，移植自 PoC）→ `trigger`（ACL_CONNECTED 广播 + goAsync 短任务，无常驻服务）→ `ui`（Compose M3：Onboarding / 首页 / 设备 / 设置 / 日志）。

**Tech Stack:** Kotlin, Jetpack Compose (Material 3, 动态取色), DataStore Preferences, Shizuku API 13.1.5, JUnit4（纯逻辑单测）, AGP 9.4.1 / Gradle 9.8.2 / compileSdk 37（沿用现有环境）

**Spec:** `docs/superpowers/specs/2026-10-20-lhdc-auto-design.md`（含 §8 发布运营附录，M3.5~M6 为用户本人执行的清单，不在本计划任务内）

## Global Constraints

- 包名 `com.chengzi.btmax`；应用名「音质满格」（FullBars）；**包名永久不可改**
- minSdk 29 / targetSdk 37 / compileSdk 37；AGP 9.4.1 默认关 aidl/buildConfig，需显式 `buildFeatures { aidl true; buildConfig true; compose true }`
- Shizuku 依赖 `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`；manifest **必须**声明 `rikka.shizuku.ShizukuProvider`（authority `${applicationId}.shizuku`, exported=true, permission=`android.permission.INTERACT_ACROSS_USERS_FULL`）——PoC 踩过的坑
- Enco X3 黄金配置（9 字段）：type=10, priority=1000000, rate=32, bits=2, channel=2, cs1=50888712, cs2=0, cs3=1543503872, cs4=0
- 采样率/位深为 bitmask：rate 1/2/4/8/16/32 = 44.1k/48k/88.2k/96k/176.4k/192k；bits 1/2/4 = 16/24/32bit
- 三个外围功能（常驻通知/结果通知/QS Tile）**默认全部关闭**；自动模式在 Onboarding 完成后默认开
- 不主动切换 codecType；不写厂商未知 cs1；降级协商 192k→96k→48k
- License GPL-3.0-or-later；keystore / local.properties 永不入库
- 反射调用必须全部 try/catch 并写运行日志（OTA 后签名可能变化）
- 真机验证命令前缀：`ADB=~/Library/Android/sdk/platform-tools/adb`；安装时用户需在手机上点确认（OriginOS 限制）

## Review Focus

1. **Shizuku 已授权但 server 未运行时耳机连上** → 应发"一键修复"通知而非静默失败（Task 4 测试）
2. **App 进程被杀后耳机连接** → manifest 静态 receiver 冷启动路径必须能拉起（Task 4 真机验证：`adb shell am force-stop` 后连耳机）
3. **预置表误匹配**：同 codecType 但不同厂商耳机 → 必须弹确认窗，不得静默写入（Task 2 单测 + Task 5 UI）
4. **写入后系统 0.5s 内二次协商回滚** → 读回验证窗口 ≥3 次、间隔 1s（PoC 实测第 1~2 拍常读到旧值）（Task 3/4）
5. **OriginOS 未开自启动/电池白名单** → Onboarding 必须检测并引导，否则自动触发被系统掐死（Task 5）

---

### Task 1: 项目骨架转型（PoC → FullBars）+ git 基线

**Files:**
- Modify: `settings.gradle`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`
- Create: `.gitignore`, `app/src/main/java/com/chengzi/btmax/`（新包结构）
- Delete: `app/src/main/java/com/chengzi/lhdcpoc/`、`app/src/main/aidl/com/chengzi/lhdcpoc/`（git 历史保留）

**Interfaces:**
- Produces: 可构建的空壳 App（label「音质满格」，MainActivity 占位 TextView）；git 仓库两次提交（PoC 基线 + 骨架）

- [ ] **Step 1: git init 并提交 PoC 基线**

```bash
cd /Users/chengzi/Documents/lhdc-poc && git init
# .gitignore: .gradle/ build/ local.properties *.jks *.keystore .idea/ *.iml .DS_Store
git add -A && git commit -m "chore: PoC baseline (validated on iQOO 15 + Enco X3)"
```

- [ ] **Step 2: 改造为 Kotlin + Compose 骨架**

`app/build.gradle`：applicationId/namespace 改 `com.chengzi.btmax`；加 `org.jetbrains.kotlin.android` 插件（版本与 AGP 9.4.1 兼容，构建期解析）；`buildFeatures { aidl true; buildConfig true; compose true }`；依赖：compose BOM（最新稳定）+ material3 + activity-compose + lifecycle-viewmodel-compose + datastore-preferences + 保留 shizuku api/provider。删除 lhdcpoc 源码与 aidl，新建 btmax 包占位 MainActivity（`setContent { Text("音质满格") }`）。Manifest：applicationId 换新、label「音质满格」、**加上 ShizukuProvider 声明**（见 Global Constraints）。

- [ ] **Step 3: 构建 + 安装验证**

Run: `gradle assembleDebug && $ADB install -r app/build/outputs/apk/debug/app-debug.apk && $ADB shell am start -n com.chengzi.btmax/.MainActivity`
Expected: BUILD SUCCESSFUL；手机显示「音质满格」占位页

- [ ] **Step 4: Commit**

`git commit -m "feat: FullBars skeleton (Compose M3, package com.chengzi.btmax)"`

---

### Task 2: codec 纯逻辑层（JVM 单测覆盖）

**Files:**
- Create: `app/src/main/java/com/chengzi/btmax/codec/CodecConfig.kt`, `DeviceProfile.kt`, `Presets.kt`, `Negotiation.kt`
- Test: `app/src/test/java/com/chengzi/btmax/codec/`（JUnit4，无 Android 依赖——mask 解码/JSON 用 `org.json` 需要 android，改用 kotlinx-serialization-json 或手写 escaping；**决定：kotlinx-serialization-json**，JVM 可测）

**Interfaces:**
- Produces（后续任务依赖的精确签名）:
  - `data class CodecConfig(codecType:Int, priority:Int, sampleRate:Int, bitsPerSample:Int, channelMode:Int, cs1:Long, cs2:Long, cs3:Long, cs4:Long)` + `fun toJson():String` / `companion object { fun fromJson(String):CodecConfig }` + `fun summary():String`（如 "LHDC·192kHz·24bit"）
  - `object Masks { fun rateHz(mask:Int):String; fun bitsDepth(mask:Int):String; fun codecName(type:Int):String }`
  - `data class DeviceProfile(mac:String, name:String, config:CodecConfig, source:Source, autoApply:Boolean)`，`enum class Source { AUTO, CAPTURED, PRESET }`
  - `object Presets { fun match(name:String, codecType:Int):CodecConfig? }`（唯一内置条目：name 含 "Enco X3" 且 codecType==10 → 黄金配置）
  - `object Negotiation { fun descending(base:CodecConfig):List<CodecConfig> }`（采样率 32→8→2、位深 2→1 的降序候选，其余字段不变）

- [ ] **Step 1: 写失败单测**

```kotlin
// CodecConfigTest: golden 9 字段 toJson→fromJson 往返相等；summary 含 "192kHz"
// MasksTest: rateHz(43)="44.1k+48k+96k+192k" 不行——rateHz 只解单值；
//   rateHz(32)="192kHz"; bitsDepth(2)="24bit"; codecName(10)="LHDC?" vendor 显示 "vendor(10)"
// PresetsTest: match("OPPO Enco X3",10)!=null; match("Enco X3",4)==null; match("Sony XM5",10)==null
// NegotiationTest: descending(golden) 首元素 rate=32，次元素 rate=8，bits 恒 2；base rate=2 时仅 1 个候选
```

- [ ] **Step 2: 跑测试确认失败** — `gradle testDebugUnitTest`，Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现四个文件** — 签名如上；实现体由实现者写（常量值见 Global Constraints）

- [ ] **Step 4: 跑测试确认通过** — Expected: 全绿

- [ ] **Step 5: Commit** — `git commit -m "feat: codec domain layer (config/presets/negotiation) with unit tests"`

---

### Task 3: shell 层 —— UserService 反射引擎（移植 PoC）

**Files:**
- Create: `app/src/main/aidl/com/chengzi/btmax/ICodecService.aidl`, `shell/CodecService.kt`, `shell/ShellProvider.kt`, `shell/ShizukuShellProvider.kt`
- Reference: PoC `CodecService.java`（git 历史，或 `git show HEAD~1:...`）——Looper 防护、frameworkInit 字段注入、adapter 五级回退链、proxy 连接、读回验证循环全部移植为 Kotlin

**Interfaces:**
- Consumes: Task 2 的 `CodecConfig`
- Produces:
  - AIDL：`String dumpDevices()`(JSON 数组 `[{name,mac}]`)、`String readConfig(String mac)`(JSON `{current:CodecConfig, selectable:[CodecConfig]}`)、`String writeConfig(String mac, String configJson)`(JSON `{applied:Boolean, readbacks:[CodecConfig], final:CodecConfig}`)、`void destroy()`
  - `interface ShellProvider { val isRunning:Boolean; val hasPermission:Boolean; suspend fun requestPermission(activity:Activity):Boolean; fun bind():ICodecService; fun unbind() }`
  - `class ShizukuShellProvider(context:Context) : ShellProvider`（内部做 Stellar 双通道探测）

- [ ] **Step 1: 移植 CodecService（Kotlin）**，逻辑与 PoC 一一对应；读回验证循环：每 500ms 一次、最多 8 次、匹配判定 = codecType+rate(0 跳过)+bits(0 跳过) 一致

- [ ] **Step 2: ShellProvider + Shizuku 实现**：UserServiceArgs(processNameSuffix="codec", daemon=false)；权限请求流程同 PoC

- [ ] **Step 3: 真机验证（Stellar 兼容性裁决点）**

临时复用占位 MainActivity 加两个调试按钮（bind + readConfig 打日志）：
```bash
$ADB install -r ... && $ADB shell am start -n com.chengzi.btmax/.MainActivity
# 场景 A：shizuku_server 运行 → 应读到 Enco X3 当前配置
# 场景 B：杀掉 shizuku_server、只留 stellar_server → 观察 pingBinder 是否为 true
```
Expected: A 通过；B 的结果决定 Stellar 是否需要专用适配（若 pingBinder=false，在 ShellProvider 内记录日志并标注 `// FIXME(stellar)`，设计 §4.4 允许 Shizuku 单通道先行）

- [ ] **Step 4: Commit** — `git commit -m "feat: shell layer (UserService reflection engine, ported from PoC)"`

---

### Task 4: 自动触发链路（M1 验收点）

**Files:**
- Create: `trigger/ConnectionReceiver.kt`, `trigger/ApplyWorker.kt`, `data/SettingsRepository.kt`, `data/ProfileRepository.kt`, `notify/Notifier.kt`, `LogStore.kt`
- Modify: `AndroidManifest.xml`（receiver + BLUETOOTH_CONNECT 权限）

**Interfaces:**
- Consumes: Task 2/3 全部接口
- Produces:
  - `class SettingsRepository(context)`：DataStore 键 `autoEnabled:Boolean`(默认 false，Onboarding 完成时置 true)、`notifyResult:Boolean`=false、`notifyPersistent:Boolean`=false、`qsTile:Boolean`=false、`onboarded:Boolean`=false
  - `class ProfileRepository(context)`：`fun profileFor(mac:String):DeviceProfile?`、`fun save(DeviceProfile)`
  - `object ApplyPolicy { fun decide(current:CodecConfig, target:CodecConfig):Boolean; fun autoTarget(current:CodecConfig, selectable:List<CodecConfig>):CodecConfig }`（decide：已一致则跳过写入——省电。autoTarget：**无档案新设备的全局默认策略**——codecType/cs1~cs4 保持 current 不变，rate/bits 从 selectable 中当前 codec 的能力掩码取最高位，priority 置 1000000）
  - `class ConnectionReceiver : BroadcastReceiver`（manifest 注册 `BluetoothDevice.ACTION_ACL_CONNECTED`）
  - `class ApplyWorker`：goAsync 流程按设计 §4.3；重试读回间隔 1s×3；失败/成功按 `notifyResult` 发通知；Shizuku 未运行 → "一键修复"通知（PendingIntent 打开 MainActivity）

- [ ] **Step 1: 写 ApplyPolicy 单测**（current==target 时 false；cs1 不同 true；autoTarget：current rate=2、selectable 掩码含 43 → 产出 rate=32、bits=2、cs1 不变；无档案时走 autoTarget 分支）

- [ ] **Step 2: 实现 data/LogStore/Notifier（Notifier 先建 channel，权限 Android 13+ 运行时申请）**

- [ ] **Step 3: 实现 ConnectionReceiver + ApplyWorker**：有档案 → 用 `Negotiation.descending(profile.config)` 逐候选 writeConfig 直到读回一致；无档案 → `ApplyPolicy.autoTarget` 写入一次并验证；manifest：
```xml
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<receiver android:name=".trigger.ConnectionReceiver" android:exported="false">
  <intent-filter><action android:name="android.bluetooth.device.action.ACL_CONNECTED"/></intent-filter>
</receiver>
```

- [ ] **Step 4: 真机验证（M1 验收）**
```bash
$ADB install -r ...; $ADB shell am force-stop com.chengzi.btmax
# 手工：把 Enco X3 的 DeviceProfile 经调试入口写入（或复用 Task 3 调试按钮录制），断连重连耳机
```
Expected: App 进程已死仍被拉起；约 3s 后 `readConfig` 显示 192k/24bit/cs1=50888712；日志区可见完整过程

- [ ] **Step 5: Commit** — `git commit -m "feat: auto-apply on headset connect (M1)"`

---

### Task 5: Onboarding + 首页（M2 验收点）

**Files:**
- Create: `ui/theme/Theme.kt`, `ui/MainActivity.kt`(重写), `ui/NavGraph.kt`, `ui/onboarding/OnboardingScreen.kt`(Welcome/Carrier/Connect/Source/Battery 五页 HorizontalPager), `ui/home/HomeScreen.kt`, `ui/onboarding/RecordWizard.kt`
- Modify: `AndroidManifest.xml`（label/icon，icon 用矢量占位：满格信号+蓝牙组合）

**Interfaces:**
- Consumes: 前序全部
- Produces: 首页状态卡 `fun CodecStatusCard(config:CodecConfig?, deviceName:String?)`；Onboarding 完成时 `settings.setOnboarded()` + `autoEnabled=true`；录制向导调 `readConfig` 存 `Source.CAPTURED`；预置命中时**必须弹确认窗**（Review Focus #3）

- [ ] **Step 1: Theme（动态取色，fallback 品牌色）+ NavGraph 骨架**

- [ ] **Step 2: Onboarding 五页**：载体页检测 Shizuku/Stellar 安装与运行状态（包名 `moe.shizuku.privileged.api` / `roro.stellar.manager`，未装给酷安/GitHub 指引文案，未运行给启动指引）；Battery 页给 vivo 自启动/电池白名单 intent（`com.vivo.permissionmanager` 等，try/catch 多入口回退到系统设置）

- [ ] **Step 3: 首页**：状态卡（onResume 主动 readConfig）+ 自动总开关 + 「立即拉满」按钮（走 ApplyWorker 同一条路径）

- [ ] **Step 4: 录制向导**：引导文案 → 用户开发者选项调好 → 「抓取当前参数」→ 存档案 → 提示关闭开发者选项

- [ ] **Step 5: 真机走查**：卸载重装，完整走一遍引导 → Expected: 每页状态正确、授权可完成、录制成功、首页显示 192k 状态卡

- [ ] **Step 6: Commit** — `git commit -m "feat: onboarding + home UI (M2)"`

---

### Task 6: 设备页 / 设置页 / 三外围开关（M3 验收点）

**Files:**
- Create: `ui/devices/DevicesScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/logs/LogsScreen.kt`, `notify/StatusService.kt`, `notify/QsTileService.kt`

**Interfaces:**
- Produces: `StatusService`（FGS，仅 `notifyPersistent=true` 时启动：事件驱动更新 + 30s 兜底轮询，前台通知显示 "codec·rate·bits"）；`QsTileService`（点击=立即拉满，仅 qsTile=true 时 `setPreferredSettings` 启用）；设备页支持重录/单设备开关/查看参数

- [ ] **Step 1: 设备页 + 设置页 + 日志页**（设置页含保活引导入口、关于页含 GPL/免责/零采集声明）

- [ ] **Step 2: StatusService + QsTileService**，两者监听设置键变化启停

- [ ] **Step 3: 真机验证**：三开关默认关 ✓；逐个打开验证各自生效；常驻通知显示的参数与首页一致

- [ ] **Step 4: Commit** — `git commit -m "feat: devices/settings/logs screens + optional notification & QS tile (M3)"`

---

### Task 7: M4 真机矩阵 + 开源仓库卫生

**Files:**
- Create: `README.md`（中英双语+截图位）、`LICENSE`（GPL-3.0）、`NOTICE`、`CONTRIBUTING.md`（设备档案 9 字段格式）、`device-profiles/enco-x3.json`、`.github/ISSUE_TEMPLATE/device-profile.md`、`.github/workflows/build.yml`（debug assemble + versionCode 递增校验）
- Modify: `docs/` 归档（设计文档已就位）

- [ ] **Step 1: 真机矩阵**（设计 §6）：开发者选项开/关 × 断连重连 × 重启手机后载体修复流程 × Shizuku/Stellar 载体 × 杀后台设置前/后——逐项记录结果
- [ ] **Step 2: 矩阵发现的 bug 修复（每个 bug 先写复现记录再修）**
- [ ] **Step 3: 仓库卫生文件全部落位**；CI 跑绿
- [ ] **Step 4: Commit** — `git commit -m "chore: release hygiene (M4 complete)"`

---

## 附录：发布运营清单（用户本人执行，spec §8 为准）

1. **M3.5** 酷安开发者实名注册 + 养号（不能卡在首发当天）
2. **M4.5** soak：`versionName=0.9.0` pre-release，**正式 keystore 签名**（`keytool -genkeypair -v -keyalg RSA -keysize 2048 -validity 36500`，生成后立刻离线双备份+密码入密码管理器），自用 3 天无 P0
3. **M5** `gh repo create FullBars --public` → push → GitHub Release v1.0.0（`apksigner verify --print-certs` 验签后上传；tag 含完整源码）→ Gitee 镜像
4. **M6** 酷安首发图文（亲自回评）→ B站 1~2min 视频（痛点对比+预答质疑+FAQ 置顶）→ 小红书（不挂外链，"酷安搜索『音质满格』"）；所有帖子 CTA："提交耳机型号帮我适配"
