# Wave 2 真进程全链路联调

- 日期：2026-11-14
- 基线：`dev @ 09399312`（Wave 1.5 完整门禁绿）
- 隔离数据库：`ai_collab_wiring_demo`（宿主 `127.0.0.1:5433`，从本机 `ai_collab` 克隆后仅改隔离 provider 指向）
- 隔离后端：`127.0.0.1:18095`，management `18096`，`local` profile
- 隔离前端：`127.0.0.1:15177`，BSK Edge 会话
- `llama-server`：`127.0.0.1:18081`，`D:\models\qwen3-reranker-0.6b-q8_0.gguf`（`--rerank -ngl 99 -c 8192`）
- `ai-worker-api`：`127.0.0.1:19100`，Ed25519；临时密钥对只存 `%TEMP%`，收口删除
- 本机 Magpie：`http://127.0.0.1:3425`，只做只读模型调用；未 logout / 重启 / 改 provider / 扫码，未使用 Gemini

## 状态总表

| 闭环 | 事实 | 状态 |
| --- | --- | --- |
| W1 专用 Reranker | BSK 在 Reranker 管理页对 v3 档案执行测试（SUCCESS 57ms / overhead 1ms）并发布，再绑定全局 `KNOWLEDGE_RERANK`；Agent `search_project_knowledge`（RAG_V2）真实调用 ConfiguredReranker | `TESTED(REAL_PROCESS)` |
| W1 P95 / top-8 | 9 个 ConfiguredReranker 样本，P50 `119ms`、P95 `280ms`、max `280ms`；Agent top-8 样本 `candidates=8 totalMs=280`，未出现 `inline_candidate_limit` | `MEASURED_LOCAL` |
| W1 Shadow | `RERANK_MODE=shadow` 下 6 次成功、1 次超时；`rerank.calls{mode="shadow",outcome="success"}=6`、`rerank.latency_seconds_count{mode="shadow"}=7`、`rerank.shadow.topk_overlap_count=6` 且 sum/max=`6.0/1.0` | `MEASURED_LOCAL` |
| W2 RAG 策略 | 单意图 `DENSE_WITH_BOUNDED_FALLBACK`、精确标识符 `HYBRID`、复杂对比 `MULTI_QUERY_HYBRID` 均在真进程命中 | `TESTED(REAL_PROCESS)` |
| W2 QueryPlan | 真实 ai-worker `POST /v1/rag/plan` 返回 `ADOPTED confidence=0.62 subQueryCount=5`；stub 注入 `LOW_CONFIDENCE confidence=0.1` 与 `TIMEOUT` 均安全回退 | `TESTED(REAL_PROCESS)` |
| F2 Decide 主链 | shadow：1 次有效观察，CALL_TOOL，agreement=match，latency 2.17s；python：`python:CALL_TOOL` 与 `python_fallback:decision_final`；停 worker：`python_fallback:worker_unavailable` | `TESTED(REAL_PROCESS)` |
| F2 真 worker 采数 | `decide_baseline.v1`：32 条，golden 20 条一致率 `0.95`、JSON 合法率 `1.0`；v4：80 条，golden 64 条一致率 `0.953125`、JSON 合法率 `1.0`；两者 `LOW_CONFIDENCE`、`qualityClaim=NOT_VERIFIED` | `MEASURED_LOCAL` |
| G Planning shadow | 新 token scope 修复后 1 次成功，latency `9.407s`，schema pass，SOFT issues=10；Java 计划 `READY` | `TESTED(REAL_PROCESS)` |
| G Planning python | `python:SKELETON` handled（10.5s）；attempts：SKELETON `PYTHON_PLANNING_DRAFT` → DETAIL Magpie 7.7s → REFLECT Magpie 2.3s；确认前 `confirmation=null` 且项目任务数 0，确认后 3 个任务、计划 `CONFIRMED` | `TESTED(REAL_PROCESS)` |
| G Planning 回退 | worker 返回 422 时 `python_fallback:worker_http` 并由 Java 完成；停止 worker 后 `python_fallback:worker_unavailable`；两者都保留 Java detail 与可用状态 | `TESTED(REAL_PROCESS)` |
| W5 Memory Job | BSK 抽屉触发 Job（Ed25519）→ worker `/v1/jobs` 200 → `SUCCEEDED`；两个候选从项目描述提取，BSK 批准 1 条、API 批准 1 条，均落为 `ACTIVE` | `TESTED(REAL_PROCESS)` |
| W5 召回 | 移除项目描述后新会话仍回答“2026-12-20 / Aurora”；`MODEL_STARTED.inputBreakdown.memoryChars` 57 → 673（单条）→ 1291（两条），回答明确引用两条 ACTIVE DECISION 记忆 | `TESTED(REAL_PROCESS)` |

## W1 专用 Reranker 与 Shadow

### UI 配置与发布

- 隔离库中把原有 RERANK provider `17dae765...` 与 model profile `396774d4...` 指向
  `127.0.0.1:18081/rerank`；新能力档案 `71cb0f14...`（v3）经真实
  `POST /api/v1/admin/ai/capability-profiles` 创建为 DRAFT（现有管理页只提供测试/
  发布/绑定，无新建档案表单）。
- BSK 在“AI 供应商 → Reranker 管理”页执行测试：`SUCCESS`，`总耗时 57ms · overhead 1ms`，两个文档分数
  `1.0000 / 0.0001`；随后发布并绑定全局 `KNOWLEDGE_RERANK`。页面截图见
  [raw/reranker-ui-bound.png](raw/reranker-ui-bound.png)。
- 复测期间服务端日志显示 Agent `search_project_knowledge` 的 ConfiguredReranker 真实完成：
  `projectId=93b67960... profileId=71cb0f14... providerId=17dae765...`。

### P95 与 top-8

[raw/w1-configured-rerank-samples.json](raw/w1-configured-rerank-samples.json) 汇总了本次会话 9 个
`configured rerank 完成` 样本：P50 `119ms`、P95 `280ms`、max `280ms`，均 < 1s。Agent top-8 样本
`candidates=8 totalMs=280 modelMs=280`；Java 模式没有出现 `inline_candidate_limit` 回退。

### Shadow

`RERANK_MODE=shadow` 的指标（[raw/metrics-shadow-summary.txt](raw/metrics-shadow-summary.txt)）：

- `rerank_calls_total{mode="shadow",outcome="success"} 6.0`、`fallback 1.0`
- `rerank_latency_seconds_count{mode="shadow"} 7`
- `rerank_shadow_topk_overlap_count 6`、`sum 6.0`、`max 1.0`
- `rerank_shadow_failure_total{reason="timeout"} 1.0`

其中 Agent 工具的 9 候选调用因 ConfiguredReranker 冻结的 8 候选 inline 契约回退
`inline_candidate_limit`，同时 Python shadow 在 3s deadline 内超时；STRATEGY_AWARE 的 6 次
2 候选子查询 shadow 成功。结论：shadow 指标已真实非零，但 `configured-rerank + shadow` 在
>8 候选时会让 Java 侧降级，local 默认因此保持 `RERANK_MODE=java`。

## W2 RAG 策略与 QueryPlan

路由原文见 [raw/w2-route-lines.txt](raw/w2-route-lines.txt)：

| 问题类型 | 实际路由 | 样本 |
| --- | --- | --- |
| 单意图“请总结项目中的主要功能模块和业务流程” | `DENSE_WITH_BOUNDED_FALLBACK`，fallbackUsed=false | [raw/w2-qa-dense-sample.json](raw/w2-qa-dense-sample.json) |
| 精确标识符“BENCH-MQB 项目里有哪些文档？” | `HYBRID`，fallbackUsed=false | [raw/w2-qa-identifier-upper-sample.json](raw/w2-qa-identifier-upper-sample.json) |
| 复杂对比“请对比项目任务与里程碑在延期风险上的区别和优缺点” | `MULTI_QUERY_HYBRID`，fallbackUsed=false | [raw/w2-qa-complex-sample.json](raw/w2-qa-complex-sample.json) |

QueryPlan 真 worker：`QUERY_PLAN_ENABLED=true` 时同一复杂问题产生
`rag.query_plan.outcome outcome=ADOPTED confidence=0.62 subQueryCount=5`，worker 访问日志有
`POST /v1/rag/plan 200`。注入 stub 后依次得到 `LOW_CONFIDENCE confidence=0.1` 和
`TIMEOUT`，两次问答都正常返回：
[raw/w2-queryplan-low-confidence.json](raw/w2-queryplan-low-confidence.json)、
[raw/w2-queryplan-timeout.json](raw/w2-queryplan-timeout.json)。

## F2 Decide 主链

- shadow：`decide_shadow_calls_total{decision="CALL_TOOL"} 1.0`、
  `decide_shadow_agreement_total{result="match"} 1.0`、
  `decide_shadow_latency_seconds_max 2.171s`；原始指标见
  [raw/metrics-decide-shadow.txt](raw/metrics-decide-shadow.txt)。
- python：`MODEL_STARTED.decideRoute` 依次出现 `python:CALL_TOOL` 与
  `python_fallback:decision_final`；停 worker 后出现 `python_fallback:worker_unavailable`，
  运行仍 `RUN_SUCCEEDED`。[raw/f2-decide-python-events.json](raw/f2-decide-python-events.json)、
  [raw/f2-decide-workerdown-events.json](raw/f2-decide-workerdown-events.json)。
- 真 worker 采数：
  - v1：`cases=32`，整体一致率 `0.875`，golden `0.95`，JSON 合法率 `1.0`，adversarial
    `rejectedRate=0.0`。
  - v4：`cases=80`，整体一致率 `0.9625`，golden `0.953125`，JSON 合法率 `1.0`，adversarial
    `rejectedRate=0.0`。
  - 两份报告均为 `LOW_CONFIDENCE`、`qualityClaim=NOT_VERIFIED`；adversarial 拒绝率为 0，
    **不作为质量通过结论**，只作为 `MEASURED_LOCAL` 原始数据。

## G Planning 主链

### shadow

[raw/metrics-planning-shadow2.txt](raw/metrics-planning-shadow2.txt)：成功 1 次，latency
`9.407s`，schema `pass`，SOFT issues `10`、HARD `0`；Java 计划 `READY`。

### python

[raw/g-planning-python-detail2.json](raw/g-planning-python-detail2.json)、
[raw/g-planning-python-diagnostics2.txt](raw/g-planning-python-diagnostics2.txt)：

- `PLANNING_DRAFT_ROUTE stage=SKELETON marker=python:SKELETON outcome=handled latencyMs=10547`
- attempts：`SKELETON SUCCESS PYTHON_PLANNING_DRAFT ai-worker` → `DETAIL SUCCESS OPENAI_COMPATIBLE Magpie 7732ms`
  → `REFLECT SUCCESS OPENAI_COMPATIBLE Magpie 2277ms`
- 计划 `READY`，确认前 `confirmation=null` 且项目任务数 `0`；确认后创建 3 个任务、
  计划状态 `CONFIRMED`（[raw/g-planning-python-confirm.json](raw/g-planning-python-confirm.json)）。

### 回退

- 首次 python 计划 worker 返回 422：`marker=python_fallback:worker_http`，Java 完成
  `READY_WITH_ISSUES`（[raw/g-planning-python-diagnostics.txt](raw/g-planning-python-diagnostics.txt)）。
- 停 worker 后：`marker=python_fallback:worker_unavailable`，Java 继续生成
  （[raw/g-planning-workerdown-diagnostics.txt](raw/g-planning-workerdown-diagnostics.txt)）。
- 根因修复：worker 的 `/v1/planning/draft` 要求 `scope=planning`，Java `SERVICE_SCOPES` 原缺该
  scope；已在 `InternalTokenIssuer` 补齐并更新定向测试，`InternalTokenIssuerTest` /
  `InternalTokenConfigurationTest` 共 18 项 exit=0。

## W5 Memory Job

- 后端临时：`MEMORY_JOB_ENABLED=true`、`INTERNAL_AI_TOKEN_MODE=ed25519`；worker
  `AI_WORKER_TOKEN_MODE=ed25519`；临时密钥对已用后删除。
- BSK 抽屉“提取记忆候选”触发 Job `f261da3e...`，worker `POST /v1/jobs 200`，Job
  `SUCCEEDED`；候选来自隔离项目描述中的发布事实，而不是 Agent 对话（当前 Job 快照只含
  项目名称/描述 + ACTIVE 记忆）。
- 两个候选：
  - `项目发布日期定为 2026 年 12 月 20 日`（BSK 批准）
  - `项目发布代号定为 Aurora`（API 批准）
- 审批后 `agent_memory` 两条均 `ACTIVE`；移除项目描述后新 Agent 会话仍回答
  `2026 年 12 月 20 日 / Aurora`，`memoryChars` 从 `57` 增至 `673`（单条）和 `1291`（两条），
  回答明确引用两条 ACTIVE DECISION 记忆：
  [raw/w5-memory-recall-events3.json](raw/w5-memory-recall-events3.json)。

## 配置与边界

- 仓库 `application.yml` 的生产/CI 默认保持不变；本波只在 `application-local.yml` 打开
  `STRATEGY_AWARE`、`configured-rerank + inline-rerank-top8` 与 `127.0.0.1:3425` 本机白名单。
- `RERANK_MODE`、`DECIDE_MODE`、`PLANNING_DRAFT_MODE`、`QUERY_PLAN_ENABLED`、`MEMORY_JOB_ENABLED`
  仍保持 java/false 本地默认；一键开启方案见根 `.env.example` 第 11 节。
- 未新增 Flyway 迁移（仍 V1–V101）；未改历史迁移、未碰 `main`、未 force push。

## 原始证据索引

- W1：[raw/reranker-smoke.json](raw/reranker-smoke.json)、
  [raw/w1-agent-top8-events.json](raw/w1-agent-top8-events.json)、
  [raw/w1-configured-rerank-samples.json](raw/w1-configured-rerank-samples.json)、
  [raw/metrics-shadow-summary.txt](raw/metrics-shadow-summary.txt)、
  [raw/reranker-ui-bound.png](raw/reranker-ui-bound.png)
- W2：[raw/w2-route-lines.txt](raw/w2-route-lines.txt)、
  [raw/w2-qa-complex-sample.json](raw/w2-qa-complex-sample.json)、
  [raw/w2-queryplan-low-confidence.json](raw/w2-queryplan-low-confidence.json)、
  [raw/w2-queryplan-timeout.json](raw/w2-queryplan-timeout.json)
- F2：[raw/f2-decide-python-events.json](raw/f2-decide-python-events.json)、
  [raw/f2-decide-workerdown-events.json](raw/f2-decide-workerdown-events.json)、
  [raw/w2-decide-v1-worker.json](raw/w2-decide-v1-worker.json)、
  [raw/w2-decide-v4-worker.json](raw/w2-decide-v4-worker.json)
- G：[raw/g-planning-shadow-detail2.json](raw/g-planning-shadow-detail2.json)、
  [raw/g-planning-python-detail2.json](raw/g-planning-python-detail2.json)、
  [raw/g-planning-python-confirm.json](raw/g-planning-python-confirm.json)、
  [raw/g-planning-workerdown-diagnostics.txt](raw/g-planning-workerdown-diagnostics.txt)
- W5：[raw/bsk-memory-candidates2.json](raw/bsk-memory-candidates2.json)、
  [raw/bsk-memory-approve-confirm.json](raw/bsk-memory-approve-confirm.json)、
  [raw/w5-memory-recall-events3.json](raw/w5-memory-recall-events3.json)
- 进程原始输出：`raw/backend-*.log`、`raw/ai-worker.*.log`、`raw/frontend.*.log`、`raw/llama-server.*.log`
