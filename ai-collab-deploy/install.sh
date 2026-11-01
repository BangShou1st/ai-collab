#!/usr/bin/env bash
# AI Collab 一键安装与统一维护入口（Linux / macOS）。
# 两种模式：
#   本地模式（默认）  ：管理 compose.local.yml，入口仅绑定 127.0.0.1，HTTP。
#   服务器模式(--server)：管理 compose.prod.yml，HTTPS + 域名 + 网络隔离，面向单台 Linux 服务器。
# 面向最终用户：只要求 Docker，不要求 Java/Maven/Node/pnpm。
#
# 用法：
#   ./install.sh install                       # 本地首次安装（幂等）
#   ./install.sh bootstrap-admin               # 首位管理员初始化（仅空系统可用一次）
#   ./install.sh status|up|stop|restart|logs|doctor|version|backup|uninstall
#   ./install.sh restore --dump <file.dump> [--minio <file.dump 对应目录>]
#   ./install.sh upgrade --tag <版本>
#   服务器模式：./install.sh install --server --domain aicollab.example.org \
#                 --tls-cert /srv/ai-collab/tls/fullchain.pem --tls-key /srv/ai-collab/tls/privkey.pem
#   服务器模式的 bootstrap-admin / status / backup 等命令同样需要 --server --domain <域名>。
#
# 退出码：0 成功；1 一般错误；2 Docker 不可用；3 配置缺失/占位符；
#         4 端口冲突；10 升级失败（已回滚）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$SCRIPT_DIR/local.env"
PROD_ENV_FILE="$SCRIPT_DIR/prod.env"
COMPOSE_LOCAL="$SCRIPT_DIR/compose.local.yml"
COMPOSE_PROD="$SCRIPT_DIR/compose.prod.yml"
BACKUP_ROOT="$REPO_ROOT/backups/local"
TAG="latest"
USERNAME="admin"
HTTP_PORT=""
TLS_CERT=""
TLS_KEY=""
DOMAIN=""
SERVER_MODE=0
IMAGE=""
FRONTEND_IMAGE=""
DUMP=""
MINIO_ARCHIVE=""
TAIL="100"
FOLLOW=0
COMMAND=""

log()  { printf '[install] %s\n' "$*"; }
fail() { printf '[install] ERROR: %s\n' "$*" >&2; exit "$1"; }
usage() { sed -n '2,28p' "$0" >&2; exit 3; }

while [ $# -gt 0 ]; do
    case "$1" in
        --server) SERVER_MODE=1 ;;
        --port) HTTP_PORT="$2"; shift ;;
        --image) IMAGE="$2"; shift ;;
        --frontend-image) FRONTEND_IMAGE="$2"; shift ;;
        --tag) TAG="$2"; shift ;;
        --username) USERNAME="$2"; shift ;;
        --tail) TAIL="$2"; shift ;;
        -f|--follow) FOLLOW=1 ;;
        --dump) DUMP="$2"; shift ;;
        --minio) MINIO_ARCHIVE="$2"; shift ;;
        --domain) DOMAIN="$2"; shift ;;
        --tls-cert) TLS_CERT="$2"; shift ;;
        --tls-key) TLS_KEY="$2"; shift ;;
        install|bootstrap-admin|up|stop|restart|status|logs|doctor|backup|restore|upgrade|version|uninstall|help)
            COMMAND="$1" ;;
        *) usage ;;
    esac
    shift
done
[ -n "$COMMAND" ] || COMMAND=status

# ---------- 通用工具 ----------
read_env() { # read_env <file> <key> -> 值（缺失时输出空串）
    [ -f "$1" ] || { printf ''; return 0; }
    grep -E "^[[:space:]]*$2=" "$1" | tail -1 | cut -d= -f2- || true
}
set_env_value() { # set_env_value <file> <key> <value>
    if [ -f "$1" ] && grep -qE "^[[:space:]]*${2}=" "$1"; then
        sed -i.bak "s#^[[:space:]]*${2}=.*#${2}=${3}#" "$1" && rm -f "$1.bak"
    else
        printf '%s=%s\n' "$2" "$3" >> "$1"
    fi
}
new_random() { openssl rand -hex "$1" 2>/dev/null || od -An -tx1 -N "$1" /dev/urandom | tr -d ' \n'; }
assert_docker() {
    command -v docker >/dev/null 2>&1 || fail 2 '未找到 docker 命令。请先安装 Docker：https://docs.docker.com/engine/install/'
    docker info >/dev/null 2>&1 || fail 2 'Docker 引擎不可用。请启动 Docker（Linux: systemctl start docker；macOS: Docker Desktop）后重试。'
    docker compose version >/dev/null 2>&1 || fail 2 '缺少 Compose v2 插件（docker compose version 失败）。'
    command -v curl >/dev/null 2>&1 || fail 2 '缺少 curl。'
    command -v openssl >/dev/null 2>&1 || fail 2 '缺少 openssl。'
}
env_file_path() {
    if [ "$SERVER_MODE" = 1 ]; then printf '%s' "$PROD_ENV_FILE"; else printf '%s' "$ENV_FILE"; fi
}
compose_cmd() {
    if [ "$SERVER_MODE" = 1 ]; then
        docker compose --env-file "$PROD_ENV_FILE" -f "$COMPOSE_PROD" "$@"
    else
        docker compose --env-file "$ENV_FILE" -f "$COMPOSE_LOCAL" "$@"
    fi
}
service_id() { compose_cmd ps -q "$1" 2>/dev/null | head -1; }
container_health() {
    local id; id="$(service_id "$1")"
    if [ -z "$id" ]; then printf 'absent'; return 0; fi
    docker inspect --format '{{if not .State.Running}}exited{{else}}{{if .State.Health}}{{.State.Health.Status}}{{else}}running{{end}}{{end}}' "$id" 2>/dev/null || printf 'absent'
}
env_key() { read_env "$(env_file_path)" "$1"; }
resolve_entry() {
    if [ "$SERVER_MODE" = 1 ]; then
        printf 'https://%s:%s' "$DOMAIN" "$(env_key HTTPS_PORT)"
    else
        printf 'http://localhost:%s' "$(env_key HTTP_PORT)"
    fi
}
try_pull() { docker pull "$1" >/dev/null 2>&1; }
wait_entry_healthy() { # wait_entry_healthy <超时秒>
    local deadline=$((SECONDS + $1)) entry
    entry="$(resolve_entry)"
    log "等待服务就绪（首次启动需完成数据库迁移，可能需要 1-3 分钟）..."
    while [ "$SECONDS" -lt "$deadline" ]; do
        if curl --noproxy '*' -ksS --max-time 5 "$entry/api/v1/auth/registration-policy" 2>/dev/null |
            grep -q '"code"[[:space:]]*:[[:space:]]*"SUCCESS"'; then
            return 0
        fi
        sleep 5
    done
    return 1
}
port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&- 3<&-; return 0; } || return 1; }
api_call() { # api_call <method> <path> <json-body 或 ""> <token 或 "">
    local entry; entry="$(resolve_entry)"
    local args=(--noproxy '*' -ksS -X "$1" "$entry$2" -H 'Content-Type: application/json' --max-time 30)
    if [ -n "$3" ]; then args+=(-d "$3"); fi
    if [ -n "$4" ]; then args+=(-H "Authorization: Bearer $4"); fi
    curl "${args[@]}" 2>/dev/null || printf ''
}
warn_arch() {
    local arch; arch="$(docker version --format '{{.Server.Arch}}')"
    case "$arch" in
        arm*|aarch64*) log "WARN: Docker 引擎架构为 $arch；后端镜像为 amd64 构建，依赖模拟层（Docker Desktop Rosetta/QEMU），性能会明显下降，建议在 x64 主机运行。" ;;
        *) log "引擎架构: $arch（与 amd64 应用镜像兼容）" ;;
    esac
}

# ---------- 镜像解析 ----------
resolve_images() { # 输出 "<backend> <frontend>"；顺序：参数 > env 已有有效值 > 注册表 > 本地构建
    local env_file; env_file="$(env_file_path)"
    local backend="$IMAGE" frontend="$FRONTEND_IMAGE" rev
    if [ -z "$backend" ]; then
        backend="$(read_env "$env_file" APP_IMAGE)"
        case "$backend" in ''|REQUIRED*) backend='' ;; esac
    fi
    if [ -z "$backend" ]; then
        log "尝试拉取预构建后端镜像 ghcr.io/bangshou1st/ai-collab-backend:$TAG ..."
        if try_pull "ghcr.io/bangshou1st/ai-collab-backend:$TAG"; then
            backend="ghcr.io/bangshou1st/ai-collab-backend:$TAG"
        fi
    fi
    if [ -z "$frontend" ]; then
        frontend="$(read_env "$env_file" WEB_IMAGE)"
        case "$frontend" in ''|REQUIRED*) frontend='' ;; esac
        if [ "$SERVER_MODE" = 1 ]; then
            local proxy; proxy="$(read_env "$env_file" PROXY_IMAGE)"
            case "$proxy" in ''|REQUIRED*) proxy='' ;; esac
            if [ -z "$frontend" ]; then frontend="$proxy"; fi
        fi
    fi
    if [ -z "$frontend" ]; then
        log "尝试拉取预构建前端镜像 ghcr.io/bangshou1st/ai-collab-frontend:$TAG ..."
        if try_pull "ghcr.io/bangshou1st/ai-collab-frontend:$TAG"; then
            frontend="ghcr.io/bangshou1st/ai-collab-frontend:$TAG"
        fi
    fi
    rev="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || true)"
    if [ -z "$backend" ] && [ -f "$REPO_ROOT/ai-collab-backend/Dockerfile" ]; then
        log "预构建镜像不可用，改为从本地源码构建后端镜像（无需 Java/Maven，约需几分钟）..."
        if [ -n "$rev" ]; then
            docker build -f "$REPO_ROOT/ai-collab-backend/Dockerfile" -t ai-collab-backend:local \
                --label "org.opencontainers.image.revision=$rev" "$REPO_ROOT" >/dev/null
        else
            docker build -f "$REPO_ROOT/ai-collab-backend/Dockerfile" -t ai-collab-backend:local "$REPO_ROOT" >/dev/null
        fi
        backend="ai-collab-backend:local"
    fi
    if [ -z "$frontend" ] && [ -f "$REPO_ROOT/ai-collab-frontend/Dockerfile" ]; then
        log "从本地源码构建前端镜像（无需 Node/pnpm）..."
        if [ -n "$rev" ]; then
            docker build -f "$REPO_ROOT/ai-collab-frontend/Dockerfile" -t ai-collab-frontend:local \
                --label "org.opencontainers.image.revision=$rev" "$REPO_ROOT" >/dev/null
        else
            docker build -f "$REPO_ROOT/ai-collab-frontend/Dockerfile" -t ai-collab-frontend:local "$REPO_ROOT" >/dev/null
        fi
        frontend="ai-collab-frontend:local"
    fi
    if [ -z "$backend" ] || [ -z "$frontend" ]; then
        fail 3 '无法解析应用镜像（无预构建镜像且当前目录不含源码）。请用 --image / --frontend-image 显式指定。'
    fi
    printf '%s %s\n' "$backend" "$frontend"
}

# ---------- 首管理员初始化（本地与服务器共用） ----------
bootstrap_admin() {
    local pg_user pg_db
    pg_user="$(env_key POSTGRES_USER)"; pg_db="$(env_key POSTGRES_DB)"
    if [ -z "$pg_user" ] || [ -z "$pg_db" ]; then fail 3 '缺少部署配置；请先执行 install'; fi
    if ! printf '%s' "$USERNAME" | grep -qE '^[A-Za-z0-9_]{3,40}$'; then
        fail 3 '用户名只允许字母、数字与下划线，长度 3-40。'
    fi
    local pg_id; pg_id="$(service_id postgres)"
    if [ -z "$pg_id" ]; then fail 1 'PostgreSQL 容器未运行；请先执行 install/up'; fi
    local count
    count="$(docker exec "$pg_id" psql -U "$pg_user" -d "$pg_db" -t -A -c 'SELECT count(*) FROM app_user WHERE system_admin = true;')" \
        || fail 1 '无法查询管理员数量（数据库尚未初始化？请先 install 并等待启动完成）'
    if [ "${count:-0}" -gt 0 ]; then
        log '系统已存在管理员，初始化通道保持关闭；本命令不再执行（防重复引导）。'
        return 0
    fi
    local password; password="ac-$(new_random 12)-A1"
    local reg
    reg="$(api_call POST /api/v1/auth/register "{\"username\":\"$USERNAME\",\"password\":\"$password\",\"displayName\":\"System Administrator\",\"email\":\"$USERNAME@ai-collab.local\"}" "")"
    if ! printf '%s' "$reg" | grep -q '"code"[[:space:]]*:[[:space:]]*"SUCCESS"'; then
        fail 1 "注册接口调用失败（响应已脱敏）。若系统已关闭公开注册，请先创建普通用户再联系运维处理。"
    fi
    if printf '%s' "$reg" | grep -q '"systemAdmin"[[:space:]]*:[[:space:]]*true'; then
        fail 1 '安全异常：公开注册用户被自动赋予管理员角色，已中止。'
    fi
    # 受控提权：PostgreSQL 事务级咨询锁保证并发引导只有一个赢家。
    local out
    out="$(docker exec "$pg_id" psql -U "$pg_user" -d "$pg_db" -t -A -c \
        "BEGIN; SELECT pg_advisory_xact_lock(917463); UPDATE app_user SET system_admin = true WHERE username = '$USERNAME' AND (SELECT count(*) FROM app_user WHERE system_admin = true) = 0; COMMIT;")" \
        || fail 1 '提权 SQL 执行失败，新注册用户保持普通用户状态。'
    if ! printf '%s' "$out" | grep -q 'UPDATE 1'; then
        log '并发竞争中另一位管理员已完成初始化，本次未提权（新注册用户保留为普通用户）。'
        return 0
    fi
    local login
    login="$(api_call POST /api/v1/auth/login "{\"username\":\"$USERNAME\",\"password\":\"$password\"}" "")"
    if ! printf '%s' "$login" | grep -q '"code"[[:space:]]*:[[:space:]]*"SUCCESS"'; then
        fail 1 '初始化后登录验证失败，请立即检查应用日志。'
    fi
    local token; token="$(printf '%s' "$login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
    local me; me="$(api_call GET /api/v1/auth/me "" "$token")"
    if ! printf '%s' "$me" | grep -q '"systemAdmin"[[:space:]]*:[[:space:]]*true'; then
        fail 1 '权限验证失败：/auth/me 未返回 systemAdmin=true。'
    fi
    log ''
    log '首位管理员创建成功（本凭据仅显示这一次，不会写入任何日志或文件）：'
    log "  用户名: $USERNAME"
    log "  密  码: $password"
    log ''
    log "请立即登录 $(resolve_entry) 并在个人设置中修改密码。"
}

# ---------- 备份（复用 ai-collab-deploy/backup 成熟脚本，仅注入容器与路径参数） ----------
run_backup() {
    local pg_id minio_id pg_user pg_db pg_pass minio_user minio_pass bucket
    pg_id="$(service_id postgres)"; minio_id="$(service_id minio)"
    if [ -z "$pg_id" ] || [ -z "$minio_id" ]; then fail 1 '备份目标容器未运行；请先执行 up'; fi
    pg_user="$(env_key POSTGRES_USER)"; pg_db="$(env_key POSTGRES_DB)"
    pg_pass="$(env_key POSTGRES_PASSWORD)"
    minio_user="$(env_key MINIO_ROOT_USER)"; minio_pass="$(env_key MINIO_ROOT_PASSWORD)"
    bucket="$(env_key MINIO_BUCKET)"
    if [ -z "$pg_pass" ]; then fail 3 'POSTGRES_PASSWORD 未配置'; fi
    mkdir -p "$BACKUP_ROOT"
    export POSTGRES_CONTAINER="$pg_id" MINIO_CONTAINER="$minio_id" BACKUP_ROOT="$BACKUP_ROOT"
    export POSTGRES_DB="$pg_db" POSTGRES_USER="$pg_user" POSTGRES_PASSWORD="$pg_pass"
    export MINIO_ROOT_USER="$minio_user" MINIO_ROOT_PASSWORD="$minio_pass" MINIO_BUCKET="$bucket"
    export MINIO_IMAGE="$(docker inspect --format '{{.Config.Image}}' "$minio_id")"
    local retention; retention="$(env_key BACKUP_RETENTION_DAYS)"
    if [ -n "$retention" ]; then export BACKUP_RETENTION_DAYS="$retention"; else export BACKUP_RETENTION_DAYS=14; fi
    bash "$SCRIPT_DIR/backup/backup-postgres.sh"
    bash "$SCRIPT_DIR/backup/backup-minio.sh"
    log "备份目录: $BACKUP_ROOT（摘要见 pg_*.summary）"
    log '注意：数据库与对象存储先后备份，非原子快照；跨系统一致性以恢复演练为准。'
}

# ---------- 服务器模式：MinIO 桶与应用账号初始化（幂等） ----------
setup_server_storage() {
    local minio_id bucket app_key app_secret
    minio_id="$(service_id minio)"
    if [ -z "$minio_id" ]; then fail 1 'MinIO 容器未运行'; fi
    bucket="$(env_key MINIO_BUCKET)"; app_key="$(env_key MINIO_ACCESS_KEY)"; app_secret="$(env_key MINIO_SECRET_KEY)"
    # 1. 应用账号桶策略（只需创建一次；容器内自带的 MINIO_ROOT_* 凭据经 -e 注入 mc 别名）。
    if ! docker exec "$minio_id" sh -c 'mc alias set init http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 && mc admin policy info init ai-collab-app-policy >/dev/null 2>&1'; then
        docker exec -i -e B="$bucket" "$minio_id" sh -c 'mc alias set init http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && cat > /tmp/app-policy.json && mc admin policy create init ai-collab-app-policy /tmp/app-policy.json >/dev/null' \
            <<POLICY
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::$bucket"]},{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::$bucket/*"]}]}
POLICY
    fi
    # 2. 建桶 + 应用账号 + 绑定策略（全部幂等；先解绑再绑定保证策略最终一致）。
    docker exec -e B="$bucket" -e U="$app_key" -e P="$app_secret" "$minio_id" sh -c '
        mc alias set init http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
        mc mb --ignore-existing --region us-east-1 "init/$B" >/dev/null &&
        (mc admin user add init "$U" "$P" >/dev/null 2>&1 || true) &&
        (mc admin policy detach init ai-collab-app-policy --user "$U" >/dev/null 2>&1 || true) &&
        mc admin policy attach init ai-collab-app-policy --user "$U" >/dev/null
    ' || fail 1 'MinIO 桶/应用账号初始化失败'
}

# ---------- 各命令实现 ----------
cmd_install() {
    assert_docker
    if [ "$SERVER_MODE" = 1 ]; then
        # ---- 服务器模式：HTTPS 生产拓扑 ----
        if [ -z "$DOMAIN" ]; then fail 3 '服务器模式需要 --domain <域名>'; fi
        if [ -z "$TLS_CERT" ] || [ ! -f "$TLS_CERT" ]; then fail 3 "服务器模式需要 --tls-cert <证书文件>（找不到: $TLS_CERT）"; fi
        if [ -z "$TLS_KEY" ] || [ ! -f "$TLS_KEY" ]; then fail 3 "服务器模式需要 --tls-key <私钥文件>（找不到: $TLS_KEY）"; fi
        local https_port="${HTTP_PORT:-443}"
        local disk_avail; disk_avail="$(df -PG "$SCRIPT_DIR" | tail -1 | awk '{print $4}')"
        if [ "${disk_avail:-0}" -le 5242880 ]; then
            fail 3 "磁盘剩余空间不足 5GB（当前 ${disk_avail}KB）。部署镜像与数据需要至少 5GB。"
        fi
        if port_busy "$https_port"; then
            fail 4 "端口 $https_port 已被占用。请换用 --port <端口> 或释放该端口。"
        fi
        if [ -f "$PROD_ENV_FILE" ]; then
            log '已存在 prod.env，保留现有凭据与数据配置（幂等安装）。'
            if [ -n "$HTTP_PORT" ]; then set_env_value "$PROD_ENV_FILE" HTTPS_PORT "$HTTP_PORT"; fi
        else
            local images backend frontend
            images="$(resolve_images)"
            backend="${images%% *}"; frontend="${images##* }"
            TLS_CERT="$(cd "$(dirname "$TLS_CERT")" && pwd)/$(basename "$TLS_CERT")"
            TLS_KEY="$(cd "$(dirname "$TLS_KEY")" && pwd)/$(basename "$TLS_KEY")"
            cat > "$PROD_ENV_FILE" <<EOF
APP_IMAGE=$backend
PROXY_IMAGE=$frontend
PUBLIC_BIND_IP=0.0.0.0
HTTPS_PORT=$https_port
TLS_CERT_FILE=$TLS_CERT
TLS_KEY_FILE=$TLS_KEY
AUTH_ALLOWED_ORIGINS=https://$DOMAIN
MINIO_PUBLIC_ENDPOINT=https://$DOMAIN
AUTH_PUBLIC_REGISTRATION_ENABLED=true
POSTGRES_DB=ai_collab
POSTGRES_USER=ai_collab
POSTGRES_PASSWORD=$(new_random 24)
REDIS_PASSWORD=$(new_random 24)
MINIO_ROOT_USER=collab-admin
MINIO_ROOT_PASSWORD=$(new_random 24)
MINIO_ACCESS_KEY=collab-app
MINIO_SECRET_KEY=$(new_random 24)
MINIO_BUCKET=ai-collab
JWT_SECRET=$(new_random 48)
MODEL_CONFIG_MASTER_KEY=$(new_random 32)
AGENT_RUNTIME_ALLOWED=true
EOF
            chmod 600 "$PROD_ENV_FILE"
            log "已生成 $PROD_ENV_FILE（含随机凭据，权限 600；文件已在 .gitignore 中排除，请勿提交或外传）。"
        fi
        log '校验生产 Compose 配置渲染...'
        docker compose --env-file "$PROD_ENV_FILE" -f "$COMPOSE_PROD" config -q || fail 3 '生产配置渲染失败；请检查 prod.env 完整性'
        log '启动生产拓扑（postgres/redis/minio/app/proxy）...'
        docker compose --env-file "$PROD_ENV_FILE" -f "$COMPOSE_PROD" up -d || fail 1 'compose up 失败；可用 logs 命令查看详情'
        setup_server_storage
        if wait_entry_healthy 420; then
            log ''
            log "服务器安装完成！访问地址: $(resolve_entry)"
            log "下一步：创建首位管理员 ->  ./install.sh bootstrap-admin --server --domain $DOMAIN"
            log '定时备份建议：ai-collab-deploy/backup/systemd/（见 backup/README.md）'
        else
            docker compose --env-file "$PROD_ENV_FILE" -f "$COMPOSE_PROD" logs --tail 50 app || true
            fail 1 '应用健康检查超时；容器已保留供诊断。'
        fi
        return 0
    fi
    # ---- 本地模式 ----
    warn_arch
    if [ ! -f "$ENV_FILE" ]; then
        local target_port="${HTTP_PORT:-8080}"
        if port_busy "$target_port"; then
            fail 4 "端口 $target_port 已被占用。请换用其他端口： ./install.sh install --port 8090"
        fi
    fi
    local images backend frontend
    images="$(resolve_images)"
    backend="${images%% *}"; frontend="${images##* }"
    if [ -f "$ENV_FILE" ]; then
        log '已存在 local.env，保留现有凭据与数据配置（幂等安装）。'
        if [ -n "$HTTP_PORT" ]; then set_env_value "$ENV_FILE" HTTP_PORT "$HTTP_PORT"; fi
        set_env_value "$ENV_FILE" APP_IMAGE "$backend"
        set_env_value "$ENV_FILE" WEB_IMAGE "$frontend"
    else
        cat > "$ENV_FILE" <<EOF
APP_IMAGE=$backend
WEB_IMAGE=$frontend
HTTP_PORT=${HTTP_PORT:-8080}
POSTGRES_DB=ai_collab
POSTGRES_USER=ai_collab
POSTGRES_PASSWORD=$(new_random 24)
REDIS_PASSWORD=$(new_random 24)
MINIO_ROOT_USER=ai-collab
MINIO_ROOT_PASSWORD=$(new_random 24)
MINIO_BUCKET=ai-collab
JWT_SECRET=$(new_random 48)
MODEL_CONFIG_MASTER_KEY=$(new_random 32)
AUTH_PUBLIC_REGISTRATION_ENABLED=true
AGENT_RUNTIME_ALLOWED=true
BACKUP_RETENTION_DAYS=14
EOF
        chmod 600 "$ENV_FILE"
        log "已生成 $ENV_FILE（含随机凭据，权限 600；文件已在 .gitignore 中排除，请勿提交或外传）。"
    fi
    log '启动服务（postgres/redis/minio/app/web）...'
    compose_cmd up -d >/dev/null || fail 1 'compose up 失败；可用 logs 命令查看详情'
    if wait_entry_healthy 420; then
        log ''
        log "安装完成！访问地址: $(resolve_entry)"
        log '下一步：创建首位管理员 ->  ./install.sh bootstrap-admin'
        log '日常维护：status | stop | up | logs | backup | doctor（详见 docs/deployment/local-install.md）'
    else
        compose_cmd logs --tail 50 app || true
        fail 1 '应用健康检查超时；容器已保留供诊断，可运行 ./install.sh doctor'
    fi
}

cmd_upgrade() {
    if [ -z "$(env_key APP_IMAGE)" ]; then fail 3 '缺少部署配置；请先执行 install'; fi
    local old_backend old_frontend new_backend new_frontend env_file web_key
    old_backend="$(env_key APP_IMAGE)"
    old_frontend="$(env_key WEB_IMAGE)"
    if [ -z "$old_frontend" ]; then old_frontend="$(env_key PROXY_IMAGE)"; fi
    log '== 受控升级：先备份，再更新镜像，失败自动回滚 =='
    log '[1/6] 升级前备份...'
    run_backup >/dev/null
    local latest_dump; latest_dump="$(ls -t "$BACKUP_ROOT"/postgres/pg_*.dump 2>/dev/null | head -1)"
    if [ -z "$latest_dump" ] || [ ! -s "$latest_dump" ]; then
        fail 1 '备份核验失败（无 dump 产物），升级中止（数据未变动）'
    fi
    log "[2/6] 备份核验通过: $(basename "$latest_dump")"
    log '[3/6] 解析新镜像...'
    new_backend="$IMAGE"; new_frontend="$FRONTEND_IMAGE"
    if [ -z "$new_backend" ]; then
        if try_pull "ghcr.io/bangshou1st/ai-collab-backend:$TAG"; then
            new_backend="ghcr.io/bangshou1st/ai-collab-backend:$TAG"
        else
            new_backend="$old_backend"
        fi
    fi
    if [ -z "$new_frontend" ]; then
        if try_pull "ghcr.io/bangshou1st/ai-collab-frontend:$TAG"; then
            new_frontend="ghcr.io/bangshou1st/ai-collab-frontend:$TAG"
        else
            new_frontend="$old_frontend"
        fi
    fi
    if [ "$new_backend" = "$old_backend" ] && [ "$new_frontend" = "$old_frontend" ]; then
        log '镜像无变化，无需升级。'
        return 0
    fi
    local pg_id; pg_id="$(service_id postgres)"
    local fly_before
    fly_before="$(docker exec "$pg_id" psql -U "$(env_key POSTGRES_USER)" -d "$(env_key POSTGRES_DB)" -t -A -c 'SELECT max(installed_rank) FROM flyway_schema_history;' 2>/dev/null || true)"
    log "[4/6] 更新镜像: $new_backend / $new_frontend"
    env_file="$(env_file_path)"
    web_key=WEB_IMAGE
    if [ "$SERVER_MODE" = 1 ]; then web_key=PROXY_IMAGE; fi
    set_env_value "$env_file" APP_IMAGE "$new_backend"
    set_env_value "$env_file" "$web_key" "$new_frontend"
    local upgrade_failed=0
    compose_cmd up -d >/dev/null || upgrade_failed=1
    if [ "$upgrade_failed" = 0 ]; then
        if ! wait_entry_healthy 420; then upgrade_failed=1; fi
    fi
    if [ "$upgrade_failed" = 1 ]; then
        log '升级失败，回滚到升级前镜像（数据卷不动）...'
        set_env_value "$env_file" APP_IMAGE "$old_backend"
        set_env_value "$env_file" "$web_key" "$old_frontend"
        local rolled=OK
        compose_cmd up -d >/dev/null || rolled=FAILED
        if ! wait_entry_healthy 300; then rolled=FAILED; fi
        printf '%s RESULT=ROLLBACK from=%s to=%s rolled_back=%s\n' \
            "$(date -Iseconds)" "$new_backend" "$old_backend" "$rolled" >> "$BACKUP_ROOT/upgrade-history.log"
        fail 10 "升级失败已回滚（应用恢复=$rolled）。注意：若新版本已执行数据库迁移，旧镜像可能无法完全兼容；数据库迁移不可自动回退，恢复请使用步骤 1 的备份（见 docs/deployment/server-install.md 升级章节）。"
    fi
    local fly_after
    fly_after="$(docker exec "$pg_id" psql -U "$(env_key POSTGRES_USER)" -d "$(env_key POSTGRES_DB)" -t -A -c 'SELECT max(installed_rank) FROM flyway_schema_history;' 2>/dev/null || true)"
    log '[5/6] 健康检查与冒烟通过。'
    if [ "$fly_before" != "$fly_after" ]; then
        log "WARN: 检测到数据库迁移已推进（Flyway installed_rank $fly_before -> $fly_after）：数据库结构升级不可自动回退，请保留步骤 1 的备份。"
    fi
    log '[6/6] 记录升级历史。'
    mkdir -p "$BACKUP_ROOT"
    printf '%s RESULT=OK from=%s/%s to=%s/%s flyway=%s->%s backup=%s\n' \
        "$(date -Iseconds)" "$old_backend" "$old_frontend" "$new_backend" "$new_frontend" \
        "$fly_before" "$fly_after" "$(basename "$latest_dump")" >> "$BACKUP_ROOT/upgrade-history.log"
    log "升级完成: $(resolve_entry)"
}

cmd_restore() {
    if [ -z "$DUMP" ] && [ -z "$MINIO_ARCHIVE" ]; then
        fail 3 'restore 需要 --dump <pg 备份文件> 和/或 --minio <备份目录>'
    fi
    local pg_id; pg_id="$(service_id postgres)"
    if [ -z "$pg_id" ]; then fail 1 'PostgreSQL 容器未运行'; fi
    log '恢复会覆盖当前数据，且先停止 app 容器以避免并发写入。'
    compose_cmd stop app >/dev/null || fail 1 '停止 app 失败'
    if [ -n "$DUMP" ]; then
        if [ ! -f "$DUMP" ]; then fail 3 "找不到备份文件: $DUMP"; fi
        if [ -f "$DUMP.sha256" ]; then
            (cd "$(dirname "$DUMP")" && sha256sum -c "$(basename "$DUMP").sha256" >/dev/null) \
                || fail 1 'SHA256 校验失败：备份文件已损坏或不匹配'
        fi
        POSTGRES_CONTAINER="$pg_id" POSTGRES_DB="$(env_key POSTGRES_DB)" POSTGRES_USER="$(env_key POSTGRES_USER)" \
            POSTGRES_PASSWORD="$(env_key POSTGRES_PASSWORD)" ALLOW_SOURCE_OVERWRITE=1 \
            bash "$SCRIPT_DIR/backup/restore-postgres.sh" --file "$DUMP" --container "$pg_id" --db "$(env_key POSTGRES_DB)"
        log 'PostgreSQL 恢复完成（已通过恢复脚本自检）。'
    fi
    if [ -n "$MINIO_ARCHIVE" ]; then
        local minio_id; minio_id="$(service_id minio)"
        if [ -z "$minio_id" ]; then fail 1 'MinIO 容器未运行'; fi
        if [ ! -d "$MINIO_ARCHIVE" ]; then fail 3 "找不到 MinIO 备份目录: $MINIO_ARCHIVE"; fi
        MINIO_CONTAINER="$minio_id" MINIO_IMAGE="$(docker inspect --format '{{.Config.Image}}' "$minio_id")" \
            MINIO_ROOT_USER="$(env_key MINIO_ROOT_USER)" MINIO_ROOT_PASSWORD="$(env_key MINIO_ROOT_PASSWORD)" \
            MINIO_BUCKET="$(env_key MINIO_BUCKET)" ALLOW_SOURCE_OVERWRITE=1 \
            bash "$SCRIPT_DIR/backup/restore-minio.sh" --dir "$MINIO_ARCHIVE" --container "$minio_id" --bucket "$(env_key MINIO_BUCKET)"
        log 'MinIO 恢复完成。'
    fi
    log '重新启动 app...'
    compose_cmd up -d >/dev/null || fail 1 'app 重启失败'
    if wait_entry_healthy 300; then
        log "恢复完成，服务已恢复: $(resolve_entry)"
    else
        fail 1 '恢复后应用健康检查未通过，请查看 logs'
    fi
}

case "$COMMAND" in
    help) usage ;;
    install) cmd_install ;;
    bootstrap-admin)
        assert_docker
        bootstrap_admin
        ;;
    up)
        assert_docker
        compose_cmd up -d || fail 1 'compose up 失败'
        if wait_entry_healthy 300; then log "已启动: $(resolve_entry)"; else fail 1 '启动后健康检查未通过；请用 logs 查看'; fi
        ;;
    stop)
        log '停止服务（数据卷保留，不会删除任何数据）...'
        compose_cmd stop || fail 1 '停止失败'
        log '已停止。数据保留在 Docker 卷中，下次 up/install 自动恢复。'
        ;;
    restart)
        compose_cmd restart || fail 1 '重启失败'
        if wait_entry_healthy 300; then log "已重启: $(resolve_entry)"; else fail 1 '重启后健康检查未通过'; fi
        ;;
    status)
        for svc in postgres redis minio app web proxy; do
            h="$(container_health "$svc")"
            if [ "$h" != 'absent' ]; then printf '%-9s %s\n' "$svc" "$h"; fi
        done
        if wait_entry_healthy 5; then
            log "Web 入口正常: $(resolve_entry)"
        else
            log 'Web 入口暂不可达（服务未启动或仍在初始化）'
        fi
        ;;
    logs)
        log_args=(logs --tail "$TAIL")
        if [ "$FOLLOW" = 1 ]; then log_args+=(-f); fi
        compose_cmd "${log_args[@]}"
        ;;
    doctor)
        assert_docker
        log "Docker 引擎: OK ($(docker version --format '{{.Server.Version}}'))"
        warn_arch
        env_f="$(env_file_path)"
        if [ ! -f "$env_f" ]; then
            log "WARN: $env_f 不存在（尚未安装）。执行 ./install.sh install 开始安装。"
        else
            for key in APP_IMAGE JWT_SECRET MODEL_CONFIG_MASTER_KEY; do
                v="$(read_env "$env_f" "$key")"
                case "$v" in ''|REQUIRED*) log "WARN: $env_f 配置不完整：$key" ;; esac
            done
        fi
        for svc in postgres redis minio app web proxy; do
            h="$(container_health "$svc")"
            if [ "$h" = 'absent' ]; then continue; fi
            case "$h" in
                healthy|running) log "容器 $svc: $h" ;;
                *) log "WARN: 容器 $svc: $h" ;;
            esac
        done
        df -h "$SCRIPT_DIR" | tail -1 | awk '{print "[install] 磁盘: 总 " $2 " 已用 " $3 " 可用 " $4}'
        docker system df || true
        log 'doctor 完成。'
        ;;
    backup)
        assert_docker
        run_backup
        ;;
    restore)
        assert_docker
        cmd_restore
        ;;
    upgrade)
        assert_docker
        cmd_upgrade
        ;;
    version)
        rev="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || true)"
        log "仓库基线: ${rev:-非 Git 目录}"
        for svc in app web proxy; do
            id="$(service_id "$svc" 2>/dev/null || true)"
            if [ -n "$id" ]; then
                docker inspect --format '{{.Name}} image={{.Config.Image}} imageID={{.Image}} revision={{index .Config.Labels "org.opencontainers.image.revision"}}' "$id"
            fi
        done
        log "访问地址: $(resolve_entry)"
        ;;
    uninstall)
        log '停止并移除容器与网络（数据卷默认保留）...'
        compose_cmd down || fail 1 '卸载失败'
        log '已卸载容器。数据卷已保留（可用于重装恢复）；确认后可手动删除：'
        docker volume ls --format '{{.Name}}' | grep -E '^ai-collab(-local)?_' || true
        ;;
    *) usage ;;
esac
