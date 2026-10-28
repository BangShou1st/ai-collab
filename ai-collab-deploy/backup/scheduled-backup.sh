#!/usr/bin/env bash
# Linux 定时入口：每次独立快照，互斥，失败报警，可选异地复制适配器。
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
for name in BACKUP_ROOT POSTGRES_CONTAINER POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD \
            MINIO_CONTAINER MINIO_ROOT_USER MINIO_ROOT_PASSWORD MINIO_BUCKET; do
  if [ -z "${!name:-}" ]; then
    bash "$SCRIPT_DIR/emit-alert.sh" backup_config_invalid || true
    echo "缺少必需配置: $name" >&2
    exit 2
  fi
done
retention=${BACKUP_RETENTION_DAYS:-14}
[[ "$retention" =~ ^[0-9]+$ ]] && [ "$retention" -ge 1 ] || { echo 'invalid retention' >&2; exit 2; }
[[ "$BACKUP_ROOT" = /* && -d "$BACKUP_ROOT" && ! -L "$BACKUP_ROOT" ]] || { echo 'invalid backup root' >&2; exit 2; }
base=$(cd "$BACKUP_ROOT" && pwd -P)
[[ "$base" != / ]] || exit 2
umask 077
mkdir -p "$base/snapshots"
[ ! -L "$base/snapshots" ] && [ "$(cd "$base/snapshots" && pwd -P)" = "$base/snapshots" ] || exit 2
exec 9>"$base/.backup.lock"
if ! flock -n 9; then bash "$SCRIPT_DIR/emit-alert.sh" backup_lock_busy || true; exit 75; fi
snapshot=$(mktemp -d "$base/snapshots/incomplete-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
[[ "$(cd "$snapshot" && pwd -P)" = "$base"/snapshots/incomplete-* ]] || exit 2
failed() { bash "$SCRIPT_DIR/emit-alert.sh" backup_failed || true; }
trap failed ERR
BACKUP_ROOT="$snapshot" BACKUP_RETENTION_DAYS=0 bash "$SCRIPT_DIR/backup-postgres.sh"
BACKUP_ROOT="$snapshot" BACKUP_RETENTION_DAYS=0 bash "$SCRIPT_DIR/backup-minio.sh"
touch "$snapshot/.complete"
name=${snapshot##*/}
complete="$base/snapshots/snapshot-${name#incomplete-}"
[ ! -e "$complete" ] && [ ! -L "$complete" ] || { echo 'snapshot destination already exists' >&2; exit 1; }
mv -- "$snapshot" "$complete"
if [ -z "${BACKUP_COPY_HOOK:-}" ]; then
  bash "$SCRIPT_DIR/emit-alert.sh" backup_copy_unconfigured || true
elif [[ "$BACKUP_COPY_HOOK" != /* || ! -x "$BACKUP_COPY_HOOK" ]]; then
  false
elif ! "$BACKUP_COPY_HOOK" "$complete"; then
  bash "$SCRIPT_DIR/emit-alert.sh" backup_copy_failed || true
  exit 1
fi
# 只清理本目录下带完成标记的旧快照；未完成的快照留待人工诊断。
while IFS= read -r -d '' old; do
  [ -f "$old/.complete" ] || continue
  [[ "$(cd "$old" && pwd -P)" = "$base"/snapshots/snapshot-* ]] || exit 2
  rm -rf -- "$old"
done < <(find "$base/snapshots" -mindepth 1 -maxdepth 1 -type d -name 'snapshot-*' -mtime +"$retention" -print0)
trap - ERR
echo 'scheduled backup completed; remote copy requires independent verification'
