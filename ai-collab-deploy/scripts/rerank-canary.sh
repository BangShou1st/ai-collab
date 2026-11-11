#!/usr/bin/env bash
# W3 rerank staging canary：用真实容器 + 真实模型跑一组知识问答，记录引用数与延迟。
#
# 只读业务 API，不写库、不改配置；指标抓取走容器内管理端口（需要 JWT）。
# 用法（在 staging 主机上）：
#   BASE_URL=https://... PROJECT_ID=<uuid> PROBE_PASSWORD=... \
#   DOCS="/path/a.md /path/b.md" COUNT=10 OUT=/tmp/canary-shadow.jsonl \
#   ./rerank-canary.sh
#
# 输出：
#   $OUT            每行 {"q":..,"ms":..,"citations":..,"insufficient":..,"answerChars":..}
#   $OUT.metrics    rerank_* / retrieval 相关 Prometheus 文本
set -euo pipefail

BASE_URL="${BASE_URL:?BASE_URL required}"
# 允许 BASE_URL 走容器内回环（http://127.0.0.1:9443），而 Origin 必须是配置的 https 公网源，
# 否则 AuthRequestOriginValidator 会 403；两者默认相同。
ORIGIN="${ORIGIN:-$BASE_URL}"
PROJECT_ID="${PROJECT_ID:?PROJECT_ID required}"
PROBE_USER="${PROBE_USER:-probe}"
PROBE_PASSWORD="${PROBE_PASSWORD:-}"
COUNT="${COUNT:-8}"
OUT="${OUT:-/tmp/rerank-canary-$(date +%Y%m%d-%H%M%S).jsonl}"
APP_CONTAINER="${APP_CONTAINER:-ai-collab-devstaging-app-1}"
DOCS="${DOCS:-}"

jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

# 两种取 token 的方式：
# - ACCESS_TOKEN：直接注入（测试环境用本地铸造的短期 JWT，免登录、免 Origin 头；
#   生产/正常环境不要用，避免绕过登录审计）；
# - 否则走 probe 账号登录，必须带精确 Origin（Cookie 端点强制来源校验）。
if [ -n "${ACCESS_TOKEN:-}" ]; then
  TOKEN="$ACCESS_TOKEN"
  ORIGIN_HEADER=()
else
  : "${PROBE_PASSWORD:?PROBE_PASSWORD required when ACCESS_TOKEN is not set}"
  TOKEN=$(curl -sf -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' -H "Origin: $ORIGIN" \
    -d "{\"username\":\"$PROBE_USER\",\"password\":\"$PROBE_PASSWORD\"}" \
    | jget 'd["data"]["accessToken"]')
  ORIGIN_HEADER=(-H "Origin: $ORIGIN")
fi
AUTH="Authorization: Bearer $TOKEN"

# 1) 可选：上传文档
if [ -n "$DOCS" ]; then
  for doc in $DOCS; do
    upload_path="$doc"
    # 上传端按内容探测类型；.md 会被判成 text/markdown 与扩展名不匹配（DOCUMENT_INVALID_FILE），
    # 测试语料统一以 .txt 副本上传，不改变内容。
    case "$doc" in
      *.md) upload_path="/tmp/canary-$(basename "$doc" .md).txt"; cp "$doc" "$upload_path" ;;
    esac
    echo "upload: $upload_path"
    curl -sf -X POST "$BASE_URL/api/v1/projects/$PROJECT_ID/documents" \
      -H "$AUTH" "${ORIGIN_HEADER[@]}" -F "file=@$upload_path" >/dev/null || echo "upload failed: $doc"
  done
fi

# 2) 等文档 READY（最多 120s）
for i in $(seq 1 24); do
  STATUS=$(curl -sf "$BASE_URL/api/v1/projects/$PROJECT_ID/documents" -H "$AUTH" "${ORIGIN_HEADER[@]}" \
    | python3 -c 'import sys,json;d=json.load(sys.stdin);print(",".join(sorted({x.get("status","?") for x in d["data"]})) or "NONE")')
  echo "docs status: $STATUS"
  if [ "$STATUS" = "READY" ]; then break; fi
  sleep 5
done

# 3) 会话
SID=$(curl -sf -X POST "$BASE_URL/api/v1/projects/$PROJECT_ID/knowledge/sessions" \
  -H "$AUTH" "${ORIGIN_HEADER[@]}" | jget 'd["data"]["id"]')
echo "session=$SID"

# 4) 问题集（覆盖回滚/容量/权限三个文档簇；循环到 COUNT）
QUESTIONS=(
  "灰度期间核心指标连续两次低于阈值时应该怎么做？"
  "回滚完成后需要复核什么，通知哪些人？"
  "回滚演练包含哪些步骤？"
  "单项目最多能放多少份已索引文档？单份正文上限是多少？"
  "知识检索接口的 P95 响应时间和峰值 QPS 上限分别是多少？"
  "跨项目检索是否允许？为什么？"
  "回滚演练多久做一次，归档要求是什么？"
  "什么情况下必须暂停发布并回滚到最近稳定版本？"
)

: > "$OUT"
for n in $(seq 1 "$COUNT"); do
  Q="${QUESTIONS[$(( (n-1) % ${#QUESTIONS[@]} ))]}"
  START=$(date +%s%3N)
  RESP=$(curl -sf -X POST "$BASE_URL/api/v1/projects/$PROJECT_ID/knowledge/sessions/$SID/questions" \
    -H "$AUTH" "${ORIGIN_HEADER[@]}" -H 'Content-Type: application/json' \
    -d "{\"question\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$Q")}")
  END=$(date +%s%3N)
  MS=$((END-START))
  echo "$RESP" | python3 - "$n" "$Q" "$MS" <<'PY' >> "$OUT"
import json,sys
d=json.load(sys.stdin)["data"]
print(json.dumps({"n":int(sys.argv[1]),"q":sys.argv[2],"ms":int(sys.argv[3]),
 "citations":len(d.get("citations") or []),"insufficient":bool(d.get("insufficientEvidence")),
 "answerChars":len(d.get("answer") or "")},ensure_ascii=False))
PY
done

# 5) 指标
docker exec "$APP_CONTAINER" curl -sf -H "$AUTH" \
  http://127.0.0.1:8081/actuator/prometheus > "$OUT.metrics" || true
grep -E '^(rerank_|knowledge_|http_server_requests)' "$OUT.metrics" | head -60 || true

# 6) 摘要
python3 - "$OUT" <<'PY'
import json,sys,math
rows=[json.loads(x) for x in open(sys.argv[1],encoding='utf-8') if x.strip()]
ms=sorted(r['ms'] for r in rows)
p95=ms[max(0,math.ceil(0.95*len(ms))-1)] if ms else 0
print(json.dumps({'cases':len(rows),'citationRate':round(sum(1 for r in rows if r['citations']>0)/max(1,len(rows)),3),
 'avgCitations':round(sum(r['citations'] for r in rows)/max(1,len(rows)),2),
 'insufficient':sum(1 for r in rows if r['insufficient']),
 'p50Ms':ms[len(ms)//2] if ms else 0,'p95Ms':p95},ensure_ascii=False))
PY
