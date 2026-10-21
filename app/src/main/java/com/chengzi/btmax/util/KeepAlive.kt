package com.chengzi.btmax.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.chengzi.btmax.LogStore

/**
 * 保活引导（Task 6 抽出复用：Onboarding 保活页 + 设置页共用）。
 * vivo OriginOS 无公开 API 可查自启动开关，按「用户已手动开启」乐观处理。
 */
object KeepAlive {

    /** 跳 vivo 自启动管理页；打不开回退本 App 详情页。返回是否成功打开任一页面。 */
    fun openVivoAutostart(context: Context): Boolean {
        val vivo = Intent().setComponent(ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ))
        return try {
            context.startActivity(vivo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            LogStore.log("vivo 自启动页打不开（$t），回退本 App 详情页")
            try {
                context.startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (t2: Throwable) {
                LogStore.log("打开应用详情页也失败: $t2")
                false
            }
        }
    }

    /** 请求忽略电池优化（白名单）。返回是否成功发出请求。 */
    fun openBatteryWhitelist(context: Context): Boolean = try {
        context.startActivity(Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        LogStore.log("请求电池白名单失败: $t")
        false
    }

    /** 当前是否已在电池白名单（设置页展示用）。 */
    fun isBatteryWhitelisted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
