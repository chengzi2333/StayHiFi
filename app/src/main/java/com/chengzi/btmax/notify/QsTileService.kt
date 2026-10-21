package com.chengzi.btmax.notify

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.chengzi.btmax.LogStore
import com.chengzi.btmax.MainActivity
import com.chengzi.btmax.data.SettingsRepository
import com.chengzi.btmax.shell.ShizukuShellProvider
import com.chengzi.btmax.trigger.ApplyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 快捷设置磁贴（Task 6）：点击 → 有连接设备则立即 [ApplyWorker] 拉满，无则打开 App。
 * 磁贴可用态受 qsTile 开关控制（关 = TILE_STATE_UNAVAILABLE）；
 * 副标题显示自动拉满总开关状态。
 */
class QsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() = refreshTile()

    override fun onClick() {
        scope.launch {
            val provider = ShizukuShellProvider(this@QsTileService)
            try {
                if (!provider.isRunning || !provider.hasPermission) {
                    LogStore.log("QS 磁贴：特权载体未就绪，打开 App")
                    openApp()
                    return@launch
                }
                val svc = provider.bind()
                try {
                    val arr = JSONArray(svc.dumpDevices())
                    if (arr.length() == 0) {
                        LogStore.log("QS 磁贴：无已连接设备，打开 App")
                        openApp()
                        return@launch
                    }
                    val mac = arr.getJSONObject(0).getString("mac")
                    val name = arr.getJSONObject(0).getString("name")
                    LogStore.log("QS 磁贴触发拉满: $name ($mac)")
                    ApplyWorker(this@QsTileService).run(mac, name)
                } finally {
                    provider.unbind()
                }
            } catch (t: Throwable) {
                LogStore.log("QS 磁贴触发失败: $t")
                openApp()
            }
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        scope.launch {
            val settings = SettingsRepository(this@QsTileService)
            val auto = settings.isAutoEnabled()
            val tileEnabled = settings.qsTile.first()
            tile.state = if (tileEnabled) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
            tile.subtitle = if (auto) "自动拉满：开" else "自动拉满：关"
            tile.updateTile()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivityAndCollapse(intent) }
    }
}
