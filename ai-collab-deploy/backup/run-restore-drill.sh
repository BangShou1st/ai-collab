#!/usr/bin/env bash
# 隔离恢复演练编排脚本：一条命令完成 备份 -> 临时实例恢复 -> 一致性核验 -> 清理。
#
# 隔离性保证（不触碰任何业务数据）：
#   - 临时 PostgreSQL / MinIO 使用全新匿名卷、不发布任何宿主机端口、不接入任何业务网络；
#   - 源侧仅以只读方式导出（pg_dump / mc mirror）；
#   - 结束时强制删除临时容器，源容器只读不写。
#
# 用法:
#   run-restore-drill.sh                 # 现场执行全新备份后演练
#   run-restore-drill.sh --backup-dir <已有备份目录>   # 复用既有备份（目录需含 postgres/ 与 minio/<bucket>/）
#
# 退出码：0 演练通过 / 其他见各子脚本约定
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

BACKUP_DIR_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --backup-dir) BACKUP_DIR_ARG="$2"; shift 2 ;;
        *) die 3 "未知参数: $1" ;;
    esac
done

POSTGRES_SOURCE_IMAGE="$(docker inspect --format '{{.Config.Image}}' "$(get_env POSTGRES_CONTAINER ai-collab-postgres)" 2>/dev/null || true)"
POSTGRES_IMAGE="$(get_env DRILL_POSTGRES_IMAGE "${POSTGRES_SOURCE_IMAGE:-pgvector/pgvector:pg17}")"
MINIO_IMAGE="$(get_env MINIO_IMAGE "$(docker inspect --format '{{.Config.Image}}' "$(get_env MINIO_CONTAINER ai-collab-minio)" 2>/dev/null || true)")"
[ -n "$MINIO_IMAGE" ] || die 3 "MINIO_IMAGE 无法确定"
MINIO_BUCKET="$(get_env MINIO_BUCKET ai-collab)"

DRILL_RUN="drill-$(date -u +%Y%m%dT%H%M%SZ)"
DRILL_PG_CONTAINER="ai-collab-${DRILL_RUN}-pg"
DRILL_MINIO_CONTAINER="ai-collab-${DRILL_RUN}-minio"
DRILL_DB="${DRILL_RUN}_db"
DRILL_PG_USER="drill"
DRILL_PG_PASSWORD="drill-only-$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
DRILL_MINIO_USER="drill"
DRILL_MINIO_PASSWORD="drill-only-$(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"

BACKUP_ROOT="$(get_env BACKUP_ROOT "$REPO_ROOT/backups")"
if [ -n "$BACKUP_DIR_ARG" ]; then
    BACKUP_DIR="$BACKUP_DIR_ARG"
else
    BACKUP_DIR="$BACKUP_ROOT"
    log "[1/5] 现场执行全新备份（只读源）"
    bash "$SCRIPT_DIR/backup-postgres.sh"
    bash "$SCRIPT_DIR/backup-minio.sh"
fi
DUMP_FILE="$(ls -t "$BACKUP_DIR"/postgres/*.dump 2>/dev/null | head -1 || true)"
[ -n "$DUMP_FILE" ] || die 3 "$BACKUP_DIR/postgres 下没有 *.dump 备份文件"
MINIO_DIR="$BACKUP_DIR/minio/$MINIO_BUCKET"
[ -d "$MINIO_DIR" ] || die 3 "未找到 MinIO 备份目录: $MINIO_DIR"
log "使用备份：$(basename "$DUMP_FILE") + $MINIO_DIR"

cleanup() {
    log "[5/5] 清理演练临时容器"
    docker rm -f "$DRILL_PG_CONTAINER" "$DRILL_MINIO_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

log "[2/5] 启动隔离演练实例（无端口、无业务网络、全新匿名卷）"
# POSTGRES_DB 用占位名：postgres 镜像启动时会自动建该库，恢复目标库名必须保持"不存在"。
MSYS_NO_PATHCONV=1 docker run -d --rm --name "$DRILL_PG_CONTAINER" --network none \
    -e POSTGRES_DB="${DRILL_DB}_seed" -e POSTGRES_USER="$DRILL_PG_USER" -e POSTGRES_PASSWORD="$DRILL_PG_PASSWORD" \
    -v "$(host_path "$DUMP_FILE"):/tmp/restore.dump:ro" "$POSTGRES_IMAGE" >/dev/null
MSYS_NO_PATHCONV=1 docker run -d --rm --name "$DRILL_MINIO_CONTAINER" --network none \
    -e MINIO_ROOT_USER="$DRILL_MINIO_USER" -e MINIO_ROOT_PASSWORD="$DRILL_MINIO_PASSWORD" \
    -v "$(host_path "$MINIO_DIR"):/restore-src:ro" -v /data "$MINIO_IMAGE" server /data >/dev/null
wait_ready() {
    local name="$1" probe="$2" i=0
    until docker exec "$name" $probe >/dev/null 2>&1; do
        i=$((i + 1))
        # 容器已退出（--rm 已回收）或 90 秒未就绪都立即失败，绝不无限等待。
        docker inspect "$name" >/dev/null 2>&1 || die 3 "演练容器 $name 已退出，无法就绪"
        [ "$i" -lt 90 ] || die 3 "演练容器 $name 等待就绪超时"
        sleep 1
    done
}
wait_ready "$DRILL_PG_CONTAINER" "pg_isready -U $DRILL_PG_USER -d $DRILL_DB"
wait_ready "$DRILL_MINIO_CONTAINER" "mc ready local"
log "演练实例就绪：$DRILL_PG_CONTAINER / $DRILL_MINIO_CONTAINER"

log "[3/5] 恢复数据库与对象到隔离实例"
# 恢复目标是非业务容器，安全守卫不会触发；显式传目标容器与库名。
POSTGRES_RESTORE_USER="$DRILL_PG_USER" POSTGRES_PASSWORD_RESTORE="$DRILL_PG_PASSWORD" \
    bash "$SCRIPT_DIR/restore-postgres.sh" --file "$DUMP_FILE" --container "$DRILL_PG_CONTAINER" --db "$DRILL_DB"
MINIO_RESTORE_USER="$DRILL_MINIO_USER" MINIO_RESTORE_PASSWORD="$DRILL_MINIO_PASSWORD" \
    bash "$SCRIPT_DIR/restore-minio.sh" --dir "$MINIO_DIR" --container "$DRILL_MINIO_CONTAINER" --bucket "$MINIO_BUCKET"

log "[4/5] 一致性核验（源业务 vs 隔离恢复结果）"
VERIFY_KEY_TABLES="${VERIFY_KEY_TABLES:-app_user project project_member project_task milestone task_comment project_document document_chunk ai_task_plan audit_log}" \
VERIFY_OBJECT_KEY_SQL="${VERIFY_OBJECT_KEY_SQL:-SELECT object_key FROM project_document}" \
POSTGRES_RESTORE_USER="$DRILL_PG_USER" POSTGRES_PASSWORD_RESTORE="$DRILL_PG_PASSWORD" \
MINIO_RESTORE_USER="$DRILL_MINIO_USER" MINIO_RESTORE_PASSWORD="$DRILL_MINIO_PASSWORD" \
    bash "$SCRIPT_DIR/verify-restore-consistency.sh" \
    --pg-container "$DRILL_PG_CONTAINER" --pg-db "$DRILL_DB" \
    --minio-container "$DRILL_MINIO_CONTAINER" --bucket "$MINIO_BUCKET"

log "演练通过：备份 -> 隔离恢复 -> 一致性核验 全部成功（源业务数据全程只读）"
