package com.chengzi.btmax.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chengzi.btmax.codec.DeviceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

private val Context.profileDataStore by preferencesDataStore(name = "profiles")

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
                val rest = (prefs[KEY_PROFILES]?.let { decode(it) } ?: emptyList())
                    .filterNot { it.mac.equals(profile.mac, ignoreCase = true) }
                prefs[KEY_PROFILES] = json.encodeToString(rest + profile)
            }
        }
    }

    private fun decode(s: String): List<DeviceProfile> = try {
        json.decodeFromString<List<DeviceProfile>>(s)
    } catch (t: Throwable) {
        emptyList()
    }

    companion object {
        private val KEY_PROFILES = stringPreferencesKey("profiles")
    }
}
