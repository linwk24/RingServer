package com.example.ringserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Root 模式：用 su 做系统级保活 + 开机自启，不依赖任何第三方框架。
 *
 * 能力：
 *  1. 系统白名单（Doze / App Standby / 后台限制 / 部分 ROM 自启动）
 *  2. OOM 保护（把本应用进程的 oom_score_adj 固定为 -800，低内存不优先回收）
 *  3. 看门狗守护脚本（root 独立进程常驻：应用进程被杀就自动拉起服务）
 *  4. 开机自启（Magisk service.d 脚本 + 系统 BOOT_COMPLETED 广播双通道）
 *
 * 注意：/data/adb/service.d 与 /data/local/tmp 属 root/shell 所有，应用进程
 * 无法直接写入。所有脚本一律先写入应用私有目录（getFilesDir），再经 su cp
 * 复制到目标位置，否则会因 EACCES 静默失败。
 */
public final class RootHelper {

    private static final String TAG = "RootHelper";

    private static final String PREFS = "ringserver_prefs";
    private static final String KEY_ROOT_MODE = "root_mode";
    private static final String KEY_BOOT_START = "boot_start";

    private static final String PKG = "com.example.ringserver";
    private static final String SVC = PKG + "/.RingServerService";

    private static final String WATCHDOG_SCRIPT = "/data/local/tmp/ringserver_watchdog.sh";
    private static final String WATCHDOG_PID = "/data/local/tmp/ringserver_watchdog.pid";
    private static final String BOOT_SCRIPT = "/data/adb/service.d/ringserver_boot.sh";

    private RootHelper() {
    }

    // ------------------------------------------------------------------
    // 偏好设置
    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isRootModeEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ROOT_MODE, false);
    }

    public static void setRootMode(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ROOT_MODE, enabled).apply();
    }

    public static boolean isBootStartEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_BOOT_START, false);
    }

    public static void setBootStart(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_BOOT_START, enabled).apply();
    }

    // ------------------------------------------------------------------
    // root 探测与 su 执行
    // ------------------------------------------------------------------

    /** 是否拥有 root（su 可用且 uid 为 0） */
    public static boolean isRootAvailable() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String first = r.readLine();
            while (r.readLine() != null) { /* drain */ }
            p.waitFor();
            return first != null && first.trim().equals("0");
        } catch (Exception e) {
            Log.d(TAG, "root check failed", e);
            return false;
        }
    }

    /** 执行一条 su 命令，返回是否成功（exit code == 0） */
    public static boolean su(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            while (r.readLine() != null) { /* drain，避免管道阻塞 */ }
            return p.waitFor() == 0;
        } catch (Exception e) {
            Log.e(TAG, "su failed: " + command, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 系统级白名单 + OOM 保护
    // ------------------------------------------------------------------

    /**
     * 执行一批系统白名单命令。个别命令在部分 ROM/版本上不存在，
     * 失败只记日志，不影响其它项。
     */
    public static void applySystemProtections() {
        String[] cmds = {
                "dumpsys deviceidle whitelist +" + PKG,
                "cmd deviceidle whitelist +" + PKG,
                "am set-standby-bucket " + PKG + " active",
                "cmd appops set " + PKG + " RUN_IN_BACKGROUND allow",
                "cmd appops set " + PKG + " RUN_ANY_IN_BACKGROUND allow",
                "appops set " + PKG + " AUTO_START allow",
        };
        for (String c : cmds) {
            if (!su(c)) {
                Log.d(TAG, "not effective (ignore): " + c);
            }
        }
    }

    // ------------------------------------------------------------------
    // 看门狗守护脚本
    // ------------------------------------------------------------------

    /** 启动看门狗（root 独立守护进程；已在运行则跳过） */
    public static void startWatchdog(Context ctx) {
        if (isWatchdogRunning()) {
            Log.i(TAG, "watchdog already running");
            return;
        }
        try {
            // 应用进程写不了 /data/local/tmp：先写私有目录，再 su cp
            File staging = new File(ctx.getFilesDir(), "ringserver_watchdog.sh");
            copyAssetToFile(ctx, "ringserver_watchdog.sh", staging);
            if (!su("cp " + q(staging.getAbsolutePath()) + " " + WATCHDOG_SCRIPT)) {
                Log.e(TAG, "copy watchdog script failed");
                return;
            }
            if (!su("chmod 755 " + WATCHDOG_SCRIPT)) {
                Log.e(TAG, "chmod watchdog failed");
                return;
            }
            // nohup + setsid + 输入重定向：与 su 会话分离，su 退出后守护进程继续存活
            su("nohup setsid sh " + WATCHDOG_SCRIPT + " >/dev/null 2>&1 < /dev/null &");
            if (!isWatchdogRunning()) {
                Log.w(TAG, "watchdog launched but not detected, check su permission");
            } else {
                Log.i(TAG, "watchdog running");
            }
        } catch (Exception e) {
            Log.e(TAG, "startWatchdog failed", e);
        }
    }

    /** 停止看门狗 */
    public static void stopWatchdog() {
        su("kill $(cat " + WATCHDOG_PID + " 2>/dev/null) 2>/dev/null");
        su("rm -f " + WATCHDOG_PID);
    }

    /** 看门狗是否在运行 */
    public static boolean isWatchdogRunning() {
        return su("kill -0 $(cat " + WATCHDOG_PID + " 2>/dev/null) 2>/dev/null");
    }

    /** Root 模式开启时确保看门狗在跑（开机 / 服务启动时调用） */
    public static void ensureWatchdog(Context ctx) {
        if (!isRootModeEnabled(ctx) || !isRootAvailable()) {
            return;
        }
        startWatchdog(ctx);
    }

    // ------------------------------------------------------------------
    // 开机自启（Magisk service.d 脚本 + 系统广播）
    // ------------------------------------------------------------------

    public static boolean isMagiskPresent() {
        return su("test -d /data/adb/service.d");
    }

    /** 安装 Magisk 开机脚本；无 Magisk 返回 false（此时走系统广播路径） */
    public static boolean installBootScript(Context ctx) {
        if (!isMagiskPresent()) {
            Log.i(TAG, "no Magisk service.d, boot autostart via system broadcast only");
            return false;
        }
        try {
            // 应用进程写不了 /data/adb/service.d：先写私有目录，再 su cp
            File staging = new File(ctx.getFilesDir(), "ringserver_boot.sh");
            copyAssetToFile(ctx, "ringserver_boot.sh", staging);
            boolean copied = su("cp " + q(staging.getAbsolutePath()) + " " + BOOT_SCRIPT);
            boolean ok = copied && su("chmod 755 " + BOOT_SCRIPT);
            Log.i(TAG, "boot script installed: " + ok);
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "installBootScript failed", e);
            return false;
        }
    }

    public static void removeBootScript() {
        su("rm -f " + BOOT_SCRIPT);
    }

    public static boolean isBootScriptInstalled() {
        return su("test -f " + BOOT_SCRIPT);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** shell 单引号引用路径（路径中无单引号，安全） */
    private static String q(String s) {
        return "'" + s + "'";
    }

    /** 把 assets 里的脚本写入指定文件，统一转成 LF 换行（避免 CRLF 导致 sh 失效） */
    private static void copyAssetToFile(Context ctx, String assetName, File dest) throws Exception {
        try (InputStream in = ctx.getAssets().open(assetName)) {
            ByteArrayOutputStream tmp = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                tmp.write(buf, 0, n);
            }
            String text = new String(tmp.toByteArray(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
