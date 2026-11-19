package com.chengzi.stayhifi.util

import android.app.Activity
import android.app.ActivityManager
import com.chengzi.stayhifi.LogStore

/**
 * v1.2.5 后台隐藏（参考 GKD 的「后台隐藏」实现）。
 *
 * 只做一件事：把本 App 的任务卡片从「最近任务」里移除
 * （[ActivityManager.AppTask.setExcludeFromRecents]），
 * 不动进程、前台服务、常驻通知和桌面图标——触发链与后台保活完全不受影响。
 *
 * 为什么不是「隐藏图标」或「隐藏通知」：图标藏了用户就找不回入口；
 * 通知是 Android 13+ 前台服务的运行凭证，强关渠道在部分 ROM 上会连带杀服务。
 *
 * 已知边界（与 GKD 同款警告）：卡片被隐藏后，用户无法再在最近任务里给卡片加锁，
 * 而「锁卡片」是国内 ROM 上最常见的保活手段。所以开启前必须让用户确认
 * 「先加锁再隐藏」，这条提示写在设置页的二次确认弹窗里。
 *
 * 实现约束：excludeFromRecents 是任务级标记，只对已存在的任务生效，
 * 因此设置变更时与每次 onResume 都要重新应用（进程重建会新开任务）。
 * 无需任何权限，minSdk 29 直接可用。
 */
object RecentsHider {

    /** 对当前进程的所有任务应用「从最近任务隐藏」；返回是否全部下发成功。 */
    fun apply(activity: Activity, hide: Boolean): Boolean {
        val manager = activity.getSystemService(ActivityManager::class.java) ?: return false
        return try {
            manager.appTasks.forEach { it.setExcludeFromRecents(hide) }
            true
        } catch (t: Throwable) {
            LogStore.log("后台隐藏应用失败(hide=$hide): $t")
            false
        }
    }
}
