# Phase 08 AI 任务规划全面代码审查报告

- **审查日期**：2026-07-26
- **结论**：**不建议合并或发布**
- **总体评级**：存在多项阻断级业务与安全问题
- **审查依据**：
  - `2026-07-26-phase-08-ai-task-planning-design.md`
  - Codex 完成报告 `粘贴的文本 (1).txt`
  - 后端 Phase 08 源码、迁移与测试
  - 当前提供的前端 `src.zip`
  - `openapi.yaml`
  - `architecture.md`
  - `database.md`
  - Phase 08 学习文档与实施计划

## 1. 审查边界与验证说明

本次进行了逐文件静态审查、接口与设计逐项对照、OpenAPI 解析和测试覆盖检查。

已验证：

- OpenAPI 3.1.0 能被 YAML 解析；
- 共有 43 条 path；
- 内部 `$ref` 无缺失；
- 后端 Phase 08 共有 29 个生产源码文件；
- 后端 Phase 08 直接测试文件只有 4 个，连同 AI Gateway 测试共 5 个文件、16 个 `@Test`；
- 当前提供的前端源码共有 37 个文件，不包含任何 planning 模块。

未能独立验证：

- Git 分支和提交 hash：附件没有 `.git`；
- Maven 测试和构建：附件没有 Maven Wrapper，审查环境没有系统 Maven；
- pnpm 测试和构建：当前前端附件没有 Phase 08 文件和测试文件；
- 真实模型烟雾测试：只提供了 Codex 的文字声明，没有可复核日志或测试产物。

---

# 2. 阻断级问题（P0）

## P0-1：当前提供的前端源码完全没有 Phase 08 功能

### 证据

Codex 报告声称新增：

- `src/modules/planning/PlanningView.vue`
- `planning-api.ts`
- `planning-draft.ts`
- `planning-poller.ts`
- planning 测试
- AI 规划路由和导航
- 任务看板 `sourcePlanId` 高亮

但当前提供的前端 `src.zip` 中：

- 不存在 `src/modules/planning/`；
- `src/router.ts:1-28` 没有 AI 规划路由；
- `src/shared/AppShell.vue:62-68` 没有“AI 任务规划”入口；
- `src/modules/work/types.ts:16-33` 没有 `sourcePlanId`；
- 全部前端源码搜索不到 `planning`、`PlanningView`、`sourcePlanId` 或“AI 任务规划”。

### 影响

- 用户无法创建、查看、编辑或确认规划；
- 无法轮询生成状态；
- 无法取消、重试或恢复版本；
- 无法维护 Idempotency-Key；
- 任务看板无法高亮规划来源；
- Codex 报告中的“前端完成”和“4 个前端测试通过”无法由当前交付物支持。

### 结论

可能是：

1. 前端实现没有真正进入最终源码；或
2. 上传了错误/旧的前端压缩包。

两种情况都意味着当前交付物不完整。

---

## P0-2：AI 可以直接设置正式负责人，绕过管理员确认

### 设计要求

设计规格明确规定：

- AI 只能输出 `suggestedAssigneeId`；
- `assigneeId` 必须由管理员明确确认；
- 正式任务只使用管理员选择的 `assigneeId`。

### 代码证据

`TaskPlanGenerationOrchestrator.java:42-50`

- 模型 Schema 明确要求并允许输出 `assigneeId`。

`TaskPlanDraftValidator.java:112-115`

- 只检查 `assigneeId` 是否为项目成员；
- 没有要求 AI 生成版本中的 `assigneeId` 必须为空。

`TaskPlanConfirmationService.java:125-131`

- 正式任务直接写入 `task.assigneeId()`。

### 可复现场景

1. 模型在 DETAIL 阶段输出一个合法项目成员 UUID 到 `assigneeId`；
2. 完整校验通过；
3. 管理员未做任何人工编辑，直接确认；
4. 任务被正式分配给该成员。

### 影响

模型可以直接做正式人员分配，违反已确认的业务边界，也可能引发人员安排和责任归属错误。

### 修复要求

- AI 输出 DTO 中完全移除 `assigneeId`；
- AI_COMPLETE 构建时强制 `assigneeId=null`；
- 增加校验模式：
  - `AI_GENERATED`：任何非空 `assigneeId` 都是错误；
  - `MANUAL_EDIT/RESTORED/CONFIRM`：允许管理员设置；
- 增加自动测试，证明模型输出合法成员 ID 也会被拒绝。

---

## P0-3：第二阶段 Prompt 重新暴露 Prompt Injection，且缺少成员上下文

### 代码证据

`TaskPlanGenerationOrchestrator.java:24-27`

系统提示只声明以下区域不可信：

- `PROJECT_DATA`
- `PLAN_INPUT`
- `SOURCES`

没有声明 `SKELETON` 不可信。

`TaskPlanGenerationOrchestrator.java:230-234`

第二阶段把整个 skeleton 序列化到：

```text
<SKELETON>...</SKELETON>
```

而 skeleton 已经包含完整 `sources` 和 `quoteText`。

`PlanningPromptText.java:9-13`

只替换三个精确的大写结束标签，没有通用转义 `&`、`<`、`>`，也不处理大小写、空格或变体标签。

`TaskPlanGenerationOrchestrator.java:119-124`

第二阶段只调用 `detailPrompt(plan, skeleton)`，没有重新加入：

- 当前项目成员和角色；
- 当前已有任务；
- 当前已有里程碑；
- 原 `PROJECT_DATA`。

### 影响一：Prompt Injection

恶意文档正文会被放进 skeleton 的 sources，然后进入没有被声明为“不可信”的 `<SKELETON>` 区域。模型可能把文档中的“忽略之前规则”“设置负责人”“改变输出格式”等内容当作更可信的规划数据。

### 影响二：负责人推荐基本不可用

第二阶段负责生成 `suggestedAssigneeId`，但第二阶段没有成员 ID 列表。模型只能：

- 猜 UUID；
- 返回空；
- 从第一阶段自然语言字段中偶然提取。

这与“基于当前项目成员推荐负责人”不符。

### 修复要求

- 第二阶段必须重新加入受边界保护的项目成员上下文；
- `<SKELETON>` 必须明确声明为不可信数据；
- skeleton 传递时不要携带完整 source quote，来源应放回独立 `<SOURCES>`；
- 对所有不可信文本进行通用 XML/JSON 边界转义，至少转义 `&`、`<`、`>`；
- 增加包含混合大小写标签、空格标签和自然语言注入的测试。

---

## P0-4：失败确认的同 key 重试可以绕过状态机，确认过期版本

### 代码证据

`TaskPlanConfirmationService.java:65-79`

当找到相同 Idempotency-Key 且记录为 FAILED 时，代码直接：

```text
confirmation -> PROCESSING
plan -> CONFIRMING
```

没有检查当前 plan 是否仍然是 `READY`。

### 可复现场景

1. v2 确认失败，confirmation 状态为 FAILED，plan 回到 READY；
2. 管理员执行 regenerate，plan 进入 `SKELETON_GENERATING`；
3. 旧确认请求使用原 key 重试；
4. `claim()` 无条件把 plan 改为 `CONFIRMING`；
5. 旧 v2 被确认，新一轮生成被中断或丢弃。

### 影响

- 可以确认过期版本；
- 可以打断正在进行的重新生成；
- 状态机和 generation sequence 失效；
- 用户看到的当前规划和正式落地内容可能不一致。

### 修复要求

FAILED 重试必须重新检查：

- plan 仍为 READY；
- version 仍属于该 plan；
- 没有活动生成；
- confirmation 的 version/request hash 与当前请求一致。

任何其他状态返回 `409 TASK_PLAN_STATE_CONFLICT`，不得强制覆盖状态。

---

## P0-5：取消存在竞争窗口，会把 CANCELED attempt 改回 RUNNING；且没有真正尝试中断

### 代码证据

`TaskPlanGenerationOrchestrator.java:83-87`

执行顺序：

1. `active(...)`
2. `markRunning(...)`

`TaskPlanRepository.java:181-184`

`markRunning` 是无条件 UPDATE：

```sql
UPDATE ai_task_plan_attempt
SET status='RUNNING'
WHERE id=?
```

`TaskPlanRepository.java:123-133`

取消只修改数据库状态，没有保存或取消 `Future`。

`TaskPlanGenerationOrchestrator.java:70-80`

使用 `Executor.execute(Runnable)`，没有获得可取消的 Future。

### 可复现场景

1. worker 的 `active()` 返回 true；
2. 用户取消，attempt 变为 CANCELED，plan 变为 CANCELED；
3. worker 执行 `markRunning()`，把 attempt 又改成 RUNNING；
4. 模型请求仍然发出并消耗额度；
5. 最后才因为 plan 状态失效而丢弃结果。

### 影响

- attempt 历史不可信；
- “取消”不能尽力中断供应商请求；
- 取消后仍可能产生完整模型成本和延迟；
- 与完成报告中“支持取消并尽力中止”不符。

### 修复要求

- `markRunning` 使用 CAS，至少匹配：
  - `status='QUEUED'`
  - `cancel_requested=false`
  - plan 的 generationSeq/activeAttempt/status；
- UPDATE 影响 0 行立即退出；
- 使用 `submit()` 保存 Future，并在 cancel 时调用 `future.cancel(true)`；
- 模型调用前再检查一次 active；
- 保留数据库 generation sequence 作为最终正确性边界。

---

## P0-6：没有真正的 JSON Schema 校验，空规划可以进入 READY 并确认成功

### 代码证据

`TaskPlanOutputParser.java:15-23`

只执行 Jackson `readValue`，没有任何 JSON Schema Validator。

Prompt 中的 `DRAFT_SCHEMA` 只是给模型看的文字，不是服务端校验。

`TaskPlanGenerationOrchestrator.java:186-208`

骨架校验只检查“不要超过上限”，没有检查：

- 至少一个里程碑；
- 至少一个任务；
- summary；
- assumptions/risks 长度与数量；
- 里程碑日期；
- 文本长度；
- sortOrder；
- Schema required/additionalProperties。

`TaskPlanDraftValidator.java:25-27`

完整校验也只有最大数量，没有最小数量。

### 可复现输入

```json
{
  "summary": "",
  "assumptions": [],
  "risks": [],
  "milestones": [],
  "tasks": [],
  "sources": []
}
```

该输入可以：

1. 通过 skeleton 校验；
2. 通过 detail skeleton-preserved 校验；
3. 通过完整 validator；
4. 进入 READY；
5. confirm 创建 0 个里程碑、0 个任务；
6. plan 被标记为 CONFIRMED。

### 影响

系统可以产生并确认一个没有任何工作内容的“成功规划”。

### 修复要求

- 引入真实 JSON Schema 校验，或使用阶段专用 DTO + Bean Validation + 显式结构校验；
- skeleton/detail 使用不同 DTO；
- 至少要求：
  - milestones >= 1；
  - tasks >= 1；
  - summary 非空且受长度限制；
  - assumptions/risks 数量和单项长度受限；
  - sortOrder 非负；
  - 不允许未知字段；
  - 阶段一禁止细节字段；
  - 阶段二禁止修改骨架字段。

---

# 3. 高优先级问题（P1）

## P1-1：第二阶段仍可修改骨架中的日期、排序、摘要、假设和风险

`TaskPlanDraftValidator.java:170-178` 的身份比较只包含：

- tempKey；
- title；
- objective；
- 任务所属里程碑。

没有比较：

- milestone.targetDate；
- milestone.sortOrder；
- task.sortOrder；
- summary；
- assumptions；
- risks。

因此 DETAIL 模型可以改变第一阶段已经生成和保存的内容，但仍通过“骨架保持”检查。

---

## P1-2：所有版本的 validation_result 都被硬编码为空，warning 功能实际上不存在

`TaskPlanRepository.java:91-99`

每次版本写入都固定保存：

```json
{"errors":[],"warnings":[]}
```

真实 `ValidationResult` 从未写入数据库。

`TaskPlanCommandService.java:142-150`

人工保存只判断 valid，不保存 warning。

`TaskPlanGenerationOrchestrator.java:121-124`

AI 完整校验只转换为 boolean，warning 被丢弃。

### 影响

无法实现设计中的：

- 重名警告；
- 未分配负责人警告；
- 无来源 AI 建议；
- 确认弹窗 warning 统计；
- 点击 warning 定位任务。

---

## P1-3：依赖、来源和排序校验不完整

`TaskPlanDraftValidator` 没有检查：

- 同一个任务的依赖 tempKey 是否重复；
- sourceRefs 是否重复；
- sources 的 ref 是否重复；
- source ref 格式；
- sortOrder 是否非负；
- summary/assumptions/risks 限制；
- milestone 标题重复；
- source 数量最多 12。

重复依赖会通过业务校验，然后在 confirmation 插入 `task_dependency` 时触发主键冲突，表现为内部错误和确认回滚，而不是稳定的 `422 PLAN_VALIDATION_FAILED`。

---

## P1-4：队列满时返回 500，且 retry-detail 被错误改成 FAILED

`TaskPlanGenerationOrchestrator.java:70-80`

队列拒绝时：

- 所有场景都把 plan 改为 `FAILED`；
- 直接重新抛出 `RejectedExecutionException`。

### 影响

- Controller 最终通常返回 500，而不是 `503 PLANNING_QUEUE_FULL`；
- retry-detail 的骨架仍然有效，却被改成 FAILED，而非 `DETAIL_GENERATION_FAILED`；
- 客户端可能重试创建，产生重复规划。

---

## P1-5：确认失败后的新 key 会撞数据库唯一约束并返回 500

迁移定义：

```text
UNIQUE(plan_id)
```

`TaskPlanConfirmationService.java:81-90` 只复用 SUCCESS confirmation。

当旧 confirmation 为 FAILED、用户丢失旧 key 并使用新 key 时，代码尝试 INSERT 第二条同 plan 记录，触发数据库唯一约束。该异常发生在外层 try 之前，最终是 500。

应明确：

- 强制返回稳定冲突并提示复用旧 key；或
- 在 plan_id 唯一记录上安全替换 key/request hash。

不能依赖数据库异常作为 API 语义。

---

## P1-6：幂等重放的响应类型不稳定

首次确认成功时返回：

```text
List<UUID>
```

重放时 `TaskPlanConfirmationService.java:153-160` 直接把 JDBC 读取的 JSONB 对象放入响应。

PostgreSQL JDBC 通常会把 JSONB 返回为 `PGobject`，因此重放响应可能变成字符串或 `{type,value}`，而不是 UUID 数组。

这会导致：

- 第一次成功响应和重试响应结构不同；
- 前端在网络超时后重试时解析失败。

应使用 ObjectMapper 显式反序列化 JSONB。

---

## P1-7：项目一旦存在已确认规划，物理删除项目可能失败

迁移同时定义：

- `ai_task_plan.project_id ON DELETE CASCADE`；
- 正式 milestone/task 对 plan/version 使用 `ON DELETE RESTRICT`；
- confirmed plan 的 BEFORE DELETE trigger 永远拒绝删除。

项目删除服务目前只检查文档，不检查已确认规划。

删除包含已确认规划的项目时，级联删除会碰到 confirmed-plan trigger 或来源 RESTRICT，最终很可能变成数据库异常和 500。

应明确产品规则：

- 项目有确认规划时稳定返回业务冲突；或
- 项目删除时允许整体删除并为级联场景放行；
- 不应由数据库异常决定外部行为。

---

## P1-8：配置回退并不满足“非空逐项回退”

`application.yml` 使用：

```yaml
planning:
  model: "${PLANNING_MODEL:${CHAT_MODEL:}}"
```

当 `.env` 中存在但为空：

```text
PLANNING_MODEL=
```

Spring 会得到空字符串，不会继续使用 CHAT_MODEL。

同样，`PLANNING_MAX_OUTPUT_TOKENS` 缺失时直接用 6000，没有回退 `CHAT_MAX_OUTPUT_TOKENS`。

应使用集中式 `ResolvedPlanningModelProperties`，按“非空 planning → 非空 chat → planning 默认值”解析和测试。

---

## P1-9：规划限流没有 Redis 主路径

`PlanningGenerationRateLimiter.java` 只有 `ConcurrentHashMap`。

完成报告声称“限流具备进程内降级”，设计要求可沿用 Redis + 进程内降级，但当前实现只有进程内计数：

- 多实例之间不共享；
- 重启会清空额度；
- 不存在 Redis 故障后的“降级”，因为从未使用 Redis。

---

## P1-10：API 详情和版本列表没有达到设计要求

`TaskPlanQueryService.detail()` 只返回：

- plan；
- permissions。

缺少设计要求的：

- 最新版本摘要/草案；
- 活动 attempt 详情；
- 最近失败 attempt；
- confirmation 结果；
- 可展示的 validation warnings。

`versions()` 直接返回完整 `TaskPlanVersionRecord`，包含所有 JSON 字符串，不是轻量版本列表。版本多时响应会快速膨胀。

---

## P1-11：重新生成和细节重试的 AI 记录归属错误

attempt 使用实际操作人创建，但 orchestrator 的模型日志和新 AI 版本始终使用 `plan.createdBy()`。

因此：

- Admin B 为 Admin A 创建的规划点击 regenerate；
- AI call log 和 AI version 仍显示 Admin A；
- 审计与版本历史无法准确回答“谁触发了这次生成”。

应从当前 attempt 读取 `created_by`，并作为本轮 operator。

---

## P1-12：V5 会无条件 DROP 旧规划表，存在静默数据丢失风险

V5 开头直接：

```sql
DROP TABLE IF EXISTS ...
```

报告声称 V1 预留表“从未使用”，但迁移本身没有验证。

只要任何环境中曾写入旧规划数据，升级会静默删除。更安全的做法是：

- 先检测旧表行数；
- 有数据时中止迁移并提示显式迁移；
- 只有确认全空后才 DROP。

---

# 4. 中优先级问题（P2）

## P2-1：attempt 表的大部分字段从未写入

迁移包含：

- provider；
- model；
- latency_ms；
- prompt_tokens；
- completion_tokens；
- error_summary。

Repository 只更新 status、时间和 error_code。attempt 记录不能承担设计中的执行追踪职责。

## P2-2：生成成功后 active_attempt_id 没有清空

AI_COMPLETE 写入 READY 时仍保留成功的 detail attempt ID。“active”字段语义不准确，详情页也无法区分活动与最近一次。

## P2-3：手动保存的成员校验和版本写入不在同一事务快照

人工保存先读取成员并校验，之后才进入 appendVersion 的行锁事务。成员可能在两步之间离开项目，使非法 assignee 被保存进版本。确认会再次拦截，但“保存时校验”仍有竞争窗口。

## P2-4：规划创建/重试的无效请求也会消耗限流额度

rateLimiter 在日期、文档、状态检查之前调用。请求参数错误、文档未 READY、状态冲突都会减少用户额度。

## P2-5：Prompt 上下文没有总预算

来源只计算 `hit.content()`，没有计算：

- 来源标签；
- 文件名；
-标题；
- JSON 转义膨胀；
- 项目成员；
- 所有现有里程碑；
- 所有未完成任务。

大型项目可能产生远超预期的 Prompt。

## P2-6：缺少稳定的参数错误映射

缺失/非法 `Idempotency-Key`、非法 UUID path/header 等 MVC 参数异常没有专门 handler，可能被全局 `Exception` 映射为 500，而不是 400。

---

# 5. 文档与接口契约问题

## 5.1 architecture.md 仍声明 Phase 07 为当前阶段

`architecture.md:34-47` 明确写着：

- 当前实现里程碑为 Phase 07；
- AI 任务规划仍为 planned；
- AI 任务规划留待后续阶段。

其 Planning 章节还是旧的单阶段状态机和旧限制，与 V5 实现不一致。

## 5.2 database.md 内部自相矛盾

前半部分仍列出旧表：

- `ai_task_plan_milestone`
- `ai_task_plan_task`
- `ai_task_plan_dependency`

ERD 也是旧模型；后面才追加 V5 新模型说明。

## 5.3 OpenAPI 语法有效，但语义并不完整

已确认：

- 3.1.0 可解析；
- 43 paths；
- 无失效 `$ref`。

但存在语义问题：

- planning list 没有记录 status/page/size 查询参数；
- versions GET 没有实际响应 schema；
- skeleton version 中 description/priority 可以为空，但 `TaskPlanDraft` schema 要求非空完整任务；
- TaskView schema 没有 `sourcePlanId`；
- 大多数 planning endpoint 没有 400/403/404/409/429/503/504 契约；
- selectedDocumentIds 以内部 JSON 字符串暴露；
- detail schema 没有 attempt、confirmation、latest draft；
- replay 响应类型与 ApplyTaskPlanResult 不稳定。

因此“OpenAPI 可解析”不等于“OpenAPI 与实现和设计一致”。

---

# 6. 测试覆盖审查

Codex 报告声称覆盖：

- 两阶段生成；
- 自动修复；
- 取消与迟到结果；
- 队列拒绝；
- 超时/429/503；
- 并发保存；
- 并发确认；
- 四个故障注入点；
- Redis 故障；
- 重启恢复；
- 前端轮询、dirty、幂等 key、XSS。

实际附件中：

## 6.1 Orchestrator 测试

`TaskPlanGenerationOrchestratorTest` 只有一个测试：

- repair prompt 是否 Base64。

没有真实测试：

- skeleton → detail → READY；
- detail-only retry；
- cancel race；
- late result；
- repair 生命周期；
- queue full；
- context；
- source snapshot；
- skeleton mutation；
- provider failure。

## 6.2 Query 权限测试

只有一个 MEMBER permissions 测试。

没有 Controller/API 层的：

- OWNER/ADMIN；
- MEMBER 写 403；
- 非成员 404；
- 跨项目 version/document；
- confirmed 状态冲突。

## 6.3 Integration 测试

只有：

- 部分 V5 约束；
- confirmed plan 直接删除；
- 基本幂等 replay；
- 单个 task insert trigger 回滚。

没有：

- 并发版本保存；
- 两管理员并发确认；
- 同 key 不同 version；
- failed key 状态竞争；
- 四个指定故障点；
- Redis 不可用；
- recovery；
- 项目级联删除；
- 真实 latestVersion/basedOn 版本链删除；
- attempt 状态竞争。

## 6.4 前端测试

当前交付的前端源码没有 planning 文件，也没有报告所列 planning 测试文件。

---

# 7. 做得正确的部分

以下实现方向是合理的，建议保留：

- 项目范围查询多数携带 projectId；
- OWNER/ADMIN 写、MEMBER 读、非成员 404 的 Guard 使用方式基本正确；
- 版本号在 plan 行锁下分配，并有 `(plan_id, version_no)` 唯一约束；
- generationSeq + activeAttemptId + status 的迟到写回检查方向正确；
- 来源快照随版本持久化；
- 确认使用数据库 confirmation 作为事实来源；
- 正式里程碑、任务、依赖和 confirmation SUCCESS 在同一落地事务；
- audit 写入失败会让确认落地事务回滚；
- AI call log 不保存完整 Prompt 和文档正文；
- OpenAPI 本身语法有效且无坏 `$ref`；
- V5 提供了版本、幂等和正式来源的多道数据库唯一约束。

这些基础可以继续使用，但不能抵消上述阻断问题。

---

# 8. 推荐修复顺序

## 第一批：发布阻断

1. 补回或重新上传真实 Phase 08 前端源码；
2. 分离 AI 输出 DTO 与人工草案 DTO，禁止模型输出 assigneeId；
3. 修复 DETAIL prompt 上下文与不可信边界；
4. 引入真实结构校验，拒绝空规划；
5. 修复 FAILED confirmation 重试状态检查；
6. 修复 cancel CAS 和 Future 中断；
7. 增加对应回归测试。

## 第二批：一致性和幂等

8. 持久化真实 validation warnings；
9. 完整检查骨架不可修改字段；
10. 修复重复依赖/source/sortOrder 等校验；
11. 修复 queue full 的状态和错误码；
12. 修复 failed confirmation 新 key 和 replay JSON 类型；
13. 处理项目删除与 confirmed plan 的稳定语义。

## 第三批：可维护性

14. 实现集中式 Planning 配置解析；
15. Redis 限流 + 进程内降级；
16. 完善 attempt 指标和当前 operator；
17. 轻量化列表/版本接口；
18. 同步更新 OpenAPI、architecture、database；
19. 补齐异步、并发、事务和前端测试。

---

# 9. 最终结论

当前代码不是“无剩余 Critical/Important”，而是仍存在多项能够直接破坏核心业务约束的问题：

- 前端交付物缺失；
- AI 可绕过管理员直接分配正式负责人；
- DETAIL 阶段存在 Prompt Injection 信任边界漏洞；
- 失败确认重试可绕过状态机；
- 取消存在竞争窗口且不实际中断；
- 空规划可以被确认为成功；
- validation warnings、attempt、API 和测试覆盖均不完整。

建议暂时不要 merge，不要进入下一阶段。应先建立一轮针对本报告 P0/P1 的修复分支或继续在当前 Phase 08 分支修复，并在修复后重新做一次逐项复审。
