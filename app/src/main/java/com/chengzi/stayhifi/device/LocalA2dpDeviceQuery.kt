package com.chengzi.stayhifi.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 本机 A2DP 已连接设备查询；null 表示读取不可得，不等于确认断开。 */
internal fun interface LocalA2dpDeviceQuery {
    fun connectedDevices(): List<RawBtDevice>?
}

/** proxy 平台回调与最小资源面；测试用 fake 驱动真实查询协调器。 */
internal interface A2dpProxyCallback {
    fun onConnected(handle: A2dpProxyHandle)
    fun onDisconnected()
}

internal data class A2dpProxyHandle(
    val readDevices: () -> List<RawBtDevice>,
    val close: () -> Unit,
)

/**
 * 单次、有界的 A2DP proxy 查询。
 *
 * v1.2.2 错用 BluetoothManager.getConnectedDevices(A2DP)：该入口只支持 GATT/
 * GATT_SERVER，异常被吞成 Unknown 后本机解耦失效。A2DP 必须经
 * BluetoothAdapter.getProfileProxy() 获取 proxy，再读 proxy.connectedDevices。
 * 每次查询至多等待 2 秒；超时只返回 null（UNKNOWN），不伪造断开。迟到 proxy
 * 仍会执行 close，避免轮询路径累积系统 binder 引用。
 */
internal class A2dpProxyDeviceQuery(
    private val request: (A2dpProxyCallback) -> Boolean,
    private val await: (CountDownLatch) -> Boolean = { latch ->
        latch.await(PROXY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    },
) : LocalA2dpDeviceQuery {

    override fun connectedDevices(): List<RawBtDevice>? {
        val latch = CountDownLatch(1)
        val devices = AtomicReference<List<RawBtDevice>?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val callback = object : A2dpProxyCallback {
            override fun onConnected(handle: A2dpProxyHandle) {
                try {
                    devices.set(handle.readDevices())
                } catch (t: Throwable) {
                    // 平台回调与调用方不一定会共享线程：把权限/读取异常带回
                    // LocalHeadsetSource.classify，不能跨线程吞成空列表。
                    failure.set(t)
                } finally {
                    runCatching { handle.close() }
                    latch.countDown()
                }
            }

            override fun onDisconnected() {
                latch.countDown()
            }
        }

        if (!request(callback)) return null
        val completed = try {
            await(latch)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) return null
        failure.get()?.let { throw it }
        return devices.get()
    }

    private companion object {
        const val PROXY_TIMEOUT_MS = 2_000L
    }
}

/** Android 平台接线：只替换系统 API，不与 Root/Shizuku 特权通道共享状态。 */
@SuppressLint("MissingPermission")
internal fun androidA2dpDeviceQuery(context: Context): LocalA2dpDeviceQuery {
    val appContext = context.applicationContext
    val adapter: BluetoothAdapter? = try {
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (t: Throwable) {
        null
    }
    return A2dpProxyDeviceQuery(
        request = { callback ->
            if (adapter == null) {
                false
            } else {
                adapter.getProfileProxy(
                    appContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            if (proxy !is BluetoothA2dp) {
                                runCatching {
                                    adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                                }
                                callback.onDisconnected()
                                return
                            }
                            callback.onConnected(
                                A2dpProxyHandle(
                                    readDevices = {
                                        proxy.connectedDevices.map {
                                            RawBtDevice(name = it.name, address = it.address)
                                        }
                                    },
                                    close = {
                                        runCatching {
                                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                                        }
                                    },
                                ),
                            )
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            callback.onDisconnected()
                        }
                    },
                    BluetoothProfile.A2DP,
                )
            }
        },
    )
}
