package com.chengzi.stayhifi.shell

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * Task D1：Root 通道的 libsu RootService 薄壳（设计 §11.1/§11.5）。
 *
 * 只 lazy 持有一个既有 [CodecService] 并在 [onBind] 返回其 binder——蓝牙逻辑仍只有一份，
 * Root 仅替换特权通道。此类运行在 libsu 的 ContextWrapper 体系中，**不添加**普通
 * Android `<service>` 声明，也不靠 `android:process` 获得 Root；后续只能由 App 进程以
 * 显式 `Intent(context, RootCodecService::class.java)` 经 `RootService.bind` 建立连接。
 * libsu 6.0.0 的 consumer ProGuard 规则已覆盖 RootService/Initializer 子类，无需我方 keep。
 */
class RootCodecService : RootService() {

    private val codecService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CodecService() }

    override fun onBind(intent: Intent): IBinder = codecService
}
