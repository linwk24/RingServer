package com.example.ringserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启（系统广播路径）。
 * 开机广播到达后：若用户开了「开机自启」则启动前台服务；
 * Root 模式开着时同时确保看门狗在跑（看门狗会兜底拉起服务）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "BOOT_COMPLETED received");

        if (RootHelper.isBootStartEnabled(context)) {
            try {
                context.startForegroundService(new Intent(context, RingServerService.class));
                Log.i(TAG, "RingServerService started from boot");
            } catch (Exception e) {
                // 部分 ROM 会拦开机广播启动前台服务，Root 看门狗会兜底
                Log.e(TAG, "startForegroundService from boot failed (ROM restriction?)", e);
            }
        }

        RootHelper.ensureWatchdog(context);
    }
}
