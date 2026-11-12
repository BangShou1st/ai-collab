#!/usr/bin/env bash
set -euo pipefail
umask 077

BASE="${W5C_BASE:-/data/home/w5d}"
JAR="${W5C_JAR:-$BASE/app.jar}"
APP_CONTAINER="${W5C_APP_CONTAINER:-ai-collab-app}"
POSTGRES_CONTAINER="${W5C_POSTGRES_CONTAINER:-ai-collab-postgres}"
COMPOSE_DIR="${W5C_COMPOSE_DIR:-/data/home/ai-collab/ai-collab-deploy}"
COMPOSE_ENV="${W5C_COMPOSE_ENV:-/data/home/ai-collab/.env}"
COMPOSE_FINAL="${W5C_COMPOSE_FINAL:-/data/home/w3s2/compose.final.yml}"

test -f "$JAR"
mkdir -p "$BASE/keys" "$BASE/output"
chmod 700 "$BASE" "$BASE/keys"

if ! docker image inspect ai-collab-backend:w5c-before >/dev/null 2>&1; then
  docker tag ai-collab-backend:local ai-collab-backend:w5c-before
fi
cat > "$BASE/Dockerfile.w5c" <<'EOF'
FROM ai-collab-backend:local
COPY app.jar /app/app.jar
EOF
docker build -f "$BASE/Dockerfile.w5c" -t ai-collab-backend:local "$BASE"

openssl genpkey -algorithm ED25519 -out "$BASE/keys/private.pem" >/dev/null 2>&1
chmod 600 "$BASE/keys/private.pem"
PRIVATE_B64="$(openssl pkey -in "$BASE/keys/private.pem" -outform DER | base64 -w0)"
PUBLIC_B64="$(openssl pkey -in "$BASE/keys/private.pem" -pubout -outform DER | base64 -w0)"

USER_ID="$(cat /proc/sys/kernel/random/uuid)"
USERNAME="w5c_$(date -u +%H%M%S)_$(printf '%s' "$USER_ID" | cut -c1-8)"
docker exec "$POSTGRES_CONTAINER" psql -U ai_collab -d ai_collab -v ON_ERROR_STOP=1 \
  -c "INSERT INTO app_user(id,username,password_hash,display_name) VALUES ('$USER_ID','$USERNAME','test-only-unusable','W5 C live evaluator')"

JWT_SECRET="$(docker inspect "$APP_CONTAINER" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^JWT_SECRET=//p')"
test -n "$JWT_SECRET"
W5_USER_ID="$USER_ID" JWT_SECRET="$JWT_SECRET" python3 - > "$BASE/jwt" <<'PY'
import base64
import hashlib
import hmac
import json
import os
import time
import uuid

def enc(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")

header = enc(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
now = int(time.time())
payload = enc(json.dumps({
    "jti": str(uuid.uuid4()),
    "sub": os.environ["W5_USER_ID"],
    "username": "w5c",
    "iat": now,
    "exp": now + 3600,
}, separators=(",", ":")).encode())
signing_input = f"{header}.{payload}".encode("ascii")
signature = hmac.new(os.environ["JWT_SECRET"].encode(), signing_input, hashlib.sha256).digest()
print(f"{header}.{payload}.{enc(signature)}")
PY
chmod 600 "$BASE/jwt"

cat > "$BASE/compose.w5c.yml" <<EOF
services:
  app:
    environment:
      INTERNAL_AI_TOKEN_MODE: dual
      INTERNAL_AI_TOKEN_PRIVATE_KEY: "$PRIVATE_B64"
      INTERNAL_AI_TOKEN_KID: k1
      MEMORY_JOB_ENABLED: "true"
      MEMORY_JOB_RECOVERY_ENABLED: "true"
  ai-worker-api:
    environment:
      AI_WORKER_TOKEN_MODE: ed25519
      AI_WORKER_ED25519_PUBLIC_KEYS: "k1=$PUBLIC_B64"
EOF
chmod 600 "$BASE/compose.w5c.yml"

cat > "$BASE/measurement-meta.env" <<EOF
W5_USER_ID=$USER_ID
W5_USERNAME=$USERNAME
W5_KID=k1
EOF
chmod 600 "$BASE/measurement-meta.env"

cd "$COMPOSE_DIR"
docker compose -p ai-collab-deploy --env-file "$COMPOSE_ENV" \
  -f docker-compose.yml -f "$COMPOSE_FINAL" -f "$BASE/compose.w5c.yml" \
  up -d --force-recreate app ai-worker-api

for _ in $(seq 1 60); do
  app_health="$(docker inspect "$APP_CONTAINER" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')"
  worker_health="$(docker inspect ai-collab-ai-worker-api --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')"
  if [ "$app_health" = "healthy" ] && [ "$worker_health" = "healthy" ]; then
    break
  fi
  sleep 3
done

test "$app_health" = "healthy"
test "$worker_health" = "healthy"
echo "staging prepared: app=$app_health worker=$worker_health user=$USER_ID"
