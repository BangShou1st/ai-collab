#!/usr/bin/env bash
# MinIO 恢复脚本：把本地备份目录镜像到【指定的目标 MinIO 实例】的指定 bucket。
# 目标必须显式指定（推荐独立/演练实例）；默认拒绝写回当前业务源实例同名 bucket。
# 用法见同目录 README.md。
#
# 退出码：0 成功 / 3 参数或容器不可用 / 14 恢复目标被安全守卫拒绝 / 17 恢复失败
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

SOURCE_DIR=""
TARGET_CONTAINER=""
TARGET_BUCKET=""
ALLOW_SOURCE_OVERWRITE="${ALLOW_SOURCE_OVERWRITE:-0}"

usage() {
    cat >&2 <<'EOF'
用法: restore-minio.sh --dir <备份目录> --container <目标MinIO容器> --bucket <目标bucket>
说明:
  - 目标实例凭据取环境变量 MINIO_RESTORE_USER / MINIO_RESTORE_PASSWORD（默认回退 MINIO_ROOT_*）
  - 恢复经目标容器自身网络命名空间（127.0.0.1:9000）执行，不发布端口
  - ALLOW_SOURCE_OVERWRITE=1 可解除"禁止写回业务源实例"守卫（强烈不建议）
EOF
    exit 3
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dir) SOURCE_DIR="$2"; shift 2 ;;
        --container) TARGET_CONTAINER="$2"; shift 2 ;;
        --bucket) TARGET_BUCKET="$2"; shift 2 ;;
        *) usage ;;
    esac
done
[ -n "$SOURCE_DIR" ] && [ -n "$TARGET_CONTAINER" ] && [ -n "$TARGET_BUCKET" ] || usage
[ -d "$SOURCE_DIR" ] || die 3 "备份目录不存在: $SOURCE_DIR"

MINIO_USER_RESTORE="$(get_env MINIO_RESTORE_USER "$(get_env MINIO_ROOT_USER '')")"
MINIO_PASSWORD_RESTORE="$(get_env MINIO_RESTORE_PASSWORD "$(get_env MINIO_ROOT_PASSWORD '')")"
[ -n "$MINIO_USER_RESTORE" ] && [ -n "$MINIO_PASSWORD_RESTORE" ] || die 3 "目标实例凭据未设置"
MINIO_IMAGE="$(get_env MINIO_IMAGE "$(docker inspect --format '{{.Config.Image}}' "$TARGET_CONTAINER" 2>/dev/null || true)")"
[ -n "$MINIO_IMAGE" ] || die 3 "MINIO_IMAGE 无法确定"

# 安全守卫：业务源实例 + 同名 bucket 默认禁止恢复。
SOURCE_MINIO_CONTAINER="$(get_env MINIO_CONTAINER ai-collab-minio)"
SOURCE_BUCKET="$(get_env MINIO_BUCKET ai-collab)"
if [ "$TARGET_CONTAINER" = "$SOURCE_MINIO_CONTAINER" ] && [ "$TARGET_BUCKET" = "$SOURCE_BUCKET" ] \
        && [ "$ALLOW_SOURCE_OVERWRITE" != "1" ]; then
    die 14 "拒绝恢复：目标 $TARGET_CONTAINER/$TARGET_BUCKET 是当前业务 bucket。请使用隔离实例或独立 bucket；确要覆盖请设 ALLOW_SOURCE_OVERWRITE=1"
fi
require_container "$TARGET_CONTAINER" "恢复目标 MinIO"

log "开始恢复：dir=$SOURCE_DIR 目标容器=$TARGET_CONTAINER bucket=$TARGET_BUCKET"
if ! MSYS_NO_PATHCONV=1 docker run --rm --network "container:$TARGET_CONTAINER" \
        -v "$(host_path "$SOURCE_DIR"):/restore-src:ro" \
        -e MC_USER="$MINIO_USER_RESTORE" -e MC_PASS="$MINIO_PASSWORD_RESTORE" \
        --entrypoint sh "$MINIO_IMAGE" -c "
            mc alias set dst http://127.0.0.1:9000 \"\$MC_USER\" \"\$MC_PASS\" >/dev/null &&
            mc mb --ignore-existing \"dst/$TARGET_BUCKET\" >/dev/null &&
            mc mirror --overwrite /restore-src \"dst/$TARGET_BUCKET\"
        "; then
    die 17 "mc mirror 恢复执行失败"
fi

# 恢复后自检：目标 bucket 必须可读且对象数与备份目录一致。
RESTORED_COUNT="$(MSYS_NO_PATHCONV=1 docker run --rm --network "container:$TARGET_CONTAINER" \
        -e MC_USER="$MINIO_USER_RESTORE" -e MC_PASS="$MINIO_PASSWORD_RESTORE" \
        --entrypoint sh "$MINIO_IMAGE" -c "
            mc alias set dst http://127.0.0.1:9000 \"\$MC_USER\" \"\$MC_PASS\" >/dev/null &&
            mc ls --recursive \"dst/$TARGET_BUCKET\" | wc -l
        " | tr -d '[:space:]')"
LOCAL_COUNT="$(find "$SOURCE_DIR" -type f | wc -l | tr -d '[:space:]')"
[ "$RESTORED_COUNT" = "$LOCAL_COUNT" ] \
    || die 17 "恢复后自检失败：目标 bucket 对象数($RESTORED_COUNT) != 备份目录文件数($LOCAL_COUNT)"
log "恢复完成：目标 bucket 共 $RESTORED_COUNT 个对象，与备份目录一致"
