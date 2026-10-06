package com.example.ringserver;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 强制响铃助手。
 *
 * 关键点：不走 MediaPlayer 默认的 STREAM_MUSIC，也不用通知音；
 * 而是使用闹钟流（STREAM_ALARM / USAGE_ALARM）+ 抢音频焦点 + 音量拉满。
 * 系统闹钟与"查找手机"正是这条路径，因此静音/勿扰（闹钟除外）模式下仍会响。
 */
public final class RingHelper {

    private static final String TAG = "RingHelper";

    /** 自动停止时长：防止无限响铃，1 分钟后自动停 */
    private static final long AUTO_STOP_MS = 60_000L;

    private static MediaPlayer player;
    private static AudioManager amRef;
    private static int originalVolume = -1;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable autoStop = RingHelper::stopRinging;

    private RingHelper() {
    }

    /** 触发强制响铃（线程安全，可从 HTTP 工作线程调用）。重复触发会重新开始。 */
    public static synchronized void ring(Context context) {
        stopRinging();

        Context ctx = context.getApplicationContext();
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            Log.e(TAG, "AudioManager unavailable");
            return;
        }

        final int stream = AudioManager.STREAM_ALARM;
        final int maxVolume = am.getStreamMaxVolume(stream);

        // 1) 闹钟用途的音频属性 + 抢音频焦点，避免被其它声音打断/压低
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .build();
        am.requestAudioFocus(focusRequest);

        // 2) 把闹钟音量拉到最大，响铃结束后恢复原音量
        originalVolume = am.getStreamVolume(stream);
        am.setStreamVolume(stream, maxVolume, 0);
        amRef = am;

        // 3) 用系统闹钟铃声 + ALARM 音频属性循环播放
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        }
        if (uri == null) {
            Log.e(TAG, "no default alarm/ringtone uri");
            restoreVolume();
            return;
        }

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(ctx, uri);
            mp.setLooping(true);
            mp.setOnErrorListener((mp1, what, extra) -> {
                Log.e(TAG, "playback error: " + what + "/" + extra);
                return true;
            });
            mp.prepare();
            mp.start();
            player = mp;

            handler.removeCallbacks(autoStop);
            handler.postDelayed(autoStop, AUTO_STOP_MS);
            Log.i(TAG, "ringing (stream=ALARM, volume=" + maxVolume + ", original=" + originalVolume + ")");
        } catch (Exception e) {
            Log.e(TAG, "failed to play ringtone", e);
            restoreVolume();
        }
    }

    /** 停止响铃并恢复原音量。 */
    public static synchronized void stopRinging() {
        handler.removeCallbacks(autoStop);
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (IllegalStateException ignored) {
                // 已释放或未就绪，无需处理
            }
            player.release();
            player = null;
        }
        restoreVolume();
    }

    private static void restoreVolume() {
        if (amRef != null && originalVolume >= 0) {
            try {
                amRef.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
            } catch (Exception ignored) {
            }
        }
        amRef = null;
        originalVolume = -1;
    }
}
