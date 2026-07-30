# 知识问答与协作 Agent 可用性修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复知识问答流式连接和页面滚动，完善协作 Agent 会话与审批交互，重做评测与规划对比入口，并让文档状态与代码一致。

**Architecture:** 保留现有 Vue 3 + Spring Boot 模块边界，将流解析、审批呈现和评测表单转换提取为可测试纯函数；后端只补充会话命令接口并移除 Agent 评估闭环。所有用户可见枚举统一在前端映射为中文，内部协议值不直接渲染。

**Tech Stack:** Vue 3、TypeScript、Element Plus、Vitest、Spring Boot、Java 21、MyBatis-Plus、Flyway、JUnit 6、PostgreSQL。

## Global Constraints

- 保留 SSE 流式输出、逐段渲染和取消能力。
- 页面外层不出现滚动条，只允许对话消息区域独立滚动。
- Agent 内部工具名、英文状态和原始 JSON 不得直接展示给用户。
- 删除 Agent 评估，保留知识库评测并改为中文表单。
- 不修改已有 Flyway 迁移，只通过新迁移删除废弃表。
- 保留工作区原有未提交改动，不提交与本轮无关的文件。

---

### Task 1: 修复知识问答流结束识别

**Files:**
- Modify: `ai-collab-frontend/src/modules/knowledge/knowledge-stream.test.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/knowledge-api.ts`
- Test: `ai-collab-frontend/src/modules/knowledge/knowledge-stream.test.ts`

**Interfaces:**
- Consumes: `ReadableStream<Uint8Array>` 返回的 SSE 字节流。
- Produces: `askStream(..., callbacks, signal): Promise<void>`，成功路径恰好调用一次 `onDone`，错误路径恰好调用一次 `onError`。

- [ ] **Step 1: 写失败测试**

新增三个真实行为用例：

```ts
it('processes a terminal event left in the final buffer', async () => {
  // 输入以 data JSON 结束且没有最后换行，期望 onDone('m1')
})

it('accepts CRLF and an event type carried by the JSON payload', async () => {
  // 输入 data: {"type":"done","messageId":"m1"}\r\n\r\n，期望完成
})

it('does not report incomplete after a valid terminal event', async () => {
  // 断言 onError 未调用
})
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `pnpm test -- src/modules/knowledge/knowledge-stream.test.ts`

Expected: 新增末尾缓冲和数据内事件类型用例失败，现有用例通过。

- [ ] **Step 3: 实现最小解析修复**

把逐行处理集中为局部 `consumeLine`/`consumeBuffer` 逻辑：

```ts
const resolvedType = eventType || parsed.type || ''
```

流结束后追加 `decoder.decode()`，处理剩余 `buffer`；兼容 `\r\n`；终止事件后禁止重复回调。

- [ ] **Step 4: 运行定向测试**

Run: `pnpm test -- src/modules/knowledge/knowledge-stream.test.ts`

Expected: 流解析测试全部通过。

---

### Task 2: 调整知识问答高度与滚动边界

**Files:**
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.vue`
- Modify: `ai-collab-frontend/src/styles.css`

**Interfaces:**
- Consumes: 应用顶部栏和项目导航占用的视口空间。
- Produces: 桌面端固定工作区、固定高度会话区、仅 `.message-area` 纵向滚动。

- [ ] **Step 1: 添加可检查的布局约束**

在 `KnowledgeView.vue` 使用组件类约束：

```css
.knowledge-page { height: calc(100dvh - var(--app-shell-offset)); overflow: hidden; }
.knowledge-layout { min-height: 0; overflow: hidden; }
.session-panel { align-self: start; height: 360px; max-height: 100%; }
.session-list { min-height: 0; overflow-y: auto; }
.conversation-panel { min-height: 0; overflow: hidden; }
.message-area { min-height: 0; overflow-y: auto; }
```

在全局样式定义与现有顶栏一致的 `--app-shell-offset`，窄屏规则保留单列布局。

- [ ] **Step 2: 编译验证**

Run: `pnpm build`

Expected: 类型检查和 Vite 构建成功。

---

### Task 3: 移动任务规划并修复规划对比入口

**Files:**
- Modify: `ai-collab-frontend/src/shared/AppShell.vue`
- Modify: `ai-collab-frontend/src/modules/work/PlanComparisonView.vue`
- Modify: `ai-collab-frontend/src/modules/planning/planning-api.ts`
- Create: `ai-collab-frontend/src/modules/work/PlanComparisonView.test.ts`

**Interfaces:**
- Consumes: `planningApi.list(projectId, page, size)` 和可选 URL `planId`。
- Produces: 一级导航“知识问答 → 任务规划 → 协作 Agent”；规划对比页面的规划选择器。

- [ ] **Step 1: 写规划选择失败测试**

挂载页面并模拟规划列表，断言未传 `planId` 时显示“请选择要对比的任务规划”而不是“请提供规划 ID”；选择规划后调用 `reportApi.planComparison(projectId, selectedId)`。

- [ ] **Step 2: 验证失败**

Run: `pnpm test -- src/modules/work/PlanComparisonView.test.ts`

Expected: 页面当前不加载规划列表，测试失败。

- [ ] **Step 3: 实现导航和选择器**

复用现有规划列表接口，加载项目与规划；合法查询参数自动选中，缺失参数停留在选择引导。将任务规划移出“智能工具”下拉并放到知识问答右侧。

- [ ] **Step 4: 运行定向测试和构建**

Run: `pnpm test -- src/modules/work/PlanComparisonView.test.ts && pnpm build`

Expected: 测试与构建成功。

---

### Task 4: 增加 Agent 会话重命名和删除

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/controller/AgentSessionController.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/dto/RenameAgentSessionRequest.java`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentRepositoryIntegrationTest.java`
- Modify: `ai-collab-frontend/src/modules/agent/agent-api.ts`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Create: `ai-collab-frontend/src/modules/agent/AgentView.test.ts`

**Interfaces:**
- Produces: `PATCH /api/v1/projects/{projectId}/agent/sessions/{sessionId}` body `{title}`。
- Produces: `DELETE /api/v1/projects/{projectId}/agent/sessions/{sessionId}` 返回 204。

- [ ] **Step 1: 写后端失败测试**

覆盖所有者可重命名/删除、其他项目或其他用户不可操作，以及删除会话级关联数据的行为。

- [ ] **Step 2: 验证后端测试失败**

Run: `.\mvnw.cmd -Dtest=AgentRepositoryIntegrationTest test`

Expected: 缺少重命名/删除方法而失败。

- [ ] **Step 3: 实现后端命令**

请求标题 `strip()` 后必须为 1–120 个 Unicode 字符；服务先校验项目成员和会话创建者，再调用仓储更新或删除。

- [ ] **Step 4: 写前端失败测试**

断言会话操作菜单提供“重命名”“删除”；删除当前会话后自动选择下一条；无剩余会话时展示新建入口。

- [ ] **Step 5: 实现前端交互**

补充 API、编辑态、删除确认、进行中禁用和成功中文提示。

- [ ] **Step 6: 定向验证**

Run: `.\mvnw.cmd -Dtest=AgentRepositoryIntegrationTest test`

Run: `pnpm test -- src/modules/agent/AgentView.test.ts`

Expected: 两端定向测试通过。

---

### Task 5: 中文化审批卡片

**Files:**
- Create: `ai-collab-frontend/src/modules/agent/approval-presentation.ts`
- Create: `ai-collab-frontend/src/modules/agent/approval-presentation.test.ts`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Reuse: `ai-collab-frontend/src/shared/display-labels.ts`

**Interfaces:**
- Produces: `presentApproval(approval): { actionLabel, statusLabel, fields }`。

- [ ] **Step 1: 写失败测试**

用 `create_task_after_approval` 与用户提供的任务差异样例断言：

```ts
expect(result.actionLabel).toBe('创建任务')
expect(result.statusLabel).toBe('待审批')
expect(result.fields).toContainEqual({ label: '任务名称', value: '完成 Agent 浏览器验收' })
```

同时断言结果中不包含 `create_task_after_approval`、`APPROVED` 或序列化原始 JSON。

- [ ] **Step 2: 验证失败**

Run: `pnpm test -- src/modules/agent/approval-presentation.test.ts`

- [ ] **Step 3: 实现集中映射与卡片**

映射审批工具、审批状态、任务状态、优先级和空值；卡片改用描述列表，操作按钮仅在待审批时显示。

- [ ] **Step 4: 运行测试**

Run: `pnpm test -- src/modules/agent/approval-presentation.test.ts`

Expected: 中文呈现测试通过。

---

### Task 6: 删除 Agent 评估闭环

**Files:**
- Delete: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/controller/AgentEvaluationController.java`
- Delete: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentEvaluationService.java`
- Delete: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentEvaluationRunView.java`
- Delete: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentEvaluationTemplateView.java`
- Delete: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentEvaluationRepository.java`
- Create: `ai-collab-backend/src/main/resources/db/migration/V25__remove_agent_evaluation.sql`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Modify: `ai-collab-frontend/src/modules/agent/agent-api.ts`
- Modify: `ai-collab-frontend/src/modules/agent/types.ts`

**Interfaces:**
- Removes: `/agent/evaluations/templates` 与 `/fixture-runs`。
- Preserves: 协作对话、审批、定时运行接口。

- [ ] **Step 1: 删除前后端引用**

移除评估页签、加载请求、类型、控制器、服务、仓储和视图对象。

- [ ] **Step 2: 添加数据库迁移**

V25 使用 `DROP TABLE IF EXISTS agent_evaluation_result;` 和 `DROP TABLE IF EXISTS agent_evaluation_run;`，再删除仅由评估使用的模板表；按实际 V23 外键顺序排列。

- [ ] **Step 3: 运行编译和迁移测试**

Run: `.\mvnw.cmd -Dtest=AgentMigrationIntegrationTest test`

Run: `pnpm build`

Expected: Flyway 到 V25 成功，前端无残留类型引用。

---

### Task 7: 将知识库评测改成中文表单

**Files:**
- Create: `ai-collab-frontend/src/modules/knowledge/knowledge-eval-form.ts`
- Create: `ai-collab-frontend/src/modules/knowledge/knowledge-eval-form.test.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeEvalView.vue`

**Interfaces:**
- Produces: `toEvalRequest(rows)` 返回 `{ question, expectedDocumentIds }[]`。
- Consumes: 当前项目 `READY` 文档列表。

- [ ] **Step 1: 写失败测试**

覆盖空问题、未选文档、至少一条有效用例和去除首尾空格。

- [ ] **Step 2: 验证失败**

Run: `pnpm test -- src/modules/knowledge/knowledge-eval-form.test.ts`

- [ ] **Step 3: 实现表单与说明**

加载项目文档；每行包含问题输入、预期文档多选和删除按钮；提供“添加问题”。在页面展示 Recall@3、Recall@5、MRR 中文说明，不再接受 JSON。

- [ ] **Step 4: 运行测试和构建**

Run: `pnpm test -- src/modules/knowledge/knowledge-eval-form.test.ts && pnpm build`

Expected: 测试与构建通过。

---

### Task 8: 补充管理中心验证

**Files:**
- Create: `ai-collab-frontend/src/modules/admin/AdminView.test.ts`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/service/AdminService.java`

**Interfaces:**
- Preserves: 系统管理员账号管理、模型配置和用途分配。

- [ ] **Step 1: 写管理员页面行为测试**

模拟三个加载接口，断言页面显示“用途分配”“模型配置”“账号管理”；触发用途分配后断言调用中文对应目的的接口；账号状态只显示“正常/停用”。

- [ ] **Step 2: 运行测试**

Run: `pnpm test -- src/modules/admin/AdminView.test.ts`

Expected: 新测试通过；如暴露可访问性或事件绑定问题，做最小修复后再次运行。

- [ ] **Step 3: 修正过期注释**

删除 `AdminService` 中“没有系统级管理员角色、任何用户可调用”的错误说明，保留真实权限约束说明。

---

### Task 9: 同步接口文档、清理历史文档并完成全量验证

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/feature-matrix.md`
- Modify: `docs/architecture.md`
- Modify: `docs/database.md`
- Delete: `docs/learning/`
- Delete: 过期的 `docs/superpowers/plans/*.md` 和 `docs/superpowers/specs/*.md`，保留本轮计划与设计
- Delete: `docs/FUTURE_ROADMAP.md`

**Interfaces:**
- OpenAPI 与 Agent 会话新增接口、Agent 评估删除保持一致。
- 功能矩阵更新日期为 2026-07-30，并只写验证后的代码事实。

- [ ] **Step 1: 更新 OpenAPI 与当前事实文档**

补充 Agent 会话 PATCH/DELETE；删除 Agent 评估路径和 schema；把管理员界面、模型管理、Agent 运行时、知识问答流式状态更新为实际结果。

- [ ] **Step 2: 精确列出待删除文档**

删除前确认目标全部位于 `E:\ai-collab\docs\learning`、`docs/superpowers/plans`、`docs/superpowers/specs` 或明确的 `docs/FUTURE_ROADMAP.md`，并保留本轮两份文档。

- [ ] **Step 3: 运行 OpenAPI 校验**

Run: `powershell -ExecutionPolicy Bypass -File scripts/validate-openapi.ps1`

Expected: 契约校验成功。

- [ ] **Step 4: 运行前端全量验证**

Run: `pnpm test`

Run: `pnpm typecheck`

Run: `pnpm build`

Expected: 所有测试通过，类型检查和生产构建退出码 0。

- [ ] **Step 5: 运行后端全量验证**

Run: `.\mvnw.cmd test`

Expected: 所有后端测试通过，Flyway 从空库迁移到最新版本。

- [ ] **Step 6: 检查改动范围**

Run: `git diff --check`

Run: `git status --short`

Expected: 无空白错误；只报告本轮文件和进入分支前已经存在的用户改动。
