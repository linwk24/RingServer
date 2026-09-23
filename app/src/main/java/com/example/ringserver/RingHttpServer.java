package com.example.ringserver;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

/**
 * 轻量级 HTTP 服务器，基于 NanoHTTPD 2.3.1（fi.iki.elonen 单文件实现）。
 *
 * 端点：
 *   GET/POST /ring  -> 强制响铃（闹钟音频流，静音/勿扰下仍发声）
 *   GET/POST /stop  -> 停止响铃
 *   GET /           -> 简单说明
 */
public class RingHttpServer extends NanoHTTPD {

    private static final String TAG = "RingHttpServer";
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
            RingHelper.ring(context);
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "RINGING\n");
        }

        if ("/stop".equals(uri) && (Method.GET.equals(method) || Method.POST.equals(method))) {
            RingHelper.stopRinging();
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "STOPPED\n");
        }

        if ("/".equals(uri) && Method.GET.equals(method)) {
            return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8",
                    "RingServer\n"
                            + "GET/POST /ring  -> 强制响铃\n"
                            + "GET/POST /stop  -> 停止响铃\n");
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not Found\n");
    }
}
