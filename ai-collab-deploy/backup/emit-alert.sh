#!/usr/bin/env bash
# 事件只有固定类别，不携带配置、对象键或请求内容。接收方由运维通过绝对路径注入。
set -euo pipefail
event=${1:-}
[[ "$event" =~ ^[a-z][a-z0-9_]*$ ]] || exit 2
if command -v logger >/dev/null 2>&1; then logger -t ai-collab-p0 -- "$event" || true; fi
printf 'ai-collab alert: %s\n' "$event" >&2
if [ -n "${ALERT_HOOK:-}" ]; then
  [[ "$ALERT_HOOK" = /* && -x "$ALERT_HOOK" ]] || { echo 'invalid ALERT_HOOK' >&2; exit 2; }
  "$ALERT_HOOK" "$event"
fi
