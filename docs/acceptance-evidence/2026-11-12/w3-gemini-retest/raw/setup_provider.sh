#!/usr/bin/env bash
# S1.5：创建/测试/发布 GEMINI provider 并分配 purpose。凭据只从 600 文件读取，不打印。
set -uo pipefail

BASE=http://127.0.0.1:8080
ORIGIN=http://127.0.0.1:8080
OUT=/data/home/w3s15/raw
mkdir -p "$OUT"

req() { # req METHOD PATH BODY_FILE OUT_FILE
  local method=$1 path=$2 body_file=${3:-} out_file=$4
  local args=(-sS -X "$method" "$BASE$path" -H "Authorization: Bearer $TOKEN" -H "Origin: $ORIGIN")
  if [ -n "$body_file" ] && [ "$body_file" != "-" ]; then
    args+=(-H 'Content-Type: application/json' --data-binary "@$body_file")
  fi
  curl "${args[@]}" -o "$out_file" -w '%{http_code}'
}

set -a
. /data/.secrets/admin.env
set +a

echo "== login =="
LOGIN_BODY=$(mktemp)
jq -nc --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{username:$u,password:$p}' > "$LOGIN_BODY"
LOGIN_CODE=$(curl -sS -X POST "$BASE/api/v1/auth/login" -H "Origin: $ORIGIN" \
  -H 'Content-Type: application/json' --data-binary "@$LOGIN_BODY" -o "$OUT/login.json" -w '%{http_code}')
rm -f "$LOGIN_BODY"
echo "login HTTP=$LOGIN_CODE"
jq -c '{code, message, user:(.data.user|{id,username,systemAdmin}), expiresInSeconds:.data.expiresInSeconds}' "$OUT/login.json"
TOKEN=$(jq -r '.data.accessToken // empty' "$OUT/login.json")
if [ -z "$TOKEN" ]; then echo "LOGIN_FAILED"; exit 1; fi
(umask 077; printf '%s\n' "$TOKEN" > /data/.secrets/admin_access_token)

echo "== baseline purposes =="
echo "purposes GET HTTP=$(req GET /api/v1/user/ai-providers/purposes - "$OUT/purposes-before.json")"
cat "$OUT/purposes-before.json"; echo
echo "== baseline providers =="
echo "providers GET HTTP=$(req GET /api/v1/admin/ai/providers - "$OUT/providers-before.json")"
jq -c '.data[] | {id,name,providerType,modelName,capabilities,isShared,sharedStatus}' "$OUT/providers-before.json"

echo "== create Gemini provider =="
set -a
. /data/.secrets/gemini.env
set +a
CREATE_BODY="$OUT/create-request.json"
jq -n --arg k "$GEMINI_API_KEY" '{
  name: "Gemini-3.1-Flash-Lite",
  providerType: "GEMINI",
  baseUrl: "https://generativelanguage.googleapis.com",
  apiPath: "/v1beta/models/{model}:generateContent",
  apiKey: $k,
  modelName: "gemini-3.1-flash-lite",
  enabled: true,
  temperature: 0,
  maxOutputTokens: 1024,
  capabilities: ["CHAT", "STREAMING"]
}' > "$CREATE_BODY"
chmod 600 "$CREATE_BODY"
echo "create POST HTTP=$(req POST /api/v1/admin/ai/providers "$CREATE_BODY" "$OUT/create.json")"
rm -f "$CREATE_BODY"
jq -c '{code, message, data:(.data|{id,name,providerType,baseUrl,apiPath,modelName,enabled,temperature,maxOutputTokens,capabilities,hasApiKey,isShared,sharedStatus})}' "$OUT/create.json"
PID=$(jq -r '.data.id // empty' "$OUT/create.json")
if [ -z "$PID" ]; then echo "CREATE_FAILED"; exit 1; fi
echo "provider_id=$PID"

echo "== provider detail (list filtered, no apiKey) =="
echo "providers GET HTTP=$(req GET /api/v1/admin/ai/providers - "$OUT/providers-after-create.json")"
jq --arg id "$PID" '.data[] | select(.id==$id)' "$OUT/providers-after-create.json" > "$OUT/provider-detail.json"
jq -c '{id,name,providerType,baseUrl,apiPath,modelName,capabilities,hasApiKey,isShared,sharedStatus,capabilityConfigVersion}' "$OUT/provider-detail.json"

echo "== test =="
echo "test POST HTTP=$(req POST "/api/v1/admin/ai/providers/$PID/test" - "$OUT/provider-test.json")"
jq -c '{code, message, data:(.data|{content,modelName,providerType,inputTokens,outputTokens,latencyMs})}' "$OUT/provider-test.json" 2>/dev/null || cat "$OUT/provider-test.json"

echo "== publish =="
echo "publish POST HTTP=$(req POST "/api/v1/admin/ai/providers/$PID/publish" - "$OUT/provider-publish.json")"
jq -c '{code, message, data:(.data|{id,isShared,sharedStatus,sharedPublishedAt})}' "$OUT/provider-publish.json"

echo "== assign purposes =="
for p in KNOWLEDGE_CHAT AGENT PLANNING; do
  BODY="$OUT/purpose-$p-request.json"
  jq -nc --arg id "$PID" '{providerId:$id}' > "$BODY"
  CODE=$(req PUT "/api/v1/user/ai-providers/purposes/$p" "$BODY" "$OUT/purpose-$p.json")
  rm -f "$BODY"
  echo "assign $p HTTP=$CODE body=$(cat "$OUT/purpose-$p.json")"
done

echo "== purposes after =="
echo "purposes GET HTTP=$(req GET /api/v1/user/ai-providers/purposes - "$OUT/purposes-after.json")"
cat "$OUT/purposes-after.json"; echo

echo "PROVIDER_ID=$PID"
