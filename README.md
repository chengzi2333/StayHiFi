# LHDC PoC —— 蓝牙编码参数读取/写入验证（Spike，一次性验证用）

验证目标（在 iQOO 15 + Enco X3 上确认三件事）：

1. **读**：能读到当前 A2DP codec 配置（codecType / 采样率 / 位深 / cs1 质量档）
2. **写**：能通过 `BluetoothA2dp.setCodecConfigPreference` 写入目标参数
3. **验证**：写入后读回，确认参数生效且不被 OriginOS 拦截

三个都通过 → 再做完整 App（自动监听连接、自动回放）。

## 构建

```bash
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 安装到手机

手机开 USB 调试，数据线连 Mac：

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试步骤

前置：Shizuku 已启动（无线调试或有线 adb 激活），Enco X3 已连接手机。

1. 打开 App → 按 **① 检查 Shizuku 并连接服务** → 弹窗里允许权限 → 看到「特权服务已连接 ✓」
2. 按 **② 枚举已连接设备** → 确认能看到 Enco X3 和它的 MAC（可把 MAC 填进输入框，不填默认操作第一台设备）
3. **手动**去开发者选项把参数调到目标状态：LHDC V5 / 192kHz / 24bit / 音质优先(1000kbps)
4. 回到 App 按 **③ 读取当前参数** → 日志里应看到 `codec=vendor(N) rate=192k bits=24bit cs1=...`，以及一行 `CONFIG|...`
   - 同时注意「可选择的编码能力」列表 —— 这里能直接看到 LHDC V5 的厂商 codecType 编号
5. 按 **④ 保存为模板**
6. 断开耳机重连（或把开发者选项拨回 48k/自适应，模拟回滚）
7. 按 **⑤ 写入模板参数** → 观察日志里「读回 #N」是否出现 `✓ 一致`

## 结果判读

| 现象 | 结论 |
|---|---|
| ③ 读出完整参数 | 读链路通（getCodecStatus 可用） |
| ⑤ 读回 ✓ 一致 | **全链路通，完整 App 可行** |
| ⑤ 一直 ✗ 未生效 | OriginOS 蓝牙栈拦截或参数组合被拒 → 看错误日志再定策略 |
| ① 报 SecurityException | shell 身份在 OriginOS 上没拿到 BLUETOOTH_PRIVILEGED → 需要临时 root 方案 |

## 关键参考实现

- [bschmidt5493/bluetooth-codec-switcher-android](https://github.com/bschmidt5493/bluetooth-codec-switcher-android) —— 本 PoC 的 Shizuku UserService 模式来源
- [CodecTweaker](https://github.com/timschneeb/awesome-shizuku)（awesome-shizuku 列表内）—— HyperOS 上同思路的重连恢复工具
