# 06 API、数据库、后端与前端合同总表

## 1. 目的

本文件用于最终对齐。Claude 修改后端 DTO、OpenAPI、前端类型、数据库 View 时必须同步检查这里，避免“后端能跑但前端字段不一致”。

## 2. 核心 API

### 2.1 提交消息

```http
POST /api/v1/projects/{projectId}/agent/sessions/{sessionId}/messages
Content-Type: application/json
```

```json
{
  "content":"把这个任务延期两天",
  "skillCode":null,
  "pageContext":{
    "route":"TASK_DETAIL",
    "selectedTaskId":"uuid",
    "selectedMilestoneId":null,
    "selectedDocumentId":null,
    "selectedPlanId":null,
    "filters":{}
  }
}
```

返回 `202`：

```json
{
  "success":true,
  "data":{
    "id":"run-uuid",
    "sessionId":"session-uuid",
    "projectId":"project-uuid",
    "skillCode":"ITERATION_PLANNING",
    "status":"QUEUED",
    "stepsUsed":0,
    "toolCallsUsed":0,
    "errorCode":null
  }
}
```

### 2.2 Run 详情

```http
GET /api/v1/projects/{projectId}/agent/runs/{runId}
```

至少返回：

```json
{
  "run": {"id":"...","status":"RUNNING","skillCode":"PROJECT_HEALTH"},
  "plan": {"version":1,"steps":[]},
  "steps": [],
  "lastEventSequence": 12,
  "pendingApprovalId": null
}
```

### 2.3 Events

```http
GET /api/v1/projects/{projectId}/agent/runs/{runId}/events?afterSequence=12
Accept: text/event-stream
Last-Event-ID: 12
```

每个 SSE frame：

```text
id: 13
event: TOOL_CALL_STARTED
data: {"id":"...","runId":"...","sequence":13,"type":"TOOL_CALL_STARTED","payload":{},"createdAt":"..."}
```

### 2.4 Cancel / Retry

```text
POST /api/v1/projects/{projectId}/agent/runs/{runId}/cancel -> 202 or 204
POST /api/v1/projects/{projectId}/agent/runs/{runId}/retry  -> 200
```

Cancel 表示请求取消，不保证正在进行的第三方 HTTP 立即中断；最终状态以事件为准。

### 2.5 Skills

```http
GET /api/v1/projects/{projectId}/agent/skills
```

```json
[
  {
    "code":"PROJECT_HEALTH",
    "displayName":"检查项目健康度",
    "description":"...",
    "recommendedRoutes":["DASHBOARD","TASK_BOARD"],
    "inputSchema":{}
  }
]
```

### 2.6 Approvals

保留现有路径和 `Idempotency-Key`。响应增加展示字段时，旧字段不能静默删除。

审批响应建议：

```json
{
  "id":"...",
  "runId":"...",
  "toolName":"task.update",
  "displayName":"修改任务",
  "target":{"type":"TASK","id":"...","title":"完成登录联调"},
  "diff":{
    "dueDate":{"before":"2026-08-03","after":"2026-08-05"}
  },
  "reason":"接口依赖延期",
  "status":"PENDING",
  "nonce":"仅 PENDING 返回",
  "expiresAt":"...",
  "resourceVersion":3
}
```

## 3. Error Code 建议

实际添加前检查 `ErrorCode` 命名规则：

```text
AGENT_TOOL_ARGUMENT_INVALID
AGENT_TOOL_NOT_ALLOWED
AGENT_TOOL_RESULT_TOO_LARGE
AGENT_CONTEXT_RESOURCE_INVALID
AGENT_SKILL_NOT_FOUND
AGENT_RUN_NOT_CANCELABLE
AGENT_EVENT_CURSOR_INVALID
AGENT_MCP_CONNECTION_NOT_FOUND
AGENT_MCP_CONNECTION_DISABLED
AGENT_MCP_TIMEOUT
AGENT_MCP_SCHEMA_CHANGED
AGENT_MCP_ENDPOINT_FORBIDDEN
AGENT_MEMORY_NOT_FOUND
```

不要把 MCP 远端错误文本直接当 API message；用安全中文概要。

## 4. 数据库迁移总表

| 迁移 | 内容 | 依赖 |
|---|---|---|
| V27 | run context、plan、cancel、event、event sequence | Phase 2/3 |
| V28 | MCP connection 和 project binding | Phase 4 |
| V29 | project memory；必要时 schedule.skill_code | Phase 4 |

实际版本冲突时顺延，但文件内容不可拆得无法独立回滚/理解。

## 5. Repository 规则

### 所有方法显式 projectId

正确：

```java
Optional<AgentRunView> findRun(UUID projectId, UUID runId);
List<AgentRunEvent> listEvents(UUID projectId, UUID runId, long after, int limit);
Optional<TaskView> findTask(UUID projectId, UUID taskId);
```

禁止：

```java
findRun(UUID runId)
findTask(UUID taskId)
```

### JSONB 映射

项目已有 PostgreSQL jsonb 与 Jackson 映射经验。若 MyBatis 无可靠 TypeHandler，Persistence Row 使用 `String xxxJson`，SQL 使用 `::text`，Application 层统一解析。不要让 Mapper 直接猜 `JsonNode` TypeHandler。

例：

```sql
SELECT page_context_json::text AS "pageContextJson"
FROM agent_run
WHERE project_id = #{projectId} AND id = #{runId}
```

## 6. 事务边界

- DB claim、状态更新、事件 append 是短事务；
- 模型、MCP、外部 HTTP 不在数据库事务中；
- 工具内部业务写入使用正式 Application Service 事务；
- 审批状态和业务写入的原子性按现有服务设计处理，至少保证幂等和可回读；
- after-commit 才向 SSE emitter 广播。

## 7. 前端路由上下文合同

建议 route code 使用稳定枚举，而不是直接把可变 path 当语义：

```ts
export type AgentRouteCode =
  | 'DASHBOARD'
  | 'TASK_BOARD'
  | 'TASK_DETAIL'
  | 'MILESTONE_LIST'
  | 'MILESTONE_DETAIL'
  | 'DOCUMENT_LIST'
  | 'DOCUMENT_DETAIL'
  | 'PLANNING'
  | 'AUDIT'
  | 'AGENT'
```

后端对未知 route 当 `UNKNOWN`，不报 500。

## 8. 前端组件合同

### `AgentContextChips`

Props：

```ts
interface Props {
  context: AgentPageContext
  taskTitle?: string
  milestoneName?: string
  documentName?: string
}
```

Events：

```text
remove:key
clear
```

### `AgentRunTimeline`

输入：plan + events + run status。不得自己请求 API。

### `AgentApprovalCard`

输入：展示级 approval。Approve/Reject 由父组件执行。不能直接显示 raw arguments JSON 作为主要 UI。

## 9. OpenAPI 同步

每个 Phase 完成前：

- DTO 添加到 `docs/api/openapi.yaml`；
- status enum、event enum 完整；
- SSE content type 与 event schema 有说明；
- 401/403/404/409/422/429/503 响应按项目惯例；
- 管理员和项目 OWNER 权限注明；
- OpenAPI 校验脚本真实执行；
- 反向测试：故意改一个字段，确认校验非零退出，再恢复。

## 10. 审计事件

至少记录：

```text
AGENT_RUN_CREATED
AGENT_RUN_CANCELED
AGENT_APPROVAL_CREATED
AGENT_APPROVAL_APPROVED
AGENT_APPROVAL_REJECTED
AGENT_TOOL_WRITE_EXECUTED
AGENT_MCP_CONNECTION_CREATED/UPDATED/ENABLED/DISABLED
AGENT_MCP_SCHEMA_CHANGED
AGENT_PROJECT_MCP_BOUND/UNBOUND
AGENT_MEMORY_CREATED/UPDATED/DISABLED
```

审计 detail 不保存密钥、nonce、完整 Prompt、工具完整返回。

## 11. 配置建议

```yaml
agent:
  enabled: true
  worker-delay-ms: 1000
  runtime:
    max-steps: 16
    max-model-turns: 8
    max-tool-calls: 12
    max-tool-calls-per-turn: 4
    max-duration: 180s
    internal-tool-timeout: 10s
    mcp-tool-timeout: 15s
    max-tool-result-bytes: 32768
  events:
    heartbeat: 20s
    replay-limit: 500
  mcp:
    enabled: false
    allow-stdio: false
    require-https: true
```

MCP 默认关闭。凭据不写 application.yml。
