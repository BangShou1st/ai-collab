#!/usr/bin/env bash
# S3 环境开关快照（只输出模式开关，不含任何令牌/密钥）。
set -uo pipefail
LOG=/data/home/w3s3/run.log
{
  echo "== env flags $(date -u +%Y-%m-%dT%H:%M:%SZ) =="
  docker inspect ai-collab-app --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | grep -E '^(INTERNAL_AI_TOKEN_MODE|RERANK_MODE|DECIDE_MODE|PLANNING_DRAFT_MODE|MEMORY_JOB_ENABLED|MEMORY_JOB_RECOVERY_ENABLED|RERANK_SHADOW_SAMPLE_RATE)='
  docker inspect ai-collab-ai-worker-api --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | grep -E '^(AI_WORKER_TOKEN_MODE|AI_WORKER_JAVA_GATEWAY_URL|AI_WORKER_PROMPTS_DIR)='
  docker ps --filter name=ai-collab-app --filter name=ai-collab-ai-worker-api --format '{{.Names}} {{.Status}}'
} 2>&1 | tee -a "$LOG"
