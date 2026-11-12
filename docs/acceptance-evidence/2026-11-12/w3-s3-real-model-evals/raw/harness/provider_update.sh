#!/usr/bin/env bash
# S3：把既有 Gemini provider 的 maxOutputTokens 1024 -> 8192，并记录 before/after。
# 凭据只从 600 文件读取；apiKey 只进临时 600 文件，不回显、不落证据。
set -uo pipefail

BASE=http://127.0.0.1:8080
ORIGIN=http://127.0.0.1:8080
PID=5b1d9af4-52d7-4055-8ca4-e654caa622b4
OUT=/data/home/w3s3/raw
mkdir -p "$OUT"

set -a
. /data/.secrets/admin.env
set +a

LOGIN_BODY=$(mktemp)
chmod 600 "$LOGIN_BODY"
jq -nc --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{username:$u,password:$p}' > "$LOGIN_BODY"
LOGIN_CODE=$(curl -sS -X POST "$BASE/api/v1/auth/login" -H "Origin: $ORIGIN" \
  -H 'Content-Type: application/json' --data-binary "@$LOGIN_BODY" -o "$OUT/login.json" -w '%{http_code}')
rm -f "$LOGIN_BODY"
echo "login HTTP=$LOGIN_CODE"
jq -c '{code, user:(.data.user|{username,systemAdmin}), expiresInSeconds:.data.expiresInSeconds}' "$OUT/login.json"
TOKEN=$(jq -r '.data.accessToken // empty' "$OUT/login.json")
if [ -z "$TOKEN" ]; then echo LOGIN_FAILED; exit 1; fi

echo "== before =="
BEFORE_CODE=$(curl -sS "$BASE/api/v1/admin/ai/providers" -H "Authorization: Bearer $TOKEN" \
  -H "Origin: $ORIGIN" -o "$OUT/providers-before-token-update.json" -w '%{http_code}')
echo "providers-before HTTP=$BEFORE_CODE"
jq --arg id "$PID" '.data[] | select(.id==$id)' "$OUT/providers-before-token-update.json" > "$OUT/provider-before.json"
jq -c '{id,name,providerType,baseUrl,apiPath,modelName,enabled,temperature,maxOutputTokens,capabilities,hasApiKey,isShared,sharedStatus,updatedAt}' "$OUT/provider-before.json"

set -a
. /data/.secrets/gemini.env
set +a

BODY=$(mktemp)
chmod 600 "$BODY"
jq -n --arg k "$GEMINI_API_KEY" '{
  name: "Gemini-3.1-Flash-Lite",
  providerType: "GEMINI",
  baseUrl: "https://generativelanguage.googleapis.com",
  apiPath: "/v1beta/models/{model}:generateContent",
  apiKey: $k,
  modelName: "gemini-3.1-flash-lite",
  enabled: true,
  temperature: 0,
  maxOutputTokens: 8192,
  capabilities: ["CHAT", "STREAMING"]
}' > "$BODY"

echo "== update PUT =="
UPDATE_CODE=$(curl -sS -X PUT "$BASE/api/v1/admin/ai/providers/$PID" -H "Authorization: Bearer $TOKEN" \
  -H "Origin: $ORIGIN" -H 'Content-Type: application/json' --data-binary "@$BODY" \
  -o "$OUT/provider-update.json" -w '%{http_code}')
rm -f "$BODY"
echo "update HTTP=$UPDATE_CODE"
jq -c '{code,message,data:(.data|{id,name,providerType,modelName,enabled,temperature,maxOutputTokens,capabilities,hasApiKey,isShared,sharedStatus,capabilityConfigVersion})}' "$OUT/provider-update.json"

echo "== after (list filtered by id) =="
AFTER_CODE=$(curl -sS "$BASE/api/v1/admin/ai/providers" -H "Authorization: Bearer $TOKEN" \
  -H "Origin: $ORIGIN" -o "$OUT/providers-after.json" -w '%{http_code}')
echo "providers-after HTTP=$AFTER_CODE"
jq --arg id "$PID" '.data[] | select(.id==$id)' "$OUT/providers-after.json" > "$OUT/provider-after.json"
jq -c '{id,name,providerType,baseUrl,apiPath,modelName,enabled,temperature,maxOutputTokens,capabilities,hasApiKey,isShared,sharedStatus,capabilityConfigVersion,updatedAt}' "$OUT/provider-after.json"

echo "== assertions =="
jq -e --arg id "$PID" '
  (.id == $id) and (.maxOutputTokens == 8192) and (.temperature == 0) and
  (.modelName == "gemini-3.1-flash-lite") and (.providerType == "GEMINI") and
  (.capabilities | sort == ["CHAT","STREAMING"]) and
  (.baseUrl == "https://generativelanguage.googleapis.com") and
  (.apiPath == "/v1beta/models/{model}:generateContent") and
  (.name == "Gemini-3.1-Flash-Lite") and (.enabled == true)
' "$OUT/provider-after.json" >/dev/null && echo "ASSERT_OK" || { echo "ASSERT_FAILED"; exit 2; }

echo "== purposes =="
PURPOSE_CODE=$(curl -sS "$BASE/api/v1/user/ai-providers/purposes" -H "Authorization: Bearer $TOKEN" \
  -H "Origin: $ORIGIN" -o "$OUT/purposes-after.json" -w '%{http_code}')
echo "purposes HTTP=$PURPOSE_CODE"
cat "$OUT/purposes-after.json"; echo
exit 0
