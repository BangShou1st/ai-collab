#!/usr/bin/env bash
# MinIO 对象存储备份脚本：mc mirror 到宿主机目录 + mc diff 完整性校验 + 清单。
# 只读备份，不触碰源 bucket 内容。用法见同目录 README.md。
#
# 退出码：0 成功 / 3 容器不可用 / 12 镜像同步失败 / 13 diff 校验发现差异
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

MINIO_CONTAINER="$(get_env MINIO_CONTAINER ai-collab-minio)"
MINIO_IMAGE="$(get_env MINIO_IMAGE "$(docker inspect --format '{{.Config.Image}}' "$MINIO_CONTAINER" 2>/dev/null || true)")"
MINIO_ROOT_USER="$(get_env MINIO_ROOT_USER '')"
MINIO_ROOT_PASSWORD="$(get_env MINIO_ROOT_PASSWORD '')"
MINIO_BUCKET="$(get_env MINIO_BUCKET ai-collab)"
BACKUP_ROOT="$(get_env BACKUP_ROOT "$REPO_ROOT/backups")"

[ -n "$MINIO_IMAGE" ] || die 3 "MINIO_IMAGE 无法确定（默认取源容器镜像，亦可显式设置）"
[ -n "$MINIO_ROOT_USER" ] && [ -n "$MINIO_ROOT_PASSWORD" ] || die 3 "MINIO_ROOT_USER / MINIO_ROOT_PASSWORD 未设置"
require_container "$MINIO_CONTAINER" "MinIO 源"

TARGET_DIR="$BACKUP_ROOT/minio/$MINIO_BUCKET"
ensure_dir "$TARGET_DIR"
TIMESTAMP="$(date +%Y%m%dT%H%M%SZ)"
STARTED_AT="$(date +%s)"

log "开始备份 MinIO：容器=$MINIO_CONTAINER bucket=$MINIO_BUCKET 输出=$TARGET_DIR"

# 借用源容器自身的网络命名空间（127.0.0.1:9000）运行一次性 mc 容器，
# 不发布端口、不写入源容器文件系统；凭据只进一次性容器进程环境。
MIRROR_LOG="$TARGET_DIR/../mirror-$TIMESTAMP.log"
if ! MSYS_NO_PATHCONV=1 docker run --rm --network "container:$MINIO_CONTAINER" \
        -v "$(host_path "$TARGET_DIR"):/backup" \
        -e MC_ACCESS_KEY="$MINIO_ROOT_USER" -e MC_SECRET_KEY="$MINIO_ROOT_PASSWORD" \
        --entrypoint sh "$MINIO_IMAGE" -c "
            mc alias set src http://127.0.0.1:9000 \"\$MC_ACCESS_KEY\" \"\$MC_SECRET_KEY\" >/dev/null &&
            mc mirror --overwrite \"src/$MINIO_BUCKET\" /backup
        " > "$MIRROR_LOG" 2>&1; then
    cat "$MIRROR_LOG" >&2
    die 12 "mc mirror 执行失败，详见 $MIRROR_LOG"
fi

# 完整性校验：mc diff 比对源 bucket 与本地副本，任何差异（多/少/内容不同）都视为备份无效。
DIFF_OUTPUT="$(MSYS_NO_PATHCONV=1 docker run --rm --network "container:$MINIO_CONTAINER" \
        -v "$(host_path "$TARGET_DIR"):/backup" \
        -e MC_ACCESS_KEY="$MINIO_ROOT_USER" -e MC_SECRET_KEY="$MINIO_ROOT_PASSWORD" \
        --entrypoint sh "$MINIO_IMAGE" -c "
            mc alias set src http://127.0.0.1:9000 \"\$MC_ACCESS_KEY\" \"\$MC_SECRET_KEY\" >/dev/null &&
            mc diff \"src/$MINIO_BUCKET\" /backup
        " 2>&1 || true)"
rm -f "$MIRROR_LOG"
if [ -n "$DIFF_OUTPUT" ]; then
    printf '%s\n' "$DIFF_OUTPUT" | sed 's/^/  diff: /' >&2
    die 13 "完整性校验失败：mc diff 在源 bucket 与本地副本之间发现差异"
fi

# 清单与统计：恢复后据此核对对象数量与总字节。
MSYS_NO_PATHCONV=1 docker run --rm --network "container:$MINIO_CONTAINER" \
        -v "$(host_path "$TARGET_DIR"):/backup" \
        -e MC_ACCESS_KEY="$MINIO_ROOT_USER" -e MC_SECRET_KEY="$MINIO_ROOT_PASSWORD" \
        --entrypoint sh "$MINIO_IMAGE" -c "
            mc alias set src http://127.0.0.1:9000 \"\$MC_ACCESS_KEY\" \"\$MC_SECRET_KEY\" >/dev/null &&
            mc ls --recursive \"src/$MINIO_BUCKET\"
        " > "$TARGET_DIR.manifest.txt" 2>/dev/null || true

OBJECT_COUNT="$(wc -l < "$TARGET_DIR.manifest.txt" | tr -d '[:space:]')"
LOCAL_FILE_COUNT="$(find "$TARGET_DIR" -type f | wc -l | tr -d '[:space:]')"

ELAPSED=$(( $(date +%s) - STARTED_AT ))
printf 'bucket=%s dir=%s objects=%s local_files=%s elapsed_seconds=%s status=OK\n' \
    "$MINIO_BUCKET" "$TARGET_DIR" "$OBJECT_COUNT" "$LOCAL_FILE_COUNT" "$ELAPSED" | tee "$TARGET_DIR.summary"
log "MinIO 备份完成（含 mc diff 完整性校验与对象清单）"
