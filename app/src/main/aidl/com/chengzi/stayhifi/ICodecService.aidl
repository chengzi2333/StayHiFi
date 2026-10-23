package com.chengzi.stayhifi;

// StayHiFi 的 IPC 接口：三个核心能力 —— 枚举设备、读、写（写后自动读回验证）
interface ICodecService {
    // 枚举当前已连接的 A2DP 设备，返回 JSON 数组 [{name, mac}]
    String dumpDevices();

    // 读取指定设备（mac 为空则取第一台已连接设备）当前的 codec 配置与可选能力，
    // 返回 JSON {current:CodecConfig, selectable:[CodecConfig]}，失败 {error:"..."}
    String readConfig(String mac);

    // 写入一份完整 codec 配置（configJson 为 CodecConfig 的 JSON），
    // 随后每 500ms 读回一次、最多 8 次做验证，
    // 返回 JSON {applied:Boolean, readbacks:[CodecConfig], final:CodecConfig}，失败 {error:"..."}
    String writeConfig(String mac, String configJson);

    // Task 43：传输层实况——shell uid 跑 dumpsys bluetooth_manager，
    // 只回传 State:/quality/bitrate 相关行（减小 binder 负载），
    // 返回 JSON {dump:"..."}，失败 {error:"..."}；解析在 app 侧 BtStateParser。
    String dumpBtState();

    void destroy();
}
