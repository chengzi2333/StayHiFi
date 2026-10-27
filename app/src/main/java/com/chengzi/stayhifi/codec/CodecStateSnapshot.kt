package com.chengzi.stayhifi.codec

import kotlinx.serialization.Serializable

/**
 * Task 1：只读编码器快照契约。特权服务把分阶段读取结果编码为增量 JSON，
 * App 侧各任务只通过 [CodecStateSnapshotJson] 消费本模型；写入协议与
 * 9 字段 [CodecConfig] 不变。
 *
 * [CodecStateSnapshot.current] 无默认值：缺 current 的 JSON（如 `{error:...}`）
 * 不能伪装成有效 snapshot；显式 `current:null` 则保留「读到了空」的事实。
 */

/** 单阶段 API 读取结果；UNAVAILABLE 表示该身份源/能力源尚未由服务提供。 */
@Serializable
enum class ApiReadState { OK, NULL, EMPTY, ERROR, UNAVAILABLE }

/** 系统支持的编码器类型目录条目（supported types）。 */
@Serializable
data class SupportedCodecType(val nativeType: Int, val codecId: Long, val codecName: String)

/** 系统 extended codec identity 的第二身份来源。 */
@Serializable
data class ExtendedCodecIdentity(val codecId: Long? = null, val codecName: String? = null)

/** codec 状态分阶段读取状态；状态字段如实保留，不得由数组长度或 current 推断改写。 */
@Serializable
data class CodecReadStatus(
    val codecStatusNull: Boolean = false,
    val currentNull: Boolean = false,
    val localCount: Int = 0,
    val selectableCount: Int = 0,
    val currentSelectable: Boolean? = null,
    val bluetoothState: String = "UNKNOWN",
    val a2dpProxyState: String = "UNKNOWN",
    val codecStatusState: ApiReadState = ApiReadState.OK,
    val currentState: ApiReadState = ApiReadState.OK,
    val localCapabilitiesState: ApiReadState = ApiReadState.UNAVAILABLE,
    val selectableState: ApiReadState = ApiReadState.OK,
    val errors: Map<String, String> = emptyMap(),
)

/** 身份源读取诊断（supportedTypes / extendedIdentity / 服务 uid）。 */
@Serializable
data class IdentityDiagnostics(
    val supportedTypesStatus: ApiReadState = ApiReadState.UNAVAILABLE,
    val extendedTypeStatus: ApiReadState = ApiReadState.UNAVAILABLE,
    val serviceUid: Int? = null,
    val errors: Map<String, String> = emptyMap(),
)

/**
 * 只读快照。属性顺序保持 `current, selectable` 在前、新字段随后，
 * 与旧 `{current, selectable}` JSON 的前缀兼容。
 */
@Serializable
data class CodecStateSnapshot(
    val current: CodecConfig?,
    val selectable: List<CodecConfig> = emptyList(),
    val localCapabilities: List<CodecConfig> = emptyList(),
    val supportedTypes: List<SupportedCodecType> = emptyList(),
    val extendedIdentity: ExtendedCodecIdentity? = null,
    val readStatus: CodecReadStatus = CodecReadStatus(),
    val identityDiagnostics: IdentityDiagnostics = IdentityDiagnostics(),
)
