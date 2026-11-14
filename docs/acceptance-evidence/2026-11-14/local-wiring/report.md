# 本地系统接线与真 Worker 联调报告（Wave 1.5 + Wave 2 完成）

- 日期：2026-11-14
- 起点：`dev @ 93a8e756`（Wave 1 收口）
- Wave 1.5 集成门禁：`dev @ 09399312`
- 范围：Wave 1 接线、Wave 1.5 清债、Wave 2 真进程五条闭环；生产/CI 默认不变

## 结论与状态词

| 范围 | 状态 | 事实 |
| --- | --- | --- |
| W1 专用 Reranker / top-8 | `IMPLEMENTED` / `TESTED(REAL_PROCESS)` / `MEASURED_LOCAL` | BSK 测试、发布并绑定 18081；Agent RAG_V2 真实调用；9 样本 P95 `280ms`，top-8 样本 `candidates=8`，无 `inline_candidate_limit` |
| W1 Shadow | `MEASURED_LOCAL` | `rerank.calls{mode="shadow"}` 成功 6、超时 1；latency count 7；top-K overlap count 6、sum 6.0、max 1.0 |
| W2 策略路由 | `TESTED(REAL_PROCESS)` | `DENSE_WITH_BOUNDED_FALLBACK` / `HYBRID` / `MULTI_QUERY_HYBRID` 均真进程命中 |
| W2 QueryPlan | `TESTED(REAL_PROCESS)` | 真 worker `ADOPTED confidence=0.62`；低置信度 0.1 与超时均回退 |
| F2 Decide | `TESTED(REAL_PROCESS)` / `MEASURED_LOCAL` | shadow CALL_TOOL agreement=match；python `python:CALL_TOOL` + `python_fallback:decision_final`；停 worker `worker_unavailable`；v1/v4 golden 一致率 `0.95/0.953125`，`qualityClaim=NOT_VERIFIED` |
| G Planning | `TESTED(REAL_PROCESS)` | shadow 成功；python `python:SKELETON` + Java detail/reflect + 确认落库 3 任务；worker 422/停机均回退 |
| W5 Memory Job | `TESTED(REAL_PROCESS)` | BSK 触发 Ed25519 Job → 2 候选 → 审批 → 2 条 ACTIVE → 移除项目描述后仍召回（memoryChars 57 → 1291） |
| Wave 1.5 Java 门禁 | `TESTED(INTEGRATION)` | 集成后完整 `mvnw verify`：3123 tests / 0 failures / 0 errors / 61 skipped / exit=0，23:08 min |
| Wave 2 证据 | `MEASURED_LOCAL` / `TESTED(REAL_PROCESS)` | 全部原始日志、JSON、指标与截图见 [wave2/README.md](wave2/README.md) |

## Wave 1 交付

- W1：`RetrievalPipeline` 配置升级与 top-8 head/tail 编排；`retrieval.configured-rerank.enabled`、
  `retrieval.inline-rerank-top8` 默认 false。
- W2：`Mode.STRATEGY_AWARE`、`retrieval.qa-mode`、两条 QA 链路接线、`QueryRouter` 可选 QueryPlan；
  默认 `DENSE_LEGACY`、`QUERY_PLAN_ENABLED=false`。
- W3：`ai.outbound.local-allowed-targets` 仅 local/test、精确 loopback 白名单与启动失败守卫；
  `archunit-junit5` 与最小架构护栏；demo6 JIT/序列化/日志拆因。
- W4：`agent-memory-api.ts`、`AgentMemoryDrawer.vue`、AgentView 入口；PENDING_REVIEW 审批、
  停用与 Job 轮询。
- W5：`decide_baseline.v4.jsonl`、review ledger、manifest、SHA-256 与确定性晋升脚本。

## Wave 1.5 清债

- A 环境：停止空闲 BSK daemon（session 列表为空），删除空目录
  `E:\project\ai-collab-wt\p1-approval-inbox`；根 `.env` 的 `JWT_ACCESS_TOKEN_MINUTES` 由 30
  改为 15（`.env` 被 gitignore，不入库）。
- B 任务分页：`TaskMapper.listPageScoped` 先只对 `project_task` 分页 id + total，再回连页内
  最多 200 行；每页仍 2 条 Mapper SQL。10k 任务、JIT=on 真进程 P95 `20.228ms`（目标 <100ms）。
- C Agent 时钟：实测运行中 PostgreSQL 比 Windows 宿主快约 `5.074s`；`claimNext` 全部比较与
  时间戳统一为数据库 `now()`；新增 `agent.scheduling-enabled`（生产默认 true），测试 profile
  只关闭动态调度，保留执行器与全部业务 Bean。
- D 小修：`application.yml` 补 `retrieval.qa-mode` 默认绑定；`ArchitectureGuardTest` 注释由四条
  更正为实际的五条冻结边。
- 集成提交：`d6982958`、`0921b6e1`、`2f5159de`、`09399312`（证据提交）。

## Wave 2 真进程关键结果

完整细节、限制与原始文件索引见 [wave2/README.md](wave2/README.md)。

1. **Reranker**：BSK 在 Reranker 管理页测试（SUCCESS 57ms / overhead 1ms）并发布 v3，绑定全局
   `KNOWLEDGE_RERANK`；Agent `search_project_knowledge` 真实进入 ConfiguredReranker。
   9 个样本 P95 `280ms`；top-8 样本 `candidates=8`，无 `inline_candidate_limit`。
2. **Shadow**：`RERANK_MODE=shadow` 下 6 次成功、1 次 3s 超时；top-K overlap 6 次全为 1.0。
   Agent 的 9 候选调用触发 ConfiguredReranker 冻结的 8 候选 inline 契约回退；因此 local 默认
   保持 `RERANK_MODE=java`，不把 shadow + configured-rerank 作为默认组合。
3. **RAG 策略/QueryPlan**：三种路由真实命中；复杂问题真实 worker 返回
   `ADOPTED confidence=0.62 subQueryCount=5`；stub 注入 `LOW_CONFIDENCE 0.1` 与 `TIMEOUT`
   均安全回退。
4. **F2**：shadow 1 次 CALL_TOOL agreement=match；python 依次出现 `python:CALL_TOOL`、
   `python_fallback:decision_final`，停 worker 后 `python_fallback:worker_unavailable`。
   v1/v4 真 worker 采数：golden 一致率 `0.95` / `0.953125`，JSON 合法率 `1.0`，
   `LOW_CONFIDENCE`、`qualityClaim=NOT_VERIFIED`；adversarial rejectedRate `0.0` 不作为质量结论。
5. **G**：shadow 成功 9.4s、schema pass；python `python:SKELETON`（10.5s）→ Java DETAIL/REFLECT，
   确认前 `confirmation=null` 且任务数 0，确认后 3 个任务、计划 `CONFIRMED`；worker 422 与停机
   均回退 Java。另修复 `InternalTokenIssuer.SERVICE_SCOPES` 缺少 `planning` 的 Ed25519 401，
   定向 18 项 exit=0。
6. **W5**：`MEMORY_JOB_ENABLED=true` + Ed25519，BSK 触发 Job `SUCCEEDED`；两个候选来自项目描述，
   BSK 批准 1 条、API 批准 1 条，均 `ACTIVE`；移除项目描述后新 Agent 会话仍回答
   `2026-12-20 / Aurora`，`memoryChars` 57 → 673 → 1291，明确引用两条 ACTIVE DECISION 记忆。

## 开关、迁移与配置差异

- 仓库生产/CI 默认保持不变：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
  `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`、
  `AGENT_MCP_GATEWAY_ENABLED=false`、`MQ_ENABLED=false`、`QUERY_PLAN_ENABLED=false`；
  `retrieval.qa-mode=DENSE_LEGACY`、`retrieval.configured-rerank.enabled=false`、
  `retrieval.inline-rerank-top8=false`、`ai.outbound.local-allowed-targets=""`。
- local profile 仅额外打开 `STRATEGY_AWARE`、`configured-rerank + inline-rerank-top8` 与
  `127.0.0.1:3425` 本机白名单；`QUERY_PLAN_ENABLED`、`DECIDE_MODE`、`PLANNING_DRAFT_MODE`、
  `MEMORY_JOB_ENABLED` 仍保持 java/false，按 `.env.example` 第 11 节一键开启。
- W6 保持 `CLOSED_AS_DEFAULT_OFF`。
- Flyway 仍为 V1–V101；没有新增或改写历史迁移；`main` 未触碰。

## 清理与 CI

- Wave 2 临时数据库 `ai_collab_wiring_demo`、llama-server、ai-worker、隔离后端/前端、临时
  Ed25519 密钥、query plan stub 与 BSK 会话在收口时清理。
- 5 个 Wave 1 lane worktree/分支按 `archive/20261114/wiring-*` tag 归档后删除；只保留
  `dev`/`main` 与主 worktree。
- 一次 push `dev`；GitHub Actions CI 结果在最终回复中记录。

## 证据索引

- Wave 1.5：[wave1_5/README.md](wave1_5/README.md)、
  [center/raw/java-verify-integrated-final.txt](center/raw/java-verify-integrated-final.txt)
- Wave 2：[wave2/README.md](wave2/README.md)、[wave2/raw/](wave2/raw/)
- 设计：[docs/design/local-system-wiring-and-verification-2026-11-14.md](../../../design/local-system-wiring-and-verification-2026-11-14.md)
- 台账：[docs/capability-matrix.md](../../../capability-matrix.md)
