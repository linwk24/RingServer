package com.example.ringserver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private TextView statusText;
    private TextView urlText;
    private TextView rootStatusText;
    private Button startButton;
    private Button stopButton;
    private Switch rootSwitch;
    private Switch bootSwitch;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        urlText = findViewById(R.id.urlText);
        rootStatusText = findViewById(R.id.rootStatusText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        rootSwitch = findViewById(R.id.rootSwitch);
        bootSwitch = findViewById(R.id.bootSwitch);
        Button testButton = findViewById(R.id.testButton);

        maybeRequestNotificationPermission();

        // -------- Root 模式开关 --------
        rootSwitch.setChecked(RootHelper.isRootModeEnabled(this));
        rootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!RootHelper.isRootAvailable()) {
                    Toast.makeText(this, "未检测到 root，Root 模式需要已 root 的设备", Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(false);
                    return;
                }
                RootHelper.setRootMode(this, true);
                RootHelper.applySystemProtections();   // Doze/待机/后台白名单
                RootHelper.startWatchdog(this);        // 看门狗防杀
                Toast.makeText(this, "Root 模式已启用（系统白名单 + 看门狗）", Toast.LENGTH_SHORT).show();
            } else {
                RootHelper.setRootMode(this, false);
                RootHelper.stopWatchdog();
                Toast.makeText(this, "Root 模式已关闭", Toast.LENGTH_SHORT).show();
            }
            refreshRootStatus();
        });

        // -------- 开机自启开关 --------
        bootSwitch.setChecked(RootHelper.isBootStartEnabled(this));
        bootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            RootHelper.setBootStart(this, isChecked);
            if (isChecked) {
                boolean magiskScript = RootHelper.installBootScript(this);
                Toast.makeText(this, magiskScript
                        ? "开机自启已开启（Magisk 开机脚本 + 系统广播）"
                        : "开机自启已开启（无 Magisk，仅系统开机广播）", Toast.LENGTH_SHORT).show();
            } else {
                RootHelper.removeBootScript();
                Toast.makeText(this, "开机自启已关闭", Toast.LENGTH_SHORT).show();
            }
            refreshRootStatus();
        });

        startButton.setOnClickListener(v -> {
            startForegroundService(new Intent(this, RingServerService.class));
            Toast.makeText(this, "服务启动中…", Toast.LENGTH_SHORT).show();
            // 服务在 onCreate 中异步绑定端口，稍后刷新状态
            handler.postDelayed(this::refreshUi, 600);
        });

        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, RingServerService.class));
            RingHelper.stopRinging();
            Toast.makeText(this, "服务已停止（Root 模式开着时看门狗会自动拉起）", Toast.LENGTH_SHORT).show();
            refreshUi();
        });

        testButton.setOnClickListener(v -> {
            RingHelper.ring(this);
            Toast.makeText(this, "本地测试：已在响铃（1 分钟后自动停）", Toast.LENGTH_SHORT).show();
        });

        refreshRootStatus();

        // 打开应用即自动启动服务（无需手动点击「启动服务」）
        autoStartService();
    }

    /** 应用进入前台时若服务未运行则自动拉起 */
    private void autoStartService() {
        if (RingServerService.isRunning()) {
            refreshUi();
            return;
        }
        try {
            startForegroundService(new Intent(this, RingServerService.class));
            // 服务在 onCreate 中异步绑定端口，稍后刷新状态
            handler.postDelayed(this::refreshUi, 600);
        } catch (Exception e) {
            Toast.makeText(this, "服务自动启动失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            refreshUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        refreshRootStatus();
    }

    /** Android 13+ 需要通知权限，否则前台服务通知不显示 */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private void refreshUi() {
        boolean running = RingServerService.isRunning();
        statusText.setText(running ? "● 服务运行中" : "○ 服务未运行");
        statusText.setTextColor(running ? 0xFF2E7D32 : 0xFF9E9E9E);
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);

        if (running) {
            String ip = getLocalIpV4();
            if (TextUtils.isEmpty(ip)) {
                urlText.setText("未获取到局域网 IP（请连接 Wi-Fi/热点）");
            } else {
                urlText.setText("触发地址：http://" + ip + ":" + RingServerService.PORT + "/ring\n"
                        + "手机本地：http://127.0.0.1:" + RingServerService.PORT + "/ring");
            }
        } else {
            urlText.setText("启动服务后显示触发地址");
        }
    }

    /** 后台线程刷新 root/看门狗/开机脚本状态（su 探测可能耗时几百毫秒） */
    private void refreshRootStatus() {
        new Thread(() -> {
            boolean root = RootHelper.isRootAvailable();
            boolean watchdog = RootHelper.isWatchdogRunning();
            boolean bootScript = RootHelper.isBootScriptInstalled();
            boolean magisk = RootHelper.isMagiskPresent();
            handler.post(() -> {
                String s = "Root：" + (root ? "可用" : "未检测到")
                        + "  |  看门狗：" + (watchdog ? "运行中" : "未运行")
                        + "  |  Magisk 开机脚本：" + (bootScript ? "已安装" : (magisk ? "未安装" : "无 Magisk"));
                rootStatusText.setText(s);
            });
        }).start();
    }

    private String getLocalIpV4() {
        try {
            List<NetworkInterface> nis = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : nis) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
