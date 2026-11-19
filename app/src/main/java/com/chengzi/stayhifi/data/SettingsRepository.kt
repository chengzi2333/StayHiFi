package com.chengzi.stayhifi.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chengzi.stayhifi.shell.ProviderSelectionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 对抗审查 I1：写盘断电/磁盘异常可损坏 preferences 文件，未配 corruptionHandler 时
// first() 抛 CorruptionException 在 StayHiFiApp.onCreate 的 runBlocking 里直接炸进程——
// App 再也打不开。损坏即重置为默认值（设置可重设，好过不可启动）。
private val Context.settingsDataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * 全局设置（DataStore Preferences，设计 §4.6）。
 * 全部默认 false；autoEnabled 在 Onboarding 完成时置 true。
 */
class SettingsRepository(private val context: Context) : ProviderSelectionStore {

    private object K {
        val AUTO_ENABLED = booleanPreferencesKey("autoEnabled")
        val NOTIFY_RESULT = booleanPreferencesKey("notifyResult")
        val NOTIFY_PERSISTENT = booleanPreferencesKey("notifyPersistent")
        val QS_TILE = booleanPreferencesKey("qsTile")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        // v1.2.5：后台隐藏（仿 GKD 的「后台隐藏」）——只把任务卡片移出最近任务
        val HIDE_FROM_RECENTS = booleanPreferencesKey("hideFromRecents")
        // v1.2.5：新手教程结束后待展示的「实际传输」一次性说明（教程完成时置 true）
        val REALTIME_INTRO_PENDING = booleanPreferencesKey("realtimeIntroPending")
        // Task 38：上次更新检查时间戳（24h 节流，毫秒）
        val LAST_UPDATE_CHECK = longPreferencesKey("lastUpdateCheckMillis")
        // v1.2.5：上次「启动补触发」时间戳——FGS 被 ROM 反复重建时避免每次重启都跑整轮拉满
        val LAST_STARTUP_TRIGGER = longPreferencesKey("lastStartupTriggerMillis")
        // Task D2：特权通道选择（只存字符串 shizuku/root，不存 enum ordinal，设计 §11.3）
        val PROVIDER = stringPreferencesKey("provider")
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
    /** v1.2.5：后台隐藏——最近任务里不显示本 App 卡片（默认关，需二次确认后开启）。 */
    val hideFromRecents: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.HIDE_FROM_RECENTS] ?: false }
    /**
     * v1.2.5：「实际传输」一次性说明是否还待展示。
     * 只在新手教程完成时置 true（老用户升级不会被补弹），用户确认「不再提醒」后置 false。
     */
    val realtimeIntroPending: Flow<Boolean> = context.settingsDataStore.data
        .map { it[K.REALTIME_INTRO_PENDING] ?: false }
    /** Task 38：上次更新检查时间戳（0 = 从未检查）。 */
    val lastUpdateCheckMillis: Flow<Long> = context.settingsDataStore.data
        .map { it[K.LAST_UPDATE_CHECK] ?: 0L }

    // Task D2：特权通道选择存储面（ProviderSelectionStore，设计 §11.3）
    override val rawSelection: Flow<String?> = context.settingsDataStore.data
        .map { it[K.PROVIDER] }

    override suspend fun setRawSelection(value: String) {
        context.settingsDataStore.edit { it[K.PROVIDER] = value }
    }

    suspend fun isAutoEnabled(): Boolean = autoEnabled.first()
    suspend fun isNotifyResult(): Boolean = notifyResult.first()

    suspend fun setAutoEnabled(v: Boolean) = put(K.AUTO_ENABLED, v)
    suspend fun setNotifyResult(v: Boolean) = put(K.NOTIFY_RESULT, v)
    suspend fun setNotifyPersistent(v: Boolean) = put(K.NOTIFY_PERSISTENT, v)
    suspend fun setQsTile(v: Boolean) = put(K.QS_TILE, v)
    suspend fun setOnboarded(v: Boolean) = put(K.ONBOARDED, v)
    /** v1.2.5：启动补触发节流用的持久时间戳（0=从未跑过）。 */
    val lastStartupTriggerAt: Flow<Long> = context.settingsDataStore.data
        .map { it[K.LAST_STARTUP_TRIGGER] ?: 0L }

    suspend fun setLastStartupTriggerAt(millis: Long) = put(K.LAST_STARTUP_TRIGGER, millis)
    suspend fun setHideFromRecents(v: Boolean) = put(K.HIDE_FROM_RECENTS, v)
    suspend fun setRealtimeIntroPending(v: Boolean) = put(K.REALTIME_INTRO_PENDING, v)

    /** Task 38：记录一次更新检查（成功/失败都记，保证 24h 内不重复联网）。 */
    suspend fun setLastUpdateCheckMillis(v: Long) {
        context.settingsDataStore.edit { it[K.LAST_UPDATE_CHECK] = v }
    }

    private suspend fun put(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, v: Boolean) {
        context.settingsDataStore.edit { it[key] = v }
    }

    private suspend fun put(key: androidx.datastore.preferences.core.Preferences.Key<Long>, v: Long) {
        context.settingsDataStore.edit { it[key] = v }
    }
}
