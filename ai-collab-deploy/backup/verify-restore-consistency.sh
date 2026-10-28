#!/usr/bin/env bash
# 恢复一致性核验脚本：对比【业务源】与【恢复目标】的关键表行数，
# 并核验恢复库中 project_document.object_key 与恢复 bucket 中对象文件的对应关系。
# 只读脚本，不写任何数据库或 bucket。用法见同目录 README.md。
#
# 退出码：0 一致 / 3 参数或容器不可用 / 18 数据不一致 / 19 对象引用不一致
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

SOURCE_PG_CONTAINER="$(get_env POSTGRES_CONTAINER ai-collab-postgres)"
SOURCE_DB="$(get_env POSTGRES_DB ai_collab)"
RESTORE_PG_CONTAINER=""
RESTORE_DB=""

SOURCE_MINIO_CONTAINER="$(get_env MINIO_CONTAINER ai-collab-minio)"
SOURCE_BUCKET="$(get_env MINIO_BUCKET ai-collab)"
RESTORE_MINIO_CONTAINER=""
RESTORE_BUCKET=""
MINIO_RESTORE_USER="$(get_env MINIO_RESTORE_USER "$(get_env MINIO_ROOT_USER '')")"
MINIO_RESTORE_PASSWORD="$(get_env MINIO_RESTORE_PASSWORD "$(get_env MINIO_ROOT_PASSWORD '')")"

# 参与行数核验的关键业务表（恢复价值最高、且能代表数据库↔对象存储关联链路）。
KEY_TABLES="$(get_env VERIFY_KEY_TABLES "app_user project project_member project_task milestone task_comment project_document document_chunk ai_task_plan audit_log")"
# 恢复库中持有对象引用的表.列（默认 documents 的 object_key）。
OBJECT_KEY_SQL="$(get_env VERIFY_OBJECT_KEY_SQL "SELECT object_key FROM project_document")"

usage() {
    cat >&2 <<'EOF'
用法: verify-restore-consistency.sh --pg-container <恢复库容器> --pg-db <恢复库名> \
        --minio-container <恢复bucket所在容器> --bucket <恢复bucket>
环境变量:
  VERIFY_KEY_TABLES      空格分隔的关键表清单（默认覆盖核心业务表）
  VERIFY_OBJECT_KEY_SQL  从恢复库提取对象引用键的 SQL
EOF
    exit 3
}

while [ $# -gt 0 ]; do
    case "$1" in
        --pg-container) RESTORE_PG_CONTAINER="$2"; shift 2 ;;
        --pg-db) RESTORE_DB="$2"; shift 2 ;;
        --minio-container) RESTORE_MINIO_CONTAINER="$2"; shift 2 ;;
        --bucket) RESTORE_BUCKET="$2"; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$RESTORE_PG_CONTAINER" ] && [ -n "$RESTORE_DB" ] && [ -n "$RESTORE_MINIO_CONTAINER" ] && [ -n "$RESTORE_BUCKET" ] || usage
validate_database_name "$SOURCE_DB"
validate_database_name "$RESTORE_DB"
validate_bucket_name "$SOURCE_BUCKET"
validate_bucket_name "$RESTORE_BUCKET"
# 可自定义表/列，但不能把写 SQL 带入声称只读的核验流程。
[[ "$OBJECT_KEY_SQL" =~ ^[[:space:]]*[Ss][Ee][Ll][Ee][Cc][Tt][[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]+[Ff][Rr][Oo][Mm][[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*$ ]] \
    || die 3 "对象键查询只允许单表单列 SELECT"

require_container "$SOURCE_PG_CONTAINER" "业务源 PostgreSQL"
require_container "$RESTORE_PG_CONTAINER" "恢复库 PostgreSQL"
require_container "$SOURCE_MINIO_CONTAINER" "业务源 MinIO"
require_container "$RESTORE_MINIO_CONTAINER" "恢复目标 MinIO"
[ -n "$MINIO_RESTORE_USER" ] && [ -n "$MINIO_RESTORE_PASSWORD" ] || die 3 "恢复 MinIO 凭据未设置"

pg_source_query() { PGPASSWORD="$(get_env POSTGRES_PASSWORD '')" docker exec -e PGPASSWORD "$SOURCE_PG_CONTAINER" \
    psql -h localhost -U "$(get_env POSTGRES_USER ai_collab)" -d "$SOURCE_DB" -Atc "$1"; }
pg_restore_query() { PGPASSWORD="$(get_env POSTGRES_PASSWORD_RESTORE "$(get_env POSTGRES_PASSWORD '')")" docker exec -e PGPASSWORD "$RESTORE_PG_CONTAINER" \
    psql -h localhost -U "$(get_env POSTGRES_RESTORE_USER "$(get_env POSTGRES_USER ai_collab)")" -d "$RESTORE_DB" -Atc "$1"; }

DATA_OK=1

log "== 关键表行数比对（源库 $SOURCE_DB vs 恢复库 $RESTORE_DB）=="
for table in $KEY_TABLES; do
    [[ "$table" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || die 18 "表名格式无效，拒绝核验"
    src_count="$(pg_source_query "SELECT count(*) FROM $table" 2>/dev/null)" \
        || die 18 "源库行数查询失败，不能判定恢复一致"
    dst_count="$(pg_restore_query "SELECT count(*) FROM $table" 2>/dev/null)" \
        || die 18 "恢复库行数查询失败，不能判定恢复一致"
    [[ "$src_count" =~ ^[0-9]+$ && "$dst_count" =~ ^[0-9]+$ ]] \
        || die 18 "行数查询未返回有效数字"
    if [ "$src_count" = "$dst_count" ]; then
        log "  OK  $table = $src_count"
    else
        log "  MISMATCH  $table 源=$src_count 恢复=$dst_count"
        DATA_OK=0
    fi
done
[ "$DATA_OK" = "1" ] || die 18 "关键表行数不一致，恢复结果不完整"

log "== 对象引用一致性核验 =="
umask 077
VERIFY_TEMP_BASE="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
VERIFY_TMP="$(mktemp -d "$VERIFY_TEMP_BASE/ai-collab-verify.XXXXXXXX")"
[[ "$VERIFY_TMP" = "$VERIFY_TEMP_BASE"/ai-collab-verify.* ]] || die 3 "核验临时目录不在预期位置"
trap 'rm -rf -- "$VERIFY_TMP"' EXIT
# 恢复库中的对象引用集合
pg_restore_query "$OBJECT_KEY_SQL" | sort > "$VERIFY_TMP/restore-object-keys.txt"
# 恢复 bucket 中的对象键集合（mc ls --recursive --json 提取 key 字段）
RESTORE_MINIO_IMAGE="$(get_env MINIO_IMAGE "$(docker inspect --format '{{.Config.Image}}' "$RESTORE_MINIO_CONTAINER" 2>/dev/null || true)")"
MC_USER="$MINIO_RESTORE_USER" MC_PASS="$MINIO_RESTORE_PASSWORD" MSYS_NO_PATHCONV=1 docker run --rm --network "container:$RESTORE_MINIO_CONTAINER" \
        -e MC_USER -e MC_PASS \
        --entrypoint sh "$RESTORE_MINIO_IMAGE" -c "
            mc alias set dst http://127.0.0.1:9000 \"\$MC_USER\" \"\$MC_PASS\" >/dev/null &&
            mc ls --recursive \"dst/$RESTORE_BUCKET\" --json
        " | sed -n 's/.*"key"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | sort > "$VERIFY_TMP/restore-bucket-objects.txt"

DB_KEYS="$(wc -l < "$VERIFY_TMP/restore-object-keys.txt" | tr -d '[:space:]')"
OBJ_KEYS="$(wc -l < "$VERIFY_TMP/restore-bucket-objects.txt" | tr -d '[:space:]')"
log "  恢复库对象引用: $DB_KEYS 条；恢复 bucket 对象: $OBJ_KEYS 个"

# 核验 1：恢复库每条引用都必须能在恢复 bucket 中找到对象（缺对象 = 知识库不可用）。
MISSING=0
while IFS= read -r key; do
    [ -z "$key" ] && continue
    if ! grep -Fqx -- "$key" "$VERIFY_TMP/restore-bucket-objects.txt"; then
        MISSING=$((MISSING + 1))
    fi
done < "$VERIFY_TMP/restore-object-keys.txt"
[ "$MISSING" = "0" ] || die 19 "恢复库有 $MISSING 条对象引用在恢复 bucket 中无对应文件"

# 核验 2：恢复库引用集合应与业务源一致（证明恢复的是同一时点的关联关系）。
pg_source_query "$OBJECT_KEY_SQL" | sort > "$VERIFY_TMP/source-object-keys.txt"
if ! diff -q "$VERIFY_TMP/source-object-keys.txt" "$VERIFY_TMP/restore-object-keys.txt" > /dev/null; then
    die 19 "恢复库对象引用集合与业务源不一致（备份与恢复间数据发生了变化？）"
fi
log "  OK  对象引用集合与业务源一致（$DB_KEYS 条），且全部能在恢复 bucket 中找到对应文件"

log "一致性核验通过：数据库关键表行数与 DB↔MinIO 对象引用均一致"
