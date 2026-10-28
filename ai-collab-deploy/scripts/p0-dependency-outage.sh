#!/usr/bin/env bash
# 只能对当前测试创建的 p0iso Compose 项目执行；绝不接受其他容器名。
set -euo pipefail
project=${1:-}
[[ "$project" =~ ^p0iso[0-9]+$ ]] || { echo 'invalid isolated project' >&2; exit 2; }
for role in app postgres redis minio; do
  name="$project-$role-1"
  owner=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$name")
  [ "$owner" = "$project" ] || exit 2
done
app="$project-app-1"
stopped=''
restart_on_exit() { [ -z "$stopped" ] || docker start "$stopped" >/dev/null 2>&1 || true; }
trap restart_on_exit EXIT
health() { docker exec "$app" wget -T 5 -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; }
health || { echo 'initial health is not UP' >&2; exit 1; }
for role in postgres redis minio; do
  stopped="$project-$role-1"
  docker stop "$stopped" >/dev/null
  down=0
  for i in $(seq 1 30); do
    if ! health; then down=1; break; fi
    sleep 1
  done
  docker start "$stopped" >/dev/null
  stopped=''
  [ "$down" = 1 ] || { echo "$role outage did not affect aggregate health" >&2; exit 1; }
  recovered=0
  for i in $(seq 1 60); do
    if health; then recovered=1; break; fi
    sleep 1
  done
  [ "$recovered" = 1 ] || { echo "$role did not recover" >&2; exit 1; }
  echo "$role isolated stop, health failure and recovery: verified"
done
