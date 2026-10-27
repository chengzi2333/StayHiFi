# StayHiFi 下一版：编码器身份、诊断与实验 Root 设计（aptX 暂缓）

- 日期：2026-10-27（同日修订：吸收 iQOO 15 / PLK110 / OPD2409 三份新日志；aptX 与手动档位整项暂缓）
- 基线：v1.2.1(6)，master `d3adf81`
- 状态：非 aptX 范围已获用户批准进入实施计划；aptX/手动档位及专门采集全部 deferred，不进入本版计划或实现
- 范围：v1.2.1 外部反馈、通用厂商栈/联发科诊断、对外机型名、可选实验 Root 授权
- 本文不涉及版本号、发布或推送授权；下一版正式版本号另行决定

## 1. 用户已明确的方向

1. 这些反馈作为下一版需求，但先研究再落地，不能把未验证直觉写成产品公理。
2. 不得遗漏 aptX Adaptive / aptX Lossless 建议，也不得遗漏同一反馈者指出的“蓝厂联发科机器没参数、编解码器读取不对”。
3. 用户没有 Root 测试机；面向社区已有 Root 的用户（包括 KernelSU 等）提供**实验性 Root 授权**，在首次新手教程的特权授权步骤可选择，并记录足够日志供外部反馈排查。
4. 只把高置信结论写成确定修复；证据不足的部分必须保留诊断、实验和外部验收边界。
5. 现有 Shizuku/Stellar 用户不被强制迁移，默认行为不变。
6. 2026-10-27 用户最终裁决：aptX 先不动，届时再议。本版**不实现 44.1 自动策略、手动档位或 aptX 专属采集**；通用厂商栈原始诊断保留，未来有真实 aptX Adaptive 样本后重新设计。
7. 同日后台新增三份日志（iQOO 15 / PLK110 / OPD2409），已分析并纳入：连接触发风暴需合并、播放中栈回报未流式需与“未在播放”区分、SBC 诊断噪音需收敛。

## 2. 反馈与证据分级

### 2.1 已由日志和源码交叉确认

| 样本 | 已确认事实 | 不能据此推出 |
|---|---|---|
| NX741J / Android 16 + OnePlus Buds Pro 2 | 当前 vendor(14)、96kHz/24bit；`capsRateMask=11`，最高位为 8=96kHz，没有 176.4/192kHz 位 | 耳机或手机“本来一定能开 192kHz”；Root 能改变上限 |
| OPD2409 / Android 17 + 同一副耳机 | 当前 vendor(18)、96kHz/24bit；`capsRateMask=10`，最高位为 96kHz；播放状态下 dumpsys 报告 `LHDC V3 / MID / 500000`；cs1=`0x8006` 也对应 500 档 | 当前实际就是 LHDC V5；可以把 V3/V5 两段字段拼在一起 |
| OPD2409 的“无匹配段落” | `Masks.codecName(type,caps)` 因 caps 不含 16/32 而回落 vendor(18)，随后 parser 用 vendor(18) 找不到 LHDC 段；这是 App 身份门控过窄 | 放宽为“所有 cs1 含 0x8000 的都是 LHDC”是安全的 |
| PHZ110 + OPPO Enco X4 | caps=42 含 192 位；日志已有 192kHz/24bit 写入并读回成功；早期 Shizuku 绑定失败是另一条生命周期问题 | 该样本证明所有 codec19 都固定是 LHDC |
| iQOO Pad5 Pro + Sound Move | codec8、caps=2，仅暴露 48kHz；当前 48kHz/16bit | cs1/cs3 已足以确认编码器身份或更高采样能力 |
| 目标派生代码 | 诊断对同 codec 多条 selectable 做或集，但 `ApplyPolicy.deriveTarget()` 只取第一条；多条目时两者可能不一致 | 已证明这就是联发科反馈的根因 |
| 参数为 0 的边界 | rate/bits=0 在 `matches()` 中是通配；current 本身不完整时可能让“最高”判定失去事实基础 | 已证明反馈中的联发科机器一定返回 0 |
| iQOO 15 / Android 17（日志 1，PD2505D） | 开机 Enco X3 自动写 192kHz/1000kbps 读回一致 ✓（回归基线）；同秒多台设备 ACL 连接/断开风暴产生多个排队 worker，重复“派生/跳过/建档”并刷“等待上一次拉满完成…”；未知 BLE 设备（无 A2DP）每次触发都跑 5s 轮询；SBC 设备产生“无码率字段”实时诊断噪音 | 触发风暴下写入功能损坏 |
| PLK110 / Android 17（日志 2，Enco X4） | 开机先协商为 SBC 后转为 vendor(19)，写 192kHz 读回一致 ✓；**isMusicActive=true 时 LHDC V3 段仍 rawBitrate=-1**（栈明确回报未流式）——当前 UI 会显示“未在播放”，与用户实际在听歌矛盾 | 实时传输永远不可用 |
| OPD2409（日志 3，同前次样本） | 与早前反馈完全一致的稳定复现：播放中 LHDC V3 `MID/500000` 存在，vendor(18) 无匹配段落；同一 dump 中 V5 段只有 `{}` | 问题不可复现 |

### 2.2 高置信外部依据

1. AOSP `BluetoothA2dp.getSupportedCodecTypes()` 返回本机平台支持的 `BluetoothCodecType`，其中含 native type、稳定 codecId、codecName；需要 `BLUETOOTH_PRIVILEGED`，现有 shell 路径具备使用该接口的权限基础。
2. native type 是可变内部编号，稳定身份应优先看 codecId/name；不能将 14/18/19 固化成跨机型协议编号。
3. `BluetoothCodecConfig` 跨 parcel 仍可能只保留旧 int，因此只调用 `getExtendedCodecType()` 不足以保证 vendor 身份保留；supported-types catalog 是独立只读路径。
4. AOSP 写配置仍会校验 selectable；Root 不为超出协商能力的配置提供豁免。
5. OnePlus 官方页明确 Buds Pro 2 支持 LHDC 5.0、最高 1Mbps，但未承诺该耳机可开 192kHz；本版不得以 192kHz 为这两台设备的验收目标。
6. Google 官方设备目录可直接提供大量 Build.MODEL→营销名映射，但存在国行缺口与命名口径差异；本次实测 PHZ110→Find X7、OPD2409→Pad 4 Pro、NX741J→nubia Z80（缺少用户所述 Ultra 后缀）。
7. Qualcomm 将 aptX Lossless 定义为 aptX Adaptive 的能力，目标为 16bit/44.1kHz 的 bit-for-bit CD 音质，但需无损音源与合适 RF 条件；官方同时把 24bit/96kHz 标为 lossy 高解析路径。因此 44.1 不是 Lossless 的充分条件，96 也不能叫“无损回退”。
8. 一份固定版本 Qualcomm 衍生公开实现显示：44.1 只出现在部分能力集，其他能力集可只有 48/96；其默认配置普遍报告 24bit，且实现内部偏好顺序不能直接当作所有厂商产品策略。该资料只证明“能力集不同、首版不应附带强写 16bit”，不是受影响设备的实际栈证据。
9. AOSP `getCodecStatus()` 可因代理未连接、蓝牙关闭、设备无效或 RemoteException 返回 null；local/selectable getter 又会把内部 null 归一化为空列表。null、空列表、异常和不支持必须分开记录。

### 2.3 仍是假设，不得写成已确认根因

1. “蓝厂联发科机器没参数、编解码读取不对”只有论坛截图，没有其所指的另两张图、原始日志、手机型号、耳机型号或固件。以下均只是待区分的候选：
   - selectable 为空或当前字段为 0/NONE；
   - current 返回多比特掩码而非单值；
   - native codecType 被旧静态表误映射；
   - 厂商栈未实现/未完整实现 extended type 或 supported types；
   - 反射签名、权限或 binder 调用异常；
   - 协商尚未稳定导致早期快照不完整。
2. aptX Adaptive 的 44.1kHz 与 aptX Lossless 有关联，但“选择 44.1kHz”不等于已证明进入 Lossless；还依赖两端平台/固件、音源和链路条件。**2026-10-27 用户最终裁决：aptX 整项暂缓**，本版不做自动策略、手动档位或专属采集；现有研究仅留作未来重新立项的背景。
3. Root 可能改善特权通道与部分生命周期问题，但是否能避免某 OEM 冻结、是否兼容某一 KernelSU/Magisk 版本，必须外部实测。
4. NX741J 的人工显示名采用用户提供的 `nubia Z80 Ultra` 作为纠正候选；Google 目录与该产品名的颗粒度不同，不能宣称目录本身已确认 Ultra 后缀。

## 3. 目标、非目标与反目标

### 3.1 本版目标

1. 将编码器**身份**与采样率/位深**能力**解耦，优先使用系统提供的稳定身份。
2. 修复 OPD 类“已有 LHDC 实况段却被误判无匹配”的链路，并防止 V3/V5 多段混读。
3. 让多条 selectable 的诊断聚合与写入派生采用同一份原始数据：写入按同条能力校验，避免只看第一条或跨条目拼接；参数不完整时不误导、不盲写。
4. 为 MediaTek/厂商栈读取异常采集足够的一次性、去重、可复制证据；本版不承诺“已修复联发科”。
5. 用厂商属性、离线目录、人工纠正和 Build.MODEL 组成机型名分层兜底。
6. 在首次教程和设置中提供可选实验 Root 通道；默认仍为 Shizuku/Stellar，失败可手动退回，全程保留诊断。
7. 连接事件合并与过期作废：同设备短窗口内多次触发只跑一次；拿到锁时设备已断开则安静结束。
8. 实际传输显示区分“播放中但栈回报未流式”（显示“暂无法确认”，不误报“未在播放”）。
9. 实时抓取诊断收敛：SBC/AAC 等标准已知无码率字段的编码器不再产生诊断噪音。
10. 不增加运行时上传，不改变日志仅本机内存、由用户主动复制的隐私边界。

### 3.2 非目标与反目标

- 不强写 selectable 之外的 192kHz，不跨 codec 自动切换，不把耳机宣传参数当协商能力。
- 不把 codecType 14/18/19 做成新的全局固定映射，不仅凭 cs1/cs3 猜身份。
- 不把任何采样率档位命名为“已开启无损”；本版不实现 aptX 自动策略、手动档位或 aptX 专属采集，也不新增相关设置或档案字段。
- 不开放原始 9 字段自由编辑，不允许跨编码器类型切换写入。
- 没有联发科原始样本前，不按 MTK/天玑型号做特殊写入或硬编码 codecType。
- 不提供通用 `su -c` 命令桥，不关闭 SELinux，不改 vendor 蓝牙栈，不做 Root 专属音质能力。
- 不把 RootService 描述为开机自启方案，不在后台触发交互式 Root 授权弹窗，不在 Root 失败后静默切回 Shizuku。
- 不新增持久日志、后台上传、完整 HCI snoop 或每 3 秒全量 dumpsys。
- 不因实验功能改变既有默认最高参数策略、现有 9 字段写入格式、恢复默认参数语义和 Shizuku/Stellar 主路径。

## 4. 统一编码器身份与只读数据模型

### 4.1 保持写入协议稳定

- `CodecConfig` 继续是原有 9 字段可写配置；旧档案 JSON、restore、writeConfig 与读回匹配语义不加入身份字段。
- `ICodecService` 方法签名不变；`readConfig` 采用**追加字段**的 JSON，旧字段 `current/selectable` 原样保留。
- 新增只读元数据独立建模，例如 `CodecStateSnapshot(current, localCapabilities, selectable, supportedTypes, extendedIdentity, diagnostics)`；首页与 ApplyWorker 共用解析与身份裁决，禁止各自猜一套。local 与 selectable 不得混用：前者是本机能力，后者才是当前连接可选能力。

### 4.2 supported types 只读目录

`readConfig` 可追加：

```text
readStatus: {
  codecStatusNull, currentNull, localCount, selectableCount,
  currentSelectable: true | false | null,
  bluetoothState, a2dpProxyState
},
localCapabilities: [CodecConfig],
supportedTypes: [
  { nativeType: 18, codecId: <Long>, codecName: "LHDC V3" }
],
extendedIdentity: { codecId: <Long?>, codecName: <String?> } | null,
identityDiagnostics: { supportedTypesStatus, extendedTypeStatus, serviceUid }
```

约束：

1. API 不存在、flag 未启用、权限不足、反射异常或 OEM 返回空表时，只记录状态，不得让原 `current/selectable` 读取失败；API 不存在、返回空、返回异常必须是三种不同状态。
2. `getCodecStatus()==null`、`getCodecConfig()==null`、local/selectable 为空与调用异常必须分别保留，不能经 `!!` 或统一 error 文案合并，也不能把空列表直接显示成“设备不支持”。`currentSelectable` 仅作交叉检查，不验证 codecSpecific，更不是 Lossless 证据。
3. supported types 是本机平台目录，不宣称是耳机能力；只有 nativeType 与 current 匹配后，才作为当前配置的身份来源。
4. 同一 nativeType 返回冲突名称/ID、空名称、`UNKNOWN CODEC` 等不可信值时，忽略并记录冲突，不取第一条硬认。
5. 目录是本地平台信息，应在特权服务内缓存/限频，不得在首页每 3 秒刷新中新增一次无界 binder 查询；缓存失效与蓝牙重启由实施计划明确。
6. `getExtendedCodecType()` 只作辅助证据；null 不代表当前不是 vendor codec。

### 4.3 身份裁决顺序

引入纯函数 `CodecIdentityResolver`，输出至少：

```text
CodecIdentity(
  nativeType,
  family,          // SBC / AAC / APTX_ADAPTIVE / LHDC / LDAC / ... / UNKNOWN
  displayName,     // 如 LHDC V3；未知为 vendor(18)
  codecId?,
  source           // SYSTEM_TYPE / EXTENDED_CONFIG / VERIFIED_LEGACY / CAPABILITY_HEURISTIC / UNKNOWN
)
```

优先级：

1. 与 current nativeType 匹配、且无冲突的系统 supported type；
2. 可信的 extended config 身份；
3. 既有已实证静态映射（标准 0..6，以及已有厂商实证项）；
4. 既有 vendor 区间 + 高采样率能力佐证；
5. `vendor(N)` 与 `UNKNOWN`。

要求：

- 系统身份与旧静态表冲突时系统身份优先，并留下一次 `IDENTITY_CONFLICT` 诊断。
- 旧启发式保留作回退，但不得再反向覆盖系统身份。
- 身份 family 仅用于显示与通用诊断；不得据此新增任何 aptX 专属写入策略（本版暂缓）。
- `qualityLabel()` 接收已裁决 family，而不是再次用 caps 推导身份。LHDC 仍须通过 cs1 magic 和低字节校验；本设计不授权任何新的 cs1 写入。
- 首页、ApplyWorker、常驻通知和面向用户的实时日志使用同一 `CodecSummaryFormatter`。历史档案没有身份上下文时可保留旧 `summary()` 兜底，不伪造新身份。

## 5. 能力聚合、参数完整性与目标语义

### 5.1 聚合当前 codec 的全部 selectable

诊断仍可对当前 codecType 的全部 selectable 做 rate/bits 或集，但**写入目标不得把不同条目的维度独立拼成不存在的组合**：

1. 先在所有同 codec 条目中选明确报告的最高 rate，再在能够支持该 rate 的条目中选明确报告的最高 bits；不能从 A 条目取 192、从 B 条目取 24bit，制造 A 不支持的组合。
2. codecType、cs2~cs4 继续继承 current，cs1 规则不变；channelMode 不主动改写，但候选必须由同一条能力记录同时兼容 codecType/rate/bits/channel。0/NONE 不等于已证明该维度可选。
3. `COMPLETE` 模式的初始目标和**整个降档链**都要经过上述同条能力校验；例如 caps 不含 96 时，不得从 192 降出 96。保留旧 `Negotiation.descending(base)` 给 `CAPABILITY_DEGRADED` 的既有兼容路径，新增能力约束路径，旧单测不改预期。
4. 降档仍只改变 rate；bits 沿用初始目标，若较低 rate 没有同条目支持该 bits，则该候选不存在，不擅自换位深。
5. 不跨 codecType 选能力，不把 0 当可写档位；诊断聚合值不得单独作为写入有效性的证明。

这修复“诊断已聚合、目标却只看第一条”的内部不一致，也避免跨条目拼参数；但不宣称它就是联发科根因。

### 5.2 参数不完整不得冒充已达标

新增显式 `TargetAssessment`，至少区分 `COMPLETE / CAPABILITY_DEGRADED / INCOMPLETE_CURRENT`；实验策略及回退原因另存，不混进能力状态。

- `COMPLETE`：当前 codec 存在能同时验证 rate/bits/channel 的明确能力条目；读回匹配后才可显示既有“已达本机最高/满血”。
- `CAPABILITY_DEGRADED`：selectable 缺失或缺当前 codec 条目，但 current 为合法单值。暂保留现有回退写入语义，避免无样本改变厂商兼容路径；UI 用中性“能力未完整暴露 · 暂无法确认最高”，不显示满血。早退/写入读回一致只能通知“当前目标已确认（能力未完整）”，不得通知“已达最高”。
- `INCOMPLETE_CURRENT`：current 的 rate/bits 为 0、未知多比特或不可写值。记录原始快照，**不写入、不记录 origin、不发成功通知**；首页显示“参数不完整 · 暂无法判定”，重试仍走重新读取。
- 不再允许 target=0 的通配匹配把不完整 current 显示成“已达本机最高”。
- `Negotiation` 对非法 base 不产生候选；单测明确 0 与多比特行为。

## 6. dumpsys 多段解析与 OPD 回归

### 6.1 不得跨同族段落拼接字段

当前 parser 对泛称 LHDC 可同时命中 V3/V5，并可能保留前段的 quality/bitrate、却把段名覆盖成后段。本版改为：

1. 先拆出所有独立 `A2DP ... State:` 段，再逐段解析；每段的 codec/quality/rawBitrate 必须来自同一段。
2. 有精确系统身份（如 LHDC V3）时优先精确段名。
3. 只有泛称 LHDC 时：
   - 若只有一个候选，返回该候选；
   - 若多个候选中只有一个有可用正码率，可选该候选；
   - 若多个候选均有可用码率或无法安全裁决，返回 `AMBIGUOUS_SECTION`，不拼字段、不显示猜出的版本。
4. `NO_SECTION / AMBIGUOUS_SECTION / NOT_STREAMING / NO_BITRATE / AVAILABLE` 在诊断中可区分；UI 对 ambiguous 显示“暂不支持抓取/无法确认”，不是“未在播放”。
5. 复用本轮 dump，不增加 dumpsys 次数；ambiguous 日志只保留候选段头和必要证据，限制行数。
6. 实时抓取诊断（实际传输抓取诊断/无匹配段落/无码率字段）只对 vendor/未知身份的编码器记录；SBC/AAC 等标准且已知无码率字段的编码器不产生诊断行（日志 1 的 SBC 噪音收敛），诊断类聚合显示不受影响。

### 6.2 必收 fixture

- OPD：V3=`MID/500000`，V5=`{}`，identity=LHDC V3 → 只返回 V3、raw=500000、500kbps。
- 同一 OPD dump + generic LHDC → 应选 V3 的 `MID/500000`；不得出现 V5 段名或 `{}` 与 V3 bitrate 的跨段组合。
- 两个 LHDC 段均有正码率 → ambiguous，不合并。
- 既有 vivo LHDC V5、LDAC -1、AAC 无码率、ABR 与播放三态测试原样通过。
- NX caps=11 与 OPD caps=10 的目标最高采样率仍为 96kHz，禁止因身份识别成功而新增 192 目标。

### 6.3 播放中但栈回报未流式（日志 2 确认的新缺陷）

PLK110 样本：用户播放中（isMusicActive=true）而 LHDC V3 段 `rawBitrate=-1`（栈明确回报未流式）。当前 `realtimeDisplay()` 把该路径归入 notStreaming，首页会显示“未在播放”——与用户实际在听歌直接矛盾。

- `RealtimeDisplay` 从布尔组合改为显式枚举：`NOT_PLAYING（未播放）/ AVAILABLE（有码率）/ NO_DATA（栈不暴露字段）/ STACK_REPORTS_IDLE（播放中但栈回报 -1）`。
- UI：STACK_REPORTS_IDLE 显示“暂无法确认”（或“—”），不归入“未在播放”；诊断保留 `not-streaming` 类别并同时记录 `isMusicActive=true` 上下文（现有证据格式已含）。
- fixture：PLK110 原文入测——isMusicActive=true + LHDC V3 `-1` → STACK_REPORTS_IDLE；既有 LDAC -1 三态测试语义改为按上述枚举断言。
- 该状态不触发“无码率字段/无匹配段落”噪音诊断；只按既有 gate 留一次证据。

## 7. 连接触发合并与过期作废

### 7.1 已确认问题（日志 1）

同一秒内多台设备的 ACL_CONNECTED / ACL_DISCONNECTED 交叉（未知 BLE 设备重连风暴 + 另一台设备接入）会为每个事件各起一个 ApplyWorker：worker 在 ApplyLock 上排队，刷出多条「等待上一次拉满完成…」，拿到锁后重复跑「派生→跳过→建档」；排队期间设备可能早已断开；永不 attach A2DP 的设备每次触发都跑一遍 5s 轮询。`ConnectionReceiver` 与 `ListenerService` 两条触发路径目前都没有任何合并机制。

### 7.2 规则

1. 进程内 per-MAC 触发闸（TriggerGate，纯 Kotlin 可测）：同一 MAC 在合并窗口内（默认 10s，实施确定）已有活动或刚完成的触发 → 新触发直接合并跳过，不排队、不起 worker；窗口外或设备断开后再连则正常放行。
2. ApplyWorker 获得 ApplyLock 后、任何载体 IO 之前，先用本机 API 复查该 MAC 是否仍 A2DP 已连接；已断开则安静结束（一条「设备已断开，跳过排队任务」）。
3. 每个 MAC 最多一个进行中/排队的 worker；风暴期间不叠加。
4. `ConnectionReceiver`、`ListenerService`（运行时 receiver）、启动补触发三条路径共用同一闸；手动「立即拉满」与 QS 磁贴可绕过合并窗口（用户显式意图），但仍受 ApplyLock 与锁后断连复查约束。
5. 5s A2DP 出现轮询保留（A2DP attach 可滞后约 2s，既有注释），但每 MAC 每窗口只跑一次；放弃时的单行日志保留。
6. 非 A2DP 设备（BLE 配件等）不再产生「派生本机最高目标」等完整流程日志，只在放弃时留一行。

### 7.3 测试

- 同 MAC 窗口内 N 个触发只产生 1 次 worker；窗口外再触发放行。
- 锁后断连复查：排队 worker 在拿到锁时设备已断开 → 零载体调用、一条结束日志。
- 两条 receiver 路径 + 启动补触发都过闸；force 路径绕过窗口但不过绕过锁后复查。
- 闸状态在 worker 完成/异常/超时后正确释放，不泄漏（finally 释放 + 单测覆盖异常路径）。
- 既有「FGS 存活广播让位」「双触发互斥」语义不变。

## 8. 暂缓项：aptX 与手动档位（本版不实施）

用户已明确“aptx 先不动，到时候再说”。因此本版计划、实现与验收均不包含：

- 44.1kHz 自动策略或任何 aptX 专属写入策略；
- 手动参数选择、`manualRate`/`manualBits` 等档案字段及相关 UI；
- aptX 专属组合采集、44.1 写读回采样或 Lossless 指示判断；
- 以 aptX 真实设备作为本版发布阻塞项。

§2.2 的协议资料仅保留为未来重新立项的背景。通用身份解析、原始 selectable 与厂商栈诊断若自然覆盖 aptX 数据可以保留，但不得为 aptX 新增专门分支、设置、推荐或发布承诺。

## 9. MediaTek 与厂商栈只读诊断

### 9.1 本版交付的是可定位性，不是虚构的芯片适配

以下情况即使 codecType 落在旧“已知”表内，也应触发一次诊断，不能只监控 vendor(N)：

- `getCodecStatus()`/`getCodecConfig()` 为 null，或蓝牙/A2DP 代理状态异常；
- local/selectable 为空、缺当前 codec 条目，或 local 与 selectable 明显不一致；
- current rate/bits 为 0、多比特或未知；
- supported types 不可用、冲突，或与旧映射冲突；
- readConfig/extended type 反射异常；
- 用户手动复制诊断前的当前快照（若实施时已有同轮数据，不新增 binder 调用）。

### 9.2 最小快照字段

```text
schemaVersion / appVersion / snapshotSeq / trigger(connection|codec-update|playing|manual)
source(home|apply|manual) / timestamp / 机型 / Android+SDK / Build.DISPLAY
Build.MANUFACTURER / Build.HARDWARE / Build.BOARD
SOC_MANUFACTURER / SOC_MODEL（API 可用时；不可用标 unknown）
providerKind / serviceUid / bluetoothState / a2dpProxyState
codecStatusNull / currentNull / localCount / selectableCount / currentSelectable
current 原始 9 字段（同时保留十进制与必要 hex）
localCapabilities 与 selectable 每一条的完整原始 9 字段（紧凑、限条数/字节）
rate/bits/channel 的 hex 与逐 bit 解码；cs1~cs4 原始 hex
supportedTypes 的 nativeType/codecId/codecName 与 API 不存在/空表/异常状态
extendedIdentity 与读取状态；可用的系统 toString 交叉证据（截断）
dumpsys 既有 24 行线索（只有本来就有 dump 时复用）
分阶段异常类型与截断后的 root cause
```

- 不读取序列号、IMEI、root 管理器账号或任意 shell 环境变量；不新增 MAC 收集，沿用现有日志边界。
- 按 `mac + current + local/selectableHash + readStatus + reason` 和状态跃迁去重；空→非空、null→有值等变化必须能留下新样本，同类异常不得随 3 秒/30 秒轮询刷满 200 行。
- 长快照优先紧凑 JSON/条目预算，实施计划给出明确最大条数与最大字符数；超限时记录 truncatedCount，不能静默丢关键 current。
- 只读元数据获取失败不得破坏 readConfig 主结果。

### 9.3 外部采样步骤

1. 优先请反馈者补原图与原始日志；无法提供时，重新采集**同一时刻的系统编解码器界面截图 + StayHiFi raw 日志**，不根据文字描述猜数值。
2. 清空日志→连接耳机→分别在连接后、首次 codec 更新后、稳定播放中留下快照→复制基线日志。复用现有连接轮询/首页刷新，不为采样新增常驻轮询。
3. 反馈时补充手机具体型号/系统构建、耳机与固件、SoC；只有同一 raw 异常签名可复现，并能与系统界面或对照样本形成稳定差异，才进入厂商适配。
4. 在拿到上述证据前，发布说明只能写“增强相关机型诊断”，不能写“修复联发科编解码读取”。

## 10. 对外机型名分层兜底

### 10.1 数据来源与顺序

采用：

1. 精确人工 override（只收有依据的 Build.MODEL，纠正已知目录误差）；
2. 厂商 SystemProperties（现有键 + 经证据补充的现代 OPlus 键）；
3. 离线 Model→营销名表；
4. Build.MODEL。

- override 必须精确匹配，避免区域型号被模糊规则误改；NX741J 的 Ultra 后缀作为首批纠正候选，实施时保留来源注释。
- Google supported_devices.csv 在开发期生成紧凑资产并随版本更新，运行时不得新增网络请求。
- 目录 Retail Branding/Marketing Name 的组合需规则化，不能机械拼出重复品牌或 `ZTE nubia ...` 这类面客文案。
- 全量映射本次研究估算 JSON 约 1.44MB、gzip 约 350KB；体积可接受，但不得每次调用都解析全表。
- 进程内只加载/缓存一次，首屏主线程不做全量 JSON 解析；资产损坏或表未就绪时回退厂商属性/Build.MODEL，不阻塞启动。
- 新 OPlus 属性键需真实 getprop 或用户诊断证据；不因为社区键名存在就宣称 PHZ110 已验证。

### 10.2 验收样例

- PHZ110 → Find X7（属性缺失时可由离线表接住）。
- OPD2409 → OPPO Pad 4 Pro（品牌格式统一）。
- NX741J → nubia Z80 Ultra（人工 override 纠正目录颗粒度）。
- DPD2438B → 继续优先使用已工作的 vivo 属性，不依赖目录。
- 未知型号、空属性、资产缺失均回退 Build.MODEL，不崩溃、不显示空白。

## 11. 实验 Root 授权

### 11.1 范围与默认行为

- 新增 `RootShellProvider` 作为第三种 `ShellProvider`，基于 libsu RootService 与既有 `ICodecService`。因现有 `CodecService` 已继承 `ICodecService.Stub`，增加薄 `RootCodecService : RootService`，其 `onBind` 只返回现有 `CodecService` binder；蓝牙逻辑仍只有一份。
- 默认仍是 Shizuku/Stellar；老用户升级后选择不变。
- Root 仅替换特权通道，不改变 codec 目标、能力、采样率或音质宣传。`CodecService` 注释中的 shell uid 假设需改为可诊断的 serviceUid；**uid 0 不在所有 ROM/SELinux 下必然等价于 shell 的蓝牙权限**，read/write/dump 必须分别外部验证。
- 不做 Magisk 模块/init 守护进程，不启用 libsu daemon mode；RootService 本身不保证 App 收到 BOOT_COMPLETED，也不保证绕过所有 OEM 冻结。
- 首版严格后台非交互：Boot/Listener/QS/ApplyWorker 不主动请求 Root；冷进程没有前台已验证 transport 时只进入 `NEEDS_FOREGROUND_REVALIDATION`，跳过特权操作并引导前台重验。这个额外步骤是明确的产品代价，不得在物料里隐瞒。

### 11.2 首次教程与设置入口

首次教程第 2 页先提供两个明确选择：**Shizuku / Stellar（默认）**与**Root（实验）**。选择前者时保留现有两张载体卡；选择后者时显示 Root 状态卡。不得根据哪张卡先就绪而自动改变选择。

Root 卡文案：

> **Root（实验）**
>
> 适用于已经 Root 的设备。仅提供系统权限通道，不会提高耳机或编码器能力上限。兼容性仍在收集反馈，可随时手动切回 Shizuku/Stellar。

- 仅查看或选择 Root 卡不改变持久 provider；首次 Root 授权、服务绑定且 `dumpDevices()` 健康检查成功后，才通过 §11.3 的安全切换提交选择。已经持久化为 Root、后续又被撤权时保留选择并提示修复，不静默回退。
- Root 使用独立状态，不硬套“已安装/运行中/已授权”三灯：至少展示 Root 可用性、授权状态、服务连接状态和最近错误。`READY` 最低条件是 `getShell` 返回 + `shell.isRoot=true` + 非空 binder + `dumpDevices()` 返回无 error。
- 用户点击“请求 Root 授权”才可触发交互；下一步只检查**当前所选 provider** 是否 ready，Root 已选时不得因 Shizuku ready 放行，Shizuku 组维持原规则。
- 教程耳机页 2.5 秒刷新及公共 `ensureCarrierReady` 对 Root 只能读取状态，绝不能请求授权或创建 shell，避免轮询反复弹窗。
- 设置页增加同一“特权方式”入口，已升级用户无需清数据重装即可参与实验；“改用 Shizuku/Stellar”任何时候都可手动选择，不要求 Root 当前可用，切换期间禁用重复操作。
- Root 状态下首页修复入口打开设置中的 Root 区域，不再跳 Stellar/Shizuku 管理器指引。失败后的已提交选择及错误状态清晰展示，不静默换通道。

### 11.3 选择持久化与切换安全

- DataStore 保存字符串 `shizuku / root`，不保存 enum ordinal；缺失回 `shizuku`，未知值回默认并记录。Stellar 继续与 Shizuku 共用现有 rikka 通道，不另立第四种 provider。
- 增加进程级 `ProviderCoordinator`：以 `StateFlow<ProviderKind?>` 异步恢复，初始 null；首读完成前阻止所有特权 IPC，不得先构造默认 Shizuku 再“纠正”。**不得在 Application/BootReceiver 主线程新增一次 `runBlocking { provider.first() }`**；由实际使用者在后台协程等待，非特权 FGS/本地蓝牙读取不被首读阻塞。
- MainActivity、ApplyWorker、ListenerService、QsTileService 当前各自直接创建 Shizuku provider 的路径全部迁移到 Coordinator，业务代码不得再直接 `new ShizukuShellProvider/RootShellProvider`。ApplyWorker 必须在取得 ApplyLock 后才解析当前 provider，避免排队 worker 使用旧通道。
- Coordinator 为调用方提供 `owner + generation` 的 session/lease。首次 Root 授权使用未提交的 candidate session，`activeProvider` 仍保持原值；只有授权+bind+健康检查全部成功才提交，失败/取消只清理 candidate。Shizuku 保留现有各 owner 绑定/解绑所有权；Root 只由教程/设置前台建立一个进程级 anchor transport，后台仅复用 READY transport 的 lease，不自行创建 shell/RootService。Activity 销毁不得关闭 Application Root anchor；只有手动切换/显式断开才解绑。
- Root transport 的所有 IPC 用独立协程 Mutex 串行，防止并发 bind、重复回调和 A2DP 初始化竞争；ApplyLock 继续负责自动写入、手动写入、恢复默认之间的业务互斥，两把锁职责不得混用。
- 切换事务：进入 SWITCHING 阻止新 session→有上限地等待 ApplyLock 与活动 IPC→超时则保留原选择并提示稍后重试→作废旧 generation→主线程幂等 unbind 旧 Root anchor→持久化新选择（写盘失败不算成功）→发布新 generation。不得以重启 ListenerService 代替代次失效；旧回调不得写 UI或重绑。
- DataStore 只保存“希望使用的通道”，不当作 Root 授权证明；每个新 App 进程的 Root 状态都从 `UNVERIFIED` 开始，只有前台显式验证并建立 anchor 后才进入 READY。
- Root provider 的 `isRunning/hasPermission` 必须反映上述当前进程可用状态；未验证时 `bind` 返回明确的 `NEEDS_FOREGROUND_REVALIDATION`，不得为了探测而启动可能弹窗的 shell。
- 后台 `isRunning/hasPermission/bind` 不得制造 Root 授权弹窗；只有教程/设置中的显式动作能请求授权。Boot/Listener/QS/ApplyWorker 在冷启动未验证时仅记录一次并引导前台重验，绝不自动尝试 Root，也绝不自动改走 Shizuku；transport 意外断开后同样只在进入前台后重验。

### 11.4 Root 状态必须按可观察事实表达

libsu 默认 shell 建立可能回落普通 `sh`，因此：

1. `getShell()` 可能长时间阻塞，只在用户显式点击后的专用 IO 执行器调用；返回不等于 Root 成功，必须再验证 `shell.isRoot`。Application 不预热 shell、不设置 `FLAG_MOUNT_MASTER`；RootService 首次读写诊断还记录 `serviceUid`。
2. `isAppGrantedRoot()` 的 `Boolean?` 只表示当前可观察授权状态，不等于发起授权。不得宣称官方 API 总能区分“用户拒绝”“没有 su”“Root 管理器不兼容”；无法区分时 UI 写“未获得 Root 权限（可能被拒绝，或设备没有可用 Root）”，日志保留原始三态、isRoot、异常与阶段。
3. 至少区分：冷启动未验证、请求中、`GRANTED_NOT_READY`、`NOT_GRANTED`、`SHELL_FAILURE`、`AUTH_TIMEOUT`、`BINDING`、`READY`、`BIND_NULL`、`BIND_TIMEOUT`、`BIND_FAILED`、`DISCONNECTED`、`NEEDS_FOREGROUND_REVALIDATION`。失败文案只使用可观察事实。
4. 已授权但 bind/健康检查失败不能直接提示用户“请重新 Root”；应与授权失败分开记录。
5. 后台只使用当前进程已经验证的 READY transport；授权不明时等待用户进入教程/设置处理。
6. 授权请求必须单飞；离页、改选通道或再次点击时使旧 attempt/generation 失效，迟到结果不得提交已取消的切换。约 30 秒 UI watchdog 只代表自有等待结束，不代表底层任务已取消，不得立即并发第二次请求。
7. `RootService.bind/unbind` 按官方要求只在主线程调用，AIDL 业务调用继续在 IO；不在主线程等待 latch，不持 synchronized monitor 跨绑定等待。普通非 daemon 服务使用 unbind，不用 `RootService.stop()`。
8. 同一 transport 同时只允许一个 bind，约 10 秒 bind watchdog 后将 connection/generation 标记过期；迟到 `onServiceConnected` 不得提升 READY，必须在主线程立即 unbind。不得声称“超时已取消底层 pending bind”。主动解绑与远端死亡回调要区分。
9. READY 还需 `dumpDevices()` 健康检查无 error；仅 binder 非空不算通道可用。

### 11.5 最小安全与工程边界

- 只允许固定 AIDL 能力与现有固定绝对路径 `/system/bin/dumpsys bluetooth_manager`；不提供任意命令输入、脚本执行、通用文件读写、getprop/setprop、mount、Root 网络接口或 `exec(String)` 桥。UI/档案/MAC/JSON/日志均不得拼成 shell 命令。
- 固定使用 `com.github.topjohnwu.libsu:service:6.0.0`（已依赖 core），在 `settings.gradle` 的 `dependencyResolutionManagement` 增加 JitPack；不另声明 core/nio/io，不使用 `+`、SNAPSHOT 或 master。实施时核对依赖闭包与上游元数据。
- 不新增 Android“Root 权限”（su 不是 runtime permission），不新增 Magisk/KernelSU 等包可见性查询，不设置 `FLAG_MOUNT_MASTER`。
- 现有 Shizuku `CodecService` 与 ShizukuProvider 声明保持不变。`RootCodecService` 是 libsu 的 ContextWrapper 体系，**不添加普通 Android `<service>` 声明，也不靠 `android:process` 获得 Root**；以显式 Intent 指向 RootService 类，不能新增 exported Service。
- libsu 6.0.0 consumer ProGuard rules 已覆盖 RootService/Initializer 子类；不做全量 `keep com.topjohnwu.superuser.**`。保留我方 AIDL/Shizuku 既有最小 keep，检查 R8 mapping；RootService 的 minified 外部启动仍是独立验收，不能提前宣称。
- Root 服务内的异常通过既有 JSON error/绑定结果回到 App 进程记录；不得假设不同进程的 LogStore 自动共享。
- 日志不包含密码、密钥、完整环境变量、su stdout/stderr、Root 管理器授权日志或用户可输入命令；异常信息截断并限制层级。

### 11.6 Root 诊断日志

在 App 进程记录：

```text
provider / event / source(onboarding|settings|boot|listener|qs|apply)
attemptId / generation / selectedProvider / activeProvider
rootGrantedState=true|false|null / shellStatus=UNKNOWN|NON_ROOT_SHELL|ROOT_SHELL
outcome / observedReason / reasonHint（推测原因必须另列，不与事实混写）
durationMs / timeoutMs / bindCallback / healthOp / healthResult / serviceUid
suppressedCount / sanitizedRootCause
```

- 每次 Root 尝试最多 4 条核心日志：`root_attempt_start / root_auth_end / root_bind_end / root_health_end`；provider 切换另记一条终态。重复事件按 `provider+event+outcome+generation+errorClass` 去重，下一次状态变化或导出时附 `suppressedCount`。
- 后台重复未就绪按状态变化记录，不按 ACL、2.5 秒教程刷新或 30 秒轮询刷屏；清空日志时重置 gate 和 suppressedCount。
- 既有 readConfig/writeConfig/dumpBtState 结果继续记录，确保外部样本能区分“Root 授权失败”与“Root 已通、厂商蓝牙 API 失败”。
- 复制日志 header 增加 logSchemaVersion、当前持久 provider、transport 状态与是否需前台重验；不自动识别或上传 Root 管理器类型。反馈模板请用户另行填写 Root 实现/版本、su 版本、ROM、Android 与 SELinux 状态。

## 12. 日志、性能与隐私预算

1. LogStore 仍为每进程 200 行环形缓冲、内存态、退出清空；反馈文本为 1 行报告头 + 最近 199 行，不新增持久化或上传。
2. 首页既有 3 秒 dump 次数不增加，ListenerService 30 秒路径不新增 dumpsys。
3. supported types 只在特权服务侧缓存/限频；失败局部隔离，不扩大锁内无超时 IO 的既有遗留问题。
4. 所有新诊断都有去重与大小上限；异常不得每轮刷屏。
5. 不改变 GitHub Releases 自更新是唯一网络用途的产品口径；离线机型表不在运行时下载。
6. Root 为非默认实验通道；aptX 整项不实施。恢复默认与手动退回必须可用。
7. 保留现有“复制日志”供本机原始排障，新增“复制反馈日志”统一脱敏：MAC 与蓝牙设备名映射为当次报告内稳定的 `device1/device2`，异常消息也同样替换；映射不持久化、不跨报告跟踪，清空时重置。耳机具体型号/固件由反馈模板另行询问，避免自定义设备名泄露。
8. `LogStore` 当前共享 `SimpleDateFormat` 且格式化在锁外，不是线程安全的；Root/后台日志增加前必须改为线程安全格式化或纳入同一同步区，并补并发日志测试。

## 13. 自动化测试要求

### 13.1 纯 Kotlin/JUnit

- 身份：系统目录优先、冲突回退、空/UNKNOWN/重复 nativeType、extended 辅助、旧高采样率回退、vendor 保底。
- 质量档：系统确认 LHDC 后按 cs1 magic/低字节显示；非 LHDC 与非法值不解码；不修改 BEST_CS1。
- OPD/NX/PHZ/iQOO fixture 全收录，特别验证 caps=10/11 不派生 192。
- 多 LHDC 段：精确 V3、generic 单有效段、双有效段 ambiguous、禁止跨段拼字段。
- selectable 多条目诊断聚合、写入同条校验：192+16 与 96+24 分属不同条目时不得拼出 192+24；完整能力模式的整个降档链不得出现未报告 96；degraded 旧 `descending(base)` 用例原样通过。全 0、缺当前条目、current 0/多比特、不写入与不满格；`codecStatus/current == null`、API 异常、local/selectable 为空分别可辨，空→非空跃迁可留证。
- 触发闸（§7）：窗口内同 MAC 多次触发合并为一次；锁后断连复查零载体调用；三路径共闸、force 绕过窗口不绕过复查；异常路径正确释放闸。
- 实况四态（§6.3）：isMusicActive=true + 栈 -1 → STACK_REPORTS_IDLE 显示「暂无法确认」，不归入「未在播放」；PLK110 fixture 入测。
- 所有 fullBars/首页文案与 ApplyWorker 使用同一有效策略；既有默认策略测试不通过“改预期”来迁就实现。
- 机型名：override/属性/目录/Build.MODEL 顺序、空值、损坏资产、精确样例。
- Root 状态：选择持久化与损坏回退、持久选择不等于授权、后台不请求交互、冷启动 UNVERIFIED、isRoot=false 不算授权、请求单飞与迟到结果失效、健康检查后才 READY、断开需前台重验。
- Root 协调：provider 首读前零 IPC、ApplyWorker 锁后取最新 provider、切换代次/锁/写盘失败、anchor lease 不随 Activity 销毁、unbind 幂等、bind timeout 后迟到连接被解绑而非复用、手动回退与日志去重。
- 用 fake RootBackend/ShellProvider、fake ICodecService 与可控 scheduler/executor 测无 Root、未授权、超时、绑定失败、断开；本地测试不依赖真实 su。
- 日志：并发格式化不崩溃/不乱序，反馈导出对 MAC、设备名和异常内嵌副本一致脱敏，200 行与 suppressedCount 预算。

### 13.2 构建与静态验证

- `./gradlew test`、`./gradlew lint`、`./gradlew assembleDebug` 全量通过，新增测试必须先有旧实现失败证据。
- 按现有签名配置构建 minified release；检查 Manifest merger、AIDL、R8 mapping、libsu 依赖闭包，确认没有意外权限、Root manager 查询或 exported Service，不为测试绕过发布签名要求。
- 最终发布包按现有签名与校验流程验证；不能用“本地无 Root 机”代替构建验证，也不能把 fake 结果说成 Root 真机通过。
- 已验证本机 JDK 27：`JAVA_HOME=/opt/homebrew/opt/openjdk@27/libexec/openjdk.jdk/Contents/Home`，并将其 `bin` 放到 `PATH` 最前；2026-10-27 基线 `./gradlew test --rerun-tasks` 实际执行为 119 tests / 0 failures / 0 errors，后续任务须重新运行并报告新结果。

## 14. 外部验收与实验降级

### 14.1 编码器、触发与诊断

- OPD2409：若 supportedTypes/extendedIdentity 返回有效身份，确认编码器/质量档/实况来自同一 LHDC V3 段，V5 不再被误标，96kHz 不被强行改成 192；若两者均无效，记录“外部 ROM 身份源不可用、问题尚未闭环”及回退诊断，不得宣称已修复，也不另用 cs1 猜身份。
- NX741J：记录 supportedTypes/extendedIdentity/完整 selectable；只在系统证据支持时改变身份显示。
- PHZ110：既有 192 写入与 LHDC 实况不回归，日志不再无上下文显示 vendor(19)。
- MediaTek：完成 §9.3 三阶段基线快照采集；本版验收首先是证据完整，不是预设修复结论。
- 触发合并回归（iQOO 15 日志 1 场景）：多设备同秒 ACL 风暴下每 MAC 只跑一次 worker，无重复「派生/跳过」，无排队 worker 对已断开设备执行载体 IO。

### 14.2 Root 外部矩阵

本地无 Root 机时，允许保留“实验”发布，但稳定宣称前至少需要：

1. 一台 Magisk 与一台 KernelSU 用户样本（APatch/其他 XXSU 分支可后补），分别记录管理器/su 版本、ROM、Android、SELinux，完成授权→绑定→`dumpDevices/readConfig/dumpBtState`→一次安全写入/读回；不能以 binder 连通代替各项 AIDL 验收。
2. 拒绝/撤权、无可用 su、普通 sh fallback、null binder、绑定超时、迟到绑定、Root 服务断开/管理器冻结均进入正确状态；迟到连接被清理，可手动切回，无重复弹窗。
3. 同一耳机做 Shizuku 与 Root 对照，selectable/current 不因载体变化凭空增加；Shizuku/Stellar 原路径回归，默认选择未被改变。
4. SELinux enforcing 的重点 ROM 与最终 minified release 都必须实测 RootService 启动、隐藏 API 和 dumpsys；uid 0 不等同权限保证。
5. 重启后只验证实际承诺的范围；若 App 自启动仍被 OEM 拦截，不把它包装成 RootService 已解决。
6. 冷启动且当前进程未验证 Root 时，Boot/Listener/QS 不弹 Root 管理器界面、不自动尝试 shell，只留下 `NEEDS_FOREGROUND_REVALIDATION`；用户前台重验后才恢复通道。
7. 外部样本不足时继续实验，不宣称“全面支持 Magisk/KernelSU”，也不阻塞其他已验证修复；只有实际通过的“管理器版本 + ROM + Android”才能写成限定已验证。

## 15. 实施顺序与依赖

1. **只读基础**：snapshot/supported types、身份裁决、共享 formatter、完整诊断。
2. **解析与能力正确性**：多段 parser、selectable 同条能力校验、参数不完整保护、OPD fixture。
3. **触发合并与实况四态**：TriggerGate（§7）、RealtimeDisplay 枚举化（§6.3）、标准编码器诊断收敛。
4. **机型名**：属性补充、离线资产、override 与缓存。
5. **实验 Root**：ProviderCoordinator、RootService、教程与设置、前台 anchor、切换与日志；不得与身份策略耦合。
6. **整分支复审与外部反馈包**：检查默认行为、锁/代次、日志预算、ProGuard、实验文案，再决定是否发布。

若 supported types 在目标 ROM 全部无效，身份回退仍可发布，但不得用 cs1 猜测替代；若 Root 外部验证失败，实验入口可默认隐藏/关闭，其他工作流不因此回滚。

## 16. 留给 builder 的事项

- 具体数据类/文件名、JSON 辅助函数组织与缓存实现，只要保持本文语义和既有 Kotlin 风格。
- supported types 缓存失效策略、快照最大条目/字符数；必须在计划中给出明确预算。
- `ProviderCoordinator` 的具体类/协程结构、session/lease 与 scheduler 注入方式，但必须满足异步首读、后台非交互、Root anchor、写入互斥和旧代次失效；libsu 版本已固定为 6.0.0，不另选。
- 机型表生成脚本的具体形式、品牌格式化与缓存 preload；运行时不得联网或每次全量解析。
- 纯 UI 排版细节；本文规定入口、默认状态、文案事实边界与逃生路径。

以下不得由 builder 自行放宽：身份可信门槛、selectable 写入边界、aptX 不宣称 Lossless、MediaTek 不硬编码、Root 不提供通用 shell、不静默换通道、日志隐私预算。

## 17. 主要依据

- AOSP：[BluetoothA2dp](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/android-16.0.0_r1/framework/java/android/bluetooth/BluetoothA2dp.java)、[BluetoothCodecType](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/android-16.0.0_r1/framework/java/android/bluetooth/BluetoothCodecType.java)、[BluetoothCodecConfig](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothCodecConfig.java)、[A2dpCodecConfig](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/a2dp/A2dpCodecConfig.java)（Android 15/16 与 main 对照）。
- [OnePlus Buds Pro 2 官方产品页](https://www.oneplus.com/cn/oneplus-buds-pro-2)；[Google Play supported_devices.csv](https://storage.googleapis.com/play_public/supported_devices.csv)。
- aptX：[Qualcomm 官方 aptX Lossless 公告](https://www.qualcomm.com/news/releases/2021/09/qualcomm-adds-bluetooth-lossless-audio-technology-snapdragon-sound)、[固定版本 Qualcomm 衍生 aptX Adaptive 实现](https://github.com/AOSPA/android_packages_modules_Bluetooth/blob/9c594d1eb40657ca6d1bc392d085b88807fed29d/system/stack/a2dp/a2dp_vendor_aptx_adaptive.cc)（仅作能力差异证据，不作受影响设备栈证明）、[AOSP BluetoothCodecStatus](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/BluetoothCodecStatus.java)。
- MediaTek 边界参考：[公开 Bluetooth audio HAL/offload 转换层](https://github.com/LineageOS/android_hardware_mediatek/blob/43d88bb61a8689d75f57e98ffd4115a970832648/aidl/bluetooth_audio/aidl_adapter/MTKBluetoothAudioProvider.cpp)，它不是 `getCodecStatus` 实现，不能用于直接归因反馈。
- Root：[Shizuku 官方启动文档](https://shizuku.rikka.app/guide/setup.html)、[libsu 6.0.0 README/依赖](https://github.com/topjohnwu/libsu/blob/6.0.0/README.md)、[Shell](https://topjohnwu.github.io/libsu/com/topjohnwu/superuser/Shell.html)、[Shell.Builder](https://topjohnwu.github.io/libsu/com/topjohnwu/superuser/Shell.Builder.html)、[RootService](https://topjohnwu.github.io/libsu/com/topjohnwu/superuser/ipc/RootService.html)、[示例 Manifest](https://github.com/topjohnwu/libsu/blob/6.0.0/example/src/main/AndroidManifest.xml)。
- libsu 6.0.0 实现边界：[MainShell](https://github.com/topjohnwu/libsu/blob/6.0.0/core/src/main/java/com/topjohnwu/superuser/internal/MainShell.java)、[Utils](https://github.com/topjohnwu/libsu/blob/6.0.0/core/src/main/java/com/topjohnwu/superuser/internal/Utils.java)、[RootServiceManager](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java)、[consumer ProGuard rules](https://github.com/topjohnwu/libsu/blob/6.0.0/core/proguard-rules.pro)。文档未保证的 pending/callback 行为按固定源码版本处理，不外推到其他版本。
- 本仓库 v1.2.1 源码、2026-10-25 LHDC 诊断设计与 v1.2.1 用户原始反馈。
