#!/usr/bin/env bash
# 只创建随机命名的隔离资源；不使用现有 .env/卷、生产凭据或收费模型。
# 依赖预先构建的 ai-collab-p0-isolated:dirty、nginx:1.30.6-alpine 和前端 dist。
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
project="p0iso$(date +%s)${RANDOM}"
tmp_base=$(cd "${TMPDIR:-/tmp}" && pwd -P)
tmp=$(mktemp -d "$tmp_base/ai-collab-p0.XXXXXXXX")
[[ "$tmp" = "$tmp_base"/ai-collab-p0.* ]] || exit 2
created=0
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
compose() { docker compose -p "$project" --env-file "$tmp/test.env" -f "$ROOT/ai-collab-deploy/compose.prod.yml" "$@"; }
cleanup() {
  if [ "$created" = 1 ]; then
    compose down --remove-orphans >/dev/null 2>&1 || true
    for role in postgres_data redis_data minio_data; do
      volume="${project}_${role}"
      owner=$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$volume" 2>/dev/null || true)
      if [ "$owner" = "$project" ]; then docker volume rm "$volume" >/dev/null 2>&1 || true; fi
    done
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT
for role in postgres_data redis_data minio_data; do
  if docker volume inspect "${project}_${role}" >/dev/null 2>&1; then echo '隔离资源名称冲突' >&2; exit 1; fi
done
if docker network inspect "${project}_edge" >/dev/null 2>&1 || docker network inspect "${project}_data" >/dev/null 2>&1; then exit 1; fi
[ -f "$ROOT/ai-collab-frontend/dist/index.html" ] || { echo '请先构建前端 dist' >&2; exit 1; }
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' \
  -addext 'subjectAltName=DNS:localhost' -keyout "$(host_path "$tmp/key.pem")" -out "$(host_path "$tmp/cert.pem")" >/dev/null 2>&1
port=$(node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})")
secret() { openssl rand -hex 32; }
cat > "$tmp/test.env" <<EOF
APP_IMAGE=ai-collab-p0-isolated:dirty
PROXY_IMAGE=nginx:1.30.6-alpine
PUBLIC_BIND_IP=127.0.0.1
HTTPS_PORT=$port
TLS_CERT_FILE=$(host_path "$tmp/cert.pem")
TLS_KEY_FILE=$(host_path "$tmp/key.pem")
AUTH_ALLOWED_ORIGINS=https://localhost:$port
MINIO_PUBLIC_ENDPOINT=https://localhost:$port
AUTH_PUBLIC_REGISTRATION_ENABLED=true
POSTGRES_DB=p0isolated
POSTGRES_USER=p0isolated
POSTGRES_PASSWORD=$(secret)
REDIS_PASSWORD=$(secret)
MINIO_ROOT_USER=p0-admin
MINIO_ROOT_PASSWORD=$(secret)
MINIO_ACCESS_KEY=p0-app
MINIO_SECRET_KEY=$(secret)
MINIO_BUCKET=p0-bucket
JWT_SECRET=$(secret)
MODEL_CONFIG_MASTER_KEY=$(secret)
AGENT_ENABLED=false
EOF
# 本文件完全由本脚本生成，值均为合成字母数字；绝不 source 用户配置。
set -a; source "$tmp/test.env"; set +a
created=1
compose up -d postgres redis minio >/dev/null
for role in postgres redis minio; do
  id=$(compose ps -q "$role")
  for i in $(seq 1 120); do
    state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
    [ "$state" = healthy ] && break
    [ "$state" != unhealthy ] && [ "$i" -lt 120 ] || { echo "$role 未就绪: $state" >&2; exit 1; }
    sleep 1
  done
done
pg=$(compose ps -q postgres)
minio=$(compose ps -q minio)
docker exec "$minio" sh -c 'mc alias set p0 http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc mb --region us-east-1 p0/p0-bucket >/dev/null'
cat > "$tmp/policy.json" <<'EOF'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::p0-bucket"]},{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject"],"Resource":["arn:aws:s3:::p0-bucket/*"]}]}
EOF
docker exec -i "$minio" sh -c 'cat > /tmp/p0-policy.json && mc admin policy create p0 p0-app-policy /tmp/p0-policy.json >/dev/null' < "$tmp/policy.json"
docker exec -e TEST_APP_USER="$MINIO_ACCESS_KEY" -e TEST_APP_PASSWORD="$MINIO_SECRET_KEY" "$minio" sh -c \
  'mc admin user add p0 "$TEST_APP_USER" "$TEST_APP_PASSWORD" >/dev/null && mc admin policy attach p0 p0-app-policy --user "$TEST_APP_USER" >/dev/null'
printf 'p0-synthetic-object' | docker exec -i "$minio" sh -c 'mc pipe p0/p0-bucket/projects/synthetic/source.txt >/dev/null'
printf 'p0-encoded-object' | docker exec -i "$minio" sh -c 'mc pipe "p0/p0-bucket/projects/synthetic/space + percent%25.txt" >/dev/null'
run_drill() {
  mkdir -p "$tmp/backup"
  export POSTGRES_CONTAINER="$pg" MINIO_CONTAINER="$minio" BACKUP_ROOT="$tmp/backup"
  export MINIO_IMAGE='cgr.dev/chainguard/minio:latest@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18'
  local started=$SECONDS
  bash "$ROOT/ai-collab-deploy/backup/run-restore-drill.sh"
  echo "isolated backup and restore elapsed_seconds=$((SECONDS-started))"
}
if [ "${P0_BACKUP_ONLY:-0}" = 1 ]; then
  docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" "$pg" psql -h localhost -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE TABLE app_user(id integer PRIMARY KEY);
CREATE TABLE project_document(object_key text NOT NULL);
INSERT INTO app_user VALUES (1);
INSERT INTO project_document VALUES ('projects/synthetic/source.txt');
SQL
  export VERIFY_KEY_TABLES='app_user project_document'
  run_drill
  exit 0
fi
compose up -d app proxy >/dev/null
app=$(compose ps -q app)
proxy=$(compose ps -q proxy)
for i in $(seq 1 180); do
  if docker exec "$app" wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then break; fi
  [ "$i" -lt 180 ] || { echo '应用启动或健康检查超时' >&2; exit 1; }
  sleep 1
done
docker exec "$proxy" nginx -t >/dev/null 2>&1
docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" "$pg" psql -h localhost -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
INSERT INTO app_user(id,username,password_hash,display_name,system_admin) VALUES
('33333333-3333-4333-8333-333333333333','p0owner',crypt('synthetic-p0-password',gen_salt('bf',12)),'P0 owner',true),
('44444444-4444-4444-8444-444444444444','p0member',crypt('synthetic-p0-password',gen_salt('bf',12)),'P0 member',false);
INSERT INTO project(id,name,owner_id,created_by) VALUES
('11111111-1111-4111-8111-111111111111','P0 isolated','33333333-3333-4333-8333-333333333333','33333333-3333-4333-8333-333333333333'),
('22222222-2222-4222-8222-222222222222','P0 private','33333333-3333-4333-8333-333333333333','33333333-3333-4333-8333-333333333333');
INSERT INTO project_member(project_id,user_id,role) VALUES
('11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','OWNER'),
('11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444','MEMBER'),
('22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333','OWNER');
INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,uploaded_by)
VALUES ('55555555-5555-4555-8555-555555555555','11111111-1111-4111-8111-111111111111',
'synthetic','synthetic.txt','text/plain',19,'projects/synthetic/source.txt','33333333-3333-4333-8333-333333333333');
INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,uploaded_by)
VALUES ('88888888-8888-4888-8888-888888888888','11111111-1111-4111-8111-111111111111',
'encoded','encoded.txt','text/plain',17,'projects/synthetic/space + percent%25.txt','33333333-3333-4333-8333-333333333333');
INSERT INTO agent_session(id,project_id,creator_id,title) VALUES ('66666666-6666-4666-8666-666666666666','11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','P0 stream');
INSERT INTO agent_run(id,session_id,project_id,requester_id,goal,status,last_event_sequence) VALUES ('77777777-7777-4777-8777-777777777777','66666666-6666-4666-8666-666666666666','11111111-1111-4111-8111-111111111111','33333333-3333-4333-8333-333333333333','synthetic','QUEUED',2);
INSERT INTO agent_run_event(project_id,run_id,sequence_no,type,payload_json) VALUES ('11111111-1111-4111-8111-111111111111','77777777-7777-4777-8777-777777777777',1,'RUN_CREATED','{}'),('11111111-1111-4111-8111-111111111111','77777777-7777-4777-8777-777777777777',2,'PLAN_CREATED','{}');
SQL
TEST_PORT="$port" TEST_CERT="$(host_path "$tmp/cert.pem")" TEST_PROJECT="$project" node "$ROOT/ai-collab-deploy/scripts/p0-https-test.mjs"
TEST_PORT="$port" node "$ROOT/ai-collab-deploy/scripts/p0-sse-test.mjs"
for mode in proxy-a proxy-b direct register-a register-b; do
  ip=172.30.173.10; [[ "$mode" = proxy-b || "$mode" = register-b ]] && ip=172.30.173.11
  MSYS_NO_PATHCONV=1 docker run --rm --network "${project}_edge" --ip "$ip" \
    -e TEST_MODE="$mode" -e TEST_ORIGIN="$AUTH_ALLOWED_ORIGINS" \
    -v "$(host_path "$ROOT/ai-collab-deploy/scripts/p0-proxy-ip-test.mjs"):/test.mjs:ro" \
    node:24.14.0-alpine node /test.mjs
done
node "$ROOT/ai-collab-deploy/scripts/p0-port-scan.mjs" "$project"
node "$ROOT/ai-collab-deploy/scripts/p0-log-scan.mjs" "$project"
bash "$ROOT/ai-collab-deploy/scripts/p0-dependency-outage.sh" "$project"
echo 'isolated HTTPS and proxy checks complete'
if [ "${P0_HOLD_BROWSER:-0}" = 1 ]; then
  printf 'BROWSER_READY https://localhost:%s user=p0owner synthetic-password=synthetic-p0-password\n' "$port"
  read -r -p '浏览器验收结束后继续（Enter）: ' _
fi
export VERIFY_KEY_TABLES='app_user project project_member project_document'
run_drill
echo 'isolated restore drill completed'
