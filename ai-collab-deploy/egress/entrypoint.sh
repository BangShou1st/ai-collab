#!/bin/sh
# egress-proxy 启动脚本：由白名单生成 tinyproxy 配置，任一前提不满足即拒绝启动（fail closed）。
#
# 网络约定：容器同时接入一个 internal 网络（staging 的 data / 本地的 ai，ai-worker 所在）和一个
# 可出网的网络（staging 的 edge / 本地的 default）。internal 网络没有默认路由，因此：
#   - 默认路由所在网卡 = 出网侧，不监听、不放行；
#   - 其余唯一一块网卡 = 内网侧，只在它的地址上监听，Allow 只放行它所在的子网。
# 白名单：每行一个域名，# 起注释，不支持通配符。filter 按 URL 匹配且默认拒绝，
# CONNECT 的 URL 形如 host:port，所以只生成 host:443 —— 普通 http:// 请求和其它端口都匹配不上。
set -eu

ALLOWLIST="${EGRESS_ALLOWLIST:-/etc/egress/allowlist}"
PORT="${EGRESS_PORT:-8888}"
# 容器 read_only，只有 /tmp 是 tmpfs；生成的配置与监听地址（供 healthcheck 读取）都放这里。
RUN_DIR=/tmp/egress
CONF="$RUN_DIR/tinyproxy.conf"
FILTER="$RUN_DIR/filter"

die() {
  echo "egress-proxy: $*" >&2
  exit 1
}

[ -r "$ALLOWLIST" ] || die "白名单 $ALLOWLIST 不可读"
mkdir -p "$RUN_DIR"
: > "$FILTER"

count=0
while IFS= read -r line || [ -n "$line" ]; do
  host=$(printf '%s' "${line%%#*}" | tr -d ' \t\r' | tr 'A-Z' 'a-z')
  [ -n "$host" ] || continue
  printf '%s' "$host" | grep -Eq '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$' \
    || die "白名单条目不是合法域名（不支持通配符/端口/路径）：$host"
  printf '%s:443\n' "$host" >> "$FILTER"
  count=$((count + 1))
done < "$ALLOWLIST"
[ "$count" -gt 0 ] || die "白名单为空"

default_if=$(ip -4 route show default | awk '{for (i = 1; i < NF; i++) if ($i == "dev") { print $(i + 1); exit }}')
[ -n "$default_if" ] || die "没有默认路由：egress-proxy 必须同时接入一个可出网的网络"
default_if=${default_if%%@*}

listen=""
allow=""
for entry in $(ip -o -4 addr show | awk '{print $2 "=" $4}'); do
  ifname=${entry%%=*}
  ifname=${ifname%%@*}
  cidr=${entry#*=}
  case "$ifname" in
    lo | "$default_if") continue ;;
  esac
  [ -z "$listen" ] || die "检测到多块内网侧网卡：egress-proxy 只能接入一个 internal 网络"
  NETWORK=""
  PREFIX=""
  eval "$(ipcalc -n -p "$cidr")"
  [ -n "$NETWORK" ] && [ -n "$PREFIX" ] || die "无法计算 $ifname 的子网"
  listen=${cidr%/*}
  allow="$NETWORK/$PREFIX"
done
[ -n "$listen" ] || die "没有内网侧网卡：egress-proxy 必须接入 ai-worker 所在的 internal 网络"

cat > "$CONF" <<EOF
Port $PORT
Listen $listen
Timeout 120
MaxClients 32
LogLevel Notice
DisableViaHeader Yes
ConnectPort 443
FilterType fnmatch
FilterURLs On
FilterDefaultDeny Yes
Filter "$FILTER"
Allow $allow
EOF
printf '%s\n' "$listen" > "$RUN_DIR/listen"

echo "egress-proxy: listen=$listen:$PORT allow=$allow out=$default_if allowlist=$count"
exec tinyproxy -d -c "$CONF"
