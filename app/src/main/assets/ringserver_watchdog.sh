#!/system/bin/sh
# RingServer 看门狗（root 独立守护进程）
# 作用：
#   1. 应用进程被杀/被系统回收后，自动拉起前台服务
#   2. 周期性把本应用进程的 oom_score_adj 固定为 -800（低内存不优先回收）
# 由应用内 RootHelper 以 su + nohup + setsid 方式启动，不随应用进程退出。

PKG=com.example.ringserver
SVC=com.example.ringserver/.RingServerService
PIDFILE=/data/local/tmp/ringserver_watchdog.pid
LOGFILE=/data/local/tmp/ringserver_watchdog.log
OOM_ADJ=-800
backoff=0

echo $$ > "$PIDFILE"
log() { echo "[$(date '+%F %T')] $*" >> "$LOGFILE"; }

# 日志上限 128KB，超出清空重记
[ "$(wc -c < "$LOGFILE" 2>/dev/null)" -gt 131072 ] && : > "$LOGFILE"
log "watchdog started pid $$"

while :; do
  if ! pidof "$PKG" >/dev/null 2>&1; then
    # 进程不在 -> 拉起服务
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
    sleep 3
    if pidof "$PKG" >/dev/null 2>&1; then
      backoff=0
      log "service started"
    else
      backoff=$((backoff + 1))
      if [ "$backoff" -ge 6 ]; then
        # 连续失败（如被强行停止，stopped 状态起不来），降频重试
        log "app cannot start (force-stopped?), slow retry every 30s"
        sleep 30
        continue
      fi
    fi
  else
    backoff=0
  fi

  # OOM 保护：系统会周期性重置该值，这里持续重写
  for p in $(pidof "$PKG" 2>/dev/null); do
    echo "$OOM_ADJ" > "/proc/$p/oom_score_adj" 2>/dev/null
  done

  sleep 5
done
