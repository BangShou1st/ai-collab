#!/usr/bin/env bash
set -euo pipefail

BASE="${W5C_BASE:-/data/home/w5d}"
COMPOSE_DIR="${W5C_COMPOSE_DIR:-/data/home/ai-collab/ai-collab-deploy}"
COMPOSE_ENV="${W5C_COMPOSE_ENV:-/data/home/ai-collab/.env}"
COMPOSE_FINAL="${W5C_COMPOSE_FINAL:-/data/home/w3s2/compose.final.yml}"
POSTGRES_CONTAINER="${W5C_POSTGRES_CONTAINER:-ai-collab-postgres}"

cd "$COMPOSE_DIR"
docker compose -p ai-collab-deploy --env-file "$COMPOSE_ENV" \
  -f docker-compose.yml -f "$COMPOSE_FINAL" \
  up -d --force-recreate app ai-worker-api

for _ in $(seq 1 60); do
  app_health="$(docker inspect ai-collab-app --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')"
  worker_health="$(docker inspect ai-collab-ai-worker-api --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')"
  if [ "$app_health" = "healthy" ] && [ "$worker_health" = "healthy" ]; then
    break
  fi
  sleep 3
done

test "$app_health" = "healthy"
test "$worker_health" = "healthy"

if [ -f "$BASE/measurement-meta.env" ]; then
  # shellcheck disable=SC1090
  source "$BASE/measurement-meta.env"
  docker exec "$POSTGRES_CONTAINER" psql -U ai_collab -d ai_collab -v ON_ERROR_STOP=1 \
    -c "DELETE FROM app_user WHERE id='$W5_USER_ID'" >/dev/null
fi

rm -rf "$BASE/keys" "$BASE/jwt" "$BASE/compose.w5c.yml" "$BASE/measurement-meta.env"
echo "staging defaults restored: app=$app_health worker=$worker_health"
