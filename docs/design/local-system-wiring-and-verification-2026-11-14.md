# 本地系统“各有所用”接线与真 Worker 联调设计（2026-11-14）

> 性质：**设计，不含实现**
> 基线：`dev @ d2a8517b`（CI `43540442044` success；H-1～H-4 / F1 / F2 / G 已集成）
> 目标：把已实现但未接通、或仅过 stub 的能力全部接进可运行路径，用真实本地模型、
>       `ai-worker-api`、`Qwen3-Reranker-0.6B` 与浏览器跑通实证，并在台账定盘
> 边界：不改仓库默认开关（生产零变更）、不碰 `main`、不新增 Flyway 迁移（保持 V1–V101）、
>       不动 W6（`CLOSED_AS_DEFAULT_OFF`）

## 0. 为什么“写了很多，实际好像没用上”

核查源码后，问题不在模块没写完，而是**断在最后一米的配置与调用点**，一共 6 处：

| # | 已写好的资产 | 代码里的真实断点 | 结果 |
| --- | --- | --- | --- |
| 1 | 专用 Reranker 平台（V92–V95 + `ConfiguredReranker` + `LlamaCppRerankAdapter` + N5 管理 UI + `Qwen3-Reranker-0.6B`） | `RetrievalPipeline.execute(..., int topK)` 硬编码 `LEXICAL_HEURISTIC` 且 `rrfTopK=40`；全仓业务代码 **0 处**传入 `CONFIGURED_RERANK`；超过 8 候选又会撞 `RerankExecutionPolicy` 的 `inline_candidate_limit` 回退 | 管理端配好并发布了本地 Reranker，在线检索仍不走它；`RERANK_MODE=python` 在 Agent 工具里也会因 >8 候选直接回退 |
| 2 | RAG 策略路由（`QueryRouter` + `StrategyAwareRetrievalExecutor` + `EvidenceSufficiencyProbe`） | 问答服务固定传 `Mode.DENSE_LEGACY`，直接跳过 `RetrievalPipeline`；`StrategyAwareRetrievalExecutor` 全仓 **0 个调用点** | 上轮 E1 跑 160 次问答时 `rerank.shadow.*` 全部为 0；`DENSE_WITH_BOUNDED_FALLBACK` 从未上线运行 |
| 3 | 查询规划（`QueryPlanGatewayClient` + Python `POST /v1/rag/plan`） | 客户端、配置和 PromptPack 都已建成，但 `QueryRouter` 与检索链路 **0 调用点** | 长期处于“幽灵客户端”状态，无法证明可用或决定是否淘汰 |
| 4 | F1 候选集 + F2 `Decide` + G `Planning` 主链 | F1 80 条仍 `PENDING_REVIEW`；F2/G 只过 Java stub/集成测试，未连真 `ai-worker-api` 与 Magpie；之前 E1 经 Cloudflare quick tunnel 访问本机 `:3425` 出现首波 8/50 `503` 与 `502` | `decide.system@2026-11-13.5`（已由 `load_current` 设为最高版本）未在真实主链跑过，无法评估真实双跑表现 |
| 5 | W5 Memory Job（`AgentMemoryJobController` + `AiJobDispatcher` + `AgentMemoryCandidateController` + Python `/v1/jobs`） | 后端 3 个控制器与状态机已齐全，但前端无触发/审批界面，本地也未用 `ed25519` 跑过 | 在本地 demo 里完全不可见 |
| 6 | 上轮收尾遗留 | `demo6` HTTP P95 `346ms`（curl `1356ms`）vs 服务层 `29ms` 未拆因；`.env` 显式保留 `JWT_ACCESS_TOKEN_MINUTES=30`；残留空目录 `ai-collab-wt\p1-approval-inbox`；无 ArchUnit 护栏；缺能力总台账 | 缺少端到端可观测解释与架构防退化守卫 |

## 1. 总原则（不破坏既有不变量）

1. **仓库默认不变**：所有新接线均由显式开关控制，仓库默认保持
   `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
   `MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`、
   `AGENT_MCP_GATEWAY_ENABLED=false`、`MQ_ENABLED=false`、`retrieval.qa-mode=DENSE_LEGACY`、
   `retrieval.configured-rerank.enabled=false`、`retrieval.inline-rerank-top8=false`、
   `QUERY_PLAN_ENABLED=false`。
2. **不改历史契约测试**：`RerankModeRouter` 和 `ConfiguredReranker` 对“直接传 9/20/50 候选时拒绝
   inline 并记 `inline_candidate_limit`”的冻结契约保持不变；8 候选切窗只在
   `RetrievalPipeline` 编排层通过开关完成。
3. **不新增 Flyway 迁移**：本轮只做接线、UI 最小闭环、护栏与真进程实跑，迁移保持 V1–V101。
4. **安全守卫只松在 `local/test`**：`OutboundEndpointPolicy` 对非本机地址与 `prod`/`staging`
   保持公网 HTTPS 强校验；本机 Magpie `:3425` 环回白名单用与 `McpLoopbackTestGuard`
   同构的 `local/test` 独占守卫，非 `local/test` profile 启用即启动失败。
5. **模型分工清晰**：
   - **在线 3s inline 重排**：走本地专用 Reranker `Qwen3-Reranker-0.6B`
     （`ConfiguredReranker` → `LlamaCppRerankAdapter` → `127.0.0.1:18081/rerank`，实测 P95 ~415ms）；
   - **Python `/v1/rag/rerank`**（经聊天模型打分，P95 ~4.8s）：定位为 `shadow` 离线对比与
     诊断，不硬充 3s inline 主力。

## 2. 五项接线设计

### 2.1 W1：打通专用 Reranker 与 Top-8 Inline 窗口

**目标**：管理员在前端绑定并发布 `Qwen3-Reranker-0.6B` 后，在线检索能真实使用它，且不因
候选池为 20–40 条而触发 `inline_candidate_limit` 回退。

**设计**：

- `RetrievalPipeline` 新增两个受控配置（默认全 `false`；既有 6 参/10 参测试构造器默认 `false`）：
  - `retrieval.configured-rerank.enabled`：为 `true` 且调用方传入的是默认
    `RerankerType.LEXICAL_HEURISTIC` 时，自动升格为 `RerankerType.CONFIGURED_RERANK`
    （若管理端未配置全局/项目绑定，`ConfiguredReranker` 已有按设计回退
    `SemanticHeuristicReranker`，不报错）。
  - `retrieval.inline-rerank-top8`：为 `true` 且当前不是 `RerankMode.SHADOW`、且需要走
    `CONFIGURED_RERANK` 或 `RerankMode.PYTHON` 时，若融合后 `candidates.size() > 8`：
    1. 截取前 8 条送给 `activeReranker.rerank(query, head, head.size())`；
    2. 将第 9 条起的 `tail` 保持原始 RRF 序严格接在重排后的 `head` 之后
       （不把 RRF 0.03 与模型 0.99 分数混排）；
    3. 整体再按 `config.finalTopN()` 截断；
    4. **`RerankMode.SHADOW` 不截成 8 条**，继续把最多 50 条完整列表送给
       `RerankModeRouter.rerankShadow`，保留完整背景对比。
- **验证**：
  - 单测：`RetrievalPipeline` 验证默认 `false` 行为逐字不变；`configured-rerank.enabled=true`
    真实调用 `ConfiguredReranker`；`inline-rerank-top8=true` 在 20 候选下只发 8 候选给
    inline，并保持 `head` 在 `tail` 之前；`SHADOW` 仍送全量 20 候选。
  - `RerankModeRouterTest` / `ConfiguredRerankerTest` 全部原有测试不得修改、保持绿。

### 2.2 W2：接通 RAG 策略路由与 `QueryPlan`

**目标**：让 `StrategyAwareRetrievalExecutor` 与 `QueryPlanGatewayClient` 真正进入可切换路径，
而不是全仓 0 调用点。

**设计**：

1. **QA 策略开关**：`ProjectKnowledgeRetrieval.Mode` 新增 `STRATEGY_AWARE`，并增加配置
   `retrieval.qa-mode`（允许 `DENSE_LEGACY` | `STRATEGY_AWARE`，默认 `DENSE_LEGACY`）。
2. `KnowledgeQuestionApplicationService` 与 `KnowledgeStreamQuestionService` 按配置选择
   `DENSE_LEGACY` 或 `STRATEGY_AWARE`，不再硬编码。
3. `ProjectKnowledgeRetrieval.retrieveStrategyAware(...)`：
   - 注入 `StrategyAwareRetrievalExecutor`（构造器或可选，缺 Bean 在启用 `STRATEGY_AWARE`
     时启动校验失败）；
   - 通过 `QueryRouter.route(query)` 得到 `QueryRouteDecision`；
   - 对单意图普通语义问题走 `DENSE_WITH_BOUNDED_FALLBACK`（保持 Dense-only 的精度和速度），
     仅当 `EvidenceSufficiencyProbe` 判定无有效证据时补一次 `HYBRID`；
   - 对 `STRUCTURED` / `COMPLEX` / `EXACT_IDENTIFIER` 走 `RetrievalPipeline`（从而能同时使用
     `ConfiguredReranker` 或 `RerankModeRouter`）。
4. **`QueryPlan` 接入 `QueryRouter`**：
   - `QueryRouter` 可选注入 `QueryPlanGatewayClient`（缺失或 `query.plan.enabled=false` 时
     零外呼、行为逐字节不变）；
   - 仅当 `query.plan.enabled=true` 且规则判定为 `COMPLEX`（或存在多子问题/歧义）时调用
     `POST /v1/rag/plan`；
   - 若 `confidence >= 0.5` 且 `subQueries`/`rewrittenQuery` 合法，采用其子查询/过滤条件；
   - 任意超时、网络异常、schema 异常或 `confidence < 0.5` 均安全回退本地
     `MultiQueryDecomposer` + `MetadataFilterParser`，并记结构化指标 `rag.query_plan.outcome`。
5. **E1 Shadow 真实触发验证**：
   - 本轮使用 **Agent `search_project_knowledge`（固定 `Mode.RAG_V2`）** 以及
     **`STRATEGY_AWARE` 下的 `COMPLEX/EXACT_IDENTIFIER` 问题**触发 `RetrievalPipeline` →
     `RerankModeRouter`，彻底消除上一轮“跑问答导致 `rerank.shadow.*=0`”的错位。

### 2.3 W3：本机回环守卫 + F1 复核 + F2/G 真 Worker 联调

**1）消除 flaky tunnel：本机模型环回白名单**

- 参考 `McpLoopbackTestGuard`（`agent.mcp.test-loopback-enabled`），新增
  `ai.outbound.local-allowed-targets`（逗号分隔 `host:port`，默认空 `""`）：
  - 新增启动守卫：若配置非空且当前非 `local` / `test` profile，**启动立即抛异常失败**；
  - 即使在 `local/test` 启用，也只放行精确匹配白名单（例如 `127.0.0.1:3425,localhost:3425`）
    且 DNS 必须解析为 loopback 地址，禁止私网段（10/172.16/192.168/169.254）和任意非白名单端口；
  - 让本机 Java 直接安全调本机 Magpie `:3425`，不再依赖不稳定的 Cloudflare quick tunnel。

**2）F1 80 条候选集复核与冻结**

- 按 `ai-collab-ai-worker/tests/fixtures/decide_set/candidates/2026-11-14-local-hardening/README.md`
  的 10 条人工复核清单逐条审查 80 条候选：
  - 核对每条 `CALL_TOOL` / `FINAL` / `ASK_USER` / `NO_OP` 建议是否可由 `request` 上下文严格推出；
  - 核对 16 条 adversarial probe 的预期拒绝 reason 与 `cognition/decide/parser.py` 完全一致；
  - 修正任何复核发现的瑕疵，填写明确 `review` 元数据并晋升为新版本文件
    `ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v4.jsonl`（SHA-256 登记）；
  - **严禁修改 `v1/v2/v3` 与 `decide_real_gemini31.v1.jsonl` 的原始文件与哈希。**

**3）F2 `Decide` & G `Planning` 真 Worker 双跑实测**

- 启动 `ai-worker-api`（`127.0.0.1:19100`，确认 `decide.system` 自动加载 `2026-11-13.5`）与
  隔离后端（`18095`，接 Magpie `workbuddy/deepseek-v4.1-flash`）：
  - **F2 观测**：分别在 `DECIDE_MODE=shadow` 与临时 `DECIDE_MODE=python` 下发起真实
    `PROJECT_RESEARCH` 运行，记录：
    - `MODEL_STARTED.decideRoute = python:CALL_TOOL`（真实执行只读知识检索工具）；
    - `FINAL/ASK_USER/NO_OP` 回退 `python_fallback:decision_*`；
    - 真实超时/worker 停止时的 `python_fallback:worker_*`；
    - 使用 `v1` + `v4` 对真实 `ai-worker /v1/agent/decide` 采样统计一致率与延迟（标 `MEASURED_LOCAL`）。
  - **G 观测**：分别在 `PLANNING_DRAFT_MODE=shadow` 与临时 `PLANNING_DRAFT_MODE=python` 下
    发起真实项目任务规划，记录：
    - `attempt diagnostics_json` 里的 `python:SKELETON`；
    - `TaskPlanDraftValidator(AI_SKELETON)` 重跑与 Java detail 生成；
    - 确认落库前 `confirmation=0`、人工确认后生成正式任务；
    - 真实 worker 不可用时的 `python_fallback:worker_*` 回退。

### 2.4 W4：W5 `Memory Job` 真实闭环 + 前端最小管理入口

**目标**：让 W5 交付的 3 个记忆管理/Job/候选控制器在前端直接可用，并在本地跑通一次
Ed25519 真实提取→审批→召回。

**设计**：

- **前端最小闭环**（仅新增 API 封装与最小抽屉，不重做现有 Agent 页面布局）：
  - 在 `ai-collab-frontend/src/modules/agent/` 补充：
    - `agent-memory-api.ts`：对接 `GET/POST/PATCH/DELETE /api/v1/projects/{id}/agent/memories`、
      `POST/GET /api/v1/projects/{id}/agent/memory-jobs`、
      `GET/POST approve/reject /api/v1/projects/{id}/agent/memory-candidates`；
    - `AgentMemoryDrawer.vue`：在 `AgentView.vue` 顶部增加“项目记忆”按钮打开抽屉，分三栏：
      1. **已发布记忆**（列表、状态标签、停用/编辑）；
      2. **待审候选**（`PENDING_REVIEW` 列表 + 来源引用 + 批准/拒绝按钮，仅管理员可操作）；
      3. **提取任务**（一键发起“提取记忆候选” Job + 状态轮询显示 `QUEUED → RUNNING → SUCCEEDED`）。
  - 遵循 AGENTS.md：无 `any`/`@ts-ignore`，loading/error 收敛，挂载/卸载清理轮询。
- **本地真跑**：
  - 隔离实例临时配置 `INTERNAL_AI_TOKEN_MODE=ed25519` + `AI_WORKER_TOKEN_MODE=ed25519` +
    `MEMORY_JOB_ENABLED=true`（临时生成本机测试 Ed25519 密钥对注入环境变量，用后清理、不入库）；
  - 用 BSK 在页面点“提取记忆候选” → `ai-worker /v1/jobs` → Magpie 提取 → 页面审批通过 →
    新建 Agent 会话验证该记忆进入上下文。

### 2.5 W5：收尾项、ArchUnit 最小护栏与能力台账

1. **`demo6` 346ms 拆因**：在隔离进程对 `GET /api/v1/projects/{id}/tasks?page=0&size=200`
   拆解 `DB 查询` vs `Jackson 序列化（77KB 响应）` vs `DEBUG 日志`（`application-local.yml`
   默认 `com.shitulelv.aicollab: DEBUG`）vs 客户端开销；若确认主要耗时来自本地 `DEBUG` 日志
   或可安全收敛的开销，记录前后对比并给出是否把压测/local 调优建议写入报告。
2. **`.env` 与空目录清理**：
   - 向用户核对或对齐本地 `.env` 的 `JWT_ACCESS_TOKEN_MINUTES`（仓库默认已是 15 分钟）；
   - 排查并清理 `E:\project\ai-collab-wt\p1-approval-inbox` 空目录（若仍被桌面进程占用，记清占用进程名）。
3. **3 条最小 ArchUnit 架构护栏**（测试依赖 `archunit-junit5`，不批量重构历史代码）：
   - 规则 1：`domain/**` 不依赖 `infrastructure/**`；
   - 规则 2：`api/controller/**` 不注入 `*Mapper*`；
   - 规则 3：Repository 接口只位于 `infrastructure/repository` 及其实现包。
   - 采用冻结基线（`FreezingArchRule` 或显式历史豁免清单）保证“现有代码 0 假红、新增违规立即拦截”。
4. **更新 `docs/capability-matrix.md`**：顶部新增“2026-11-14 全能力与开关台账”，把每项能力明确标为
   **默认在线 / 可配置开启（已验证） / 冻结储备**，写清开关名、开启条件、实测数字与证据链接。

## 3. 执行编排（给下一个实现会话）

### 3.1 波次与 Lane 划分（1 中心 + 最多 5 子 agent）

| 波次 | Lane | 内容 | 分支 / worktree | 端口 | 冲突边界 |
| --- | --- | --- | --- | --- | --- |
| Wave 1 | W1 | 专用 Reranker 接线 + top-8 inline 窗 | `wiring/rerank-pipeline` / `..\ai-collab-wt\wiring-rerank` | `18091` | 只改 `RetrievalPipeline` + `application.yml` + 测试 |
| Wave 1 | W2 | RAG 策略路由 + `QueryPlan` 接线 | `wiring/rag-strategy-queryplan` / `..\ai-collab-wt\wiring-rag-strategy` | `18092` | 只改 `ProjectKnowledgeRetrieval`、`Knowledge*Service`、`QueryRouter` + 测试，**不改 `RetrievalPipeline`** |
| Wave 1 | W3 | 模型回环守卫 + `demo6` 拆因 + ArchUnit | `wiring/loopback-perf-archunit` / `..\ai-collab-wt\wiring-loopback-perf` | `18093` | `pom.xml`、`Admin/UserAiProviderService`、`JsonHttpModelClient`、新 guard/ArchUnit |
| Wave 1 | W4 | 前端记忆管理与提取 Job 最小抽屉 | `wiring/frontend-memory-ui` / `..\ai-collab-wt\wiring-memory-ui` | `15176` / `18094` | 仅 `ai-collab-frontend/` |
| Wave 1 | W5 | F1 80 条复核与晋升 `v4` | `wiring/f1-candidate-review` / `..\ai-collab-wt\wiring-f1-review` | 无 | 仅 `ai-collab-ai-worker/tests/fixtures/decide_set/` |

- Wave 1 门禁：每个 Lane 完成后中心复核、按 `W1 → W2 → W3 → W4 → W5` cherry-pick 到 `dev`，
  跑一次全量 Java / 前端 / Python 门禁，全绿再开 Wave 2。
- Wave 2（真进程端到端联调，仅在集成后的隔离环境串行执行资源锁任务）：
  - 启动 `llama-server.exe`（`18081`）+ `ai-worker-api`（`19100`）+ 后端（`18095`）+ 前端（`15177`）；
  - 跑完 §2 全部 5 条真实闭环（BSK UI 配置 Reranker + Agent Shadow 非零 + `STRATEGY_AWARE` +
    `QueryPlan` + F2 Decide 真 worker 双跑 + G Planning 真 worker 双跑 + W5 Memory Job Ed25519 + BSK 审批）。
- Wave 3（台账与收口）：
  - 更新 `docs/capability-matrix.md` 与交接/证据；
  - 删除全部临时数据库、容器、模型服务、临时 Ed25519 密钥、worktree；
  - lane 分支打 `archive/20261114/*` tag 后删除，仅留 `dev`/`main` 与主 worktree；
  - 一次 push `dev`，CI 全绿收口。

### 3.2 资源锁

| 锁 | 占用者 | 规则 |
| --- | --- | --- |
| `local-gpu` | `llama-server.exe`（`18081`） | Wave 2 独占使用 RTX 4060 |
| `ai-worker` | `ai-worker-api`（`19100`） | Wave 2 单实例复用 |
| `model-provider` | 本机 Magpie `:3425` | 只读使用；不 logout/重启/改 provider/扫码；禁止并发压测打挂 Magpie |
| `local-db` | 各 lane 独立库名 | `ai_collab_lh_*`，用完即删 |
| `bsk` | W4 UI 单测后 Wave 2 统一验收 | 用完 `session stop` |

## 4. 验收完成定义（不达标不写 PASS）

1. **专用 Reranker 在线可用**：BSK 配通 `Qwen3-Reranker-0.6B`，Agent `search_project_knowledge`
   触发 `ConfiguredReranker`，8 候选 P95 < 1000ms（历史 baseline ~415ms），不再被 `inline_candidate_limit` 拦截。
2. **Shadow 指标真实非零**：`RERANK_MODE=shadow` 下经 Agent 工具与 `STRATEGY_AWARE` 真实产生
   `rerank.shadow.calls > 0`、`rerank.shadow.latency` 与 `topk_overlap` 数据。
3. **RAG 策略与 QueryPlan 可切换**：`STRATEGY_AWARE` 单意图走 `DENSE_WITH_BOUNDED_FALLBACK`，
   复杂/标识符走对应路径；`QUERY_PLAN_ENABLED=true` 真实调 `/v1/rag/plan` 并验证 `confidence < 0.5`/超时回退。
4. **F1/F2/G 真 Worker 实跑闭环**：`v4` 完成 80 条复核；F2/G 真实连 `ai-worker-api` + Magpie
   跑通 `shadow` 与临时 `python`（分别产生 `python:CALL_TOOL` 与 `python:SKELETON`、以及真实回退证据），不编造质量分数。
5. **W5 Memory Job 端到端**：BSK 在前端记忆抽屉点发起 Job → Ed25519 → `/v1/jobs` → 审批通过 →
   下轮 Agent 会话召回。
6. **收口整洁**：默认开关不变、无新迁移、无私钥/JTL/日志残留、全量 Java/前端/Python 与 CI 全绿、
   只剩 `dev`/`main` 与主 worktree。
