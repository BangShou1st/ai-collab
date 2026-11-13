# 本地 Demo 加固设计：4 个真实缺陷（2026-11-14）

> 性质：**设计，不含实现**
> 基线：`dev @ 258e71ed`
> 目标：先在本地 demo 把 4 个真实缺陷修到可演示、可回归，再讨论部署
> 非目标：生产 canary、W6 解冻、RAG 索引调优、MQ 双轨、仓库瘦身

## 0. 判定与优先级

| ID | 缺陷 | 类型 | 优先级 | 需要迁移 | 主要归属 |
| --- | --- | --- | --- | --- | --- |
| H-1 | 文档删除非原子，DB 失败留 `DELETING` 且无恢复 | 数据一致性 | **P0** | V100 | 后端 |
| H-2 | Access Token 无 `tv` 校验；停用用户不撤销 Refresh Token | 安全边界 | **P0** | 无（`token_version` 自 V1 已存在） | 后端/安全 |
| H-3 | Run 终态关流后，审批通过/拒绝事件无活跃订阅 | UX/语义 | P1 | 无 | 前端 + 小后端 |
| H-4 | 任务列表 N+1 且无分页 | 性能/契约 | P1 | V101（索引） | 后端 + 前端 |

排序理由：H-1 会产生不可见脏数据；H-2 是权限失效窗口；H-3 只影响可见性；
H-4 在数据量上来后才明显，但会先撞 JMeter 门禁。

## 1. 总原则

- 本地 demo 先跑通：不接生产、不启用 W6、不改六个默认开关、不碰 `main`。
- 每个修复必须有**失败注入测试**，不能只测 happy path。
- 对外契约变更要么向后兼容，要么同批改前端 + 契约测试，禁止半套。
- 证据统一放 `docs/acceptance-evidence/2026-11-14/local-hardening/<lane>/`。
- 不引入新中间件：复用 PostgreSQL、MinIO、现有 Spring 调度与 Caffeine。

## 2. H-1 文档删除一致性

### 2.1 现状与失败路径

`DocumentApplicationService.delete()` 的顺序是：

```
markDeleting（单独提交） → storage.delete(objectKey) → deletionService.deleteRows()
```

`DocumentDeletionService.deleteRows()` 才是 `@Transactional` 的行删除 + 审计。失败路径：

| 故障点 | 结果 |
| --- | --- |
| 对象已删、DB 删除失败 | 记录永久 `DELETING`，对象已丢，无法重试恢复 |
| 对象删除抛错 | 记录 `DELETING`，对象还在，无自动重试 |
| 进程在两步之间崩溃 | 同上，且无 reconciler |
| 用户重复点删除 | 依赖 `status<>'DELETING'` 跳过标记，但每次都会再删对象 |

读路径已过滤 `DELETING`，所以“脏数据不可见”，但会永久占用配额与审计盲区。

### 2.2 目标语义

1. 删除请求只做两件事，且在**同一事务**：标记 `DELETING` + 写入删除 Job。
2. 对象删除与行删除由可重试的 purge worker 完成，语义为 **at-least-once + 幂等**。
3. `DELETING` 必须始终对应一个 Job；不存在“孤儿 DELETING”。
4. 对象不存在视为删除成功（S3/MinIO `DeleteObject` 幂等）。
5. 删除失败进入 `FAILED`，管理端可见并可一键重试；不静默吞错。

状态机：

```
document: ACTIVE/READY/... --delete--> DELETING --purge ok--> (row gone)
job:      PENDING --> RUNNING --> DONE
                     \--> 失败且 attempts<5 --> PENDING(backoff)
                     \--> attempts>=5 --> FAILED --retry--> PENDING
```

### 2.3 V100 迁移

```sql
CREATE TABLE document_deletion_job (
    id              uuid PRIMARY KEY,
    project_id      uuid NOT NULL,
    document_id     uuid NOT NULL,
    object_key      text NOT NULL,
    status          varchar(16) NOT NULL
                    CHECK (status IN ('PENDING','RUNNING','DONE','FAILED')),
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until     timestamptz,
    last_error      varchar(500),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_deletion_job_document UNIQUE (document_id)
);

CREATE INDEX ix_document_deletion_job_due
    ON document_deletion_job (status, next_attempt_at);
```

`DONE` 行保留 7 天用于追溯，再由清理任务删除；不参与读路径。

### 2.4 流程

**同步 API**

```
DELETE /api/projects/{projectId}/documents/{documentId}
  tx: update status=DELETING where status<>'DELETING'
      insert job(PENDING) on conflict (document_id) do nothing
  返回 202 {deletionId, status:"PENDING"}
```

- 已是 `DELETING` 且 Job 存在：返回 202，幂等。
- Job 为 `FAILED`：同一 DELETE 重新入队（重置 attempts/next_attempt_at）。

**Purge worker**（`@Scheduled(fixedDelay=30s)`，单实例 demo 即可）

1. 认领：`SELECT ... FOR UPDATE SKIP LOCKED LIMIT 20`，置 `RUNNING`、`lease_until=now()+60s`。
2. 删对象：`storage.delete(objectKey)`；对象不存在按成功处理。
3. 删行：`deleteRows`（含审计）与 Job `DONE` 放同一事务。
4. 失败：`attempts+1`；`<5` 回 `PENDING`，退避 `30s * 2^(attempts-1)` 上限 1h；
   `>=5` 置 `FAILED` 并记录脱敏错误。
5. 恢复：启动时与每小时执行 reconciler，为 `DELETING` 但无 Job 的文档补 Job；
   把 `lease_until < now()` 的 `RUNNING` 重置为 `PENDING`。

### 2.5 测试

| 用例 | 断言 |
| --- | --- |
| 正常删除 | 202；对象删除 1 次；行删除；Job `DONE` |
| MinIO 不可用 | Job 保持 `PENDING`；退避；恢复后 `DONE` |
| DB 删除失败（注入） | 对象已删不报错；Job 重试；最终行删除成功 |
| 进程在删对象后崩溃 | reconciler 接管；不产生孤儿 |
| 重复 DELETE | 只有一个 Job；对象删除幂等 |
| 5 次失败 | Job `FAILED`；管理端可重试；错误脱敏 |

## 3. H-2 Token 撤销

### 3.1 现状

- `user.token_version` 自 V1 存在；修改密码与重置密码会 `+1` 并撤销 Refresh Token。
- **Access Token 不含 `tv`**，`JwtDecoder` 只校验签名/过期，不比对版本。
- `UserService.updateStatus()` 只改状态，不 `+1`、不撤销 Refresh Token。
- 结果：停用/改密后，旧 Access Token 仍可用最长 `access-token-minutes`（当前 30 分钟）；
  被停用用户的 Refresh Token 仍可换新 Access Token。

### 3.2 目标语义

1. Access Token 携带 `tv`，每次请求与用户当前 `token_version` 比对。
2. 用户状态不是 `ACTIVE` 时，任何用户态请求 401（`AUTH_SESSION_REVOKED`）。
3. 停用、删除、改密、重置、`logoutAll` 统一走 `revokeAllSessions(userId, reason)`：
   `token_version+1` + 撤销全部 Refresh Token + 失效本地缓存 + 审计。
4. Refresh 换发前重新校验用户状态与 `tv`。
5. Access Token 默认 TTL 从 30 分钟降为 **15 分钟**（可配置，纵深防御）。

### 3.3 实现

- `AccessTokenService` 增加 `.claim("tv", user.getTokenVersion())`。
- 新增 `UserSessionStateService`：`userId -> (status, tokenVersion)`，
  Caffeine `expireAfterWrite=30s`、`maximumSize=10_000`；`evict(userId)` 在撤销时同步调用。
- 新增 `AuthSessionGuardFilter`，挂在用户安全链 `BearerTokenAuthenticationFilter` 之后：
  - 仅处理 `JwtAuthenticationToken`；内部 token 链不受影响；
  - `sub` 或 `tv` 缺失 → 401；
  - 版本不匹配或状态非 `ACTIVE` → 清上下文 + 401。
- `UserService.updateStatus` 改为在一条 SQL 内 `set status, token_version=token_version+1`；
  调用方（停用/删除）同事务调用 `refreshTokens.revokeAllForUser` 与缓存 `evict`。
- `RefreshTokenService` 换发前加载用户，校验 `ACTIVE` 与 `tv` 一致。

### 3.4 兼容与窗口

- 旧 Token 无 `tv`：按 `0` 处理；用户 `token_version>0` 时旧 Token 立即失效（需重新登录）。
- 单实例本地 demo：撤销即时生效。
- 多实例：缓存 TTL 内最多 30s 偏差；若要求强一致，后续接 Redis pub/sub，不在本轮做。

### 3.5 测试

| 用例 | 断言 |
| --- | --- |
| 停用用户后立即请求 | 旧 Access Token 401 |
| 停用后刷新 | Refresh 401，且不签发新 Token |
| 改密后另一设备 | 旧 Access Token 401 |
| `logoutAll` | 所有 Refresh Token 撤销 |
| 重新启用 | 旧 Token 仍 401，必须重新登录 |
| 正常用户 | 不受影响，无额外 DB 查询（缓存命中） |
| 旧格式 Token | 版本 0 用户可用至过期；版本>0 被拒 |

## 4. H-3 审批事件可见性

### 4.1 现状

- `AgentEventStreamService.isTerminal()` 在 `RUN_SUCCEEDED` 等终态后关闭 SSE；
- `AgentApprovalService` 在审批通过/拒绝后继续向同一 Run 追加
  `APPROVAL_APPROVED/REJECTED`；
- 前端 `streamAgentEvents` 是 fetch 流，读到 `done` 即返回，不会自动重连；
- 审批 API 存在，但页面只在进入/操作时刷新，另一个浏览器看不到变化。

### 4.2 方案取舍

| 方案 | 结论 |
| --- | --- |
| 终态后保持 Run SSE 不关 | 否决：审批可能挂 24h，长期占用连接 |
| 新建项目级 SSE | 本轮不做，成本高，收益与轮询接近 |
| **终态后轮询审批收件箱 + 焦点刷新** | 采纳：最小改动，语义清晰 |

### 4.3 设计

- 后端补一个轻量端点：
  `GET /api/projects/{id}/agent/approvals?status=PENDING&updatedSince=<iso>`
  返回 `{items, serverTime, maxUpdatedAt}`，支持 ETag；无变化返回 304。
- 前端 `useApprovalInbox(runId)`：
  - 仅当 `run` 已终态且存在 `PENDING` 审批时启动 5s 轮询；
  - `document.visibilityState !== 'visible'` 时暂停；
  - `visibilitychange` / `focus` 立即拉一次；
  - approve/reject 成功后立即刷新，不等下一轮；
  - 组件卸载清理 timer 与 listener。
- Run SSE 行为不变（终态关闭是正确的）；重新打开页面时用
  `Last-Event-ID` 仍可回放历史审批事件。

### 4.4 验收

- 浏览器 A 发起写提案，Run 进入 `SUCCEEDED`；浏览器 B 审批通过；
  A 在 **≤5s** 内看到状态变化，无需手动刷新。
- A 切到后台标签页后停止轮询；切回立即刷新。
- 无 pending 时不发请求；304 不重复渲染。
- Run SSE 仍在终态关闭，不产生长连接泄漏。

## 5. H-4 任务列表

### 5.1 现状

- `TaskApplicationService.list()` 返回全量 `List<TaskView>`，无分页；
- `view()` 对每个任务调用 `tasks.dependencyIds(projectId, taskId)`，即 **1+N 查询**；
- 前端看板默认假设一次拿全量。

### 5.2 设计

**批量化**

- 新增 `TaskRepository.dependencyIdsByTask(projectId, List<UUID> taskIds)`：
  单条 `WHERE project_id=? AND task_id IN (...)`，按 500 分块。
- `list()` 先取任务页，再一次性取依赖映射，`TaskView.from(entity, map.getOrDefault(...))`。
- 目标：列表 SQL 数从 `1+N` 降到 **2**（分页 + 依赖）。

**分页**

- `GET /api/projects/{id}/tasks?page=0&size=50&status=&assigneeId=&milestoneId=`
- 默认 `size=50`，最大 `200`；返回 `PageView<TaskView>`：
  `{items,total,page,size,hasNext}`。
- 看板：按列请求 `status` + `size=200`，先保证一屏完整；超过 200 再加载更多。
- 列表视图：标准分页。
- 兼容：旧的无参调用改为默认第一页，前端同批适配；不保留“无分页全量”路径。

**索引（V101）**

```sql
CREATE INDEX ix_task_project_status_updated
    ON project_task (project_id, status, updated_at DESC);
CREATE INDEX ix_task_project_assignee
    ON project_task (project_id, assignee_id);
CREATE INDEX ix_task_project_milestone
    ON project_task (project_id, milestone_id);
```

依赖表已有主键/唯一约束时按实际执行计划补索引，不重复建。

### 5.3 验收

- 10,000 任务种子：列表 P95 **< 200ms**（本地），SQL 数恒为 2。
- 分页边界：`size=0/201` 拒绝；`page` 越界返回空页。
- 看板 200 条内行为与现在一致；超过 200 显示“加载更多”。
- JMeter per-label 复跑，项目详情不再出现 1ms 级抖动放大（作为诊断，不改冻结门禁）。

## 6. 执行编排（交给实现会话）

| Lane | 内容 | 分支 / worktree | 资源锁 | 依赖 |
| --- | --- | --- | --- | --- |
| A | H-1 删除 Job + V100 | `hardening/p0-doc-delete` | `local-minio`, `local-db` | 无 |
| B | H-2 token 撤销 | `hardening/p0-token-revoke` | `local-db` | 无 |
| C | H-3 审批收件箱 | `hardening/p1-approval-inbox` | `frontend-5173` | 无 |
| D | H-4 任务列表 + V101 | `hardening/p1-task-list` | `local-db`, `frontend-5173` | 无 |

- A/B 并行；C/D 并行；C 与 D 共享前端时用不同 worktree，不共享 `node_modules` 写入。
- 每个 Lane 只在自己的分支提交；中心 agent 复核原始证据后 cherry-pick 到 `dev`。
- 集成后统一跑：Java 定向 + 全量、前端 lint/typecheck/test/build、本地 demo runbook。
- 收口只留 `dev` / `main` 与主 worktree；Lane 分支打 `archive/20261114/*` tag 后删除。

## 7. 本地 Demo 验收清单

1. 停掉 MinIO 后删除文档：API 202、文档隐藏、Job `PENDING`；恢复 MinIO 后 `DONE`、行消失。
2. 注入 DB 删除失败：Job 重试，最终成功；无孤儿 `DELETING`。
3. 停用用户：旧 Access Token 下一次请求 401；Refresh 401；重新启用后旧 Token 仍 401。
4. 改密：另一浏览器旧 Token 401；当前端按产品约定重新登录。
5. 双浏览器审批：B 审批后 A ≤5s 看到状态；后台标签停止轮询。
6. 10k 任务：列表 P95 <200ms；SQL 数 2；看板正常。
7. 全量门禁绿；证据落在 `docs/acceptance-evidence/2026-11-14/local-hardening/`。

## 8. Demo 通过后的部署前置

- V100/V101 在 staging 先跑，验证 Flyway 与回滚脚本；
- Access Token TTL 30→15 分钟需要前端续期路径确认；
- 审批轮询在生产按项目/用户限流，避免放大；
- 任务分页是新契约，前端旧缓存与深链接要回归；
- 删除 Job 的失败告警接入现有日志/指标，不新增中间件。

## 9. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 删除改异步，前端以为已删完 | 202 + `DELETING` 状态 + 列表轮询；管理端显示失败可重试 |
| `tv` 上线导致全员掉线 | 仅 `token_version>0` 用户受影响；发布说明提前告知 |
| 审批轮询放大 DB | 5s、仅 pending、仅可见标签、304；后续可换项目 SSE |
| 分页破坏看板 | 同批改前端 + 契约测试；200 条内行为不变 |
| 多实例缓存不一致 | 本轮接受 ≤30s；后续 Redis 失效 |

## 10. 未接线 / 未完成清单（不纳入本轮）

> 判定原则：**默认关闭/双轨 = 能力未启用；规模债 = 设阈值；架构债 = 设护栏。**
> 三者都不按 bug 修，否则范围会无限膨胀；但必须在 demo runbook 里可见，
> 避免演示时被误认为“已启用”。

| 项 | 代码事实 | 性质 | 本轮动作 | 触发条件 |
| --- | --- | --- | --- | --- |
| pgvector 无 ANN 索引 | 迁移中无 `hnsw`/`ivfflat`，当前为精确扫描 | 规模债 | 记录阈值，不改 | 单项目向量 >5 万行，或 RAG P95 >800ms |
| 问答主链固定 `DENSE_LEGACY` | `KnowledgeStreamQuestionService` / `KnowledgeQuestionApplicationService` 直接构造 `DENSE_LEGACY`；`QueryRouter` 未成为主链策略 | 产品决策，不是欠债 | 保持现状；`QueryRouter` 标 `EXPERIMENTAL` | 有证据显示 V2 优于 Dense 且质量门禁通过 |
| Python worker 不在主拓扑 | `compose.local/staging/prod` 均无 `ai-worker`，生产默认 Java 路径 | 拓扑决策 | demo 单 Java；worker 仅诊断 | 需要独立部署认知面 |
| MQ 三条链路双轨 | `MQ_ENABLED=false`；planning/run 均可单独覆盖 | 默认关闭 | 不启用 | 需要削峰或多实例 |
| MCP Gateway | `CLOSED_AS_DEFAULT_OFF` | 已冻结 | 不碰 | 重新立项并按 8 项前置验收 |
| 工具准入单 JVM | 16 线程 + Semaphore，进程内限流，非集群全局 | 架构债 | 不修 | 部署多实例 |
| 子 Agent 每 Run 最多 3 个 | 产品上限，防委派风暴 | 不是缺陷 | 不修 | 产品明确要更高并行 |
| 状态机部分写路径直接 SQL；无 ArchUnit/Modulith | 声明式约束 + 部分直写；`pom.xml` 无架构测试依赖 | 架构债 | 下一轮加 3 条最小 ArchUnit 规则（不阻塞 demo） | 立即（可选 0.5 天） |

### 10.1 Demo 可见性要求

本地 demo 启动后必须能确认以下事实，写进 runbook 并在验收记录中截取：

- 六个默认开关与 `agent.mcp.gateway.enabled=false`；
- `MQ_ENABLED=false`，planning/run 均走进程内路径；
- Python worker 不在当前拓扑，AI 调用走 Java；
- 问答主链模式为 `DENSE_LEGACY`；
- 任务列表分页与删除 Job 已生效（H-4/H-1 的证据）。

### 10.2 最小 ArchUnit 规则（下一轮）

1. `domain/**` 不依赖 `infrastructure/**`；
2. `api/controller/**` 不注入 Mapper；
3. Repository 接口只出现在 `infrastructure/repository` 与其实现包。

先只加规则、不批量改代码；用 `allowEmptyShould(true)` 起步，列出违规清单后再决定修复批次。

## 11. 开关现状：能开 / 开不了 / 别开

| 开关 | 默认 | `python` 主链是否实现 | 开启前置 | 建议 |
| --- | --- | --- | --- | --- |
| `RERANK_MODE` | `java` | **是**（java / shadow / python） | worker + 服务令牌 + 模型档案 | 本地先 shadow，再 python（inline 8） |
| `DECIDE_MODE` | `java` | **是（F2 受限主链）**：根运行、只读 `PROJECT_RESEARCH`；失败回退 Java | worker + 服务令牌 | 默认保持 java；本地可 shadow→python 采数 |
| `PLANNING_DRAFT_MODE` | `java` | **否**：`python` 启动失败，仅 java/shadow | 需实现运行时主链 | 只开 shadow |
| `MEMORY_JOB_ENABLED` | `false` | dispatcher/recovery 已实现 | worker + 令牌 + 审批 | 本地可开，仅测试项目 |
| `MQ_ENABLED` | `false` | 不适用 | RocketMQ | 单机不开 |
| `QUERY_PLAN_ENABLED` | `false` | 客户端有，无生产调用方 | 接线 | 不开 |
| `agent.mcp.gateway.enabled` | `false` | 不适用 | 生产调用方 | 不开（已冻结） |

### 11.1 为什么“移到 Python”却像没用上

- 设计是双平面：Java 控制面（权限、事务、审批、SSE、密钥）+ Python 认知面
  （prompt、rerank、decide、planning、memory 提取）。
- 实际交付分三种形态：
  1. **可切换**：rerank 有完整 python 主链；
  2. **decide 已接受限主链（F2）**：默认仍 java，仅根运行只读 `PROJECT_RESEARCH` 使用 Python
     decision；planning 主链仍待 G；
  3. **只有离线评测**：W4/W5 的调优结果来自 harness 与 staging 评测，不是产品运行时。
- Java 保留旧实现是**默认与回退**，不是遗漏；代价是双份实现、双份维护。
- 所以“能开就开”不能作为默认策略：decide 目前只有受限主链，planning 主链仍待实现；
  默认保持 Java，切换必须有本地采数与回退证据。

### 11.2 推荐的开启顺序（本地 demo）

1. `docker compose --profile ai-worker up -d ai-worker-api`，配置
   `RERANK_SERVICE_TOKEN`、模型档案与 `RERANK_MODEL_USER_ID`；
2. 三个模式先全开 **shadow**：`RERANK_MODE=shadow`（采样 0.1）、
   `DECIDE_MODE=shadow`、`PLANNING_DRAFT_MODE=shadow`；
3. 观察一致率、失败率、P95 开销 1–2 天；
4. 只有 rerank 达到门禁后切 `RERANK_MODE=python`，仍保留 Java 回退；
5. `MEMORY_JOB_ENABLED=true` 只在测试项目开，保留 `autoPublished=0`；
6. decide 受限主链按 `docs/design/decide-python-mainchain-2026-11-14.md` 验收；planning 主链仍待 G，
   两者默认均不切换。

### 11.3 判定标准

- shadow 一致率达到现有门禁、失败率不高于 Java、P95 开销可接受，才允许切 python；
- 任一指标不达标，保留 Java 并记录原因，不为了“用上 Python”而切；
- 切换必须可按环境回退，禁止一次性删除 Java 实现。
