# P0 Agent and Knowledge Contract Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the browser-visible Agent task proposal, knowledge Q&A, feedback, and terminal error flows before changing provider architecture or navigation.

**Architecture:** Keep the existing persisted Agent runtime and knowledge services. Add explicit tool definitions with JSON Schema, stop no-progress tool loops, make every run terminal state visible to the frontend, and align feedback ownership and SSE terminal events across OpenAPI, backend DTOs, and frontend types.

**Tech Stack:** Java 21, Spring Boot, Jackson, Jakarta Validation, PostgreSQL, Vue 3, TypeScript, Vitest, Element Plus.

## Global Constraints

- The fixed acceptance prompt is `创建一个任务：完成 Agent 浏览器验收，优先级高，截止日期为 2026-08-05。`
- Agent writes remain approval-only; no model decision writes task data directly.
- Every project query remains scoped by `project_id` and authenticated membership.
- Failed, canceled, budget-exceeded, and waiting-for-approval runs must be visible in the conversation UI.
- Feedback controls render only for persisted assistant messages.
- Tests are written and observed failing before production changes.
- Existing uncommitted user changes are preserved.

---

### Task 1: Publish strict Agent tool definitions

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/tool/AgentToolDefinition.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/tool/AgentTool.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/AgentToolRegistry.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/CreateTaskApprovalAgentTool.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentPromptFactory.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentWorker.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentPromptFactoryTest.java`
- Create test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/CreateTaskApprovalAgentToolTest.java`

**Interfaces:**
- Produces: `AgentToolDefinition(String name, String description, JsonNode inputSchema, boolean writesBusinessData)`
- Produces: `AgentTool.definition()`
- Produces: `AgentToolRegistry.definitionsFor(AgentToolContext)`
- Consumes: definitions in `AgentPromptFactory.systemPrompt(Collection<AgentToolDefinition>, String)`

- [ ] **Step 1: Write the failing prompt Schema test**

Add a test that supplies a `create_task_after_approval` definition and asserts the prompt contains the exact field names, enum values, date format, and `additionalProperties:false`:

```java
@Test
void includesCreateTaskInputSchema() throws Exception {
    JsonNode schema = new ObjectMapper().readTree("""
        {"type":"object","additionalProperties":false,
         "required":["title","priority","dueDate"],
         "properties":{
           "title":{"type":"string","maxLength":160},
           "priority":{"type":"string","enum":["LOW","MEDIUM","HIGH","URGENT"]},
           "dueDate":{"type":"string","format":"date"}}}
        """);
    AgentToolDefinition tool = new AgentToolDefinition(
            "create_task_after_approval", "创建待审批任务", schema, true);

    String prompt = prompts.systemPrompt(List.of(tool), "SUPERVISOR");

    assertThat(prompt)
            .contains("create_task_after_approval")
            .contains("\"priority\":{\"type\":\"string\",\"enum\":[\"LOW\",\"MEDIUM\",\"HIGH\",\"URGENT\"]}")
            .contains("\"dueDate\":{\"type\":\"string\",\"format\":\"date\"}")
            .contains("\"additionalProperties\":false");
}
```

- [ ] **Step 2: Run the focused test and observe the expected compile failure**

Run:

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=AgentPromptFactoryTest test
```

Expected: FAIL because `AgentToolDefinition` and the collection-based `systemPrompt` overload do not exist.

- [ ] **Step 3: Add the tool definition interface**

Implement:

```java
public record AgentToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        boolean writesBusinessData) {
    public AgentToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("工具名不能为空");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("工具说明不能为空");
        if (inputSchema == null || !inputSchema.isObject()) throw new IllegalArgumentException("工具 Schema 必须是对象");
        inputSchema = inputSchema.deepCopy();
    }
}
```

Add `AgentToolDefinition definition();` to `AgentTool`. Make the registry return definitions filtered by the existing write/depth policy.

- [ ] **Step 4: Define the complete create-task Schema**

`CreateTaskApprovalAgentTool.definition()` must describe all `CreateTaskRequest` fields, require `title`, reject unknown properties, use Java enum names, and declare `startDate` and `dueDate` as ISO `date`. Nullable UUID and optional fields must use JSON Schema unions rather than empty strings.

- [ ] **Step 5: Serialize definitions into the Agent system prompt**

Change the prompt to include a `<TOOLS_JSON>` array containing exact definitions. Keep the three decision envelopes and explicitly state that `arguments` must validate against the selected tool Schema.

- [ ] **Step 6: Pass definitions from the Worker**

Replace `tools.namesFor(context)` with `tools.definitionsFor(context)` and keep policy filtering in one registry method.

- [ ] **Step 7: Write and run the create-task normalization test**

Test:

```java
@Test
void normalizesTheBrowserAcceptanceTask() throws Exception {
    JsonNode arguments = json.readTree("""
        {"title":"完成 Agent 浏览器验收","description":null,
         "milestoneId":null,"assigneeId":null,"status":"TODO",
         "priority":"HIGH","estimateHours":null,"startDate":null,
         "dueDate":"2026-08-05"}
        """);

    JsonNode normalized = tool.normalize(context, arguments);

    assertThat(normalized.path("title").asText()).isEqualTo("完成 Agent 浏览器验收");
    assertThat(normalized.path("priority").asText()).isEqualTo("HIGH");
    assertThat(normalized.path("dueDate").asText()).isEqualTo("2026-08-05");
}
```

Run:

```powershell
.\mvnw.cmd -Dtest=AgentPromptFactoryTest,CreateTaskApprovalAgentToolTest test
```

Expected: PASS.

- [ ] **Step 8: Commit the strict tool contract**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent
git commit -m "fix(agent): publish strict tool schemas"
```

### Task 2: Stop no-progress loops and expose terminal run outcomes

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentLoopGuard.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentWorker.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentWorkerTest.java`
- Create test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/domain/AgentLoopGuardTest.java`
- Create: `ai-collab-frontend/src/modules/agent/agent-run-state.ts`
- Create test: `ai-collab-frontend/src/modules/agent/agent-run-state.test.ts`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Modify: `ai-collab-frontend/src/modules/agent/agent-api.ts`
- Modify: `ai-collab-frontend/src/modules/agent/types.ts`
- Create test: `ai-collab-frontend/src/modules/agent/AgentView.test.ts`

**Interfaces:**
- Produces: `AgentLoopGuard.hasNoProgress(List<AgentStepView>, AgentDecision.CallTool): boolean`
- Produces: `AgentRunDetail` frontend type with `run`, `steps`
- Produces: `agentRunPresentation(run): { terminal, severity, title, canRetry }`

- [ ] **Step 1: Write the failing loop-guard test**

```java
@Test
void rejectsThirdEquivalentToolCallWithoutProgress() {
    List<AgentStepView> steps = List.of(
            completed(1, "check_project_progress", args, output),
            completed(2, "check_project_progress", args, output));

    assertThat(guard.hasNoProgress(
            steps,
            new AgentDecision.CallTool("check_project_progress", args, "再次检查")))
            .isTrue();
}
```

Also assert that a changed argument, changed output, or intervening different tool is allowed.

- [ ] **Step 2: Run the guard test and observe failure**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=AgentLoopGuardTest test
```

Expected: FAIL because `AgentLoopGuard` does not exist.

- [ ] **Step 3: Implement canonical call fingerprints**

Use `toolName + canonical arguments + canonical output` from the two most recent completed calls. Reject only when both previous calls are equivalent and produced equivalent output. Record `AGENT_NO_PROGRESS` through `recordDecisionFailure` and finish the run as `FAILED`.

- [ ] **Step 4: Write the failing frontend state test**

```ts
it('presents failed runs instead of silently reloading messages', () => {
  expect(agentRunPresentation({
    status: 'FAILED',
    errorCode: 'AGENT_TOOL_EXECUTION_FAILED',
  } as AgentRun)).toEqual({
    terminal: true,
    severity: 'error',
    title: 'Agent 无法完成这次操作：工具参数无效',
    canRetry: true,
  })
})
```

- [ ] **Step 5: Run the frontend test and observe the missing module**

```powershell
Set-Location ..\ai-collab-frontend
pnpm test -- src/modules/agent/agent-run-state.test.ts
```

Expected: FAIL because `agent-run-state.ts` does not exist.

- [ ] **Step 6: Implement terminal presentation and retry API**

Map `WAITING_FOR_APPROVAL`, `SUCCEEDED`, `FAILED`, `CANCELED`, and `BUDGET_EXCEEDED` to Chinese user states. Add `agentApi.retry(projectId, runId)` using `POST /runs/{runId}/retry`.

- [ ] **Step 7: Render run state in the conversation**

Keep `activeRun` in `AgentView.vue`. During polling, render a compact state card below messages. On a terminal failure, retain the card and show retry when allowed. On `WAITING_FOR_APPROVAL`, switch or link to the approval card. Do not set page-global `busy` for the full run duration; only disable duplicate sends for the active session.

- [ ] **Step 8: Add the component regression test**

Mount `AgentView` with mocked APIs, submit the fixed browser prompt, resolve the polled run as failed, and assert:

```ts
expect(wrapper.text()).toContain('工具参数无效')
expect(wrapper.get('[data-test="agent-retry"]').exists()).toBe(true)
```

Add a second case resolving to `WAITING_FOR_APPROVAL` and assert the title, `高`, and `2026-08-05` are visible in the approval content.

- [ ] **Step 9: Run focused backend and frontend tests**

```powershell
Set-Location ..\ai-collab-backend
.\mvnw.cmd -Dtest=AgentLoopGuardTest,AgentWorkerTest test
Set-Location ..\ai-collab-frontend
pnpm test -- src/modules/agent/agent-run-state.test.ts src/modules/agent/AgentView.test.ts
```

Expected: PASS.

- [ ] **Step 10: Commit run visibility**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent ai-collab-frontend/src/modules/agent
git commit -m "fix(agent): surface failures and stop loops"
```

### Task 3: Align knowledge feedback ownership and UI behavior

**Files:**
- Create test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge/application/KnowledgeFeedbackServiceTest.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/application/service/KnowledgeFeedbackService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/api/controller/KnowledgeController.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/api/dto/KnowledgeFeedbackRequest.java`
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.vue`
- Modify: `ai-collab-frontend/src/modules/knowledge/knowledge-api.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/types.ts`
- Create test: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.test.ts`

**Interfaces:**
- Produces: `KnowledgeFeedbackRequest(@NotNull Boolean helpful)`
- Preserves: `POST|GET|DELETE /projects/{projectId}/knowledge/sessions/messages/{messageId}/feedback`
- Produces: frontend `isFeedbackEligible(message)` for persisted assistant answers only.

- [ ] **Step 1: Write backend ownership tests**

Cover:

```java
@Test void rejectsFeedbackForUserMessage() { /* expect KNOWLEDGE_SESSION_NOT_FOUND */ }
@Test void rejectsFeedbackForAnotherUsersAssistantMessage() { /* scoped lookup */ }
@Test void submitsAndRemovesFeedbackForOwnAssistantMessage() { /* counts return to zero */ }
```

Use the existing PostgreSQL integration-test convention when mapper SQL must be exercised; otherwise mock `ProjectAccessGuard`, `KnowledgeFeedbackMapper`, and `JdbcTemplate`.

- [ ] **Step 2: Run the focused test and observe the current contract failure**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=KnowledgeFeedbackServiceTest test
```

Expected: at least one assertion fails against the current map-based controller or ownership response.

- [ ] **Step 3: Replace the map request with a validated DTO**

Implement:

```java
public record KnowledgeFeedbackRequest(
        @NotNull(message = "helpful 字段不能为空") Boolean helpful) {
}
```

Use `@Valid @RequestBody KnowledgeFeedbackRequest` in the controller. Keep the existing project/session-owner/assistant-role restriction in the service and return one stable not-found error for ineligible messages.

- [ ] **Step 4: Write the frontend eligibility and rollback tests**

Test that a `USER` message has no feedback controls, an unsaved streaming assistant placeholder has no controls, and a persisted `ASSISTANT` message has both buttons. Reject the mocked submit call and assert the selected state returns to its prior value while the normalized error is shown.

- [ ] **Step 5: Run the frontend test and observe failure**

```powershell
Set-Location ..\ai-collab-frontend
pnpm test -- src/modules/knowledge/KnowledgeView.test.ts
```

Expected: FAIL because current rendering exposes controls using the wrong predicate or does not roll state back.

- [ ] **Step 6: Implement assistant-only feedback**

Define the predicate using `message.role === 'ASSISTANT' && Boolean(message.id) && !message.pending && !message.error`. Disable both buttons during mutation, optimistically update only after a successful response, and restore the previous server value after an error.

- [ ] **Step 7: Run focused tests**

```powershell
Set-Location ..\ai-collab-backend
.\mvnw.cmd -Dtest=KnowledgeFeedbackServiceTest test
Set-Location ..\ai-collab-frontend
pnpm test -- src/modules/knowledge/KnowledgeView.test.ts
```

Expected: PASS.

- [ ] **Step 8: Commit feedback alignment**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge ai-collab-frontend/src/modules/knowledge
git commit -m "fix(knowledge): align answer feedback contract"
```

### Task 4: Guarantee SSE terminal events

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/application/service/KnowledgeStreamQuestionService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/application/view/KnowledgeStreamEvent.java`
- Create test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge/application/KnowledgeStreamQuestionServiceTest.java`
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.vue`
- Create: `ai-collab-frontend/src/modules/knowledge/knowledge-stream.ts`
- Create test: `ai-collab-frontend/src/modules/knowledge/knowledge-stream.test.ts`

**Interfaces:**
- Produces SSE events: `start`, `token`, `citations`, `done`, `error`, `canceled`
- Produces: `consumeKnowledgeStream(response, handlers, signal): Promise<void>`

- [ ] **Step 1: Write service tests for retrieval and callback failures**

Assert that a thrown `DocumentSearchService.search` error sends one `error` event and completes. Assert that a chat callback error also sends one `error` event and that `done` is never emitted afterward.

- [ ] **Step 2: Run the test and capture the failing terminal behavior**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=KnowledgeStreamQuestionServiceTest test
```

Expected: FAIL on missing `start`/`canceled` semantics or duplicate completion protection.

- [ ] **Step 3: Add a single terminal guard**

Use an `AtomicBoolean terminalSent`. All completion paths call one helper:

```java
private void finish(SseEmitter emitter, AtomicBoolean terminalSent, KnowledgeStreamEvent event) {
    if (terminalSent.compareAndSet(false, true)) {
        sendSse(emitter, event);
        emitter.complete();
    }
}
```

Send `start` before asynchronous retrieval. Treat disconnect/abort as cancellation without logging a server error.

- [ ] **Step 4: Extract and test the frontend stream consumer**

Feed a synthetic SSE response containing `start`, two tokens, citations, and done; assert ordered callbacks. Feed an `error` event and assert the Promise rejects with the stable code. Abort the signal and assert it exits as canceled rather than generic failure.

- [ ] **Step 5: Run backend and frontend stream tests**

```powershell
Set-Location ..\ai-collab-backend
.\mvnw.cmd -Dtest=KnowledgeStreamQuestionServiceTest test
Set-Location ..\ai-collab-frontend
pnpm test -- src/modules/knowledge/knowledge-stream.test.ts
```

Expected: PASS.

- [ ] **Step 6: Commit terminal stream handling**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge ai-collab-frontend/src/modules/knowledge
git commit -m "fix(knowledge): guarantee stream termination"
```

### Task 5: Synchronize contracts and verify P0

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/feature-matrix.md`
- Modify: `scripts/validate_openapi.py`
- Create: `scripts/verify-agent-browser-case.ps1`

**Interfaces:**
- Consumes: authenticated local project ID and configured model.
- Produces: a repeatable script that submits the fixed Agent prompt, polls the run, and verifies either a pending approval with exact normalized fields or an explicit terminal error.

- [ ] **Step 1: Add contract assertions before documentation edits**

Extend OpenAPI validation to require:

```text
POST /projects/{projectId}/agent/sessions/{sessionId}/messages -> 202
POST /projects/{projectId}/agent/runs/{runId}/retry
KnowledgeFeedbackRequest.helpful -> required boolean
AgentRun.errorCode -> nullable string
```

- [ ] **Step 2: Run validation and observe mismatch**

```powershell
Set-Location ..
.\scripts\validate-openapi.ps1
```

Expected: FAIL until the OpenAPI document matches implemented DTOs and endpoints.

- [ ] **Step 3: Update OpenAPI and factual documentation**

Document exact request/response and SSE events. Mark Agent task proposal and feedback as complete only after focused tests pass. Record multi-model administration as pending P1.

- [ ] **Step 4: Add the browser-case verifier**

The script must:

1. Log in using values read from `.env` without printing credentials or tokens.
2. Accept `-ProjectId`.
3. Create or reuse an Agent session.
4. Submit the fixed Chinese prompt.
5. Poll at one-second intervals for at most 90 seconds.
6. On `WAITING_FOR_APPROVAL`, assert `title`, `priority`, and `dueDate`.
7. On any other terminal state, print status and stable error code, then exit nonzero.

- [ ] **Step 5: Run the P0 verification suite**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build
Set-Location ..
.\scripts\validate-openapi.ps1
git diff --check
```

Expected: all commands exit 0.

- [ ] **Step 6: Run the local Agent browser-case verifier**

```powershell
.\scripts\verify-agent-browser-case.ps1 -ProjectId 09b0f65e-9e7a-46eb-aa9b-852dba2757e6
```

Expected: `WAITING_FOR_APPROVAL` with title `完成 Agent 浏览器验收`, priority `HIGH`, and due date `2026-08-05`.

- [ ] **Step 7: Commit P0 contract evidence**

```powershell
git add docs/api/openapi.yaml docs/feature-matrix.md scripts/validate_openapi.py scripts/verify-agent-browser-case.ps1
git commit -m "docs: record agent and knowledge recovery"
```
