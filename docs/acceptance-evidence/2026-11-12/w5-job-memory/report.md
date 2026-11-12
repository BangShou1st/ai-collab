# W5 Job + Memory 闭环：本地实现与验收记录（2026-11-12）

> 泳道：D（W5 Job + Memory）
> 分支：`w5/job-memory`；基线：`c2495377`（已含 A/B 证据与冻结契约）
> 当前阶段：**本地实现 + staging Job/审核 E2E + D7-C 可执行诊断**
> 证据等级：`TESTED (offline/Testcontainers)`；Job/审核路径 `LIVE_VERIFIED (staging)`；D7-C 门禁仍 `NOT_EVALUATED`

---

## 0. 结论先行

| 项 | 结果 |
| --- | --- |
| Job 队列扫描/真实派发 | 已实现 `AiJobDispatchScheduler`，仅 `memory.job.enabled=true` 装配 |
| Job recovery 定时触发 | 已实现 `AiJobRecoveryJob.scheduledRun()`，仅 enabled + recovery-enabled 装配 |
| Job 状态机/租约/幂等/CAS | 复用并验证既有 V91/`AiJobService`，本地 32 项定向测试通过 |
| `/v1/jobs` Ed25519 job claims | 已有 Java 控制器 + Python worker 契约；本地控制器矩阵 10 项通过，static 503 负例保留 |
| 候选审核/发布 | 已实现 Java 管理 API：列表、approve、reject；行锁 + CAS + 审计；不自动发布 |
| 数据迁移 | **无新增迁移**；V91 未修改，仍为 91 个迁移 |
| 默认开关 | 未改：`MEMORY_JOB_ENABLED=false` 等六项保持冻结值 |
| staging `/v1/jobs` E2E | **PASS**：job `49281b14…` SUCCEEDED，approve 幂等发布，reject 不落库；默认已恢复 static/false |
| recovery 真实触发 | **PASS（观察）**：旧 job `2daf4f82…` 经 3 次 lease 过期回收，`AiJobRecoveryJob` 日志记录 `leaseExpired=1` |
| D7-C A/B/C 真实对照 | **NOT_EVALUATED**：A/B 本地镜像诊断已跑，C 仍 `NOT_VERIFIED`；不得写 PASS |

---

## 1. 实现范围

| 组件 | 改动 |
| --- | --- |
| `AiJobDispatchScheduler` | 扫描到期 QUEUED Job，逐条派发；单条失败不阻塞其它 Job |
| `AiJobRecoveryJob` | 增加 `@Scheduled` 包装；默认关闭，开关为 `MEMORY_JOB_ENABLED` + `MEMORY_JOB_RECOVERY_ENABLED` |
| `AiJobRepository` | 增加 `findQueued`、审核列表/行锁/CAS、按来源查已发布记忆 |
| `AgentMemoryJobController` / `AiJobAdminService` | 项目管理员显式创建/查询 Memory Job；默认关闭时拒绝创建 |
| `AgentMemoryCandidateController` / `AgentMemoryCandidateService` | 候选列表、approve、reject；项目管理员权限；不自动发布 |
| `AgentMemorySourceType` | 新增 `AI_JOB_RESULT`，对应 `source_id = ai_job_result.id` |
| `memory_arms` 口径 | C 从“路径未实现”改为“路径已实现、本次未执行真实对照”；仍标 `NOT_VERIFIED`/`NOT_EVALUATED` |

### 1.1 审核发布边界

1. job 结果初始 `review_status=PENDING_REVIEW`；
2. 只有项目管理员调用 approve 才会创建正式 `agent_memory`；
3. 同一候选重复 approve 返回同一来源记忆，不重复创建；
4. reject 必须给出 reason，不创建记忆；
5. 候选行使用 `SELECT ... FOR UPDATE` + `review_status` CAS，跨项目读取/审核返回 NOT_FOUND；
6. 已发布记忆复用现有 `AgentMemoryService` 的权限/来源校验/审计链，不绕过管理员边界。

---

## 2. 本地验证

| 检查 | 结果 | 证据等级 |
| --- | --- | --- |
| Java 定向：调度器、dispatcher 状态解析、Job service、V91 真库、候选审核、内部 Job 控制器 | **33 passed, 0 failed**，`raw/java-targeted.txt` | TESTED (offline/Testcontainers) |
| Java 全量 `mvnw verify` | **FAIL（1 项未改代码的时序断言；单测复跑 PASS）** | 见 §2.1 |
| Python `/v1/jobs` + claims + Java job client | **62 passed**，`raw/python-jobs.txt` | TESTED (offline) |
| Python memory-arms | **15 passed**，`raw/python-memory-arms.txt` | TESTED (local mirror) |
| 迁移 | Flyway 91 migrations，V91 未改 | TESTED |

### 2.1 全量 Java 回归说明

`mvnw verify` 本次跑完 **2899 tests / 1 failure / 0 errors / 61 skipped**；唯一失败是
`ConnectorSyncReliabilityIntegrationTest.activeLongTaskRenewsLeaseThroughHeartbeat`
的时间顺序断言（与 W5 文件无调用关系）。该用例单独复跑 **1/1 PASS**，判为时序 flaky；
**本次不宣称全量 verify 绿**，集成验证仍需在最终 HEAD 重跑。

命令（原始输出已保存到 `raw/java-targeted.txt`、`raw/python-jobs.txt`、`raw/python-memory-arms.txt`）：

```powershell
cd ai-collab-backend
.\mvnw.cmd -B -ntp '-Dtest=AiJobDispatchSchedulerTest,AiJobServiceTest,AiJobRepositoryIntegrationTest,AgentMemoryCandidateServiceTest,InternalAiJobControllerTest' test

cd ..\ai-collab-ai-worker
$env:SPEC_AGENT_TAVILY_API_KEY=$null
uv run --frozen pytest -q tests/integration/test_jobs_api.py tests/unit/test_job_claims.py tests/unit/test_java_jobs.py
uv run --frozen pytest -q tests/unit/test_memory_arms.py tests/integration/test_memory_arms_cli.py
```

---

## 3. staging E2E（`box-aae54f`，非生产）

### 3.1 首次失败暴露的真实缺陷

第一轮 Job `2daf4f82-8f96-418e-884b-e086314c0820` 进入 RUNNING 后未收敛。原始日志显示：

- Java dispatcher 发送 `deadlineMs=60000`，worker 原样转发给 Java `/internal/ai/v1/model-calls`；
- Java 网关上限为 30000，返回 `INVALID_REQUEST`；
- worker `/v1/jobs` 返回 200 + `FAILED_RETRYABLE`，但旧 dispatcher 把任何 2xx 都当成功，未回报 Job 失败。

修复：dispatcher deadline 改为 `30000`；解析 200 body 的 `FAILED_RETRYABLE`/`FAILED`/非法 body 并调用 Java `service.fail`；补 `AiJobDispatcherTest`。修复后旧 Job 由 recovery 回收为 3 次尝试 FAILED。

### 3.2 修复后成功路径

| 步骤 | 结果 |
| --- | --- |
| 测试态启用 | app `INTERNAL_AI_TOKEN_MODE=ed25519`、`MEMORY_JOB_ENABLED=true`、`MEMORY_JOB_RECOVERY_ENABLED=true`；worker `AI_WORKER_TOKEN_MODE=ed25519` |
| Job 创建/派发 | `49281b14-f854-4f84-81a6-0e5a9c0f43ec` 从 QUEUED → RUNNING → **SUCCEEDED**，attempt 1 |
| 候选 | `97944f76-db37-4fe1-980a-cb1f61992e5c`，`DECISION`，`PENDING_REVIEW` |
| approve 两次 | 两次均 200，返回**同一**记忆 `9762f1e7-e74b-41ee-9488-33790b585b1f`，`sourceType=AI_JOB_RESULT` |
| reject | 候选 `d8a42dd2-4766-4e2b-bfb8-7e2f28287897` → **REJECTED**；`agent_memory` 未增加 |
| recovery | `AiJobRecoveryJob` 日志 `leaseExpired=1`；旧 Job attempt 1/2/3 均为 lease expiry，最终 FAILED |
| 收尾 | app/worker healthy；六个默认开关恢复 `java/java/java/false/static/static`；临时 key/token/目录已删除 |

原始证据：`raw/staging/evidence/`（job/candidate/approve/reject JSON、DB snapshot、env/health、日志）以及
`raw/staging/defaults-after.txt`、`raw/staging/health-after.txt`。

### 3.3 `/v1/jobs` 鉴权与状态语义

既有 `InternalAiJobControllerTest` 已验证：

- 缺失 token → 401；
- `AI_WORKER_TOKEN_MODE=static` → job 端点 503 fail-closed（预期负例，不是“未验证”结论）；
- Ed25519 service token 无 job claims → 403；
- claims jobId/attempt/leaseEpoch 不匹配 → 403，且不进入服务层；
- 正常 snapshot/heartbeat/results → 200；
- STALE_LEASE → 409。

Python `test_jobs_api.py` 覆盖 Ed25519 job token、snapshot 绑定、心跳、结果回报、static fail-closed 与错误映射。

staging 已完成一次 Ed25519 job token 的完整 create → lease → snapshot → model call → results → SUCCEEDED。
static 模式的 503 仍只有离线负例证据；本次未把它当作“未验证”或“通过”。

---

## 4. 默认开关与合规

| 开关 | 值 |
| --- | --- |
| `MEMORY_JOB_ENABLED` | `false` |
| `MEMORY_JOB_RECOVERY_ENABLED` | `false` |
| `RERANK_MODE` / `DECIDE_MODE` / `PLANNING_DRAFT_MODE` | `java` / `java` / `java` |
| `INTERNAL_AI_TOKEN_MODE` / `AI_WORKER_TOKEN_MODE` | `static` / `static` |

- 未改前端、未改历史迁移、未新增迁移；
- 未自动发布候选；
- 未让 Python 写业务表或决定重试；
- 未把 static 503 写成通过。

---

## 5. D7-C 三组对照

`ai-eval memory-arms` staging 输出（`raw/staging/memory-arms.json`）：

- A `LEGACY`：`DIAGNOSTIC_ONLY`，Recall@K **0.454545**；
- B `GOVERNED_RECALL`：`DIAGNOSTIC_ONLY`，Recall@K **1.0**；
- C `DREAMING`：**NOT_VERIFIED**（路径已实现，但本次未跑真实 C 对照）；
- 三组门禁：**NOT_EVALUATED**。

本次未伪造 C 的 precision/recall、任务正确率、Dreaming 费用或人工审批率。后续若要在 staging 跑 C，必须使用真实 Job 结果和人工审核记录，不能用 stub fixture 替代。

---

## 6. 待办与交接

1. 在最终集成 HEAD 重跑全量 Java `verify`；本次唯一失败为未改代码的时序 flaky，不能写成全绿。
2. 真实 A/B/C 对照仍需下一窗口执行；precision/recall 只做诊断，撤权泄露必须为 0。
3. staging 测试项目因业务删除返回 500 仍留在 DB；临时目录 `/data/home/w5d` 已删除，不影响默认/密钥。
4. 补齐 Java/Python 定向原始日志文件；当前 staging raw 已在 `raw/staging/`。
