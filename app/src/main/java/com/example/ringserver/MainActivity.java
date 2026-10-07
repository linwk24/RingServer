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
import android.widget.EditText;
import android.widget.ScrollView;
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

    // 公网穿透（frpc）
    private Switch frpcSwitch;
    private EditText frpcAddrEdit;
    private EditText frpcPortEdit;
    private EditText frpcTokenEdit;
    private EditText frpcRemotePortEdit;
    private Button frpcApplyButton;
    private TextView frpcStatusText;
    private ScrollView frpcLogScroll;
    private TextView frpcLogText;
    private EditText ringKeyEdit;

    /** 初始化回填控件时置位，避免触发监听器 */
    private boolean loadingConfig;
    private String lastLog = "";

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 每秒刷新 frpc 状态与日志 */
    private final Runnable frpcTicker = new Runnable() {
        @Override
        public void run() {
            refreshFrpcStatus();
            handler.postDelayed(this, 1000);
        }
    };

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

        frpcSwitch = findViewById(R.id.frpcSwitch);
        frpcAddrEdit = findViewById(R.id.frpcAddrEdit);
        frpcPortEdit = findViewById(R.id.frpcPortEdit);
        frpcTokenEdit = findViewById(R.id.frpcTokenEdit);
        frpcRemotePortEdit = findViewById(R.id.frpcRemotePortEdit);
        frpcApplyButton = findViewById(R.id.frpcApplyButton);
        frpcStatusText = findViewById(R.id.frpcStatusText);
        frpcLogScroll = findViewById(R.id.frpcLogScroll);
        frpcLogText = findViewById(R.id.frpcLogText);
        ringKeyEdit = findViewById(R.id.ringKeyEdit);

        maybeRequestNotificationPermission();

        // -------- Root 模式开关 --------
        rootSwitch.setChecked(RootHelper.isRootModeEnabled(this));
        rootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingConfig) {
                return;
            }
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
            if (loadingConfig) {
                return;
            }
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

        // -------- 公网穿透（frpc） --------
        loadFrpcConfig();

        frpcSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingConfig) {
                return;
            }
            persistFrpcFields();
            Prefs.setFrpcEnabled(this, isChecked);
            applyFrpcConfig(isChecked ? "公网穿透已开启，正在连接…" : "公网穿透已关闭");
        });

        frpcApplyButton.setOnClickListener(v -> {
            if (!persistFrpcFieldsChecked()) {
                return;
            }
            Prefs.setRingKey(this, text(ringKeyEdit));
            applyFrpcConfig("配置已保存并应用");
        });

        startButton.setOnClickListener(v -> {
            startForegroundService(new Intent(this, RingServerService.class));
            Toast.makeText(this, "服务启动中…", Toast.LENGTH_SHORT).show();
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
        refreshFrpcStatus();

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
        handler.removeCallbacks(frpcTicker);
        handler.post(frpcTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(frpcTicker);
    }

    // ------------------------------------------------------------ frpc 配置

    private void loadFrpcConfig() {
        loadingConfig = true;
        try {
            frpcSwitch.setChecked(Prefs.isFrpcEnabled(this));
            frpcAddrEdit.setText(Prefs.getFrpcServerAddr(this));
            frpcPortEdit.setText(String.valueOf(Prefs.getFrpcServerPort(this)));
            frpcTokenEdit.setText(Prefs.getFrpcToken(this));
            frpcRemotePortEdit.setText(String.valueOf(Prefs.getFrpcRemotePort(this)));
            ringKeyEdit.setText(Prefs.getRingKey(this));
        } finally {
            loadingConfig = false;
        }
    }

    /** 保存输入框内容（不校验，非法端口回落到默认值） */
    private void persistFrpcFields() {
        Prefs.saveFrpc(this,
                text(frpcAddrEdit),
                parseInt(text(frpcPortEdit), Prefs.DEFAULT_FRPC_PORT),
                text(frpcTokenEdit),
                parseInt(text(frpcRemotePortEdit), Prefs.DEFAULT_REMOTE_PORT));
    }

    /** 保存输入框内容（带校验），校验失败返回 false */
    private boolean persistFrpcFieldsChecked() {
        int serverPort = parseInt(text(frpcPortEdit), -1);
        int remotePort = parseInt(text(frpcRemotePortEdit), -1);
        if (serverPort < 1 || serverPort > 65535 || remotePort < 1 || remotePort > 65535) {
            Toast.makeText(this, "端口必须是 1-65535 的数字", Toast.LENGTH_SHORT).show();
            return false;
        }
        Prefs.saveFrpc(this, text(frpcAddrEdit), serverPort, text(frpcTokenEdit), remotePort);
        return true;
    }

    private void applyFrpcConfig(String toast) {
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        try {
            Intent i = new Intent(this, RingServerService.class);
            i.setAction(RingServerService.ACTION_RELOAD_FRPC);
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "应用失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(this::refreshFrpcStatus, 800);
    }

    private void refreshFrpcStatus() {
        boolean enabled = Prefs.isFrpcEnabled(this);
        boolean running = FrpcManager.isRunning();
        String err = FrpcManager.getLastError();

        String s;
        if (!enabled) {
            s = "frpc：未启用";
        } else if (running) {
            s = "frpc：● 运行中";
        } else {
            s = "frpc：○ 未运行";
        }
        if (!TextUtils.isEmpty(err)) {
            s += "　—　" + err;
        }
        frpcStatusText.setText(s);

        String log = FrpcManager.tailLog(40);
        if (!log.equals(lastLog)) {
            lastLog = log;
            frpcLogText.setText(log.isEmpty() ? "（暂无日志输出）" : log);
            // 自动滚到底部（若用户已上滑查看历史，此时会被拉回，属预期取舍）
            frpcLogScroll.post(() -> {
                if (frpcLogScroll.getChildCount() > 0) {
                    frpcLogScroll.scrollTo(0, frpcLogScroll.getChildAt(0).getHeight());
                }
            });
        }
    }

    // ------------------------------------------------------------------ 状态

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
            StringBuilder sb = new StringBuilder();
            if (TextUtils.isEmpty(ip)) {
                sb.append("未获取到局域网 IP（请连接 Wi-Fi/热点）");
            } else {
                sb.append("触发地址：http://").append(ip).append(":").append(RingServerService.PORT)
                        .append("/ring").append(keySuffix());
                sb.append("\n手机本地：http://127.0.0.1:").append(RingServerService.PORT)
                        .append("/ring").append(keySuffix());
            }
            if (Prefs.isFrpcEnabled(this)) {
                String addr = Prefs.getFrpcServerAddr(this);
                if (!TextUtils.isEmpty(addr)) {
                    sb.append("\n公网地址：http://").append(addr).append(":")
                            .append(Prefs.getFrpcRemotePort(this)).append("/ring").append(keySuffix());
                }
            }
            urlText.setText(sb.toString());
        } else {
            urlText.setText("启动服务后显示触发地址");
        }
    }

    /** 设置了访问密钥时，显示在地址后面方便直接复制 */
    private String keySuffix() {
        String key = Prefs.getRingKey(this);
        return TextUtils.isEmpty(key) ? "" : "?key=" + key;
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

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
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
