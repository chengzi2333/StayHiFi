package com.chengzi.stayhifi.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.chengzi.stayhifi.LogStore

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

    /**
     * 跳「后台高耗电」管理页（第三道闸，Task 7 真机实证：自启动+白名单全开
     * 仍会被划卡杀，活路是后台耗电放开 + 最近任务卡片锁定）。
     * 各家 ROM 路径不一，逐一枚举尽力而为；全失败回退电池设置页，再回退本 App 详情页。
     * 返回是否成功打开任一页面。
     */
    fun openBackgroundPower(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgPowerConsumeManagerActivity",
            )),
            Intent().setComponent(ComponentName(
                "com.iqoo.powersaving",
                "com.iqoo.powersaving.PowerSavingManagerActivity",
            )),
        )
        for (candidate in candidates) {
            try {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (t: Throwable) {
                LogStore.log("后台耗电管理页打不开（$t），尝试下一个")
            }
        }
        try {
            context.startActivity(Intent(
                Settings.ACTION_BATTERY_SAVER_SETTINGS,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (t: Throwable) {
            LogStore.log("电池设置页也打不开（$t），回退本 App 详情页")
        }
        return try {
            context.startActivity(Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (t: Throwable) {
            LogStore.log("打开应用详情页也失败: $t")
            false
        }
    }

    /** 请求忽略电池优化（白名单）。返回是否成功发出请求。 */
    fun openBatteryWhitelist(context: Context): Boolean = try {        context.startActivity(Intent(
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
