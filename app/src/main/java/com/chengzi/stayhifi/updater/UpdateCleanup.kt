package com.chengzi.stayhifi.updater

import android.content.Context
import android.os.Environment
import com.chengzi.stayhifi.LogStore
import java.io.File

/**
 * Task 41：自更新 APK 残包清理（用户裁决：下载安装完的安装包要删掉）。
 *
 * 两个入口：
 * - 升级成功：MY_PACKAGE_REPLACED 广播拉起新版本的 [UpdateCleanupReceiver]；
 * - 兜底：App 每次启动清一遍（下载后取消安装/安装失败/进程被杀等残留场景）。
 *
 * 安全前提：只删 `StayHiFi-*.apk` 前缀、且只扫应用专属外部 Download 目录，
 * 绝不碰用户自己的文件。不能在下载完成当刻删——系统安装器还没读文件，
 * 删了会导致安装失败。
 */
object UpdateCleanup {

    private const val APK_PREFIX = "StayHiFi-"

    /** 清掉应用专属 Download 目录里的自更新 APK 残包。失败静默记日志。 */
    fun deleteStaleApks(context: Context) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            dir.listFiles()
                ?.filter { it.name.startsWith(APK_PREFIX) && it.name.endsWith(".apk") }
                ?.forEach { f ->
                    if (f.delete()) LogStore.log("已清理更新残包: ${f.name}")
                }
        } catch (t: Throwable) {
            LogStore.log("清理更新残包失败: $t")
        }
    }
}
