# W5 施工冻结（J5 + P3：planning draft shadow + Memory 提取器 Job 骨架）

> 日期：2026-11-12
> 基线：W4 之后 `dev`（`rerank.mode=java`、`decide.mode=java` 不变）
> 设计依据：[Python AI 能力平面](../../design/python-ai-capability-plane-2026-11-11.md)
> §1.4 / §4 / §5.2（J5）/ §5.3 / §6.3（P3）/ §7.1 / §7.6 / §7.7 / §7.8 / §8 / §9 / §10
> 上游：[W4 冻结](w4-decide-freeze-20261112.md)、[W3 收尾冻结](w3-final-freeze-20261112.md)
> 性质：Phase 0 冻结。**先冻结、再实现**；实现期偏离必须回写并说明。

---

## 0. 范围

**做**

| 侧 | 交付 | 默认 |
| --- | --- | --- |
| Python | `POST /v1/planning/draft`（§7.6 契约、Schema 自校验、边界、快照、测试） | 端点可用 |
| Python | Memory 无状态候选提取（`POST /v1/jobs`，Job 模式；只读快照 + 回报候选） | 端点可用 |
| Java | `planning.draft.mode=java|shadow`（默认 `java`；`python` 配置启动失败） | `java` |
| Java | `PlanningDraftShadowService`：异步有界；Python 草稿**必须重跑** `TaskPlanDraftValidator`；不落库 | 关闭 |
| Java | Memory Job 骨架：V91 表 + 状态机/租约/重试/结果表 + 内部端点 + 指标；**不发布候选** | `memory.job.enabled=false` |
| Python/Java | 评测：`ai-eval planning`（Schema 一次通过率）、`ai-eval memory`（一致率/重复率） | 离线 |

**不做**：不切 `planning.draft.mode=python`；不做修复/反思迁 Python；不做候选审核 UI/发布路径；
不改历史迁移；不改前端；不引入调度器/第二运行时；不做 W6（MCP）。

**红线**：Job 状态机、租约、重试、结果落库、审核与发布**全部在 Java**；Python 不建 Job 表、
不调度、不决定重试、不发布候选；Python 仍零 DB / 零 Provider 密钥。

---

## 1. Python `POST /v1/planning/draft`（§7.6 落地版）

请求：

```json
{"schemaVersion":1,"goal":"…","locale":"zh-CN",
 "projectFacts":{"projectName":"…","description":"…",
   "members":[{"id":"<uuid>","name":"张三"}],
   "existingMilestoneTitles":["…"],"existingTaskTitles":["…"]},
 "template":"ITERATION",
 "constraints":{"maxTasks":20,"maxMilestones":6,"startDate":"2026-11-20","dueDate":"2026-12-31"},
 "deadlineMs":60000}
```

| 字段 | 约束 |
| --- | --- |
| `goal` | 1–4000 |
| `locale` | 同 W4 §1 |
| `projectFacts.projectName` | 0–200 |
| `projectFacts.description` | 0–4000 |
| `projectFacts.members` | 0–50；`id` UUID、`name` 1–100 |
| `projectFacts.existingMilestoneTitles` / `existingTaskTitles` | 各 0–40，每项 1–200 |
| `template` | `ITERATION` \| `DELIVERY` \| `CUSTOM` |
| `constraints.maxTasks` | 1–40（默认 20） |
| `constraints.maxMilestones` | 1–8（默认 6） |
| `constraints.startDate`/`dueDate` | ISO 日期，可空；都有时 `start<=due` |
| `deadlineMs` | 1–120000，默认 60000；硬 deadline |

响应：

```json
{"schemaVersion":1,
 "plan":{"milestones":[{"tempKey":"m1","title":"…","objective":"…","description":"…",
          "targetDate":"2026-12-01","sourceRefs":["fact-1"]}],
         "tasks":[{"tempKey":"t1","milestoneTempKey":"m1","title":"…","objective":"…","description":"…",
                   "priority":"MEDIUM","estimatedHours":8.0,"startDate":"2026-11-20","dueDate":"2026-11-27",
                   "assigneeId":"<uuid|null>","dependencyTempKeys":[],"sourceRefs":["fact-1"],"sortOrder":1}]},
 "warnings":["…"],"usage":{…}}
```

Python 侧自校验（不通过 → `SCHEMA_VIOLATION`(422)，Java 侧会再校验一次）：

1. `milestones` 1–`maxMilestones`；`tasks` 1–`maxTasks`；`tempKey` 全局唯一（milestone 与 task 分域，均非空、1–64）；
2. `plan.tasks[i].milestoneTempKey` 必须存在于 `milestones`；
3. `dependencyTempKeys` 必须引用本次 `tasks`（不含自身），且**无环**（Kahn/DFS）；
4. `priority ∈ {LOW, MEDIUM, HIGH, URGENT}`；`estimatedHours ∈ [0.5, 200]` 或 null；
5. 日期：`targetDate`/`startDate`/`dueDate` 若给出必须在 `constraints.startDate..dueDate` 内
   （缺约束时只校验 `start<=due`）；`dueDate>=startDate`；
6. `assigneeId` 若给出必须 ∈ `projectFacts.members[].id`（否则置 null 并 warning，不报错）；
7. `sourceRefs` 允许空；非空时必须 ∈ 请求 `projectFacts` 派生的 ref 集合
   （`member:<id>`、`milestone:<index>`、`task:<index>`、`fact:project`），否则丢弃该 ref；
8. `warnings` 0–20，每项 1–500；`description` 每项 ≤2000，`objective` ≤500，`title` ≤100；
9. 输出必须是单个 JSON object（允许围栏）；字段缺失/多余/类型错 → `SCHEMA_VIOLATION`。

Prompt：新增 `planning.draft.system`、`planning.draft.user`（版本化）。

---

## 2. Java `planning.draft.mode`

```yaml
planning:
  draft:
    mode: "${PLANNING_DRAFT_MODE:java}"          # java | shadow（python 未实现，配置即启动失败）
    worker-base-url: "${PLANNING_DRAFT_WORKER_BASE_URL:http://ai-worker-api:9100}"
    deadline-ms: "${PLANNING_DRAFT_DEADLINE_MS:60000}"
    max-concurrency: "${PLANNING_DRAFT_MAX_CONCURRENCY:4}"
    snapshot-max-chars: "${PLANNING_DRAFT_SNAPSHOT_MAX_CHARS:48000}"
```

- `java`（默认）：零外呼、零快照、零指标；
- `shadow`：在**Java 草稿提交点**（`TaskPlanGenerationOrchestrator.commitDetailOutcome` 内、
  `commitService.commitGenerated` 之前）异步发起；任何失败只记指标；
- 触发条件：`mode=shadow`、`plan.projectId` 可解析、信号量可用、快照 ≤ 上限（超限截断并记
  `planning.draft.shadow.truncated` 后仍发送）。
- 请求映射：`goal = plan.goal`（无则用 `plan.title`）；`projectFacts` 来自
  `TaskPlanRepository.validationContext(plan)`（成员 id、项目日期）+ 项目名/描述 + 既有标题；
  `template=ITERATION`；`constraints.maxTasks = min(40, context.maxTaskCount())`、
  `maxMilestones = min(8, maxTasks)`、`startDate/dueDate` 取计划的计划区间。
- 响应处理（**必须**）：
  1. 解析为 `TaskPlanDraft`（复用/新增最小解析器；字段缺失/非法 → `schemaInvalid`）；
  2. **重跑** `TaskPlanDraftValidator.assess(repository.validationContext(plan), draft, COMPLETE, true)`；
  3. 记指标：`schemaPass = assessment.ready() && !assessment.hasHardIssues()`；
  4. **不落库、不发事件、不改用户结果、不合并 Python 草稿**。
- 指标：

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `planning.draft.shadow.calls` | Counter | `outcome`(success/schema_invalid/failure) |
| `planning.draft.shadow.schema_pass` | Counter | `result`(pass/fail) |
| `planning.draft.shadow.issues` | DistributionSummary | `severity`(HARD/SOFT)（每条草稿的记录数） |
| `planning.draft.shadow.latency` | Timer | `outcome`(success/failure) |
| `planning.draft.shadow.failure` | Counter | `reason`(timeout/unavailable/schema/http/unexpected) |
| `planning.draft.shadow.skipped` | Counter | `reason`(disabled/concurrency/saturated/empty) |
| `planning.draft.shadow.truncated` | Counter | 无 |

- 与 W4 decide shadow 相同的隔离纪律：虚拟线程 + 信号量、不重试、不写业务表、不改 `assessment`。

---

## 3. Memory 提取器（Job 模式）

### 3.1 数据归属（V91 新建，Java 唯一写者）

`V91__ai_job.sql`（命名连续、不改历史；表名与设计 §8 一致）：

```sql
CREATE TABLE ai_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id),
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_ai_job_status CHECK (status IN
      ('QUEUED','LEASED','RUNNING','SUCCEEDED','FAILED_RETRYABLE','FAILED','CANCELED','EXPIRED')),
    CONSTRAINT ck_ai_job_type CHECK (job_type IN ('MEMORY_EXTRACTION'))
);
CREATE INDEX idx_ai_job_queue ON ai_job (status, next_attempt_at);
CREATE INDEX idx_ai_job_project ON ai_job (project_id, created_at DESC);

CREATE TABLE ai_job_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES ai_job(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    input_tokens INTEGER,
    output_tokens INTEGER,
    CONSTRAINT uk_ai_job_attempt UNIQUE (job_id, attempt_no),
    CONSTRAINT ck_ai_job_attempt_status CHECK (status IN
      ('RUNNING','SUCCEEDED','FAILED_RETRYABLE','FAILED','EXPIRED'))
);

CREATE TABLE ai_job_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES ai_job(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL,
    result_kind VARCHAR(32) NOT NULL,
    dedupe_key CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_job_result_dedupe UNIQUE (job_id, dedupe_key),
    CONSTRAINT ck_ai_job_result_kind CHECK (result_kind IN ('MEMORY_CANDIDATE')),
    CONSTRAINT ck_ai_job_result_review CHECK (review_status IN ('PENDING_REVIEW','ACCEPTED','REJECTED'))
);
```

- **不发布**：`review_status` 只允许停在 `PENDING_REVIEW`；本波没有 ACCEPTED/REJECTED 的写入路径，
  也没有"候选 → agent_memory"的转换。审核/发布仍由既有 Java 人工流程承担（后续波次）。

### 3.2 状态机（Java `AiJobStateMachine` + `AiJobService`）

```
QUEUED --lease--> LEASED --start/heartbeat--> RUNNING --success--> SUCCEEDED
                                                   \--retryable--> FAILED_RETRYABLE --retry(attempt<max)--> QUEUED
                                                   \--fatal-----> FAILED
QUEUED/LEASED/RUNNING --deadline--> EXPIRED
任意非终态 --cancel--> CANCELED
```

- 租约：`lease_token`（UUID）+ `lease_epoch`（递增）；心跳/结果上报必须携带**同一** epoch，
  否则 409 `STALE_LEASE`（迟到结果被 fence）；
- 超时回收：`lease_expires_at < now()` 且为 LEASED/RUNNING → 回到 `FAILED_RETRYABLE`（attempt<max）
  或 `FAILED`；回收由 Java 侧（`AiJobRecoveryJob`，可关闭）执行，不依赖 Python；
- 重试：`attempt_count += 1`，`next_attempt_at` 指数退避（30s/120s/…，上限 10min）；
- 幂等：`(job_id, dedupe_key)` 唯一；重复 results 只更新心跳、返回已有结果，不重复入库；
- 所有状态跃迁先 CAS `version`（`WHERE version=?`），失败重读一次，二次失败抛冲突。

### 3.3 内部端点（Java，沿用 `/internal/ai/**` 前缀与 Ed25519/静态令牌鉴权）

| 端点 | 请求 | 响应 |
| --- | --- | --- |
| `GET /internal/ai/v1/jobs/{id}/snapshot` | 头：`Authorization`；无请求体 | `{schemaVersion:1, jobId, projectId, attempt, leaseEpoch, snapshotHash, facts:[{ref,kind,text}], existingMemories:[{id,type,title,content}]}`（≤64 KiB） |
| `POST /internal/ai/v1/jobs/{id}/heartbeat` | `{schemaVersion:1, attempt, leaseEpoch}` | `{schemaVersion:1, status, leaseExpiresAt}` |
| `POST /internal/ai/v1/jobs/{id}/results` | `{schemaVersion:1, attempt, leaseEpoch, candidates:[…]}` | `{schemaVersion:1, jobId, status:"SUCCEEDED", accepted, duplicates}` |

- 令牌 scope：`job:snapshot` / `job:heartbeat` / `job:results`；令牌 claims 里的
  `jobId/attempt/leaseEpoch` 必须与路径/请求一致，否则 403（不代表业务权限，只是 lease 绑定）；
- 错误信封沿用内部九码子集：`INVALID_REQUEST` / `SCHEMA_VIOLATION` / `INTERNAL` / `MODEL_UNAVAILABLE`；
  租约不符用 HTTP 409 + `STALE_LEASE`（新增内部错误码，仅内部端点使用）；
- 响应不包含成员名单、文档正文、Prompt、令牌。

### 3.4 Java → Python `POST /v1/jobs`

```json
{"schemaVersion":1,"jobId":"<uuid>","type":"MEMORY_EXTRACTION","deadlineMs":60000}
```

- 头：`Authorization: Bearer <job-scoped token>`（Java 签发，claims 含
  `jobId/attempt/leaseEpoch/projectRef/snapshotHash`，scope
  `job:execute job:snapshot job:heartbeat job:results model-call`，TTL 900s）；
- Python 行为：校验 job token → `GET snapshot` → PromptPack `memory.extract.system/user` →
  模型调用（同一 token）→ 解析候选 → `POST results` → 返回
  `{schemaVersion:1, jobId, status, candidateCount, latencyMs}`；
- 失败映射：超时/网关不可用 → `FAILED_RETRYABLE`（HTTP 503 或 200 + status）；
  模型输出非法 → `FAILED`（422 `SCHEMA_VIOLATION`）；**Python 不重试、不调度**；
- 本波 `memory.job.enabled=false`：没有自动 dispatcher 在生产路径；仅测试与手动触发。

### 3.5 Memory 候选契约

```json
{"kind":"DECISION","title":"…","content":"…","confidence":0.82,
 "sourceRef":"memory:<uuid>","dedupeKey":"<sha256 hex 64>"}
```

- `kind ∈ {DECISION, PREFERENCE, CONSTRAINT, LESSON}`（与 `ck_agent_memory_type` 一致）；
- `title` 1–200、`content` 1–2000、`confidence ∈ [0,1]`、`sourceRef` 必须引用快照里的 fact/memory；
- `dedupeKey = sha256(kind|normalized(title)|normalized(content))`；与既有记忆标题/内容重复时
  候选重复率由此衡量；
- 每次 ≤50 条候选。

---

## 4. 评测

### 4.1 `ai-eval planning`

冻结集 `tests/fixtures/planning_set/planning_baseline.v1.jsonl`：≥12 条 case，每条含
`goal/projectFacts/constraints` + `modelOutput`（代表性模型输出，标注为 stub）。

指标：`schemaPassRate`（Python 契约 + 依赖/日期/成员/数量校验一次通过 / 用例数）、
`sourceViolations`、`assigneeViolations`、`duplicateTitles`、`cases`、`confidence`。
目标：Schema 一次通过率 ≥95%；无真实模型 → `TESTED (stub)` + `LOW_CONFIDENCE`。

### 4.2 `ai-eval memory`

冻结集 `tests/fixtures/memory_set/memory_baseline.v1.jsonl`：每条含快照 + `modelOutput`
（代表性候选输出）+ `humanJudgement`（人工判断：应当提取的候选集合）。

指标：`candidateAgreement`（与人工判断一致 / 人工判断总数或候选总数，取 F1 风格：精确率+召回率
分开输出，另给一致率）、`duplicateRate`（与既有记忆重复的候选 / 候选总数）、`schemaPassRate`、
`cases`、`confidence`。无真实模型 → `TESTED (stub)` + `LOW_CONFIDENCE`。

---

## 5. 默认开关与回滚

| 开关 | 默认 | 作用 | 回滚 |
| --- | --- | --- | --- |
| `PLANNING_DRAFT_MODE` | `java` | java/shadow | 设 `java` + 重启 |
| `PLANNING_DRAFT_WORKER_BASE_URL` / `_DEADLINE_MS` / `_MAX_CONCURRENCY` / `_SNAPSHOT_MAX_CHARS` | 同 §2 | shadow 边界 | 无需（mode=java 不访问） |
| `MEMORY_JOB_ENABLED` | `false` | Job dispatcher/恢复任务 | 设 false + 重启 |
| `MEMORY_JOB_MAX_ATTEMPTS` | `3` | 重试上限 | 设 1 |
| `MEMORY_JOB_LEASE_SECONDS` | `120` | 租约时长 | 调大 |

`rerank.mode` / `decide.mode` 默认值不变；V91 只新增表，不影响既有表读写。
回滚部署：V91 可留在库中（只增表），回滚镜像必须 ≥V91；或按"保留表、关开关"处理。

---

## 6. 停止条件

1. Job 表/状态机超出本会话 → 交付 planning draft 全量 + Memory 契约/Job 设计 + 可运行骨架
   （V91 + 状态机 + 端点 + 单测），报告明确"未完成：自动 dispatcher / 候选审核与发布"，
   **不得**假装 W5 全绿；
2. 若实现需要 Python 写 Job 表/调度/发布 → 停止并记录（违反红线）；
3. Schema 通过率无法在无真实模型下达成 → 标 `TESTED (stub)`/`LOW_CONFIDENCE`；
4. 任何 Python 草稿进入落库/用户结果/事件 → 回退该实现并记录。

---

## 7. 证据等级（实现后回填）

| 项 | 预期 |
| --- | --- |
| Python planning draft 契约/边界/自校验 | TESTED |
| Java planning shadow + 重跑 Validator + 隔离 | TESTED（stub worker） |
| Schema 一次通过率 | TESTED (stub) + LOW_CONFIDENCE |
| Memory 提取器契约/Job 握手/幂等 | TESTED（离线） |
| V91 + Job 状态机/租约/重试 | TESTED（单测；不含真实调度） |
| 候选一致率/重复率 | TESTED (stub) + LOW_CONFIDENCE |
| 真实模型 / 真实 Job 运行 | NOT_VERIFIED |
| 候选审核与发布 | 未实现（明确登记） |
