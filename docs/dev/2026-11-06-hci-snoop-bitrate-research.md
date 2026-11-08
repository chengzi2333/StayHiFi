# HCI snoop 实际码率可行性研究（v1.3 立项，2026-11-06）

> 状态：立项文档，未实施。用户已拍板 v1.2.4 只做口径优化，本功能进入 v1.3 研究。
> 关联：docs/dev/2026-11-06-v1.2.4-orchestration.md 问题 #1。

## 目标

让任何机型都能看到**真实空中传输码率**，不再依赖厂商 dumpsys 是否回报（已实证仅 vivo 栈可读，ColorOS 有段无值、Xiaomi 无段）。

## 原理

开启系统「蓝牙 HCI 信息收集日志」后，host↔controller 的全部 HCI 流量落入 btsnoop 文件（pcap 变体格式）。A2DP 媒体流 = ACL 连接上的 L2CAP AVDTP media channel，RTP payload 即编码后音频帧。统计单位时间内媒体包 payload 字节数 → 真实编码码率（kbps），与编码器无关（SBC/AAC/aptX/LDAC/LHDC 通吃），且含重传可算丢包率。

## 前提与权限

- 用户手动开开发者选项「蓝牙 HCI 信息收集日志」（多数 ROM 需重启蓝牙生效）→ **产品摩擦点**，需引导 UI。
- 读 btsnoop 文件需 Root（路径因 ROM 而异：`/data/misc/bluetooth/logs/btsnoop_hci.log`、`/sdcard/Android/data/btsnoop_hci.log`、`/data/vendor/bluetooth/…` 等；Shizuku 大概率不够，待验证）。
- 隐私边界：文件含**全部**蓝牙流量（含通话、其他设备）。必须本地即时解析、只提取计数、原始数据不落盘不进反馈日志——这一点要写进设计与隐私说明。

## 技术要点（原型需验证）

1. **格式**：btsnoop = pcap 变体（magic `btsnoop\0`，epoch 差 0x00dcddb30f2f8000），逐记录解析 HCI ACL DATA 包。
2. **通道识别**：跟踪 HCI connection handle → L2CAP CID 分配（或启发式：取流量最大的 handle）。AVDTP media 通道的识别需解析 L2CAP 建链信令，或退化为"连接建立后流量最大的 ACL handle"。
3. **采样方式**：tail 增量解析（记录 offset），每 N 秒统计窗口字节数 → kbps；文件会轮转/截断，需处理 inode 变化。
4. **开销**：snoop 本身由系统写盘（用户已知情开启）；App 侧只读 tail，CPU 占用应可控（需实测）。

## 收益-代价

| | 内容 |
|---|---|
| 收益 | 真实空中码率，全 codec 通用；可附赠丢包/重传指标（独家卖点，回应最多反馈的问题#1） |
| 代价 | 用户操作门槛（开发者选项+重启蓝牙）；Root 限定；工程量中等偏大（解析器+通道跟踪+轮转处理）；隐私设计成本 |
| 风险 | 部分 ROM 的 snoop 路径/开关行为差异；长期开启 snoop 的系统开销需实测 |

## 备选：statsd 字节计数器

Android statsd 有蓝牙字节类 atom（如 BluetoothBytesTransfer）。若可经 shell 权限读 `dumpsys stats`，可免开发者选项。跨版本稳定性与权限未验证——**原型第一阶段先查这个，若可用则摩擦远小于 snoop**。

## 建议验证顺序

1. 查 statsd atom 在本机（iQOO 15）与一台 ColorOS 机的可用性与粒度（`dumpsys stats` 各权限级）。
2. Root 下读 btsnoop 路径枚举 + 最小 tail 解析原型（命令行脚本即可，不进 App）。
3. 两路线都有结论后再做产品设计（引导流、开关、展示位）并回报用户拍板。

## 明确的非目标

- 不采集/上传原始 HCI 数据；不进反馈日志。
- 不为它改 v1.2.4 的发布节奏。
