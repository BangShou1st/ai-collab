# 本地系统接线与真 Worker 联调 Lane Registry

- 日期：2026-11-14
- 基线：`dev @ 59d00cf0922eb115ea643df9d639d569f0828ecf`
- 设计：`docs/design/local-system-wiring-and-verification-2026-11-14.md`
- 中心工作树：`E:\project\ai-collab`
- 证据根目录：`docs/acceptance-evidence/2026-11-14/local-wiring/`

## Lane 登记

| Lane | 分支 | Worktree | 预留端口 | 证据目录 | 冲突边界 |
| --- | --- | --- | --- | --- | --- |
| W1 | `wiring/rerank-pipeline` | `E:\project\ai-collab-wt\wiring-rerank` | 18091 | `w1-rerank/` | `RetrievalPipeline`、配置、相关测试 |
| W2 | `wiring/rag-strategy-queryplan` | `E:\project\ai-collab-wt\wiring-rag-strategy` | 18092 | `w2-rag-strategy/` | `ProjectKnowledgeRetrieval`、`Knowledge*Service`、`QueryRouter`、相关测试；不改 `RetrievalPipeline` |
| W3 | `wiring/loopback-perf-archunit` | `E:\project\ai-collab-wt\wiring-loopback-perf` | 18093 | `w3-loopback-perf/` | 回环守卫、`demo6` 拆因、ArchUnit、`pom.xml` |
| W4 | `wiring/frontend-memory-ui` | `E:\project\ai-collab-wt\wiring-memory-ui` | 前端 15176 / 后端 18094 | `w4-memory-ui/` | 仅 `ai-collab-frontend/` |
| W5 | `wiring/f1-candidate-review` | `E:\project\ai-collab-wt\wiring-f1-review` | 无 | `w5-f1-review/` | 仅 `ai-collab-ai-worker/tests/fixtures/decide_set/` |

## Wave 2 集成栈端口

| 组件 | 地址 / 端口 | 备注 |
| --- | --- | --- |
| 隔离 PostgreSQL | `127.0.0.1:5433`，库 `ai_collab_wiring_demo` | 资源锁 `local-db` |
| 专用 Reranker `llama-server.exe` | `127.0.0.1:18081` | 资源锁 `local-gpu`；模型 `D:\models\qwen3-reranker-0.6b-q8_0.gguf` |
| `ai-worker-api` | `127.0.0.1:19100` | 资源锁 `ai-worker`；Ed25519 |
| 隔离后端 | `127.0.0.1:18095` | 临时开关仅用于本轮，仓库默认不变 |
| 隔离前端 | `127.0.0.1:15177` | BSK 验收 |
| 本机 Magpie | `127.0.0.1:3425` | 只读复用；不 logout / 重启 / 改 provider / 扫码 |

## 资源锁

| 锁 | 默认占用者 | 规则 |
| --- | --- | --- |
| `local-gpu` | `llama-server.exe`（18081） | Wave 2 独占，不并发启动第二个实例 |
| `ai-worker` | `ai-worker-api`（19100） | 单实例串行复用 |
| `model-provider` | Magpie（3425） | 只读调用，禁止并发压测 |
| `local-db` | 各 lane / Wave 2 独立库 | 使用 `ai_collab_*` 专用名，收口删除 |
| `bsk` | UI 验收 | 单会话串行，验收完成后 `session stop` |

## Wave 0 检查记录

- 端口预检：`3425`（magpie）、`5173`（node）、`8080`（java）已有既有进程；本轮不使用这些端口。
- `E:\project\ai-collab-wt\p1-approval-inbox`：空目录，删除时返回 `being used by another process`。
- 只读 PEB 当前目录探测：`bsk.exe`（PID `40324`，`C:\Users\32962\.local\bin\bsk.exe`）工作目录指向该目录；未强制终止进程，留待 BSK 收口后复查。

## 状态词约束

本目录只使用 `IMPLEMENTED`、`TESTED(UNIT)`、`TESTED(INTEGRATION)`、`TESTED(REAL_PROCESS)`、`MEASURED`、`MEASURED_LOCAL`、`BLOCKED`、`NOT_RUN` 表达执行事实，不写 `PASS` / `FAIL` 或“验收通过”。
