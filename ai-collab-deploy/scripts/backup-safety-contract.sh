#!/usr/bin/env bash
# 纯模拟故障：不连接真实 Docker、数据库或对象存储。
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
tmp_base=$(cd "${TMPDIR:-/tmp}" && pwd -P)
tmp=$(mktemp -d "$tmp_base/ai-collab-backup-test.XXXXXXXX")
[[ "$tmp" = "$tmp_base"/ai-collab-backup-test.* ]] || exit 2
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/backup"
cat > "$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  inspect) echo synthetic-image; exit 0 ;;
  exec)
    if [ "${MOCK_SCENARIO:-}" = query_failure ]; then exit 1; fi
    if [ "${MOCK_SCENARIO:-}" = monitor ]; then exit 1; fi
    case "$*" in *'count(*)'*) echo 1 ;; *) echo projects/synthetic/source.txt ;; esac ;;
  run)
    case "$*" in
      *' diff '*) [ "${MOCK_SCENARIO:-}" != diff_failure ] ;;
      *' ls --recursive '*)
        [ "${MOCK_SCENARIO:-}" != restore_list_failure ] || exit 1
        echo '{"key":"projects/synthetic/source.txt"}' ;;
      *) exit 0 ;;
    esac ;;
  logs) exit 1 ;;
  *) exit 1 ;;
esac
EOF
cat > "$tmp/alert-hook" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$1" >> "$ALERT_TEST_OUT"
EOF
chmod +x "$tmp/bin/docker" "$tmp/alert-hook"
export PATH="$tmp/bin:$PATH"
export POSTGRES_CONTAINER=synthetic-pg MINIO_CONTAINER=synthetic-minio MINIO_IMAGE=synthetic-image
export POSTGRES_DB=synthetic POSTGRES_USER=synthetic POSTGRES_PASSWORD=synthetic
export MINIO_ROOT_USER=synthetic MINIO_ROOT_PASSWORD=synthetic MINIO_BUCKET=synthetic-bucket
export MINIO_RESTORE_USER=synthetic MINIO_RESTORE_PASSWORD=synthetic
export POSTGRES_RESTORE_USER=synthetic POSTGRES_PASSWORD_RESTORE=synthetic
export VERIFY_KEY_TABLES=project_document BACKUP_ROOT="$tmp/backup"
verify=(bash "$ROOT/ai-collab-deploy/backup/verify-restore-consistency.sh" --pg-container target-pg \
  --pg-db target --minio-container target-minio --bucket synthetic-bucket)
MOCK_SCENARIO=success "${verify[@]}" >/dev/null
set +e
MOCK_SCENARIO=query_failure "${verify[@]}" >/dev/null 2>&1
status=$?
set -e
[ "$status" -eq 18 ] || { echo 'query failure was not rejected' >&2; exit 1; }
set +e
VERIFY_OBJECT_KEY_SQL='DELETE FROM project_document' "${verify[@]}" >/dev/null 2>&1
status=$?
set -e
[ "$status" -eq 3 ] || { echo 'write SQL was not rejected' >&2; exit 1; }
set +e
MOCK_SCENARIO=diff_failure bash "$ROOT/ai-collab-deploy/backup/backup-minio.sh" >/dev/null 2>&1
status=$?
set -e
[ "$status" -eq 13 ] || { echo 'silent mc diff failure was not rejected' >&2; exit 1; }
[ -z "$(find "$tmp/backup" -name 'mirror-*' -print -quit)" ] || exit 1
mkdir -p "$tmp/restore"
set +e
MOCK_SCENARIO=restore_list_failure bash "$ROOT/ai-collab-deploy/backup/restore-minio.sh" \
  --dir "$tmp/restore" --container target-minio --bucket synthetic-bucket >/dev/null 2>&1
status=$?
set -e
[ "$status" -eq 17 ] || { echo 'restore list failure was not rejected' >&2; exit 1; }
export ALERT_HOOK="$tmp/alert-hook" ALERT_TEST_OUT="$tmp/events" MONITOR_APP_CONTAINER=synthetic-app
set +e
env -u POSTGRES_PASSWORD bash "$ROOT/ai-collab-deploy/backup/scheduled-backup.sh" >/dev/null 2>&1
status=$?
set -e
[ "$status" -eq 2 ] && grep -qx backup_config_invalid "$tmp/events"
if command -v flock >/dev/null 2>&1; then
  exec 8>"$BACKUP_ROOT/.backup.lock"
  flock -n 8
  set +e
  bash "$ROOT/ai-collab-deploy/backup/scheduled-backup.sh" >/dev/null 2>&1
  status=$?
  set -e
  flock -u 8
  [ "$status" -eq 75 ] && grep -qx backup_lock_busy "$tmp/events"
  echo 'backup lock contention: OK'
fi
set +e
MOCK_SCENARIO=monitor bash "$ROOT/ai-collab-deploy/backup/monitor.sh" >/dev/null 2>&1
status=$?
set -e
[ "$status" -eq 1 ] && grep -qx app_unavailable "$tmp/events" && grep -qx app_log_check_failed "$tmp/events"
echo 'backup failure and simulated alert contract: OK'
