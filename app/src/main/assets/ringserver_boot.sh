#!/system/bin/sh
# RingServer 开机自启（Magisk service.d 脚本，开机时以 root 运行）
# 安装位置：/data/adb/service.d/ringserver_boot.sh
# 等待系统启动完成后拉起前台服务；若看门狗脚本存在则一并拉起。

PKG=com.example.ringserver
SVC=com.example.ringserver/.RingServerService
WATCHDOG=/data/local/tmp/ringserver_watchdog.sh

# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done
sleep 5

# 拉起服务（root 上下文，不受后台启动限制）
am start-foreground-service -n "$SVC" >/dev/null 2>&1

# 看门狗存在则启动（Root 模式开启时才有）
if [ -f "$WATCHDOG" ]; then
  setsid sh "$WATCHDOG" >/dev/null 2>&1 &
fi

exit 0
