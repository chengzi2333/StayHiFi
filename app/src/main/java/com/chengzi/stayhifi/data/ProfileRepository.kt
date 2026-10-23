package com.chengzi.stayhifi.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.codec.DeviceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

// 对抗审查 I1：同 SettingsRepository——preferences 文件损坏时重置，避免启动崩溃死循环。
// Task 33：文件级损坏重置为空表时留警告日志（JSON 级损坏在 save 里有，文件级原来没有）。
private val Context.profileDataStore by preferencesDataStore(
    name = "profiles",
    corruptionHandler = ReplaceFileCorruptionHandler {
        LogStore.log("警告：profiles 存储文件损坏，已重置为空档案表")
        emptyPreferences()
    },
)

/**
 * 已知设备档案存储（DataStore Preferences + JSON，设计 §4.6）。
 * 设备数量小，不需要 Room。同步签名（runBlocking）以匹配 Worker 的调用点。
 */
class ProfileRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profiles: Flow<List<DeviceProfile>> = context.profileDataStore.data
        .map { prefs -> prefs[KEY_PROFILES]?.let { decode(it) } ?: emptyList() }

    /** 全部设备档案（设备页列表数据源，Task 6 新增；既有签名不变）。 */
    val all: Flow<List<DeviceProfile>> = profiles

    /** MAC 大小写不敏感匹配。 */
    fun profileFor(mac: String): DeviceProfile? = runBlocking {
        profiles.first().firstOrNull { it.mac.equals(mac, ignoreCase = true) }
    }

    /** 按 MAC 覆盖保存。 */
    fun save(profile: DeviceProfile) {
        runBlocking {
            context.profileDataStore.edit { prefs ->
                // 对抗审查 I1：JSON 级损坏被 decode 吞成空表后 save 会先读后覆盖，
                // 用户一次重录就静默清空全部档案——加警告日志让数据损失可见。
                val raw = prefs[KEY_PROFILES]
                val existing = raw?.let { decode(it) }
                if (raw != null && existing == null) {
                    LogStore.log("警告：profiles 数据损坏（JSON 解析失败），本次保存将覆盖清空既有档案")
                }
                val rest = (existing ?: emptyList())
                    .filterNot { it.mac.equals(profile.mac, ignoreCase = true) }
                prefs[KEY_PROFILES] = json.encodeToString(rest + profile)
            }
        }
    }

    private fun decode(s: String): List<DeviceProfile>? = try {
        json.decodeFromString<List<DeviceProfile>>(s)
    } catch (t: Throwable) {
        null
    }

    companion object {
        private val KEY_PROFILES = stringPreferencesKey("profiles")
    }
}
