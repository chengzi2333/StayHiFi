package com.chengzi.btmax.shell

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.chengzi.btmax.ICodecService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道的 [ShellProvider] 实现。
 *
 * 双通道探测说明（设计 §4.4）：Stellar（roro.stellar.manager）是 Shizuku 的
 * Android 17 兼容二开，但其 provider authority 与官方不同，rikka API 只连官方
 * 通道。这里先做单通道探测并把结果打进日志，供真机场景 B 裁决。
 * FIXME(stellar): 若 stellar_server 运行时 pingBinder 仍为 false，需在此补
 *  Stellar 专用通道（authority / binder 获取方式以实测为准）。
 */
class ShizukuShellProvider(private val context: Context) : ShellProvider {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, CodecService::class.java.name))
        .daemon(false)
        .processNameSuffix("codec")
        .debuggable(true)
        .version(1)

    @Volatile private var service: ICodecService? = null
    private var connection: ServiceConnection? = null

    override val isRunning: Boolean
        get() = try {
            val shizukuUp = Shizuku.pingBinder()
            Log.i(TAG, "probe: pingBinder=$shizukuUp stellarInstalled=${probeStellar()}")
            shizukuUp
        } catch (t: Throwable) {
            Log.w(TAG, "pingBinder 异常: $t")
            false
        }

    override val hasPermission: Boolean
        get() = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }

    override suspend fun requestPermission(activity: Activity): Boolean {
        if (hasPermission) return true
        if (!isRunning) return false
        val latch = CountDownLatch(1)
        val result = AtomicInteger(PackageManager.PERMISSION_DENIED)
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_PERMISSION) {
                result.set(grantResult)
                latch.countDown()
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        return try {
            Shizuku.requestPermission(REQ_PERMISSION)
            latch.await(30, TimeUnit.SECONDS)
            result.get() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission 异常: $t")
            false
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    override fun bind(): ICodecService {
        service?.let { return it }
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = ICodecService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        connection = conn
        try {
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            connection = null
            throw IllegalStateException("UserService 绑定失败（Shizuku 未运行？）: $t")
        }
        latch.await(10, TimeUnit.SECONDS)
        return service
            ?: throw IllegalStateException("UserService 绑定超时（Shizuku 未运行或未授权？）")
    }

    override fun unbind() {
        val conn = connection ?: return
        try {
            Shizuku.unbindUserService(args, conn, true)
        } catch (ignored: Throwable) {
        }
        connection = null
        service = null
    }

    /** Stellar 通道探测（仅记录安装态，连接通道待 FIXME(stellar) 裁决后实现） */
    private fun probeStellar(): Boolean = try {
        context.packageManager.getPackageInfo(STELLAR_PACKAGE, 0)
        true
    } catch (t: Throwable) {
        false
    }

    companion object {
        private const val TAG = "ShizukuShellProvider"
        private const val REQ_PERMISSION = 1001
        private const val STELLAR_PACKAGE = "roro.stellar.manager"
    }
}
