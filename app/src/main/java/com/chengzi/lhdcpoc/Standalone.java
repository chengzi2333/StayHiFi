package com.chengzi.lhdcpoc;

/**
 * 独立入口：不依赖 Shizuku，直接通过 adb shell + app_process 以 shell(uid 2000) 身份运行，
 * 复用 CodecService 的全部读/写/验证逻辑。验证 shell 权限在 OriginOS 7 上的可行性。
 *
 * 用法（Mac 上）：
 *   APK=$(adb shell pm path com.chengzi.lhdcpoc | sed 's/package://')
 *   adb shell "CLASSPATH=$APK app_process /system/bin com.chengzi.lhdcpoc.Standalone devices"
 *   adb shell "CLASSPATH=$APK app_process /system/bin com.chengzi.lhdcpoc.Standalone read [MAC]"
 *   adb shell "CLASSPATH=$APK app_process /system/bin com.chengzi.lhdcpoc.Standalone write <MAC> <type> <prio> <rate> <bits> <ch> <cs1> <cs2> <cs3> <cs4>"
 */
public class Standalone {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: devices | read [mac] | write mac type prio rate bits ch cs1 cs2 cs3 cs4");
            System.exit(1);
        }
        final String[] a = args;
        // 蓝牙代理的回调可能投递到主 Looper——主线程专职 pump，命令在 worker 线程执行
        final CodecService svc = new CodecService();
        new Thread(() -> {
            try {
                System.out.println(run(svc, a));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            System.exit(0);
        }).start();
        android.os.Looper.loop();
    }

    private static String run(CodecService svc, String[] args) throws Exception {
        switch (args[0]) {
            case "probe":
                return svc.probe();
            case "devices":
                return svc.dumpDevices();
            case "read":
                return svc.readConfig(args.length > 1 ? args[1] : "");
            case "write":
                if (args.length < 11) {
                    return "usage: write mac type prio rate bits ch cs1 cs2 cs3 cs4";
                }
                return svc.writeConfig(
                        args[1],
                        Integer.parseInt(args[2]),
                        Integer.parseInt(args[3]),
                        Integer.parseInt(args[4]),
                        Integer.parseInt(args[5]),
                        Integer.parseInt(args[6]),
                        Long.parseLong(args[7]),
                        Long.parseLong(args[8]),
                        Long.parseLong(args[9]),
                        Long.parseLong(args[10]));
            default:
                return "unknown command: " + args[0];
        }
    }
}
