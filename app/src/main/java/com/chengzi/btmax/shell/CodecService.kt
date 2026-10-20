package com.chengzi.btmax.shell

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.chengzi.btmax.ICodecService
import com.chengzi.btmax.codec.CodecConfig
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 本类运行在 Shizuku 拉起的独立进程（:codec）中，身份是 adb shell (uid 2000)，
 * 天然持有 BLUETOOTH_PRIVILEGED，因此可以调用隐藏 API：
 *   BluetoothA2dp.getCodecStatus() / setCodecConfigPreference()
 *
 * PoC 原则：全部走反射，不依赖任何隐藏类的编译期符号。
 * 逻辑与 PoC CodecService.java 一一对应（git e5973cb）。
 */
class CodecService : ICodecService.Stub() {

    private var mContext: Context? = null              // shell 进程的 system context（拿不到就为 null）
    private var mThread: HandlerThread                 // A2DP 代理回调需要 Looper
    @Volatile private var mA2dp: BluetoothA2dp? = null
    @Volatile private var mAdapter: BluetoothAdapter? = null

    init {
        disableHiddenApiChecks()
        // app_process 裸进程没有主 Looper，而 ActivityThread.systemMain() 需要它；
        // Shizuku 场景下主 Looper 已存在，prepareMainLooper 只能调一次，故先判空
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }
        mThread = HandlerThread("bt-worker").apply { start() }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getDeclaredMethod("systemMain").invoke(null)
            mContext = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        } catch (t: Throwable) {
            Log.w(TAG, "no system context, fallback to null", t)
            t.printStackTrace()   // app_process 场景下打到 stdout，方便 adb 直接看
        }
    }

    // ─────────────────────────── IPC 接口 ───────────────────────────

    override fun dumpDevices(): String = try {
        val a2dp = getA2dp()
        buildJsonArray {
            for (d in a2dp.connectedDevices) {
                addJsonObject {
                    put("name", safeName(d))
                    put("mac", d.address)
                }
            }
        }.toString()
    } catch (t: Throwable) {
        errorJson("dumpDevices", t)
    }

    override fun readConfig(mac: String?): String = try {
        val a2dp = getA2dp()
        val dev = findDevice(a2dp, mac)
        if (dev == null) {
            error("找不到目标设备 $mac")
        } else {
            val status = a2dp.javaClass
                .getMethod("getCodecStatus", BluetoothDevice::class.java)
                .invoke(a2dp, dev)
            val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status)
            val selectable = status.javaClass
                .getMethod("getCodecsSelectableCapabilities")
                .invoke(status) as List<*>
            Log.i(TAG, "readConfig ${safeName(dev)}: ${configFromReflected(cfg!!).summary()}")
            buildJsonObject {
                put("current", configFromReflected(cfg).toJsonElement())
                putJsonArray("selectable") {
                    for (cap in selectable) add(configFromReflected(cap!!).toJsonElement())
                }
            }.toString()
        }
    } catch (t: Throwable) {
        errorJson("readConfig", t)
    }

    override fun writeConfig(mac: String?, configJson: String?): String = try {
        val target = CodecConfig.fromJson(configJson ?: "")
        val a2dp = getA2dp()
        val dev = findDevice(a2dp, mac)
        if (dev == null) {
            error("找不到目标设备 $mac")
        } else {
            val cfg = buildConfig(
                target.codecType, target.priority, target.sampleRate,
                target.bitsPerSample, target.channelMode,
                target.cs1, target.cs2, target.cs3, target.cs4,
            )

            val bccClass = Class.forName("android.bluetooth.BluetoothCodecConfig")
            val m = BluetoothA2dp::class.java.getMethod(
                "setCodecConfigPreference", BluetoothDevice::class.java, bccClass)
            m.invoke(a2dp, dev, cfg)
            Log.i(TAG, "setCodecConfigPreference: ${target.summary()} cs1=${target.cs1}")

            // 写入后验证：协议栈重新协商需要时间，最多轮询 ~4 秒
            val readbacks = mutableListOf<CodecConfig>()
            var applied = false
            var finalCfg: CodecConfig? = null
            for (i in 0 until 8) {
                Thread.sleep(500)
                val cur = currentConfig(a2dp, dev)
                readbacks.add(cur)
                // 匹配判定：codecType + rate（0 跳过）+ bits（0 跳过）一致
                val match = cur.codecType == target.codecType &&
                    (target.sampleRate == 0 || cur.sampleRate == target.sampleRate) &&
                    (target.bitsPerSample == 0 || cur.bitsPerSample == target.bitsPerSample)
                Log.i(TAG, "readback #${i + 1}: ${cur.summary()} cs1=${cur.cs1} match=$match")
                if (match) {
                    applied = true
                    finalCfg = cur
                    break
                }
            }
            finalCfg = finalCfg ?: readbacks.lastOrNull()
            buildJsonObject {
                put("applied", applied)
                putJsonArray("readbacks") {
                    for (r in readbacks) add(r.toJsonElement())
                }
                if (finalCfg != null) put("final", finalCfg.toJsonElement())
            }.toString()
        }
    } catch (t: Throwable) {
        errorJson("writeConfig", t)
    }

    override fun destroy() {
        Log.i(TAG, "destroy")
    }

    // ─────────────────────────── 内部实现（PoC 一一对应） ───────────────────────────

    private fun getA2dp(): BluetoothA2dp {
        mA2dp?.let { return it }
        val latch = CountDownLatch(1)
        val diag = StringBuilder()
        Handler(mThread.looper).post {
            try {
                // 0) 裸进程没有初始化蓝牙框架静态注册表 → 直接反射注入 BluetoothServiceManager 实例
                frameworkInit(diag)
                var adapter: BluetoothAdapter? = null
                // 1) Android 14+ 的工厂方法：BluetoothAdapter.createAdapter(context)
                if (mContext != null) {
                    try {
                        val create = BluetoothAdapter::class.java
                            .getDeclaredMethod("createAdapter", Context::class.java)
                        create.isAccessible = true
                        adapter = create.invoke(null, mContext) as BluetoothAdapter?
                        diag.append("createAdapter=").append(adapter != null).append(' ')
                    } catch (t: Throwable) {
                        diag.append("createEx=").append(rootCause(t)).append(' ')
                    }
                }
                // 2) 系统服务包装
                if (adapter == null && mContext != null) {
                    val bm = mContext!!.getSystemService(
                        android.bluetooth.BluetoothManager::class.java)
                    adapter = bm?.adapter
                    diag.append("bm.getAdapter=").append(adapter != null).append(' ')
                }
                // 3) 传统静态入口
                if (adapter == null) {
                    adapter = BluetoothAdapter.getDefaultAdapter()
                    diag.append("getDefaultAdapter=").append(adapter != null).append(' ')
                }
                // 4) 手工复刻 createAdapter：bluetooth_manager binder + 探测到的构造器签名
                if (adapter == null) {
                    adapter = adapterFromBinder(diag)
                }
                mAdapter = adapter
                if (adapter == null) {
                    latch.countDown()
                    return@post
                }
                try {
                    diag.append("enabled=").append(adapter.isEnabled).append(' ')
                } catch (t: Throwable) {
                    diag.append("enabledEx=").append(rootCause(t)).append(' ')
                }
                val ok = adapter.getProfileProxy(
                    mContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            mA2dp = proxy as BluetoothA2dp
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            mA2dp = null
                        }
                    },
                    BluetoothProfile.A2DP)
                diag.append("proxyReq=").append(ok).append(' ')
                if (!ok) latch.countDown()
            } catch (t: Throwable) {
                diag.append("EX:").append(t)
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        // 5) 代理回调没回来 → 手工构造 proxy 并用 onServiceConnected 接线
        if (mA2dp == null && mAdapter != null) {
            try {
                val ctor = BluetoothA2dp::class.java
                    .getDeclaredConstructor(Context::class.java, BluetoothAdapter::class.java)
                ctor.isAccessible = true
                val proxy = ctor.newInstance(mContext, mAdapter)
                val getProfile = BluetoothAdapter::class.java
                    .getDeclaredMethod("getProfile", Int::class.java)
                getProfile.isAccessible = true
                val svcBinder = getProfile.invoke(mAdapter, BluetoothProfile.A2DP) as IBinder?
                diag.append("profileBinder=").append(svcBinder != null).append(' ')
                if (svcBinder != null) {
                    BluetoothA2dp::class.java.getMethod("onServiceConnected", IBinder::class.java)
                        .invoke(proxy, svcBinder)
                    mA2dp = proxy
                    diag.append("directWire=ok ")
                }
            } catch (t: Throwable) {
                diag.append("directEx=").append(rootCause(t)).append(' ')
            }
        }
        return mA2dp ?: throw Exception("A2DP 代理不可用 [$diag]")
    }

    /** 反射注入 BluetoothFrameworkInitializer 的静态 BluetoothServiceManager 字段 */
    private fun frameworkInit(diag: StringBuilder) {
        try {
            val bsm = Class.forName("android.os.BluetoothServiceManager")
            val ctor = bsm.getDeclaredConstructor()
            ctor.isAccessible = true
            val mgr = ctor.newInstance()
            val bfi = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
            for (f in bfi.declaredFields) {
                if (f.type == bsm &&
                    java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    f.set(null, mgr)
                    diag.append("fwField=ok ")
                    return
                }
            }
            diag.append("fwField=notfound ")
        } catch (t: Throwable) {
            diag.append("fwField=").append(rootCause(t)).append(' ')
        }
    }

    /** 复刻 BluetoothAdapter.createAdapter：从 binder 手工构造（本机签名 (IBluetoothManager, Context)） */
    private fun adapterFromBinder(diag: StringBuilder): BluetoothAdapter? {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "bluetooth_manager") as IBinder
            diag.append("binder=").append(binder != null).append(' ')
            val stub = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
            val mgr = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            val ibm = Class.forName("android.bluetooth.IBluetoothManager")
            return try {
                val ctor = BluetoothAdapter::class.java
                    .getDeclaredConstructor(ibm, Context::class.java)
                ctor.isAccessible = true
                diag.append("via=ctor2 ")
                ctor.newInstance(mgr, mContext)
            } catch (e: NoSuchMethodException) {
                // AOSP main 分支：(IBluetoothManager, AttributionSource)
                val asClass = Class.forName("android.content.AttributionSource")
                val attr = asClass.getDeclaredMethod("myAttributionSource").invoke(null)
                val ctor = BluetoothAdapter::class.java
                    .getDeclaredConstructor(ibm, asClass)
                ctor.isAccessible = true
                diag.append("via=ctorAS ")
                ctor.newInstance(mgr, attr)
            }
        } catch (t: Throwable) {
            diag.append("binderEx=").append(rootCause(t)).append(' ')
            return null
        }
    }

    private fun findDevice(a2dp: BluetoothA2dp, mac: String?): BluetoothDevice? {
        val devices = a2dp.connectedDevices
        if (mac == null || mac.trim().isEmpty()) {
            return if (devices.isEmpty()) null else devices[0]
        }
        for (d in devices) {
            if (d.address.equals(mac.trim(), ignoreCase = true)) return d
        }
        return null
    }

    private fun currentConfig(a2dp: BluetoothA2dp, dev: BluetoothDevice): CodecConfig {
        val status = a2dp.javaClass
            .getMethod("getCodecStatus", BluetoothDevice::class.java)
            .invoke(a2dp, dev)
        val cfg = status.javaClass.getMethod("getCodecConfig").invoke(status)
        return configFromReflected(cfg!!)
    }

    /** 构造 BluetoothCodecConfig：Android 13+ 用 Builder，更老的走 9 参构造 */
    private fun buildConfig(
        codecType: Int, priority: Int, sampleRate: Int,
        bits: Int, channel: Int,
        cs1: Long, cs2: Long, cs3: Long, cs4: Long,
    ): Any {
        val bcc = Class.forName("android.bluetooth.BluetoothCodecConfig")
        return try {
            val builderCls = Class.forName("android.bluetooth.BluetoothCodecConfig\$Builder")
            val builder = builderCls.getDeclaredConstructor().newInstance()
            builderCls.getMethod("setCodecType", Int::class.java).invoke(builder, codecType)
            builderCls.getMethod("setCodecPriority", Int::class.java).invoke(builder, priority)
            builderCls.getMethod("setSampleRate", Int::class.java).invoke(builder, sampleRate)
            builderCls.getMethod("setBitsPerSample", Int::class.java).invoke(builder, bits)
            builderCls.getMethod("setChannelMode", Int::class.java).invoke(builder, channel)
            builderCls.getMethod("setCodecSpecific1", Long::class.java).invoke(builder, cs1)
            builderCls.getMethod("setCodecSpecific2", Long::class.java).invoke(builder, cs2)
            builderCls.getMethod("setCodecSpecific3", Long::class.java).invoke(builder, cs3)
            builderCls.getMethod("setCodecSpecific4", Long::class.java).invoke(builder, cs4)
            builderCls.getMethod("build").invoke(builder)
        } catch (e: NoSuchMethodException) {
            ctor9(bcc, codecType, priority, sampleRate, bits, channel, cs1, cs2, cs3, cs4)
        } catch (e: ClassNotFoundException) {
            ctor9(bcc, codecType, priority, sampleRate, bits, channel, cs1, cs2, cs3, cs4)
        }
    }

    private fun ctor9(
        bcc: Class<*>, codecType: Int, priority: Int, sampleRate: Int,
        bits: Int, channel: Int,
        cs1: Long, cs2: Long, cs3: Long, cs4: Long,
    ): Any {
        val ctor = bcc.getDeclaredConstructor(
            Int::class.java, Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Long::class.java, Long::class.java, Long::class.java, Long::class.java)
        return ctor.newInstance(codecType, priority, sampleRate, bits, channel, cs1, cs2, cs3, cs4)
    }

    // ── 反射读字段 ──

    private fun getInt(cfg: Any, getter: String): Int =
        cfg.javaClass.getMethod(getter).invoke(cfg) as Int

    private fun getLong(cfg: Any, getter: String): Long =
        cfg.javaClass.getMethod(getter).invoke(cfg) as Long

    private fun configFromReflected(cfg: Any): CodecConfig = CodecConfig(
        codecType = getInt(cfg, "getCodecType"),
        priority = getInt(cfg, "getCodecPriority"),
        sampleRate = getInt(cfg, "getSampleRate"),
        bitsPerSample = getInt(cfg, "getBitsPerSample"),
        channelMode = getInt(cfg, "getChannelMode"),
        cs1 = getLong(cfg, "getCodecSpecific1"),
        cs2 = getLong(cfg, "getCodecSpecific2"),
        cs3 = getLong(cfg, "getCodecSpecific3"),
        cs4 = getLong(cfg, "getCodecSpecific4"),
    )

    private fun CodecConfig.toJsonElement(): JsonElement = Json.parseToJsonElement(toJson())

    private fun safeName(d: BluetoothDevice): String = try {
        d.name ?: "?"
    } catch (t: Throwable) {
        "?"
    }

    private fun errorJson(op: String, t: Throwable): String = error("$op: ${rootCause(t)}")

    private fun error(msg: String): String = buildJsonObject {
        put("error", msg)
    }.toString()

    companion object {
        private const val TAG = "CodecService"

        private fun rootCause(t: Throwable): String {
            var cause = t
            while (cause is InvocationTargetException && cause.cause != null) {
                cause = cause.cause!!
            }
            return cause.javaClass.simpleName + ": " + cause.message
        }

        /** shell 进程一般不校验隐藏 API，但 OriginOS 可能不同——加个保险 */
        private fun disableHiddenApiChecks() {
            try {
                val vm = Class.forName("dalvik.system.VMRuntime")
                val runtime = vm.getDeclaredMethod("getRuntime").invoke(null)
                vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                    .invoke(runtime, arrayOf("L"))
            } catch (ignored: Throwable) {
            }
        }
    }
}
