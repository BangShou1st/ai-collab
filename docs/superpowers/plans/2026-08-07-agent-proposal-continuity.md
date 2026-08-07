# Agent Proposal Continuity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让所有 Agent 审批写工具支持跨轮次提案修订、最新需求覆盖和提案落库后的确定性即时回答，消除虚假“已提交”和有交付证据却失败的 Run。

**Architecture:** 在审批表上增加会话、提案族、稳定主体和修订号，并用不可变修订表保留历史。`AgentApprovalService` 统一创建或修订提案，Runtime 在事务成功后直接保存确定性回答并结束 Run；可信上下文从数据库加载最新提案，不依赖截断聊天文本。

**Tech Stack:** Java 21、Spring Boot、Spring JDBC、PostgreSQL/Flyway、JUnit 5、Mockito、Testcontainers、Vue 3、TypeScript、Vitest、Element Plus、OpenAPI。

## Global Constraints

- 只在 `main` 工作，不创建分支或 worktree，不执行 push 或 PR。
- 现有工作树包含大量未提交成果；每个提交只暂存当前任务列出的文件，提交前检查 `git diff --cached`。
- 当前已有未提交 V35、V36；本功能只能新增 V37，不修改任何已有迁移。
- Controller 不访问 Repository；项目子资源查询必须包含 `projectId`。
- 模型不能批准提案；批准和拒绝仍要求项目管理员并重校验 nonce、幂等键、资源版本和业务状态。
- 当前消息明确字段覆盖旧值，显式 `null` 清空，缺失字段继承。
- 不使用 `any`、非空断言、空 catch、弱化断言或硬编码业务假数据。
- 每个生产改动先有按预期失败的测试；Claude 必须亲自读取 MiMo diff 和完整测试输出。
- 规格依据：`docs/superpowers/specs/2026-08-07-agent-proposal-continuity-design.md`。

---

## File Structure

### 新建

- `ai-collab-backend/src/main/resources/db/migration/V37__add_agent_proposal_continuity.sql`：提案元数据、修订历史、索引和事件约束。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentProposalFamily.java`：五类现有写操作的稳定枚举。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentProposalContext.java`：提供给 Runtime 的可信提案摘要。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentProposalRevisionView.java`：不可变修订记录 View。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentProposalArgumentMerger.java`：按字段存在性覆盖 JSON。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentProposalOutcome.java`：创建/修订结果。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentProposalSummaryRenderer.java`：根据真实审批数据生成中文回答。
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentEvidenceFallbackRenderer.java`：收敛边界的证据保底回答。
- 对应的单元和集成测试文件。

### 修改

- `ApprovalWriteAgentTool.java` 及五个现有写工具：声明提案族，并接受保留字段 `approvalId`。
- `AgentApprovalRepository.java`、`AgentApprovalService.java`、`AgentApprovalView.java`：创建/修订、匹配、历史、独立审批生命周期。
- `AgentContextAssembler.java`、`AgentExecutionContext.java`、`AgentRuntimeCoordinator.java`：可信提案上下文和确定性交付。
- `AgentPromptFactory.java`：禁止无证据交付声明，说明修订时传递 `approvalId`。
- `AgentEventType.java`、事件迁移和前端事件类型：增加 `APPROVAL_UPDATED`。
- `AgentRunService.java` 和 Run detail：按 Run 返回真实 pending approval，兼容已成功 Run。
- `docs/api/openapi.yaml`、前端 `types.ts`、`agent-api.ts`、`agent-run-store.ts`、`AgentView.vue`：契约和刷新展示闭环。
- `docs/database.md`、`docs/feature-matrix.md`、`docs/development/guides/planning-and-agent.md`：权威事实。

---

### Task 1: 锁定参数覆盖、提案状态和 Runtime 失败行为

**Files:**
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/domain/policy/AgentProposalArgumentMergerTest.java`
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentProposalContinuityIntegrationTest.java`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeBehaviorTest.java`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentApprovalServiceTest.java`

**Interfaces:**
- Consumes: 当前 `AgentApprovalService.propose(...)`、`AgentRuntimeCoordinator.advance(...)`。
- Produces: 后续任务必须满足的失败测试；此任务不写生产代码。

- [ ] **Step 1: MiMo 编写纯合并器失败测试**

按附录 MiMo-1 委派，只创建 `AgentProposalArgumentMergerTest.java`。测试必须调用期望接口：

```java
JsonNode merge(JsonNode current, JsonNode patch)
```

覆盖：缺失字段继承、普通字段覆盖、显式 null 清空、嵌套 `changes` 逐字段合并、输入不为 object 时拒绝。

- [ ] **Step 2: 验证合并器测试按预期失败**

Run:

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd -Dtest=AgentProposalArgumentMergerTest test
```

Expected: FAIL，仅因 `AgentProposalArgumentMerger` 尚不存在。

- [ ] **Step 3: Claude 编写数据库生命周期失败测试**

在 `AgentProposalContinuityIntegrationTest` 使用真实 PostgreSQL fixture，至少写出：

```java
@Test void revisesTheOnlyCompatiblePendingProposalInTheSameSession()
@Test void rejectedProposalIsNotReused()
@Test void approvedCreateProducesAResourceUpdateProposal()
@Test void concurrentRevisionUsesVersionCompareAndSet()
@Test void proposalIdCannotCrossProjectSessionOrRequester()
```

断言审批 ID、`revision`、完整参数、修订历史和 Run 状态，不只验证 mock 调用次数。

- [ ] **Step 4: Claude 编写 Runtime 与审批解耦失败测试**

在现有测试中增加：

```java
@Test void persistedProposalReturnsDeterministicAnswerAndSucceeds()
@Test void persistedProposalRevisionEmitsApprovalUpdatedAndSucceeds()
@Test void invalidFinalTurnFallsBackWhenSuccessfulEvidenceExists()
@Test void invalidFinalTurnStillFailsWithoutEvidence()
@Test void approvalCanResolveAfterItsRunSucceeded()
```

- [ ] **Step 5: 验证所有新测试失败原因正确**

Run:

```powershell
.\mvnw.cmd -Dtest=AgentProposalArgumentMergerTest,AgentProposalContinuityIntegrationTest,AgentRuntimeBehaviorTest,AgentApprovalServiceTest test
```

Expected: FAIL，原因必须是新接口/新行为缺失，而不是 fixture、SQL 或 Mockito 配置错误。

- [ ] **Step 6: 仅提交测试**

```powershell
git add ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/domain/policy/AgentProposalArgumentMergerTest.java `
  ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentProposalContinuityIntegrationTest.java `
  ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeBehaviorTest.java `
  ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentApprovalServiceTest.java
git diff --cached
git commit -m "test: define continuous agent proposal behavior"
```

---

### Task 2: 建立 V37 数据模型和只读 View

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V37__add_agent_proposal_continuity.sql`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentProposalFamily.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentProposalContext.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentProposalRevisionView.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentApprovalView.java`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `AgentProposalFamily { TASK_CREATE, TASK_UPDATE, MILESTONE_CREATE, MILESTONE_UPDATE, MEMORY_CREATE }`。
- Produces: Approval View 新字段 `sessionId`、`proposalFamily`、`subjectKey`、`revision`、`updatedAt`。

- [ ] **Step 1: Claude 完成并先运行迁移失败断言**

迁移测试必须断言 `agent_approval` 新列、`agent_approval_revision` 表、唯一约束和 `APPROVAL_UPDATED` 事件值。先运行并确认 FAIL。

- [ ] **Step 2: Claude 编写 V37**

V37 必须：

```sql
ALTER TABLE agent_approval
  ADD COLUMN session_id uuid,
  ADD COLUMN proposal_family varchar(40),
  ADD COLUMN subject_key uuid,
  ADD COLUMN revision integer NOT NULL DEFAULT 1,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
```

随后从 `agent_run` 回填 `session_id`，按五个 `tool_name` 回填 `proposal_family`，以 `COALESCE(resource_id,id)` 回填 `subject_key`，再设置 NOT NULL 和外键。创建 `agent_approval_revision`，至少包含 `project_id`、`approval_id`、`source_run_id`、`revision`、`before_arguments_json`、`after_arguments_json`、`diff_json`、`created_at`，并建立 `(approval_id, revision)` 唯一约束。

同一 V37 重建 `ck_agent_run_event_type`，保留 V36 的全部事件并增加 `APPROVAL_UPDATED`。禁止修改 V35/V36。

- [ ] **Step 3: MiMo 补充只读 model/view**

按附录 MiMo-2 分成两个任务，每次只允许 1–2 个文件。Claude 先给出 record 的完整字段顺序，MiMo 不得自行改 Repository。

- [ ] **Step 4: Claude 更新 RowMapper 并验证迁移**

Run:

```powershell
.\mvnw.cmd -Dtest=AgentMigrationIntegrationTest,AgentProposalContinuityIntegrationTest test
```

Expected: 迁移测试 PASS；生命周期测试继续因服务未实现而 FAIL。

- [ ] **Step 5: 提交数据骨架**

只暂存本任务文件，检查 cached diff 后提交：

```powershell
git commit -m "feat: add agent proposal revision model"
```

---

### Task 3: 实现通用字段覆盖和五个工具提案契约

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentProposalArgumentMerger.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/tool/ApprovalWriteAgentTool.java`
- Modify: five files under `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/*ApprovalAgentTool.java`
- Modify: matching tool contract tests.

**Interfaces:**
- Produces: `AgentProposalFamily proposalFamily()`。
- Produces: `JsonNode mergeArguments(JsonNode current, JsonNode patch)`，默认委托纯合并器。
- Consumes: 保留元字段 `approvalId`，传给 DTO normalize 前必须移除。

- [ ] **Step 1: MiMo 实现纯合并器**

按附录 MiMo-3 委派 `AgentProposalArgumentMerger.java`，只实现 object 深度覆盖：patch 中存在的 scalar/array/null 替换 current；两边同字段都是 object 时递归；patch 缺失字段继承；任一根节点非 object 抛 `IllegalArgumentException`。禁止处理审批匹配或数据库。

- [ ] **Step 2: 运行合并器测试至 GREEN**

```powershell
.\mvnw.cmd -Dtest=AgentProposalArgumentMergerTest test
```

- [ ] **Step 3: Claude 定义工具接口和保留元字段协议**

写工具输入统一允许：

```json
"approvalId": {"type":["string","null"],"format":"uuid"}
```

修订调用使用 `approvalId + 本轮明确变化字段`；初次创建仍满足原业务必填字段。Runtime 在 Schema 校验前识别合法修订引用，合并后再按工具完整业务 Schema 校验，不能让 `approvalId` 进入业务 DTO。

- [ ] **Step 4: MiMo 分批补五个工具的 family 和 Schema**

按附录 MiMo-4，每次最多两个工具文件。Claude 必须逐文件核对工具族映射、原 Schema 字段和 normalize 行为。

- [ ] **Step 5: 运行工具契约测试**

```powershell
.\mvnw.cmd -Dtest=AgentSkillToolMappingTest,AgentAnalysisToolsTest,AgentPromptFactoryTest test
```

- [ ] **Step 6: 提交通用工具契约**

```powershell
git commit -m "feat: define revision-aware agent write tools"
```

---

### Task 4: 实现审批创建、修订、匹配和独立解析事务

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentProposalOutcome.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentApprovalRepository.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentApprovalService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java`
- Test: `AgentProposalContinuityIntegrationTest.java`、`AgentApprovalServiceTest.java`。

**Interfaces:**
- Produces:

```java
record AgentProposalOutcome(
    AgentApprovalView approval,
    Operation operation,
    JsonNode previousArguments,
    JsonNode currentArguments,
    JsonNode diff) {
  enum Operation { CREATED, UPDATED }
}
```

- Produces:

```java
AgentProposalOutcome proposeOrRevise(
    AgentRunView run,
    ModelTurnResult turn,
    ModelToolCall call,
    AgentToolContext context,
    ApprovalWriteAgentTool tool)
```

- [ ] **Step 1: Claude 实现项目范围候选查询**

Repository 查询必须始终包含 `project_id`，并提供：按显式 approval ID 锁定；按 `project + session + requester + family + PENDING` 查兼容候选；读取会话提案上下文；读取修订历史。

- [ ] **Step 2: Claude 实现匹配规则**

显式 `approvalId` 先校验项目、会话、请求人、状态和 family。无 ID 时只有唯一兼容候选才自动修订；零候选创建；多候选返回稳定的歧义结果，让 Runtime 询问目标，不能随机选。

- [ ] **Step 3: Claude 实现锁和 CAS 修订**

修订事务内 `FOR UPDATE`，合并后重新 normalize/diff/hash，更新 `arguments_json`、`arguments_hash`、`diff_json`、`revision`、`version`、`expires_at`、`updated_at`，并插入不可变 revision。CAS 失败抛 `VERSION_CONFLICT`，不能重用陈旧参数。

- [ ] **Step 4: Claude 解耦审批与 Run**

`requireExecutableRun` 接受 `SUCCEEDED` 和兼容旧数据的 `WAITING_FOR_APPROVAL`，拒绝 CANCELED/FAILED。批准或拒绝后不 requeue；旧 `WAITING_FOR_APPROVAL` Run 直接收敛到 `SUCCEEDED`。批准仍通过正式 Application Service 执行并保存 result。

- [ ] **Step 5: 运行生命周期测试至 GREEN**

```powershell
.\mvnw.cmd -Dtest=AgentProposalContinuityIntegrationTest,AgentApprovalServiceTest,AgentRepositoryIntegrationTest test
```

- [ ] **Step 6: 提交事务闭环**

```powershell
git commit -m "feat: reconcile agent proposal revisions"
```

---

### Task 5: 注入可信提案上下文并确定性结束 Run

**Files:**
- Modify: `AgentContextAssembler.java`
- Modify: `AgentExecutionContext.java`
- Modify: `AgentRuntimeCoordinator.java`
- Modify: `AgentPromptFactory.java`
- Create: `AgentProposalSummaryRenderer.java`
- Create: `AgentEvidenceFallbackRenderer.java`
- Modify: `AgentEventType.java`
- Test: `AgentRuntimeBehaviorTest.java`、`AgentPromptFactoryTest.java`。

**Interfaces:**
- Produces: `List<AgentProposalContext> proposals` 作为 `AgentExecutionContext` 的不可空字段。
- Produces: `String render(AgentProposalOutcome outcome)`。
- Produces: `Optional<String> render(List<AgentStepView> steps)`，只使用已消毒且 `TOOL_SUCCESS` 的结果。

- [ ] **Step 1: MiMo 添加 context record 字段和构造调用编译修复**

只在 Claude 列出的 1–2 个文件中执行；若构造点超过允许范围，MiMo 停止，由 Claude 完成。

- [ ] **Step 2: Claude 加载可信提案上下文**

Assembler 通过 Approval Repository/Application query service 获取当前 session 的未决与最近已解决提案。Prompt 使用单独 `<TRUSTED_PROPOSALS>` system message；历史消息仍在不可信区。

- [ ] **Step 3: Claude 修改写工具执行路径**

`executeCalls` 遇到写工具时调用 `proposeOrRevise`。事务返回后依次：记录工具成功、发 `APPROVAL_REQUESTED` 或 `APPROVAL_UPDATED`、渲染并 `recordFinal`、发 `RUN_SUCCEEDED`、返回 `SUCCEEDED`。不得再返回 `WAITING_FOR_APPROVAL`。

- [ ] **Step 4: MiMo 实现确定性摘要 renderer**

按附录 MiMo-5，仅改 renderer 和其测试。摘要从 Outcome 的真实参数/diff 读取，区分 CREATED/UPDATED；不得输出 nonce；字段未知时省略，不编造标题、负责人或日期。

- [ ] **Step 5: Claude 实现证据保底和虚假声明防护**

收敛阶段空内容或非法 final 时：有成功提案证据用提案摘要；有成功只读工具证据用限制长度的证据摘要；无证据仍 `AGENT_INVALID_RESPONSE`。Prompt 明确禁止没有工具证据时声称提交/创建/更新。

- [ ] **Step 6: 运行 Runtime 测试至 GREEN**

```powershell
.\mvnw.cmd -Dtest=AgentRuntimeBehaviorTest,AgentRuntimeCoordinatorTest,AgentPromptFactoryTest,CrossTickToolCallTest test
```

- [ ] **Step 7: 提交 Runtime 闭环**

```powershell
git commit -m "fix: complete agent runs after proposal delivery"
```

---

### Task 6: 同步 API、前端类型和审批展示

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `ai-collab-frontend/src/modules/agent/types.ts`
- Modify: `ai-collab-frontend/src/modules/agent/agent-api.ts`
- Modify: `ai-collab-frontend/src/modules/agent/agent-run-store.ts`
- Modify: `ai-collab-frontend/src/modules/agent/agent-run-store.test.ts`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.test.ts`

**Interfaces:**
- Approval 新增：`sessionId`、`proposalFamily`、`subjectKey`、`revision`、`updatedAt`。
- Event 新增：`APPROVAL_UPDATED`。
- Run 成功后 `pendingApprovalId` 仍可非空。

- [ ] **Step 1: Claude 先修改 OpenAPI 契约并运行校验**

OpenAPI 明确成功 Run 可以携带 pending approval，审批解析不依赖 Run 的等待状态。运行 `scripts/validate-openapi.ps1`，旧类型测试应失败。

- [ ] **Step 2: MiMo 同步 TypeScript 类型和 store 测试**

按附录 MiMo-6，只允许 `types.ts` 与 `agent-run-store.test.ts`。断言 `APPROVAL_UPDATED` 不把 Run 置成等待状态，后续 `RUN_SUCCEEDED` 正常终结。

- [ ] **Step 3: Claude 更新 API/store 实现**

SSE 收到 `APPROVAL_REQUESTED`/`APPROVAL_UPDATED` 时刷新审批，但继续等待 `RUN_SUCCEEDED`。终态后同时刷新消息和审批。删除新流程对 `WAITING_FOR_APPROVAL` 的依赖，保留旧数据展示兼容。

- [ ] **Step 4: MiMo 添加审批卡局部展示**

按附录 MiMo-7，只允许 `AgentView.vue` 和 `AgentView.test.ts`：显示“修订 #N”和最新 diff；不得改变审批权限、API 调用或状态机。

- [ ] **Step 5: 前端验证**

```powershell
Set-Location ..\ai-collab-frontend
pnpm test -- agent-run-store.test.ts AgentView.test.ts
pnpm typecheck
pnpm build
```

- [ ] **Step 6: 契约闭环提交**

```powershell
git commit -m "feat: show continuous agent proposal delivery"
```

---

### Task 7: 权威文档、全量验证和真实交付验收

**Files:**
- Modify: `docs/database.md`
- Modify: `docs/feature-matrix.md`
- Modify: `docs/development/guides/planning-and-agent.md`
- Modify: `docs/agent/00_CURRENT_STATUS.md`
- Modify: relevant smoke/browser testing documentation.

**Interfaces:**
- Consumes: 所有前述已通过的行为和契约。
- Produces: 可复现验证报告和交付事实。

- [ ] **Step 1: Claude 更新权威文档**

数据库文档记录 V37、revision 表和关系；功能矩阵说明确定性交付与跨轮次修订；Agent 指南更新审批独立生命周期、上下文优先级和收敛规则。

- [ ] **Step 2: 后端全量验证**

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests
```

记录每条命令退出码、测试数量、耗时和完整失败原因。

- [ ] **Step 3: 前端全量验证**

```powershell
Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build
```

- [ ] **Step 4: 契约和工作树验证**

```powershell
Set-Location ..
.\scripts\validate-openapi.ps1
git diff --check
git status --short
```

- [ ] **Step 5: 真实数据库/API/浏览器验收**

用真实模型或固定 provider fixture 执行：任务创建后改负责人和日期；显式清空日期；两个候选时选择其一；拒绝后重建；批准后更新真实任务；里程碑修订；记忆创建；一次会议生成多个独立任务；刷新后仍显示最新审批；事件序号超过 29；最终空响应但已有证据时仍返回结果。

- [ ] **Step 6: Claude 最终逐项审查 MiMo 输出**

按 `docs/development/claude-mimo-protocol.md` 检查所有允许文件 diff、越界修改、`any`、空 catch、弱化断言、调试日志、敏感数据和未运行验证。MiMo 不能自行宣布交付完成。

- [ ] **Step 7: 最终提交**

只暂存本任务文档和必要修复，检查 cached diff：

```powershell
git commit -m "docs: document continuous agent proposal delivery"
```

---

## MiMo Delegation Prompts

### MiMo-1：字段覆盖测试

```text
任务：为 AgentProposalArgumentMerger 编写失败测试，不写生产实现。

允许修改：
- ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/domain/policy/AgentProposalArgumentMergerTest.java

禁止修改：
- 除允许列表外的所有文件
- 数据库迁移、权限、事务、OpenAPI、Git

必须保持的接口：
- new AgentProposalArgumentMerger().merge(JsonNode current, JsonNode patch)

实现要求：
1. 分别测试缺失字段继承、字符串覆盖、显式 null 清空、嵌套 changes 合并。
2. 根节点非 object 时断言 IllegalArgumentException。
3. 每个测试只验证一个行为，不使用 mock。

验证：
- .\mvnw.cmd -Dtest=AgentProposalArgumentMergerTest test
- 期望：当前因生产类不存在而失败。

输出：仅给出修改说明和 diff；如接口冲突，停止报告。
```

### MiMo-2A：提案枚举和上下文 record

```text
任务：创建提案族枚举和可信上下文 record。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentProposalFamily.java
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentProposalContext.java

禁止修改：
- Repository、Service、迁移、OpenAPI、Git 和其他文件

实现要求：
1. AgentProposalFamily 仅包含 TASK_CREATE、TASK_UPDATE、MILESTONE_CREATE、MILESTONE_UPDATE、MEMORY_CREATE。
2. AgentProposalContext 字段顺序固定为 UUID approvalId、AgentProposalFamily proposalFamily、UUID subjectKey、String status、int revision、JsonNode arguments、JsonNode result、JsonNode latestDiff。
3. 不增加业务方法、默认值或推断字段。
4. import 和 nullability 沿用同目录现有 record 风格；如调用方不一致，停止报告。

验证：
- .\mvnw.cmd -DskipTests compile
- 期望：若调用方尚未同步，可报告精确编译错误，不越界修复。
```

### MiMo-2B：修订 View 和审批 View 字段

```text
任务：创建修订 View，并按既定字段扩展 AgentApprovalView。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentProposalRevisionView.java
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentApprovalView.java

禁止修改：
- Repository、Service、迁移、OpenAPI、Git 和其他文件

必须保持的接口：
- AgentApprovalView 现有字段顺序和类型不变；在 createdAt 后、nonce 前依次增加 UUID sessionId、AgentProposalFamily proposalFamily、UUID subjectKey、int revision、OffsetDateTime updatedAt。
- AgentProposalRevisionView 字段依次为 UUID id、UUID projectId、UUID approvalId、UUID sourceRunId、int revision、JsonNode beforeArguments、JsonNode afterArguments、JsonNode diff、OffsetDateTime createdAt。

实现要求：
1. 只定义 record，不增加业务方法或默认值。
2. 如现有调用方无法编译，报告精确位置，不越界修改。

验证：
- .\mvnw.cmd -DskipTests compile
- 期望：允许因 Repository RowMapper 尚未同步而失败；不得自行修改 Repository。
```

### MiMo-3：纯 JSON 覆盖函数

```text
任务：实现无状态 JSON 字段覆盖函数。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentProposalArgumentMerger.java

禁止修改：
- 除允许文件外所有文件；尤其是审批、Repository、Runtime、迁移、Git

必须保持的接口：
- public final class AgentProposalArgumentMerger
- public JsonNode merge(JsonNode current, JsonNode patch)

实现要求：
1. current 和 patch 必须是 object，否则抛 IllegalArgumentException。
2. patch 缺失字段继承 current。
3. patch 中 scalar、array、null 覆盖 current。
4. 同名值双方为 object 时递归合并。
5. 返回深拷贝，不修改输入节点。

验证：
- .\mvnw.cmd -Dtest=AgentProposalArgumentMergerTest test
- 期望：全部通过。
```

### MiMo-4A：任务写工具机械适配

```text
任务：按 Claude 已定义的 ApprovalWriteAgentTool 接口适配任务创建和更新工具。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/CreateTaskApprovalAgentTool.java
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/UpdateTaskApprovalAgentTool.java

禁止修改：
- 其他工具、接口、Service、Repository、迁移、OpenAPI、Git

必须保持：
- 现有 name、execute、revalidate 和业务 DTO 不变。
- proposalFamily 返回 Claude 指定枚举常量。
- CreateTask 返回 TASK_CREATE；UpdateTask 返回 TASK_UPDATE。
- input schema 只增加 Claude 给出的 approvalId 定义和修订 anyOf，不删除原字段限制。

验证：
- .\mvnw.cmd -Dtest=AgentSkillToolMappingTest,AgentAnalysisToolsTest test
- 期望：通过；如 Schema 与修订协议冲突，停止报告。
```

### MiMo-4B：里程碑写工具机械适配

```text
任务：按已定义接口适配里程碑创建和更新工具。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/CreateMilestoneApprovalAgentTool.java
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/UpdateMilestoneApprovalAgentTool.java

禁止修改：
- 其他工具、接口、Service、Repository、迁移、OpenAPI、Git

必须保持：
- 现有 name、execute、revalidate 和业务 DTO 不变。
- CreateMilestone 返回 MILESTONE_CREATE；UpdateMilestone 返回 MILESTONE_UPDATE。
- input schema 只增加 approvalId 和 Claude 已写入参考工具的同构修订 anyOf。

验证：
- .\mvnw.cmd -Dtest=AgentSkillToolMappingTest,AgentAnalysisToolsTest test
- 期望：全部通过；如参考工具与当前 Schema 不同，停止报告。
```

### MiMo-4C：项目记忆写工具机械适配

```text
任务：按已定义接口适配项目记忆创建工具。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/CreateMemoryApprovalAgentTool.java

禁止修改：
- 其他工具、接口、Service、Repository、迁移、OpenAPI、Git

必须保持：
- 现有 name、execute、revalidate 和业务 DTO 不变。
- proposalFamily 返回 MEMORY_CREATE。
- input schema 只增加 approvalId 和 Claude 已写入任务工具的同构修订 anyOf。

验证：
- .\mvnw.cmd -Dtest=AgentSkillToolMappingTest,AgentAnalysisToolsTest test
- 期望：全部通过；项目记忆没有更新工具，不得自行新增。
```

### MiMo-5：确定性摘要

```text
任务：实现基于 AgentProposalOutcome 的确定性中文摘要及单测。

允许修改：
- ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentProposalSummaryRenderer.java
- ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/runtime/AgentProposalSummaryRendererTest.java

禁止修改：
- Runtime、审批 Service/Repository、迁移、OpenAPI、Git

必须保持的接口：
- public String render(AgentProposalOutcome outcome)

实现要求：
1. CREATED 使用“已生成…提案”，UPDATED 使用“已更新…提案”。
2. 只展示 arguments/diff 中真实存在的标题、负责人、日期、优先级和修订号。
3. 不输出 nonce、hash、Prompt、完整模型输出。
4. 未知资源族使用“项目写入提案”，不编造名称。

验证：
- .\mvnw.cmd -Dtest=AgentProposalSummaryRendererTest test
- 期望：全部通过。
```

### MiMo-6：前端类型和事件测试

```text
任务：同步已确定的审批字段和 APPROVAL_UPDATED 事件测试。

允许修改：
- ai-collab-frontend/src/modules/agent/types.ts
- ai-collab-frontend/src/modules/agent/agent-run-store.test.ts

禁止修改：
- store 实现、Vue 页面、API、OpenAPI、Git

实现要求：
1. AgentApproval 增加 sessionId、proposalFamily、subjectKey、revision、updatedAt，类型与 OpenAPI 完全一致。
2. AgentRunEvent type 增加 APPROVAL_UPDATED。
3. 测试证明 APPROVAL_UPDATED 不把 Run 设为 WAITING，RUN_SUCCEEDED 随后正常结束。
4. 不使用 any 或非空断言。

验证：
- pnpm test -- agent-run-store.test.ts
- 期望：实现 store 前新增测试按预期失败。
```

### MiMo-7：审批卡局部展示

```text
任务：在现有 Agent 审批卡展示修订号和最新差异，并补组件测试。

允许修改：
- ai-collab-frontend/src/modules/agent/AgentView.vue
- ai-collab-frontend/src/modules/agent/AgentView.test.ts

禁止修改：
- API、types、store、路由、OpenAPI、Git

实现要求：
1. 显示“修订 #<revision>”。
2. 使用现有 approval.diff 展示当前差异，不创建第二张历史审批卡。
3. 不改变批准/拒绝权限、API 参数或 Run 状态判断。
4. 不使用 any、非空断言、硬编码审批数据。

验证：
- pnpm test -- AgentView.test.ts
- pnpm typecheck
- 期望：全部通过。
```

## Plan Self-Review

- Spec coverage: 数据模型、上下文优先级、字段覆盖、五个写工具、生命周期、确定性交付、收敛、API、前端和验收均有对应任务。
- Type consistency: `AgentProposalOutcome`、`AgentProposalFamily`、`AgentProposalContext` 和新增 Approval 字段在生产与测试任务中使用同一命名。
- Scope: 没有新增业务资源或取消审批；项目记忆缺少更新工具时明确返回能力边界。
- MiMo safety: 所有 MiMo 任务限制为 1–2 个文件，不允许迁移、状态机、事务、OpenAPI 或 Git。
