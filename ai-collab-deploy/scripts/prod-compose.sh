#!/usr/bin/env bash
# 只提供只读检查与启动入口；不提供 down、卷删除或构建。
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
if [ "$#" -ne 2 ]; then
  echo "用法: bash prod-compose.sh <环境文件> <config|up|ps>" >&2
  exit 2
fi
ENV_FILE=$1
ACTION=$2
case "$ACTION" in config|up|ps) ;; *) echo "不支持的操作" >&2; exit 2 ;; esac
node "$SCRIPT_DIR/prod-config-check.mjs" "$ENV_FILE"
case "$ACTION" in
  config) docker compose --env-file "$ENV_FILE" -f "$ROOT/ai-collab-deploy/compose.prod.yml" config -q ;;
  up) docker compose --env-file "$ENV_FILE" -f "$ROOT/ai-collab-deploy/compose.prod.yml" up -d --no-build ;;
  ps) docker compose --env-file "$ENV_FILE" -f "$ROOT/ai-collab-deploy/compose.prod.yml" ps ;;
esac
