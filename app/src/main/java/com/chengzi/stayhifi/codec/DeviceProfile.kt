package com.chengzi.stayhifi.codec

import kotlinx.serialization.Serializable

/** 已知设备的持久化档案：MAC + 名称 + 抓取的 codec 配置（Task 27 起仅设备页"本机记录"展示，不参与拉满决策）。 */
@Serializable
data class DeviceProfile(
    val mac: String,
    val name: String,
    val config: CodecConfig,
    val source: Source,
    val autoApply: Boolean,
    /** 录制出处四字段（Task 27 保留项）：纯本地给未来留数据形状。均有默认值——旧档案文件
     * 缺字段时 kotlinx.serialization 按默认值兜底，可正常读出。 */
    val recordedOnModel: String = "",
    val recordedAndroidVersion: String = "",
    val recordedBuildDisplay: String = "",
    val recordedAtMillis: Long = 0L,
    /** Task 42：首次拉满写入前的原始协商配置（后悔药），设备页「恢复默认参数」的数据来源。
     * null = 尚未记录（老档案 / 从未被本 App 拉满过），还原入口置灰。
     * 有默认值——旧档案 JSON 无此字段时按 null 兜底，可正常解码。 */
    val originConfig: CodecConfig? = null,
) {
    /** 配置来源：AUTO=自动拉满成功后自动建档（Task 35），CAPTURED=抓包捕获，PRESET=内置预设（历史档案，现已不再新增）。 */
    enum class Source { AUTO, CAPTURED, PRESET }
}
