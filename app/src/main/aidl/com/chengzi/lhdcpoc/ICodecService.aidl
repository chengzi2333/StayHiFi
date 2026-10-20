package com.chengzi.lhdcpoc;

// PoC 的 IPC 接口：三个核心能力 —— 读、写、（写后）验证
interface ICodecService {
    // 枚举当前已连接的 A2DP 设备（名称/MAC）
    String dumpDevices();

    // 读取指定设备（mac 为空则取第一台已连接设备）当前的 codec 配置与可选能力
    String readConfig(String mac);

    // 写入一份完整 codec 配置，随后读回验证并返回对比报告
    String writeConfig(String mac, int codecType, int codecPriority, int sampleRate,
                       int bitsPerSample, int channelMode,
                       long cs1, long cs2, long cs3, long cs4);

    void destroy();
}
