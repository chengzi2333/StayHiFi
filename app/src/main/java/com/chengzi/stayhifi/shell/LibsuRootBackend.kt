package com.chengzi.stayhifi.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.atomic.AtomicLong

/**
 * Task D5：Root 通道的 libsu 生产实现（设计 §11.4/§11.5）。
 *
 * 线程纪律由调用方（RootTransport）保证：
 *  - [openShell]（Shell.getShell）可能长时间阻塞，只在专用 auth dispatcher 调用；
 *  - [bind]/[unbind] 经 RootTransport 注入的 mainDispatcher 在主线程调用（§11.4.7）；
 *  本类内部不再自行切线程，也不等待任何 latch。
 *
 * 边界：显式 Intent 指向 [RootCodecService]；不使用 daemon mode，不调用 RootService.stop
 * （§11.1/§11.4.7）；不设置 FLAG_MOUNT_MASTER；不存在任意命令执行面。
 */
class LibsuRootBackend(private val context: Context) : RootBackend {

    private val bindingIds = AtomicLong(0)

    override fun grantedState(): Boolean? = try {
        Shell.isAppGrantedRoot()
    } catch (t: Throwable) {
        null   // 观察失败按"未知"处理，不得据此定因（§11.4.2）
    }

    override fun openShell(): RootShellObservation {
        val shell = Shell.getShell()
        // libsu 默认 shell 可能回落普通 sh（§11.4.1）：返回不等于 Root 成功
        return RootShellObservation(isRoot = shell.isRoot)
    }

    override fun bind(callbacks: RootBindCallbacks): RootBinding {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                callbacks.onConnected(binder?.let { ICodecService.Stub.asInterface(it) })
            }

            override fun onServiceDisconnected(name: ComponentName) {
                callbacks.onDisconnected()
            }
        }
        RootService.bind(Intent(context, RootCodecService::class.java), connection)
        return LibsuBinding(bindingIds.incrementAndGet(), connection)
    }

    override fun unbind(binding: RootBinding) {
        try {
            RootService.unbind((binding as LibsuBinding).connection)
        } catch (ignored: Throwable) {
            // 幂等解绑：已断开/重复 unbind 不抛出（§11.4.8）
        }
    }

    private class LibsuBinding(override val id: Long, val connection: ServiceConnection) : RootBinding
}
