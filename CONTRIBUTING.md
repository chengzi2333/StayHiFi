# 贡献指南

感谢你对音质满格 FullBars 的兴趣！本项目 GPL-3.0-or-later，提交 PR 即表示你同意按该协议贡献代码。

## 设备档案贡献（最欢迎的贡献）

一台「手机 + 耳机」组合的满血参数，是其他同型号用户能开箱即用的关键。codecType / cs1 质量档为厂商私有值，无法跨设备预测，只能靠真机实测积累。

### 9 字段格式

每份档案是一个 JSON 文件，放在 `device-profiles/` 下，文件名建议 `耳机型号.json`（小写连字符）：

| 字段 | 类型 | 说明 |
|---|---|---|
| codecType | int | 编码器编号；0~4 为 AOSP 标准（SBC/AAC/aptX/aptX-HD/LDAC），≥5 为厂商私有（如 10=LHDC，仅 OPPO/vivo 系栈实测成立） |
| priority | int | 优先级，满血通常 1000000 |
| sampleRate | int | 采样率**位掩码**：1=44.1k 2=48k 4=88.2k 8=96k 16=176.4k 32=192k（可叠加，如 43=44.1k~192k 全支持） |
| bitsPerSample | int | 位深**位掩码**：1=16bit 2=24bit |
| channelMode | int | 声道模式（如 2=立体声） |
| cs1 | long | codec-specific 1，质量档，厂商私有（如 LHDC: 50888712=音质优先 1000kbps，33914889=自适应） |
| cs2 | long | codec-specific 2 |
| cs3 | long | codec-specific 3 |
| cs4 | long | codec-specific 4 |

完整范例见 [`device-profiles/enco-x3.json`](device-profiles/enco-x3.json)。

### 抓取方式（任选其一）

1. **App 内捕获（推荐）**：开发者选项手动把参数调到满血 → App 首页/录制向导「读取当前参数」→ 把读到的 9 字段原样抄进档案
2. **readConfig 直读**：通过特权 shell 调用 `getCodecStatus()` 读回当前配置

### 提交前 checklist

- [ ] JSON 可被标准解析器解析，`meta` 段注明手机机型 / ROM / 日期 / 抓取方式（source: `captured` / `preset`）
- [ ] 断连重连 ≥3 次，写入稳定生效、不被 ROM 回滚
- [ ] 不含 MAC 地址等个人信息
- [ ] 使用 [设备档案 issue 模板](.github/ISSUE_TEMPLATE/device-profile.md) 或直接发 PR

## 代码贡献

- 构建与测试：`gradle assembleDebug` + `gradle testDebugUnitTest`，保持全绿
- 不提交 keystore、`local.properties` 等本地文件（`.gitignore` 已覆盖，请勿强行 `-f`）
- commit message 用中文简述改动动机，说明「真机实证/复现路径」类的修复尤其受欢迎
