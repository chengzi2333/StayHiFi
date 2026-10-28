<div align="center">
  <img src="docs/assets/logo.png" width="128" alt="StayHiFi logo">
  <h1>StayHiFi 音质常驻</h1>
  <p><strong>蓝牙耳机一连，自动拉满音质——免 root，装完就不用管</strong></p>
  <p>[简体中文] | <a href="README.zh-TW.md">繁體中文</a> | <a href="README.en.md">English</a> | <a href="README.ko.md">한국어</a></p>
  <p>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/v/release/chengzi2333/StayHiFi" alt="Release"></a>
    <a href="https://github.com/chengzi2333/StayHiFi/releases"><img src="https://img.shields.io/github/downloads/chengzi2333/StayHiFi/total" alt="Downloads"></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/chengzi2333/StayHiFi" alt="License"></a>
    <img src="https://img.shields.io/badge/Android-10%2B-green" alt="Android 10+">
  </p>
</div>

## 为什么需要 StayHiFi

厂商出于兼容与功耗考虑，蓝牙编码默认走保守参数（如 SBC / 48kHz / 自适应码率）——花 HiFi 的钱，听 MP3 的声。想跑满耳机的真实能力，只能每次连接后钻进**开发者选项**手动调 codec / 采样率 / 位深，而且**断连重连、关掉开发者选项都会回滚**，第二天一切重来。

StayHiFi 替你盯死这件事：连上耳机的一瞬间，把参数写回这台设备协商得出的上限，全程无感。拉满后遇到无声、杂音，或你就是想换个口味，也可以在自定义音质里手动微调，随时一键恢复自适应。

## 截图

<p>
  <img src="docs/assets/screenshots/01-home.png" width="260" alt="首页状态卡">
  <img src="docs/assets/screenshots/02-devices.png" width="260" alt="设备页">
  <img src="docs/assets/screenshots/03-settings.png" width="260" alt="设置页">
</p>

## 功能特性

- **连上即拉满**：任何蓝牙耳机零适配——目标参数按「耳机×手机×ROM」现场派生，写入后读回验证；触发合并、断连保护，重连与开始播放时更稳
- **状态卡一眼看懂**：「已达本机最高音质输出」/「未拉满 · 点按钮重试」/「自定义参数生效中」/「已连接，授权后可用」如实陈述，不冒认
- **断连重连自动再拉**：前台服务监听连接事件，近零耗电，无轮询空转
- **自定义音质**：编码器 / 采样率 / 位深 / 码率可按当前连接能力手动调整；拉满后无声、杂音时可降档，一键恢复自适应；置灰档位 = 当前手机或耳机暂不支持。自定义状态只如实显示「自定义」，绝不冒充满血——自动拉满的默认规则不变
- **身份与实况诊断**：核对本机实际生效的编码器身份，实时显示实际传输码率；部分机型固定档仍会自适应，以实际传输为准
- **常驻静默通知 + QS 磁贴**：可选实时显示当前参数；QS 磁贴一键触发重拉，无已连接设备时打开 App
- **iOS 风浮动 Dock 导航**：弹簧透镜 + 果冻变形物理动效，拖拽跟手
- **记录当前参数**：入口在设备页，抓一次当前协商参数存档，附机型 / Android 版本出处；机型名离线可查（v1.2.2）
- **随时一键还原**：设备页可把参数写回首次拉满前的原始协商值
- **Material You**：动态取色，深浅色主题随系统

## 工作原理

通过 **Shizuku 或 Stellar**（任选其一，免 root）获得 shell 级特权；已 root 的设备也可改用 **Root 通道（实验）**，随时可切回。反射调用系统蓝牙栈的隐藏接口 `setCodecConfigPreference()` 写入 A2DP codec 配置。

- **不 patch 系统、不解锁隐藏 codec**，不突破硬件已协商的能力上限——Root 只替换特权通道，不提升音质上限
- Root 通道的机型覆盖仍在收集外部样本，尚未宣称稳定支持，遇到问题欢迎反馈
- 系统蓝牙连接与特权读取解耦：未授权也能读本机 A2DP 连接与耳机名；详细参数查看与写入才需要授权
- 功能层面的外部交互仅是与 Shizuku / Stellar / Root 的本机 IPC；唯一网络请求是 GitHub Releases 版本检查

## 安装

> ⚠️ 官方渠道目前**仅有 GitHub Releases**（酷安上架中），其他来源的 APK 请勿安装。

1. 准备特权（二选一）：安装 **Shizuku** 或 **Stellar** 管理器，按其指引激活（无线调试 / 有线 adb，无需 root）；已 root 的设备也可在 App 内改用实验 Root 通道
2. 从 [GitHub Releases](https://github.com/chengzi2333/StayHiFi/releases) 下载 APK——**单包通用所有机型**，无需挑选 ABI 版本；页面附 SHA-256 与签名证书指纹，安装前可核对

要求：Android 10+。

## 使用

1. 首次打开跟随**四步引导**：选择特权方式 → 授权 → 连接耳机 → 保活设置。连接异常时，连接页有「排障日志」入口，可就地查看并复制日志反馈
2. 保活「三道闸」（国产 ROM 杀后台严格，全做才稳）：
   - **允许自启动**（系统设置 → 应用管理）
   - **加入电池白名单**（关闭电池优化）
   - **后台高耗电 + 卡片锁定**：设置 → 电池 → 后台耗电管理 中允许本应用；并在最近任务里下拉卡片点「锁定」
3. 之后正常用即可：耳机一连，参数自动拉满；首页状态卡实时告诉你有没有到顶。想微调就用首页的「自定义音质」按当前连接能力手动调整，异常时降档或一键恢复自适应；未授权时也能看到已连接的耳机名，授权后参数才会展示与写入

## 卸载

写入的参数偏好持久保存在系统蓝牙栈中，**卸载 App 不会自动还原**。想回到默认：卸载前在设备页点「恢复默认参数」，把参数写回首次拉满前的原始协商值。除此之外无任何残留——不碰系统文件，卸载即净。

## 反馈与交流

设备型号众多、各家 ROM 差异大，难免有覆盖不到的机器。遇到问题欢迎通过任一渠道找我：

- **GitHub Issues**：报 bug、提需求的首选，附 App 内「日志」页「复制日志」的输出最佳——日志已自动脱敏（MAC / 设备名映射为 deviceN），直接粘贴即可，请勿改写或补充未脱敏原文
- **酷安**：@一般社畜橙子
- **小红书**：@橙橙橙子Orenji

## 隐私

- **零数据采集**：所有数据只保存在本机，无网络上传、无统计埋点、无账号体系
- **唯一网络请求** = GitHub Releases 版本检查（仅版本号比对，不携带任何设备信息；每天至多一次静默检查，也可在设置页手动触发）
- 其余唯一的外部交互是与 Shizuku / Stellar / Root 的本机 IPC——无论哪种特权通道，都只在本机通信

## 免责声明

本项目与 **Shizuku、Stellar、OPPO、vivo** 及其关联方无任何隶属、赞助或认可关系；所有商标归其各自所有者。仅在你自己的设备上调整系统隐藏接口（反射调用）的参数，请自行评估风险。

## 开源许可

GPL-3.0-or-later，详见 [LICENSE](LICENSE)。第三方组件声明见 [NOTICE](NOTICE)，设备档案贡献指引见 [CONTRIBUTING.md](CONTRIBUTING.md)。

源码可自由使用与修改，但 **「StayHiFi / 音质常驻」名称与图标不属于授权范围**——fork 或二次发布请更换名称与图标（参照 Shizuku / Seal 的通行做法）。
