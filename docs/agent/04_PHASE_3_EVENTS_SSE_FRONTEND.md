# 04 Phase 3：事件存储、SSE 与 Agent 工作台

## 1. 阶段目标

让用户看到 Agent 正在做什么，并保证断线可重放、运行可取消、审批嵌入对话。前端不展示模型私密推理，只展示可审计的计划和工具事实。

## 2. 数据库迁移 V27

实际开始前先确认最新迁移仍为 V26；若已有 V27，则顺延版本，禁止覆盖。

```sql
-- V27__add_agent_context_plan_and_events.sql
ALTER TABLE agent_run
    ADD COLUMN skill_code varchar(80),
    ADD COLUMN page_context_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN plan_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN context_version integer NOT NULL DEFAULT 1,
    ADD COLUMN cancel_requested_at timestamptz;

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_context_version CHECK (context_version > 0);

CREATE TABLE agent_run_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id uuid NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    sequence_no bigint NOT NULL,
    type varchar(48) NOT NULL,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_run_event_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT ck_agent_run_event_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_run_event_type CHECK (type IN (
        'RUN_CREATED','CONTEXT_CAPTURED','SKILL_SELECTED',
        'PLAN_CREATED','PLAN_UPDATED','MODEL_STARTED','MODEL_COMPLETED',
        'TOOL_CALL_PROPOSED','TOOL_CALL_STARTED','TOOL_CALL_COMPLETED','TOOL_CALL_FAILED',
        'APPROVAL_REQUESTED','APPROVAL_APPROVED','APPROVAL_REJECTED',
        'APPROVAL_EXPIRED','RESULT_VERIFIED','RUN_RETRY_SCHEDULED',
        'RUN_CANCELED','RUN_SUCCEEDED','RUN_FAILED','RUN_BUDGET_EXCEEDED'
    ))
);

CREATE INDEX idx_agent_run_event_replay
    ON agent_run_event(project_id, run_id, sequence_no);
```

不要修改 V22/V23。

## 3. 事件模型

```java
public enum AgentEventType { ... }

public record AgentRunEvent(
        UUID id,
        UUID projectId,
        UUID runId,
        long sequence,
        AgentEventType type,
        JsonNode payload,
        OffsetDateTime createdAt) {
}
```

事件 payload 必须是展示级摘要，不保存完整模型 Prompt 和完整工具结果。

### 3.1 Payload 合同

`PLAN_CREATED`：

```json
{
  "planVersion":1,
  "objective":"检查项目健康度",
  "steps":[
    {"id":"snapshot","title":"读取项目概况","status":"PENDING"}
  ]
}
```

`TOOL_CALL_STARTED`：

```json
{
  "callId":"call_123",
  "toolName":"task.search",
  "displayName":"查询任务",
  "purpose":"查找逾期和阻塞任务"
}
```

`TOOL_CALL_COMPLETED`：

```json
{
  "callId":"call_123",
  "toolName":"task.search",
  "summary":"找到 3 个逾期任务",
  "durationMs":126,
  "citationCount":0,
  "truncated":false
}
```

`TOOL_CALL_FAILED` 不包含堆栈：

```json
{
  "callId":"call_123",
  "toolName":"mcp.github.list_pull_requests",
  "errorCode":"MCP_TIMEOUT",
  "message":"GitHub 工具响应超时",
  "retryable":true
}
```

## 4. 原子序号

事件序号必须在数据库中原子生成。不要用 Java `AtomicLong`，因为重启和多实例会冲突。

推荐在同一事务中：

```sql
SELECT COALESCE(MAX(sequence_no), 0) + 1
FROM agent_run_event
WHERE run_id = #{runId}
FOR UPDATE;
```

更稳妥的方式是在 `agent_run` 增加 `last_event_sequence bigint default 0`，使用：

```sql
UPDATE agent_run
SET last_event_sequence = last_event_sequence + 1
WHERE id = #{runId} AND project_id = #{projectId}
RETURNING last_event_sequence;
```

若采用后者，V27 同时增加该字段和非负约束。优先采用后者。

## 5. Event Service

```java
@Service
public class AgentEventService {
    private final AgentRepository runs;
    private final AgentEventRepository events;
    private final AgentEventPublisher publisher;

    @Transactional
    public AgentRunEvent append(
            UUID projectId,
            UUID runId,
            AgentEventType type,
            JsonNode safePayload) {
        long sequence = runs.nextEventSequence(projectId, runId);
        AgentRunEvent event = events.insert(projectId, runId, sequence, type, safePayload);
        publisher.publishAfterCommit(event);
        return event;
    }
}
```

必须 after-commit 发布，避免客户端看到尚未提交或最终回滚的事件。

## 6. SSE Endpoint

### 6.1 API

```text
GET /api/v1/projects/{projectId}/agent/runs/{runId}/events?afterSequence=0
Accept: text/event-stream
Last-Event-ID: 123   # 可选
```

先验证：

- 当前用户是项目成员；
- run 属于 project；
- `afterSequence >= 0`；
- 不泄露其他项目 run 是否存在。

### 6.2 Spring MVC 参考实现

```java
@GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter events(
        @PathVariable UUID projectId,
        @PathVariable UUID runId,
        @RequestParam(defaultValue = "0") long afterSequence,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
        @AuthenticationPrincipal Jwt jwt) {
    long cursor = cursor(afterSequence, lastEventId);
    return eventStream.subscribe(projectId, runId, userId(jwt), cursor);
}
```

### 6.3 订阅服务

单体应用可使用：

- DB 作为事实来源；
- 内存 emitter 只负责实时推送；
- 连接时先 replay DB 中 `sequence > cursor` 的事件；
- 再注册实时 emitter；
- 注册前后需要二次补读，避免 replay 与注册之间丢事件；
- 每 15～30 秒发送 comment/heartbeat；
- emitter completion/timeout/error 时移除。

伪代码：

```java
public SseEmitter subscribe(..., long cursor) {
    access.requireMember(projectId, userId);
    runs.requireRun(projectId, runId);
    SseEmitter emitter = new SseEmitter(0L);
    replay(projectId, runId, cursor, emitter);
    subscriptions.add(runId, emitter);
    replay(projectId, runId, lastSent(emitter), emitter);
    return emitter;
}
```

事件格式：

```java
emitter.send(SseEmitter.event()
        .id(Long.toString(event.sequence()))
        .name(event.type().name())
        .data(event));
```

## 7. 取消语义

当前 `cancel()` 直接把状态改为 CANCELED，可能无法阻止已开始的模型/网络调用。最终做法：

```java
@Transactional
public void requestCancel(...) {
    access.requireMember(projectId, userId);
    repository.markCancelRequested(projectId, runId, now);
    events.append(... RUN_CANCELED_REQUESTED 若新增此事件，或只记录审计);
}
```

Coordinator 在以下位置检查：

1. 调模型前；
2. 调模型后、使用结果前；
3. 每个工具前；
4. 每个工具后、持久化前；
5. requeue 前。

确认取消后才进入 CANCELED 并发出 `RUN_CANCELED`。已经在外部执行但无法中断的只读结果必须丢弃；写操作本来就不会自动执行。

若文档事件枚举不增加 `CANCEL_REQUESTED`，前端在 POST 成功后本地显示“正在停止”，直到收到 `RUN_CANCELED`。

## 8. 前端类型

```ts
export type AgentEventType =
  | 'RUN_CREATED' | 'CONTEXT_CAPTURED' | 'SKILL_SELECTED'
  | 'PLAN_CREATED' | 'PLAN_UPDATED'
  | 'MODEL_STARTED' | 'MODEL_COMPLETED'
  | 'TOOL_CALL_PROPOSED' | 'TOOL_CALL_STARTED'
  | 'TOOL_CALL_COMPLETED' | 'TOOL_CALL_FAILED'
  | 'APPROVAL_REQUESTED' | 'APPROVAL_APPROVED'
  | 'APPROVAL_REJECTED' | 'APPROVAL_EXPIRED'
  | 'RESULT_VERIFIED' | 'RUN_RETRY_SCHEDULED'
  | 'RUN_CANCELED' | 'RUN_SUCCEEDED'
  | 'RUN_FAILED' | 'RUN_BUDGET_EXCEEDED'

export interface AgentRunEvent<T = Record<string, unknown>> {
  id: string
  projectId: string
  runId: string
  sequence: number
  type: AgentEventType
  payload: T
  createdAt: string
}

export interface AgentPageContext {
  route: string
  selectedTaskId?: string | null
  selectedMilestoneId?: string | null
  selectedDocumentId?: string | null
  selectedPlanId?: string | null
  filters: Record<string, unknown>
}
```

## 9. 带 Authorization 的 SSE

浏览器原生 `EventSource` 不能自由设置 Authorization header。当前系统使用 JWT HTTP 客户端，因此不要直接使用裸 EventSource，除非项目已改为同源 HttpOnly Cookie。

推荐新增 `fetch-event-stream.ts`，使用 `fetch` + `ReadableStream`：

```ts
export async function streamAgentEvents(
  url: string,
  token: string,
  afterSequence: number,
  signal: AbortSignal,
  onEvent: (event: AgentRunEvent) => void,
): Promise<void> {
  const response = await fetch(`${url}?afterSequence=${afterSequence}`, {
    headers: {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
      'Last-Event-ID': String(afterSequence),
    },
    signal,
  })
  if (!response.ok || !response.body) {
    throw new Error(`SSE ${response.status}`)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''
    for (const frame of frames) {
      const data = frame.split('\n')
        .filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart())
        .join('\n')
      if (data) onEvent(JSON.parse(data) as AgentRunEvent)
    }
  }
}
```

生产实现必须：

- 处理 `\r\n`；
- 支持多行 data；
- 忽略 comment；
- 保存最大 sequence；
- 指数退避重连；
- 组件卸载/项目切换时 Abort；
- 同一 sequence 幂等应用。

## 10. 前端状态 Store

```ts
interface AgentTimelineState {
  run: AgentRun | null
  events: AgentRunEvent[]
  lastSequence: number
  plan: AgentPlanView | null
  tools: Record<string, AgentToolStepView>
  connected: boolean
}

function applyEvent(state: AgentTimelineState, event: AgentRunEvent) {
  if (event.sequence <= state.lastSequence) return
  if (event.runId !== state.run?.id) return
  state.events.push(event)
  state.lastSequence = event.sequence
  // switch event.type 更新 plan/tool/run 状态
}
```

不使用事件到达顺序猜状态，只使用 sequence。

## 11. 页面上下文采集

建议每个页面通过统一 composable：

```ts
export function useAgentPageContext(): AgentPageContext {
  const route = useRoute()
  return {
    route: String(route.name ?? route.path),
    selectedTaskId: asUuid(route.params.taskId),
    selectedMilestoneId: asUuid(route.params.milestoneId),
    selectedDocumentId: asUuid(route.params.documentId),
    selectedPlanId: asUuid(route.params.planId),
    filters: sanitizeVisibleFilters(...),
  }
}
```

禁止把 JWT、邮箱、文档正文、完整对象放入 context。

跨页面“交给 Agent”入口通过 Router state 或 Pinia 暂存一次性 context，进入 Agent 页面后显示可移除标签。

## 12. Agent 工作台

推荐拆分：

```text
modules/agent/
  AgentView.vue
  components/
    AgentSessionList.vue
    AgentConversation.vue
    AgentComposer.vue
    AgentContextChips.vue
    AgentSkillPicker.vue
    AgentRunTimeline.vue
    AgentApprovalCard.vue
    AgentRunActions.vue
  agent-api.ts
  agent-event-stream.ts
  agent-run-store.ts
  types.ts
```

桌面端：左会话、中对话、右上下文/计划/时间线。移动端用 Tab 或 Drawer，不横向溢出。

审批卡嵌入对应 Assistant 消息附近，同时保留审批 Tab 作为集中处理入口。

## 13. 前端 API 变化

```ts
async submit(
  projectId: string,
  sessionId: string,
  body: {
    content: string
    skillCode?: string | null
    pageContext?: AgentPageContext | null
  },
): Promise<ApiResult<AgentRun>>

async cancel(projectId: string, runId: string): Promise<void>

async events(...) // 由专用 stream helper 处理，不走 axios JSON parser
```

## 14. 测试

### 后端

- event sequence 并发唯一；
- afterSequence replay；
- 连接注册边界不丢事件；
- 跨项目订阅 403/统一不存在响应；
- cancel requested 后不执行下一个工具；
- after-commit 才推送；
- terminal event 后连接可结束；
- 心跳不进入业务事件表。

### 前端

- 同 sequence 不重复应用；
- 乱序事件按 sequence 处理或忽略；
- 项目切换 abort；
- 断线后从 lastSequence 重连；
- 取消后不显示成功；
- WAITING_FOR_APPROVAL 显示暂停而不是完成；
- 审批执行后资源刷新；
- 窄屏无横向滚动。

## 15. 阶段验收

真实启动后完成：

1. 发起项目健康检查；
2. 首个 SSE 事件 P95 目标 < 1 秒；
3. 页面依次看到 context、plan、tool start/result、final；
4. 刷新页面后从 DB 重放完整时间线；
5. 运行中断网再恢复，不重复事件；
6. 中途取消，后端不继续新工具调用且无假成功消息；
7. 写请求出现内嵌审批卡，批准后回读结果；
8. 后端日志无 emitter 泄漏和新增 ERROR。
