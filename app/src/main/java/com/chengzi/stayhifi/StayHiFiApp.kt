package com.chengzi.stayhifi

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.chengzi.stayhifi.data.ProfileRepository
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.trigger.ListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 应用入口：autoEnabled=true 时常驻拉起 [ListenerService]。
 * FGS 是 OriginOS 7 上 ACL_CONNECTED 的主触发载体（广播冷启动被系统拦截，真机实证），
 * 因此在进程创建的第一时间确保它活着。
 */
class StayHiFiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Task 30：档案 DataStore 预热——冷文件 IO + 全量 JSON 解码挪到启动期 IO 线程。
        // 不预热时首次切设备页才冷初始化，全砸在切换帧上（实测单帧 150ms）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ProfileRepository(applicationContext).all.first() }
        }
        // 对抗审查 C2：权限被撤（隐私恐慌后常见）时 startForeground 会抛 SecurityException
        // 连带进程崩溃死循环。拉起前先查 BLUETOOTH_CONNECT，runBlocking 整体兜底 try/catch。
        try {
            val auto = runBlocking { SettingsRepository(applicationContext).isAutoEnabled() }
            if (!auto) return
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                LogStore.log("应用启动：autoEnabled=true 但 BLUETOOTH_CONNECT 未授予，不拉起 ListenerService")
                return
            }
            LogStore.log("应用启动：autoEnabled=true，拉起 ListenerService")
            ListenerService.start(this)
        } catch (t: Throwable) {
            LogStore.log("应用启动拉起 ListenerService 失败: $t")
        }
    }
}
