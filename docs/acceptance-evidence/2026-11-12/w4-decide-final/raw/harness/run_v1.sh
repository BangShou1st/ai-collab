#!/usr/bin/env bash
set -euo pipefail

mkdir -p /data/home/w4-final-eval
docker run --rm \
  --network ai-collab-deploy_ai \
  --user 0 \
  -v /data/home/ai-collab:/repo:ro \
  -v /data/home/w4-final-eval:/out \
  --env-file /data/.secrets/worker.env \
  --entrypoint /app/.venv/bin/python \
  ai-collab-ai-worker:dev \
  /repo/docs/acceptance-evidence/2026-11-12/w4-decide-final/raw/harness/run_decide_live.py \
  --set /repo/ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl \
  --out-dir /out \
  --worker-url http://ai-worker-api:9100 \
  --interval-ms 10000 \
  --evidence "LIVE_VERIFIED (staging, real model)" \
  --model gemini-3.5-flash-lite \
  --subject "w4/decide-final @ 87450efecfa36fdbcb3e970267845f9197748033" \
  --tag v1 \
  2>&1 | tee /data/home/w4-final-eval/live-output-v1.txt
