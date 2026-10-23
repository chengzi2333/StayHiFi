package com.chengzi.stayhifi.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Task 41：升级完成信号——本包被替换（自更新覆盖装成功）时，新版本进程收到
 * ACTION_MY_PACKAGE_REPLACED，此刻系统安装器已读完 APK，清理残包安全。
 */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateCleanup.deleteStaleApks(context)
        }
    }
}
