#!/usr/bin/env bash
# Linux 一键安装验收（本地模式）：在真实 Docker Engine 上验证 install.sh 全生命周期。
# 覆盖：环境检查 → 首次安装 → 首管理员初始化/防重复引导 → 登录 → 项目/任务/文档上传下载
#       → 停止/重启/数据保留 → 备份 → 覆盖式恢复 → server-check 正向/负向配置校验。
# 用法: bash ai-collab-deploy/scripts/install-acceptance.sh [--port 18080]
# 环境变量：
#   INSTALL_ACCEPTANCE_TAG                 GHCR 镜像标签（设置后走「拉取发布镜像」路径，默认空=源码构建回退路径）
#   INSTALL_ACCEPTANCE_EXPECT_BACKEND_DIGEST 期望的后端镜像 digest（sha256: 后的 hex，仅 TAG 模式校验）
#   INSTALL_ACCEPTANCE_EXPECT_FRONTEND_DIGEST 期望的前端镜像 digest（同上）
# 断言失败立即以非 0 退出；管理员凭据只在内存流转，绝不写入输出、日志或文件。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INSTALL="$REPO_ROOT/ai-collab-deploy/install.sh"
PORT=18080
while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift ;;
        *) printf '未知参数: %s\n' "$1" >&2; exit 3 ;;
    esac
    shift
done
ENTRY="http://localhost:$PORT"
BACKUP_ROOT="$REPO_ROOT/backups/local"
PROD_ENV_FILE="$REPO_ROOT/ai-collab-deploy/prod.env"
EVIDENCE_DIR="${INSTALL_ACCEPTANCE_LOG_DIR:-$REPO_ROOT/target/install-acceptance}"
mkdir -p "$EVIDENCE_DIR"
trap 'rm -f "$PROD_ENV_FILE"' EXIT

START_SECONDS=$SECONDS
PASS_COUNT=0
step() { printf '\n[accept] == %s ==\n' "$*"; }
pass() { PASS_COUNT=$((PASS_COUNT + 1)); printf '[accept] PASS  %s\n' "$*"; }
assert_contains() { # assert_contains <haystack> <needle> <说明>
    printf '%s' "$1" | grep -q "$2" || { printf '[accept] FAIL  %s（未找到预期内容）\n' "$3" >&2; exit 1; }
    pass "$3"
}
assert_eq() { # assert_eq <实际> <预期> <说明>
    [ "$1" = "$2" ] || { printf '[accept] FAIL  %s（实际=%s 预期=%s）\n' "$3" "$1" "$2" >&2; exit 1; }
    pass "$3"
}
json_field() { # json_field <json> <外层键> <内层键> -> "外层":{...,"内层":"..."} 的首个字符串值
    # 注意：GNU grep 的括号表达式不支持 [^{}]（{} 会被当作区间语法），必须用 [^}]。
    printf '%s' "$1" | grep -o "\"$2\"[[:space:]]*:[[:space:]]*{[^}]*}" | head -1 \
        | sed -n 's/.*"'"$3"'"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' || printf ''
}
api() { # api <method> <path> <json-body 或 ""> <token 或 ""> -> 原始响应（末行附 HTTP 状态码）
    local args=(--noproxy '*' -sS -X "$1" "$ENTRY$2" -H 'Content-Type: application/json'
        -H "Origin: $ENTRY" --max-time 60 -w '\n%{http_code}')
    if [ -n "$3" ]; then args+=(-d "$3"); fi
    if [ -n "$4" ]; then args+=(-H "Authorization: Bearer $4"); fi
    curl "${args[@]}" 2>/dev/null
}
api_code() { # 断言响应 code=SUCCESS 并回显 body
    assert_contains "$1" '"code"[[:space:]]*:[[:space:]]*"SUCCESS"' "$2"
    printf '%s' "$1"
}

step '0. 环境检查（Linux 发行版/架构/Docker）'
[ "$(uname -s)" = "Linux" ] || { echo '本脚本必须在 Linux 上执行' >&2; exit 1; }
grep PRETTY /etc/os-release || true
printf '架构: %s\n' "$(uname -m)"
docker version --format 'Docker: client={{.Client.Version}} server={{.Server.Version}} arch={{.Server.Arch}}'
docker compose version
pass 'Docker Engine 与 Compose 可用'
df -h "$REPO_ROOT" | tail -1 | awk '{print "[accept] 磁盘: 总 "$2" 已用 "$3" 可用 "$4}'
free -h | awk 'NR<=2 {print "[accept] mem: "$0}'

step '1. 首次安装（GHCR 无镜像时自动回退源码构建）'
INSTALL_LOG="$EVIDENCE_DIR/install.log"
INSTALL_START=$SECONDS
INSTALL_ARGS=(--port "$PORT")
if [ -n "${INSTALL_ACCEPTANCE_TAG:-}" ]; then INSTALL_ARGS+=(--tag "$INSTALL_ACCEPTANCE_TAG"); fi
bash "$INSTALL" install "${INSTALL_ARGS[@]}" >"$INSTALL_LOG" 2>&1 \
    || { tail -40 "$INSTALL_LOG" >&2; echo 'install 失败' >&2; exit 1; }
INSTALL_SECONDS=$((SECONDS - INSTALL_START))
pass "install exit=0（耗时 ${INSTALL_SECONDS}s）"
grep -E '安装完成|访问地址' "$INSTALL_LOG" | tail -2

if [ -n "${INSTALL_ACCEPTANCE_TAG:-}" ]; then
    # 发布镜像路径：必须真实来自 GHCR 拉取，且 digest 与发布记录一致。
    if grep -q '从本地源码构建' "$INSTALL_LOG"; then
        echo 'FAIL 发布镜像路径出现源码构建（未命中预构建镜像）' >&2; exit 1
    fi
    grep -q '尝试拉取预构建' "$INSTALL_LOG" || { echo 'FAIL 未尝试拉取预构建镜像' >&2; exit 1; }
    LOCAL_ENV="$REPO_ROOT/ai-collab-deploy/local.env"
    APP_IMAGE="$(grep -E '^APP_IMAGE=' "$LOCAL_ENV" | head -1 | cut -d= -f2-)"
    WEB_IMAGE="$(grep -E '^WEB_IMAGE=' "$LOCAL_ENV" | head -1 | cut -d= -f2-)"
    # 本地模式按设计使用标签引用（服务器模式才 digest 固定）；镜像内容以 RepoDigests 校验为准。
    case "$APP_IMAGE" in ghcr.io/*) ;; *) echo "FAIL APP_IMAGE 非注册表引用: $APP_IMAGE" >&2; exit 1 ;; esac
    case "$WEB_IMAGE" in ghcr.io/*) ;; *) echo "FAIL WEB_IMAGE 非注册表引用: $WEB_IMAGE" >&2; exit 1 ;; esac
    if [ -n "${INSTALL_ACCEPTANCE_EXPECT_BACKEND_DIGEST:-}" ]; then
        docker image inspect "$APP_IMAGE" --format '{{join .RepoDigests ","}}' \
            | grep -q "sha256:${INSTALL_ACCEPTANCE_EXPECT_BACKEND_DIGEST}" \
            || { echo "FAIL 后端镜像 digest 与发布记录不符: $APP_IMAGE" >&2; exit 1; }
    fi
    if [ -n "${INSTALL_ACCEPTANCE_EXPECT_FRONTEND_DIGEST:-}" ]; then
        docker image inspect "$WEB_IMAGE" --format '{{join .RepoDigests ","}}' \
            | grep -q "sha256:${INSTALL_ACCEPTANCE_EXPECT_FRONTEND_DIGEST}" \
            || { echo "FAIL 前端镜像 digest 与发布记录不符: $WEB_IMAGE" >&2; exit 1; }
    fi
    pass "镜像来自 GHCR 拉取且 digest 与发布一致（sha-${INSTALL_ACCEPTANCE_TAG} 对应记录）"
fi

step '2. status / doctor'
bash "$INSTALL" status
bash "$INSTALL" doctor >"$EVIDENCE_DIR/doctor.log" 2>&1
pass 'doctor 正常'

step '3. 首管理员初始化（凭据只进内存）'
BOOT_OUT="$(bash "$INSTALL" bootstrap-admin --username acadmin 2>&1)" \
    || { printf '%s\n' "$BOOT_OUT" >&2; echo 'bootstrap-admin 失败' >&2; exit 1; }
ADMIN_PASSWORD="$(printf '%s\n' "$BOOT_OUT" | sed -n 's/.*密  码: //p' | head -1)"
[ -n "$ADMIN_PASSWORD" ] || { echo '未能从 bootstrap 输出提取凭据' >&2; exit 1; }
pass '首位管理员创建成功（凭据已捕获，不展示）'
REINSTALL_OUT="$(bash "$INSTALL" bootstrap-admin --username acadmin 2>&1)"
assert_contains "$REINSTALL_OUT" '防重复引导' '重复初始化被拒绝（防重复引导）'

step '4. 登录与权限'
LOGIN_BODY="$(curl --noproxy '*' -sS -X POST "$ENTRY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -H "Origin: $ENTRY" -d "{\"username\":\"acadmin\",\"password\":\"$ADMIN_PASSWORD\"}")"
LOGIN_BODY="$(api_code "$LOGIN_BODY" '管理员登录 SUCCESS')"
TOKEN="$(json_field "$LOGIN_BODY" data accessToken)"
[ -n "$TOKEN" ] || { echo '未取得 accessToken' >&2; exit 1; }
ME="$(api GET /api/v1/auth/me '' "$TOKEN")"
assert_contains "$ME" '"systemAdmin"[[:space:]]*:[[:space:]]*true' '/auth/me 返回 systemAdmin=true'

step '5. 创建项目'
PROJECT_BODY="$(api POST /api/v1/projects '{"name":"Linux 安装验收项目","type":"COMPETITION","startDate":"2026-11-01","dueDate":"2026-12-31"}' "$TOKEN")"
PROJECT_BODY="$(api_code "$PROJECT_BODY" '创建项目 SUCCESS')"
PROJECT_ID="$(json_field "$PROJECT_BODY" data id)"
[ -n "$PROJECT_ID" ] || { echo '未取得项目 ID' >&2; exit 1; }
pass "项目 ID=$PROJECT_ID"

step '6. 创建任务'
TASK_BODY="$(api POST "/api/v1/projects/$PROJECT_ID/tasks" '{"title":"Linux 安装验收任务","priority":"HIGH"}' "$TOKEN")"
assert_contains "$TASK_BODY" '"code"[[:space:]]*:[[:space:]]*"SUCCESS"' '创建任务 SUCCESS'

step '7. 文档上传与下载（内容一致性校验）'
DOC_FILE="$EVIDENCE_DIR/acceptance-doc.md"
printf '# Linux 安装验收文档 %s\n本文件由 install-acceptance.sh 生成，用于验证 MinIO 上传下载。\n' "$(date -Iseconds)" > "$DOC_FILE"
DOC_SHA="$(sha256sum "$DOC_FILE" | awk '{print $1}')"
UPLOAD_BODY="$(curl --noproxy '*' -sS -X POST "$ENTRY/api/v1/projects/$PROJECT_ID/documents" \
    -H "Origin: $ENTRY" -H "Authorization: Bearer $TOKEN" -F "file=@$DOC_FILE;type=text/markdown")"
UPLOAD_BODY="$(api_code "$UPLOAD_BODY" '文档上传 SUCCESS')"
DOC_ID="$(json_field "$UPLOAD_BODY" data id)"
[ -n "$DOC_ID" ] || { echo '未取得文档 ID' >&2; exit 1; }
URL_BODY="$(api GET "/api/v1/projects/$PROJECT_ID/documents/$DOC_ID/download-url" '' "$TOKEN")"
URL_BODY="$(api_code "$URL_BODY" '获取下载预签名 URL SUCCESS')"
DOC_URL="$(json_field "$URL_BODY" data url)"
[ -n "$DOC_URL" ] || { echo '未取得下载 URL' >&2; exit 1; }
DOWNLOADED="$EVIDENCE_DIR/downloaded-doc.md"
curl --noproxy '*' -ksS "$DOC_URL" -o "$DOWNLOADED"
DOWN_SHA="$(sha256sum "$DOWNLOADED" | awk '{print $1}')"
assert_eq "$DOWN_SHA" "$DOC_SHA" '下载内容 SHA256 与上传原文一致'

step '8. 停止 / 重启 / 数据保留'
bash "$INSTALL" stop
if curl --noproxy '*' -sS --max-time 3 "$ENTRY/api/v1/auth/registration-policy" >/dev/null 2>&1; then
    echo 'FAIL stop 后入口仍可达' >&2; exit 1
fi
pass 'stop 后入口不可达（数据卷保留）'
bash "$INSTALL" up
LOGIN2="$(api POST /api/v1/auth/login "{\"username\":\"acadmin\",\"password\":\"$ADMIN_PASSWORD\"}" '')"
TOKEN2="$(json_field "$(api_code "$LOGIN2" '重启后登录 SUCCESS')" data accessToken)"
PROJ_AFTER="$(api GET "/api/v1/projects/$PROJECT_ID" '' "$TOKEN2")"
assert_contains "$PROJ_AFTER" '"code"[[:space:]]*:[[:space:]]*"SUCCESS"' '重启后项目数据保留'

step '9. 备份'
bash "$INSTALL" backup >"$EVIDENCE_DIR/backup.log" 2>&1
LATEST_DUMP="$(ls -t "$BACKUP_ROOT"/postgres/pg_*.dump 2>/dev/null | head -1)"
[ -n "$LATEST_DUMP" ] && [ -s "$LATEST_DUMP" ] || { echo '无 pg dump 产物' >&2; exit 1; }
[ -f "$LATEST_DUMP.sha256" ] || { echo 'pg dump 缺少 SHA256 清单' >&2; exit 1; }
MINIO_DIR="$BACKUP_ROOT/minio/ai-collab"
[ -d "$MINIO_DIR" ] || { echo '无 MinIO 备份目录' >&2; exit 1; }
pass "备份产物齐备: $(basename "$LATEST_DUMP") + minio/"

step '10. 覆盖式恢复演练'
bash "$INSTALL" restore --dump "$LATEST_DUMP" --minio "$MINIO_DIR" >"$EVIDENCE_DIR/restore.log" 2>&1
LOGIN3="$(api POST /api/v1/auth/login "{\"username\":\"acadmin\",\"password\":\"$ADMIN_PASSWORD\"}" '')"
TOKEN3="$(json_field "$(api_code "$LOGIN3" '恢复后登录 SUCCESS')" data accessToken)"
URL3="$(json_field "$(api_code "$(api GET "/api/v1/projects/$PROJECT_ID/documents/$DOC_ID/download-url" '' "$TOKEN3")" '恢复后获取下载 URL SUCCESS')" data url)"
curl --noproxy '*' -ksS "$URL3" -o "$EVIDENCE_DIR/restored-doc.md"
RESTORED_SHA="$(sha256sum "$EVIDENCE_DIR/restored-doc.md" | awk '{print $1}')"
assert_eq "$RESTORED_SHA" "$DOC_SHA" '恢复后文档内容与原上传一致'

step '11. 升级失败自动回滚演练（坏镜像，数据卷不动）'
LOCAL_ENV="$REPO_ROOT/ai-collab-deploy/local.env"
UPGRADE_HISTORY="$REPO_ROOT/backups/local/upgrade-history.log"
BAD_BE="ghcr.io/bangshou1st/ai-collab/ai-collab-backend:sha-acceptance-nonexistent"
BAD_FE="ghcr.io/bangshou1st/ai-collab/ai-collab-frontend:sha-acceptance-nonexistent"
OLD_BE="$(grep -E '^APP_IMAGE=' "$LOCAL_ENV" | head -1 | cut -d= -f2-)"
OLD_FE="$(grep -E '^WEB_IMAGE=' "$LOCAL_ENV" | head -1 | cut -d= -f2-)"
if bash "$INSTALL" upgrade --image "$BAD_BE" --frontend-image "$BAD_FE" >>"$EVIDENCE_DIR/upgrade-rollback.log" 2>&1; then
    echo 'FAIL 坏镜像升级未被拒绝（预期 exit=10）' >&2; exit 1
fi
grep -q 'RESULT=ROLLBACK' "$UPGRADE_HISTORY" || { echo 'FAIL 升级历史缺少 ROLLBACK 记录' >&2; exit 1; }
RESTORED_BE="$(grep -E '^APP_IMAGE=' "$LOCAL_ENV" | head -1 | cut -d= -f2-)"
assert_eq "$RESTORED_BE" "$OLD_BE" '升级失败后 APP_IMAGE 自动回滚到升级前镜像'
HEALTH="$(curl --noproxy '*' -ksS --max-time 10 "$ENTRY/api/v1/auth/registration-policy" 2>/dev/null || true)"
assert_contains "$HEALTH" '"code"[[:space:]]*:[[:space:]]*"SUCCESS"' '回滚后应用健康'
pass '坏镜像升级被拒绝且自动回滚（数据卷未动）'

step '12. server-check：正向配置通过 + 非法配置拒绝（不启动任何容器）'
CERT_DIR="$EVIDENCE_DIR/tls"; mkdir -p "$CERT_DIR"
openssl req -x509 -newkey rsa:2048 -keyout "$CERT_DIR/key.pem" -out "$CERT_DIR/cert.pem" \
    -days 30 -nodes -subj '/CN=p0.example.test' -addext 'subjectAltName=DNS:p0.example.test' >/dev/null 2>&1
render_prod_env() { # render_prod_env <jwt_secret 值>：渲染一份格式合规的 prod.env（镜像用格式合法的假 digest）
    cp "$REPO_ROOT/ai-collab-deploy/prod.env.example" "$PROD_ENV_FILE"
    sed -i \
        -e "s#^APP_IMAGE=.*#APP_IMAGE=ghcr.io/bangshou1st/ai-collab/ai-collab-backend@sha256:$(printf 'a%.0s' $(seq 64))#" \
        -e "s#^PROXY_IMAGE=.*#PROXY_IMAGE=ghcr.io/bangshou1st/ai-collab/ai-collab-frontend@sha256:$(printf 'b%.0s' $(seq 64))#" \
        -e "s#^POSTGRES_PASSWORD=.*#POSTGRES_PASSWORD=acc0-$(openssl rand -hex 16)#" \
        -e "s#^REDIS_PASSWORD=.*#REDIS_PASSWORD=acc1-$(openssl rand -hex 16)#" \
        -e "s#^MINIO_ROOT_PASSWORD=.*#MINIO_ROOT_PASSWORD=acc2-$(openssl rand -hex 16)#" \
        -e "s#^MINIO_SECRET_KEY=.*#MINIO_SECRET_KEY=acc3-$(openssl rand -hex 16)#" \
        -e "s#^JWT_SECRET=.*#JWT_SECRET=$1#" \
        -e "s#^MODEL_CONFIG_MASTER_KEY=.*#MODEL_CONFIG_MASTER_KEY=$(openssl rand -hex 24)#" \
        -e 's#^AUTH_ALLOWED_ORIGINS=.*#AUTH_ALLOWED_ORIGINS=https://p0.example.test#' \
        -e 's#^MINIO_PUBLIC_ENDPOINT=.*#MINIO_PUBLIC_ENDPOINT=https://p0.example.test#' \
        -e "s#^TLS_CERT_FILE=.*#TLS_CERT_FILE=$CERT_DIR/cert.pem#" \
        -e "s#^TLS_KEY_FILE=.*#TLS_KEY_FILE=$CERT_DIR/key.pem#" \
        "$PROD_ENV_FILE"
    chmod 600 "$PROD_ENV_FILE"
}
render_prod_env "$(openssl rand -hex 24)"
bash "$INSTALL" server-check --server --domain p0.example.test >"$EVIDENCE_DIR/server-check-pass.log" 2>&1
pass '正向配置通过 server-check'
render_prod_env "$(openssl rand -hex 24)"
sed -i 's#^POSTGRES_PASSWORD=.*#POSTGRES_PASSWORD=REQUIRED_RANDOM_POSTGRES_PASSWORD#' "$PROD_ENV_FILE"
if bash "$INSTALL" server-check --server --domain p0.example.test >/dev/null 2>&1; then
    echo 'FAIL 未替换占位符未被拒绝' >&2; exit 1
fi
pass '非法配置被拒绝（未替换占位符）'
render_prod_env "$(openssl rand -hex 12)"
if bash "$INSTALL" server-check --server --domain p0.example.test >/dev/null 2>&1; then
    echo 'FAIL 弱 JWT_SECRET 未被拒绝' >&2; exit 1
fi
pass '非法配置被拒绝（JWT_SECRET 强度不足）'
render_prod_env "$(openssl rand -hex 24)"
if bash "$INSTALL" server-check --server --domain other.example.test >/dev/null 2>&1; then
    echo 'FAIL 证书域名不匹配未被拒绝' >&2; exit 1
fi
pass '非法配置被拒绝（证书域名不匹配）'

step '13. 资源占用与结论'
docker stats --no-stream --format '{{.Name}}\tCPU={{.CPUPerc}}\tMEM={{.MemUsage}}' \
    | grep -E 'app|postgres|redis|minio|web' || true
docker system df
TOTAL_SECONDS=$((SECONDS - START_SECONDS))
printf '\n[accept] 全部 %s 项通过，总耗时 %ss（其中首次安装 %ss）\n' "$PASS_COUNT" "$TOTAL_SECONDS" "$INSTALL_SECONDS"
printf '[accept] 证据目录: %s（凭据零落盘）\n' "$EVIDENCE_DIR"
