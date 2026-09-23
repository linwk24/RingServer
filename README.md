# RingServer — 局域网 HTTP 触发「强制响铃」APK

一个极简 Android 应用：内置 NanoHTTPD 轻量 HTTP 服务器，监听 **8080** 端口；
收到 `GET/POST /ring` 请求后，用**闹钟音频流（STREAM_ALARM + USAGE_ALARM）**
强制播放系统闹钟铃声 —— **静音 / 勿扰（闹钟除外）模式下也会响**，
与系统闹钟、「查找手机」是同一机制。

典型用途：找不到手机时从电脑 / 另一台设备触发响铃。

---

## 功能

| 端点 | 方法 | 作用 |
|---|---|---|
| `/ring` | GET / POST | 强制响铃（循环播放，2 分钟后自动停止） |
| `/stop` | GET / POST | 立即停止响铃并恢复原音量 |
| `/` | GET | 服务说明 |

应用内界面：启动 / 停止服务、显示局域网触发地址、本地测试响铃，
以及 **Root 模式**（防杀后台）和**开机自启**两个开关。

## 环境要求

- JDK 17（Android Studio 自带 JBR，或单独安装）
- Android SDK：**Platform 34** + Build-Tools（Android Studio 的 SDK Manager 装一下即可）
- Gradle：无需手动安装，工程已内置 wrapper（Gradle 8.7）

## 构建

### 方式一：Android Studio（推荐）

1. `File → Open`，选择本目录；
2. 等待 Gradle Sync 完成（首次会自动下载 Gradle 8.7 与依赖）；
3. `Build → Build APK(s)`，产物在 `app\build\outputs\apk\debug\app-debug.apk`。

### 方式二：命令行

```bat
:: 配置 SDK 位置（二选一）
set ANDROID_HOME=C:\path\to\Android\Sdk
:: 或在工程根目录新建 local.properties：sdk.dir=C\:\\path\\to\\Android\\Sdk

gradlew.bat assembleDebug
:: 产物：app\build\outputs\apk\debug\app-debug.apk
```

## 安装与使用

1. 安装 APK，打开应用，点「**启动服务**」（Android 13+ 会请求通知权限，请允许）；
2. 记下界面显示的地址，形如 `http://192.168.x.x:8080/ring`；
3. 在**同一局域网**的另一台设备上触发：
   - 浏览器直接打开该地址，或
   - `curl http://192.168.x.x:8080/ring`；
4. 手机本地也可自测：`http://127.0.0.1:8080/ring`；
5. 响铃循环播放，**2 分钟自动停止**；想提前停就访问 `/stop`，
   或在应用里点「停止服务」。

## 强制响铃的原理（关键实现）

普通的 `MediaPlayer` 默认走 `STREAM_MUSIC`（媒体流），通知声音走
`STREAM_NOTIFICATION` —— 静音 / 勿扰时会被系统压掉。本应用反其道而行：

1. 使用 `AudioManager.STREAM_ALARM` 音频流；
2. 播放时给 `MediaPlayer` 设置 `AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)`；
3. 响铃前抢音频焦点 `AUDIOFOCUS_GAIN`，并把闹钟音量拉到最大（结束后恢复）；
4. 铃声源取系统闹钟铃声 `RingtoneManager.TYPE_ALARM`。

对应代码集中在 `app\src\main\java\com\example\ringserver\RingHelper.java`。

服务器跑在**前台服务**（`RingServerService`）里，否则 Android 8+ 在应用退到
后台后会把进程杀掉、HTTP 端口随之失效。

## Root 模式（防杀后台，需已 root）

应用内「Root 模式」开关，用 `su` 做系统级保活，不依赖任何第三方框架：

| 能力 | 实现 |
|---|---|
| 系统白名单 | `dumpsys deviceidle whitelist`、`am set-standby-bucket active`、`cmd appops set ... RUN_IN_BACKGROUND/RUN_ANY_IN_BACKGROUND allow` 等 |
| OOM 保护 | 看门狗周期把本应用进程的 `oom_score_adj` 写为 -800，低内存不优先回收 |
| 看门狗防杀 | root 独立守护脚本常驻：应用进程被系统杀掉后自动 `am start-foreground-service` 拉起（约 5 秒内恢复） |

### 启用步骤

1. 打开应用 → 打开「**Root 模式**」开关（首次会弹 su 授权，请允许）；
2. 状态栏会显示：Root 可用 / 看门狗运行中；
3. 想彻底停用：关掉开关即可（会停掉看门狗）。

脚本位置与日志（排查用）：
- 看门狗脚本：`/data/local/tmp/ringserver_watchdog.sh`
- 看门狗日志：`/data/local/tmp/ringserver_watchdog.log`

### 边界

- 对**系统自动杀进程**有效；用户手动「强行停止」会把应用置于 stopped 状态，
  此时拉起会失败（看门狗会自动降频重试，你手动打开一次应用即恢复）；
- `oom_score_adj` 会被系统周期性重置，看门狗每 5 秒重写一次，开销极小；
- 建议同时在系统设置里允许本应用自启动、关闭省电限制，双保险。

## 开机自启（可配置）

应用内「开机自启」开关，双通道：

| 通道 | 说明 |
|---|---|
| 系统广播 | 清单已注册 `BootReceiver`（`BOOT_COMPLETED`），ROM 允许时开机自动启动服务 |
| Magisk 脚本（更稳） | 检测到 Magisk（`/data/adb/service.d`）时，自动安装开机脚本，开机后以 root 拉起服务与看门狗 |

启用时若显示「无 Magisk，仅系统开机广播」，说明设备没装 Magisk 或不是 Magisk root，
此时主要靠系统广播 + Root 模式看门狗兜底。

脚本位置：`/data/adb/service.d/ringserver_boot.sh`（关闭开关时自动删除）。

## 已知限制（重要）

- **勿扰「完全静音」**模式会把闹钟一起静音（系统行为），除非把本应用加入
  勿扰的「闹钟/应用优先级」名单；
- 部分国产 ROM（MIUI / ColorOS / EMUI 等）会杀后台：请在系统设置中允许
  自启动、关闭省电限制；触发后通知栏应能看到常驻通知；
- 服务器监听 `0.0.0.0`，**同一局域网内任何设备都能触发**，请只在可信
  网络（家庭 / 办公 Wi-Fi）中使用；如需限制，可在路由器或手机防火墙层面处理。

## 项目结构

```
RingServer/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/     # Gradle 8.7 wrapper
└── app/
    ├── build.gradle                            # AGP 8.5.2，compileSdk 34，minSdk 26
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/ringserver/
        │   ├── MainActivity.java               # 简单 UI：启停服务 + 地址 + Root/开机自启开关
        │   ├── RingServerService.java          # 前台服务，持有 HTTP 服务器
        │   ├── RingHttpServer.java             # NanoHTTPD 子类，处理 /ring /stop
        │   ├── RingHelper.java                 # 强制响铃核心（STREAM_ALARM）
        │   ├── RootHelper.java                 # Root 模式：su 白名单 + OOM + 看门狗 + 开机脚本
        │   └── BootReceiver.java               # 开机自启（BOOT_COMPLETED 广播）
        ├── assets/
        │   ├── ringserver_watchdog.sh          # 看门狗守护脚本（root 运行）
        │   └── ringserver_boot.sh              # Magisk 开机自启脚本
        └── res/
            ├── layout/activity_main.xml
            ├── values/ (strings / colors)
            ├── drawable/ (铃铛图标)
            └── mipmap-anydpi-v26/ (自适应应用图标)
```

## 想改的东西

- **改端口**：改 `RingServerService.PORT` 一处即可；
- **改触发路径**：改 `RingHttpServer.serve()` 里的 URI 判断；
- **改响铃时长**：改 `RingHelper.AUTO_STOP_MS`。
