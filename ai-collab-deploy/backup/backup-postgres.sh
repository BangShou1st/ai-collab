#!/usr/bin/env bash
# PostgreSQL 备份脚本：pg_dump 自定义格式 + 完整性校验 + SHA256 + 保留策略。
# 只读备份，绝不写业务数据库。用法见同目录 README.md。
#
# 退出码：0 成功 / 3 容器不可用 / 10 pg_dump 失败 / 11 完整性校验失败 / 20 校验和生成失败
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

POSTGRES_CONTAINER="$(get_env POSTGRES_CONTAINER ai-collab-postgres)"
POSTGRES_DB="$(get_env POSTGRES_DB ai_collab)"
POSTGRES_USER="$(get_env POSTGRES_USER ai_collab)"
POSTGRES_PASSWORD="$(get_env POSTGRES_PASSWORD '')"
BACKUP_ROOT="$(get_env BACKUP_ROOT "$REPO_ROOT/backups")"
RETENTION_DAYS="$(get_env BACKUP_RETENTION_DAYS 14)"

[ -n "$POSTGRES_PASSWORD" ] || die 3 "POSTGRES_PASSWORD 未设置（环境变量或 .env）"
require_container "$POSTGRES_CONTAINER" "PostgreSQL 源"

ensure_dir "$BACKUP_ROOT/postgres"
TIMESTAMP="$(date +%Y%m%dT%H%M%SZ)"
DUMP_FILE="$BACKUP_ROOT/postgres/pg_${POSTGRES_DB}_${TIMESTAMP}.dump"
STARTED_AT="$(date +%s)"

log "开始备份 PostgreSQL：容器=$POSTGRES_CONTAINER 数据库=$POSTGRES_DB 输出=$DUMP_FILE"

# pg_dump 经 stdout 流出到宿主机文件；凭据只进容器内进程环境，不落日志。
if ! docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" "$POSTGRES_CONTAINER" \
        pg_dump -h localhost -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
        > "$DUMP_FILE"; then
    rm -f "$DUMP_FILE"
    die 10 "pg_dump 执行失败，已删除不完整的输出文件"
fi

DUMP_SIZE="$(wc -c < "$DUMP_FILE" | tr -d '[:space:]')"
[ "$DUMP_SIZE" -gt 0 ] || { rm -f "$DUMP_FILE"; die 10 "pg_dump 输出为空（0 字节）"; }

# 完整性校验 1：pg_restore --list 能完整解析目录结构（可读、非截断）。
if ! docker exec -i "$POSTGRES_CONTAINER" pg_restore --list < "$DUMP_FILE" \
        > "$DUMP_FILE.manifest.txt" 2>/dev/null; then
    rm -f "$DUMP_FILE" "$DUMP_FILE.manifest.txt"
    die 11 "完整性校验失败：pg_restore --list 无法解析备份文件"
fi
TABLE_COUNT="$(grep -c 'TABLE DATA' "$DUMP_FILE.manifest.txt" || true)"

# 完整性校验 2：SHA256 供恢复前复核。
if ! (cd "$(dirname "$DUMP_FILE")" && sha256sum "$(basename "$DUMP_FILE")" > "$DUMP_FILE.sha256"); then
    die 20 "SHA256 校验和生成失败"
fi

apply_retention "$BACKUP_ROOT" "$RETENTION_DAYS"

ELAPSED=$(( $(date +%s) - STARTED_AT ))
printf 'database=%s file=%s size_bytes=%s table_data_entries=%s elapsed_seconds=%s status=OK\n' \
    "$POSTGRES_DB" "$DUMP_FILE" "$DUMP_SIZE" "$TABLE_COUNT" "$ELAPSED" | tee "$DUMP_FILE.summary"
log "PostgreSQL 备份完成（含 pg_restore --list 完整性校验与 SHA256）"
