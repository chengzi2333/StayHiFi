[简体中文] | [繁體中文](README.zh-TW.md) | [English](README.en.md) | [한국어](README.ko.md)

# StayHiFi 音质常驻

**一次设置，持续生效。**

连接耳机后，自动把蓝牙音频（A2DP codec）参数拉到这台设备协商得出的上限——全程无感，不用再每次进开发者选项手动调。

（传播语：耳机一连，音质满血。）

## 为什么会有这个 App

厂商出于兼容与功耗考虑，蓝牙编码默认走保守参数（如 SBC / 48kHz / 自适应码率）。想跑满耳机的真实能力，只能：

- 每次连接后钻进 **开发者选项** 手动调 codec / 采样率 / 位深；
- 而且 **断连重连、关掉开发者选项都会回滚**，第二天一切重来。

StayHiFi 只做一件事：连上耳机的一瞬间，替你把参数写回去。

## 原理

通过 **Shizuku 或 Stellar**（任选其一）获得 shell 级特权，调用系统蓝牙栈的隐藏接口 `setCodecConfigPreference()`（反射调用） 写入 A2DP codec 配置。

- **不 patch 系统、不解锁隐藏 codec**，不突破硬件已协商的能力上限
- 常驻前台服务监听连接事件，近零额外耗电（无轮询循环）
- 没有现成参数模板的设备，可用 App 内的「录制向导」抓一次当前参数，存成专属模板

## 截图

<img src="docs/marketing/screenshots/01-home.png" width="280" alt="首页状态卡">
<img src="docs/marketing/screenshots/02-devices.png" width="280" alt="设备页">
<img src="docs/marketing/screenshots/03-settings.png" width="280" alt="设置页">

## 安装

官方渠道仅此两处（酷安 / GitHub），其他来源的 APK 请勿安装：

- **酷安**：应用市场内搜索「音质常驻」
- **GitHub Releases**：<https://github.com/chengzi2333/StayHiFi/releases>（页面附 APK SHA-256，安装前请核对）

前置条件：安装 **Shizuku** 或 **Stellar** 管理器，并按其指引激活（无线调试 / 有线 adb 启动，无需 root）。

## 使用

1. 首次打开跟随 **Onboarding 五步引导**：选择特权载体（Shizuku/Stellar）→ 授权 → 连接耳机 → 选择参数来源 → 保活设置
2. 保活设置「三道闸」（国产 ROM 杀后台严格，全做才稳）：
   - **允许自启动**（系统设置 → 应用管理）
   - **加入电池白名单**（关闭电池优化）
   - **后台高耗电 + 卡片锁定**：设置 → 电池 → 后台耗电管理 中允许本应用；并在最近任务里下拉卡片点「锁定」，防划卡清理
3. 之后正常用即可：耳机一连，参数自动拉满；通知栏常驻一条静默通知（可选显示实时参数）

新设备适配：不需要任何模板——拉满目标由 App 按耳机×手机×ROM 现场派生（各编码器内置最佳档位表），写入后读回验证。Onboarding 里的「录制向导」是诊断工具，抓一次当前参数存作本机记录（不参与拉满决策）。

## 隐私

- **零数据采集**：所有数据只保存在本机，无网络上传、无统计埋点、无账号体系
- 唯一的外部交互是与 Shizuku/Stellar 的本机 IPC

## 免责声明

本项目与 **Shizuku、Stellar、OPPO、vivo** 及其关联方无任何隶属、赞助或认可关系；所有商标归其各自所有者。仅在你自己的设备上调整系统隐藏接口（反射调用）的参数，请自行评估风险。

## 开源

GPL-3.0-or-later，详见 [LICENSE](LICENSE)。第三方组件声明见 [NOTICE](NOTICE)。设备档案贡献指引见 [CONTRIBUTING.md](CONTRIBUTING.md)。
