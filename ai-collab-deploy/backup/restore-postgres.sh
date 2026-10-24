#!/usr/bin/env bash
# PostgreSQL 恢复脚本：把 pg_dump 自定义格式备份恢复到【指定的目标容器】。
# 目标必须显式指定（推荐临时/演练容器）；默认拒绝恢复到当前业务源容器。
# 用法见同目录 README.md。
#
# 退出码：0 成功 / 3 容器或参数不可用 / 14 恢复目标被安全守卫拒绝 / 15 恢复失败 / 16 恢复后自检失败
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

DUMP_FILE=""
TARGET_CONTAINER=""
TARGET_DB=""
ALLOW_SOURCE_OVERWRITE="${ALLOW_SOURCE_OVERWRITE:-0}"

usage() {
    cat >&2 <<'EOF'
用法: restore-postgres.sh --file <备份.dump> --container <目标PostgreSQL容器> --db <目标数据库名>
环境变量:
  POSTGRES_RESTORE_USER / POSTGRES_RESTORE_PASSWORD   目标容器内建立的角色（默认取源配置 POSTGRES_USER）
  ALLOW_SOURCE_OVERWRITE=1                            显式解除"禁止恢复进业务源容器"守卫（强烈不建议）
EOF
    exit 3
}

while [ $# -gt 0 ]; do
    case "$1" in
        --file) DUMP_FILE="$2"; shift 2 ;;
        --container) TARGET_CONTAINER="$2"; shift 2 ;;
        --db) TARGET_DB="$2"; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$DUMP_FILE" ] && [ -n "$TARGET_CONTAINER" ] && [ -n "$TARGET_DB" ] || usage
[ -f "$DUMP_FILE" ] || die 3 "备份文件不存在: $DUMP_FILE"

POSTGRES_USER_RESTORE="$(get_env POSTGRES_RESTORE_USER "$(get_env POSTGRES_USER ai_collab)")"
POSTGRES_PASSWORD_RESTORE="$(get_env POSTGRES_PASSWORD_RESTORE "$(get_env POSTGRES_PASSWORD '')")"
[ -n "$POSTGRES_PASSWORD_RESTORE" ] || die 3 "目标容器口令未设置（POSTGRES_RESTORE_PASSWORD 或 POSTGRES_PASSWORD）"

# 安全守卫：源业务容器默认禁止作为恢复目标，防止误覆盖线上数据。
SOURCE_CONTAINER="$(get_env POSTGRES_CONTAINER ai-collab-postgres)"
if [ "$TARGET_CONTAINER" = "$SOURCE_CONTAINER" ] && [ "$ALLOW_SOURCE_OVERWRITE" != "1" ]; then
    die 14 "拒绝恢复：目标容器 $TARGET_CONTAINER 是当前业务容器。请使用隔离的临时容器；确要覆盖请设 ALLOW_SOURCE_OVERWRITE=1"
fi
require_container "$TARGET_CONTAINER" "恢复目标"

# 恢复前 SHA256 复核（备份时生成）。
if [ -f "$DUMP_FILE.sha256" ]; then
    (cd "$(dirname "$DUMP_FILE")" && sha256sum -c "$(basename "$DUMP_FILE").sha256" --status) \
        || die 16 "备份文件 SHA256 与记录不一致，文件可能损坏或被篡改，中止恢复"
else
    warn "未找到 $DUMP_FILE.sha256，跳过恢复前校验和复核"
fi

log "开始恢复：dump=$(basename "$DUMP_FILE") 目标容器=$TARGET_CONTAINER 目标库=$TARGET_DB"
# 校验 dump 可解析后再建库恢复；目标库必须不存在，避免覆盖既有数据。
docker exec -i "$TARGET_CONTAINER" pg_restore --list < "$DUMP_FILE" > /dev/null 2>&1 \
    || die 16 "备份文件无法被 pg_restore 解析"
if docker exec -e PGPASSWORD="$POSTGRES_PASSWORD_RESTORE" "$TARGET_CONTAINER" \
        psql -h localhost -U "$POSTGRES_USER_RESTORE" -d postgres -Atc \
        "SELECT 1 FROM pg_database WHERE datname='$TARGET_DB'" | grep -q 1; then
    die 14 "目标数据库 $TARGET_DB 已存在，拒绝覆盖。请换库名或先删除隔离容器"
fi

docker exec -e PGPASSWORD="$POSTGRES_PASSWORD_RESTORE" "$TARGET_CONTAINER" \
    createdb -h localhost -U "$POSTGRES_USER_RESTORE" "$TARGET_DB" \
    || die 15 "createdb 失败"
if ! docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD_RESTORE" "$TARGET_CONTAINER" \
        pg_restore -h localhost -U "$POSTGRES_USER_RESTORE" -d "$TARGET_DB" --no-owner --no-privileges \
        < "$DUMP_FILE" > /dev/null 2>&1; then
    die 15 "pg_restore 执行失败（部分对象可能未恢复，目标库仅用于演练请直接删除容器重试）"
fi

# 恢复后自检：public schema 下必须存在业务表。
RESTORED_TABLES="$(docker exec -e PGPASSWORD="$POSTGRES_PASSWORD_RESTORE" "$TARGET_CONTAINER" \
    psql -h localhost -U "$POSTGRES_USER_RESTORE" -d "$TARGET_DB" -Atc \
    "SELECT count(*) FROM pg_tables WHERE schemaname='public'")"
[ "$RESTORED_TABLES" -gt 0 ] || die 16 "恢复后自检失败：public schema 无任何表"
log "恢复完成：public schema 共 $RESTORED_TABLES 张表"
