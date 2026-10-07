package com.example.ringserver;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

/**
 * 轻量级 HTTP 服务器，基于 NanoHTTPD 2.3.1（fi.iki.elonen 单文件实现）。
 *
 * 端点：
 *   GET/POST /ring  -> 强制响铃（闹钟音频流，静音/勿扰下仍发声）
 *   GET/POST /stop  -> 停止响铃
 *   GET /           -> 简单说明
 *
 * 鉴权：若在设置里填了「访问密钥」，则 /ring 与 /stop 必须带 key：
 *   - query 参数：/ring?key=xxxx
 *   - 或请求头：  X-Ring-Key: xxxx
 * 密钥为空时不做校验（与旧版本行为一致）。
 */
public class RingHttpServer extends NanoHTTPD {

    private static final String TAG = "RingHttpServer";
    private static final String HEADER_KEY = "x-ring-key";

    private final Context context;

    public RingHttpServer(int port, Context context) {
        super(port);
        this.context = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String uri = session.getUri();
        Log.i(TAG, method + " " + uri);

        if ("/ring".equals(uri) && (Method.GET.equals(method) || Method.POST.equals(method))) {
            Response denied = checkKey(session);
            if (denied != null) {
                return denied;
            }
            RingHelper.ring(context);
            return text(Response.Status.OK, "RINGING\n");
        }

        if ("/stop".equals(uri) && (Method.GET.equals(method) || Method.POST.equals(method))) {
            Response denied = checkKey(session);
            if (denied != null) {
                return denied;
            }
            RingHelper.stopRinging();
            return text(Response.Status.OK, "STOPPED\n");
        }

        if ("/".equals(uri) && Method.GET.equals(method)) {
            boolean authOn = !TextUtils.isEmpty(Prefs.getRingKey(context));
            return text(Response.Status.OK,
                    "RingServer\n"
                            + "GET/POST /ring  -> 强制响铃\n"
                            + "GET/POST /stop  -> 停止响铃\n"
                            + (authOn ? "(需要带 key 参数或 X-Ring-Key 请求头)\n" : ""));
        }

        return text(Response.Status.NOT_FOUND, "Not Found\n");
    }

    /** 校验通过返回 null，否则返回 403 响应 */
    private Response checkKey(IHTTPSession session) {
        String expected = Prefs.getRingKey(context);
        if (TextUtils.isEmpty(expected)) {
            return null;
        }

        String provided = null;
        try {
            provided = session.getParms().get("key");
        } catch (Exception ignored) {
            // 某些请求体解析失败时忽略，继续尝试 header
        }
        if (TextUtils.isEmpty(provided)) {
            provided = session.getHeaders().get(HEADER_KEY);
        }
        if (!expected.equals(provided)) {
            Log.w(TAG, "rejected: bad key, uri=" + session.getUri());
            return text(Response.Status.FORBIDDEN, "FORBIDDEN\n");
        }
        return null;
    }

    private static Response text(Response.Status status, String body) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
    }
}
