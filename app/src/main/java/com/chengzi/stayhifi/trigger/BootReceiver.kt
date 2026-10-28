package com.chengzi.stayhifi.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chengzi.stayhifi.LogStore
import com.chengzi.stayhifi.StayHiFiApp
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.shell.ProviderSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 开机拉起 [ListenerService]（manifest 注册 BOOT_COMPLETED，exported=false）。
 *
 * 注意：OriginOS 等国产 ROM 需用户在自启动管理里放行本 App 才收得到
 * BOOT_COMPLETED（Onboarding 保活页已引导）；收不到时主入口仍靠
 * Application.onCreate 与首页开关兜底拉起。
 *
 * Task D8：唯一的特权通道动作是 goAsync 内等异步首读后 noteBackground（冷 Root
 * 去重登记 NEEDS_FOREGROUND_REVALIDATION）——非交互，绝不创建 shell/弹授权/回退
 * Shizuku（§11.4.5）；不新增主线程 runBlocking（既有 autoEnabled 读取原样保留）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = runBlocking { SettingsRepository(context).isAutoEnabled() }
        if (!enabled) return
        LogStore.log("BOOT_COMPLETED：拉起 ListenerService")
        ListenerService.start(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as? StayHiFiApp)?.providerCoordinator?.let { coordinator ->
                    // 等异步首读完成，避免 selected=null 时登记空转；不阻塞主线程
                    coordinator.awaitSelection()
                    coordinator.noteBackground(ProviderSource.BOOT)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
