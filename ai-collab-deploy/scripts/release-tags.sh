#!/usr/bin/env bash
# GHCR 发布标签推导（单一实现来源）：CI release job 与本地测试共用。
# 用法: release-tags.sh <git-ref> <commit-short-sha>
# 输出: "backend-tags,逗号分隔 frontend-tags,逗号分隔"（不含 registry 前缀）
# 规则: v* 标签 -> v<版本>, sha-<short>；其余（main/dev）-> sha-<short>, main, latest
#       镜像路径整体小写（GHCR 要求）。
set -euo pipefail

REF="${1:?用法: release-tags.sh <git-ref> <short-sha>}"
SHORT="${2:?用法: release-tags.sh <git-ref> <short-sha>}"

if [[ "$REF" = refs/tags/v* ]]; then
    version="${REF#refs/tags/v}"
    backend="v${version},sha-${SHORT}"
    frontend="v${version},sha-${SHORT}"
else
    backend="sha-${SHORT},main,latest"
    frontend="sha-${SHORT},main,latest"
fi
printf '%s %s\n' "$backend" "$frontend"
