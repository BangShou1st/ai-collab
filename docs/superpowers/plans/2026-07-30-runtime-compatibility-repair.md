# 运行时兼容修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复知识问答 AI、AI 任务规划和项目文档的真实端到端可用性，并补齐多文件上传、日期约束和具体顶部错误提示。

**Architecture:** 先修复知识问答与规划共用的 PostgreSQL 检索映射，再恢复 MinIO 运行配置；前端通过独立的认证 fetch、日期规则、批量上传队列和错误呈现工具建立统一边界。页面核心数据与可选文档列表解耦加载，瞬时依赖故障不再让整个页面不可用。

**Tech Stack:** Vue 3、TypeScript、Element Plus、Vitest、Spring Boot 4、Java 21、MyBatis、PostgreSQL/pgvector、MinIO、JUnit、Testcontainers

## Global Constraints

- 知识问答必须真实调用 AI 并保留 SSE 逐段输出、引用和消息持久化。
- 任务规划必须真实完成知识检索和规划骨架生成，不允许用假成功替代。
- 最大任务数默认 20，允许用户输入 1–40 的整数。
- 项目、规划、里程碑和任务结束日期不得早于开始日期，子资源日期不得超出项目日期。
- 业务错误显示在页面上层，提示必须包含操作上下文和安全的具体原因。
- MinIO 重建必须保留现有命名卷，不删除旧对象。
- 保留工作区已有未提交改动，不提交与本轮无关的文件。

---

### Task 1: 修复 PostgreSQL 知识检索映射

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/view/DocumentSearchHit.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/infrastructure/mapper/DocumentMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/persistence/PostgresJsonMapTypeHandler.java`
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/document/infrastructure/DocumentRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `DocumentMapper.search(...) -> List<DocumentSearchHit>`，其中 `metadata()` 始终为非空 `Map<String, Object>`。
- Consumes: PostgreSQL `document_chunk.metadata jsonb`。

- [ ] **Step 1: 写失败的真实检索测试**

在 PostgreSQL 集成测试中插入带 `{"pageNumber":7}` metadata 和 1024 维向量的 READY 文档分块，调用 `DocumentRepository.search(...)`，断言：

```java
assertThat(result).singleElement().satisfies(hit -> {
    assertThat(hit.originalFilename()).isEqualTo("需求.pdf");
    assertThat(hit.metadata()).containsEntry("pageNumber", 7);
});
```

- [ ] **Step 2: 验证测试按预期失败**

Run: `.\mvnw.cmd -Dtest=DocumentRepositoryIntegrationTest test`

Expected: FAIL，错误包含 `No constructor found in DocumentSearchHit` 或 metadata 无法映射。

- [ ] **Step 3: 增加显式 jsonb 类型处理**

实现 `PostgresJsonMapTypeHandler extends BaseTypeHandler<Map<String,Object>>`，读取 `ResultSet#getString` 后通过 Jackson 转换；空值返回 `Map.of()`。在 `DocumentMapper.search` 使用显式 `@Results`/`@Result` 将 `metadata` 绑定到该处理器，不依赖构造器猜测。

- [ ] **Step 4: 验证检索测试通过**

Run: `.\mvnw.cmd -Dtest=DocumentRepositoryIntegrationTest test`

Expected: PASS，页码 metadata 被正确读取。

---

### Task 2: 恢复 MinIO 文档存储

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/infrastructure/storage/MinioDocumentStorageGateway.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/document/infrastructure/storage/MinioDocumentStorageGatewayTest.java`
- Runtime: `ai-collab-deploy/docker-compose.yml`
- Runtime config: `.env`（只读取并核对，不输出密钥）

**Interfaces:**
- Produces: `DocumentStorageGateway.put/open/presign/delete` 的稳定业务错误。
- Preserves: Docker volume `ai_collab_minio_data`。

- [ ] **Step 1: 写存储错误分类失败测试**

模拟 MinIO 认证失败，断言业务异常消息为“文件存储认证失败，请联系管理员检查 MinIO 配置”，同时确保日志不含账号或密钥。

- [ ] **Step 2: 验证失败测试**

Run: `.\mvnw.cmd -Dtest=MinioDocumentStorageGatewayTest test`

- [ ] **Step 3: 实现安全、具体的错误映射**

将 MinIO 认证/连接/bucket 异常映射为用户可执行的安全中文信息；日志仅记录异常类型与错误码。

- [ ] **Step 4: 保留数据卷重建 MinIO**

先验证 compose 项目和命名卷，再运行：

```powershell
docker compose --env-file ..\.env -f .\docker-compose.yml up -d --force-recreate minio
```

工作目录：`ai-collab-deploy`。禁止使用 `down -v`。

- [ ] **Step 5: 真实存储验收**

确认容器健康、bucket 可访问；通过应用 API 上传一个小型 UTF-8 文本文件，取得下载地址并读取内容，最后仅删除该验收文件。

---

### Task 3: 为流式问答接入令牌刷新

**Files:**
- Create: `ai-collab-frontend/src/api/authenticated-fetch.ts`
- Create: `ai-collab-frontend/src/api/authenticated-fetch.test.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/knowledge-api.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/knowledge-stream.test.ts`

**Interfaces:**
- Produces: `authenticatedFetch(input, init): Promise<Response>`。
- Behavior: 首次 401 时调用 `useAuthStore().refresh()` 并重试一次；第二次 401 清理认证并触发未授权处理。

- [ ] **Step 1: 写失败测试**

覆盖：

```ts
it('refreshes once and retries a streaming request after 401', async () => {})
it('redirects after the retried request is still unauthorized', async () => {})
it('does not retry non-401 responses', async () => {})
```

- [ ] **Step 2: 验证失败**

Run: `pnpm test -- src/api/authenticated-fetch.test.ts`

- [ ] **Step 3: 实现最小认证 fetch**

复用 `auth.refresh()` 和现有 `handleUnauthorized()`；每次发送前重新写入 Authorization，不修改调用者传入的 Headers 对象。

- [ ] **Step 4: 将 SSE 请求切换到认证 fetch**

保持现有流解析逻辑不变，只替换发送边界，并将最终 HTTP 401 映射为明确中文错误。

- [ ] **Step 5: 运行定向测试**

Run: `pnpm test -- src/api/authenticated-fetch.test.ts src/modules/knowledge/knowledge-stream.test.ts`

Expected: PASS。

---

### Task 4: 修复规划输入、日期和轮询

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/api/CreateTaskPlanRequest.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanCommandService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftValidator.java`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanCommandServiceTest.java`
- Modify: `ai-collab-frontend/src/modules/planning/PlanningView.vue`
- Modify: `ai-collab-frontend/src/modules/planning/types.ts`
- Modify: `ai-collab-frontend/src/modules/planning/planning-poller.ts`
- Modify: `ai-collab-frontend/src/modules/planning/planning-poller.test.ts`
- Modify: `ai-collab-frontend/src/modules/planning/PlanningView.test.ts`

**Interfaces:**
- Accepts: `maxTaskCount` 任意 1–40 整数，默认由前端设为 20。
- Rejects: 空标题、空目标、缺失日期、结束早于开始、超出项目日期。

- [ ] **Step 1: 写后端范围失败测试**

断言 1、17、40 被接受，0、41 被拒绝且消息包含“最大任务数必须在 1 到 40 之间”。

- [ ] **Step 2: 验证后端测试失败**

Run: `.\mvnw.cmd -Dtest=TaskPlanCommandServiceTest test`

- [ ] **Step 3: 实现后端范围校验**

DTO 增加 `@Min(1) @Max(40)`；服务移除 `Set.of(10,20,30,40)` 限制并保留防御性范围校验。

- [ ] **Step 4: 写前端表单失败测试**

断言最大任务数使用 `el-input-number`、默认 20；空字段或错误日期不会调用 `planningApi.create`，并显示具体顶部错误。

- [ ] **Step 5: 写轮询器失败测试**

`load` 抛出 HTTP 400 后推进计时器，断言只调用一次；429/503 仍会重试。

- [ ] **Step 6: 实现规划前端校验和轮询规则**

将固定选项改为：

```vue
<el-input-number v-model="form.maxTaskCount" :min="1" :max="40" :step="1" />
```

轮询器对除 429 外的所有 4xx 停止。

- [ ] **Step 7: 运行定向测试**

Run: `pnpm test -- src/modules/planning/planning-poller.test.ts src/modules/planning/PlanningView.test.ts`

---

### Task 5: 解耦页面加载并统一顶部错误

**Files:**
- Modify: `ai-collab-frontend/src/api/api-result.ts`
- Create: `ai-collab-frontend/src/shared/operation-error.ts`
- Create: `ai-collab-frontend/src/shared/operation-error.test.ts`
- Modify: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.vue`
- Create: `ai-collab-frontend/src/modules/knowledge/KnowledgeView.test.ts`
- Modify: `ai-collab-frontend/src/modules/planning/PlanningView.vue`

**Interfaces:**
- Produces: `showOperationError(action: string, error: unknown): ApiResult<unknown>`。
- Error copy: `${action}失败：${具体安全原因}`。

- [ ] **Step 1: 写错误优先级失败测试**

后端返回 `{code:'VALIDATION_ERROR', message:'规划结束日期不能早于开始日期'}` 时，断言不被“请检查填写内容”覆盖。

- [ ] **Step 2: 写页面降级失败测试**

模拟文档列表 503，断言知识会话/规划列表仍被加载，页面显示顶部文档不可用提示而不是整页空白。

- [ ] **Step 3: 实现错误工具**

Element Plus `ElMessage.error` 置顶显示；优先使用后端安全具体消息，仅对空消息使用代码映射。网络错误附带“请确认后端服务已启动”。

- [ ] **Step 4: 解耦加载**

项目、会话/规划列表作为核心请求；文档列表单独 try/catch，失败时置空并提供重试按钮，不中止核心页面。

- [ ] **Step 5: 移除受影响页面的布局型错误行**

删除知识问答和规划页顶部通用 `el-alert`；规划校验问题、历史只读说明等非请求错误保留在内容区。

- [ ] **Step 6: 运行定向测试**

Run: `pnpm test -- src/shared/operation-error.test.ts src/modules/knowledge/KnowledgeView.test.ts src/modules/planning/PlanningView.test.ts`

---

### Task 6: 支持多文件上传

**Files:**
- Create: `ai-collab-frontend/src/modules/document/upload-queue.ts`
- Create: `ai-collab-frontend/src/modules/document/upload-queue.test.ts`
- Modify: `ai-collab-frontend/src/modules/document/DocumentView.vue`
- Create: `ai-collab-frontend/src/modules/document/DocumentView.test.ts`

**Interfaces:**
- Produces: `runUploadQueue(files, upload, concurrency = 3)`。
- Each result: `{ file, status: 'SUCCESS' | 'FAILED', error?: ApiResult<unknown> }`。

- [ ] **Step 1: 写队列失败测试**

覆盖多个文件、最大并发 3、单个失败不阻断、结果保持原选择顺序。

- [ ] **Step 2: 验证失败**

Run: `pnpm test -- src/modules/document/upload-queue.test.ts`

- [ ] **Step 3: 实现上传队列**

不增加后端批量端点；调用现有 `documentApi.upload`，每个 worker 从共享索引领取下一文件。

- [ ] **Step 4: 改造文档页面**

文件选择开启 `multiple`；展示每个文件的等待、上传中、成功、失败；全部完成后刷新列表并以顶部消息汇总成功/失败数量。

- [ ] **Step 5: 运行页面测试**

Run: `pnpm test -- src/modules/document/upload-queue.test.ts src/modules/document/DocumentView.test.ts`

---

### Task 7: 建立共享日期约束

**Files:**
- Create: `ai-collab-frontend/src/shared/date-rules.ts`
- Create: `ai-collab-frontend/src/shared/date-rules.test.ts`
- Modify: `ai-collab-frontend/src/modules/project/ProjectListView.vue`
- Modify: `ai-collab-frontend/src/modules/planning/PlanningView.vue`
- Modify: `ai-collab-frontend/src/modules/work/MilestoneView.vue`
- Modify: `ai-collab-frontend/src/modules/work/TaskBoardView.vue`
- Modify: `ai-collab-frontend/src/modules/work/TaskDetailDrawer.vue`

**Interfaces:**
- Produces:

```ts
validateDateRange(start: string | null, end: string | null, bounds?: DateBounds): string | null
disabledOutsideProject(date: Date, bounds: DateBounds): boolean
```

- [ ] **Step 1: 写日期规则失败测试**

覆盖相等日期、结束早于开始、开始早于项目、结束晚于项目、缺失可选日期。

- [ ] **Step 2: 验证失败**

Run: `pnpm test -- src/shared/date-rules.test.ts`

- [ ] **Step 3: 实现纯函数**

只比较 `YYYY-MM-DD`，不引入时区转换；错误消息明确指出哪一个日期越界。

- [ ] **Step 4: 接入日期选择器**

为项目、规划、里程碑、任务设置 `disabled-date`；当开始日期变化导致结束日期非法时清空结束日期并顶部提示。

- [ ] **Step 5: 接入提交前校验**

所有 create/update 调用前执行同一纯函数；非法时不调用 API。

- [ ] **Step 6: 运行日期和页面测试**

Run: `pnpm test -- src/shared/date-rules.test.ts`

Run: `pnpm test`

---

### Task 8: 真实 AI 与全量验收

**Files:**
- Modify: `docs/feature-matrix.md`
- Modify: `docs/api/openapi.yaml`（仅当请求约束发生契约变化）

**Interfaces:**
- Verifies: 文档上传 → READY → 知识问答 AI 流式回答 → 规划骨架生成。

- [ ] **Step 1: 后端全量测试**

Run: `.\mvnw.cmd test`

Expected: 全部通过，Flyway v25。

- [ ] **Step 2: 前端全量测试和构建**

Run: `pnpm test`

Run: `pnpm typecheck`

Run: `pnpm build`

- [ ] **Step 3: OpenAPI 和差异检查**

Run: `.\scripts\validate-openapi.ps1`

Run: `git diff --check`

- [ ] **Step 4: 真实多文件上传**

用项目管理员账号一次选择至少两个小文件，断言两个文件分别成功并最终进入 READY；下载其中一个文件验证内容。

- [ ] **Step 5: 真实知识问答 AI**

在同一项目新建问答会话并提问已上传文档中的事实，断言收到多个 token 或完整 token 流、citations 和 done，且会话详情包含持久化的用户与 AI 消息。

- [ ] **Step 6: 真实任务规划**

创建 `maxTaskCount=17` 的新规划，等待轮询，断言不再出现 `No constructor found in DocumentSearchHit`，并至少生成有效骨架；若进入细节阶段则继续等待 READY/READY_WITH_ISSUES。

- [ ] **Step 7: 更新事实文档**

只记录上述真实验收后的状态；未通过的链路不得标记完成。
