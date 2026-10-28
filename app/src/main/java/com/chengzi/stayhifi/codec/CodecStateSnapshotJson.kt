package com.chengzi.stayhifi.codec

import kotlinx.serialization.json.Json

/**
 * Task 1：快照 JSON 编解码。兼容旧 `{current, selectable}` 两字段 JSON
 * （新字段走默认值），增量字段（localCapabilities/supportedTypes/…）随
 * 服务版本追加；未知附加字段可忽略。
 */
object CodecStateSnapshotJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * 解析快照。`current` 无默认值：缺 current 或损坏 JSON 抛 [kotlinx.serialization.SerializationException]；
     * 显式 `current:null` 合法。parse 后仅用实际数组长度归一化 localCount/selectableCount，
     * 不把 EMPTY 改成 OK，也不从 current 推断 extended/supported 状态。
     */
    fun parse(raw: String): CodecStateSnapshot {
        val snapshot = json.decodeFromString(CodecStateSnapshot.serializer(), raw)
        return snapshot.copy(
            readStatus = snapshot.readStatus.copy(
                localCount = snapshot.localCapabilities.size,
                selectableCount = snapshot.selectable.size,
            ),
        )
    }

    fun encode(snapshot: CodecStateSnapshot): String =
        json.encodeToString(CodecStateSnapshot.serializer(), snapshot)
}
