#!/usr/bin/env bash
# Unit tests for bootstrap-maven-wrapper.sh covering normal, transient failure, and persistent failure paths.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target_script="$script_dir/bootstrap-maven-wrapper.sh"

if [ ! -f "$target_script" ]; then
  echo "Target script not found: $target_script" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

counter_file="$tmp_dir/probe_count.txt"

reset_counter() {
  echo "0" > "$counter_file"
}

get_counter() {
  cat "$counter_file"
}

echo "=== Test 1: Normal immediate success (MVN-T01) ==="
reset_counter
export MAVEN_BOOTSTRAP_MAX_ATTEMPTS=3
export MAVEN_BOOTSTRAP_INITIAL_DELAY_SEC=0
export MAVEN_BOOTSTRAP_CMD="echo 'probe success'; count=\$((\$(cat '$counter_file') + 1)); echo \$count > '$counter_file'; exit 0"

output=$(bash "$target_script")
status=$?

if [ "$status" -ne 0 ]; then
  echo "FAIL: Expected exit 0, got $status" >&2
  exit 1
fi
if [ "$(get_counter)" -ne 1 ]; then
  echo "FAIL: Expected probe count 1, got $(get_counter)" >&2
  exit 1
fi
if ! echo "$output" | grep -q "Maven Wrapper bootstrapped successfully on attempt 1"; then
  echo "FAIL: Missing success marker in output: $output" >&2
  exit 1
fi
echo "PASS: Normal immediate success verified."

echo "=== Test 2: Transient failure then success on retry (MVN-T02) ==="
reset_counter
# 模拟第 1 次网络失败（返回 1），第 2 次成功（返回 0）
export MAVEN_BOOTSTRAP_MAX_ATTEMPTS=3
export MAVEN_BOOTSTRAP_INITIAL_DELAY_SEC=0
export MAVEN_BOOTSTRAP_CMD="count=\$((\$(cat '$counter_file') + 1)); echo \$count > '$counter_file'; if [ \$count -eq 1 ]; then echo 'wget: Failed to fetch' >&2; exit 1; else echo 'probe success'; exit 0; fi"

output=$(bash "$target_script")
status=$?

if [ "$status" -ne 0 ]; then
  echo "FAIL: Expected exit 0 after retry, got $status" >&2
  exit 1
fi
if [ "$(get_counter)" -ne 2 ]; then
  echo "FAIL: Expected probe count 2, got $(get_counter)" >&2
  exit 1
fi
if ! echo "$output" | grep -q "Maven Wrapper bootstrapped successfully on attempt 2"; then
  echo "FAIL: Missing retry success marker in output: $output" >&2
  exit 1
fi
echo "PASS: Transient failure recovered on retry verified."

echo "=== Test 3: Continuous failure exits non-zero with error (MVN-T03) ==="
reset_counter
# 模拟所有尝试均失败（例如网络持续不可达）
export MAVEN_BOOTSTRAP_MAX_ATTEMPTS=3
export MAVEN_BOOTSTRAP_INITIAL_DELAY_SEC=0
export MAVEN_BOOTSTRAP_CMD="count=\$((\$(cat '$counter_file') + 1)); echo \$count > '$counter_file'; echo 'wget: Failed to fetch' >&2; exit 1"

set +e
output=$(bash "$target_script" 2>&1)
status=$?
set -e

if [ "$status" -eq 0 ]; then
  echo "FAIL: Expected non-zero exit for continuous failure, got 0" >&2
  exit 1
fi
if [ "$(get_counter)" -ne 3 ]; then
  echo "FAIL: Expected probe count 3, got $(get_counter)" >&2
  exit 1
fi
if ! echo "$output" | grep -q "Maven Wrapper bootstrap failed after 3 attempts"; then
  echo "FAIL: Missing failure error marker in output: $output" >&2
  exit 1
fi
echo "PASS: Continuous failure clearly blocked with non-zero exit code."

echo "=== Test 4: Custom max attempts override ==="
reset_counter
export MAVEN_BOOTSTRAP_MAX_ATTEMPTS=2
export MAVEN_BOOTSTRAP_INITIAL_DELAY_SEC=0
export MAVEN_BOOTSTRAP_CMD="count=\$((\$(cat '$counter_file') + 1)); echo \$count > '$counter_file'; exit 1"

set +e
output=$(bash "$target_script" 2>&1)
status=$?
set -e

if [ "$status" -eq 0 ]; then
  echo "FAIL: Expected non-zero exit, got 0" >&2
  exit 1
fi
if [ "$(get_counter)" -ne 2 ]; then
  echo "FAIL: Expected probe count 2, got $(get_counter)" >&2
  exit 1
fi
echo "PASS: Custom max attempts override verified."

echo "All bootstrap-maven-wrapper tests PASSED successfully."
