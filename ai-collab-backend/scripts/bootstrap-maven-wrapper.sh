#!/usr/bin/env bash
# Bootstrap Maven distribution for CI with bounded retry on transient network errors.
# Only retries the wrapper download/startup phase; never retries build or test phases.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
backend_root="$(cd "$script_dir/.." && pwd)"
mvnw_bin="$backend_root/mvnw"

if [ ! -f "$mvnw_bin" ]; then
  echo "::error::mvnw script not found at $mvnw_bin" >&2
  exit 1
fi

max_attempts="${MAVEN_BOOTSTRAP_MAX_ATTEMPTS:-3}"
initial_delay="${MAVEN_BOOTSTRAP_INITIAL_DELAY_SEC:-3}"
current_delay="$initial_delay"
attempt=1

echo "==> Starting Maven Wrapper bootstrap (up to $max_attempts attempts)..."

clean_partial_wrapper() {
  local user_home="${MAVEN_USER_HOME:-${HOME}/.m2}"
  local wrapper_dists="$user_home/wrapper/dists"
  if [ -d "$wrapper_dists" ]; then
    echo "Cleaning up partial Maven Wrapper distributions under $wrapper_dists..."
    rm -rf "$wrapper_dists"/*apache-maven* 2>/dev/null || :
  fi
}

run_bootstrap_probe() {
  if [ -n "${MAVEN_BOOTSTRAP_CMD:-}" ]; then
    ( eval "$MAVEN_BOOTSTRAP_CMD" )
  else
    (
      cd "$backend_root"
      MVNW_VERBOSE=true bash "$mvnw_bin" -B -ntp --version
    )
  fi
}

while [ "$attempt" -le "$max_attempts" ]; do
  echo "--> Bootstrap attempt $attempt of $max_attempts..."

  set +e
  run_bootstrap_probe
  status=$?
  set -e

  if [ "$status" -eq 0 ]; then
    echo "==> Maven Wrapper bootstrapped successfully on attempt $attempt."
    exit 0
  fi

  echo "::warning::Maven Wrapper bootstrap attempt $attempt failed with exit code $status" >&2

  if [ "$attempt" -lt "$max_attempts" ]; then
    clean_partial_wrapper
    echo "Waiting ${current_delay}s before retrying..."
    sleep "$current_delay"
    current_delay=$((current_delay * 2))
  fi

  attempt=$((attempt + 1))
done

echo "::error::Maven Wrapper bootstrap failed after $max_attempts attempts." >&2
exit 1
