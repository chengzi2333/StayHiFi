package com.chengzi.stayhifi.shell

import android.app.Activity
import com.chengzi.stayhifi.ICodecService

/**
 * Task D5：Root 通道的 [ShellProvider] 门面（设计 §11.3/§11.4.5）。
 *
 * 只反映**当前进程**已验证的 READY anchor：
 *  - isRunning/hasPermission 仅在 transport READY 时为 true（持久选择不是授权证明）；
 *  - 未 READY 时 bind 抛 NEEDS_FOREGROUND_REVALIDATION，绝不为探测而启动可能弹窗的 shell；
 *  - requestPermission 永不代表 Root 授权交互（Root 授权只能由教程/设置经
 *    RootTransport.beginForegroundAuthorization 显式发起）；
 *  - unbind 是 no-op：Root anchor 属进程级，不属于任何 Activity/owner（§11.3），
 *    只有 RootTransport.disconnectAnchor / 手动切换才解绑。
 */
class RootShellProvider(private val transport: RootTransport) : ShellProvider {

    override val isRunning: Boolean
        get() = transport.state.value.status == RootStatus.READY

    override val hasPermission: Boolean
        get() = transport.state.value.status == RootStatus.READY

    override suspend fun requestPermission(activity: Activity): Boolean = false

    override fun bind(): ICodecService =
        transport.readyService() ?: throw RootAccessException(
            RootStatus.NEEDS_FOREGROUND_REVALIDATION,
            "Root 未在当前进程就绪，需前台重验（§11.4.5）")

    override fun unbind() = Unit
}
