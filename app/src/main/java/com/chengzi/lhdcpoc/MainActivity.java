package com.chengzi.lhdcpoc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * PoC 主界面。手动流程（对应 record-replay）：
 *   ① 检查 Shizuku → ② 枚举设备 → ③ 读取参数（在开发者选项手动调好目标状态后按）
 *   → ④ 保存为模板 → ⑤ 写入模板（断开重连/参数回滚后按，自动读回验证）
 */
public class MainActivity extends Activity {

    private static final int REQ_SHIZUKU = 1001;

    private TextView mLog;
    private EditText mMacInput;
    private volatile ICodecService mService;
    private Runnable mPendingAction;

    private String mLastConfigLine;   // 最近一次读到的 CONFIG|... 行

    // ─────────────────────────── 生命周期 ───────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        Shizuku.addBinderReceivedListenerSticky(() -> log("Shizuku binder 已连接"));
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == REQ_SHIZUKU) {
                log(grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Shizuku 权限已授予 ✓" : "Shizuku 权限被拒绝 ✗");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mService != null) {
            try {
                Shizuku.unbindUserService(buildArgs(), mConn, true);
            } catch (Throwable ignored) {
            }
        }
    }

    // ─────────────────────────── UI ───────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        mMacInput = new EditText(this);
        mMacInput.setHint("耳机 MAC（留空 = 第一台已连接设备）");
        mMacInput.setSingleLine();
        root.addView(mMacInput, lp());

        root.addView(btn("① 检查 Shizuku 并连接服务", v -> checkShizukuAndBind()), lp());
        root.addView(btn("② 枚举已连接设备", v -> callService(() -> mService.dumpDevices())), lp());
        root.addView(btn("③ 读取当前参数", v -> callService(this::doRead)), lp());
        root.addView(btn("④ 保存为模板", v -> saveTemplate()), lp());
        root.addView(btn("⑤ 写入模板参数（自动验证）", v -> callService(this::doWrite)), lp());

        mLog = new TextView(this);
        mLog.setTextColor(Color.DKGRAY);
        mLog.setTextSize(12);
        mLog.setMovementMethod(new ScrollingMovementMethod());
        mLog.setGravity(Gravity.TOP);
        ScrollView sv = new ScrollView(this);
        sv.addView(mLog);
        LinearLayout.LayoutParams lp = lp();
        lp.weight = 1;
        lp.topMargin = pad;
        root.addView(sv, lp);

        setContentView(root);
        log("PoC 就绪。请先连上 Enco X3，并按 ①。");
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void log(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        runOnUiThread(() -> {
            mLog.append("[" + ts + "] " + s + "\n");
        });
        android.util.Log.i("LhdcPoc", s);
    }

    // ─────────────────────────── Shizuku 连接 ───────────────────────────

    private void checkShizukuAndBind() {
        try {
            if (!Shizuku.pingBinder()) {
                log("Shizuku 未运行——请先在 Shizuku App 里启动服务");
                return;
            }
            log("Shizuku 运行中，API v" + Shizuku.getVersion());
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                log("请求 Shizuku 权限…（请在弹窗中允许）");
                Shizuku.requestPermission(REQ_SHIZUKU);
                return;
            }
            bindService(null);
        } catch (Throwable t) {
            log("Shizuku 异常: " + t);
        }
    }

    private Shizuku.UserServiceArgs buildArgs() {
        return new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), CodecService.class.getName()))
                .daemon(false)
                .processNameSuffix("codec")
                .debuggable(true)
                .version(1);
    }

    private final ServiceConnection mConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ICodecService.Stub.asInterface(binder);
            log("特权服务已连接 ✓");
            if (mPendingAction != null) {
                Runnable r = mPendingAction;
                mPendingAction = null;
                r.run();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            log("特权服务已断开");
        }
    };

    private void bindService(Runnable afterBound) {
        if (mService != null) {
            if (afterBound != null) afterBound.run();
            return;
        }
        mPendingAction = afterBound;
        try {
            Shizuku.bindUserService(buildArgs(), mConn);
            log("正在绑定特权服务…");
        } catch (Throwable t) {
            log("绑定失败: " + t);
        }
    }

    /** 所有 IPC 调用走后台线程，结果打进日志 */
    private void callService(ServiceCall call) {
        if (mService == null) {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                log("请先按 ① 完成 Shizuku 授权");
                return;
            }
            bindService(() -> callService(call));
            return;
        }
        new Thread(() -> {
            try {
                String result = call.run();
                log(result);
            } catch (Throwable t) {
                log("IPC 调用失败: " + t);
            }
        }).start();
    }

    private interface ServiceCall {
        String run() throws Exception;
    }

    // ─────────────────────────── 读 / 存 / 写 ───────────────────────────

    private String mac() {
        return mMacInput.getText().toString().trim();
    }

    private String doRead() throws Exception {
        String out = mService.readConfig(mac());
        // 抽出 CONFIG|... 行缓存起来
        for (String line : out.split("\n")) {
            if (line.startsWith("CONFIG|")) mLastConfigLine = line;
        }
        return out;
    }

    private void saveTemplate() {
        if (mLastConfigLine == null) {
            log("还没有读到过配置——先按 ③（记得先在开发者选项里把参数调到目标状态）");
            return;
        }
        SharedPreferences p = getSharedPreferences("tpl", MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        for (String kv : mLastConfigLine.split("\\|")) {
            if (kv.equals("CONFIG")) continue;
            String[] pair = kv.split("=", 2);
            if (pair.length == 2) e.putString(pair[0], pair[1]);
        }
        e.apply();
        log("模板已保存: " + mLastConfigLine);
    }

    private String doWrite() throws Exception {
        SharedPreferences p = getSharedPreferences("tpl", MODE_PRIVATE);
        if (!p.contains("type")) {
            return "还没有模板——先按 ③ 读取、④ 保存";
        }
        return mService.writeConfig(
                mac(),
                Integer.parseInt(p.getString("type", "0")),
                Integer.parseInt(p.getString("priority", "0")),
                Integer.parseInt(p.getString("rate", "0")),
                Integer.parseInt(p.getString("bits", "0")),
                Integer.parseInt(p.getString("channel", "0")),
                Long.parseLong(p.getString("cs1", "0")),
                Long.parseLong(p.getString("cs2", "0")),
                Long.parseLong(p.getString("cs3", "0")),
                Long.parseLong(p.getString("cs4", "0")));
    }
}
