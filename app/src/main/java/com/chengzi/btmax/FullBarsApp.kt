package com.chengzi.btmax

import android.app.Application
import com.chengzi.btmax.data.SettingsRepository
import com.chengzi.btmax.trigger.ListenerService
import kotlinx.coroutines.runBlocking

/**
 * 应用入口：autoEnabled=true 时常驻拉起 [ListenerService]。
 * FGS 是 OriginOS 7 上 ACL_CONNECTED 的主触发载体（广播冷启动被系统拦截，真机实证），
 * 因此在进程创建的第一时间确保它活着。
 */
class FullBarsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val auto = runBlocking { SettingsRepository(applicationContext).isAutoEnabled() }
        if (auto) {
            LogStore.log("应用启动：autoEnabled=true，拉起 ListenerService")
            ListenerService.start(this)
        }
    }
}
