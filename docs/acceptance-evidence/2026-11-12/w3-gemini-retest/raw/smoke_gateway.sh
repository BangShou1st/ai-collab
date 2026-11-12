#!/usr/bin/env bash
# 真实网关冒烟：/internal/ai/v1/model-calls（静态服务令牌，purpose=KNOWLEDGE_CHAT）
set -uo pipefail
BASE=http://127.0.0.1:8080
OUT=/data/home/w3s15/raw
mkdir -p "$OUT"

set -a
. /data/.secrets/worker.env
set +a
TOKEN=${AI_WORKER_SERVICE_TOKEN:-${RERANK_SERVICE_TOKEN:-}}
if [ -z "$TOKEN" ]; then echo "MISSING_SERVICE_TOKEN"; exit 1; fi

TS=$(date +%s%3N)
BODY="$OUT/smoke-request.json"
jq -nc --arg cid "s15-smoke-$TS" '{
  schemaVersion: 1,
  callId: $cid,
  purpose: "KNOWLEDGE_CHAT",
  systemPrompt: "你是 staging 健康检查助手。",
  userPrompt: "用一句话说明你正在通过哪个模型回答。",
  outputFormat: "TEXT",
  deadlineMs: 30000
}' > "$BODY"

CODE=$(curl -sS -X POST "$BASE/internal/ai/v1/model-calls" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary "@$BODY" -o "$OUT/smoke-model-call.json" -w '%{http_code}')
echo "model-call HTTP=$CODE"
jq -c '{schemaVersion, content, usage, modelAlias, latencyMs, error}' "$OUT/smoke-model-call.json" 2>/dev/null || cat "$OUT/smoke-model-call.json"
