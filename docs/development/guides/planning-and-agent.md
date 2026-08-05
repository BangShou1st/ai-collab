# AI 规划与 Agent 实现指南

## 功能边界

本指南覆盖模型配置与路由、AI 任务规划、结构化输出、版本与人工确认，以及 Agent 会话、执行计划、原生 Tool Calling、事件流、Skill、项目记忆、审批写工具和受控 MCP。

AI 不拥有业务权限。模型输出、文档内容和 MCP 返回都不可信；任何业务写入都必须重新经过服务端权限、状态、参数、并发和事务校验。

## 当前入口

模型轮次：

- `infrastructure/ai/turn/ModelTurnGateway.java`
- `infrastructure/ai/model/RoutingModelTurnGateway.java`
- `infrastructure/ai/model/ModelTurnProviderAdapter.java`
- `OpenAiCompatibleModelAdapter.java`、`AnthropicModelAdapter.java`、`GeminiModelAdapter.java`

规划：

- `planning/application/TaskPlanGenerationOrchestrator.java`
- `TaskPlanModelClient.java`
- `TaskPlanCommandService.java`
- `TaskPlanConfirmationService.java`
- `TaskPlanQueryService.java`

Agent：

- `agent/api/controller/AgentSessionController.java`
- `AgentEventController.java`、`AgentMemoryController.java`、`AgentSkillController.java`
- `agent/application/AgentRunService.java`
- `agent/application/runtime/AgentRuntimeCoordinator.java`
- `NativeToolCallingExecutor.java`、`LegacyReadOnlyAgentExecutor.java`
- `AgentEventService.java`、`AgentEventStreamService.java`
- `agent/application/AgentApprovalService.java`
- `agent/domain/model/AgentSkillRegistry.java`
- `agent/infrastructure/tool/AgentToolRegistry.java`
- `agent/infrastructure/mcp/`

前端：

- `modules/planning/PlanningView.vue`
- `modules/agent/AgentView.vue`
- `modules/agent/agent-run-store.ts`
- `modules/agent/agent-event-stream.ts`
- `modules/admin/AdminView.vue`

## 模型 Gateway

知识问答和规划依赖统一 `ChatModelGateway`；Agent 原生工具循环依赖 `ModelTurnGateway`。用途通过 `ModelPurpose` 路由到管理员配置。Provider adapter 只负责供应商协议差异，业务语义、工具权限和终态由 Agent runtime 决定。

增加或修改供应商时必须保持统一的 message、tool definition、tool call、finish reason 和 usage contract，并补充固定响应契约测试。能力不足、限流、超时、配额和无效响应映射为稳定 `ErrorCode`。API Key 加密保存且只返回掩码，不进入日志、审计或前端调试数据。

## 任务规划

任务规划遵循“权限与输入校验 → 骨架生成 → 结构化解析与领域校验 → 细节生成 → 保存不可变版本 → 用户编辑 → 人工确认 → 单事务创建正式数据”。模型生成与确认事务分离；确认携带 `Idempotency-Key`，事务内重新读取最新版本并复验全部规则。

模型输出必须限制 JSON 大小、Schema、key 唯一性、日期、任务数、依赖、优先级、工时以及成员/文档/里程碑的项目归属。解析失败只允许有限修复，不能通过吞异常或删除必要字段迎合输出。

## Agent 运行时与事件

运行时持久化 session、message、run、plan、step、event 和 approval。提交请求时只接受当前项目路由可证明的页面上下文；服务端重新校验选择的任务、里程碑、文档和规划 ID。Skill 来自固定 `AgentSkillRegistry`，客户端不能注入任意 Prompt 或工具集合。

```text
提交目标和 Skill
  → 校验项目成员与页面上下文
  → 保存 Run、计划和初始事件
  → Worker 领取租约
  → ModelTurnGateway 原生工具轮次
  → 工具 Schema/参数/风险校验
  → 只读执行或创建写审批
  → 保存 step 与严格递增 event
  → SSE 重放、续传并展示终态
```

`agent_run.last_event_sequence` 与 `(run_id, sequence_no)` 唯一约束保证事件顺序；SSE 使用 sequence 作为 id，支持 `Last-Event-ID`/`afterSequence`。取消请求和 Worker 完成存在并发时，以行锁、CAS 和终态重读保护，不能让旧 Worker 覆盖 `CANCELED`。

## 工具、审批与记忆

内置只读工具仍校验项目成员和项目范围。写工具只能生成审批提案：保存 approval、参数哈希、nonce、过期时间与资源版本；批准时锁定 Approval 和 Run，确认仍处于 `WAITING_FOR_APPROVAL`，再重新校验当前角色、项目状态、资源版本和幂等键，并调用正式 Application Service。Agent 工具禁止直接访问 Mapper。

项目记忆只有 `DECISION / PREFERENCE / CONSTRAINT / LESSON` 四类。读取按项目隔离；创建、更新和停用需要项目管理员，更新使用 version。模型不能静默写记忆，`create_memory` 同样走审批。

## MCP 边界

系统管理员管理 MCP Connection，项目 OWNER 管理 Binding。实际可见工具是以下交集：

```text
发现结果 readOnlyHint=true
∩ 系统管理员确认只读白名单
∩ 项目 OWNER 白名单
```

当前只支持受控 `STREAMABLE_HTTP` 实际调用；STDIO 明确拒绝。Endpoint 必须是 allowlist 内公网 HTTPS 主机，并在每次请求前做 DNS/SSRF 校验；不跟随重定向。外部 test/discover/call 不进入数据库长事务，响应流受 `timeoutMs` 和 `maxResultBytes` 限制。每次执行前重新读取连接、绑定、白名单和已确认 Schema Hash；输出作为不可信内容清洗。详细部署与验收见 `docs/agent/MCP_DEPLOYMENT_AND_BROWSER_TESTING.md`。

## 预算、循环和定时运行

每次运行限制步骤、模型轮次、工具调用、子运行、输入/输出 Token、预计费用和总运行时间。`AgentLoopGuard` 拒绝无进展的重复工具调用；`AgentConvergencePolicy` 同时基于持久化步骤约束模型轮次、单轮工具数和总工具数。已有成功工具证据且只剩可完成最终回答的预算时，Runtime 保留工具历史但向模型传入空工具列表，强制收敛为最终回答；该轮继续请求工具或返回空内容时稳定失败，不再重新排队。

Prompt 必须服从用户要求的查询深度。用户只要求根目录、当前层或列表时不得继续读取子目录或文件正文；已有结果足够回答时不得为套用 Skill 的通用输出模板扩大查询目标。

知识问答与 AI 规划的每用户每小时默认限额均为 60，分别通过 `KNOWLEDGE_RATE_LIMIT_PER_USER_HOUR` 和 `PLANNING_GENERATION_LIMIT_PER_USER_HOUR` 配置。Agent 本身不设每小时运行次数限制，避免把测试吞吐与单次运行收敛混在一起。

定时任务保存稳定 `skillCode`，触发记录以 `(schedule_id, scheduled_for)` 幂等。执行前重新校验创建者仍是项目成员；失去资格时停用定时任务并写审计，网络调用不包在停用事务内。

## 测试边界

- 三类 provider 的原生工具请求/响应、finish reason、usage、timeout 与无效响应。
- 页面上下文伪造、跨项目 ID、Skill/工具白名单和参数 Schema。
- 事件严格递增、重放、SSE CRLF/跨字节分块续传与单连接管理。
- 取消与审批、Worker/CAS、重试和预算终态竞态。
- 写工具 nonce、过期、重复批准、权限变化、资源版本和业务状态复验。
- MCP Endpoint/DNS/重定向、Schema 变化、双层白名单、只读标记、结果清洗与大小限制。
- 项目记忆隔离、version 冲突和定时任务成员资格变化。
- V1–V30 全量迁移、OpenAPI、前端类型、错误映射和生产装配。

## 发给 Claude 的提示

```text
读取 AGENTS.md、docs/feature-matrix.md、docs/development/guides/planning-and-agent.md、
docs/development/api-contract-checklist.md 和任务相关 Agent 文档。
先说明模型边界、可信上下文、工具风险、人工审批、事件/终态、幂等和事务；
模型不得直接写业务表，MCP 只允许通过三重只读交集和执行前重校验的工具。
```

MiMo 只可补充已完全限定的 DTO/type、枚举映射、局部文案或单个测试；不得设计模型协议、Prompt、状态机、权限、审批、MCP 安全、事务或迁移。
