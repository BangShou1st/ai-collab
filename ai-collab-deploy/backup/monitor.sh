#!/usr/bin/env bash
# 定时健康、磁盘和持续错误检查。只发类别，不输出或保存应用日志。
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
: "${MONITOR_APP_CONTAINER:?必须显式指定应用容器}"
: "${BACKUP_ROOT:?必须显式指定备份目录}"
threshold=${DISK_ALERT_PERCENT:-90}
error_limit=${ERROR_ALERT_COUNT:-10}
[[ "$threshold" =~ ^[0-9]+$ && "$error_limit" =~ ^[0-9]+$ ]] &&
  [ "$threshold" -ge 1 ] && [ "$threshold" -le 99 ] && [ "$error_limit" -ge 1 ] || exit 2
status=0
alert() { bash "$SCRIPT_DIR/emit-alert.sh" "$1" || true; status=1; }
if ! docker exec "$MONITOR_APP_CONTAINER" wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
  alert app_unavailable
fi
if usage=$(df -Pk "$BACKUP_ROOT" | awk 'NR==2 {gsub(/%/, "", $5); print $5}'); then
  if [[ ! "$usage" =~ ^[0-9]+$ ]]; then alert disk_check_failed
  elif [ "$usage" -ge "$threshold" ]; then alert disk_space_low; fi
else
  alert disk_check_failed
fi
if count=$(docker logs --since 5m "$MONITOR_APP_CONTAINER" 2>&1 | awk '/ ERROR / {n++} END {print n+0}'); then
  [ "$count" -lt "$error_limit" ] || alert app_errors_sustained
else
  alert app_log_check_failed
fi
exit "$status"
