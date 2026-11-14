# 本地系统接线与真 Worker 联调报告（Wave 2 暂停）

- 日期：2026-11-14
- 基线：`dev @ 59d00cf0`
- Wave 1 主集成提交：`f285b40e`（未 push；整合修复在 `4749f7e7`）
- 范围：Wave 1 接线、单元证据、集成门禁与收尾；按用户指示**暂不进入 Wave 2**

## 结论与状态词

| 范围 | 状态 | 事实 |
| --- | --- | --- |
| W1 专用 Reranker 接线 | `IMPLEMENTED` / `TESTED(UNIT)` | 新增两个默认 false 的开关；32 项定向测试 exit=0；真实 18081 与 Shadow `NOT_RUN` |
| W2 策略路由与 QueryPlan | `IMPLEMENTED` / `TESTED(UNIT)` | 问答/流式接线与 QueryPlan 回退单测通过；中心修复 `RetrievalQaProperties` 装配；真实 worker `NOT_RUN` |
| W3 回环守卫 / demo6 / ArchUnit | `IMPLEMENTED` / `TESTED(UNIT)` / `MEASURED_LOCAL` | 白名单、守卫、3 条 ArchUnit 规则与 demo6 拆因完成；完整 Java 门禁 `BLOCKED` |
| W4 记忆 UI | `IMPLEMENTED` / `TESTED(UNIT)` | 抽屉、API、轮询、候选审批、角色收紧完成；BSK/Ed25519 闭环 `NOT_RUN` |
| W5 F1 复核 | `IMPLEMENTED` / `TESTED(UNIT)` | 80/80 逐条 review，v4 晋升；真实模型质量 `NOT_VERIFIED` |
| Wave 2 | `NOT_RUN` | 用户明确要求收尾，不启动 llama-server、ai-worker、隔离后端/前端或 BSK |

## 交付内容

- W1：`RetrievalPipeline` 配置升级与 top-8 head/tail 编排；`retrieval.configured-rerank.enabled`、`retrieval.inline-rerank-top8` 默认 false。
- W2：`Mode.STRATEGY_AWARE`、`retrieval.qa-mode`、两条 QA 链路接线、`QueryRouter` 可选 QueryPlan；默认 `DENSE_LEGACY`、`QUERY_PLAN_ENABLED=false`。
- W3：`ai.outbound.local-allowed-targets` 仅 local/test、精确 loopback 白名单与启动失败守卫；接入模型 Provider 出站；`archunit-junit5` 与最小架构护栏；demo6 JIT/序列化/日志拆因。
- W4：`agent-memory-api.ts`、`AgentMemoryDrawer.vue`、AgentView 入口；PENDING_REVIEW 审批、停用与 Job 轮询。
- W5：`decide_baseline.v4.jsonl`、review ledger、manifest、SHA-256 与确定性晋升脚本。
- 整合修复：`4749f7e7` 将 `RetrievalQaProperties` 改为 `@EnableConfigurationProperties` 绑定，修复全应用上下文启动失败。

## 门禁原始结果

| 门禁 | 结果 | 原始证据 |
| --- | --- | --- |
| Java 全量 round 1 | 首轮在 W2 装配缺陷处启动失败，未作为最终结果 | `center/raw/java-verify-round1.txt` |
| 修复后定向 | `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`，exit=0 | `center/raw/java-targeted-round1-fix.txt` |
| Java 全量 round 2 | 仅 3 个既有 Agent Postgres 时序/容器连接用例错误（`AgentCrashRecoveryTimingPostgresTest`、`AgentDelegationLedgerPostgresTest`、`AgentDelegationPostgresTest`）；完整命令未取得 exit=0，记 `BLOCKED` | `center/raw/java-verify-round2.txt` |
| 上述 3 类单独复跑 | `Tests run: 52, Failures: 0, Errors: 0, Skipped: 0`，exit=0 | `center/raw/java-agent-postgres-retest.txt` |
| 前端四门禁 | `lint` / `typecheck` / `test` / `build` 均 exit=0；68 files、515 tests；lint 0 error / 69 warnings | `center/raw/frontend-*.txt` |
| Python 全量 | `1284 passed, 1 warning`，exit=0；首轮因宿主机存在 `SPEC_AGENT_TAVILY_API_KEY` 被 worker allowlist 拦截，清掉该环境变量后复跑 | `center/raw/python-pytest.txt`、`center/raw/python-pytest-attempt2.txt` |

Java 全量第二轮失败的三类均为既有测试在整套 Testcontainers 负载下的时序/连接问题；没有修改断言、阈值、测试集或业务代码来伪造全量结果。

## demo6 拆因事实

- 10,000 个任务、首页 200 条、响应 `87,495` bytes。
- JIT on：HTTP P95 DEBUG `267.8ms`、INFO `243.0ms`；SQL Execution Time 中位数约 `217.1ms`。
- JIT off：HTTP P95 DEBUG `29.7ms`、INFO `30.0ms`；SQL Execution Time 中位数约 `24.7ms`。
- Jackson P95 `0.391ms`；DEBUG 日志每请求约 `15.5–19.3KB`，但 JIT off 时未形成可区分延迟。
- 结论：本机复现的主要耗时来自 PostgreSQL JIT，不是 Jackson、DEBUG 日志或 curl 客户端；未修改 SQL、阈值或日志级别。详见 `w3-loopback-perf/demo6-breakdown.md`。

## F1 复核事实

- 80 条：64 golden + 16 adversarial；review ledger 80 条，17 条有显式修正。
- v4 raw SHA-256：`866f88f388237201c130973363d1c3d2c7d77643555f721f735654d0df107f93`。
- v4 canonical SHA-256：`abf169b85526e2b1471e8d6ce939b447273b5c8994d76f7e0a12b9f3a9ff484a`。
- replay 仅作为标签/解析/校验证据，`qualityClaim=NOT_VERIFIED`；未把它当真实模型质量。
- v1/v2/v3 与 `decide_real_gemini31.v1.jsonl` 与 `dev` 逐字节相同，历史哈希未变。

## 开关、迁移与配置差异

- 仓库默认保持不变：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
  `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`、
  `AGENT_MCP_GATEWAY_ENABLED=false`、`MQ_ENABLED=false`、`QUERY_PLAN_ENABLED=false`；
  `retrieval.qa-mode=DENSE_LEGACY`、`retrieval.configured-rerank.enabled=false`、
  `retrieval.inline-rerank-top8=false`、`ai.outbound.local-allowed-targets=""`。
- Flyway 仍为 V1–V101；没有新增或改写历史迁移；`main` 未触碰。
- 根 `.env` 的 `JWT_ACCESS_TOKEN_MINUTES=30` 与代码/`.env.example` 默认 `15` **仍不一致**；
  本轮没有擅自修改用户已有 `.env`。本地应用若未显式覆盖，会采用现有 `.env` 的 30 分钟。

## 清理与未执行项

- 隔离库 `ai_collab_wiring_demo` 已删除；临时 Ed25519 密钥文件已删除；`target/wiring-run` 已清理。
- 5 个 lane worktree 已删除，lane 分支已打 `archive/20261114/wiring-*` tag 后删除；主工作树仅保留 `dev/main`。
- Wave 2 的 `llama-server`、`ai-worker-api`、隔离后端/前端、BSK 会话均未启动，因此没有真实 Reranker P95、Shadow 非零指标、QueryPlan 真 worker、F2/G 双跑或 Memory 闭环数据。
- Java 全量最终 exit=0 `NOT_REACHED`；第二轮的 3 个既有 Agent Postgres 错误仍保留原文，不宣称门禁通过。
- 未 push，未触发 GitHub Actions CI；因未完成全量门禁且未进入 Wave 2，按收尾状态留在本地 `dev`。
- `E:\project\ai-collab-wt\p1-approval-inbox` 仍为空目录，被既有 `bsk.exe`（本轮探测 PID `40324`）工作目录占用；未强杀无关 BSK 进程，按锁占用记录保留。

## 证据索引

- Lane 证据：`w1-rerank/`、`w2-rag-strategy/`、`w3-loopback-perf/`、`w4-memory-ui/`、`w5-f1-review/`
- 中心原始门禁：`center/raw/`
- Lane registry：`lanes.md`
- 设计：`docs/design/local-system-wiring-and-verification-2026-11-14.md`
