#!/usr/bin/env bash
# W3 rerank staging canary（Agent 路径）：通过 Agent 的 search_project_knowledge（RAG_V2）
# 触发检索管线与 rerank，统计用户可见延迟与回答数。
#
# 用法（在 staging 主机上，需先有可用的 ACCESS_TOKEN 或 PROBE_PASSWORD）：
#   ACCESS_TOKEN=<JWT> BASE_URL=http://127.0.0.1:9443 PROJECT_ID=<uuid> \
#   COUNT=4 OUT=/tmp/agent-java.jsonl ./rerank-agent-canary.sh
#
# 输出：$OUT 每行 {"i","ms","answerChars"}；stdout 一行 p50/p95/answered 摘要。
# 判据：用户可见 rerank 成本看 app 的 rerank_latency{mode}，不要看 Agent 端到端（模型轮次占主导）。
set -uo pipefail

BASE_URL="${BASE_URL:?BASE_URL required}"
PROJECT_ID="${PROJECT_ID:?PROJECT_ID required}"
TOKEN="${ACCESS_TOKEN:?ACCESS_TOKEN required}"
OUT="${OUT:-/tmp/agent-canary.jsonl}"
COUNT="${COUNT:-4}"
A="Authorization: Bearer $TOKEN"

PROMPTS=(
  "请先调用 search_project_knowledge 检索项目文档，再回答：灰度期间核心指标连续两次低于阈值应该怎么处理？必须给出文档引用。"
  "请先调用 search_project_knowledge 检索项目文档，再回答：回滚演练包含哪些步骤？需要归档吗？必须给出引用。"
  "请先调用 search_project_knowledge 检索项目文档，再回答：知识检索接口的 P95 响应时间和 QPS 上限分别是多少？引用原文。"
  "请先调用 search_project_knowledge 检索项目文档，再回答：跨项目检索是否允许？给出依据。"
)

one() {
  local idx="$1" prompt="${PROMPTS[$(( $1 % ${#PROMPTS[@]} ))]}"
  local sid start end ms answer
  sid=$(curl -sf -X POST "$BASE_URL/api/v1/projects/$PROJECT_ID/agent/sessions" \
    -H "$A" -H "Content-Type: application/json" -d '{"title":"W3 rerank canary"}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["id"])') || return 1
  start=$(date +%s%3N)
  curl -sf -X POST "$BASE_URL/api/v1/projects/$PROJECT_ID/agent/sessions/$sid/messages" \
    -H "$A" -H "Content-Type: application/json" \
    -d "$(python3 -c 'import json,sys;print(json.dumps({"content":sys.argv[1]},ensure_ascii=False))' "$prompt")" \
    >/dev/null || return 1
  for _ in $(seq 1 60); do
    sleep 3
    answer=$(curl -sf "$BASE_URL/api/v1/projects/$PROJECT_ID/agent/sessions/$sid/messages" -H "$A" \
      | python3 -c 'import sys,json
d=json.load(sys.stdin)["data"]
a=[m for m in d if m.get("role")=="ASSISTANT"]
print((a[-1].get("content") or "").replace("\n"," ") if a else "")' 2>/dev/null || true)
    [ -n "$answer" ] && break
  done
  end=$(date +%s%3N); ms=$((end-start))
  python3 -c 'import json,sys;print(json.dumps({"i":int(sys.argv[1]),"ms":int(sys.argv[2]),"answerChars":len(sys.argv[3])},ensure_ascii=False))' \
    "$idx" "$ms" "$answer" >> "$OUT"
}

: > "$OUT"
for i in $(seq 0 $((COUNT-1))); do one "$i" & done
wait

python3 - "$OUT" <<'PY'
import json, math, sys
rows = [json.loads(x) for x in open(sys.argv[1], encoding="utf-8") if x.strip()]
ms = sorted(r["ms"] for r in rows)
p95 = ms[max(0, math.ceil(0.95 * len(ms)) - 1)] if ms else 0
print(json.dumps({
    "cases": len(rows),
    "p50Ms": ms[len(ms) // 2] if ms else 0,
    "p95Ms": p95,
    "answered": sum(1 for r in rows if r["answerChars"] > 0),
}, ensure_ascii=False))
PY
