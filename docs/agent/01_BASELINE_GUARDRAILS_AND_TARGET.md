# 01 当前基线、目标架构与不可违反规则

## 1. 目的

本文件固定 Agent 2.0 的边界。Claude 不得重新发明架构，不得引入第二套后端，也不得把 MCP 当作绕过内部权限的捷径。

## 2. 当前实现调用链

当前核心链路为：

```text
AgentSessionController.submit
  -> AgentRunService.submit
  -> AgentRepository.createRun
  -> AgentRuntimeJob.tick
  -> AgentRecoveryJob.claim
  -> AgentWorker.process
  -> AgentPromptFactory.systemPrompt/userPrompt
  -> ChatModelGateway.complete(JSON_OBJECT, tools = [])
  -> AgentDecisionParser
  -> AgentToolRegistry / AgentApprovalService
  -> AgentRepository.record*
```

主要问题不是“没有类”，而是运行协议不正确：

```java
completion = model.complete(new ChatCompletionCommand(
        systemPrompt,
        userPrompt,
        ChatCompletionCommand.OutputFormat.JSON_OBJECT,
        ModelPurpose.AGENT,
        null,
        java.util.List.of() // 当前主路径没有把工具交给模型
));
```

模型只能手写：

```json
{"action":"call_tool","tool":"list_tasks","arguments":{}}
```

最终必须改为结构化 Tool Call 与 Tool Result 对话。

## 3. 可保留与必须替换

### 3.1 保留

- `AgentSessionController` 的会话 CRUD 路径；
- `AgentRunService` 的项目成员校验框架；
- `AgentRuntimeJob` + `AgentRecoveryJob` 的数据库领取和租约思路；
- `agent_session`、`agent_message`、`agent_run`、`agent_step`、`agent_approval`、`agent_schedule`；
- `AgentApprovalService` 的 nonce、幂等、版本与正式服务执行原则；
- `ProjectAccessGuard`；
- `RoutingChatModelGateway` 现有知识问答与规划功能；
- 现有内部分析工具中可复用的 Application Service 调用；
- 前端会话、审批、定时任务基础页面。

### 3.2 替换或重构

- `AgentDecisionParser` 不再作为主路径；
- `AgentWorker` 拆成 Runtime Coordinator、Context、Plan、Model Turn、Tool Execution；
- `AgentToolDefinition.openObject(additionalProperties=true)` 不再用于正式工具；
- 固定 `KNOWLEDGE_RESEARCHER / PROGRESS_ANALYST / RISK_REVIEWER` 委派删除；
- 前端运行状态轮询升级为事件重放 + SSE；
- 工具风险不再用布尔值或名称后缀推断。

## 4. 最终架构

```text
┌────────────────────────────────────────────────────────────┐
│ Vue Agent Workspace                                        │
│ chat / page context / plan / timeline / approval / cancel │
└───────────────────────────┬────────────────────────────────┘
                            │ HTTP + SSE
┌───────────────────────────▼────────────────────────────────┐
│ Agent API                                                  │
│ session / run / events / skills / approvals / memories    │
└───────────────────────────┬────────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────────┐
│ AgentRuntimeCoordinator.advance(run)                       │
│ capture -> select skill -> plan -> model -> tools -> verify│
└───────────────┬───────────────────────────────┬────────────┘
                │                               │
┌───────────────▼──────────────┐  ┌────────────▼─────────────┐
│ InternalAgentToolProvider    │  │ McpAgentToolProvider     │
│ Application Services only   │  │ trusted connections only │
└───────────────┬──────────────┘  └────────────┬─────────────┘
                └──────────────┬────────────────┘
                               │
┌──────────────────────────────▼─────────────────────────────┐
│ ToolPolicy + ApprovalGateway + ResultSanitizer + Audit     │
└──────────────────────────────┬─────────────────────────────┘
                               │
┌──────────────────────────────▼─────────────────────────────┐
│ PostgreSQL events / runs / approvals / memory / MCP config │
└────────────────────────────────────────────────────────────┘
```

## 5. 设计原则

### 5.1 单 Agent，不做多 Agent

Skill 只是固定工作流约束，不拥有独立身份、上下文或预算。最终运行中：

```text
maxChildren = 0
DELEGATION_* 不再产生新记录
```

旧字段可先保留兼容，代码不再使用。迁移删除字段不属于本次范围。

### 5.2 事件驱动 UI，不把 LLM token stream 当执行轨迹

Agent 模型轮次第一版使用同步请求。前端实时性来自 Agent 事件：

```text
PLAN_CREATED
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
APPROVAL_REQUESTED
RUN_SUCCEEDED
```

这样可以先正确完成多供应商 Tool Calling，而不用同时解决三家流式 tool delta 拼装。

### 5.3 内部工具不改造成 MCP

内部工具直接调用 Java Application Service：

```text
Agent -> Internal Tool -> TaskApplicationService
```

禁止：

```text
Agent -> 本机 MCP Server -> HTTP -> 自己的 Controller -> Mapper
```

后者增加认证、网络和事务复杂度，没有业务价值。

### 5.4 MCP 是外部能力来源

首个闭环只要求外部只读 GitHub 能力。外部写工具默认全部禁用。

### 5.5 运行推进保持可恢复

保留现有“领取一个 run，推进一次，再进入下一状态”的思路。`advance()` 每次只完成一个确定步骤或一组安全只读工具，不在数据库事务中长时间等待模型或 MCP 网络调用。

## 6. 推荐包结构

```text
agent/
  api/
    controller/
    dto/
  application/
    AgentRunService.java
    runtime/
      AgentRuntimeCoordinator.java
      AgentContextAssembler.java
      AgentPlanService.java
      AgentEventService.java
      AgentCancellationService.java
    skill/
      AgentSkill.java
      AgentSkillRegistry.java
      builtin/
    approval/
  domain/
    model/
    policy/
    tool/
  infrastructure/
    repository/
    tool/
      internal/
      mcp/
```

不要求一次移动所有旧类。每个阶段只移动本阶段真正触及的代码，避免无意义重命名。

## 7. 运行状态

最终状态：

```java
public enum AgentRunStatus {
    CREATED,
    QUEUED,
    PLANNING,
    RUNNING,
    WAITING_FOR_APPROVAL,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED,
    CANCELED,
    BUDGET_EXCEEDED
}
```

状态转换至少满足：

```text
CREATED -> QUEUED
QUEUED -> PLANNING | RUNNING | CANCELED
PLANNING -> RUNNING | FAILED* | CANCELED
RUNNING -> QUEUED | WAITING_FOR_APPROVAL | SUCCEEDED | FAILED* | CANCELED | BUDGET_EXCEEDED
WAITING_FOR_APPROVAL -> QUEUED | SUCCEEDED | FAILED | CANCELED
FAILED_RETRYABLE -> QUEUED | CANCELED
```

`WAITING_FOR_APPROVAL` 对当前运行是暂停态，不应被前端当作普通成功。

## 8. 默认预算

```java
public record AgentRuntimeLimits(
        int maxSteps,
        int maxModelTurns,
        int maxToolCalls,
        int maxToolCallsPerTurn,
        Duration maxRunDuration,
        Duration internalToolTimeout,
        Duration mcpToolTimeout,
        int maxToolResultBytes
) {
    public static AgentRuntimeLimits defaults() {
        return new AgentRuntimeLimits(
                16, 8, 12, 4,
                Duration.ofMinutes(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                32 * 1024);
    }
}
```

这些值可以配置，但不能由模型修改。

## 9. 全局安全不变量

以下规则必须写成 Java 代码和测试，不能只写进 Prompt：

1. `projectId` 固定在 `AgentExecutionContext`；
2. 工具参数中出现 `projectId` 时拒绝或忽略，不能覆盖上下文；
3. 所有资源 ID 使用 `projectId + resourceId` 查询；
4. 每次工具执行前检查当前用户仍是成员；
5. 只读工具也必须执行角色和资源可见性检查；
6. 写工具只生成审批，不直接执行；
7. 批准时重新执行权限、版本、状态、DTO 校验；
8. 写入成功后必须回读；
9. 工具描述、文档、Issue、PR、MCP Resource 都是不可信数据；
10. 错误返回业务错误码，不返回堆栈、SQL 或内部绝对路径。

## 10. 明确不实现

- Python/LangGraph 服务；
- 任意 Shell、SQL、HTTP 工具；
- MCP Marketplace；
- 用户自由输入 STDIO 命令；
- 自动批准；
- 跨项目记忆；
- 无限制自治循环；
- 浏览器自动化；
- AI Collab 对外 MCP Server。
