# 本地系统接线与真 Worker 联调 Lane Registry

- 日期：2026-11-14
- 起点：`dev @ 59d00cf0`（Wave 1 lane 基线）
- 最终中心基线：`dev @ 024c274a`（Wave 1.5 + Wave 2 收口）
- 设计：`docs/design/local-system-wiring-and-verification-2026-11-14.md`
- 中心工作树：`E:\project\ai-collab`
- 证据根目录：`docs/acceptance-evidence/2026-11-14/local-wiring/`

## Wave 1 Lane（已归档删除）

| Lane | 分支 | Worktree | 证据目录 | 归档 tag |
| --- | --- | --- | --- | --- |
| W1 | `wiring/rerank-pipeline` | `..\ai-collab-wt\wiring-rerank` | `w1-rerank/` | `archive/20261114/wiring-rerank-pipeline` |
| W2 | `wiring/rag-strategy-queryplan` | `..\ai-collab-wt\wiring-rag-strategy` | `w2-rag-strategy/` | `archive/20261114/wiring-rag-strategy-queryplan` |
| W3 | `wiring/loopback-perf-archunit` | `..\ai-collab-wt\wiring-loopback-perf` | `w3-loopback-perf/` | `archive/20261114/wiring-loopback-perf-archunit` |
| W4 | `wiring/frontend-memory-ui` | `..\ai-collab-wt\wiring-memory-ui` | `w4-memory-ui/` | `archive/20261114/wiring-frontend-memory-ui` |
| W5 | `wiring/f1-candidate-review` | `..\ai-collab-wt\wiring-f1-review` | `w5-f1-review/` | `archive/20261114/wiring-f1-candidate-review` |

## Wave 1.5 Lane（已归档删除）

| Lane | 分支 | Worktree | 证据目录 | 归档 tag |
| --- | --- | --- | --- | --- |
| B（demo6 SQL/JIT） | `fix/demo6-sql-jit` | `..\ai-collab-wt\fix-demo6-sql-jit` | `wave1_5/demo6-sql-jit/` | `archive/20261114/wiring-demo6-sql-jit` |
| C（Agent PG 时钟） | `fix/agent-postgres-timing` | `..\ai-collab-wt\fix-agent-postgres-timing` | `wave1_5/agent-postgres-timing/` | `archive/20261114/wiring-agent-postgres-timing` |

## Wave 2 集成栈（已清理）

| 组件 | 地址 / 端口 | 结果 |
| --- | --- | --- |
| 隔离 PostgreSQL | `127.0.0.1:5433`，库 `ai_collab_wiring_demo` | 克隆 `ai_collab` 后使用，收口已删除 |
| 专用 Reranker `llama-server.exe` | `127.0.0.1:18081` | Qwen3-Reranker-0.6B；收口已停止 |
| `ai-worker-api` | `127.0.0.1:19100` | Ed25519；收口已停止 |
| 隔离后端 | `127.0.0.1:18095`（management 18096） | 收口已停止 |
| 隔离前端 | `127.0.0.1:15177` | BSK Edge；会话已停止 |
| 本机 Magpie | `127.0.0.1:3425` | 只读复用；未 logout / 重启 / 改 provider / 扫码 |
| QueryPlan 注入 stub | `127.0.0.1:19102` | 仅低置信度/超时注入；收口已停止 |

## 资源锁

| 锁 | 默认占用者 | 规则 |
| --- | --- | --- |
| `local-gpu` | `llama-server.exe`（18081） | Wave 2 独占，不并发启动第二个实例 |
| `ai-worker` | `ai-worker-api`（19100） | 单实例串行复用 |
| `model-provider` | Magpie（3425） | 只读调用，禁止并发压测 |
| `local-db` | 各 lane / Wave 2 独立库 | 使用 `ai_collab_*` 专用名，收口删除 |
| `bsk` | UI 验收 | 单会话串行，验收完成后 `session stop` |

## Wave 0 检查记录与清理

- 端口预检：`3425`（Magpie）、`5173`（node）、`8080`（java）已有既有进程；本轮未占用。
- 空闲 BSK daemon（PID `40324`）已停止；空目录
  `E:\project\ai-collab-wt\p1-approval-inbox` 已删除。
- `E:\project\ai-collab-wt` 在 lane worktree 删除后为空并已移除；当前只剩主 worktree。
- 临时 Ed25519 密钥目录 `%TEMP%\ai-collab-wiring-keys` 与隔离库已删除。

## 状态词约束

本目录只使用 `IMPLEMENTED`、`TESTED(UNIT)`、`TESTED(INTEGRATION)`、
`TESTED(REAL_PROCESS)`、`MEASURED`、`MEASURED_LOCAL`、`BLOCKED`、`NOT_RUN`
表达执行事实，不写 `PASS` / `FAIL` 或“验收通过”。

## 最终收口状态

| 范围 | 提交 / 证据 | 状态 |
| --- | --- | --- |
| W1 Reranker/W2 策略/W3 守卫/W4 UI/W5 F1 | `a707aa99…93a8e756` 等 Wave 1 提交 | `IMPLEMENTED` / `TESTED(UNIT)`；真进程见 Wave 2 |
| Wave 1.5 B | `0921b6e1` | `IMPLEMENTED` / `TESTED(INTEGRATION)` / `MEASURED_LOCAL`；P95 20.228ms |
| Wave 1.5 C | `2f5159de` | `IMPLEMENTED` / `TESTED(INTEGRATION)`；全量 3123/0/0/61 exit=0 |
| Wave 1.5 中心门禁 | `09399312` | `TESTED(INTEGRATION)`：完整 `mvnw verify` exit=0 |
| Wave 2 五条闭环 | `024c274a` + [wave2/README.md](wave2/README.md) | `TESTED(REAL_PROCESS)` / `MEASURED_LOCAL` |
| Planning scope 修复 | `34b1e67b` | `IMPLEMENTED`；定向 18 项 exit=0 |
| CI | push `dev` 后 GitHub Actions | 结果见最终回复与 `gh run view` |
