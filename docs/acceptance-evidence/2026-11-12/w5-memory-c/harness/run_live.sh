#!/usr/bin/env bash
set -euo pipefail

BASE="${W5C_BASE:-/data/home/w5d}"
OUT="${1:-$BASE/output}"
WORKER="${W5C_WORKER_CONTAINER:-ai-collab-ai-worker-api}"
COMMIT="${W5_COMMIT:-4fd32479}"

test -f "$BASE/jwt"
test -f "$BASE/harness/live_memory_eval.py"
test -f "$BASE/harness/arms_cases.v1.json"
mkdir -p "$OUT"
chmod 755 "$OUT"

docker exec "$WORKER" rm -rf /tmp/w5c-live
docker exec "$WORKER" mkdir -p /tmp/w5c-live
docker cp "$BASE/jwt" "$WORKER:/tmp/w5c-live/jwt"
docker cp "$BASE/harness/live_memory_eval.py" "$WORKER:/tmp/w5c-live/live_memory_eval.py"
docker cp "$BASE/harness/arms_cases.v1.json" "$WORKER:/tmp/w5c-live/arms_cases.v1.json"

set +e
docker exec \
  -e W5_USER_JWT_FILE=/tmp/w5c-live/jwt \
  -e W5_BRANCH=w5/memory-c \
  -e W5_COMMIT="$COMMIT" \
  -e W5_BOX=box-aae54f \
  "$WORKER" python /tmp/w5c-live/live_memory_eval.py \
  --fixture /tmp/w5c-live/arms_cases.v1.json \
  --output-dir /tmp/w5c-live/output \
  --base-url http://app:8080 \
  --model-base-url http://app:8080 \
  --interval-seconds 10
status=$?
set -e

docker cp "$WORKER:/tmp/w5c-live/output" "$OUT/"
echo "harness_exit=$status"
exit "$status"
