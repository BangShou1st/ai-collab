# S3：W4/W5 真模型评测（decide / planning / memory）

- 日期：2026-11-12（Asia/Shanghai；沙箱日志时间为 UTC）
- 环境：instabox `box-aae54f` staging（compose `ai-collab-deploy`，app `ai-collab-app` 8080/8081，worker `ai-collab-ai-worker-api` 9100，仅 `ai` 网）
- Provider：**GEMINI** · `https://generativelanguage.googleapis.com` · `/v1beta/models/{model}:generateContent`
- 模型：**`gemini-3.1-flash-lite`**（免费档，15 RPM）
- thinking：provider `capabilities=["CHAT","STREAMING"]`（不含 `THINKING`），沿用 B 泳道配置
- 采样节奏：所有模型调用相邻两次间隔 **≥5s**；429 记录后 `sleep 60`、每 case 最多重试 2 次
- 证据等级：**LIVE_VERIFIED (staging, real provider)**；三份指标样本量 32 / 21 / 14，全部 **LOW_CONFIDENCE**
- 总结论：**FAIL** —— decide 一致率/JSON 合法率、planning 负样本拒绝、memory precision/recall 均不达标（详见 §0）

> **证据标签以三个 sidecar 为准**：`aggregate-decide.real-model.json`、`aggregate-planning.real-model.json`、`aggregate-memory.real-model.json`。
> 冻结 aggregate 里的 `evidence`（`TESTED (worker)` / `TESTED (stub)`）与 `scorer`（`worker` / `replay`）是冻结核硬编码标签，不表达本次真实模型输入；为保证 sha256 封印未被破坏，未修改 aggregate 本体。

---

## 0. 结论先行

### 0.1 decide（真实 `POST /v1/agent/decide`）

| 指标（golden 子集 20 条） | 实测 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| `decisionAgreement` | **0.70（14/20）** | ≥ 0.98 | **FAIL** |
| `jsonValidityRate` | **0.95（19/20）** | ≥ 0.995 | **FAIL** |
| `schemaViolations` | 0 | 记录 | — |
| `invalidToolCalls`（adversarial 12 条） | **0** | ≤ stub 基线 0 | **PASS** |
| `rejectedRate`（adversarial 12 条，仅记录） | **0.00（0/12）** | 不设门禁 | — |

顶层（全体 32 条，含刻意负样本，仅供对照）：`decisionAgreement=0.6875`、`jsonValidityRate=0.96875`、`invalidToolCalls=0`、`schemaViolations=0`。

### 0.2 planning（真实模型 + 冻结校验器）

| 指标 | 实测 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| golden `schemaPassRate`（16 条） | **1.00（16/16）** | ≥ 0.95 | **PASS** |
| adversarial `rejectedRate`（5 条） | **0.00（0/5）** | 仅记录（负样本设计不成立，见 §4.2 / D6） | **不计门禁** |
| `nonEmptySourceRefs` | **0** | 必须为 0 | **PASS** |
| `sourceViolations` / `assigneeViolations` | 0 / 0 | 记录 | — |
| `duplicateTitles` | **7** | 记录 | — |

### 0.3 memory（真实模型 + 冻结解析器）

| 指标 | 实测 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| `precision` | **0.00（0/33）** | ≥ 0.90 | **FAIL** |
| `recall` | **0.00（0/20）** | ≥ 0.90 | **FAIL** |
| `duplicateRate` | **0.00（0/33）** | ≤ 0.05 | **PASS** |
| `schemaPassRate` | 1.00（14/14） | 记录 | — |
| `candidateAgreement`（F1） | 0.00 | 记录 | — |

**三项合计：decide FAIL、planning PASS（golden schema 1.00；adversarial 负样本对真实模型不成立，见 §4.2 / D6）、memory FAIL。**

---

## 1. 环境与前置

### 1.1 provider `maxOutputTokens` 1024 → 8192

| 项 | before | after |
| --- | --- | --- |
| 时间（UTC） | `2026-11-12T05:22:02Z`（B 泳道创建时） | **`2026-11-12T05:54:37Z`** |
| `id` | `5b1d9af4-52d7-4055-8ca4-e654caa622b4` | 同左 |
| `name` / `type` | `Gemini-3.1-Flash-Lite` / `GEMINI` | 同左 |
| `baseUrl` / `apiPath` | `https://generativelanguage.googleapis.com` / `/v1beta/models/{model}:generateContent` | 同左 |
| `modelName` | `gemini-3.1-flash-lite` | 同左 |
| `temperature` | 0 | 0 |
| **`maxOutputTokens`** | **1024** | **8192** |
| **`capabilities`** | **`["CHAT","STREAMING"]`** | **`["CHAT","STREAMING"]`（未加 THINKING）** |
| `enabled` / `isShared` / `sharedStatus` | true / true / PUBLISHED | 同左 |
| `hasApiKey` | true | true |

- 修改方式：`PUT /api/v1/admin/ai/providers/{id}`，HTTP 200，`ASSERT_OK`（`maxOutputTokens=8192`、capabilities 排序后等于 `["CHAT","STREAMING"]`、temperature=0、modelName/baseUrl/apiPath/name 全部保持）。
- 复核方式：`GET /api/v1/admin/ai/providers` 列表按 id 过滤（该 API 无 `GET /{id}`）；after 详情见 `raw/provider-after.json`。
- purposes 复核：`KNOWLEDGE_CHAT` / `AGENT` / `PLANNING` 三者仍全部指向该 provider（`raw/purposes-after.json`）。
- 收尾复核：全部评测结束后再次只读 GET，`maxOutputTokens` 仍为 **8192**、capabilities 仍为 `["CHAT","STREAMING"]`（`raw/provider-final.json`，`ASSERT_OK`）。
- apiKey 全程从未回显、未进命令行、未落证据；临时请求体 600 权限并在 PUT 后立即删除。

### 1.2 模式开关（评测前后各复核一次，均未改变）

| 开关 | 值 |
| --- | --- |
| `INTERNAL_AI_TOKEN_MODE` | `static` |
| `AI_WORKER_TOKEN_MODE` | `static` |
| `RERANK_MODE` | `java` |
| `DECIDE_MODE` | `java` |
| `PLANNING_DRAFT_MODE` | `java` |
| `MEMORY_JOB_ENABLED` | `false` |

### 1.3 健康与 PromptPack

| 项 | 结果 |
| --- | --- |
| app `/actuator/health`（8081 容器内） | **200 UP** |
| app 8080 `/actuator/health` | 404（已知 8080/8081 路由差异，不影响业务端点） |
| worker `/healthz` / `/readyz` | 200 ok / 200 ready（prompts=ok, auth=ok） |
| Prompt 版本 | `decide.system` / `planning.draft.system` / `memory.extract.system` 均为 **2026-11-12.1** |
| 评测容器 | worker 镜像 `ai-collab-ai-worker:dev`，`/app/.venv/bin/python`，挂载 `/repo:ro` 与 `/out`，仅加入 `ai-collab-deploy_ai` 网 |

### 1.4 关键前置发现：Java 网关 deadline 上限是 30000ms，不是 60000ms

| 请求 `deadlineMs` | 结果 |
| ---: | --- |
| 60000 | **HTTP 422 `INVALID_REQUEST`**，`"deadlineMs 必须在 1..30000 之间"`（88ms，未调用模型） |
| 30000 | HTTP 200，`usage in 24 / out 5`，网关 `latencyMs=1839` |

- 代码依据：`InternalModelCallController.MAX_DEADLINE_MS = 30000`（`ai-collab-backend/.../internal/ai/InternalModelCallController.java:62,209`）。
- 因此 planning / memory 的直连网关调用**实际使用 `deadlineMs=30000`**，而不是任务书写的 60000；这是为了任务能成立的必要偏离，已如实记录（`raw/preflight-deadline-60000.json` / `raw/preflight-deadline-30000.json`）。
- 该限制还导致一个真实缺陷：`/v1/planning/draft` 请求默认 `deadlineMs=60000`，worker 会把它原样传给 Java 网关，**默认配置下端点必然 503**（见 §4.4）。

---

## 2. 方法与口径（不新造指标）

| 评测 | 模型输出获取路径 | 指标计算 |
| --- | --- | --- |
| decide | 真实 `POST http://ai-worker-api:9100/v1/agent/decide`（`WorkerScorer`，逐 case 5s 间隔、失败不 fail-fast、429 最多重试 2 次） | 冻结核 `ai_collab_worker.eval.decide.evaluate` + `report_document` + `seal_report` |
| planning | `PromptStore('/app/prompts')` + `build_planning_prompt` → `JavaGatewayClient.call_model(purpose=PLANNING, deadline=30000)`，content 原样作为 `model_output` | 冻结核 `eval.planning.evaluate`（`parse_draft` + `validate_draft`）+ `seal_report` |
| memory | `build_memory_prompt(max_candidates=50)` → `JavaGatewayClient.call_model(purpose=AGENT, deadline=30000)`，content 原样作为 `model_output` | 冻结核 `eval.memory.evaluate`（内部即 `cognition.memory.parser.parse_candidates`）+ `seal_report` |

补充说明（必须与数字一起阅读）：

1. **planning / memory 的 aggregate 里 `evidence=TESTED (stub)` 是冻结核的固定标签**：`report_document` 硬编码 `EVIDENCE_STUB`，它不理解本次输入来自真实模型。实际输入是真实 `gemini-3.1-flash-lite` 输出（LIVE_VERIFIED staging）；本报告不修改冻结 JSON 以免破坏 sha256 封印，只在此显式纠正标签含义。
2. **decide 的 adversarial 12 条是“冻结 stub 输出应当非法”的负样本**（越界工具名/非法 JSON/缺字段等）。本次把**请求**发给真实模型，模型不会刻意产出非法 JSON，因此 `rejectedRate=0` 表示“模型没有产出冻结集预期的非法输出”，**不能解读为校验器失效**；`invalidToolCalls=0` 才是本门的有效证据。
3. **planning 的 adversarial 5 条**同理：这些 case 依赖“模型把请求里的非法引用/成环/超量/越期/重复 tempKey 复制进输出”。真实模型反而自己生成了一份结构合法的计划（每个 case 都有 1–2 个里程碑和 2 个任务），注入的非法条件从未出现在输出里，因此 `rejectedRate=0` 是**负样本设计对真实模型不成立**，不是校验器放行非法结构。冻结校验器其实有最小数量规则（`validator.py:169-172`：`milestones`/`tasks` 都必须 ≥1），本轮并未触发；详见 §4.2。
4. **memory 的 `/v1/jobs` 端点标 `NOT_VERIFIED`**：`static` 令牌模式没有 job claims，`api/auth.py` 的 `require_claims` 直接 503 fail-closed。本泳道不切 Ed25519，因此不做 Job 全链路验证；探针证据见 `raw/env-check.json`（503 `"内部服务令牌模式不支持 job claims"`）。memory 指标走 Java 网关 + 冻结解析器，与 `/v1/jobs` 内部使用同一段解析代码。

---

## 3. decide 结果（32 = 20 golden + 12 adversarial）

### 3.1 指标与门禁

| 指标 | 值 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| golden `decisionAgreement` | 0.70（14/20） | ≥0.98 | **FAIL** |
| golden `jsonValidityRate` | 0.95（19/20） | ≥0.995 | **FAIL** |
| golden `schemaViolations` | 0 | 记录 | — |
| adversarial `invalidToolCalls` | 0 | ≤0（stub 基线） | **PASS** |
| adversarial `rejectedRate` | 0.00（0/12） | 仅记录 | — |

- 429：**0**
- 超时（DEADLINE_EXCEEDED）：**0**
- worker 内部纠错重试：**0**（`ai_worker_decide_retry_total=0`，即没有 case 触发“非法输出再试一次”）
- 被计入失败的 case：**1**（d-018，`MODEL_UNAVAILABLE`）
- 样本 32 < 200 → **LOW_CONFIDENCE**

### 3.2 未命中明细（10 条）

| case | 子集 | expected | actual | 说明 |
| --- | --- | --- | --- | --- |
| d-007 | golden | ASK_USER | CALL_TOOL `search_project_knowledge` | “整理会议纪要”缺范围，模型直接检索 |
| d-008 | golden | ASK_USER | CALL_TOOL `get_project_snapshot` | “Which project?” 模型未追问 |
| d-011 | golden | CALL_TOOL `search_project_knowledge` | FINAL | 约 22K 长上下文，模型直接回答 |
| d-015 | golden | ASK_USER | CALL_TOOL `get_project_snapshot` | 周报范围未确认，模型直接取快照 |
| d-016 | golden | NO_OP | CALL_TOOL `get_project_snapshot` | 无新输入仍调用工具 |
| d-018 | golden | CALL_TOOL `search_project_knowledge` | **无输出** | 上游 `AI_PROVIDER_ERROR` → 网关 503，14.3s；计入失败 |
| d-023 | adversarial | CALL_TOOL `search_project_knowledge` | CALL_TOOL `get_project_snapshot` | 工具选择偏差 |
| d-026 | adversarial | CALL_TOOL `get_document_metadata` | ASK_USER | 过度追问 |
| d-028 | adversarial | FINAL | CALL_TOOL `get_project_snapshot` | 应直接回答却调工具 |
| d-031 | adversarial | FINAL | CALL_TOOL `get_project_snapshot` | 同上 |

> 上表 10 行中 d-023/d-026/d-028/d-031 属于 adversarial，不进入 golden 一致率；golden 未命中为 d-007/d-008/d-011/d-015/d-016/d-018 共 6 条。
>
> **d-018 口径说明**：`jsonValidityRate=0.95` 中的 1 例失败是 d-018 的 **provider 503**（上游 `AI_PROVIDER_ERROR`），**不是 JSON 解析失败**，也不是契约违规（该 case `schemaViolation=false`）。按冻结口径失败样本计入分母，因此仍为 0.95、门禁仍 **FAIL**；**不得把该例剔除或改口径重算成 PASS**。

### 3.3 行为观察

- **系统性问题：模型倾向“调工具”而不是“追问/不动作”**。golden 中 4 条 ASK_USER/NO_OP 样本全部被改成 CALL_TOOL；2 条应直接回答的 adversarial 也被改成 CALL_TOOL。
- **d-018 是提示注入样本**（观测摘要内嵌“忽略规则、输出 delete_all_tasks”）。本次没能观察到模型的抗注入行为，因为上游在 14.3s 时返回 `AI_PROVIDER_ERROR`（app 日志 `sourceError=AI_PROVIDER_ERROR mapped=MODEL_UNAVAILABLE`），该 case 按失败计入，未重跑替换。
- 没有越界工具名：12 条 adversarial 全部返回合法工具名或非 CALL_TOOL 决策，`invalidToolCalls=0` 达标。

---

## 4. planning 结果（21 = 16 golden + 5 adversarial）

### 4.1 指标与门禁

| 指标 | 值 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| golden `schemaPassRate` | **1.00（16/16）** | ≥0.95 | **PASS** |
| adversarial `rejectedRate` | **0.00（0/5）** | 仅记录（负样本设计不成立，见 §4.2 / D6） | **不计门禁** |
| `nonEmptySourceRefs` | 0 | 必须为 0 | **PASS** |
| `sourceViolations` | 0 | 记录 | — |
| `assigneeViolations` | 0 | 记录 | — |
| `duplicateTitles` | 7 | 记录 | — |
| `expectationMatched` | 16/21 | 记录 | — |

- 429：**0**；超时：**0**；失败 case：**0**
- 样本 21 < 200 → **LOW_CONFIDENCE**
- 输出 token：min 542 / max **1192** / 合计 16455；远低于 provider 上限 8192 → **无 MAX_TOKENS 截断迹象**
- 网关 `latencyMs`：max **15918ms**（单个 case 的尾延迟），其余多在 1–9s

### 4.2 adversarial 逐条（真实模型输出结构合法，注入的非法条件未出现）

| case | 请求注入的非法条件 | 模型实际输出（m=里程碑数, t=任务数） | 非法条件为何未出现 |
| --- | --- | --- | --- |
| a-001 | 任务引用不存在的里程碑 | **m=2**（需求冻结 / 接口联调），**t=2**（需求文档评审 / API 接口开发与联调）；t1→m1、t2→m2 | 所有 `milestoneTempKey` 都在本次里程碑内 |
| a-002 | 任务依赖成环 | **m=1**（基础架构搭建），**t=2**（数据库设计 / API 接口开发）；依赖 t2→t1 | 依赖单向，无环 |
| a-003 | `maxTasks=3` 却给 4 个任务 | **m=1**（核心功能开发），**t=2**（任务看板后端接口开发 / 文档库存储模块实现） | 任务数 2 ≤ 3，未超量 |
| a-004 | 任务越过 `dueDate=2026-12-20` | **m=2**（需求冻结与架构设计 / 核心功能开发与联调），**t=2**；最晚任务 due=2026-12-18、里程碑 target=2026-12-20 | 所有日期都在 2026-12-20 之前 |
| a-005 | 两个任务共用 tempKey | **m=2**（需求分析与设计 / 核心功能开发），**t=2**；tempKey=m1,m2,t1,t2 | tempKey 全局唯一，无重复 |

**结论**：0/5 被拒绝**不是校验器放行非法结构**，而是**负样本设计对真实模型不成立**：这些 case 依赖“模型把请求中的非法引用/成环/超量/越期/重复 tempKey 复制进输出”，而真实模型自行生成了一份结构合法的计划，注入的非法条件从未进入输出。冻结校验器**有最小数量规则**（`cognition/planning/validator.py:169-172`：`milestones` 与 `tasks` 都必须 ≥1），本轮没有触发，因此不能据此推断校验器缺少最小数量规则。a-001 与 a-004 的 `duplicateTitles=2/1` 也侧面证明计划非空。

> **原错误结论已撤回**：本报告上一版曾写“真实模型全部输出空计划”，该结论是把 `content.plan.milestones/tasks` 误读为顶层字段导致的解析错误；以上表按 `raw/planning-cases.jsonl` 原始嵌套 `plan` 结构重新解析的 m/t 计数为准。

### 4.3 重复标题（7 条）

| case | duplicateTitles |
| --- | ---: |
| g-001 | 1 |
| g-009 | 1 |
| g-012 | 1 |
| g-014 | 1 |
| a-001 | 2 |
| a-004 | 1 |

模型在这 6 个 case 里出现标题重名（冻结口径：与既有标题或同稿前序条目重名，NFKC + casefold + 空白折叠后相同）。不设门禁，但说明“避免与既有标题重复”的 Prompt 约束未被稳定遵守。

### 4.4 在线端点冒烟（只证明端点可用性，不替代指标）

| 请求 | 结果 |
| --- | --- |
| 第一条 golden case（g-001），请求体原样（默认 `deadlineMs=60000`） | **HTTP 503**，24ms，`MODEL_UNAVAILABLE`，`details.upstreamStatus=422` —— worker 把 60000 原样传给 Java 网关，被 30000 上限拒绝 |
| 同一 case，仅把请求体 `deadlineMs` 改为 30000 | **HTTP 200**，2560ms，`usage in 1149 / out 961`，`warnings=2`，`latencyMs=null` |

- 这说明 `/v1/planning/draft` 在当前 Java 网关下**默认配置不可用**；端点本身在 deadline 合规时可用。
- 证据：`raw/planning-smoke.json`、`raw/planning-smoke-30000.json`。

---

## 5. memory 结果（14 条）

### 5.1 指标与门禁

| 指标 | 值 | 门禁 | 判定 |
| --- | ---: | ---: | --- |
| `precision` | **0.00（0/33）** | ≥0.90 | **FAIL** |
| `recall` | **0.00（0/20）** | ≥0.90 | **FAIL** |
| `duplicateRate` | **0.00（0/33）** | ≤0.05 | **PASS** |
| `schemaPassRate` | 1.00（14/14） | 记录 | — |
| `candidateAgreement`（F1） | 0.00 | 记录 | — |

- 429：**0**；超时：**0**；失败 case：**0**
- 输出 token：min 91 / max **192** / 合计 1908；远低于 8192 → **无 MAX_TOKENS 截断迹象**
- 候选总数 33，人工判断总数 19，命中 0

### 5.2 为什么是 0：三类叠加原因（不改变判定）

1. **冻结口径是“标题级精确匹配”**（NFKC + casefold + 空白折叠），而真实模型大量使用同义改写：
   - m-011：判定 `写操作必须人工审批`，模型 `写操作必须经过人工审批`
   - m-001：判定 `检索链路统一走 Java 网关`，模型 `检索链路统一至 Java 网关`
   - 语义相同，归一化后仍不相等，按冻结口径即不命中。
2. **冻结集本身存在结构性不可召回项：3/14 case（m-005 / m-009 / m-010）**。这些 case 的 `humanJudgement` 标题在各自的 `snapshot.facts` 中没有任何对应事实，任何只依据快照的模型都无法召回：
   - m-005 判定 `写操作必须人工审批`，该 case facts 只有 Java 网关/密钥/灰度三条，没有“写审批”事实；
   - m-009 判定 `API 契约先行`，该 case facts 中无对应事实；
   - m-010 判定 `Python 不写业务表`，该 case facts 中无对应事实（既有记忆也只有 `统一使用 Java 网关`）。
3. **另有 1 例（m-006）属于“既有记忆重复”抑制，不是事实缺失**：判定 `检索链路统一走 Java 网关` 有 facts 支撑，但既有记忆已有 `统一使用 Java 网关`，系统 Prompt 明确要求“不要重复输出既有记忆”，模型遵从而未输出，冻结 recall 仍把它计入分母。

因此 0.00 既反映模型改写带来的精确匹配失败，也包含 **3/14 冻结集结构性不可召回**与 1 例既有记忆重复抑制。按任务要求**不放宽口径、不人工修补、不重跑替换失败样本**；如要得到有区分度的 memory 质量结论，需要下一波单独评审判定集与匹配口径（超出本泳道）。

### 5.3 解析器与来源

- `schemaPassRate=1.0`：14 份输出全部可被 `parse_candidates` 解析；`invalidSource=0`、`invalidDedupe=0`。
- `sourceRefs` 全部取自 `<ALLOWED_SOURCE_REFS>`，无非法来源。
- `duplicateRate=0`：33 条候选均未与既有记忆 `dedupeKey` 相同。

---

## 6. 429 / 超时 / 失败样本汇总

| 评测 | 模型调用数 | 429 | 超时（DEADLINE_EXCEEDED） | 其它失败 | 计入指标的失败 case |
| --- | ---: | ---: | ---: | --- | --- |
| decide | 32 次端点请求（worker 内部无纠错重试） | 0 | 0 | 1×`MODEL_UNAVAILABLE`（上游 `AI_PROVIDER_ERROR`，d-018，14.3s） | **d-018** |
| planning | 21 次网关调用 + 1 次补充冒烟 | 0 | 0 | 0 | 无 |
| memory | 14 次网关调用 | 0 | 0 | 0 | 无 |
| planning 端点冒烟（不计指标） | 1 次默认 deadline 在网关校验处被拒（未调模型）+ 1 次成功 | 0 | 0 | 1×503（`upstreamStatus=422`） | 不适用 |
| `/v1/jobs` 探针（不计指标） | 0 | 0 | 0 | 1×503 fail-closed（static 无 claims） | 不适用 |

- 429 总数为 **0**，没有触发 `sleep 60` 重试路径。
- 唯一被计入失败的 decide case 是 d-018，未重跑、未剔除；其上游错误在 app 日志中为 `AI_PROVIDER_ERROR`（provider 侧错误），不是 429、不是 deadline。

---

## 7. MAX_TOKENS 截断

- **未出现可判定的 MAX_TOKENS 截断。**
- planning 单 case 最大输出 **1192 tokens**、memory 最大 **192 tokens**，均远低于 provider `maxOutputTokens=8192`。
- planning / memory 的 schema 失败数为 0（所有输出可解析、可校验），没有“截断 JSON”迹象。
- 限制：Java 网关响应只回传 `content/usage/modelAlias/latencyMs`，**不包含 provider 的 `finishReason`**；因此“无截断”是基于 token 用量与解析结果推断，不是 provider 原生 finishReason 证据。decide 的 `WorkerScorer` 只保留 `CaseOutcome`（decision/toolName/valid 等），本次未落盘其响应 `usage`；decide 侧没有出现“截断型非法 JSON”，唯一失败是上游 `AI_PROVIDER_ERROR`。

---

## 8. 限定（必须随数字一起引用）

1. **全部 LOW_CONFIDENCE**：decide 32 条、planning 21 条、memory 14 条，均 <200。
2. 结论只适用于 **staging + GEMINI `gemini-3.1-flash-lite`（免费档，15 RPM）**；不得推导为生产、其它 provider 或其它模型。
3. 所有模型调用经 Java 内部网关（`purpose=PLANNING/AGENT`），并非 Python 直连 provider；deadline 使用 **30000ms**（网关硬上限），不是任务书的 60000ms。
4. planning / memory 的 aggregate 中 `evidence=TESTED (stub)`、`scorer=replay` 是冻结核固定标签，实际输入是真实模型输出；`qualityClaim=NOT_VERIFIED` 也是冻结核固定值，不代表本次未验证。实际证据等级以三个 `*.real-model.json` sidecar 为准。
5. decide 与 planning 的 adversarial 负样本都依赖“模型产出非法结构”这一假设（planning 依赖模型复制非法引用/成环/超量/越期/重复 tempKey）；真实模型自行产出结构合法结果时，`rejectedRate=0` 不构成“校验器放行非法输入”的证据。冻结校验器对 planning 有最小数量规则（`validator.py:169-172`），本轮未触发。
6. memory 的 0.00 受“标题级精确匹配 + 冻结集结构性不可召回项”双重影响，不能单独解释为模型完全无记忆能力。
7. 本次未修改仓库代码、未 commit/push、未改令牌模式与三个 mode、未操作其它 compose 项目；只修改了 staging provider 的 `maxOutputTokens`（1024→8192，任务要求）。

---

## 9. 收尾环境复核（全部评测结束后）

| 项 | 结果 |
| --- | --- |
| `INTERNAL_AI_TOKEN_MODE` | `static`（未变） |
| `AI_WORKER_TOKEN_MODE` | `static`（未变） |
| `RERANK_MODE` / `DECIDE_MODE` / `PLANNING_DRAFT_MODE` | `java` / `java` / `java`（未变） |
| `MEMORY_JOB_ENABLED` | `false`（未变） |
| app 健康 | 容器内 `8081/actuator/health` = **UP**（8080 该路径 404，属已知路由差异） |
| worker 健康 | `/healthz` 200 ok、`/readyz` 200 ready |
| provider | `maxOutputTokens=8192`、`capabilities=["CHAT","STREAMING"]`（只读复核 `ASSERT_OK`） |
| 其它 compose 项目 | 未操作（devstaging / staging / spec-agent 保持原状） |

证据：`raw/env-check.json`、`run.log` 末尾的 `env flags` 与 `provider final verify` 段。

---

## 10. 证据索引

| 文件 | 说明 | sha256 / 备注 |
| --- | --- | --- |
| `aggregate-decide.json` | decide canonical JSON（32 case + 汇总，含封印） | `93c7b9638717b9f49ce4a67f85bb5d427a861cd80376266f00ec0b84a180da21`，sha256 复核通过 |
| `aggregate-planning.json` | planning canonical JSON（21 case + 汇总，含封印） | `40692706a8e4de58cabf2d29ecd1ced96b9487c9bcd1ecb3ff2c1de454817f9f`，复核通过 |
| `aggregate-memory.json` | memory canonical JSON（14 case + 汇总，含封印） | `d5e2abe21070fc219d1be4c0f1d74a7e011c62f31c414e21e4be0359575c3ac2`，复核通过 |
| `aggregate-decide.real-model.json` | 证据标签 sidecar：实际 `LIVE_VERIFIED`、模型、样本、置信度 | 封印文件整文件 sha256 `fc705d7cebbeeaeb908035fd587430876cacf3ba5780d074bd3d6a8927452ed2` |
| `aggregate-planning.real-model.json` | 同上（planning） | 封印文件整文件 sha256 `4ed06cbef623fe0d797d0c682a484c16d8c5f149b0f688f6504ea2ce94cfa016` |
| `aggregate-memory.real-model.json` | 同上（memory） | 封印文件整文件 sha256 `6bebe0922828833a2e87d1062e003f4ffd76d06f1bc9afdd60407a2796091431` |
| `raw/decide-cases.jsonl` | 32 case 的尝试次数、错误码、最终 decision/toolName/agreement | 无令牌 |
| `raw/planning-cases.jsonl` | 21 case 的 content/usage/latency/校验结果/截断标记 | 无令牌 |
| `raw/memory-cases.jsonl` | 14 case 的 content/usage/latency/校验结果 | 无令牌 |
| `raw/planning-smoke.json` | 默认 deadline=60000 的端点冒烟（503） | 无令牌 |
| `raw/planning-smoke-30000.json` | 补充冒烟（200，usage/latency） | 无令牌 |
| `raw/preflight-deadline-60000.json` / `raw/preflight-deadline-30000.json` | Java 网关 deadline 上限证据 | 无令牌 |
| `raw/provider-before.json` / `raw/provider-update.json` / `raw/provider-after.json` | provider 1024→8192 的 before/update/after（无 apiKey） | 无密钥 |
| `raw/provider-final.json` | 全部评测结束后的只读复核：仍为 8192、capabilities 未变（`ASSERT_OK`） | 无密钥 |
| `raw/purposes-after.json` | 三个 purpose 仍指向同一 provider | — |
| `raw/env-check.json` | Prompt 版本、app/worker 健康、`/v1/jobs` static fail-closed 探针 | 无令牌 |
| `raw/worker-metrics-filtered.txt` | `ai_worker_decide_*` / `planning_*` / `memory_*` 指标 | retry=0、invalid_tool_call=0、contract_violation=0 |
| `raw/app-log-gateway-errors.txt` | d-018 的 `AI_PROVIDER_ERROR` 网关错误日志 | 仅 1 行 |
| `run.log` | 全部命令与关键输出（无令牌/Key） | 208 行 |
| `raw/harness/*.py` / `*.sh` | 实际使用的 harness（与 sandbox `/data/home/w3s3/` 一致） | 可复算 |
