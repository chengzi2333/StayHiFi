# StayHiFi 1.2.2 README 更新报告

- 日期：2026-10-28
- 分支：worktree-stayhifi-next-codec-root（HEAD 5f567d9）
- 提交：8cb110c
- 范围：仅 `README.md` / `README.en.md` / `README.zh-TW.md` / `README.ko.md`，未触碰任何代码，未替换截图文件（主 agent 后续处理）。

## 各文件差异摘要

### README.md（简体中文）
- 「为什么需要」段：删除「只做一件事」，改为自动拉满 + 自定义音质微调/恢复自适应的双重表述。
- 功能特性：「两态状态卡」→「状态卡一眼看懂」（多态：已达最高/未拉满重试/自定义生效中/待授权）；新增「自定义音质」（四参数按当前连接能力手调、置灰含义、降档、恢复自适应、不冒充满血、默认规则不变）；新增「身份与实况诊断」；「录制向导」→「记录当前参数」（入口落设备页 + 离线机型名）；「QS 一键查看」→「QS 磁贴一键触发重拉，无连接时打开 App」；自动拉满条目补充触发合并/断连保护。
- 工作原理：默认 Shizuku/Stellar 免 root + 可选实验 Root 通道（只替换特权通道、不提升上限、覆盖仍需外部样本、未宣称稳定支持）；新增连接与特权读取解耦说明；IPC 范围扩展为 Shizuku/Stellar/Root。
- 安装/使用：五步引导（含选择参数来源）→四步引导（选择特权方式→授权→连接耳机→保活）；连接页新增「排障日志」入口说明；使用第 3 条补充自定义音质与未授权可见耳机名的行为。
- 反馈：改为引导复制「复制日志」的脱敏输出（MAC/设备名→deviceN），明确不要补充未脱敏原文。
- 隐私：本机 IPC 载体扩展为 Shizuku/Stellar/Root，均为本机通信；无上传、GitHub Releases 版本检查为唯一网络用途的表述不变。

### README.en.md / README.zh-TW.md / README.ko.md
与中文版结构/语义一一对应，各自自然表达（非逐字机翻）：four-step onboarding、Custom Quality/自訂音質/사용자 지정 음질、Root channel (experimental)/Root 通道（實驗）/Root 채널(실험)、redacted logs with deviceN、QS tile re-apply、identity/live diagnostics、offline model names 等关键语义全部对齐。

## 核对依据（代码事实）
- 引导页数：`OnboardingScreen.kt` PAGE_COUNT=4（欢迎/特权方式/连接/保活），连接页含「排障日志」入口（OnboardingAction.OpenLogs）。
- 特权载体：CarrierPage 提供「Shizuku / Stellar（推荐）」与「Root（实验）」，Root 描述明确「不提升音质上限，随时可切回」。
- 状态卡文案：`HomeTargetStatus.kt` 七态（等待耳机连接/已连接授权后可用/自定义参数生效中/暂无法读取当前参数/最高能力暂无法确认/已达本机最高/未拉满点按钮重试），徽标「自定义」不冒认「满血」。
- 自定义音质：`ManualEditorSheet.kt` 四参数手调，置灰=当前手机或耳机暂不支持，含「恢复自适应」。
- QS 磁贴：`QsTileService.kt` 点击触发拉满，无连接设备时打开 App。
- 日志：`LogsScreen.kt` 唯一「复制日志」入口；`FeedbackLogSanitizer.kt` 将 MAC/设备名映射为 deviceN。
- 记录当前参数入口：设备页空态「记录当前参数」（DevicesScreen.kt）。

## Concerns
1. 截图文件未替换（按任务要求留给主 agent）；当前截图说明文字仍为「首页状态卡/设备页/设置页」，与新 UI 基本吻合，但若主 agent 替换截图后首页出现「自定义音质」按钮，alt 文字可考虑补充。
2. Root 兼容性表述统一为「仍在收集外部样本、未宣称稳定支持」，与任务口径一致；后续若外部样本到位需再更新。
3. 韩文版由本 agent 生成，过程中发现并修正了 4 处音节误生成（'\ud734\ud0dc\ud3f0'->'\ud734\ub300\ud3f0'、'\ud3f4\ud081'->'\ud3f4\ub9c1'、'\uc628\ubcfc\ub529'->'\uc628\ubcf4\ub529'、'\uc744 \ubcc4 \uc8fc'->'\uc744 \ubcf4\ub0b84 \uc8fc'，均为合法但不正确的韩文音节）；建议母语者快速通读一遍韩语 README，以防仍有细微不自然处。
4. README 未写具体版本号「1.2.2」于标题（保持与既有风格一致，特性条目中标注 v1.2.2 的位置仅「离线机型名」一处）；如发布说明需要，可由主 agent 决定是否加版本徽章。

## 补充修订（ff453b4 Docs: 收紧 1.2.2 README 事实边界）

主 agent 审查后四语同步收紧 4 处事实边界，每文件 5-6 行：
1. 去除「任何蓝牙耳机零适配/单包通用所有机型」绝对化承诺——改为「目标参数按当前连接的实际能力现场派生，写入后读回验证」；APK 改为「单一 APK，无需按 ABI 挑选版本」（不等于所有机型功能兼容）。
2. 「未授权也能读 A2DP 连接与耳机名」明确为「未配置 Shizuku/Root 时（仍需系统的蓝牙连接/BLUETOOTH_CONNECT 权限）」；使用一节同步。
3. 实况诊断改为「支持读取的机型可显示实际传输码率，读取不到会明确提示」，保留四态展示事实。
4. 英文「near-zero battery drain, no polling」→「event-driven, no idle polling」；韩文「폴킁 루프 없음」→「유휴 폴킁 없음」；简/繁「无轮询空转/無輪詢空轉」按审查意见保留。
