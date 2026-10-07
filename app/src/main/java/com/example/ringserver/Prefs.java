package com.example.ringserver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 公网访问相关配置的统一存取：frpc 连接参数 + /ring 访问密钥。
 */
final class Prefs {

    private static final String FILE = "ringserver_net_prefs";

    // ---- frpc ----
    static final String KEY_FRPC_ENABLED = "frpc_enabled";
    static final String KEY_FRPC_ADDR = "frpc_server_addr";
    static final String KEY_FRPC_PORT = "frpc_server_port";
    static final String KEY_FRPC_TOKEN = "frpc_token";
    static final String KEY_FRPC_REMOTE_PORT = "frpc_remote_port";

    // ---- /ring 鉴权 ----
    static final String KEY_RING_KEY = "ring_key";

    static final int DEFAULT_FRPC_PORT = 7000;
    static final int DEFAULT_REMOTE_PORT = 18089;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ frpc

    static boolean isFrpcEnabled(Context c) {
        return sp(c).getBoolean(KEY_FRPC_ENABLED, false);
    }

    static void setFrpcEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean(KEY_FRPC_ENABLED, enabled).apply();
    }

    static String getFrpcServerAddr(Context c) {
        return safe(sp(c).getString(KEY_FRPC_ADDR, ""));
    }

    static String getFrpcToken(Context c) {
        return safe(sp(c).getString(KEY_FRPC_TOKEN, ""));
    }

    static int getFrpcServerPort(Context c) {
        return sp(c).getInt(KEY_FRPC_PORT, DEFAULT_FRPC_PORT);
    }

    static int getFrpcRemotePort(Context c) {
        return sp(c).getInt(KEY_FRPC_REMOTE_PORT, DEFAULT_REMOTE_PORT);
    }

    static void saveFrpc(Context c, String addr, int serverPort, String token, int remotePort) {
        sp(c).edit()
                .putString(KEY_FRPC_ADDR, safe(addr))
                .putInt(KEY_FRPC_PORT, serverPort)
                .putString(KEY_FRPC_TOKEN, safe(token))
                .putInt(KEY_FRPC_REMOTE_PORT, remotePort)
                .apply();
    }

    // -------------------------------------------------------- /ring 访问密钥

    /** 为空表示不做鉴权（与旧行为一致） */
    static String getRingKey(Context c) {
        return safe(sp(c).getString(KEY_RING_KEY, ""));
    }

    static void setRingKey(Context c, String key) {
        sp(c).edit().putString(KEY_RING_KEY, safe(key)).apply();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
