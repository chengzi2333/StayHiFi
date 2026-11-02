package com.chengzi.stayhifi.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chengzi.stayhifi.codec.CodecConfig
import java.io.File
import kotlinx.coroutines.flow.first

/** 设备级应用策略；AUTO_MAX 不持久化目标，自定义/自适应可保留上次目标供手动重试。 */
enum class ApplyMode { AUTO_MAX, CUSTOM, SYSTEM_ADAPTIVE }

data class ApplyPreference(
    val mode: ApplyMode = ApplyMode.AUTO_MAX,
    val lastTarget: CodecConfig? = null,
)

interface ApplyPreferences {
    suspend fun get(mac: String): ApplyPreference
    suspend fun setCustom(mac: String, target: CodecConfig)
    suspend fun setSystemAdaptive(mac: String, lastTarget: CodecConfig? = null)
    suspend fun setAutoMax(mac: String)
}

class ApplyPreferenceStore(
    private val dataStore: DataStore<Preferences>,
) : ApplyPreferences {

    override suspend fun get(mac: String): ApplyPreference {
        val prefs = dataStore.data.first()
        val mode = runCatching { ApplyMode.valueOf(prefs[modeKey(mac)] ?: ApplyMode.AUTO_MAX.name) }
            .getOrDefault(ApplyMode.AUTO_MAX)
        val target = prefs[targetKey(mac)]?.let { raw ->
            runCatching { CodecConfig.fromJson(raw) }.getOrNull()
        }
        if (mode == ApplyMode.CUSTOM && target == null) return ApplyPreference()
        return ApplyPreference(mode, target)
    }

    override suspend fun setCustom(mac: String, target: CodecConfig) {
        dataStore.edit {
            it[modeKey(mac)] = ApplyMode.CUSTOM.name
            it[targetKey(mac)] = target.toJson()
        }
    }

    override suspend fun setSystemAdaptive(mac: String, lastTarget: CodecConfig?) {
        dataStore.edit {
            it[modeKey(mac)] = ApplyMode.SYSTEM_ADAPTIVE.name
            if (lastTarget == null) it.remove(targetKey(mac))
            else it[targetKey(mac)] = lastTarget.toJson()
        }
    }

    override suspend fun setAutoMax(mac: String) {
        dataStore.edit {
            it.remove(modeKey(mac))
            it.remove(targetKey(mac))
        }
    }

    companion object {
        private fun modeKey(mac: String) = stringPreferencesKey("apply-mode-$mac")
        private fun targetKey(mac: String) = stringPreferencesKey("apply-target-$mac")

        fun create(context: Context): ApplyPreferenceStore = ApplyPreferenceStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { File(context.filesDir, "apply-preferences.preferences_pb") },
            ),
        )
    }
}
