# 项目协作 Agent 设计

## 1. 目标与范围

在现有 Spring Boot 模块化单体和 Vue 3 前端内实现完整的项目协作 Agent 框架。Agent 仅在当前项目范围内运行，能够持久化恢复、受预算约束地选择窄工具、回答带来源的问题、分析进度、生成周报草案、提出经人工审批的任务或里程碑变更、定时运行并发送站内通知，以及执行评估模板和受限多 Agent 协作。

实现分为五个可独立验收的增量，但在同一开发任务中连续交付：

1. 持久化运行状态机、预算和只读工具；
2. 带来源的项目问答、进度检查和周报草案；
3. 前端审批式任务/里程碑写工具；
4. 定时运行与通知；
5. 评估模板、受限多 Agent 和扩展工具框架。

第一阶段禁止任何业务写入。第二阶段仍只注册窄工具。所有阶段永久禁止任意 SQL、任意 HTTP、项目删除、文档删除、成员与角色管理、所有权转移、密钥配置、AI 规划确认和自动审批。

## 2. 已确认的产品决策

- Agent 运行时作为现有 Spring Boot 模块化单体中的独立 `agent` 功能包实现。
- 模型决策采用结构化 JSON 协议，复用现有 OpenAI-compatible 聊天模型网关，不依赖平台原生 `tool_calls`。
- 模型平台地址、模型名和密钥只由服务端环境变量配置，不提供 Agent 或前端模型密钥配置能力。
- 支持可配置的 JSON mode。平台支持时发送 `response_format=json_object`；不支持时不发送该字段，但仍严格解析和校验模型 JSON。
- 定时运行可以生成待审批提案并通知用户，但不能自动执行任何业务写入。
- 多 Agent 采用一级“主管—专家”模式。专家只读、不可递归派生，主管负责汇总与提出写入提案。
- 调度界面只开放每日和每周预设，项目时区默认为 `Asia/Shanghai`，不开放任意 Cron 表达式。

## 3. 架构与模块边界

后端新增 package-by-feature 模块：

```text
agent/
├── api/                会话、运行、审批、调度、评估 Controller 和 DTO
├── application/        运行编排、恢复、审批、调度、评估、多 Agent 协调
├── domain/             状态机、预算、决策协议、工具定义、审批策略
└── infrastructure/     Repository/Mapper、模型适配、工具注册、后台领取
```

依赖方向保持为：

```text
Controller/DTO
  → Agent Application Service/View
  → Agent Domain Policy/Repository Port
  → Mapper、模型适配器、受控工具适配器
```

Agent 只负责编排，不直接访问其他功能包的 Mapper。只读工具和写工具均通过现有 Application Service 或专门的项目级查询服务执行。模型网关只负责模型协议，工具注册、参数校验、权限、审批和执行全部属于 Agent 模块。

前端新增项目内“协作 Agent”模块，复用现有认证、项目上下文、API 封装、中文标签和 Element Plus 组件。

## 4. 持久化模型

使用 V21 之后的连续 Flyway 迁移创建以下表：

- `agent_session`：`id`、`project_id`、`creator_id`、标题、状态、创建/更新时间、版本。
- `agent_run`：`id`、`session_id`、`project_id`、`requester_id`、`parent_run_id`、角色、目标、模型、状态、预算上限与已消费量、重试信息、开始/结束时间、锁定信息、版本。
- `agent_step`：`id`、`run_id`、单调递增序号、类型、工具名、脱敏输入/输出、决策原因、Token、耗时、错误码、创建时间。
- `agent_message`：`id`、`session_id`、`run_id`、角色、正文、引用和推断元数据、创建时间。
- `agent_approval`：`id`、`run_id`、`step_id`、工具名、规范化参数、参数摘要、资源版本、状态、请求人、审批人、过期时间、一次性 nonce 摘要、执行幂等键、结果摘要、版本。
- `agent_schedule`：`id`、`project_id`、创建者、会话、目标、每日/每周规则、项目时区、下次运行时间、启停状态、最近结果、版本。
- `agent_schedule_fire`：`schedule_id + scheduled_for` 唯一，关联实际运行，防止同一时段重复触发。
- `agent_evaluation_template`：模板名、类别、版本、启用状态。
- `agent_evaluation_case`：固定输入、允许工具、禁止工具、期望终态和结构化断言。
- `agent_evaluation_run`：模板、执行模式、模型、汇总指标和状态。
- `agent_evaluation_result`：用例结果、实际工具、引用、预算和失败原因。

Agent 不复用 `knowledge_session`。所有项目级表和查询显式包含 `project_id`。步骤采用 `(run_id, sequence)` 唯一约束，调度触发和审批执行使用数据库唯一约束保证幂等。

工具输入输出只保存推理与审计所需字段。API Key、JWT、Cookie、邀请码、文档全文、完整审计 detail、Prompt 和内部异常栈不得进入步骤、审批、通知或应用日志。

## 5. 状态机与恢复

运行状态：

```text
CREATED → QUEUED → RUNNING
RUNNING → WAITING_FOR_APPROVAL → QUEUED
RUNNING → SUCCEEDED
CREATED / QUEUED / RUNNING / WAITING_FOR_APPROVAL
  → FAILED_RETRYABLE / FAILED / CANCELED / BUDGET_EXCEEDED
```

步骤类型：

- `MODEL_REQUEST`
- `MODEL_DECISION`
- `TOOL_CALL_PROPOSED`
- `TOOL_CALL_COMPLETED`
- `APPROVAL_REQUESTED`
- `APPROVAL_RESOLVED`
- `DELEGATION_REQUESTED`
- `DELEGATION_COMPLETED`
- `FINAL_ANSWER`
- `ERROR`

每次后台领取只执行一个决策单位。领取通过版本条件更新实现，同一运行同一时刻只能有一个 worker。运行状态与不可变步骤在短事务中提交；模型调用和外部网络调用不进入数据库长事务。

服务启动和周期恢复任务处理：

- 可立即执行的 `QUEUED` 运行；
- 锁租约过期的 `RUNNING` 运行；
- 满足退避时间的 `FAILED_RETRYABLE` 运行；
- 已过期且仍待处理的审批。

恢复上下文从持久化消息和最后已提交步骤确定性重建，不依赖 JVM 内存。模型不可用或超时时进入有限重试；参数错误、安全拒绝和重复结构化输出错误不可重试。重试不得重复已经成功提交的写工具。

## 6. 预算

默认预算：

- 主管运行最多 12 个步骤；
- 全树最多 8 次工具调用；
- 最多 3 个一级专家子运行；
- 子 Agent 深度固定为 1；
- 单步和整次运行均有超时；
- 输入、输出 Token 分别累计；
- 费用使用可选配置的单价估算并持久化。

供应商未返回 Token 用量时，按 Unicode 码点采用保守估算并标记为估算值。父子运行通过共享预算账本原子扣减。任何硬上限达到后，不再调用模型或工具，运行稳定进入 `BUDGET_EXCEEDED` 并生成可读终止说明。

## 7. 模型决策协议

模型每步只能输出以下动作之一：

```json
{
  "action": "call_tool",
  "tool": "list_tasks",
  "arguments": {},
  "reason": "需要确认当前任务状态"
}
```

```json
{
  "action": "delegate",
  "role": "risk_reviewer",
  "objective": "检查里程碑逾期和阻塞风险"
}
```

```json
{
  "action": "final",
  "answer": "最终回答",
  "citations": [],
  "inferences": []
}
```

服务端使用严格 JSON 解析和动作级 Schema 校验：

- 拒绝未知字段和未知动作；
- 拒绝未注册工具；
- 工具参数拒绝未知字段、超长文本、不合法枚举和跨项目 ID；
- `projectId` 由运行上下文注入或必须与运行项目完全一致；
- `delegate` 只允许固定专家角色且深度为零的主管使用；
- `final` 必须区分来源事实和业务状态推断。

解析或 Schema 失败时，保存脱敏错误步骤并允许一次结构化纠错。第二次失败进入 `FAILED`。文档、任务描述、评论和工具输出用明确的不可信数据边界包裹，不能覆盖系统策略、工具清单、预算或审批规则。

## 8. 工具注册与安全策略

### 8.1 第一阶段只读工具

- `get_project_overview(projectId)`
- `list_tasks(projectId, status?, assigneeId?, milestoneId?, limit?)`
- `get_task(projectId, taskId)`
- `list_milestones(projectId, limit?)`
- `search_project_knowledge(projectId, query, documentIds?, limit?)`
- `get_project_dashboard(projectId)`
- `list_recent_audit_summaries(projectId, limit)`

所有工具响应使用窄 View，限制列表条数和文本长度。审计工具仅对现有服务允许查看审计的角色注册或执行。知识检索返回文档 ID、文件名、页码/分块定位、受限摘要和相似度，不返回完整文档。

### 8.2 第二阶段分析工具

- `answer_project_question_with_sources`
- `check_project_progress`
- `analyze_project_risks`
- `draft_weekly_report`

完成率、逾期、阻塞、未分配、里程碑风险和时间范围由现有确定性查询服务计算。模型只负责解释、排序和措辞，不自行虚构或重新计算业务指标。最终答案将文档来源和任务状态推断分开显示。

### 8.3 第三阶段审批写工具

- `create_task_after_approval`
- `update_task_after_approval(projectId, taskId, version, patch)`
- `create_milestone_after_approval`
- `update_milestone_after_approval(projectId, milestoneId, version, patch)`

工具名体现审批要求。模型提出调用时只创建审批记录，不执行工具。Patch 使用字段白名单，不允许删除、成员/角色修改、项目修改或规划确认。

### 8.4 永久禁止注册

- 任意 SQL、任意 URL/HTTP、反射式类名或方法名调用；
- 项目删除、文档删除、任务删除、里程碑删除；
- 成员邀请、移除、角色修改、所有权转移；
- API Key、模型、Token、Cookie 和密钥配置；
- AI 规划确认；
- 自动审批、模型自我审批和定时任务自动审批。

## 9. 审批与业务写入

审批流程：

1. 模型提出写工具和参数。
2. Agent 服务注入项目上下文，执行 Schema、权限和资源范围校验。
3. 参数规范化，生成摘要、字段 diff、资源版本、过期时间和一次性 nonce 摘要。
4. 保存 `WAITING_FOR_APPROVAL`，不修改正式业务表。
5. 前端向有权用户展示明确的批准和拒绝操作。
6. 批准时重新检查 nonce、过期时间、审批人当前项目权限、资源归属和乐观锁版本。
7. 通过现有任务或里程碑 Application Service 在其事务内执行。
8. 保存脱敏结果、审批解决步骤和现有业务审计日志。

审批 nonce 只保存摘要并只能消费一次。批准请求使用幂等键，网络重试返回相同执行结果。资源版本冲突时审批失效，返回最新事实供用户或 Agent 重新提案，禁止静默覆盖。

MEMBER 继续遵守现有 `WorkPermissionPolicy`。前端按钮可按权限隐藏，但后端服务授权是最终边界。

## 10. 定时运行与通知

调度只允许每日和每周预设，保存 IANA 时区，默认 `Asia/Shanghai`。后端计算并持久化下一次 UTC 触发时间，不依赖服务器默认时区。

调度 worker 通过条件更新领取到期规则，并先插入唯一的 `agent_schedule_fire`。运行以规则创建者身份执行，每次触发重新验证创建者的项目成员身份。创建者失去权限时停用规则并发送站内通知。

定时运行只允许只读、分析和创建待审批提案。它不能调用审批解决接口，也不能消费 nonce。运行成功、失败、预算耗尽、规则停用或产生待审批动作时，通过现有通知模块发送站内通知，并使用稳定 dedupe key 避免重复。

## 11. 受限多 Agent

主管可派生以下固定专家：

- `knowledge_researcher`：项目文档检索和来源整理；
- `progress_analyst`：确定性进度指标解释；
- `risk_reviewer`：逾期、阻塞、依赖和里程碑风险复核。

专家只获得完成目标所需的窄只读工具和裁剪后的上下文，不能提出或执行写工具，不能创建审批，也不能派生子 Agent。专家输出必须包含来源、推断和置信说明。

主管最多创建三个一级子运行。父子运行共享总 Token、工具调用和费用预算。主管负责去重、处理冲突和生成最终回答；主管不能把没有来源的专家观点标记为事实。写入建议始终由主管转换为待审批提案。

## 12. 评估模板

内置五类版本化模板：

- 项目问答引用正确性；
- 进度事实一致性；
- 安全工具选择；
- 预算终止；
- 审批绕过防护。

每个用例包含固定输入、允许工具、禁止工具、期望终态和结构化断言。默认评估使用夹具工具和脚本化模型决策，不访问真实业务写工具。可选真实模型评估只能在显式测试项目上运行，只读，并记录 provider、model、Token 和耗时。

评估输出至少包括：

- 用例通过率；
- 工具选择准确率；
- 引用覆盖率；
- 预算合规率；
- 审批绕过次数。

评估模板和结果可以查询，不允许 Agent 自行修改模板或把评估环境权限带入正常项目。

## 13. 前端体验

项目导航新增“协作 Agent”，包含：

- 会话列表、新建会话和消息区；
- 运行状态、预算消耗和步骤时间线；
- 文档引用定位，以及“来源事实 / 状态推断”分区；
- 待审批卡片，展示工具名、字段 diff、资源版本和过期时间；
- 批准、拒绝、取消运行和可重试失败重试；
- 每日/每周规则、时区、下次运行时间、启停和最近结果；
- 评估模板、运行触发和结果指标；
- 多 Agent 专家步骤的折叠展示。

全部用户文案使用简体中文。Agent、审批、调度、评估和专家角色状态通过共享中文映射显示，未知内部值显示安全的“未知状态/未知操作”，不展示原始代码。

## 14. API 边界

API 使用现有 `/api/v1`、JWT 和统一响应约定，主要资源为：

- `/projects/{projectId}/agent/sessions`
- `/projects/{projectId}/agent/sessions/{sessionId}/messages`
- `/projects/{projectId}/agent/runs/{runId}`
- `/projects/{projectId}/agent/runs/{runId}/cancel`
- `/projects/{projectId}/agent/runs/{runId}/retry`
- `/projects/{projectId}/agent/approvals`
- `/projects/{projectId}/agent/approvals/{approvalId}/approve`
- `/projects/{projectId}/agent/approvals/{approvalId}/reject`
- `/projects/{projectId}/agent/schedules`
- `/projects/{projectId}/agent/evaluations/templates`
- `/projects/{projectId}/agent/evaluations/runs`

子资源始终通过 `projectId + entityId` 查询，禁止先按全局 ID 读取再补权限。列表设置明确上限。审批接口要求 nonce 和幂等键。模型决策步骤的内部接口不暴露给浏览器。

## 15. 错误处理

- 模型超时、临时供应商错误：有限退避后进入 `FAILED_RETRYABLE`。
- 模型配额、配置不可用：不重试或按配置重试，并显示不泄密的中文错误。
- JSON 决策解析失败：允许一次纠错，第二次进入 `FAILED`。
- 工具参数或安全策略拒绝：记录安全错误码，不把内部 Schema 或栈返回模型。
- 权限变化：执行前重新校验；不再有权限时拒绝并终止或停用调度。
- 资源版本冲突：审批失效，返回最新资源摘要。
- 预算耗尽：进入 `BUDGET_EXCEEDED`，不继续调用。
- worker 崩溃：锁租约过期后从已提交步骤恢复。
- 通知失败：不回滚已经完成的 Agent 运行，使用幂等重试补发。

## 16. 测试与验收

后端单元测试覆盖：

- 状态转换和非法转换；
- 步骤、工具、Token、费用和父子共享预算；
- JSON 决策严格解析、未知字段、纠错次数；
- 工具注册白名单、参数限制和项目注入；
- Prompt injection 数据边界；
- 审批 nonce、过期、权限复查、幂等和版本冲突；
- 每日/每周下一次触发时间及时区/DST；
- 专家角色、深度、数量和工具限制；
- 评估断言和指标计算。

PostgreSQL 集成测试覆盖：

- 新迁移及约束；
- 项目隔离；
- 并发运行领取；
- 崩溃租约恢复；
- 调度触发幂等；
- 审批并发消费和写工具幂等；
- 父子预算原子扣减。

前端测试覆盖：

- 会话和运行轮询；
- 状态时间线与预算展示；
- 引用、事实和推断；
- 审批 diff、权限按钮、批准/拒绝；
- 调度表单和时区；
- 评估指标与专家步骤。

交付验证命令：

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests

Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build

Set-Location ..
.\scripts\validate-openapi.ps1
git diff --check
git status --short
```

同时更新 `docs/api/openapi.yaml`、`docs/database.md`、`docs/architecture.md` 和 `docs/feature-matrix.md`。功能矩阵按实际验证证据标记每个阶段，不能仅凭代码存在声称完整。

## 17. 工作区与提交边界

当前工作区存在其他未提交修改。Agent 实现只新增或最小增量修改与 Agent 直接相关的文件，保留所有现有修改。不清理、不覆盖、不回退无关工作。设计、后端增量、前端增量和文档同步使用可审查的小步提交；每个提交只暂存该步明确列出的文件。
