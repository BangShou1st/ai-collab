# W5 报告：planning draft shadow + Memory 提取器 Job 骨架

> 日期：2026-11-12
> 基线：W4 之后 `dev`（`rerank.mode=java`、`decide.mode=java`、`planning.draft.mode=java`）
> 设计依据：[Python AI 能力平面](../../design/python-ai-capability-plane-2026-11-11.md)
> §1.4 / §4 / §5.2（J5）/ §5.3 / §6.3（P3）/ §7.1 / §7.6 / §7.7 / §7.8 / §8 / §9 / §10
> 施工契约：[W5 冻结](../handoffs/w5-planning-memory-freeze-20261112.md)
> 上游：[W4 报告](w4-decide-20261112.md)

---

## 0. 结论摘要

| # | 交付/退出条件 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | Python `POST /v1/planning/draft` 契约/边界/自校验/Schema | **达成** | `contract/planning.py`、`cognition/planning/*`、`api/planning.py`；依赖环/悬空里程碑/重复 tempKey/日期/成员/数量测试 |
| 2 | Java `planning.draft.mode=java|shadow`，默认 `java`；`python` 启动失败 | **达成** | `PlanningDraftMode/Properties` + ApplicationContextRunner 测试 |
| 3 | Python 草稿在 Java **必须重跑** `TaskPlanDraftValidator`；不落库/不改变用户结果 | **达成** | `PlanningDraftShadowService`；9 项单测；`commitDetailOutcome` 提交点前异步观察 |
| 4 | Memory 提取器：Python 无状态候选提取 + `POST /v1/jobs` | **达成** | `api/jobs.py`、`cognition/memory/*`、`clients/java_jobs.py`；260 项新增 Python 测试 |
| 5 | V91 新建 `ai_job`/`ai_job_attempt`/`ai_job_result`（不改历史迁移） | **达成** | `V91__ai_job.sql`；Testcontainers 真库 `Successfully applied 91 migrations` |
| 6 | Job 状态机/租约/epoch fencing/重试/结果幂等全在 Java | **达成** | `AiJobStateMachine/Service/Repository`；16 项服务测试 + 2 项真库测试 |
| 7 | 内部端点 snapshot/heartbeat/results + job token claims 绑定 | **达成** | `InternalAiJobController`；10 项 MockMvc 测试（static 503 / claims 403 / 409 STALE_LEASE） |
| 8 | **不发布候选**（review_status 恒 PENDING_REVIEW，无 agent_memory 写入） | **达成** | V91 CHECK + 服务无发布路径；Python 侧断言"无 agent_memory URL/写入" |
| 9 | 默认关闭：`memory.job.enabled=false`，无 dispatcher/调度器进入生产 | **达成** | `@ConditionalOnProperty`；`AiJobDispatcher`/`AiJobRecoveryJob` 仅骨架 |
| 10 | `ai-eval planning` Schema 一次通过率 ≥95% | **golden 子集 100%，但仅 stub** | 见 §4；真实模型 **NOT_VERIFIED** |
| 11 | `ai-eval memory` 一致率/重复率达标 | **stub 下 precision/recall 0.9、duplicateRate 0.05** | 仅 stub，**NOT_VERIFIED** |
| 12 | 全量门禁 | **见 §6** | Java `mvnw verify` + Python 全量 |

**一句话**：planning draft 与 Memory 契约/端点/Job 骨架全部落地、默认关闭、不发布候选；
Schema 通过率在 **stub golden 子集**上 100%，真实模型与真实 Job 运行 **NOT_VERIFIED**；
dispatcher 真实调用、recovery 定时、候选审核/发布 **本波未实现**（明确登记）。

---

## 1. 范围与不做

**做**：`/v1/planning/draft`、`/v1/jobs`、Memory 候选契约、Java planning shadow、V91 Job 表与
状态机/租约/重试/内部端点、job-scoped Ed25519 token、`ai-eval planning|memory`、开关与文档。

**不做**：不切 `planning.draft.mode=python`；不做候选审核 UI/发布；不做调度器/第二运行时；
不改历史迁移；不改前端；不把 Python 草稿合并进用户结果；不做 W6（MCP）。

---

## 2. Planning draft（实现版）

### 2.1 Python

- 请求/响应严格按冻结 §1；`deadlineMs` 默认 60000、上限 120000；
- Python 自校验：milestone/task 数量、tempKey 唯一、milestone 引用、依赖无环、priority 白名单、
  日期区间、assignee ∈ members（越界置 null + warning）、标题/objective/description 上限、
  `sourceRefs` 强制为空（见 §5 修正）、`estimatedHours ≤80`；
- 失败 → `SCHEMA_VIOLATION`(422)，`details.reason` 固定短语；
- 指标 `ai_worker_planning_draft_duration_seconds{outcome}`、`_warnings_total`、
  `_schema_violation_total`、`_source_refs_dropped_total`。

### 2.2 Java shadow

- `planning.draft.mode=java`（默认）：零外呼、零快照、零指标；
- `shadow`：在 `commitDetailOutcome`（最终草稿提交前）异步观察；虚拟线程 + `Semaphore(4)`；
  请求由 `validationContext(plan)` + 项目事实构造，有界（≤48000 字符，裁剪顺序
  既有标题→成员→描述→goal）；
- **收到 Python 草稿后重跑** `TaskPlanDraftValidator.assess(COMPLETE, aiGenerated=true)`，
  记录 `schema_pass{pass|fail}`、问题数（HARD/SOFT）、延迟、失败原因；
- 不落库、不发事件、不修改 `assessment`/`draft`/用户结果。

指标：`planning.draft.shadow.calls{outcome}`、`.schema_pass{result}`、`.issues{severity}`、
`.latency{outcome}`、`.failure{reason}`、`.skipped{reason}`、`.truncated`。

---

## 3. Memory 提取器（Job 模式）

### 3.1 Python（无状态执行器）

- `POST /v1/jobs`：校验 job token（`job:execute` scope + claims `jobId/attempt/leaseEpoch/snapshotHash/projectRef`）
  → heartbeat(start) → `GET snapshot` → Prompt → 模型调用（同一 token）→ 解析候选 →
  `POST results`；端到端 deadline；
- 候选：`kind ∈ {DECISION,PREFERENCE,CONSTRAINT,LESSON}`、`title≤200`、`content≤2000`、
  `confidence∈[0,1]`、`sourceRef` 必须引用快照、`dedupeKey=sha256(kind|normalized title|normalized content)`；
- **不重试、不调度、不发布、不写业务表**；失败映射：超时/网关不可用 → `FAILED_RETRYABLE`；
  快照 404/409 → `FAILED`；模型输出非法 → 422 且不回报结果。

### 3.2 Java（唯一写者）

- V91 三表 + CHECK + 索引/唯一键；状态机 `QUEUED→LEASED→RUNNING→SUCCEEDED/FAILED_RETRYABLE→FAILED`，
  非终态可 `CANCELED/EXPIRED`；
- 租约 `lease_token + lease_epoch`，所有跃迁 CAS `version`（失败重读一次→409 `STALE_LEASE`）；
  心跳/结果必须匹配 epoch；重试 30/120/480… 上限 600s；
- 内部端点：snapshot（≤64 KiB、facts≤32×500、memories≤50×500）、heartbeat、results
  （`(job_id,dedupe_key)` 唯一、重复只返回已存在、`review_status` 恒 `PENDING_REVIEW`）；
- `AiJobDispatcher`/`AiJobRecoveryJob` 仅在 `memory.job.enabled=true`（recovery 还需
  `recovery-enabled=true`）时装配；**本波没有生产触发、没有定时器、没有 Job 创建入口**。

---

## 4. 评测（stub，LOW_CONFIDENCE）

| 评测 | 指标 | 值 | 说明 |
| --- | --- | --- | --- |
| `ai-eval planning` | golden schemaPassRate | **1.0（15/15）** | 冻结集 20 条：15 golden + 5 adversarial |
| `ai-eval planning` | adversarial rejectedRate | **1.0（5/5）** | 负样本全部被拒 |
| `ai-eval planning` | 全体 schemaPassRate | 0.75 | 含负样本分母 |
| `ai-eval memory` | precision / recall | **0.9 / 0.9** | 20 个候选、14 条 case |
| `ai-eval memory` | duplicateRate | **0.05** | 与既有记忆重复率 |
| 两者 | confidence | `LOW_CONFIDENCE` | 样本 <200 |
| 两者 | evidence / qualityClaim | `TESTED (stub)` / `NOT_VERIFIED` | 真实模型未测 |

**这不是**"Schema 通过率 ≥95% 已达标"的证据：golden 子集全绿只证明契约/管线正确；真实模型结论
必须在 staging 产生，本会话不可达。

---

## 5. 实现期发现并修正的跨语言契约问题（重要）

| # | 问题 | 处理 |
| --- | --- | --- |
| 1 | Python `sourceRefs` 命名空间（`fact:/member:/milestone:/task:`）与 Java `PlanSource.ref`（`S1..S12`）不一致，会让 shadow 稳定报 `SOURCE_REF_*` | **Python 强制 `sourceRefs=[]`**（本波不产出引用；引用由 Java 检索负责），丢弃计数；契约保留字段向前兼容 |
| 2 | Python `estimatedHours` 允许 200，Java Validator 上限 80 | Python 收紧到 ≤80 |
| 3 | Java `TaskPlanDraftValidator` 要求 milestone/task `objective` 非空、task `description` 非空（COMPLETE 模式），Python 原实现把它们当可缺省 | Python 契约改为必填；否则 shadow 会解析失败/误判质量 |
| 4 | Java Parser 要求 `objective`（requiredText） | 同 #3 |

这些修正体现了"Java 是唯一校验权威"：Python 契约向 Java 语义收敛，而不是放宽 Java 校验。

---

## 6. 门禁（最终运行）

### 6.1 Java

```bash
cd ai-collab-backend && bash ./mvnw -B -ntp -o verify
```

```text
Tests run: 2869, Failures: 0, Errors: 0, Skipped: 61
BUILD SUCCESS
```

- V91 在 Testcontainers 真库测试中实际执行：`Successfully applied 91 migrations ... version v91`；
- 全量 verify 覆盖了 W5 Java 的 264 项定向测试；末次 promptpack 同步（W5 Python 新增 4 条
  worker prompt）后复跑
  `BundledPromptCatalogTest,RemotePromptCatalogTest,PromptPack*Test,PlanningDraft*Test,AiJob*Test,InternalAiJob*Test`
  → **76 项全绿**；
- 最终以 CI 的全量 verify 为准。

### 6.2 Python

```text
uv sync --frozen                 → Checked 41 packages
ruff check . / ruff format --check → 全绿（152 files）
mypy --strict src                → Success（60 source files）
pytest -q                        → 1104 passed
contract.schema                  → 14 files
ai-prompts check / export-java --check → 22 条 / 23 文件
```

`ai-eval planning` / `ai-eval memory` 的 aggregate 见 §4；两次运行逐字节一致（canonical + sha256）。

---

## 7. 未完成项（明确登记，不假装完成）

| # | 未完成 | 影响 | 后续 |
| --- | --- | --- | --- |
| 1 | `AiJobDispatcher` 真实 HTTP 调用与失败分类未集成验证 | 无法真正触发 Python Job | W6 前补集成测试/端到端 |
| 2 | `AiJobRecoveryJob` 无定时触发 | 租约过期只能手动跑 | 需要时加调度（Java 侧） |
| 3 | **无 Job 创建入口 / 无候选审核与发布** | Memory Dreaming 尚不可用 | 后续波次：创建入口 + 审核 UI/服务 + 发布到 agent_memory |
| 4 | 未接真实 Python worker（planning/job 均为 HttpServer/respx stub） | 跨语言链路未 LIVE 验证 | staging 联调 |
| 5 | 真实模型质量未测 | Schema 通过率/一致率仅 stub | staging `--scorer worker` |
| 6 | job token claims 的 `snapshotHash` 只作审计，不校验实时快照 | 弱绑定 | 后续可加 |
| 7 | `planning.draft.shadow.skipped{disabled}` 不发（mode=java 零指标优先） | 指标无 disabled 维度 | 文档已写明 |

---

## 8. 证据等级

| 结论 | 等级 |
| --- | --- |
| Python planning draft/jobs 契约、边界、自校验 | **IMPLEMENTED + TESTED**（离线） |
| Java planning shadow + Validator 重跑 + 隔离 | **TESTED**（stub worker） |
| V91 + Job 状态机/租约/重试/端点 | **TESTED**（单测 + 真库迁移） |
| Schema 通过率 / 候选一致率 | **TESTED (STUB)** + LOW_CONFIDENCE |
| 真实模型 / 真实 Job 运行 | **NOT_VERIFIED** |
| 候选审核与发布 | **未实现**（明确登记） |
| 默认 `java` / `enabled=false` | **TESTED** |

