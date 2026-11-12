#!/usr/bin/env bash
set -euo pipefail

BASE="${W5C_BASE:-/data/home/w5d}"
OUT="${1:-$BASE/output}"
WORKER="${W5C_WORKER_CONTAINER:-ai-collab-ai-worker-api}"
COMMIT="${W5_COMMIT:-4fd32479}"
NETWORK="${W5C_NETWORK:-ai-collab-deploy_default}"
IMAGE="${W5C_WORKER_IMAGE:-ai-collab-ai-worker:dev}"

test -f "$BASE/jwt"
test -f "$BASE/harness/live_memory_eval.py"
test -f "$BASE/harness/arms_cases.v1.json"
mkdir -p "$OUT"
umask 077
docker exec "$WORKER" printenv AI_WORKER_SERVICE_TOKEN > "$BASE/worker-token"
{
  printf 'AI_WORKER_SERVICE_TOKEN=%s\n' "$(cat "$BASE/worker-token")"
  printf 'W5_USER_JWT=%s\n' "$(cat "$BASE/jwt")"
} > "$BASE/live.env"

set +e
docker run --rm \
  --user 0 \
  --network "$NETWORK" \
  -v "$BASE:$BASE" \
  -w "$BASE" \
  --env-file "$BASE/live.env" \
  -e W5_BRANCH=w5/memory-c \
  -e W5_COMMIT="$COMMIT" \
  -e W5_BOX=box-aae54f \
  --entrypoint python \
  "$IMAGE" "$BASE/harness/live_memory_eval.py" \
  --fixture "$BASE/harness/arms_cases.v1.json" \
  --output-dir "$OUT" \
  --base-url http://app:8080 \
  --model-base-url http://app:8080 \
  --interval-seconds 10
status=$?
set -e

rm -f "$BASE/worker-token" "$BASE/live.env"
echo "harness_exit=$status"
exit "$status"
