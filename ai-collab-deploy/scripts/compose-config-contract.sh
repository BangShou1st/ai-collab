#!/usr/bin/env bash
# ==============================================================================
# P5-DEPLOYMENT-GATE-01 配置契约回归：验证 docker compose 对 app 服务环境变量的
# 真实展开结果（渲染后的 config，而非 YAML 静态文本）。
#
# 覆盖场景：
#   1) 正向：合成测试值能原样透传到 app 容器环境；
#   2) 默认：未配置时使用与 application.yml 一致的默认值；
#   3) 负向：仅基础设施变量时无未定义变量报错，且历史遗留的
#      EMBEDDING_*/PLANNING_* 变量（已无运行时消费点）不被注入；
#   4) 安全基线：Refresh Cookie Secure 默认不被放松；
#   5) 特殊字符/URL 形态的值原样透传。
#
# 仅渲染 Compose 配置，不启动容器、不使用任何真实凭据（全部合成值）。
# 运行环境：本机 Git Bash 或 CI ubuntu-latest（需要 docker、node）。
# ==============================================================================
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
COMPOSE_FILE="$ROOT_DIR/ai-collab-deploy/docker-compose.yml"
ENV_EXAMPLE="$ROOT_DIR/.env.example"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

# 渲染 app 服务 environment，输出 KEY=VALUE 行
render_app_env() {
  docker compose --env-file "$1" -f "$COMPOSE_FILE" config --format json 2>/dev/null \
    | node -e '
        let raw = "";
        process.stdin.on("data", (c) => (raw += c));
        process.stdin.on("end", () => {
          const cfg = JSON.parse(raw);
          const env = (cfg.services && cfg.services.app && cfg.services.app.environment) || {};
          for (const [k, v] of Object.entries(env)) {
            process.stdout.write(`${k}=${v ?? ""}\n`);
          }
        });
      '
}

expect_kv() { # $1=渲染输出 $2=KEY $3=期望值
  local actual
  actual=$(grep -E "^$2=" <<<"$1" | head -1 | cut -d= -f2-)
  [ "$actual" = "$3" ] || fail "$2 期望 [$3]，实际 [$actual]"
}

expect_absent() { # $1=渲染输出 $2=KEY（期望不出现）
  grep -qE "^$2=" <<<"$1" && fail "遗留变量 $2 不应注入 app 容器" || true
}

# --- 合成基础环境（不含任何真实凭据）-----------------------------------------
cat > "$TMP_DIR/base.env" <<'EOF'
POSTGRES_DB=gate01db
POSTGRES_USER=gate01
POSTGRES_PASSWORD=gate01-synthetic
REDIS_PASSWORD=gate01-synthetic
MINIO_ROOT_USER=gate01
MINIO_ROOT_PASSWORD=gate01-synthetic-minio
MINIO_BUCKET=gate01-bucket
JWT_SECRET=gate01-synthetic-jwt-secret-0123456789abcdef
AUTH_PUBLIC_REGISTRATION_ENABLED=false
MODEL_CONFIG_MASTER_KEY=gate01-synthetic-master-key-0123456789abcdef
CHAT_ENABLED=false
CHAT_PROVIDER=openai-compatible
CHAT_BASE_URL=https://chat.invalid
CHAT_API_KEY=
CHAT_MODEL=synthetic-model
AGENT_ENABLED=false
AGENT_MCP_ALLOWED_HOSTS=
KNOWLEDGE_RATE_LIMIT_PER_USER_HOUR=60
PLANNING_GENERATION_LIMIT_PER_USER_HOUR=60
AUTH_ALLOWED_ORIGINS=http://localhost:5173
EOF

echo "== 场景 1：正向——合成 Planning/Embedding 配置值原样透传 =="
cat > "$TMP_DIR/positive.env" <<'EOF'
PLANNING_MAX_OUTPUT_TOKENS=7777
PLANNING_MAX_SOURCES=9
PLANNING_SOURCE_CODEPOINT_BUDGET=22222
PLANNING_RETRIEVAL_THRESHOLD=0.7
PLANNING_EXECUTOR_CORE_SIZE=3
PLANNING_EXECUTOR_MAX_SIZE=6
PLANNING_EXECUTOR_QUEUE_CAPACITY=33
EMBEDDING_LOCAL_ALLOWED_TARGETS=ollama.example.internal:11434
EOF
cat "$TMP_DIR/base.env" "$TMP_DIR/positive.env" > "$TMP_DIR/case1.env"
POS_ENV=$(render_app_env "$TMP_DIR/case1.env")
expect_kv "$POS_ENV" PLANNING_MAX_OUTPUT_TOKENS 7777
expect_kv "$POS_ENV" PLANNING_MAX_SOURCES 9
expect_kv "$POS_ENV" PLANNING_SOURCE_CODEPOINT_BUDGET 22222
expect_kv "$POS_ENV" PLANNING_RETRIEVAL_THRESHOLD 0.7
expect_kv "$POS_ENV" PLANNING_EXECUTOR_CORE_SIZE 3
expect_kv "$POS_ENV" PLANNING_EXECUTOR_MAX_SIZE 6
expect_kv "$POS_ENV" PLANNING_EXECUTOR_QUEUE_CAPACITY 33
expect_kv "$POS_ENV" EMBEDDING_LOCAL_ALLOWED_TARGETS ollama.example.internal:11434
echo "-- 通过"

echo "== 场景 2：默认值——仅基础环境变量时使用 application.yml 同款默认 =="
DEF_ENV=$(render_app_env "$TMP_DIR/base.env")
expect_kv "$DEF_ENV" PLANNING_MAX_OUTPUT_TOKENS 6000
expect_kv "$DEF_ENV" PLANNING_MAX_SOURCES 12
expect_kv "$DEF_ENV" PLANNING_SOURCE_CODEPOINT_BUDGET 16000
expect_kv "$DEF_ENV" PLANNING_RETRIEVAL_THRESHOLD 0.55
expect_kv "$DEF_ENV" PLANNING_EXECUTOR_CORE_SIZE 2
expect_kv "$DEF_ENV" PLANNING_EXECUTOR_MAX_SIZE 4
expect_kv "$DEF_ENV" PLANNING_EXECUTOR_QUEUE_CAPACITY 20
# 容器内访问宿主机 Ollama 必须用 host.docker.internal，而不是本机测试地址 127.0.0.1
expect_kv "$DEF_ENV" EMBEDDING_LOCAL_ALLOWED_TARGETS "host.docker.internal:11434"
echo "-- 通过"

echo "== 场景 3：负向——遗留变量不被注入、安全基线不放松 =="
for env_file in "$TMP_DIR/case1.env" "$TMP_DIR/base.env" "$ENV_EXAMPLE"; do
  RENDERED=$(render_app_env "$env_file")
  for legacy in EMBEDDING_ENABLED EMBEDDING_PROVIDER EMBEDDING_BASE_URL EMBEDDING_API_KEY \
                EMBEDDING_MODEL EMBEDDING_DIMENSIONS EMBEDDING_BATCH_SIZE EMBEDDING_PATH \
                PLANNING_ENABLED PLANNING_PROVIDER PLANNING_BASE_URL PLANNING_API_KEY \
                PLANNING_MODEL PLANNING_TEMPERATURE; do
    expect_absent "$RENDERED" "$legacy"
  done
  expect_kv "$RENDERED" REFRESH_COOKIE_SECURE true
  expect_kv "$RENDERED" REFRESH_COOKIE_ALLOW_INSECURE false
done
echo "-- 通过"

echo "== 场景 4：特殊字符/URL 形态的值原样透传 =="
cat > "$TMP_DIR/urls.env" <<'EOF'
PLANNING_MAX_OUTPUT_TOKENS=64000
EMBEDDING_LOCAL_ALLOWED_TARGETS=http://ollama.internal:11434,[::1]:11434
EOF
cat "$TMP_DIR/base.env" "$TMP_DIR/urls.env" > "$TMP_DIR/case4.env"
URL_ENV=$(render_app_env "$TMP_DIR/case4.env")
expect_kv "$URL_ENV" EMBEDDING_LOCAL_ALLOWED_TARGETS "http://ollama.internal:11434,[::1]:11434"
expect_kv "$URL_ENV" PLANNING_MAX_OUTPUT_TOKENS 64000
echo "-- 通过"

echo "compose-config-contract: 全部场景通过"
