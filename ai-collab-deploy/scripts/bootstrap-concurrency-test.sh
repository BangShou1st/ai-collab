#!/usr/bin/env bash
# 首管理员提权的确定性并发安全测试（对应 install.sh/install.ps1 的提权事务）。
# 场景 1（确定性交错）：复现「会话1 持锁期间尚未提交、会话2 阻塞取锁」的旧快照风险，
#   验证会话2 的检查与提权使用锁释放后的新快照——绝不可能双提权。
# 场景 2（并行压测）：N 个提权事务同时启动，断言恰好一个赢家。
# 前置：本地一键栈在运行（compose.local.yml）。只创建/清理自己的测试用户，
#       不触碰业务数据；目标锁键与安装脚本一致（917463）。
# 用法: bootstrap-concurrency-test.sh [容器名] [用户] [库名]
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$DEPLOY_DIR/local.env"
LOCK_KEY=917463
CONCURRENCY="${CONCURRENCY:-8}"

read_kv() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -1 | cut -d= -f2-; }

POSTGRES_CONTAINER="${1:-$(docker compose --env-file "$ENV_FILE" -f "$DEPLOY_DIR/compose.local.yml" ps -q postgres 2>/dev/null)}"
POSTGRES_USER="${2:-$(read_kv POSTGRES_USER)}"
POSTGRES_DB="${3:-$(read_kv POSTGRES_DB)}"
if [ -z "$POSTGRES_CONTAINER" ] || [ -z "$POSTGRES_USER" ] || [ -z "$POSTGRES_DB" ]; then
    echo "FAIL: 无法确定目标容器/凭据；请先执行 install 或显式传参" >&2
    exit 3
fi

psql() { docker exec -i "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"; }
QA() { psql -t -A -c "$1"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

U1=conc_probe_a
U2=conc_probe_b
cleanup() {
    psql -q -c "DELETE FROM app_user WHERE username LIKE 'conc_probe%';" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

# 造两个普通测试用户（pgcrypto 生成合法 bcrypt；结构与业务迁移一致）。
QA "CREATE EXTENSION IF NOT EXISTS pgcrypto;" >/dev/null
for u in "$U1" "$U2"; do
    psql -q -c "INSERT INTO app_user (id, username, password_hash, display_name, system_admin)
        VALUES (gen_random_uuid(), '$u', crypt('conc-probe-only', gen_salt('bf', 4)), 'concurrency probe', false)
        ON CONFLICT (username) DO UPDATE SET system_admin = false;" >/dev/null
done
[ "$(QA "SELECT count(*) FROM app_user WHERE system_admin = true;")" = "0" ] || {
    # 已有真实管理员的系统无法做「从 0 提权」的测试：使用隔离探针用户无效。
    echo "SKIP: 系统已存在管理员，本测试需要空管理员库（请在新装栈上运行）"
    exit 0
}

echo "== 场景 1：确定性交错（持锁期间他人提交） =="
# 会话1：取锁后持锁 3 秒（pg_sleep），其间提交自己的提权；会话2 于 0.5 秒后启动，
# 必然阻塞在咨询锁上，并在会话1 提交后才能执行自己的检查/提权语句。
# 咨询锁的阻塞语义即精确同步点：会话2 的独立 count/UPDATE 语句（READ COMMITTED
# 语句级新快照）必然晚于会话1 的提交，从而验证旧快照竞态已被消除。
docker exec -i "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -1 -v ON_ERROR_STOP=1 \
    -c 'SELECT pg_advisory_xact_lock(917463);' \
    -c 'SELECT pg_sleep(3);' \
    -c "UPDATE app_user SET system_admin = true WHERE username = '$U1' RETURNING 'S1_PROMOTED';" \
    > /tmp/conc-s1.out 2>&1 &
S1=$!
sleep 0.5
docker exec -i "$POSTGRES_CONTAINER" psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -1 -v ON_ERROR_STOP=1 \
    -c 'SELECT pg_advisory_xact_lock(917463);' \
    -c "SELECT 'S2_COUNT=' || count(*) FROM app_user WHERE system_admin = true;" \
    -c "UPDATE app_user SET system_admin = true WHERE username = '$U2' AND (SELECT count(*) FROM app_user WHERE system_admin = true) = 0 RETURNING 'S2_PROMOTED';" \
    > /tmp/conc-s2.out 2>&1 &
S2=$!
wait $S1 $S2 2>/dev/null
echo "--- 会话1 输出 ---"; cat /tmp/conc-s1.out
echo "--- 会话2 输出 ---"; cat /tmp/conc-s2.out

U1_STATE="$(QA "SELECT system_admin FROM app_user WHERE username='$U1';")"
U2_STATE="$(QA "SELECT system_admin FROM app_user WHERE username='$U2';")"
[ "$U1_STATE" = "t" ] || fail "场景 1：会话1 提权丢失（U1=$U1_STATE）"
[ "$U2_STATE" = "f" ] || fail "场景 1：双提权发生——持锁期间他人提交后，会话2 的检查/提权仍使用了旧快照（U2=$U2_STATE）"
ADMIN_TOTAL="$(QA "SELECT count(*) FROM app_user WHERE system_admin = true;")"
[ "$ADMIN_TOTAL" = "1" ] || fail "场景 1：管理员数量 $ADMIN_TOTAL ≠ 1"
grep -q 'S1_PROMOTED' /tmp/conc-s1.out || fail "场景 1：会话1 提权未生效"
grep -q 'S2_COUNT=1' /tmp/conc-s2.out || fail "场景 1：会话2 获锁后的新快照计数应为 1（可见会话1 已提交的提权）"
grep -q 'UPDATE 0' /tmp/conc-s2.out || fail "场景 1：会话2 的提权语句不应更新任何行"
echo "场景 1 PASS：旧快照场景下第二个事务正确放弃提权（U1=t, U2=f, 管理员数=1，S2 新快照可见 S1 提交）"

echo "== 场景 2：并行压测（$CONCURRENCY 个提权事务同时启动） =="
# 清理探针后记录起点：0 起点恰 1 个赢家，非 0 起点为 0 个赢家。
cleanup
START_ADMINS="$(QA "SELECT count(*) FROM app_user WHERE system_admin = true;")"
expected_winners=0
[ "$START_ADMINS" = "0" ] && expected_winners=1
probe_users=()
for i in $(seq 1 "$CONCURRENCY"); do
    u="conc_probe_$i"
    probe_users+=("$u")
    psql -q -c "INSERT INTO app_user (id, username, password_hash, display_name, system_admin)
        VALUES (gen_random_uuid(), '$u', crypt('conc-probe-only', gen_salt('bf', 4)), 'concurrency probe', false)
        ON CONFLICT (username) DO UPDATE SET system_admin = false;" >/dev/null
done
pids=()
tmpd="$(mktemp -d)"
for u in "${probe_users[@]}"; do
    psql -t -A -1 -v ON_ERROR_STOP=1 \
        -c "SELECT pg_advisory_xact_lock($LOCK_KEY);" \
        -c 'SELECT count(*) FROM app_user WHERE system_admin = true;' \
        -c "UPDATE app_user SET system_admin = true WHERE username = '$u' AND (SELECT count(*) FROM app_user WHERE system_admin = true) = 0 RETURNING 1;" \
        > "$tmpd/$u.out" 2>&1 &
    pids+=($!)
done
wait "${pids[@]}"
# 输出行序：count 行在前（每事务一条），RETURNING 行仅在真正提权时出现。
# 起点已有管理员（S>0）时：count 行=S、无 RETURNING 行，赢家应为 0；
# 起点为 0 时：恰好一个事务 count=0 且 RETURNING 1，其余 count=1 无 RETURNING。
winner_count=0
for u in "${probe_users[@]}"; do
    numeric=($(grep -E '^[0-9]+$' "$tmpd/$u.out"))
    count_line="${numeric[0]:-}"
    promote_line="${numeric[1]:-}"
    echo "  $u count=$count_line promote=${promote_line:-none}"
    [ "${promote_line:-}" = "1" ] && winner_count=$((winner_count + 1))
done
rm -rf "$tmpd"
WINNERS="$(QA "SELECT count(*) FROM app_user WHERE system_admin = true;")"
[ "$winner_count" -eq "$expected_winners" ] ||
    fail "场景 2：$CONCURRENCY 个事务中出现 $winner_count 个提权赢家（起点管理员=$START_ADMINS，期望 $expected_winners）"
[ "$WINNERS" -eq "$((START_ADMINS + expected_winners))" ] ||
    fail "场景 2：最终管理员数 $WINNERS 与预期 $((START_ADMINS + expected_winners)) 不符"
echo "场景 2 PASS：$CONCURRENCY 个并行提权事务的赢家数符合预期（$winner_count）"

echo "bootstrap-concurrency-test: 全部通过"
