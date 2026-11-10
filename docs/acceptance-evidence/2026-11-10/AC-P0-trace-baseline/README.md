# AC-P0 Trace 与评测基线审计

> 工作包：AC-01（Trace 关联与冻结评测集）的前置基线审计（AC-P0）。
> 审计方式：纯只读代码/迁移/文档核查；唯一产出为本报告。
> 设计依据：`docs/design/AI-Collab-2026-11-10.md` §3（3.1 运行记录模型 / 3.2 故障可定位验收 / 3.3 冻结评测集 / 3.4 评测指标及裁决）。

---

## 1. 审计范围与代码基线（SHA）

| 项 | 值 |
| --- | --- |
| 任务指定基线 | `dev` HEAD `c6cb49a6`（c6cb49a6d3ca93a8e1709e69ec0ca682d1a86fb8，fix(agent): 补提交 P1 遗漏的 3 个记忆错误码定义） |
| 审计实际工作区 | 审计进行期间工作区被并行 AC07-P1 wave2 会话切到分支 `feat/ac07-p1-wave2-revision` @ `b6a8f066`（feat(db): V88 新增 agent_memory_revision），并有少量未提交的 Memory 相关文件（`AgentMemoryRequest.java` / `AgentMemoryView.java` / `ErrorCode.java` 等） |
| 影响评估 | AC07-P1 wave2 只触碰记忆域文件与 V88 迁移，不触及 agent_run / agent_step / agent_run_event / agent_tool_invocation / agent_usage_settlement / ai_call_log 等任何 trace 面；本报告全部 file:line 在 `b6a8f066` 下核对，结论对 `c6cb49a6` 同样成立 |
| 迁移版本计数 | 88 个迁移（V1–V88），下一个可用版本号 **V89** |
| 读过的关键面 | Flyway V22/V23/V27/V28/V47/V51/V52/V56/V57/V58/V59/V60/V61/V63/V64/V67/V68/V81/V82/V83/V85；AgentRuntimeCoordinator / AgentRunEventRecorder / AgentRepository / AgentRecoveryJob / AgentWorker / AgentLeaseRenewer / AgentModelConfigurationStore / AgentModelAccounting / AiCallLogService / 诊断与指标类；评测资产（P9/P10 fixtures、golden set、评测测试与脚本） |

**审计范围声明**：除本报告文件外零写入；所有结论均给出 file:line 证据，未能验证的项显式标注 `NOT_VERIFIED`。

---

## 2. Trace 关联 ID 现状图谱（表/字段/生命周期，file:line）

### 2.1 一次真实 Agent Run 的生命周期（持久化视角）

```
HTTP POST /projects/{p}/agent/sessions/{sid}/messages   AgentSessionController.java:86
  → 创建 agent_session / agent_run(status=CREATED→QUEUED)   V22__add_agent_runtime.sql:16（agent_run 建表）
  → 双轨触发（HTTP 直推 + MQ 派发，M2）：AgentRunQueuedMqIntegrationTest 面板
  → worker 认领：AgentRecoveryJob.claim → AgentRepository.claimNext
      FOR UPDATE SKIP LOCKED + 租约/claim_version/last_progress 结转
      AgentRepository.java:530-560；AgentRecoveryJob.java:23-30
  → 接管即收口未结算模型调用身份：AgentRecoveryJob.java:29
      → AgentRunEventRecorder.closeUnresolvedModelCalls（:1820-1826）
  → 推进：AgentWorker.process（MDC 诊断作用域）→ AgentRuntimeCoordinator.advance
      AgentWorker.java:54-77（AgentDiagnosticScope( run, claimVersion, null )）
  → 每个模型轮：
      ① 持久化调用身份 + 准入边界 beginModelCall
         AgentRuntimeCoordinator.java:567-576 → AgentRunEventRecorder.beginModelCall（:1725-1740）
         先落 agent_usage_settlement 一行（usage_basis=UNKNOWN，token 列 NULL，call_id=gen_random_uuid()）
      ② 出站请求（MODEL_STARTED 事件携带 modelCallId/provider/model/inputBreakdown）
         AgentRuntimeCoordinator.java:579-593；租约续期 AgentLeaseRenewer.forCurrentClaim（:608-613）
      ③ 响应落库：recordModelTurnWithSettlement（同事务：身份行独占结算 + run 累计 +
         agent_step MODEL_TURN + agent_tool_invocation 清单 + MODEL_COMPLETED 事件 + 成本挂账）
         AgentRuntimeCoordinator.java:718 → AgentRunEventRecorder.java:1295-1364
      ④ 工具批分派 AgentToolCallExecutor → recordToolResult（TOOL_CALL_COMPLETED 步 +
         invocation 状态机 PENDING→终态 + TOOL_CALL_COMPLETED/FAILED 事件）
         AgentRunEventRecorder.java:1383-1454
  → 收尾 recordFinal / recordBudgetPartialAnswer / recordFailure / recordCanceled
      AgentRunEventRecorder.java:893-976、1461-1545
```

### 2.2 各表职责与关联字段

| 表 | 主键 | 关联字段 | 说明（file:line） |
| --- | --- | --- | --- |
| `agent_run` | `id uuid` | `session_id` FK、`parent_run_id` FK（V22:20-21）、`retried_from_run_id`（V58，部分唯一索引）、`claim_version`（V47:2）、`model_configuration_id/_updated_at`（V47:3-4）、`model_configuration_snapshot`（V51:1）、`last_event_sequence`（V28）、`last_progress_at`（V60）、`claim_started_at`/`active_elapsed_ms`（V52）、`pause_requested_at`（V61）、`budget_semantics`（V63）、`context_policy_version`（V64） | 根 Run/子 Run 两级（depth CHECK 0/1，V22:41-45）；**无 trace_id / span 列** |
| `agent_step` | `id uuid` | `run_id` FK + `sequence_no`，`UNIQUE(run_id, sequence_no)`（V22:99） | 步骤账本；type CHECK 含 `MODEL_TURN/MODEL_REQUEST/TOOL_CALL_COMPLETED/...`（V22:100-108 + V27 扩 `MODEL_TURN`）；`model_provider/model_name`（V27）；`usage_basis`（V56:18）；**无 status 列、无 parent 引用列——父子关系只靠 sequence 顺序** |
| `agent_tool_invocation` | `(run_id, turn_sequence, ordinal)` | `invocation_id UNIQUE`（确定性 UUID，AgentRunEventRecorder.java:1343）、`tool_call_id`（Provider 侧 id）、`proposal_id`→agent_approval（V47:14-15）、`source_mode`（V59）、`dispatch_started_at/_claim_version/execution_safety`（V67） | 工具调用的执行身份与状态机（7 值 status，含 `UNKNOWN`，V47:9） |
| `agent_run_event` | `id uuid` | `run_id` FK + `sequence_no UNIQUE(run_id, sequence_no)`（V28:8-18）；`cost_amount`（V85:32） | SSE/重放事件源（27 种 type，V28 + V36 + V61）；**step 关联只存在于 payload（stepSequence/invocationId/modelCallId），无 step_id 列** |
| `agent_usage_settlement` | `id uuid` | `run_id` FK + `call_id`，`UNIQUE(run_id, call_id)`（V57:6-22） | 每次真实出站模型请求的持久化身份与一次性结算（kind/input/output_tokens_actual/usage_basis/latency_ms）；**无 provider/model 列** |
| `agent_delegation_ledger` | `id bigserial` | `session_id/parent_run_id/child_run_id/objective_hash`（V81:7-18） | 委派防重台账 |
| `agent_approval` | `id uuid` | `run_id`、`step_id UNIQUE`（V22:139） | 写操作审批事实（result_json/rejection_reason） |
| `agent_session` | `id uuid` | —（V22:3；`working_state` V47:4；聚合预算 `max_aggregate_tokens/aggregate_tokens_used` V83:23-25） | 会话聚合记账的归属行 |
| `ai_call_log` | `id uuid` | `request_id`（业务请求身份，非 run 关联）（V1:307-321） | **独立于 Agent 模块**：只有 RAG 问答（feature 硬编码 `KNOWLEDGE_QA`，AiCallLogService.java:44）与规划（TaskPlanModelClient.java:139-152）写入；**Agent 模型调用完全不写这张表** |

### 2.3 关联 ID 覆盖结论

- **已贯通**：`run_id`（step/invocation/event/settlement/approval/ledger 全部携带）；`session_id`（run/message/ledger）；`parent_run_id`（两级委派）；`modelCallId`（settlement.call_id ↔ MODEL_STARTED/MODEL_COMPLETED 事件 payload ↔ 流式预览，AgentRuntimeCoordinator.java:581、AgentRunEventRecorder.java:1357）；`invocationId`（invocation ↔ 事件 payload，AgentRunEventRecorder.java:1445）。
- **缺失**：全库迁移中 **无任何 `trace_id` / `span_id` / `parent_span_id` 列**（grep 全 migration 目录零命中）。OpenTelemetry 的 traceId/spanId 只活在日志 MDC 与 Tempo 导出（application.yml:232-244），**不落库、不与 run 关联**。
- **近似承担**：`phase` ← step.type + event.type；`attempt` ← retry_count/agent_recovery_counter/FORMAT_REPAIR（AgentRunEventRecorder.java:893-910）+ 摘要 attempt 以 agent_step 行身份表达（insertSummaryCallStep :431-442 `RETURNING id`）；`config_snapshot_id` ← model_configuration_id/snapshot。

---

## 3. §3.1 字段差距表（已有 / 部分 / 缺失）

| §3.1 字段 | 档位 | 现状与证据 |
| --- | --- | --- |
| `trace_id` | **缺失** | 无列、无 MDC 业务键；OTel traceId 不落库（见 §2.3）。恢复/重试（`retried_from_run_id`，V58）与委派（`parent_run_id`）已能串起"逻辑 Trace"，但无一等 ID |
| `run_id` | **已有** | `agent_run.id`，全下游表贯通（V22:17） |
| `session_id` | **已有** | `agent_run.session_id`（V22:17）、`agent_message.session_id`（V22:120） |
| `span_id` / `parent_span_id` | **缺失** | 无列；调用树只能由 step.sequence + invocation.turn_sequence 重建，"步骤顺序"与"调用父子"未区分 |
| `step_id` | **已有** | `agent_step.id`（V22:86）；但下游仅 `agent_approval.step_id` 引用（V22:139），invocation 用 `(run_id,turn_sequence,ordinal)` 复合键、事件用 payload stepSequence |
| `phase` | **部分** | 无独立列；`agent_step.type`（V22:100）+ `agent_run_event.type`（V28:14-27）近似覆盖 context/model/tool/approval/summary 场景，但 `retrieval` 无一等 phase（检索内嵌在 search_project_knowledge 工具里） |
| `attempt` | **部分** | `retry_count`/`retry_after`（V22:52-53）、`agent_recovery_counter`（recorder :907-909）、FORMAT_REPAIR（:622-646）、摘要 attempt 身份行（:431-442）；**无统一 attempt 编号列，attempt 语义散在 reason/purpose 中** |
| `status` | **部分** | run 级 11 值状态机（V61:8-12）；invocation 级 7 值含 `UNKNOWN`（V47:9）；**agent_step 无 status 列**（错误靠 error_code，成功靠 type+reason），"模型轮成功但收尾未完成"靠 output_json 内嵌 `batchHandled` 布尔（AgentRepository.java:462-470 查询谓词），非一等字段 |
| `error_code` | **部分** | run.error_code（V22:48）、step.error_code（V22:107）、事件 payload errorCode（recorder :665-668）、invocation result_json.error（:1448）；**无统一错误码字典/映射校验，`UNKNOWN` 在 run 级状态中不存在**（见 §4 问 8） |
| `provider` / `model` | **已有** | run 级（V22:26-27）、step 级（V27）、事件 payload（recorder :1355-1356、coordinator :583-584）；settlement 级缺失（V57 无此列，只能回联事件） |
| `config_snapshot_id` | **部分** | `model_configuration_id` + `_updated_at` + `model_configuration_snapshot` JSONB（V47:3-4、V51:1；写入 AgentModelConfigurationStore.java:34-39，含 configurationId/provider/model/maxOutputTokens/mode）。**缺口：每轮重新解析、快照列只保留最新值，运行中途换配置时历史轮次的快照被覆盖**（轮次身份只剩 step.provider/model） |
| `input_tokens` / `output_tokens` | **已有** | run 级 used（预算封顶）+ actual（如实累计，V56:6-7）；step 级 prompt/completion_tokens（V22:95-96）；settlement 级 actual（V57:11-12）；会话聚合 aggregate_tokens_used（V83:24） |
| `usage_basis` | **已有** | step.usage_basis CHECK `PROVIDER/ESTIMATED/UNKNOWN`（V56:18-20）；settlement.usage_basis 同枚举（V57:13-15）；run 级只有布尔 `token_usage_estimated`（V22:37）**无三分枚举列**；来源判定：provider 上报优先、缺失侧 CJK 感知估算（recorder :1697-1712），usage 来自 Provider adapter 的 usage 字段（OpenAiCompatibleModelAdapter.java:208、AnthropicModelAdapter.java:134、GeminiModelAdapter.java:177、KiloGatewayModelExecution.java:269） |
| `latency_ms` | **已有** | step.latency_ms（V22:106）、settlement.latency_ms（V57:15）、ai_call_log.latency_ms（V1:312）；run 级无总延迟列，由 started_at/finished_at + active_elapsed_ms（V52）近似 |
| `budget_before/after` | **缺失** | 只有累计 used 列（V22:33-37）与软阈值/聚合分级事件（agent.budget.* 指标，AgentRuntimeMetrics.java:46-53）；**无逐轮 budget 快照**，事件 payload 无 budget 字段 |
| `tool_name` | **已有** | step.tool_name（V22:92）、invocation.tool_name（V47:10）、事件 payload toolName（recorder :1444） |
| `result_status` | **部分** | invocation.status 7 值（V47:9）+ step.reason `TOOL_SUCCESS/TOOL_ERROR`（recorder :1430）+ 事件 payload status（:1446）；工具业务结果状态在 result_json.status（:1438）；无统一 result_status 一等列 |
| `citation_ids` | **部分** | `agent_message.citations_json`（V22:121-122，最终答案级）；recordFinal 的 citations 落 message（recorder :1461-1545）；**run/step 级无引用身份列**，工具结果只存 output_json |

**小结**：18 个建议字段中——已有 8（run_id/session_id/step_id/provider+model/tokens/usage_basis/latency/tool_name），部分 7（phase/attempt/status/error_code/config_snapshot_id/result_status/citation_ids），缺失 3（trace_id、span_id/parent_span_id、budget_before/after）。

---

## 4. 故障可定位验收逐问回答（§3.2）

验收问题："对一个真实失败 Run，能够回答：哪次配置快照、哪个模型、哪个工具或检索环节、为何失败、是否重试、是否真实完成写操作、花了多少实际/估算 Token。刷新或 Worker 接管后，同一逻辑步骤不得重复记为两个已结算成功动作；状态可区分 FAILED、TIMEOUT、CANCELED、UNKNOWN。"

| # | 验收问题 | 回答能力 | 证据 |
| --- | --- | --- | --- |
| 1 | 哪次配置快照？ | **部分** | `agent_run.model_configuration_id/_updated_at/_snapshot`（V47/V51；AgentModelConfigurationStore.java:20-42）可回答"最后一次出站用的哪套配置"；**中途换配置时历史轮次快照被覆盖**，只能靠 step.provider/model 反推轮次身份。历史轮次→configurationId 的映射不可精确重建 |
| 2 | 哪个模型？ | **可回答** | step.model_provider/model_name（recorder :1338-1339）、MODEL_COMPLETED payload（:1356）、MODEL_STARTED payload（coordinator :583-584）逐轮可查 |
| 3 | 哪个工具环节？ | **可回答** | invocation 全清单 + 状态机（V47）+ TOOL_CALL_* 事件（recorder :1443-1451）+ TOOL_CALL_COMPLETED 步（:1423-1430）；耗时走 agent.tool.invocation.duration 指标（AgentRuntimeMetrics.java:84-101）与 step.latency_ms（工具结果步未写 latency——只有模型步写，:1337，工具耗时只能从事件时间戳差值推算，**工具步 latency 为部分缺口**） |
| 4 | 哪个检索环节？ | **NOT_VERIFIED（部分）** | 工具级可回答"search_project_knowledge 成败"；但检索内部链路（dense/lexical/rerank/融合）的 `RetrievalTrace`（RetrievalTrace.java:8-25）**仅内存对象，不落库、不被 knowledge 模块消费**，从持久化数据无法回答"检索哪一段丢召回/慢" |
| 5 | 为何失败？ | **可回答** | run.error_code + RUN_FAILED 事件（errorCode/retryable，recorder :663-671、:893-935）+ step ERROR 行（appendErrorStep :1675-1707）+ 未知工具调用显式失败（TOOL_OUTCOME_UNKNOWN，coordinator :267-270） |
| 6 | 是否重试？ | **可回答** | FAILED_RETRYABLE/retry_count/retry_after（recorder :910-928）+ agent_recovery_counter（:907-909）+ RUN_RETRY_SCHEDULED 事件 + 派生链 retried_from_run_id（V58，至多一次派生的部分唯一索引） |
| 7 | 是否真实完成写操作？ | **可回答** | agent_approval（status/result_json/step_id 唯一绑定，V22:137-166）+ invocation.proposal_id（V47:14-15）+ invocation.status；审批执行链有回归防线用例守护（golden-cases `write-approval-task-approve-persists`） |
| 8 | 花了多少实际/估算 Token？ | **可回答** | run.input/output_tokens_actual（V56:6-7）+ token_usage_estimated 布尔 + step.usage_basis 三分枚举（V56:18-20）+ settlement 逐调用账（V57）+ 会话聚合（V83）+ 成本挂事件 cost_amount（V85:29-33，bookUsageCost recorder :642-690）。三表职责清晰：agent_run=累计视图、agent_step=轮次账本、agent_usage_settlement=出站身份幂等账 |
| 9 | 刷新/接管后同一逻辑步骤是否重复记账？ | **可回答（有防护）** | 见 §5，调用身份行 + 条件更新独占 + 接管收口三层防护；未发现重复记账路径 |
| 10 | 状态可区分 FAILED/TIMEOUT/CANCELED/UNKNOWN？ | **部分** | CANCELED/FAILED 显式（V61 状态机）；**run 级无 TIMEOUT、无 UNKNOWN 状态**——超时表现为 `status=FAILED + error_code=AI_MODEL_TIMEOUT`（recorder :648-649），UNKNOWN 只存在于 invocation.status（V47:9）与 usage_basis（V56:20）；"远端结果未知"的工具结局有显式语义（TOOL_OUTCOME_UNKNOWN，recorder :1405-1408、V67:33-38），但 run 终态不区分"失败原因类别" |

### 4.1 现有诊断通道清单

| 通道 | 内容 | 证据 |
| --- | --- | --- |
| Micrometer 计数/计时 | `agent.worker.attempt(+duration)`（按 outcome 分 tag）、`agent.model.request(+duration)`（purpose=turn/summary × outcome）、`agent.tool.invocation.outcome(+duration)`（safety × outcome）、`agent.budget.soft.warning`、`agent.budget.session.warning`、`agent.worker.dispatch.rejected` | AgentRuntimeMetrics.java:26-101 |
| Micrometer 量规 | `agent.run.status.count`（按状态）、`agent.run.lease.expired.count`、`agent.invocation.unknown.count`、`agent.invocation.unknown.oldest.age`、`agent.diagnostics.snapshot.age` | AgentRuntimeStateMetrics.java:20-24 |
| 日志 MDC | `requestId`（服务端自生成、响应头回传 X-Request-Id）、`agentRunId/agentClaimVersion/agentInvocationId`、OTel 注入的 `traceId/spanId`；console pattern 显式输出全部键 | RequestIdMdcFilter.java:27-34、AgentDiagnosticScope.java:11-17、application.yml:243-244 |
| OpenTelemetry | 全采样 OTLP→Tempo（compose 内），仅覆盖 HTTP 请求线程；**agent 后台 worker 未发现显式 Span 创建**（模型/工具调用不产生 OTel span）→ worker 侧关联依赖日志 MDC | application.yml:231-238 |
| 查询 API | `GET /runs/{runId}`（run+steps+totalCost）、`GET /runs/{runId}/events-history`、SSE `/runs/{runId}/events`（Last-Event-ID 续传） | AgentSessionController.java:110/118、AgentEventController.java:28-33、AgentRunDetailResponse.java:11-17 |
| Actuator | 独立管理端口 8081，默认暴露 health,info，部署层可开 prometheus；show-details=never | application.yml:206-228 |

**结论**：§3.2 的 7 个子问题中 6 个可由现有持久化数据直接回答（其中配置快照与检索环节为部分），1 个（状态四分）部分满足；**不存在**"单 Run 一键 trace 导出"（投影查询/JSON 导出/OTel 关联导出）能力，需按 §3.1 的实现策略补投影查询。

---

## 5. 重复记账与接管风险清单

### 5.1 已有防护（按接管时序）

1. **调用身份先行落库**：`beginModelCall` 在请求发出前写 settlement 身份行（UNKNOWN + token NULL），并兼作暂停/取消的持久化准入边界（AgentRunEventRecorder.java:1714-1740）。
2. **结算独占 + 同事务原子**：`recordModelTurnWithSettlement` 用 `UNSETTLED_PREDICATE`（UNKNOWN + token IS NULL，:1742-1744）条件更新独占身份行，失败即抛错回滚整个记账事务；身份行结算、run 累计、step 写入、事件、成本在同一事务（:1746-1778、:1295-1364）。
3. **回滚后同身份补结算**：落库事务因取消/租约/版本冲突失败时，协调器按同一 callId 走 `settleOrphanUsage`，总额仍只加一次（AgentRuntimeCoordinator.java:620-727 六处分支）。
4. **迟到证据幂等补全**：`settleOrphanUsage` 的 `UNCONFIRMED_PREDICATE`（UNKNOWN + COALESCE=0）允许被恢复收口过的 0/0 行被真实证据补全，PROVIDER/ESTIMATED 已确认行拒绝覆盖（recorder :1812-1849）。
5. **接管即收口**：`AgentRecoveryJob.claim` 认领（含抢占过期 RUNNING）成功后立即 `closeUnresolvedModelCalls`，未结算行显式记 0/0+UNKNOWN 终态，不虚构消耗、不长期停留"调用中"（AgentRecoveryJob.java:26-30、recorder :1817-1826）。
6. **已落库响应只消费一次**：`pendingModelTurn` 只取最近一轮未 `batchHandled` 的 MODEL_TURN，接管复用该响应继续收尾而不重新请求模型；batchHandled 由各终态写入在同一事务置位（AgentRepository.java:455-470、coordinator :312-323）。
7. **租约 fencing**：所有落库前 `AgentLeaseScope.verify`（recorder :1297、:1385）；长请求期间 `AgentLeaseRenewer` 按 2 分钟周期把租约推到"当前+6 分钟"，崩溃后仍按短窗口被接管，请求返回时若已失去租约由 epoch 拒绝落库（AgentLeaseRenewer.java:16-44、coordinator :604-613）。
8. **claim 原子性**：`claimNext` 用 CTE `FOR UPDATE SKIP LOCKED` 抢占，双 worker 不会同时获得同一 run（AgentRepository.java:530-556）。
9. **工具结局一次写入**：invocation 状态机 PENDING→终态一次性转换，"Settled invocation cannot be overwritten"（recorder :1391-1408、:1432-1441）；取消时 `preserveUnconfirmedDispatches` 显式保留未决证据（:1644-1660、AgentRepository.java:426）。

### 5.2 残余缺口（按严重度）

| # | 缺口 | 分析 | 定级 |
| --- | --- | --- | --- |
| G1 | **"结果丢失"窗口优于"重复记账"的取舍未被产物化** | 旧 worker 响应在接管后才返回时：身份行已被收口为 0/0 UNKNOWN，`recordModelTurnWithSettlement` 的 claimIdentity（IS NULL 谓词）失败 → 事务回滚 → 响应正文/工具清单丢失，仅 settleOrphanUsage 补全用量（coordinator :725-728）。符合"不重复记账"验收，但**有效模型响应会静默丢弃**，事件流只留下用量补账，无"迟到响应被丢弃"的显式事件/计数 | 低（可接受，建议补观测） |
| G2 | **agent 记账与 ai_call_log 完全脱节** | Agent 模型调用不写 ai_call_log（§2.2），ai_call_log 也无 run_id/call_id 列（V1:307-321）；跨 RAG/规划/Agent 的全局面成本对账需 UNION 三套语义不同的表，且 Agent 侧 request_id 缺位 | 中 |
| G3 | **步骤级 status 缺失导致"部分成功"不可查询** | "模型轮已落库、工具批未消费/收尾未完成"由 output_json.batchHandled 布尔承载（AgentRepository.java:462-470），无一等 status 列；接管语义正确但诊断查询需解析 JSON | 中 |
| G4 | **settlement 不记 provider/model/latency 之外的请求指纹** | settlement 有 latency_ms 但无 provider/model（V57:6-22）；跨配置故障定位需回联事件或 step | 低 |
| G5 | **重试派生是"新 Run"而非同 Trace** | retried_from_run_id（V58）至多一级派生；多次重试链、委派树展开后的完整 Trace 遍历需要递归查询，无 trace_id 一等键 | 中（AC-01 主目标） |

**结论**：未发现"同一逻辑步骤记为两个已结算成功动作"的现实路径——身份行独占 + UNCONFIRMED 补全 + batchHandled 三层使重复结算在事务上不可表达；风险集中在**可诊断性**（G2/G3/G5）而非正确性。

---

## 6. 评测资产盘点与 40–60 条冻结集继承方案

### 6.1 现有资产

| 资产 | 位置 | 规模/格式 | 可复用面 |
| --- | --- | --- | --- |
| **P9 冻结语料** | `tests/fixtures/documents/p9-rag-generalization-01/`（23 文档，A 族 4 + B 4 + C 4 + D 4 + E 3 + F 4，见 corpus-manifest.json 含逐文档 sha256 与 dev/holdout set 标记 + question-manifest.json 21 问 + question-manifest-holdout.json 18 问 + holdout-integrity.json；**勘误 2026-11-11:初稿误写 24 文档，按 manifest 实际 23 修正**） | 冻结于 2026-10-26；marker 为逐字锚点，禁止参与检索 | 语料与 dev/holdout 划分直接继承；**不动人工基准** |
| **P10 QA 题集** | 同目录 `p10-evaluation-question-set.json` | 14 题、12 维度（VERSION_CURRENT/REPEALED/CHANGE、REGION_CONFLICT×2、CROSS_DOC、CONDITION_COMPOUND/NEGATION/TIMEOUT、ABSTENTION×2、UNDETERMINED_HONESTY、PROMPT_INJECTION），每题 expectedFacts/distractFacts/rules(requiredMarkers/legacyMarkers/forbiddenAffirmations) | 与 §3.3 "RAG 15–20 条"高度吻合，14 条可整体继承，差 1–6 条从 holdout manifest 补 |
| **P10 Agent 场景集** | 同目录 `p10-real-generation-03-agent-scenarios.json` | 20 场景 AG01–AG20（SINGLE_SOURCE/MULTI_DOC 等维度，含 seedScope/expectedFacts/forbidden/mustToolCall/maxModelCallsPerScenario/runtimeFixture 约束） | 可作为 Agent 工具选择类 case 的题干来源 |
| **P10 离线评分器** | `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityOfflineEvaluationTest.java` | 零外部模型的确定性评分（marker 命中/禁句/引用契约），固化"证据缺口 vs 生成错误"归因 | §3.4 "自动裁判仅辅助"的现成实现；评分器契约可复用 |
| **其他检索评测测试** | `retrieval/` 下 RagV2QualityEvaluationPostgresTest、RetrievalQualityHardGatePostgresTest（CI 硬门禁）、DuRetrievalBenchmarkPostgresTest、AgentRetrievalQualityPostgresTest、RealRetrievalBaselineTest、EvidenceValueExperiment | Testcontainers 基准 + 门禁 | Recall@K/覆盖率类指标的运行骨架可复用 |
| **Agent golden set** | `ai-collab-backend/src/test/resources/evals/golden-cases.json`（20 case，`"version":1`）+ 运行器 `AgentEvalSuiteTest`/`AgentEvalRunner`（Testcontainers 全链路）+ `GoldenCaseLoader`（schema 契约/BOM 拒绝/畸形样本校验）+ 报告 `target/eval-report.json` | 6 类：TASK_WRITE_APPROVAL(4)、TASK_QUERY(4)、DELEGATION_RESEARCH(4)、BUDGET_CONVERGENCE(3)、LOOP_GUARD(2)、MEMORY_WRITE_APPROVAL(3)；断言面=事件流白名单+invocation 状态+受限 DB probe+模型请求全文捕获；4 个回归防线 case | §3.3 "Agent 工具选择与参数约束 10–15 条"的主体来源；事件/DB probe 断言机制与 §3.4 Tool Calling 指标完全对齐 |
| **业务评测清单** | `docs/acceptance-evidence/business-evaluation-cases.json`（17 条：long-constraints/summary-resume/target-switch/memory-selection/object-identity/insufficient-sources/multi-sources/planning-product-chain/cancel-permission/repair-restart/model-capabilities/configuration-reload/summary-budget/large-result/upgrade 等）+ 配套驱动脚本（run-long-conversation.ps1、run-business-evaluation.ps1 等） | 每条=scenario+testClass+gate；目前**绑定具体集成测试类**而非独立 case JSON | §3.3 "长对话与用户纠正 8–10 条"与"任务依赖/规划/审批/越权 6–10 条"的素材来源，需脱测试化改造 |
| **P9/P10 证据与脚本** | `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`（compute-metrics.cjs、generate-manifests.cjs、finalize-evidence.cjs、metrics.json、retrieval-results.json）与 `.../p10-rag-answer-quality-01/`（rubric.md、baseline.json、failure-analysis.md） | 指标计算脚本与基线格式 | §3.4 指标裁决的 baseline 采集模板 |
| **历史教训** | agent_evaluation_* 五张表 V22 建表 → V25 remove → V33 drop | 评估基础设施曾进库后移除 | 冻结集落 **文件 fixtures**（仓库既有约定 tests/fixtures/evals/README.md）而非业务表，避免重蹈 |

### 6.2 对照 §3.3（40–60 条）的差距与继承方案

| §3.3 桶 | 建议 | 已有可继承 | 缺口 | 方案 |
| --- | --- | --- | --- | --- |
| RAG 单文档/跨文档/反证/旧版本/拒答 15–20 | P10 14 题（12 维度全覆盖）+ P9 holdout 题库 | 14 条现成，字段（expectedFacts/marker/distractFacts/rules）≈ §3.3 要求 | 差 1–6 条；holdout 题不可见性需维持 | 整体继承 P10 14 条；从 `question-manifest-holdout.json`（18 问）挑未进 P10 的题补齐——先核对 holdout-integrity 约束，避免破坏盲测 |
| Agent 工具选择与参数约束 10–15 | golden-cases 20 条中 TASK_QUERY(4)+TASK_WRITE_APPROVAL(4)+DELEGATION_RESEARCH(4) ≈ 12 条 | 断言机制（事件白名单/invocation/DB probe）可直接沿用 | "参数约束"专项（错误参数/越权工具/JSON 畸形）缺 3–5 条 | 以 golden-cases schema 增补参数约束 case；P10 agent 场景的 mustToolCall/forbidden 字段可转换导入 |
| 长对话与用户纠正 8–10 | business-evaluation-cases 的 long-constraints/summary-resume/target-switch/memory-selection + LOOP_GUARD(2)+BUDGET_CONVERGENCE(3) | 场景与 gate 文字现成 | 目前绑定集成测试类，非独立 case；"摘要失败/工具结果过长"专项缺 | 把 4–6 条业务评测场景改写为 golden-cases 风格 case JSON（脚本化模型响应已有 mock 基建），补 2–3 条摘要污染/超长工具结果 case |
| 任务依赖/规划/审批/跨项目越权 6–10 | business-evaluation 的 planning-product-chain/cancel-permission/model-capabilities + MEMORY_WRITE_APPROVAL(3) | 审批/取消/越权链路测试现成 | 跨项目越权专项 case 数量不足 | 继承改写 + 增补 2–3 条跨项目越权（ProjectAccessGuard 断言已有基建） |
| **元数据缺口（共性）** | §3.3 要求每条含 case_id/可信事实来源/期望工具/禁止工具/引用目标/允许结果范围/人工复核状态 | golden-cases 已有 id/category/guards/assertions；P10 已有 expectedFacts/forbidden/rules | **无 `split`(dev/holdout)、`reviewStatus`、`factSource`、`citationTarget`、`allowedRange` 字段；无统一"运行 SHA/模型配置/温度/费率版本"固化字段** | 以 golden-cases.json schema 为骨架扩展字段；运行环境字段参照 `p10-real-generation-03` 的 constraints 节（runtimeFixture/provider/时间墙）与 P9 corpus-manifest 的 sha256 做法；`eval-report.json` 的 version+逐 case 结果格式作 baseline 对比载体 |
| **dev/holdout 划分** | 不能反复用 holdout 调参 | P9 corpus-manifest 有 per-doc `set: dev` 标记与 holdout-integrity.json | golden-cases 20 条全部是 dev（回归防线用途） | holdout 主体用 P9 holdout 题库 + 新增 case 按比例分配；holdout 结果只在最终报告解锁一次 |

**总量测算**：14（RAG）+ 12–15（Agent，含增补）+ 8–10（长对话）+ 6–10（规划/越权）= **40–49 条**，全部来自既有资产继承或既有场景改写，无重复造数据；补充量集中在参数约束与跨项目越权两类。

---

## 7. 与在途工作包冲突面及集成窗口建议

### 7.1 AC-01 将触碰的文件 × 近期提交重叠清单

| AC-01 触碰面 | 近期改动（均在最近 ~20 个提交内） | 冲突程度 |
| --- | --- | --- |
| `AgentRunEventRecorder.java`（1998 行，投影/新字段主要落点） | `37ffb345` MQ M2、`f63858d1` 成本台账（事件 cost_amount 挂载语义）、`a8e95d65` 会话聚合记账、`3f857880` fan-out（delegation.resumeParent）、`d55f2da0` 委派台账、`c5786b7a` 预算软阈值/CJK 估算 | **高**——6 个特性提交刚重构过该文件 |
| `AgentRuntimeCoordinator.java`（1572 行） | 同上 6 提交 + `867d7a02` 死代码清理 | **高** |
| `AgentRepository.java` | 同上（claimNext/children_pending/聚合记账） | 高 |
| 迁移链 | `b6a8f066` 已占用 **V88**；事件 type CHECK 约束（V28+V61）加新事件类型需新迁移；`ck_agent_step_type` 同理 | 高（版本号资源） |
| `ai_call_log`（V1:307 + AiCallLogService/Mapper） | 自 Phase 07（`88be3aa0`）后几乎零改动；只有 `8188bec5`/planning 侧小改 | **低（冷区）**——AC-02 Token 治理也会碰它，需协调 |
| `AgentSessionController` / `AgentRunDetailResponse` / SSE | `37ffb345` 双轨触发改过启动路径；详情 DTO 自 V85 加 totalCost 后稳定 | 中 |
| 事件表消费方（SSE 事件类型枚举 `AgentEventType`、前端事件渲染） | fan-out/暂停恢复（V61）刚扩过事件类型 | 中 |
| 评测资产（golden-cases/eval runner） | `8e2f0488` 刚落地（AgentEvalRunner 白名单契约） | 低（AC-01 只扩 schema，动 GoldenCaseLoader 校验需同步契约测试） |

### 7.2 集成窗口建议

1. **等 AC07-P1 wave2 合入 dev 后再开分支**：当前 `feat/ac07-p1-wave2-revision` 未合入且改了共享文件 `ErrorCode.java`（新增记忆错误码），AC-01 若加 trace 错误码会直接冲突；合入后从最新 dev 拉 `feat/ac01-trace-baseline`。
2. **迁移号从 V89 起**，全部增量加列/加约束，不改 V1–V88 历史（V88 刚占位，AC07-P1 wave3 可能继续占用 V89——开工前先 rebase 核对）。
3. **不动 `f63858d1` 的成本挂账语义**（成本并入最近 MODEL_COMPLETED、settleOrphanUsage 走 bookUsageCost）：AC-01 的投影查询直接消费 cost_amount，不重定义挂载点。
4. **事件写入路径保持单一**：新增 trace 字段优先随 `recordModelTurnWithSettlement`/`recordToolResult` 既有事务落列，不新增旁路写路径（MQ 消费端 `AgentRunQueuedMqIntegrationTest` 等测试面需同步）。
5. **ai_call_log 的归属裁决放到 AC-02 一并做**（G2 缺口），AC-01 只做投影视图，避免与 AC-02 Token 统一口径工作双改同表。

---

## 8. AC-P1（Token/窗口）的最小前置清单（仅建议）

1. **增量迁移（V89+）**：`agent_step` 加 `phase`、`attempt`、`status`、`config_snapshot_id`（或 configurationId 列）；`agent_run_event` 加 `step_id` 可空列；`agent_run` 加 `trace_id`（沿用 run_id 值回填，委派/重试共享）；全部为可空/带默认，不破坏既有 CHECK。
2. **统一 usage 记账写路径裁决**：Agent 侧模型调用是否补写 `ai_call_log`（加 run_id/call_id 列）或正式声明弃用该表于 Agent 域——AC-02 开工前必须裁决（G2）。
3. **投影查询**：提供 run → 时间线（MODEL_STARTED/COMPLETED、TOOL_CALL_*、settlement、cost_amount）的单条 SQL 视图或 `GET /runs/{id}/trace` 只读 API，作为 §3.2 验收的导出载体。
4. **config 快照历史**：中途换配置时把 configurationId 写入当轮 MODEL_COMPLETED 事件 payload（step 已有 provider/model，补 ID 即可闭环问 1）。
5. **工具步 latency**：recordToolResult 补 latency_ms（现在只有模型步写，:1337），对齐 §3.1 latency 字段行。
6. **状态四分**：run 终态语义层区分 TIMEOUT（保留 error_code 为主，投影层派生类别即可，不必动状态机 CHECK）。
7. **冻结集 schema 定稿**：按 §6.2 扩展字段并给 golden-cases 升 `version:2`，同步 GoldenCaseLoader 契约测试与数量守门断言。
8. **OTel 关联**：在 AgentWorker/工具执行器内创建携带 `agent.run.id` attribute 的显式 Span（当前 worker 无 Span），使 Tempo trace 能与库内 run_id 互查——这是把"日志 traceId"升级为"一等 trace_id"的最低成本路径。

---

## 附：最重要发现 Top 5

1. **关联 ID 骨架已基本可用，缺的是"一等 trace 键"**：run_id/session_id/parent_run_id/modelCallId/invocationId 五个 ID 已全链路贯通且事件可透出；全库 0 个 trace_id/span_id 列，OTel traceId 只在日志 MDC，不落库不关联（§2.3、§3）。
2. **重复记账在事务上不可表达**：身份行独占结算（UNSETTLED 谓词）+ 迟到证据幂等补全（UNCONFIRMED 谓词）+ 接管即收口 + batchHandled 单次消费 + epoch fencing 五层防护，未发现重复结算路径；残余风险是"迟到有效响应被丢弃"无显式观测（§5 G1）。
3. **§3.2 七问六答**：配置快照/模型/工具/失败原因/重试/写操作/Token 均可由库内数据回答；检索内部环节（RetrievalTrace 不持久化）与 run 级 TIMEOUT/UNKNOWN 状态区分是仅有的两个降档点（§4）。
4. **agent 记账与 ai_call_log 脱节**：Agent 模型调用完全不写 ai_call_log，该表也无 run 关联列——三套计量表（agent_run/agent_step/settlement）自洽，但全局面成本对账缺统一入口，应在 AC-02 裁决（§5 G2、§7）。
5. **冻结评测集可 100% 继承拼出 40–49 条**：P10 14 QA 题 + golden-cases 20 条 + business-evaluation 17 条素材覆盖 §3.3 四个桶，无重复造数据；共性缺口是 case 元数据（split/reviewStatus/固化环境字段）需 schema 扩展，holdout 主体用 P9 holdout 题库维持盲测纪律（§6）。
