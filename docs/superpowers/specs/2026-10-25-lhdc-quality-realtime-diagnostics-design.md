# StayHiFi：LHDC 设置层码率与实际传输诊断增强设计

- 日期：2026-10-25
- 基线：v1.2.0，master `ef13067`
- 状态：builder-check 已完成，唯一关键冲突已回填裁决；用户新增 1.2.1 与简短 Release 要求已纳入；待实施
- 实施任务：Task 52（设置层码率）、Task 53（诊断增强）、Task 54（1.2.1 版本与 Release 文案）
- 开发方式：每个任务由独立 fable subagent 以 TDD 实施，主 agent 逐任务审查，最后整分支复审

## 1. 背景与问题陈述

v1.2.0 外部反馈原件位于 `~/Documents/stayhifi用户反馈/1.2.0/`，共 5 张图，已全部按原始分辨率阅读。涉及 4 台 Android 17 手机：

| 手机 | 耳机 | codecType | cs1 | dumpsys 证据摘要 |
|---|---|---:|---:|---|
| PLK110 | OPPO Enco X3 | 19 | 32777 (`0x8009`) | 可匹配 `LHDC V3`；`qualityMode=LOW_64 bitrateKbps=— notStreaming=true`；样本在重连/协商初期 |
| RMX5010 | OPPO Enco X3 | 19 | 32777 (`0x8009`) | 同上；MainActivity 与 ApplyWorker 均留下早期样本 |
| Xiaomi 15 Pro | OPPO Enco X4 | 19 | 32773 (`0x8005`) | 媒体状态有 `PLAYING(3)`，但解析结果为「无匹配段落」；两份日志含多次重连、拉满、恢复默认 |
| Xiaomi 17 Pro | OPPO Enco X4 | 19 | 32773 (`0x8005`) | 媒体状态有 `PLAYING(3)`，但解析结果为「无匹配段落」 |

这暴露出三个不同问题，不得合并成一次“解析修复”：

1. **首页上方设置层「码率」空白**：`Masks.qualityLabel()` 只识别 codecType 4/10，19 一律返回 null。
2. **首页下方「实际传输」没有数值**：OPlus 缺有效播放中样本；Xiaomi 的当前 dumpsys 路径没有匹配段。二者不能共用结论。
3. **诊断采集漏掉后续状态**：当前 vendor 诊断只按 `mac + raw current JSON` 去重，连接初期采过一次后，同配置后续从非流式变为可读取码率，也不会再记录。

## 2. 已核实的技术依据

### 2.1 LHDC cs1 显示语义

以下来源交叉一致：

- Savitech 官方 `lhdcv5_api.h`：质量枚举 `LOW0..LOW4=0..4`、`LOW=5`、`MID=6`、`HIGH=7`、`HIGH1=8`、`AUTO=9`。
- 厂商蓝牙栈公开实现 `a2dp_vendor_lhdc_constants.h`：`A2DP_LHDC_VENDOR_CMD_MASK=0xC000`、`A2DP_LHDC_QUALITY_MAGIC_NUM=0x8000`、`A2DP_LHDC_QUALITY_MASK=0xFF`；低字节 0..9 与码率/ABR 的映射完整一致。
- 同栈 `a2dp_vendor_lhdcv5.cc` / `a2dp_vendor_lhdcv5_encoder.cc`：`A2DP_VendorGetBitRateLhdcV5()` 先验证 magic，再按低字节返回 64/128/192/256/320/400/500/900/1000kbps 或 ABR；`quality_mode_index_to_name()` 与反馈中的 `LOW_64` 命名一致。
- 本仓库已有 vivo 实证：`0x03088008=1000kbps`、`0x02058009=自适应`，也与低字节规则一致。

因此本批反馈的只读显示结果应为：

- `32777 (0x8009)` → `自适应`。
- `32773 (0x8005)` → `400kbps`。

**边界**：这是设置层质量档，不是实时空中码率；本设计不允许据此修改任何写入目标。

### 2.2 codecType 19 不能裸认

AOSP `BluetoothCodecType.getNativeCodecType()` 明确 native type 是分配给 codec 的内部值，不应把 19 当成跨所有手机/未来系统永远不变的公共常量。

因此本次必须同时满足：

1. 现有 `codecName(type, capsRateMask)` 已凭 vendor 区间 + selectable 高采样率能力佐证为 LHDC，或属于已有实证的 codecType 10；
2. cs1 的 `0xC000` magic 为 `0x8000`；
3. cs1 低字节在 0..9 内。

不能仅因 `codecType==19` 就解码，更不能把这个规则用于写入。

### 2.3 实际传输显示的数据边界

- 当前实时值只来自 `dumpsys bluetooth_manager` 的 `A2DP <codec> State:` 段及 `transmission bitrate`。
- `PLAYING(3)` 是媒体状态，不单独证明声音正在通过目标 A2DP 设备传输。
- OPlus 日志已有 LHDC 段，但留下的样本均为非流式/连接早期状态，不能据此判定播放时不支持。
- Xiaomi 日志证明当前路径未找到 LHDC 段，不证明系统永远不存在其他可用字段。
- `CodecService.runDumpsysFiltered()` 已保留所有含 `State:`、`quality`、`bitrate` 的行；但 App 在无匹配段时只打印前 12 个 `State:` 行，可能丢掉已经取回的 quality/bitrate 线索。

## 3. 目标

1. 让首页上方设置层「码率」在已佐证的 LHDC 上正确显示质量档。
2. 保留 vivo codecType 10 的既有显示结果，并补齐 LHDC 协议内其他合法档位的只读解码。
3. 让同一 raw 配置通过**首页既有 3 秒刷新路径**分别留下以下诊断样本：无段、明确非流式、有段但无可用码率、首次可用码率；ApplyWorker 不为此增加 dump。
4. 每次诊断同时保留采集来源、`isMusicActive`、原始 bitrate 和归一化 bitrate，便于区分媒体状态、栈原始值与 App 解析结果。
5. 无匹配段时优先保留 A2DP/codec/quality/bitrate 线索，避免被通用 State 行挤掉。
6. 控制成本：不增加 dumpsys 次数、不扩大 shell 侧过滤、不写持久日志、不让 ABR 动态变化刷爆 200 行环形缓冲。

## 4. 非目标与反目标

- 不承诺本次让 Xiaomi 15 Pro / 17 Pro 显示实际传输数值。
- 不从 cs1、采样率、位深、PCM 增量或理论压缩率推算“实际传输”。
- 不修改 `BtStateParser` 的段名匹配规则来猜其他段。
- 不新增 HCI snoop、logcat 采集、root/LSPosed、网络上传或持久化日志。
- 不修改 `ApplyPolicy.BEST_CS1`，不写 19 号 cs1，不改变自动/手动拉满目标。
- 不扩大 `runDumpsysFiltered()` 的每 3 秒采集范围，不新增 AIDL 方法。
- 不修改 `ListenerService` 的 30 秒轮询，不往其中加入新的 dumpsys。
- 不处理 `CodecConfig.summary()` 在日志中显示 `vendor(19)` 的既有命名问题；它不是本次空码率根因。
- 不调整首页布局、文案或「实际传输」三态显示规则。
- 不顺手修复 ApplyLock 内 binder IO 超时遗留项。

## 5. 方案比较与裁决

### 5.1 设置层质量档

- **方案 A：对所有 codecType 19 直接按低字节解码。** 改动最小，但把动态内部编号当固定协议编号，可能误标未来 codec，否决。
- **方案 B：只对已佐证的 LHDC 解码，并验证 magic。** 与现有 codecName 能力门控一致，误标面最小，采用。
- **方案 C：只硬编码 32777/32773 两个值。** 能覆盖本批反馈，但会把已知协议再次变成样本硬编码，否决；这两个值只作为回归 fixture。

### 5.2 诊断去重

- **方案 A：继续按 mac+raw 去重。** 会漏掉同配置后续流式状态，否决。
- **方案 B：把每次解析结果（含实时码率）都放进签名。** 能留全量变化，但 ABR 每 3 秒变化会刷爆 200 行日志，否决。
- **方案 C：按四种诊断类别分桶，首次可用码率只记一次。** 能覆盖状态跃迁且有明确上限，采用。

### 5.3 dumpsys 采集范围

- **方案 A：每 3 秒扩大到近全量 dumpsys。** 可能找到更多字段，但 binder/日志成本不可控，违背轻量原则，否决。
- **方案 B：复用当前已过滤文本，无匹配段时改变 App 侧取舍。** 零新增 dumpsys、零新增 binder 数据，先验证现有证据是否足够，采用。
- **方案 C：新增一次性 expanded dump AIDL。** 只有方案 B 的外部证据仍不足时才另行设计，本次不做。

## 6. Task 52：设置层 LHDC 质量档显示

### 6.1 API 设计

在 `Masks` 中保留现有两参调用兼容性，新增可选能力掩码参数，语义如下：

```kotlin
fun qualityLabel(
    codecType: Int,
    cs1: Long,
    capsRateMask: Int = 0,
): String?
```

- codecType 4：完全沿用现有 LDAC 精确值映射，忽略 capsRateMask。
- codecType 10：作为已有 vivo 实证 LHDC，可按 LHDC 规则解码。
- vendor 区间 codec：只有 `Masks.codecName(codecType, capsRateMask) == "LHDC"` 时才可按 LHDC 规则解码。
- 其他 codec：返回 null。
- 两参调用等价于 `capsRateMask=0`，所以 vendor 19 在没有 selectable 上下文的旧调用点仍返回 null，不裸认。

`MainActivity.refreshHome()` 已计算 `capsMask`，首页赋值改为：

```kotlin
quality = Masks.qualityLabel(current.codecType, current.cs1, capsMask)
```

`CodecConfig.summary()` 没有 selectable 上下文，继续两参调用；本任务不改它的 codec 命名或设备页/通知展示语义。

### 6.2 LHDC 解码规则

仅在 §6.1 的 LHDC 身份门控通过后执行：

```text
if ((cs1 and 0xC000L) != 0x8000L) return null
when (cs1 and 0xFFL) {
  0 -> 64kbps
  1 -> 128kbps
  2 -> 192kbps
  3 -> 256kbps
  4 -> 320kbps
  5 -> 400kbps
  6 -> 500kbps
  7 -> 900kbps
  8 -> 1000kbps
  9 -> 自适应
  else -> null
}
```

标签格式与现有 UI 保持一致：数值不带空格，ABR 显示「自适应」。

### 6.3 Task 52 测试要求（先失败，后实现）

`MasksTest` 至少覆盖：

1. 反馈 fixture：19 + caps 42 + 32777 → `自适应`。
2. 反馈 fixture：19 + caps 43 + 32773 → `400kbps`。
3. 19 + 高采样率能力 + cs1 低字节 0..9 → 完整映射。
4. 同一 cs1 但 caps=0/8 → null，证明没有裸认 19。
5. 非 vendor 区间但带相同 caps/cs1 → null。
6. magic 非 `0x8000`（包括 `0xC000` 低延迟命名空间）→ null。
7. 低字节 10/255 → null。
8. 未知 codec 99 即使带高采样率能力和合法 cs1 → null。
9. codecType 10 的 50888712/33914889 既有结果不变。
10. codecType 10 的其他合法低字节档位可解码，非法 magic/低字节仍返回 null。
11. LDAC 既有测试不变。

不得修改 `ApplyPolicyTest` 的既有预期来迁就实现；写入策略测试应原样通过。

## 7. Task 53：实际传输诊断增强

### 7.1 保留原始 bitrate

`BtStateParser.BtState` 增加：

```kotlin
val rawBitrate: Long? = null
```

- 只在 `transmission bitrate` 行成功解析为 Long 时赋值，保留系统原文数值。
- `bitrateKbps` 继续按现有规则归一化；`notStreaming` 逻辑不变。
- 缺字段、非数字值均为 `rawBitrate=null`。
- 新字段只用于诊断，不改变 `realtimeDisplay()`、UI 或 `formatDisplay()`。

### 7.2 四类诊断样本

在 `VendorDiagnostics` 增加纯函数：

```kotlin
fun sampleKind(state: BtStateParser.BtState?): String = when {
    state == null -> "no-section"
    state.notStreaming -> "not-streaming"
    state.bitrateKbps == null -> "no-bitrate"
    else -> "available"
}
```

约束：

- `available` 不包含具体码率/qualityMode；400→500 仍是同一类，避免 ABR 刷日志。
- dumpsys error/exception 不归入 `no-section`；ApplyWorker 使用独立 `dump-error`/`dump-exception` 类别，MainActivity 继续由现有 `realtimeDiag()` 记录错误。
- `notStreaming` 只描述栈原始字段语义，不改名为“耳机未播放”。

### 7.3 可测试的去重 Gate

在 `VendorDiagnostics.kt` 中增加轻量 `VendorDiagnosticGate`（或同等小型专用类）：

```kotlin
class VendorDiagnosticGate {
    @Synchronized
    fun shouldRecord(mac: String, current: CodecConfig, sampleKind: String): Boolean

    @Synchronized
    fun reset()
}
```

- 内部签名 = `mac + "|" + current.toJson() + "|" + sampleKind`。
- 首次返回 true，重复返回 false；reset 后同签名可再次返回 true。
- **MainActivity（状态跃迁采集主路径）**：使用真实 `sampleKind` 进入 gate。它本来就会在首页可见且 `isMusicActive=true` 时每 3 秒 dump/parse，因此新增类别不需要任何额外 dump。
- **ApplyWorker（仅首次配置采集）**：仍以固定类别 `apply-dump` 在实际 sampleKind 尚未计算前进入自己的 gate；只有首次命中才执行既有那一次 dump。真实 sampleKind 只写入日志，不作为后续运行再次 dump 的依据。四类状态跃迁不要求也不允许由 ApplyWorker 额外轮询采全。
- 两路各持有一个 gate，保留当前各自留证据的行为；不引入跨进程共享假设。
- 现有 `recordUnknownVendor()`/`resetVendorDiagnostics()` 可改为 gate 的薄封装，保持清空日志入口行为。
- `LogsAction.Clear` 继续同时重置两路 gate 和 `btDiagLast`，不新增持久状态。

**主 agent 对 builder-check 的裁决**：若“所有运行总 dump 次数不变”与“ApplyWorker 也按四类状态采全”冲突，以前者为硬约束；ApplyWorker 只保留首次配置证据，后续 `not-streaming → available` 等跃迁由首页既有轮询留证。这是本次设计方的选择，不要求 builder 再向用户确认。

### 7.4 日志内容与来源

`VendorDiagnostics.evidence()` 扩展为包含：

```text
段=<section> qualityMode=<raw/—> rawBitrate=<raw/—> bitrateKbps=<normalized/—> notStreaming=<true/false>
```

两条调用路径在证据前补充：

```text
source=home|apply isMusicActive=<true/false> sample=<kind>
```

- MainActivity 使用本轮已经读取的 `audioManager.isMusicActive`。
- ApplyWorker 在 dumpsys 诊断时读取同一名义 API 并记录；它是媒体状态，不宣称 A2DP 路由状态。
- raw 9 字段日志保持现有内容和脱敏边界，不新增设备个人信息。
- MainActivity 复用本轮 `parsed`，不得为 vendor 诊断再解析/再 dump 一次。
- ApplyWorker 保持现有控制流预算：先用固定 `apply-dump` 类别通过 gate，才执行同一次 dump/parse；真实 `sampleKind` 只用于标注这次证据。不得为发现后续类别而增加第二次或后续运行的 dump。

典型可用样本：

```text
dumpsys证据: mac=<mac> source=home isMusicActive=true sample=available 段=LHDC V3 qualityMode=ABR rawBitrate=400000 bitrateKbps=400 notStreaming=false
```

### 7.5 无匹配段时的线索保留

在 `VendorDiagnostics` 增加共享纯函数，例如：

```kotlin
fun dumpClues(dump: String, limit: Int = 24): List<String>
```

规则：

1. 去空行，保留原始行顺序（组内）与必要缩进。
2. 第一组优先：含 `A2DP`、`codec`、`quality`、`bitrate`（均忽略大小写）的行。
3. 第二组补充：其余含 `State:` 的行。
4. 两组按内容去重，合计最多 24 行。
5. MainActivity 与 ApplyWorker 共用该函数，替换当前仅 `.filter { State: }.take(12)` 的两份重复逻辑。
6. 日志标签由 `dumpsys段` 改为 `dumpsys线索`；不改变 `dumpsys证据: ... 无匹配段落` 的主结论。

这样即使通用 State 行超过 24 行，已经取回的 quality/bitrate 也不会被截掉。若总线索超过上限，接受现有环形日志的有界取舍，不在本任务扩大采集。

### 7.6 Task 53 测试要求（先失败，后实现）

`BtStateParserTest`：

1. 400000 → `rawBitrate=400000`、`bitrateKbps=400`。
2. -1 → `rawBitrate=-1`、`bitrateKbps=null`、`notStreaming=true`。
3. 缺字段/非数字 → `rawBitrate=null`，既有 notStreaming 语义不变。
4. 既有解析和实时三态测试原样通过。

`VendorDiagnosticsTest`：

1. `sampleKind` 四类判定。
2. available 不随码率或 qualityMode 改变类别。
3. evidence 同时包含 rawBitrate 与 bitrateKbps；缺值显示 `—`。
4. gate 同签名首次 true/重复 false。
5. 同 mac+raw 的四个 sampleKind 可各记录一次。
6. available 从 400 变为 500 不新增记录。
7. 不同 mac、不同 raw 配置互不吞证据。
8. reset 后同签名可重新记录。
9. dumpClues 在 24 条以上通用 State 行前存在时，仍优先保留后面的 quality/bitrate/A2DP 行。
10. dumpClues 去空行、去重、最多 24 行；两组相对顺序稳定。

集成审查必须确认：首页实时状态更新不依赖 gate；即使 available 日志被去重，后续码率变化仍会正常更新 UI。ApplyWorker 在同一 mac+raw 的后续运行中不得为了采集新 sampleKind 而新增 dump；只有 `apply-dump` 首次命中才走既有 dump 路径。

## 8. Task 54：1.2.1 版本与 Release 说明

### 8.1 版本元数据

用户已明确本次版本定为 **1.2.1**。在 `app/build.gradle` 中且仅改：

```groovy
versionCode 6
versionName '1.2.1'
```

- 基线为 `versionCode 5 / versionName '1.2.0'`，versionCode 只递增 1。
- 不修改 applicationId、minSdk、targetSdk、compileSdk、签名配置或更新渠道。
- 通过最终 APK 的 badging 信息验证 versionCode/versionName，不以源文件字符串代替包体验证。
- 这是用户指定的版本元数据配置变更，不新增逻辑单测；既有 UpdateChecker 测试必须通过。

### 8.2 Release 文案约束

用户明确要求不再使用 v1.2.0 那种长、晦涩的更新说明。v1.2.1 Release 正文面向普通用户：

- 最多三条更新点，优先说“修复了什么”，不描述内部实现过程。
- 不写 Task 编号、cs1、codecType、magic、解析器、dumpsys、gate、commit 或测试技术细节。
- 不把“诊断增强”夸大为“所有机型已支持实际码率”。
- 不附长篇发布复盘、验证流水账或术语解释。
- 若保留 APK 校验信息，只在正文后单列一行；不占更新点，不展开证书/签名教学。

目标文案（发布时按实际交付逐字复核，只能保持同等或更短篇幅）：

```text
- 修复部分手机不显示码率档位的问题。
- 改进运行日志，方便反馈实际传输问题。
- 实时码率仍取决于手机系统支持，部分机型暂无法显示。
```

本任务只准备版本与文案；是否 push、打 tag、创建 GitHub Release 仍按 finishing 流程另行决定，不因版本号确定而自动发布。

## 9. 日志量级与性能预算

- dumpsys 次数：与 v1.2.0 完全相同。
- shell 过滤条件：不变。
- binder 返回文本：不变。
- 每个 gate、每个 mac+raw：四类样本加错误类有上限；available 的数值变化不新增 vendor 证据块。
- 无匹配段线索：每块最多 24 行，替代现有最多 12 行；只在 gate 首次命中时写入。
- LogStore：仍为进程内 200 行环形缓冲；不提高容量，不持久化。
- 首页 3 秒刷新、ApplyLock、Shizuku/Stellar 调用时序：不变。

## 10. 验收标准

### 10.1 自动化

1. 每个任务先提交/展示新测试在旧实现上的失败证据，再写生产代码。
2. 单任务完成后跑 `./gradlew test`，全绿；最终再跑一次完整测试。
3. 跑 `./gradlew assembleDebug`，构建成功。
4. 最终测试数按实际报告，不预写“全绿”或固定数量。

### 10.2 反馈 fixture 验收

- PLK110/RMX5010 的 19 + caps 42 + 32777 → 首页设置档「自适应」。
- Xiaomi 15 Pro/17 Pro 的 19 + caps 43 + 32773 → 首页设置档「400kbps」。
- 无高采样率佐证的同 cs1 vendor 19 → 仍为空，不误标。
- OPlus 非流式→可用状态的模拟序列 → 两类样本都保留；重复 available 不刷日志。
- Xiaomi 无匹配段模拟文本 → quality/bitrate 线索优先于通用 State 被保留。

### 10.3 本机回归（主 agent 执行）

在当前已连接的 V2505A / Android 17 / StayHiFi v1.2.0 环境上：

1. 使用最终提交构建并安装可覆盖安装的包；不得泄露 keystore 密码或打印签名环境变量。
2. 既有 LHDC codecType 10 设备仍显示 `LHDC V5 / 192kHz / 24bit / 1000kbps`。
3. 实际传输显示不因 gate 去重而冻结；播放/暂停三态行为与基线一致。
4. 清空日志后，同一样本可重新记录；日志仍只存在本机内存，复制日志可用。
5. 本机不是 OPlus/Xiaomi 反馈机型，不能据此宣称外部机型的实际传输问题已修复。

### 10.4 外部回归（下一版收集，不阻塞本次代码验收）

让用户使用包含本任务的版本：

1. 连接耳机并持续播放，回首页等待至少 10 秒；确认声音实际走耳机。
2. 清空日志，回首页再等待 5 秒，再复制日志。
3. OPlus：检查是否出现 `sample=available` 及 rawBitrate；若仍是非流式，保留原始值继续分析。
4. Xiaomi：检查 `dumpsys线索` 是否含未参与当前解析的 quality/bitrate 字段，再决定是否值得设计替代数据源。

## 11. 实施与审查顺序

1. Task 52：fable implementer，TDD；fable reviewer 审查 spec 符合性和代码质量；主 agent 复审后提交。
2. Task 53：基于 Task 52 的最终代码，fable implementer，TDD；fable reviewer；主 agent 复审后提交。两任务共享 MainActivity，禁止并行施工。
3. 主 agent 对完整 diff 做一次独立 fable 终审，重点检查：身份门控、magic、日志刷量、重复 dump、UI 更新与 gate 解耦、ApplyPolicy 未变。
4. Task 54：fable implementer 修改 `versionCode 6 / versionName '1.2.1'` 并按 §8.2 准备三条以内的 Release 文案；主 agent 审查版本 diff 与文案，配置变更以最终 APK badging 验证。
5. 主 agent 跑全量测试/构建并执行本机回归；如实报告无法覆盖的外部机型。
6. 是否合并 master、push、打 tag、创建 Release 另行按项目 finishing 流程决定；本 spec 不授权发布。

## 12. 明确留给 builder 的事项

- 在不改变上述语义的前提下，选择最贴合现有 Kotlin 风格的私有辅助函数命名与小范围代码组织。
- `VendorDiagnosticGate` 放在 `VendorDiagnostics.kt` 还是同包独立小文件，由 builder 按测试与可读性决定。
- ApplyWorker 为读取 `isMusicActive` 所需的最小 Android API 调用方式；不得引入依赖注入框架或新模块。
- 具体新增测试数量与测试方法命名，以覆盖本 spec 全部场景为准。

除上述事项外，若实现需要改变身份门控、写入策略、实时显示语义、dump 次数/过滤范围或日志持久化边界，必须停止并回到主 agent/用户重新裁决。
