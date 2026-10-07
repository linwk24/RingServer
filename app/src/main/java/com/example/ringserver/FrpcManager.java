package com.example.ringserver;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 公网穿透：把 frpc（以 {@code libfrpc.so} 的名义打进 jniLibs）作为子进程拉起。
 *
 * <p>为什么用 .so：Android 10+ 禁止 app 执行自己 home 目录下的文件（W^X）。
 * 而打包进 jniLibs 的文件会被系统安装时解压到 native library 目录并授予执行权限，
 * 该目录允许执行 —— 于是免 root 即可跑原生可执行文件。
 *
 * <p>生命周期跟随 {@link RingServerService}：服务创建时按配置拉起，销毁时结束。
 */
final class FrpcManager {

    private static final String TAG = "FrpcManager";

    private static final String EXE_NAME = "libfrpc.so";
    private static final String CONF_NAME = "frpc.toml";
    private static final int MAX_LOG_LINES = 300;

    private static final Object LOCK = new Object();
    private static final Deque<String> LOG = new ArrayDeque<>();

    private static Process process;
    private static volatile String lastError = "";

    private FrpcManager() {
    }

    static boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    static String getLastError() {
        return lastError;
    }

    /** 取最近若干行日志，供界面展示 */
    static String tailLog(int maxLines) {
        synchronized (LOG) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, LOG.size() - maxLines);
            int i = 0;
            for (String line : LOG) {
                if (i++ < skip) {
                    continue;
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 按当前配置同步 frpc 状态：开关关闭 → 停掉；开关打开 → 用最新配置（重）启动。
     */
    static void sync(Context context) {
        Context ctx = context.getApplicationContext();
        if (!Prefs.isFrpcEnabled(ctx)) {
            stop();
            appendLog("[frpc] 公网穿透已关闭");
            return;
        }
        String addr = Prefs.getFrpcServerAddr(ctx);
        if (addr.isEmpty()) {
            stop();
            lastError = "未填写 frps 服务器地址";
            appendLog("[frpc] 未填写 frps 服务器地址，跳过启动");
            return;
        }
        restart(ctx);
    }

    /** 停掉旧进程后按当前配置重启 */
    static void restart(Context context) {
        Context ctx = context.getApplicationContext();
        synchronized (LOCK) {
            stopLocked();
            clearLog();

            File exe = new File(ctx.getApplicationInfo().nativeLibraryDir, EXE_NAME);
            if (!exe.exists()) {
                lastError = "未找到内置 frpc（" + exe.getAbsolutePath() + "），本机可能不是 arm64 设备";
                appendLog("[frpc] " + lastError);
                Log.e(TAG, lastError);
                return;
            }

            File conf = new File(ctx.getFilesDir(), CONF_NAME);
            try {
                writeConfig(ctx, conf);
            } catch (Exception e) {
                lastError = "写入 frpc 配置失败：" + e.getMessage();
                appendLog("[frpc] " + lastError);
                Log.e(TAG, lastError, e);
                return;
            }

            try {
                ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-c", conf.getAbsolutePath());
                pb.redirectErrorStream(true);
                pb.directory(ctx.getFilesDir());
                Process p = pb.start();
                process = p;
                lastError = "";
                appendLog("[frpc] 已启动 " + exe.getAbsolutePath());
                startLogReader(p);
                Log.i(TAG, "frpc started");
            } catch (Exception e) {
                process = null;
                lastError = "启动 frpc 失败：" + e.getMessage();
                appendLog("[frpc] " + lastError);
                Log.e(TAG, "start frpc failed", e);
            }
        }
    }

    static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        Process p = process;
        process = null;
        if (p == null) {
            return;
        }
        try {
            p.destroy();
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        } catch (Exception e) {
            Log.w(TAG, "stop frpc: " + e);
        }
        appendLog("[frpc] 已停止");
    }

    // ------------------------------------------------------------- internals

    private static void writeConfig(Context ctx, File conf) throws Exception {
        String addr = Prefs.getFrpcServerAddr(ctx);
        int serverPort = Prefs.getFrpcServerPort(ctx);
        String token = Prefs.getFrpcToken(ctx);
        int remotePort = Prefs.getFrpcRemotePort(ctx);

        StringBuilder sb = new StringBuilder();
        sb.append("# 由 RingServer 自动生成，请勿手动修改\n");
        sb.append("serverAddr = \"").append(escape(addr)).append("\"\n");
        sb.append("serverPort = ").append(serverPort).append("\n\n");
        if (!token.isEmpty()) {
            sb.append("auth.token = \"").append(escape(token)).append("\"\n\n");
        }
        sb.append("log.to = \"console\"\n");
        sb.append("log.level = \"info\"\n\n");
        // 连不上 frps 时不要退出，持续重试
        sb.append("loginFailExit = false\n\n");
        sb.append("[[proxies]]\n");
        sb.append("name = \"").append(escape(sanitizeProxyName(Prefs.getFrpcProxyName(ctx)))).append("\"\n");
        sb.append("type = \"tcp\"\n");
        sb.append("localIP = \"127.0.0.1\"\n");
        sb.append("localPort = ").append(RingServerService.PORT).append("\n");
        sb.append("remotePort = ").append(remotePort).append("\n");

        try (FileOutputStream fos = new FileOutputStream(conf)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }

    /** TOML 基本字符串转义 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * frp 对 proxy name 有字符限制（只认字母数字、下划线、短横线，且不能含 '.'），
     * 这里把非法字符统一换成下划线，兜底空名。
     */
    private static String sanitizeProxyName(String raw) {
        if (raw == null) {
            return "ringserver";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
            sb.append(ok ? ch : '_');
        }
        String s = sb.toString();
        return s.isEmpty() ? "ringserver" : s;
    }

    private static void startLogReader(final Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendLog(line);
                }
            } catch (Exception ignored) {
                // 进程结束时流会关闭，正常现象
            } finally {
                if (!p.isAlive()) {
                    appendLog("[frpc] 进程已退出（exit=" + p.exitValue() + "）");
                }
            }
        }, "frpc-log");
        t.setDaemon(true);
        t.start();
    }

    private static void appendLog(String line) {
        synchronized (LOG) {
            LOG.addLast(line);
            while (LOG.size() > MAX_LOG_LINES) {
                LOG.removeFirst();
            }
        }
    }

    private static void clearLog() {
        synchronized (LOG) {
            LOG.clear();
        }
    }
}
