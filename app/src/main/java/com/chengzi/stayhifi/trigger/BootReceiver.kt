package com.chengzi.stayhifi.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.data.SettingsRepository
import kotlinx.coroutines.runBlocking

/**
 * 开机拉起 [ListenerService]（manifest 注册 BOOT_COMPLETED，exported=false）。
 *
 * 注意：OriginOS 等国产 ROM 需用户在自启动管理里放行本 App 才收得到
 * BOOT_COMPLETED（Onboarding 保活页已引导）；收不到时主入口仍靠
 * Application.onCreate 与首页开关兜底拉起。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = runBlocking { SettingsRepository(context).isAutoEnabled() }
        if (!enabled) return
        LogStore.log("BOOT_COMPLETED：拉起 ListenerService")
        ListenerService.start(context)
    }
}
