# AI 规划与 Agent 实现指南

## 功能边界

本指南覆盖模型配置与路由、AI 任务规划、结构化输出、版本、人工确认、Agent 会话/运行/步骤、只读工具和审批写工具。

AI 不拥有业务权限。模型输出不能直接写项目表，所有写入必须重新经过服务端权限、状态、参数、并发和事务校验。

## 当前入口

模型：

- `infrastructure/ai/ChatModelGateway.java`
- `infrastructure/ai/model/RoutingChatModelGateway.java`
- `ModelConfigurationService.java`
- `ModelProviderAdapter.java`

规划：

- `planning/application/TaskPlanGenerationOrchestrator.java`
- `TaskPlanModelClient.java`
- `TaskPlanCommandService.java`
- `TaskPlanConfirmationService.java`
- `TaskPlanQueryService.java`
- `planning/domain/`

Agent：

- `agent/api/controller/AgentSessionController.java`
- `agent/application/AgentRunService.java`
- `AgentWorker.java`
- `AgentApprovalService.java`
- `agent/domain/policy/AgentToolPolicy.java`
- `agent/infrastructure/tool/AgentToolRegistry.java`

前端：

- `modules/planning/PlanningView.vue`
- `modules/agent/AgentView.vue`
- `modules/admin/AdminView.vue`

## 模型 Gateway

业务代码依赖统一 `ChatModelGateway`，不能直接调用供应商 SDK。用途通过 `ModelPurpose` 路由到管理员配置：

- 知识问答。
- 任务规划。
- 协作 Agent。

Provider adapter 负责协议差异；业务服务负责业务语义。API Key 加密保存，响应只返回掩码，不进入日志、审计或前端调试数据。

增加供应商时：

1. 实现 `ModelProviderAdapter`。
2. 明确能力矩阵：JSON、tools、streaming。
3. 处理供应商错误到统一 `ErrorCode`。
4. 增加契约测试，使用固定受控响应。
5. 不在业务模块加入 provider `if/else`。

## 任务规划

流程：

```text
用户目标和约束
  → 权限/配额/日期/文档校验
  → 生成规划骨架
  → 结构化解析和领域校验
  → 生成细节
  → 保存不可变版本和问题
  → 用户编辑
  → 人工确认
  → 一个数据库事务创建正式里程碑、任务和依赖
```

模型输出是候选，不是事实。必须执行：

- JSON 大小和结构限制。
- key 唯一性。
- 日期范围。
- 任务数、依赖、优先级和工时。
- 成员、文档和里程碑项目归属。
- 依赖环检测。

解析失败使用受控修复次数；不能无限重试或通过删除字段“修好”。

## 版本与确认

- 每次用户编辑生成新的不可变版本。
- 历史版本只读。
- 确认携带 `Idempotency-Key`。
- 同一 key + 同一请求返回已有结果。
- 同一 key + 不同请求返回 `IDEMPOTENCY_KEY_REUSED`。
- 确认事务中重新读取最新版本并复验全部业务规则。
- 任一里程碑、任务或依赖失败时整体回滚。

模型生成和确认事务分离，不能在数据库事务中等待模型。

## Agent 运行时

持久化边界：

- session：用户对话上下文。
- run：一次执行。
- step：模型、工具、审批和结果步骤。
- approval：待人工批准的写操作。

Agent 工具分两类：

### 只读工具

可以直接运行，但仍校验项目成员和项目范围，例如项目概览、任务列表、风险、知识搜索。

### 写工具

模型只能生成提案。工具返回待审批记录，用户明确批准后由应用服务执行：

```text
模型工具调用
  → 参数结构化校验
  → AgentToolPolicy 判断必须审批
  → 保存 approval + nonce + 过期时间
  → 用户查看中文摘要
  → 批准
  → 重新校验当前权限和业务状态
  → 调用正式 Application Service
  → 保存结果和审计
```

禁止 Agent 工具直接访问 Mapper。审批不是缓存授权：执行时必须重新校验。

## 预算与循环保护

每次运行限制：

- 最大步骤数。
- 最大工具调用数。
- 最大模型 token/费用。
- 总运行时间。
- 相同工具和相同参数重复调用。

`AgentLoopGuard` 检测无进展重复。达到限制时以可解释状态停止，不能悄悄继续后台运行。

## 工具定义

工具 schema 使用稳定、最小参数：

```java
public interface AgentTool {
    AgentToolDefinition definition();
    AgentToolResult execute(AgentToolContext context, Map<String, Object> arguments);
}
```

定义必须说明：

- 工具用途和非用途。
- required 字段、类型、长度和 enum。
- 是否只读、是否需要审批。
- 返回的结构化字段。

不要把内部 Entity、SQL、异常堆栈或敏感配置返回给模型。

## 测试

模型层：

- provider 请求/响应转换。
- timeout、rate limit、quota、malformed JSON。
- 能力不足时拒绝路由。

规划：

- 无效输出、截断、修复上限。
- 跨项目文档/成员。
- 版本冲突、幂等复用、事务回滚。
- 正式任务与来源 key 对齐。

Agent：

- 非成员与跨项目工具调用。
- 只读工具结果项目隔离。
- 写工具必须审批。
- nonce、过期、重复批准。
- 批准时权限已变化。
- 循环和预算停止。
- 重启后的运行恢复。

## 发给 Claude 的提示

```text
读取 AGENTS.md、docs/development/guides/planning-and-agent.md 和
docs/development/api-contract-checklist.md。
先说明模型边界、结构化验证、人工确认、幂等和事务；
模型不得直接写业务表。新增工具必须分类为只读或审批写，并给出越权测试。
```

## 可交给 MiMo 的任务

可以：在既定 Tool Definition 中补一个已完全定义的描述字段，或增加一个固定错误响应映射测试。

禁止：让 MiMo设计 Prompt、模型路由、规划 schema、幂等确认、Agent 状态机、工具权限、审批或预算。
