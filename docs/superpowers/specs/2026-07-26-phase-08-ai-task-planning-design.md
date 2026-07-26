# Phase 08：AI 任务规划与一键落地——设计规格

- **项目**：AI Collab 高校竞赛项目协作平台
- **阶段**：Phase 08
- **目标分支**：`feat/phase-08-ai-task-planning`
- **日期**：2026-07-26
- **状态**：待项目方最终书面确认后进入实施计划
- **范围**：完整 AI 任务规划闭环，不包含 Dashboard 重构或自动修改既有任务

---

## 1. 背景与目标

Phase 07 已完成基于项目文档的知识问答。Phase 08 在此基础上，把项目目标、团队成员、现有里程碑、未完成任务和管理员选定的知识库资料组合成规划上下文，通过两阶段模型生成得到可审查、可编辑、可追溯的任务规划草案。

本阶段必须完成以下闭环：

1. 管理员创建规划并异步生成；
2. 第一阶段生成里程碑与任务骨架；
3. 第二阶段补全任务细节、日期、依赖、负责人建议和来源引用；
4. 对模型输出进行解析、结构校验、业务校验和最多一次自动修复；
5. 保存不可变版本历史；
6. 支持手动编辑、保存新版本、查看与恢复历史版本；
7. 支持生成取消、细节重试、完整重新生成和进程重启恢复；
8. 使用幂等键和数据库事实记录确认规划；
9. 在单个业务事务中创建新的正式里程碑、任务和依赖；
10. 在任务看板追踪本次规划创建的数据；
11. 完成后端、前端、OpenAPI、数据库、测试和学习文档。

### 1.1 成功标准

用户能够从规划输入开始，最终在任务看板看到由已确认规划创建的新里程碑、任务与依赖。整个过程必须满足：

- 模型调用不占用数据库长事务；
- 模型输出不能绕过领域校验；
- 文档内容不能改变系统规则；
- 旧版本不可变；
- 并发保存不能静默覆盖；
- 重复确认不能重复创建业务数据；
- 任何确认落地异常都不能留下部分数据；
- MEMBER 可审阅但不能执行写操作；
- 非项目成员不能探测规划是否存在。

---

## 2. 已确认的产品决策

### 2.1 权限

- **OWNER / ADMIN**
  - 创建规划；
  - 取消生成；
  - 重试细节生成；
  - 完整重新生成；
  - 编辑并保存新版本；
  - 恢复历史版本；
  - 删除未确认规划；
  - 确认并落地。
- **MEMBER**
  - 只读查看规划列表、详情、版本、风险、假设、来源和确认结果；
  - 所有写请求返回 `403 FORBIDDEN`。
- **非项目成员**
  - 统一返回 `404 PROJECT_NOT_FOUND`；
  - 不泄露项目、规划或版本是否存在。

规划是项目共享资源，不是私人会话。

### 2.2 多规划

同一项目允许同时存在多个活动规划。确认其中一个规划不会自动取消或修改其他规划。

### 2.3 模型配置

优先使用独立的 `PLANNING_*` 配置。单个配置项缺失时，逐项回退到对应的 `CHAT_*` 配置。

### 2.4 版本历史

同一规划内保留不可变版本历史：

- AI 骨架；
- AI 完整规划；
- 人工编辑；
- 历史恢复。

任何保存、重新生成和恢复都不能覆盖旧版本。

### 2.5 保存方式

草案采用手动保存。前端编辑期间只修改工作副本，点击“保存草案”后才创建新版本。

### 2.6 负责人

AI 只提供 `suggestedAssigneeId`。管理员必须明确选择 `assigneeId`，确认后才写入正式任务；也允许保持未分配。

### 2.7 正式落地

确认规划时只创建新的里程碑、任务和依赖，不修改、合并或删除已有业务数据。疑似重名只生成警告。

### 2.8 上下文与知识库

系统自动加入项目基础信息，管理员手动选择最多 10 个当前项目的 READY 文档。后端只检索这些文档中与规划目标相关的片段。

### 2.9 来源引用

每个里程碑和任务可保存 `sourceRefs`。引用必须来自本次生成上下文的合法来源。无直接资料依据的内容标记为“AI 建议”。

### 2.10 规模限制

- 最多 8 个里程碑；
- 最多 40 个任务；
- 每个任务最多 5 个前置依赖；
- 每个里程碑或任务最多 5 条来源引用；
- 单次最多选择 10 个 READY 文档；
- `maxTaskCount` 仅允许 10、20、30、40。

### 2.11 生成方式

采用两阶段生成：

1. 骨架：摘要、假设、风险、里程碑、任务标题与所属里程碑；
2. 细节：描述、优先级、工时、日期、负责人建议、依赖与来源引用。

### 2.12 第二阶段失败

保留骨架并进入 `DETAIL_GENERATION_FAILED`。管理员可以只重试细节，也可以完整重新生成。

### 2.13 取消

支持尽力取消。无法及时中断的供应商响应必须在写库前被识别为迟到结果并丢弃。

### 2.14 日期

规划必须位于项目日期范围内：

`项目开始日期 ≤ 规划开始日期 ≤ 规划截止日期 ≤ 项目截止日期`

项目缺少某侧边界时只校验已存在的边界。里程碑、任务和依赖日期必须满足本规格的完整约束。

---

## 3. 范围与非范围

### 3.1 本阶段包含

- 规划主记录、版本、生成尝试、确认记录；
- 两阶段异步生成；
- 模型输出自动修复一次；
- 结构化校验和领域校验；
- 取消、重试、重新生成和重启恢复；
- 不可变版本与手动保存；
- 历史版本恢复；
- 并发冲突处理；
- 幂等确认和事务性落地；
- 任务/里程碑来源追踪；
- MEMBER 只读；
- 前端完整工作流；
- OpenAPI、数据库和学习文档；
- 单元、集成、异步、事务和前端测试。

### 3.2 本阶段不包含

- 自动修改或合并已有任务；
- 每个草案项手动选择“新建/更新/跳过”；
- 实时多人协同编辑；
- 字段级自动合并冲突；
- 用户在页面自由选择模型；
- AI 直接进行正式负责人分配；
- Dashboard 重构；
- 定时自动生成规划；
- 跨项目规划；
- 按里程碑分别调用模型；
- 删除已确认规划；
- 将模型自由文本直接写入正式业务表。

---

## 4. 领域模型

## 4.1 `ai_task_plan`

代表一项独立规划目标。

建议字段：

- `id`
- `project_id`
- `title`
- `goal`
- `constraints`
- `plan_start_date`
- `plan_due_date`
- `max_task_count`
- `selected_document_ids_json`
- `status`
- `latest_version_no`
- `latest_version_id`
- `generation_seq`
- `active_attempt_id`
- `created_by`
- `confirmed_by`
- `confirmed_version_id`
- `confirmed_at`
- `canceled_at`
- `created_at`
- `updated_at`
- `lock_version`

规划输入在创建后保持不变。需要改变目标、日期、约束或文档集合时，创建一条新规划。`regenerate` 只使用该规划原始输入，避免历史解释发生漂移。

`lock_version` 是数据库乐观锁字段，不是草案版本号。

## 4.2 状态机

状态：

- `SKELETON_GENERATING`
- `DETAIL_GENERATING`
- `DETAIL_GENERATION_FAILED`
- `READY`
- `CONFIRMING`
- `CONFIRMED`
- `FAILED`
- `CANCELED`

正常路径：

`SKELETON_GENERATING → DETAIL_GENERATING → READY → CONFIRMING → CONFIRMED`

失败路径：

- `SKELETON_GENERATING → FAILED`
- `DETAIL_GENERATING → DETAIL_GENERATION_FAILED`
- `DETAIL_GENERATION_FAILED → DETAIL_GENERATING`

取消路径：

- `SKELETON_GENERATING → CANCELED`
- `DETAIL_GENERATING → CANCELED`

重新生成路径：

- `READY / FAILED / DETAIL_GENERATION_FAILED / CANCELED → SKELETON_GENERATING`

`CONFIRMED` 是规划生命周期终态。`CANCELED` 是当前生成周期终态，但允许通过显式 `regenerate` 开始新的生成周期。

## 4.3 `ai_task_plan_version`

保存完整、不可变的版本快照。

字段：

- `id`
- `plan_id`
- `version_no`
- `source_type`
- `based_on_version_id`
- `generation_seq`
- `summary`
- `assumptions_json`
- `risks_json`
- `milestones_json`
- `tasks_json`
- `sources_json`
- `validation_result_json`
- `created_by`
- `created_at`

`source_type`：

- `AI_SKELETON`
- `AI_COMPLETE`
- `MANUAL_EDIT`
- `RESTORED`

约束：

- `UNIQUE(plan_id, version_no)`
- 版本正文只能 INSERT，不能通过业务 API 原地 UPDATE；
- 确认必须指定具体 `versionId`；
- 恢复历史版本时复制为新版本；
- 恢复、保存和确认都必须重新执行当前领域校验。

## 4.4 `ai_task_plan_attempt`

每一次供应商模型请求保存一条 attempt，包括自动修复请求。

字段：

- `id`
- `plan_id`
- `parent_attempt_id`
- `attempt_no`
- `generation_seq`
- `stage`
- `status`
- `repair_count`
- `cancel_requested`
- `provider`
- `model`
- `started_at`
- `finished_at`
- `latency_ms`
- `prompt_tokens`
- `completion_tokens`
- `error_code`
- `error_summary`
- `skeleton_version_id`
- `result_version_id`
- `created_by`
- `created_at`
- `updated_at`

`stage`：

- `SKELETON`
- `DETAIL`
- `REPAIR`

`status`：

- `QUEUED`
- `RUNNING`
- `SUCCESS`
- `FAILED`
- `CANCELED`
- `DISCARDED`

自动修复 attempt 使用 `parent_attempt_id` 指向首次非法输出的 attempt。每个阶段最多产生一次 REPAIR。

## 4.5 来源快照

每个完整版本保存不可变来源快照：

- `ref`
- `documentId`
- `documentName`
- `chunkId`
- `heading`
- `similarity`
- `quoteText`
- `contentHash`

来源编号为 `S1` 至 `S12`。里程碑和任务通过 `sourceRefs` 引用。人工编辑只能选择当前版本来源快照中已有的 ref，不能伪造新 ref。重新生成会重新检索并形成新的来源快照。

## 4.6 `ai_task_plan_confirmation`

保存数据库幂等事实和正式落地结果。

字段：

- `id`
- `project_id`
- `plan_id`
- `version_id`
- `idempotency_key`
- `request_hash`
- `status`
- `created_milestone_ids_json`
- `created_task_ids_json`
- `created_dependency_count`
- `error_code`
- `error_summary`
- `created_by`
- `created_at`
- `started_at`
- `completed_at`
- `updated_at`

`status`：

- `PROCESSING`
- `SUCCESS`
- `FAILED`

约束：

- `UNIQUE(project_id, idempotency_key)`
- `UNIQUE(plan_id)`

一条规划只能成功确认一次。失败后复用相同 key 和相同请求可以重试同一 confirmation 记录。

---

## 5. 数据库迁移

新增：

`V5__create_ai_task_planning.sql`

不得修改 V1 至 V4。

### 5.1 主表约束

- `max_task_count IN (10, 20, 30, 40)`
- `plan_start_date <= plan_due_date`
- `latest_version_no >= 0`

索引：

- `(project_id, updated_at DESC)`
- `(project_id, status, updated_at DESC)`
- `(created_by, updated_at DESC)`

### 5.2 版本表

- `UNIQUE(plan_id, version_no)`
- JSONB 存储假设、风险、里程碑、任务、来源和校验结果；
- 版本表随未确认规划删除而级联删除；
- 已确认规划禁止删除，因此其版本永久保留。

### 5.3 attempt 表

索引：

- `(plan_id, attempt_no DESC)`
- `(plan_id, status)`
- `(status, updated_at)`

禁止保存完整 Prompt、完整模型响应和 API Key。

### 5.4 confirmation 表

- `UNIQUE(project_id, idempotency_key)`
- `UNIQUE(plan_id)`

JSONB 保存本次创建出的里程碑和任务 ID 列表。

### 5.5 正式业务表来源字段

在现有正式任务表增加：

- `source_plan_id`
- `source_plan_version_id`
- `source_plan_task_key`

在现有正式里程碑表增加：

- `source_plan_id`
- `source_plan_version_id`
- `source_plan_milestone_key`

约束：

- `UNIQUE(source_plan_version_id, source_plan_task_key)`
- `UNIQUE(source_plan_version_id, source_plan_milestone_key)`

这些字段允许为空，不能影响人工创建的既有数据。

规划记录删除不能级联删除正式任务或里程碑。已确认规划本身禁止删除。

---

## 6. API 契约

统一前缀沿用项目现有规范。

## 6.1 列表

`GET /projects/{projectId}/ai/task-plans`

权限：OWNER、ADMIN、MEMBER。

查询：

- `status`
- `createdBy`
- `page`
- `size`

列表只返回摘要，不返回完整草案 JSON。

## 6.2 创建并生成

`POST /projects/{projectId}/ai/task-plans`

权限：OWNER、ADMIN。

请求：

```json
{
  "title": "完成竞赛作品开发",
  "goal": "根据比赛要求完成开发、测试、材料和答辩准备",
  "constraints": "每周至少完成一次内部验收",
  "planStartDate": "2026-08-01",
  "planDueDate": "2026-10-01",
  "maxTaskCount": 30,
  "documentIds": ["uuid"]
}
```

同步校验：

- 项目权限；
- 项目日期边界；
- 文档数量；
- 文档属于当前项目；
- 文档状态为 READY；
- 最大任务数枚举；
- 规划字段长度。

创建主记录、生成 attempt 和异步任务后返回 `202 Accepted`。

## 6.3 详情

`GET /projects/{projectId}/ai/task-plans/{planId}`

返回：

- 主记录；
- 最新版本摘要；
- 活动 attempt；
- 最近安全错误摘要；
- 确认结果；
- 当前用户权限；
- 可执行动作。

后端返回 `permissions`，前端不得仅通过角色和状态自行推导按钮。

## 6.4 取消

`POST /projects/{projectId}/ai/task-plans/{planId}/cancel`

允许状态：

- `SKELETON_GENERATING`
- `DETAIL_GENERATING`

重复取消 CANCELED 规划仍返回成功。返回 `202 Accepted`。

取消必须：

1. 锁定主记录；
2. 标记当前 attempt `cancel_requested=true`；
3. 增加 `generation_seq` 或使活动 attempt 失效；
4. 状态改为 CANCELED；
5. 尝试中断 Future 和 HTTP；
6. 供应商返回后再次核对 generation sequence 和 active attempt；
7. 迟到结果标记 DISCARDED，不创建版本。

## 6.5 重试细节

`POST /projects/{projectId}/ai/task-plans/{planId}/retry-detail`

仅允许 `DETAIL_GENERATION_FAILED`，且必须存在有效 AI_SKELETON 版本。

返回 `202 Accepted`。不得新增、删除、重命名骨架中的里程碑或任务。

## 6.6 完整重新生成

`POST /projects/{projectId}/ai/task-plans/{planId}/regenerate`

允许：

- `READY`
- `FAILED`
- `DETAIL_GENERATION_FAILED`
- `CANCELED`

不允许：

- `CONFIRMED`
- `SKELETON_GENERATING`
- `DETAIL_GENERATING`
- `CONFIRMING`

沿用原始规划输入，保留全部历史版本，增加 `generation_seq`，返回 `202 Accepted`。

## 6.7 版本列表

`GET /projects/{projectId}/ai/task-plans/{planId}/versions`

返回轻量版本信息。

## 6.8 指定版本

`GET /projects/{projectId}/ai/task-plans/{planId}/versions/{versionId}`

返回完整不可变快照。

## 6.9 保存新版本

`POST /projects/{projectId}/ai/task-plans/{planId}/versions`

仅允许 READY。

请求必须包含 `baseVersionId` 和完整草案。

处理：

1. 锁定规划；
2. 检查 `baseVersionId == latestVersionId`；
3. 重新读取项目成员与当前边界；
4. 执行完整领域校验；
5. 创建 MANUAL_EDIT 版本；
6. 更新 latest version。

并发冲突返回 `409 PLAN_VERSION_CONFLICT`。

## 6.10 恢复版本

`POST /projects/{projectId}/ai/task-plans/{planId}/versions/{versionId}/restore`

仅允许 READY。恢复前执行当前领域校验。通过后复制为 `RESTORED` 新版本。

## 6.11 确认

`POST /projects/{projectId}/ai/task-plans/{planId}/confirm`

Header：

`Idempotency-Key: UUID`

请求：

```json
{
  "versionId": "uuid"
}
```

确认必须指定版本，不能默认 latest version。

幂等规则：

- 相同 key、plan、version 和 request hash：
  - SUCCESS：返回第一次结果；
  - PROCESSING：返回 `202 Accepted` 和当前 confirmation；
  - FAILED：允许使用相同 key 重新进入 PROCESSING。
- 相同 key 对应不同 request hash：`409 IDEMPOTENCY_KEY_REUSED`。
- 已确认规划使用任何新 key：返回原成功 confirmation，不创建新记录和业务数据。

## 6.12 删除

`DELETE /projects/{projectId}/ai/task-plans/{planId}`

允许：

- `READY`
- `FAILED`
- `DETAIL_GENERATION_FAILED`
- `CANCELED`

不允许：

- 生成中；
- 确认中；
- 已确认。

生成中必须先取消。

---

## 7. 两阶段异步生成

## 7.1 上下文构建

自动加入：

- 项目名称、描述和日期；
- 当前项目成员与角色；
- 已有里程碑；
- 未完成任务；
- 规划标题、目标、约束和日期；
- 选定文档的相关片段。

模型调用在事务外执行。

## 7.2 文档检索

建议配置：

- `PLANNING_MAX_SOURCES=12`
- `PLANNING_SOURCE_CODEPOINT_BUDGET=16000`
- `PLANNING_RETRIEVAL_THRESHOLD=0.55`

检索文本：

`规划标题 + 目标 + 约束`

流程：

1. 仅检索选择的 READY 文档；
2. Top K 检索；
3. provider/model/dimension 过滤；
4. 相似度过滤；
5. content hash 去重；
6. 最多 12 条；
7. 严格 Unicode code point 预算；
8. 固化来源快照。

骨架和细节阶段使用同一来源快照，避免引用编号漂移。

## 7.3 Prompt Injection 防护

项目数据、用户输入和文档来源全部视为不可信数据。

必须：

- 使用明确的数据边界；
- 对边界字符转义；
- 明确声明来源中的指令、系统提示、角色声明和格式要求不得执行；
- 禁止来源内容提前闭合 `<SOURCES>`；
- 模型输出仍必须通过 schema 和领域校验。

## 7.4 第一阶段：骨架

仅允许输出：

- summary；
- assumptions；
- risks；
- milestones：tempKey、title、objective、targetDate、sortOrder；
- tasks：tempKey、milestoneTempKey、title、objective、sortOrder。

骨架校验：

- 里程碑不超过 8；
- 任务不超过 maxTaskCount；
- tempKey 全局唯一；
- 任务里程碑引用有效；
- 日期合法；
- 标题和目标非空；
- 不允许提前携带依赖、负责人和来源等细节字段。

成功后：

1. 创建 `AI_SKELETON` 版本；
2. 创建 DETAIL attempt；
3. 状态进入 `DETAIL_GENERATING`。

## 7.5 第二阶段：细节

必须保留骨架中的：

- 数量；
- tempKey；
- 标题；
- objective；
- 任务所属里程碑。

仅补充：

- 里程碑描述与来源；
- 任务描述、优先级、工时、日期；
- suggestedAssigneeId；
- dependencyTempKeys；
- sourceRefs；
- sortOrder。

通过完整领域校验后创建 `AI_COMPLETE` 版本并进入 READY。

## 7.6 自动修复

每个阶段最多一次自动修复。

首次失败时只向模型提供：

- 原始输出，明确标注为不可信；
- 精简错误代码；
- JSON Schema；
- 只输出 JSON 的要求。

不得发送：

- Java 堆栈；
- SQL；
- API Key；
- 完整内部系统提示；
- 与修复无关的敏感数据。

第二次失败：

- 骨架阶段进入 FAILED；
- 细节阶段进入 DETAIL_GENERATION_FAILED；
- 保存安全错误摘要；
- 不创建非法完整版本。

---

## 8. 领域校验

统一由 `TaskPlanDraftValidator` 实现，供以下路径共用：

- AI 完整结果；
- 人工保存；
- 历史恢复；
- 正式确认。

校验输出：

- `errors`
- `warnings`

存在 errors 时不能保存、恢复或确认。只有 warnings 可以继续。

### 8.1 硬错误

- 里程碑最多 8；
- 任务最多 40，且不超过规划 maxTaskCount；
- 每个任务最多 5 个依赖；
- 每项最多 5 个来源；
- tempKey 唯一；
- 里程碑引用有效；
- sourceRef 存在于版本来源快照；
- suggestedAssigneeId 和 assigneeId 属于当前项目成员；
- 成员退出后旧负责人失效；
- 规划位于项目日期边界内；
- 里程碑位于规划范围内；
- 任务开始和截止日期位于规划范围内；
- 任务开始日期不晚于截止日期；
- 前置任务截止日期不晚于后续任务开始日期；
- 禁止自依赖；
- 依赖图无环；
- 预计工时大于 0 且不超过配置上限；
- 标题、目标、描述长度满足约束；
- AI 细节阶段不能修改骨架不允许修改的字段。

### 8.2 警告

- 规划内部标题疑似重复；
- 与已有任务或里程碑疑似重名；
- 任务未指定最终负责人；
- 任务没有来源，属于 AI 建议；
- 工时或日期分布异常但仍合法。

---

## 9. 确认与事务

确认采用“认领事务 + 原子落地事务”，使 CONFIRMING 可观察，同时确保正式数据一次性提交。

## 9.1 事务外准备

- 权限检查；
- 规范化请求；
- 计算 request hash；
- 查询幂等记录；
- 读取版本做初步校验。

## 9.2 认领事务

短事务：

1. 锁定 plan；
2. 检查或创建 confirmation；
3. 处理相同 key 的 SUCCESS、PROCESSING、FAILED；
4. 检查 plan 仍为 READY；
5. 检查 version 属于 plan；
6. 将 confirmation 设为 PROCESSING；
7. 将 plan 设为 CONFIRMING；
8. 提交。

## 9.3 原子落地事务

新事务：

1. 再次锁定 plan 和 confirmation；
2. 检查仍为 CONFIRMING / PROCESSING；
3. 重新读取项目、成员和版本；
4. 再次执行完整领域校验；
5. 创建正式里程碑；
6. 建立 milestone tempKey 到 UUID 映射；
7. 创建正式任务；
8. 建立 task tempKey 到 UUID 映射；
9. 创建依赖；
10. 写来源规划字段；
11. 更新 plan 为 CONFIRMED；
12. 保存 confirmedVersionId、confirmedBy、confirmedAt；
13. 更新 confirmation 为 SUCCESS；
14. 保存创建结果；
15. 写审计日志；
16. 提交。

任何步骤失败，正式里程碑、任务和依赖全部回滚。

## 9.4 失败收尾

落地事务失败后，在独立短事务中：

- 如果 plan 仍为 CONFIRMING，则恢复 READY；
- confirmation 改为 FAILED；
- 保存安全错误码与摘要；
- 不删除版本；
- 同 key、同请求可以再次确认。

数据库是幂等事实来源。Redis 只能做快速缓存，Redis 不可用不能破坏正确性。

---

## 10. 并发控制

必须防止：

- 两名管理员保存出相同版本号；
- 基于旧版本静默覆盖；
- 取消后迟到模型结果写入；
- retry-detail 与 regenerate 同时开始；
- 两次确认重复创建；
- 确认时仍保存新版本；
- 删除与异步写回竞争。

规则：

- 状态切换使用行锁或带 lock_version 条件的更新；
- 创建版本时先锁定 plan；
- `nextVersionNo = latestVersionNo + 1`；
- 数据库唯一约束作为最终防线；
- attempt 写回必须同时匹配 planId、generationSeq、activeAttemptId 和允许状态；
- plan 进入 CONFIRMING 后禁止保存、恢复、重新生成和删除；
- confirmation 和来源字段唯一约束防止重复落地。

---

## 11. 异步执行与恢复

## 11.1 执行器

独立执行器：

`planningTaskExecutor`

建议：

- `PLANNING_EXECUTOR_CORE_SIZE=2`
- `PLANNING_EXECUTOR_MAX_SIZE=4`
- `PLANNING_EXECUTOR_QUEUE_CAPACITY=20`

队列拒绝时：

- attempt 进入 FAILED；
- plan 进入 FAILED；
- errorCode 为 `PLANNING_QUEUE_FULL`；
- 不留下伪生成状态。

## 11.2 写回检查

每次模型调用前和写库前都检查：

- plan.status；
- generationSeq；
- activeAttemptId；
- cancelRequested。

## 11.3 重启恢复

应用启动时和定时任务扫描超过 10 分钟未更新的：

- SKELETON_GENERATING；
- DETAIL_GENERATING；
- CONFIRMING。

恢复：

- SKELETON_GENERATING → FAILED；
- DETAIL_GENERATING → DETAIL_GENERATION_FAILED；
- CONFIRMING：
  - 已有 SUCCESS confirmation → CONFIRMED；
  - 否则 → READY，并将 PROCESSING confirmation 标记 FAILED。

对应 attempt 标记 FAILED，错误码 `PROCESS_RESTARTED`。

---

## 12. 模型配置

新增可空的 planning 配置。解析后的有效配置集中在一个组件中，例如：

`ResolvedPlanningModelProperties`

规则：

- `PLANNING_ENABLED` 缺失时回退 `CHAT_ENABLED`；
- 其他非空 `PLANNING_*` 优先；
- 缺失时逐项使用 `CHAT_*`；
- 两侧都缺失的调优参数使用 planning 默认值；
- 显式 `PLANNING_ENABLED=false` 禁用规划模型。

建议项：

- `PLANNING_ENABLED`
- `PLANNING_PROVIDER`
- `PLANNING_BASE_URL`
- `PLANNING_PATH`
- `PLANNING_API_KEY`
- `PLANNING_MODEL`
- `PLANNING_CONNECT_TIMEOUT=5s`
- `PLANNING_READ_TIMEOUT=120s`
- `PLANNING_TEMPERATURE=0.2`
- `PLANNING_MAX_OUTPUT_TOKENS=6000`
- `PLANNING_MAX_SOURCES=12`
- `PLANNING_SOURCE_CODEPOINT_BUDGET=16000`
- `PLANNING_RETRIEVAL_THRESHOLD=0.55`

业务代码不得散落重复回退逻辑。

---

## 13. 应用级限流

为了控制高成本生成调用，对以下操作使用同一用户级窗口：

- 创建并生成；
- 完整重新生成；
- 重试细节。

默认：

`PLANNING_GENERATION_LIMIT_PER_USER_HOUR=10`

取消、读取、人工保存、恢复和确认不计入该额度。

实现可沿用现有 Redis 限流模式，并提供进程内降级。供应商自身 429 独立映射为 `PLANNING_MODEL_RATE_LIMITED`。

---

## 14. 后端模块边界

建议新增或扩展独立 `planning` 模块：

- `api`
- `application`
- `domain`
- `infrastructure`
- `model`

核心组件：

- `TaskPlanCommandService`
- `TaskPlanQueryService`
- `TaskPlanGenerationOrchestrator`
- `TaskPlanContextAssembler`
- `TaskPlanModelClient`
- `TaskPlanOutputParser`
- `TaskPlanDraftValidator`
- `TaskPlanConfirmationService`
- `TaskPlanRecoveryJob`

职责要求：

- Controller 不承载业务编排；
- Model Client 不做领域校验；
- Output Parser 不访问数据库；
- Validator 是完整草案业务校验的唯一入口；
- Confirmation Service 不调用外部模型；
- Generation Orchestrator 不直接处理 HTTP；
- 不允许将整个模块堆入单个超大 Service。

---

## 15. 前端设计

路由：

`/projects/:projectId/ai-planning`

项目导航增加“AI 任务规划”。

## 15.1 列表

展示：

- 标题；
- 目标摘要；
- 创建人；
- 日期；
- 状态；
- 最新版本；
- 里程碑和任务数量；
- 更新时间；
- 安全失败摘要；
- 操作菜单。

支持状态筛选和分页。

生成中的记录轮询：

- 前 30 秒每 2 秒；
- 之后每 5 秒；
- 页面不可见时暂停；
- 只能由单一轮询控制器管理；
- 离开页面或状态稳定后停止。

## 15.2 创建规划

字段：

- 标题；
- 目标；
- 约束；
- 开始日期；
- 截止日期；
- 最大任务数；
- READY 文档。

创建成功后立即进入详情并展示生成状态。不得伪造具体百分比。

## 15.3 编辑器

布局：

- 左侧：里程碑与任务树；
- 中间：当前项编辑表单；
- 右侧：假设、风险、来源与校验结果；
- 顶部：版本选择和操作栏。

支持：

- 增删改排序里程碑与任务；
- 编辑日期、优先级、工时；
- 查看 AI 推荐负责人；
- 选择最终负责人；
- 编辑依赖；
- 编辑合法 sourceRefs；
- 定位校验错误。

删除被依赖任务时提示影响，并在确认后同步清除相关依赖。

## 15.4 版本历史

历史版本只读。OWNER/ADMIN 可“恢复为新版本”。MEMBER 始终只读。

版本标签示例：

- v1 AI 骨架；
- v2 AI 完整规划；
- v3 人工编辑；
- v4 恢复自 v2。

## 15.5 未保存保护

维护：

- `baseVersionId`
- `serverSnapshot`
- `workingDraft`
- `isDirty`

以下操作在 dirty 时确认：

- 离开页面；
- 切换规划；
- 切换版本；
- 重新生成；
- 浏览器刷新。

`409 PLAN_VERSION_CONFLICT` 时：

- 提供加载最新版本；
- 保留当前工作副本供复制；
- 不自动合并复杂 JSON。

## 15.6 确认

确认弹窗显示：

- 将创建的里程碑数；
- 任务数；
- 依赖数；
- 未分配任务数；
- warnings 数量。

用户必须勾选已检查声明。

前端生成并持久保留 `Idempotency-Key`。网络超时后的重试必须复用原 key，直到收到明确结果。

确认成功后显示创建结果并提供“查看任务看板”。任务看板支持按 `sourcePlanId` 高亮。

## 15.7 安全渲染

AI 输出、风险、描述和引用全部是不可信文本：

- 禁止原始 HTML；
- 清理 `javascript:`；
- 禁止 script、iframe 和事件属性；
- 使用项目现有安全 Markdown 方案；
- 不能用 `v-html` 直接渲染未清洗内容。

---

## 16. 错误码与 HTTP

主要错误码：

- `TASK_PLAN_NOT_FOUND`
- `TASK_PLAN_STATE_CONFLICT`
- `TASK_PLAN_VERSION_NOT_FOUND`
- `PLAN_VERSION_CONFLICT`
- `PLAN_VALIDATION_FAILED`
- `PLAN_GENERATION_FAILED`
- `PLAN_DETAIL_GENERATION_FAILED`
- `PLAN_GENERATION_CANCELED`
- `PLAN_ALREADY_CONFIRMED`
- `IDEMPOTENCY_KEY_REUSED`
- `PLANNING_MODEL_UNAVAILABLE`
- `PLANNING_MODEL_TIMEOUT`
- `PLANNING_MODEL_RATE_LIMITED`
- `PLANNING_MODEL_INVALID_OUTPUT`
- `PLANNING_DOCUMENT_NOT_READY`
- `PLANNING_DOCUMENT_LIMIT_EXCEEDED`
- `PLANNING_QUEUE_FULL`

HTTP：

- 400：请求格式和字段错误；
- 403：项目成员但无写权限；
- 404：非成员、规划或版本不可见；
- 409：状态、版本、并发和幂等冲突；
- 422：草案领域校验失败；
- 429：应用级生成限流或供应商限流；
- 503：模型未配置、供应商不可用或队列不可用；
- 504：模型超时。

错误响应不得包含 Prompt、模型原文、文档全文、SQL 或堆栈。

---

## 17. 日志与审计

允许记录：

- projectId；
- planId；
- versionId；
- attemptId；
- confirmationId；
- stage；
- provider；
- model；
- 状态；
- 延迟；
- Token 数；
- 数量统计；
- 错误码；
- requestId。

禁止记录：

- API Key；
- 完整 Prompt；
- 完整模型响应；
- 完整文档片段；
- 完整草案 JSON；
- 成员敏感评价。

AI 调用继续写入统一 `ai_call_log`，feature 需能区分骨架、细节和修复。

审计至少覆盖：

- 创建规划；
- 取消；
- 重试；
- 重新生成；
- 保存版本；
- 恢复；
- 删除；
- 确认成功或失败。

---

## 18. 测试策略

## 18.1 单元测试

覆盖：

- 项目和规划日期边界；
- 里程碑与任务日期；
- tempKey 唯一；
- 非法里程碑引用；
- 非法 sourceRef；
- 成员合法性；
- 规模限制；
- 自依赖；
- 多层循环；
- 依赖日期冲突；
- errors/warnings 分离；
- 自动修复只允许一次；
- `PLANNING_* → CHAT_*` 逐项回退；
- Unicode code point 预算；
- Prompt 边界转义；
- request hash 规范化。

## 18.2 Repository 集成测试

使用真实 PostgreSQL/Testcontainers：

- 版本号唯一；
- 并发保存仅一个成功；
- 幂等 key 唯一；
- plan confirmation 唯一；
- 正式来源字段唯一；
- 未确认规划删除级联；
- 已确认规划不可删除；
- 规划删除不影响正式业务数据。

## 18.3 异步编排测试

使用 Fake Model Client、可控 Executor、Latch/Future，禁止依赖真实 sleep：

- 骨架成功、细节成功；
- 骨架非法后修复成功；
- 骨架两次非法进入 FAILED；
- 细节失败保留骨架；
- 重试细节成功；
- 生成中取消；
- 取消后迟到结果 DISCARDED；
- regenerate 后旧 attempt 迟到；
- 队列拒绝；
- 进程重启恢复；
- 模型超时、429、503；
- detail 阶段偷偷修改骨架被拒绝。

## 18.4 确认事务测试

故障注入：

- 创建第 3 个里程碑时异常；
- 创建第 17 个任务时异常；
- 创建依赖时异常；
- 写审计前异常。

每次必须确认：

- 里程碑无新增；
- 任务无新增；
- 依赖无新增；
- plan 不为 CONFIRMED；
- confirmation 不为 SUCCESS。

同时覆盖：

- 相同 key 重试返回相同结果；
- 相同 key 不同请求返回 409；
- 新 key 重复确认已确认规划返回原结果；
- Redis 停止后幂等仍正确；
- 两名管理员并发确认只有一次真实创建。

## 18.5 Controller 权限测试

- OWNER 全权限；
- ADMIN 全权限；
- MEMBER 可读不可写；
- 非成员 404；
- 跨项目 planId 404；
- 跨项目 versionId 404；
- 已退出成员不能继续作为负责人；
- CONFIRMED 不可编辑、恢复、删除或 regenerate。

## 18.6 前端测试

- 列表状态；
- 项目切换隔离；
- 轮询开始、降频、暂停和停止；
- MEMBER 无写按钮；
- dirty 离开提示；
- 删除被依赖任务；
- 循环依赖即时提示；
- 版本冲突；
- 确认重试复用 key；
- 成功跳转任务看板；
- XSS 文本不能执行。

---

## 19. 正式验收

必须完成：

- 创建规划立即返回 202；
- 真实模型骨架和细节调用；
- READY 包含里程碑、任务、建议负责人和来源；
- 人工编辑并保存新版本；
- 查看并恢复旧版本；
- 两管理员制造版本冲突；
- 细节失败后只重试第二阶段；
- 生成中取消并验证迟到结果未写入；
- 循环依赖不能保存；
- 超出项目日期不能保存；
- MEMBER 可读、写请求 403；
- 非成员 404；
- 确认创建里程碑、任务和依赖；
- 重复确认不重复创建；
- 确认故障整体回滚；
- Redis 停止后幂等仍正确；
- 任务看板追踪来源规划；
- 后端测试和构建通过；
- 前端 typecheck、测试和 build 通过；
- OpenAPI 与实现一致；
- 数据库和 Phase 08 学习文档更新；
- `.env`、密钥、target、dist、node_modules 和压缩包未提交。

---

## 20. 文档交付

更新：

- `docs/api/openapi.yaml`
- `docs/architecture.md`
- `docs/database.md`

新增：

- `docs/learning/phase-08-ai-task-planning.md`

OpenAPI 必须删除或明确废弃旧的覆盖式 `PUT .../draft`，采用创建不可变版本的 `POST .../versions`。

---

## 21. 建议提交拆分

1. `feat(db): add AI task planning schema`
2. `feat(planning): add task plan domain and query APIs`
3. `feat(planning): implement two-stage model generation`
4. `feat(planning): add immutable draft versions and validation`
5. `feat(planning): implement cancellation and recovery`
6. `feat(planning): add idempotent transactional confirmation`
7. `feat(frontend): add AI task planning workflow`
8. `test(planning): add generation validation and confirmation tests`
9. `docs: document Phase 08 AI task planning`

---

## 22. 实施前置条件

实施必须从最新 main 创建分支：

```powershell
git switch main
git pull --ff-only
git switch -c feat/phase-08-ai-task-planning
```

开始编码前先检查：

- 当前仓库结构；
- 现有 task、milestone、project、document、AI client、Redis、audit 模式；
- V1 至 V4 实际表名与外键；
- 现有 OpenAPI 返回包装与错误格式；
- 现有前端路由、状态管理和安全 Markdown 组件；
- 现有测试基础设施。

不得依据本规格猜测实际类名和表名；应遵循仓库现有命名与分层。

---

## 23. 规格自检结果

- 无 TBD、TODO 或未决产品选项；
- 状态机与取消、重试、重新生成规则一致；
- `CONFIRMING` 采用认领事务与原子落地事务，解决可观察状态和事务原子性的冲突；
- `CANCELED` 明确为生成周期终态，可显式重新生成；
- 规划输入创建后不可变，避免 regenerate 输入语义不清；
- 来源快照、版本、负责人和确认幂等均定义了事实来源；
- Redis 不承担幂等正确性；
- 正式数据创建只发生在单个原子事务；
- 范围聚焦在一个完整业务模块，没有混入 Dashboard 等独立子系统。
