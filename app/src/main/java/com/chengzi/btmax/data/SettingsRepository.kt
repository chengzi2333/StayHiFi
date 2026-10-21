package com.chengzi.btmax.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * 全局设置（DataStore Preferences，设计 §4.6）。
 * 全部默认 false；autoEnabled 在 Onboarding 完成时置 true。
 */
class SettingsRepository(private val context: Context) {

    private object K {
        val AUTO_ENABLED = booleanPreferencesKey("autoEnabled")
        val NOTIFY_RESULT = booleanPreferencesKey("notifyResult")
        val NOTIFY_PERSISTENT = booleanPreferencesKey("notifyPersistent")
        val QS_TILE = booleanPreferencesKey("qsTile")
        val ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val autoEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.AUTO_ENABLED] ?: false }
    val notifyResult: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.NOTIFY_RESULT] ?: false }
    val notifyPersistent: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.NOTIFY_PERSISTENT] ?: false }
    val qsTile: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.QS_TILE] ?: false }
    val onboarded: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.ONBOARDED] ?: false }

    suspend fun isAutoEnabled(): Boolean = autoEnabled.first()
    suspend fun isNotifyResult(): Boolean = notifyResult.first()

    suspend fun setAutoEnabled(v: Boolean) = put(K.AUTO_ENABLED, v)
    suspend fun setNotifyResult(v: Boolean) = put(K.NOTIFY_RESULT, v)
    suspend fun setNotifyPersistent(v: Boolean) = put(K.NOTIFY_PERSISTENT, v)
    suspend fun setQsTile(v: Boolean) = put(K.QS_TILE, v)
    suspend fun setOnboarded(v: Boolean) = put(K.ONBOARDED, v)

    private suspend fun put(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, v: Boolean) {
        context.settingsDataStore.edit { it[key] = v }
    }
}
