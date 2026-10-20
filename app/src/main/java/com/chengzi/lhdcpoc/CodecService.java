package com.chengzi.lhdcpoc;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 本类运行在 Shizuku 拉起的独立进程中，身份是 adb shell (uid 2000)，
 * 天然持有 BLUETOOTH_PRIVILEGED，因此可以调用隐藏 API：
 *   BluetoothA2dp.getCodecStatus() / setCodecConfigPreference()
 *
 * PoC 原则：全部走反射，不依赖任何隐藏类的编译期符号。
 */
public class CodecService extends ICodecService.Stub {

    private static final String TAG = "CodecService";

    private Context mContext;                 // shell 进程的 system context（拿不到就为 null）
    private HandlerThread mThread;            // A2DP 代理回调需要 Looper
    private volatile BluetoothA2dp mA2dp;

    public CodecService() {
        disableHiddenApiChecks();
        // app_process 裸进程没有主 Looper，而 ActivityThread.systemMain() 需要它；
        // Shizuku 场景下主 Looper 已存在，prepareMainLooper 只能调一次，故先判空
        if (android.os.Looper.getMainLooper() == null) {
            android.os.Looper.prepareMainLooper();
        }
        mThread = new HandlerThread("bt-worker");
        mThread.start();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("systemMain").invoke(null);
            mContext = (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            Log.w(TAG, "no system context, fallback to null", t);
            t.printStackTrace();   // app_process 场景下打到 stdout，方便 adb 直接看
        }
    }

    // ─────────────────────────── IPC 接口 ───────────────────────────

    @Override
    public String dumpDevices() {
        try {
            BluetoothA2dp a2dp = getA2dp();
            List<BluetoothDevice> devices = a2dp.getConnectedDevices();
            if (devices.isEmpty()) return "没有已连接的 A2DP 设备（先连上 Enco X3）";
            StringBuilder sb = new StringBuilder("已连接设备:\n");
            for (BluetoothDevice d : devices) {
                sb.append("• ").append(safeName(d)).append("  [").append(d.getAddress()).append("]\n");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERROR(dumpDevices): " + rootCause(t);
        }
    }

    @Override
    public String readConfig(String mac) {
        try {
            BluetoothA2dp a2dp = getA2dp();
            BluetoothDevice dev = findDevice(a2dp, mac);
            if (dev == null) return "ERROR: 找不到目标设备 " + mac;

            Object status = a2dp.getClass()
                    .getMethod("getCodecStatus", BluetoothDevice.class)
                    .invoke(a2dp, dev);
            Object cfg = status.getClass().getMethod("getCodecConfig").invoke(status);

            StringBuilder sb = new StringBuilder();
            sb.append("设备: ").append(safeName(dev)).append(" [").append(dev.getAddress()).append("]\n");
            sb.append("当前配置: ").append(describe(cfg)).append('\n');
            sb.append(machineLine(cfg)).append('\n');

            List<?> selectable = (List<?>) status.getClass()
                    .getMethod("getCodecsSelectableCapabilities").invoke(status);
            sb.append("可选择的编码能力 (").append(selectable.size()).append(" 项):\n");
            for (Object cap : selectable) {
                sb.append("  - ").append(describe(cap)).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "ERROR(readConfig): " + rootCause(t);
        }
    }

    @Override
    public String writeConfig(String mac, int codecType, int codecPriority, int sampleRate,
                              int bitsPerSample, int channelMode,
                              long cs1, long cs2, long cs3, long cs4) {
        try {
            BluetoothA2dp a2dp = getA2dp();
            BluetoothDevice dev = findDevice(a2dp, mac);
            if (dev == null) return "ERROR: 找不到目标设备 " + mac;

            Object cfg = buildConfig(codecType, codecPriority, sampleRate,
                    bitsPerSample, channelMode, cs1, cs2, cs3, cs4);

            Class<?> bccClass = Class.forName("android.bluetooth.BluetoothCodecConfig");
            Method m = BluetoothA2dp.class.getMethod(
                    "setCodecConfigPreference", BluetoothDevice.class, bccClass);
            m.invoke(a2dp, dev, cfg);

            StringBuilder sb = new StringBuilder();
            sb.append("已调用 setCodecConfigPreference: ")
                    .append("type=").append(codecType)
                    .append(" rate=").append(sampleRate)
                    .append(" bits=").append(bitsPerSample)
                    .append(" cs1=").append(cs1).append('\n');

            // 写入后验证：协议栈重新协商需要时间，最多轮询 ~4 秒
            for (int i = 0; i < 8; i++) {
                Thread.sleep(500);
                Object status = a2dp.getClass()
                        .getMethod("getCodecStatus", BluetoothDevice.class)
                        .invoke(a2dp, dev);
                Object cur = status.getClass().getMethod("getCodecConfig").invoke(status);
                int curType = getInt(cur, "getCodecType");
                int curRate = getInt(cur, "getSampleRate");
                int curBits = getInt(cur, "getBitsPerSample");
                long curCs1 = getLong(cur, "getCodecSpecific1");
                boolean match = curType == codecType
                        && (sampleRate == 0 || curRate == sampleRate)
                        && (bitsPerSample == 0 || curBits == bitsPerSample);
                sb.append("读回 #").append(i + 1).append(": ").append(describe(cur));
                sb.append(match ? "  ✓ 一致\n" : "  ✗ 未生效\n");
                if (match) {
                    sb.append("结论: 写入成功并已生效");
                    return sb.toString();
                }
            }
            sb.append("结论: 写入未被栈接受（可能被 ROM 拦截或参数组合不支持）");
            return sb.toString();
        } catch (Throwable t) {
            return "ERROR(writeConfig): " + rootCause(t);
        }
    }

    @Override
    public void destroy() {
        Log.i(TAG, "destroy");
    }

    /** 探测这台机器上蓝牙类的真实构造器/工厂方法签名 */
    public String probe() {
        StringBuilder sb = new StringBuilder();
        try {
            Class<?> ba = Class.forName("android.bluetooth.BluetoothAdapter");
            sb.append("== BluetoothAdapter 构造器 ==\n");
            for (Constructor<?> c : ba.getDeclaredConstructors()) sb.append("  ").append(c).append('\n');
            sb.append("== 返回 BluetoothAdapter 的静态方法 ==\n");
            for (Method m : ba.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == ba) sb.append("  ").append(m).append('\n');
            }
            Class<?> a2dp = Class.forName("android.bluetooth.BluetoothA2dp");
            sb.append("== BluetoothA2dp 构造器 ==\n");
            for (Constructor<?> c : a2dp.getDeclaredConstructors()) sb.append("  ").append(c).append('\n');
            Class<?> ibm = Class.forName("android.bluetooth.IBluetoothManager");
            sb.append("== IBluetoothManager 中含 a2dp/profile 的方法 ==\n");
            for (Method m : ibm.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("a2dp") || n.contains("profile")) sb.append("  ").append(m).append('\n');
            }
            sb.append("== BluetoothAdapter 中含 profile 的方法 ==\n");
            for (Method m : ba.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("profile")) sb.append("  ").append(m).append('\n');
            }
            Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
            sb.append("== BluetoothServiceManager 构造器 ==\n");
            for (Constructor<?> c : bsm.getDeclaredConstructors()) sb.append("  ").append(c).append('\n');
            sb.append("== BluetoothFrameworkInitializer 字段 ==\n");
            for (java.lang.reflect.Field f : bfi2()) sb.append("  ").append(f).append('\n');
        } catch (Throwable t) {
            sb.append("ERROR: ").append(t);
        }
        return sb.toString();
    }

    private static java.lang.reflect.Field[] bfi2() throws Exception {
        return Class.forName("android.bluetooth.BluetoothFrameworkInitializer").getDeclaredFields();
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private volatile BluetoothAdapter mAdapter;

    private BluetoothA2dp getA2dp() throws Exception {
        if (mA2dp != null) return mA2dp;
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder diag = new StringBuilder();
        new Handler(mThread.getLooper()).post(() -> {
            try {
                // 0) 裸进程没有初始化蓝牙框架静态注册表 → 直接反射注入 BluetoothServiceManager 实例
                frameworkInit(diag);
                BluetoothAdapter adapter = null;
                // 1) Android 14+ 的工厂方法：BluetoothAdapter.createAdapter(context)
                if (mContext != null) {
                    try {
                        Method create = BluetoothAdapter.class
                                .getDeclaredMethod("createAdapter", Context.class);
                        create.setAccessible(true);
                        adapter = (BluetoothAdapter) create.invoke(null, mContext);
                        diag.append("createAdapter=").append(adapter != null).append(' ');
                    } catch (Throwable t) {
                        diag.append("createEx=").append(rootCause(t)).append(' ');
                    }
                }
                // 2) 系统服务包装
                if (adapter == null && mContext != null) {
                    android.bluetooth.BluetoothManager bm =
                            mContext.getSystemService(android.bluetooth.BluetoothManager.class);
                    if (bm != null) adapter = bm.getAdapter();
                    diag.append("bm.getAdapter=").append(adapter != null).append(' ');
                }
                // 3) 传统静态入口
                if (adapter == null) {
                    adapter = BluetoothAdapter.getDefaultAdapter();
                    diag.append("getDefaultAdapter=").append(adapter != null).append(' ');
                }
                // 4) 手工复刻 createAdapter：bluetooth_manager binder + 探测到的构造器签名
                if (adapter == null) {
                    adapter = adapterFromBinder(diag);
                }
                mAdapter = adapter;
                if (adapter == null) { latch.countDown(); return; }
                try {
                    diag.append("enabled=").append(adapter.isEnabled()).append(' ');
                } catch (Throwable t) {
                    diag.append("enabledEx=").append(rootCause(t)).append(' ');
                }
                boolean ok = adapter.getProfileProxy(mContext, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        mA2dp = (BluetoothA2dp) proxy;
                        latch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(int profile) {
                        mA2dp = null;
                    }
                }, BluetoothProfile.A2DP);
                diag.append("proxyReq=").append(ok).append(' ');
                if (!ok) latch.countDown();
            } catch (Throwable t) {
                diag.append("EX:").append(t);
                latch.countDown();
            }
        });
        latch.await(5, TimeUnit.SECONDS);
        // 5) 代理回调没回来 → 手工构造 proxy 并用 onServiceConnected 接线
        if (mA2dp == null && mAdapter != null) {
            try {
                Constructor<BluetoothA2dp> ctor = BluetoothA2dp.class
                        .getDeclaredConstructor(Context.class, BluetoothAdapter.class);
                ctor.setAccessible(true);
                BluetoothA2dp proxy = ctor.newInstance(mContext, mAdapter);
                Method getProfile = BluetoothAdapter.class
                        .getDeclaredMethod("getProfile", int.class);
                getProfile.setAccessible(true);
                android.os.IBinder svcBinder =
                        (android.os.IBinder) getProfile.invoke(mAdapter, BluetoothProfile.A2DP);
                diag.append("profileBinder=").append(svcBinder != null).append(' ');
                if (svcBinder != null) {
                    BluetoothA2dp.class.getMethod("onServiceConnected", android.os.IBinder.class)
                            .invoke(proxy, svcBinder);
                    mA2dp = proxy;
                    diag.append("directWire=ok ");
                }
            } catch (Throwable t) {
                diag.append("directEx=").append(rootCause(t)).append(' ');
            }
        }
        if (mA2dp == null) throw new Exception("A2DP 代理不可用 [" + diag + "]");
        return mA2dp;
    }

    /** 反射注入 BluetoothFrameworkInitializer 的静态 BluetoothServiceManager 字段 */
    private void frameworkInit(StringBuilder diag) {
        try {
            Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
            Constructor<?> ctor = bsm.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object mgr = ctor.newInstance();
            Class<?> bfi = Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            for (java.lang.reflect.Field f : bfi.getDeclaredFields()) {
                if (f.getType() == bsm && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    f.set(null, mgr);
                    diag.append("fwField=ok ");
                    return;
                }
            }
            diag.append("fwField=notfound ");
        } catch (Throwable t) {
            diag.append("fwField=").append(rootCause(t)).append(' ');
        }
    }

    /** 复刻 BluetoothAdapter.createAdapter：从 binder 手工构造（本机签名 (IBluetoothManager, Context)） */
    private BluetoothAdapter adapterFromBinder(StringBuilder diag) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            android.os.IBinder binder = (android.os.IBinder)
                    sm.getMethod("getService", String.class).invoke(null, "bluetooth_manager");
            diag.append("binder=").append(binder != null).append(' ');
            Class<?> stub = Class.forName("android.bluetooth.IBluetoothManager$Stub");
            Object mgr = stub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binder);
            Class<?> ibm = Class.forName("android.bluetooth.IBluetoothManager");
            try {
                Constructor<BluetoothAdapter> ctor = BluetoothAdapter.class
                        .getDeclaredConstructor(ibm, Context.class);
                ctor.setAccessible(true);
                diag.append("via=ctor2 ");
                return ctor.newInstance(mgr, mContext);
            } catch (NoSuchMethodException e) {
                // AOSP main 分支：(IBluetoothManager, AttributionSource)
                Class<?> as = Class.forName("android.content.AttributionSource");
                Object attr = as.getDeclaredMethod("myAttributionSource").invoke(null);
                Constructor<BluetoothAdapter> ctor = BluetoothAdapter.class
                        .getDeclaredConstructor(ibm, as);
                ctor.setAccessible(true);
                diag.append("via=ctorAS ");
                return ctor.newInstance(mgr, attr);
            }
        } catch (Throwable t) {
            diag.append("binderEx=").append(rootCause(t)).append(' ');
            return null;
        }
    }

    private BluetoothDevice findDevice(BluetoothA2dp a2dp, String mac) {
        List<BluetoothDevice> devices = a2dp.getConnectedDevices();
        if (mac == null || mac.trim().isEmpty()) {
            return devices.isEmpty() ? null : devices.get(0);
        }
        for (BluetoothDevice d : devices) {
            if (d.getAddress().equalsIgnoreCase(mac.trim())) return d;
        }
        return null;
    }

    /** 构造 BluetoothCodecConfig：Android 13+ 用 Builder，更老的走 9 参构造 */
    private Object buildConfig(int codecType, int priority, int sampleRate,
                               int bits, int channel,
                               long cs1, long cs2, long cs3, long cs4) throws Exception {
        Class<?> bcc = Class.forName("android.bluetooth.BluetoothCodecConfig");
        try {
            Class<?> builderCls = Class.forName("android.bluetooth.BluetoothCodecConfig$Builder");
            Object builder = builderCls.getDeclaredConstructor().newInstance();
            builderCls.getMethod("setCodecType", int.class).invoke(builder, codecType);
            builderCls.getMethod("setCodecPriority", int.class).invoke(builder, priority);
            builderCls.getMethod("setSampleRate", int.class).invoke(builder, sampleRate);
            builderCls.getMethod("setBitsPerSample", int.class).invoke(builder, bits);
            builderCls.getMethod("setChannelMode", int.class).invoke(builder, channel);
            builderCls.getMethod("setCodecSpecific1", long.class).invoke(builder, cs1);
            builderCls.getMethod("setCodecSpecific2", long.class).invoke(builder, cs2);
            builderCls.getMethod("setCodecSpecific3", long.class).invoke(builder, cs3);
            builderCls.getMethod("setCodecSpecific4", long.class).invoke(builder, cs4);
            return builderCls.getMethod("build").invoke(builder);
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            Constructor<?> ctor = bcc.getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class,
                    long.class, long.class, long.class, long.class);
            return ctor.newInstance(codecType, priority, sampleRate, bits, channel,
                    cs1, cs2, cs3, cs4);
        }
    }

    // ── 反射读字段 ──

    private static int getInt(Object cfg, String getter) throws Exception {
        return (int) cfg.getClass().getMethod(getter).invoke(cfg);
    }

    private static long getLong(Object cfg, String getter) throws Exception {
        return (long) cfg.getClass().getMethod(getter).invoke(cfg);
    }

    private static String describe(Object cfg) throws Exception {
        int type = getInt(cfg, "getCodecType");
        int rate = getInt(cfg, "getSampleRate");
        int bits = getInt(cfg, "getBitsPerSample");
        int channel = getInt(cfg, "getChannelMode");
        int priority = getInt(cfg, "getCodecPriority");
        long cs1 = getLong(cfg, "getCodecSpecific1");
        return "codec=" + codecName(type) + "(" + type + ")"
                + " rate=" + rateHz(rate) + "(" + rate + ")"
                + " bits=" + bitsDepth(bits) + "(" + bits + ")"
                + " ch=" + channel
                + " prio=" + priority
                + " cs1=" + cs1;
    }

    /** 机器可读行，App 侧解析后存为模板 */
    private static String machineLine(Object cfg) throws Exception {
        return "CONFIG|type=" + getInt(cfg, "getCodecType")
                + "|priority=" + getInt(cfg, "getCodecPriority")
                + "|rate=" + getInt(cfg, "getSampleRate")
                + "|bits=" + getInt(cfg, "getBitsPerSample")
                + "|channel=" + getInt(cfg, "getChannelMode")
                + "|cs1=" + getLong(cfg, "getCodecSpecific1")
                + "|cs2=" + getLong(cfg, "getCodecSpecific2")
                + "|cs3=" + getLong(cfg, "getCodecSpecific3")
                + "|cs4=" + getLong(cfg, "getCodecSpecific4");
    }

    private static String codecName(int t) {
        switch (t) {
            case 0: return "SBC";
            case 1: return "AAC";
            case 2: return "aptX";
            case 3: return "aptX-HD";
            case 4: return "LDAC";
            default: return "vendor";   // LHDC 各版本为厂商私有编号，以实际读到的数字为准
        }
    }

    private static String rateHz(int mask) {
        switch (mask) {
            case 1: return "44.1k";
            case 2: return "48k";
            case 4: return "88.2k";
            case 8: return "96k";
            case 16: return "176.4k";
            case 32: return "192k";
            default: return "?";
        }
    }

    private static String bitsDepth(int mask) {
        switch (mask) {
            case 1: return "16bit";
            case 2: return "24bit";
            case 4: return "32bit";
            default: return "?";
        }
    }

    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "?" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String rootCause(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** shell 进程一般不校验隐藏 API，但 OriginOS 可能不同——加个保险 */
    private static void disableHiddenApiChecks() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
        }
    }
}
