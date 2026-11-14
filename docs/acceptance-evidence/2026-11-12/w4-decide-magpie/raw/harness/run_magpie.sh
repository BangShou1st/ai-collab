#!/usr/bin/env bash
set -euo pipefail

python3 /data/home/ai-collab/docs/acceptance-evidence/2026-11-12/w4-decide-magpie/raw/harness/run_magpie_revalidation.py \
  --repo /data/home/ai-collab \
  --out-dir /data/home/w4-magpie-eval \
  --admin-env /data/.secrets/admin.env \
  --key-file /data/home/magpie-gw.key \
  --worker-env /data/.secrets/worker.env \
  --worker-image ai-collab-ai-worker:dev \
  --gateway-url https://3425-agqyc3kteqehqgazwefpmhwulu.instabox.tech \
  --model marvis/deepseek-v4-1-flash-261017 \
  --provider-name Magpie-Marvis-DeepSeek-V4.1-Flash-W4R \
  --gemini-provider-id 0f58581a-dbba-4e26-bafa-2e137f650563 \
  --run "$@"
