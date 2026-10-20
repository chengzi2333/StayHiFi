package com.chengzi.btmax.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chengzi.btmax.LogStore
import com.chengzi.btmax.MainActivity

/**
 * 结果通知（设计 §4.3 / §5）：成功、失败、以及 Shizuku 未运行时的"一键修复"引导。
 * Android 13+ 的通知运行时权限（POST_NOTIFICATIONS）未授予时静默跳过并记日志，
 * 正式入口在 Onboarding（Task 5）里申请。
 */
class Notifier(private val context: Context) {

    init { createChannel() }

    /** 拉满结果通知（受 notifyResult 开关控制，由调用方决定）。 */
    fun notifyResult(title: String, content: String) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .build()
        notify(ID_RESULT, n)
    }

    /** Shizuku 未运行 → "点我打开 App 一键修复"。 */
    fun notifyFixRequired(reason: String) {
        if (!canNotify()) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("FullBars 需要修复特权环境")
            .setContentText("$reason，点我打开 App 一键修复")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notify(ID_FIX, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_AUTO, "自动拉满", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }
    }

    private fun canNotify(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            LogStore.log("通知被禁用，跳过通知")
            return false
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            LogStore.log("缺少 POST_NOTIFICATIONS 权限，跳过通知")
            return false
        }
        return true
    }

    private fun notify(id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (se: SecurityException) {
            LogStore.log("notify 抛 SecurityException: $se")
        }
    }

    companion object {
        private const val CHANNEL_AUTO = "auto_apply"
        private const val ID_RESULT = 1
        private const val ID_FIX = 2
    }
}
