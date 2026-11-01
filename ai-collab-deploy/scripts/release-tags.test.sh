#!/usr/bin/env bash
# GHCR 发布标签推导测试：验证 release-tags.sh（CI 与本测试共用同一实现）
# 的标签规则、小写化结果与安装脚本常量的一致性。
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail() { echo "FAIL: $*" >&2; exit 1; }
assert_eq() { [ "$1" = "$2" ] || fail "$3: 期望 [$2] 实际 [$1]"; }

SHORT="abc123456789"
REPO_ORIGINAL="BangShou1st/ai-collab"

echo "== 标签规则 =="
read -r be fe < <(bash "$SCRIPT_DIR/release-tags.sh" refs/tags/v1.2.3 "$SHORT")
assert_eq "$be" "v1.2.3,sha-$SHORT" "v 标签 backend"
assert_eq "$fe" "v1.2.3,sha-$SHORT" "v 标签 frontend"
read -r be fe < <(bash "$SCRIPT_DIR/release-tags.sh" refs/heads/main "$SHORT")
assert_eq "$be" "sha-$SHORT,main,latest" "main backend"
assert_eq "$fe" "sha-$SHORT,main,latest" "main frontend"
read -r be fe < <(bash "$SCRIPT_DIR/release-tags.sh" refs/heads/dev "$SHORT")
assert_eq "$be" "sha-$SHORT,main,latest" "dev backend"
if bash "$SCRIPT_DIR/release-tags.sh" >/dev/null 2>&1; then
    fail "缺少参数时应报错退出"
fi
echo "标签规则 PASS"

echo "== 小写化与安装脚本一致性（模拟 release job 的 Compute 步骤） =="
base="ghcr.io/${REPO_ORIGINAL,,}"
assert_eq "$base" "ghcr.io/bangshou1st/ai-collab" "小写化后的镜像 base"
if [ "$base" != "ghcr.io/bangshou1st/ai-collab" ]; then
    fail "install 脚本 GHCR 常量与仓库实际小写路径不一致"
fi
grep -q 'GHCR_BACKEND="ghcr.io/bangshou1st/ai-collab/ai-collab-backend"' "$SCRIPT_DIR/../install.sh" \
    || fail "install.sh 后端镜像常量与发布目标不一致"
grep -q 'GHCR_FRONTEND="ghcr.io/bangshou1st/ai-collab/ai-collab-frontend"' "$SCRIPT_DIR/../install.sh" \
    || fail "install.sh 前端镜像常量与发布目标不一致"
grep -q 'ghcr.io/bangshou1st/ai-collab/ai-collab-backend' "$SCRIPT_DIR/../install.ps1" \
    || fail "install.ps1 后端镜像常量与发布目标不一致"
grep -q 'ghcr.io/bangshou1st/ai-collab/ai-collab-frontend' "$SCRIPT_DIR/../install.ps1" \
    || fail "install.ps1 前端镜像常量与发布目标不一致"
# 最终发布标签（模拟 workflow 的 join）：全部小写、无大写字符。
read -r be fe < <(bash "$SCRIPT_DIR/release-tags.sh" refs/heads/main "$SHORT")
full_backend="ghcr.io/bangshou1st/ai-collab/ai-collab-backend:${be//,/,ghcr.io\/bangshou1st\/ai-collab\/ai-collab-backend:}"
if printf '%s' "$full_backend" | grep -q '[A-Z]'; then
    fail "发布标签包含大写字符：$full_backend"
fi
echo "小写化与一致性 PASS"

echo "release-tags.test: 全部通过"
