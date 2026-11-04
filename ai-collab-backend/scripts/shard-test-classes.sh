#!/usr/bin/env bash
# 按类名哈希把 Surefire 测试类均匀拆分为 N 个分片，供 CI 并行执行。
# 用法: shard-test-classes.sh <shard-index> <shard-count> <output-dir>
# 产出: <output-dir>/includes-<i>.txt（每行一个测试类 FQCN，交给
#       -Dsurefire.includesFile 使用；全部类恰好落入一个分片）。
set -euo pipefail

shard_index="${1:?shard index required}"
shard_count="${2:?shard count required}"
output_dir="${3:?output dir required}"
src_root="src/test/java"

if ! [ "$shard_index" -ge 1 ] 2>/dev/null || [ "$shard_index" -gt "$shard_count" ]; then
  echo "shard index $shard_index out of range 1..$shard_count" >&2
  exit 1
fi

mkdir -p "$output_dir"
tmp_all="$(mktemp)"
trap 'rm -f "$tmp_all"' EXIT

# 与 Surefire 默认 include 模式（Test*.java / *Test.java / *Tests.java /
# *TestCase.java）保持一致，避免分片清单与默认扫描集合出现偏差。
find "$src_root" -type f -name '*.java' | while read -r path; do
  base="$(basename "$path")"
  case "$base" in
    Test*.java | *Test.java | *Tests.java | *TestCase.java) ;;
    *) continue ;;
  esac
  rel="${path#"$src_root"/}"
  fqcn="${rel%.java}"
  echo "${fqcn//\//.}"
done | sort > "$tmp_all"

total="$(wc -l < "$tmp_all")"
if [ "$total" -eq 0 ]; then
  echo "no test classes found under $src_root" >&2
  exit 1
fi

for ((i = 1; i <= shard_count; i++)); do
  : > "$output_dir/includes-$i.txt"
done

while IFS= read -r fqcn; do
  hash_decimal=$((16#$(printf '%s' "$fqcn" | md5sum | cut -c1-8)))
  shard=$((hash_decimal % shard_count + 1))
  echo "$fqcn" >> "$output_dir/includes-$shard.txt"
done < "$tmp_all"

for ((i = 1; i <= shard_count; i++)); do
  echo "shard $i: $(wc -l < "$output_dir/includes-$i.txt") / $total test classes"
done
