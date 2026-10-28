#!/usr/bin/env bash
# 备份/恢复脚本的公共函数库。被同目录其他脚本 source，不单独执行。
# 所有脚本共用约定：
#   - set -euo pipefail，任何步骤失败立即退出并返回明确错误码；
#   - 凭据只从环境变量或仓库根 .env 读取，绝不写入日志与命令输出；
#   - 恢复类操作默认拒绝触碰当前业务容器，除非显式传 ALLOW_SOURCE_OVERWRITE=1。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[1]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

log()  { printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }
warn() { log "WARN: $*" >&2; }
die()  { log "ERROR: $*" >&2; exit "$1"; }

# 读取配置：优先取真实环境变量，其次回退仓库根 .env 中同名键，最后用默认值。
# 值两端的成对引号会被剥掉；不在日志中打印任何读取到的值。
get_env() {
    local key="$1" default="${2:-}" value=""
    if [ -n "${!key:-}" ]; then
        printf '%s' "${!key}"
        return 0
    fi
    if [ -f "$REPO_ROOT/.env" ]; then
        value="$(grep -E "^[[:space:]]*${key}=" "$REPO_ROOT/.env" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
        value="${value%\"}"; value="${value#\"}"
        value="${value%\'}"; value="${value#\'}"
    fi
    if [ -n "$value" ]; then
        printf '%s' "$value"
    else
        printf '%s' "$default"
    fi
}

require_container() {
    local name="$1" role="$2"
    docker inspect "$name" >/dev/null 2>&1 || die 3 "$role 容器不存在或未运行: $name（可用环境变量覆盖）"
}

validate_database_name() {
    [[ "$1" =~ ^[A-Za-z0-9_][A-Za-z0-9_-]{0,62}$ ]] || die 3 "数据库名格式无效"
}

validate_bucket_name() {
    [[ "$1" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ && "$1" != *..* && "$1" != *.-* && "$1" != *-.* ]] \
        || die 3 "bucket 名称格式无效"
}

# docker run -v 的宿主机路径：Git Bash/MSYS 下转为盘符形式（E:/...），
# 既避免 MSYS 对参数的隐式改写，也让 Docker Desktop 能正确挂载；Linux 原样返回。
host_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# 确保目标目录存在且不在 Git 跟踪范围（backups/ 已在 .gitignore）。
ensure_dir() {
    mkdir -p "$1"
}

# 保留策略：删除备份根目录下超过保留天数的归档文件。
apply_retention() {
    local root="$1" days="$2"
    if [ "$days" -gt 0 ] 2>/dev/null; then
        find "$root" -type f \( -name '*.dump' -o -name '*.sha256' -o -name '*.manifest.txt' -o -name '*.summary' \) -mtime +"$days" -print -delete 2>/dev/null | while read -r f; do
            log "retention: 删除过期备份文件 $f"
        done
    fi
}
