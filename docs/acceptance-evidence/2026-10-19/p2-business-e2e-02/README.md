# P2-BUSINESS-E2E-02 核心业务闭环验收报告

日期：2026-10-19

仓库：`BangShou1st/ai-collab`  ·  分支：`dev`
验收基线：`80dc61b1150256491a90d2d2f9c3de403b609d52`（验收开工时本地与 `origin/dev` 一致）

## 结论

本轮修复和回归已完成。此前阻断 P2 的 RAG 有效引用、Agent 检索引用、规划确认闭环，均在真实浏览器和真实免费模型上完成正向复验。规划 8 任务初次细节生成仍按预期报截断；新增有界拆分后，同一计划的“重试细节 → Owner 编辑 → 确认 → 刷新”成功，未降低产品任务数上限。

严格按本轮退出标准，**P2 核心路径已恢复，但仍不建议把 P2 标为完全关闭**：6 份历史文档需要各自 Owner 重试；Member 访问私有项目的 watcher 修复仅有自动化验证、未在真实 Member 会话中复测；旧 Agent `ERR_ABORTED` 未能复现并确定归因；429、Embedding 服务故障及 SSE 故障注入仍未做真实环境复验。若这些风险作为明确待办接受，可以并行开始 P3；若 P3 的准入要求包括全部异常和历史数据清理，则应先完成这些待办。

所有新项目、文档、计划和任务均是合成验收数据。模型为 Kilo Gateway 的免费模型 `cohere/north-mini-code:free`；没有新增付费凭证。报告、截图和网络记录未保存 Token、Cookie、API Key、请求/响应正文或原始业务 ID。

## E2E 验收矩阵

| 场景 | 最新状态 | 证据与限制 |
|---|---|---|
| E2E-01 登录及工作区 | **PASS（基线复用）** | P2-01 Owner 登录、项目列表和刷新已通过；本轮使用同一 Owner 在合成项目完成 RAG、Agent、规划操作。未重复做独立登录场景。 |
| E2E-02 文档上传与 Embedding | **PASS（有限范围）** | P2-01 已确认系统级 Ollama 配置有效：`http://127.0.0.1:11434/v1/embeddings`、`qwen3-embedding:0.6b`、1024 维；真实文档生成 3 个 READY 分块并重索引至 v1，刷新后保留。本轮引用使用已有 READY 合成文档，没有重复索引。6 份旧 Owner 文档本轮未重试。 |
| E2E-03 知识检索及引用 | **PASS（真实模型，6 类问题）** | 覆盖精确事实、跨分块总结、多来源、材料未记载预算时拒答、文档限制筛选、刷新后的答案与引用。引用卡片对应真实 READY 文档分块。截图：[knowledge-multisource-history.png](knowledge-multisource-history.png)。本轮未改 RAG 代码；P2-01 的失败没有在当前完整前置条件下复现。 |
| E2E-04 Agent 完整引用流程 | **PASS（正常闭环）；异常归因 NOT_TESTED** | 真实 Agent 调用 `search_project_knowledge` 两次，回答带 6 个来源、跨两份项目文档；刷新后完成状态与来源仍在，项目未被 Agent 修改。截图：[agent-completed-two-search-calls-six-sources.png](agent-completed-two-search-calls-six-sources.png)；刷新网络：[agent-history-reload-sanitized.json](network/agent-history-reload-sanitized.json)。此前约 34–36 秒的 `ERR_ABORTED` 本轮未重现，因此不能断言旧事件来自主动取消、页面切换、网络断线、SSE 超时或服务端失败。 |
| E2E-05 AI 规划及人工确认 | **PASS（真实 8 任务闭环）；子场景部分自动化** | 首次细节响应截断并保留 v1 骨架；重试生成 v2（3 个里程碑、8 个任务、3 条依赖），人工修改并保存 v3，Owner 确认一次，刷新后计划版本及正式任务均持久化，正式任务详情含人工修改。看板为 12 项（4 项既存 + 8 项新建）。真实 UI 未重复提交确认；同幂等键重复确认和成员只读权限策略由自动化覆盖，未在浏览器尝试 Member 确认。草稿曾显示 17 项提醒；确认时仍有 13 项，其中包括 8 项未分配任务，需 Owner 后续处理。 |
| E2E-06 任务协作 | **PASS（基线复用）** | P2-01 已通过 Owner/Member 任务权限、状态变更和刷新持久化；本轮另确认 8 个正式任务在看板刷新后不重复。没有重复全套协作操作。 |
| E2E-07 权限隔离 | **PASS（后端拒绝与自动化）；浏览器 watcher NOT_TESTED** | P2-01 Member 访问私有项目得到正确 404。新增前端测试覆盖 404 中性提示、清理旧项目状态、隐藏受保护页面内容和 watcher 异常处理。本轮未用真实 Member 会话复测新的页面状态。 |
| E2E-08 异常与恢复 | **部分 PASS；真实故障注入 NOT_TESTED** | Ollama 配置后真实文档重试与刷新持久化已在 P2-01 通过。规划截断与细节重试本轮真实复验成功。429、Embedding 停机与恢复、SSE 异常断开、并发提交真实注入未执行；Maven 有 18 项显式 opt-in 集成/真实服务测试被跳过。 |

## 根因与修复

### RAG / E2E-03

P2-01 的失败不能据现有证据归因于 RAG 代码缺陷。当时至少有两个前置条件未满足：向量材料尚未处于 READY 状态，且执行问答的管理员没有可用聊天模型；旧报告记录了一次 HTTP 200 但业务校验未通过，未保存足以进一步定位的响应正文。本轮先使用已索引文档和 Owner 可用的真实 Kilo 模型，六类问题均得到符合材料的回答或拒答，引用匹配实际来源。因此本轮没有对检索、引用校验或 SSE 代码做无证据修改。

本轮 RAG 结论以浏览器中可见的回答、来源卡片和刷新历史为依据。当前新增目录未保留每个 RAG 请求的独立 BSK 网络抓包，所以不把单次请求 HTTP 状态或 SSE 事件顺序当作已证明事实。旧失败响应的具体业务 code 也仍未还原。

### Agent / E2E-04

历史上引用链失败与文档未索引有关；在 READY 文档和真实模型条件下，本轮 Agent 确实调用知识检索工具并将返回来源带入最终回答。刷新网络记录显示 Agent 会话、最新 run、事件历史、消息和 planning operation 均重新读取成功（所查 13 个业务 API 请求均为 HTTP 200；完整 BSK capture 153 请求、2 次操作，记录 0 错误、0 丢失）。该 capture 从已完成运行开始，**只验证持久化恢复，不包含此前的模型生成和工具调用请求**；模型调用与 2 次工具调用由真实页面运行观察及截图证明。

旧 `ERR_ABORTED` 在本轮没有重现。恢复链正常不能证明旧中断的具体来源；没有足够证据修改 SSE 或 Agent 架构，故未改 Agent 代码。

### 规划 / E2E-05

使用的模型为 `cohere/north-mini-code:free`（Kilo Gateway）。真实首轮生成了 8 任务骨架和 3 个里程碑；详情输出被供应商以截断类别结束，应用返回 `PLANNING_MODEL_OUTPUT_TRUNCATED`，保留 v1 骨架且没有创建正式任务。现有格式修复无法补回模型未输出的 JSON 尾部和遗漏字段；把不完整响应当作成功会违反结构及业务校验。已保存的首轮 BSK 记录显示 UI 的截断失败结果；精确 provider 数值 token usage 与预算没有进入脱敏证据，因此本报告不猜测输出上限数值。

沿用现有 Skeleton / Detail、配置快照、生成序列和取消流程，新增以下修复：

- 仅遇到明确的 `PLANNING_MODEL_OUTPUT_TRUNCATED` 且仍有多个条目时，将 detail 范围有界拆分，单批最多 4 个任务；失败批次递归细分。非截断错误不会被吞掉或伪装成成功。
- 每批显式声明里程碑/任务 `tempKey` 范围，保留骨架身份字段；所有批次完成后统一合并并运行原有完整业务校验。取消检查适用于批次请求；任何批次失败都不会形成可确认的部分计划。
- 对 Kilo Gateway 的规划请求使用原生 JSON Schema 响应格式；其他 Provider 保持现有提示 JSON 路径。Schema 对描述和骨架字段加合理长度/条目约束，避免不必要的重复输出。
- 产品最大任务数未下调；新增 Schema 测试验证 40 个任务仍被接受。测试覆盖截断后稳定身份与分批合并、输出 schema、生产装配和 Postgres wiring。

真实复验中，初次截断保留骨架；点击一次“重试细节”后 8 任务 v2 完整 READY。Owner 将首任务描述改为包含验收阈值及审批记录检查，并保存说明，形成 v3；确认只提交一次。刷新计划页仍显示“已创建正式任务 · v3”；打开正式任务详情仍有人工修改文本。计划详情显示 3 个里程碑、8 个任务和 3 条依赖。看板刷新前后均为 12 项。网络元数据记录 retry-detail `202`、保存版本 `200`、confirm `200`，刷新相关 GET `200`。详见 [规划 BSK 元数据](network/scale-v3-retry-confirm-sanitized.json) 和 [首轮截断记录](network/scale-v3-truncated-detail-sanitized.json)。

### 私有项目 404 watcher / E2E-07

后端 404 是正确的权限隐藏行为。旧页面让异步 `loadProject` watcher Promise 拒绝未处理，也没有统一的中性访问拒绝页面。现在 store 将 `404` / `PROJECT_NOT_FOUND` 变为访问拒绝状态，清除先前项目缓存；`AppShell` 不渲染私有路由内容，显示“项目不存在或无权访问”。其他错误仍通过既有错误提示处理。新增 store 和 AppShell 单元测试覆盖这些状态。真实 Member 页面复测尚未完成。

## 真实模型、浏览器与自动化分类

| 能力 | 分类 | 结论 |
|---|---|---|
| Ollama Embedding | `REAL_MODEL_PASS`（P2-01） | 本机真实 `/v1/embeddings` 配置，3 个分块并重索引到 v1；本轮使用这些 READY 文档。 |
| RAG | `REAL_MODEL_PASS` | 六类问题在真实模型与真实 READY 文档上通过；不存在资料支持的预算正确拒答。 |
| Agent | `REAL_MODEL_PASS`（正常链路） | 真实模型、两次检索工具调用、6 个有效来源、刷新恢复。旧 `ERR_ABORTED` 根因 `NOT_TESTED`。 |
| AI 规划 | `REAL_MODEL_FAIL`（首次 8 任务细节）；随后 `REAL_MODEL_PASS`（重试与确认） | 截断失败被正确报告；同计划有界重试后完成 8 任务人工编辑和正式写入。 |
| 前端 watcher / 故障注入 | `MOCK_ONLY` / 自动化 | 单测和集成测试不是浏览器或真实服务故障注入证据。 |
| 18 项 opt-in Maven 测试 | `NOT_TESTED` | 需要显式 AI / 真实服务环境开关，本轮未开启。 |

真实 Edge 浏览器为 Microsoft Edge 155，BrowserSkill 0.3.3，当前 BSK session 为后台真实页面操作。截图包括：

- [Kilo 免费模型设置](screenshots/01-kilo-free-model-settings.png)
- [RAG 多来源历史](knowledge-multisource-history.png)
- [Agent 两次检索、六个来源](agent-completed-two-search-calls-six-sources.png)
- [8 任务 v2 细节成功](planning-8-task-v2-success.png)
- [Owner 编辑后 v3 确认](planning-8-task-v3-confirmed.png)
- [确认后刷新看板](planning-8-task-board-after-refresh.png)
- [正式任务详情含人工修改](planning-edited-task-confirmed.png)

首轮规划 debug capture 为 332 请求、15 次操作，0 错误、0 丢失；重试、确认及刷新关键请求另存为脱敏路由元数据。BSK 操作曾因原生 checkbox 隐藏导致 2 次点击/滚动尝试失败，重新观察后点击可见 label 勾选并成功确认；这是自动化操作器定位问题，不是应用业务请求失败。未保存请求/响应正文、头、Cookie 或 query string。

## 代码变更清单

**Backend implementation**

- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/KiloGatewayModelExecution.java`：传递显式规划 JSON Schema。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java`：截断后有界分批重试、稳定身份、Schema 与完整合并校验。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanModelClient.java`：规划配置快照下选择 Schema-capable 路径。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanOutputParser.java`：严格校验结构化输出。

**Backend tests**

- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/model/KiloGatewayModelTest.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestratorIntegrationTest.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanOutputParserTest.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanProductionWiringPostgresIntegrationTest.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanSkeletonSchemaTest.java`（新增）
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanSpringBeanPostgresIntegrationTest.java`

**Frontend**

- `ai-collab-frontend/src/shared/AppShell.vue`、`ai-collab-frontend/src/styles.css`
- `ai-collab-frontend/src/stores/project-context-store.ts`、`ai-collab-frontend/src/stores/project-context-store.test.ts`
- `ai-collab-frontend/src/shared/AppShell.project-access.test.ts`（新增）

RAG 和 Agent 本轮没有代码修改；没有数据库迁移或依赖变更。

## 自动化回归

| 模块 | 实际命令 | 结果 |
|---|---|---|
| 后端定向回归 | `.\mvnw.cmd '-Dtest=TaskPlanGenerationOrchestratorIntegrationTest#eightTaskPlanBatchesSplitTruncatedDetailsAndKeepStableIdentity' test` | 通过。 |
| 后端完整回归 | `.\mvnw.cmd clean verify`（`ai-collab-backend`） | `BUILD SUCCESS`；1,528 tests，0 failures，0 errors，18 skipped。Postgres / pgvector Testcontainers 集成完成。18 项为显式 opt-in 真实 AI / 服务测试。 |
| 前端 lint | `pnpm lint`（`ai-collab-frontend`） | 通过；0 errors，79 warnings（现有 warnings）。 |
| 前端类型 | `pnpm typecheck` | 通过。 |
| 前端测试 | `pnpm test` | 42 个测试文件，243 passed。 |
| 前端构建 | `pnpm build` | 通过；有既存 PURE 注释及大 chunk 提示。 |
| 差异空白检查 | `git diff --check` | 通过。 |

自动化结果用于代码回归，不替代上述真实模型、真实 Edge 与文档引用验收。

## 未解决问题与风险

| 优先级 | 问题 | 状态与处理 |
|---|---|---|
| P1 数据待办 | 6 份 Owner 项目旧文档在历史 Embedding 缺失期间失败 | 本轮没有用管理员越权，也没有获得这些 Owner 的授权会话；本轮重试 `0/6`，实际可恢复数未知。由各自 Owner 在对应项目合法重试后再记录结果。 |
| P2 验收缺口 | Member 私有项目页面 watcher 修复 | 自动化覆盖；真实 Member 页面状态与控制台尚未复验。 |
| P2 未归因异常 | Agent 约 34–36 秒 `ERR_ABORTED` | 正常运行和持久化刷新通过；异常未重现，旧事件原因仍未知。 |
| P2 恢复测试 | 429、Embedding outage/recovery、SSE 异常断开、并发/幂等故障注入 | 未在真实服务执行。相关 opt-in 测试未打开；不要把本轮绿色全量测试解释成这些外部服务异常均已通过。 |
| P3 业务完善 | 新规划 8 个任务仍有 8 个未分配提醒 | 真实确认证明任务正确持久化，不代表已满足所有项目负责人分配要求；由 Owner 在后续执行中补齐负责人。 |
| P3 RAG 取证 | 本轮没有单独保存 6 次 RAG 请求的逐条 BSK 路由/流事件记录 | 六类问答与来源由浏览器 UI/真实模型结果验证；旧 HTTP 200 业务失败的原始响应未保留，具体业务 code 未还原。 |

## Git 与 CI 交付

- 开工时本地 `dev` 与 `origin/dev` 均为 `80dc61b1150256491a90d2d2f9c3de403b609d52`，无预存工作区修改。
- 修复代码、截图及脱敏网络证据提交：`c0effb878f27e367590b0274053f4c0c9b474016`，分支 `dev`；推送后远端 `origin/dev` 与该提交一致。
- 对应 GitHub Actions [CI run 39261506192](https://github.com/BangShou1st/ai-collab/actions/runs/39261506192) 成功，head SHA 为 `c0effb878f27e367590b0274053f4c0c9b474016`。Frontend (pnpm) 与 Backend (Maven verify) 两个 job 均通过；后端 Testcontainers + Flyway 构建及容器测试断言通过。
- 本节在上述代码提交 CI 结束后补记。报告元数据的后续 README-only 提交及其 CI 状态记入最终交付消息；不改变代码验收结果。
