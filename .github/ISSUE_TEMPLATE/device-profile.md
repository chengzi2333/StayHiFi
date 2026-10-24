---
name: 设备档案提交
description: 提交一台新「手机 + 耳机」组合的满血参数档案（device-profiles/*.json）
title: "[device-profile] 手机机型 + 耳机型号"
labels: ["device-profile"]
---

<!--
感谢贡献设备档案！提交前请确认已阅读 CONTRIBUTING.md 的 9 字段格式说明。
范例见 device-profiles/enco-x3.json。MAC 地址等个人信息请勿提交。
-->

**手机机型 / ROM**：
<!-- 例：iQOO 15 / OriginOS 7 -->

**耳机型号**：
<!-- 例：OPPO Enco X3 -->

**codecType**：
<!-- 厂商私有编号，可在 App 读取结果或开发者选项中确认；例：10（LHDC V5，仅 OPPO/vivo 系栈） -->

**9 字段配置**：

| 字段 | 值 | 说明 |
|---|---|---|
| codecType | | |
| priority | | |
| sampleRate | | 位掩码：1=44.1k 2=48k 4=88.2k 8=96k 16=176.4k 32=192k |
| bitsPerSample | | 位掩码：1=16bit 2=24bit 4=32bit |
| channelMode | | |
| cs1 | | codec-specific 1（质量档，厂商私有） |
| cs2 | | |
| cs3 | | |
| cs4 | | |

**抓取方式**：
<!-- 例：开发者选项手动调至满血 → App 内「读取当前参数」捕获；或机型预设/其他 -->

**稳定性验证**：
<!-- 断连重连 ≥3 次，写入是否稳定生效？有无被 ROM 回滚？ -->
