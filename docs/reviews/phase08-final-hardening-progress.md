# Phase 08 最终硬化进度

日期：2026-07-28（Asia/Shanghai）

## 阶段一：本轮可信基线

- 分支：`feat/phase-08-ai-task-planning`
- 起始提交：`0836073`
- `git status --short`、`git diff --check`、`git stash list` 均无输出。
- `git worktree list` 仅包含 `E:\ai-collab`；旧 worktree 不存在。
- `.env` 未被 Git 跟踪。
- Docker Client/Server：29.6.1。
- PostgreSQL、Redis 和 MinIO 共三个基础设施容器运行；PostgreSQL、Redis 健康。

本轮重新运行的基线：

| 项目 | 命令 | 结果 |
|---|---|---|
| 后端测试 | `mvnw.cmd test` | 222 tests，0 failure/error/skipped |
| 后端构建 | `mvnw.cmd clean package` | 222 tests，0 failure/error/skipped，BUILD SUCCESS |
| 前端类型 | `pnpm typecheck` | 通过 |
| 前端测试 | `pnpm test` | 5 个文件、33 tests 通过 |
| 前端构建 | `pnpm build` | 通过；保留非阻断 chunk size 警告 |

基线通过只证明现有测试为绿，不视为满足本轮门槛。

## 首轮契约与测试覆盖审计

已确认的 RED 目标：

1. `TaskPlanQueryService.events()` 直接返回基础设施 `TaskPlanEventRecord`，包含
   `actorId`、`beforeHash`、`afterHash`；TypeScript 也公开这些字段。
2. OpenAPI 的 `UpdateTaskPlanRequest.required` 缺少真实必填的 `expectedVersionNo`。
3. OpenAPI 版本来源 enum 内嵌在 View，尚无可与 Java/TypeScript/DB 独立比对的正式 schema。
4. `TaskPlanDraftValidator.assess()` 仍先调用平面 `validate()`，再按 code 反向定位 issue，
   与“生产 Validator 直接产生结构化 issue”的硬约束冲突。
5. `TaskPlanQueryService` 自行硬编码 permissions，CommandService 使用另一套状态守卫。
6. 前端尚未依赖 Vue Test Utils，也没有 mount `PlanningView.vue` 的真实组件测试；
   当前 33 个测试均为函数/API/poller 测试。

下一步严格从契约 RED 测试开始，验证失败原因后再修改生产代码。

## 阶段二：冻结正式契约

一致性审计：

| 契约 | Java / DB | OpenAPI | TypeScript / 页面 | 处置 |
|---|---|---|---|---|
| `TaskPlanStatus` | Java 与 V7/V8 constraint 共 10 项 | 10 项 | 10 项 | 一致 |
| `TaskPlanVersionSource` | Java 与 V5/V9/V10 最终 constraint 共 8 项 | 8 项 | 页面按字符串映射 | 一致 |
| `PartialRegenerateRequest` | base/version 必填，异步 202 | required 与 202 | API 发送相同字段 | 一致；mode 深度规则留待阶段五 |
| `SaveTaskPlanVersionRequest` | base/version/draft 必填 | 相同 | 正式完整编辑入口 | 一致 |
| `UpdateTaskPlanRequest` | base/version 必须校验 | 原缺 `expectedVersionNo` | 受限 PATCH | 已修复 required |
| validation issue | 持久化 ID + 定位 + safeDetails | 安全 View | 相同字段 | 一致 |
| event | 原返回基础设施 Record | 原 schema 不够严格 | 原 TS 暴露 actor/hash | 已改安全 `TaskPlanEventView` |
| permissions | QueryService 硬编码 | 字段完整 | 页面消费 | 待阶段七统一策略 |

RED 证据：

- `PlanningOpenApiContractTest` 失败于缺少 `expectedVersionNo`。
- `TaskPlanEventViewTest` 初次编译失败，因为安全 View 尚不存在。

根因与修复：

- OpenAPI required 列表由人工维护时遗漏了真实乐观锁字段。
- events 端点越过应用层直接返回数据库 Record，使内部 actor 与完整性 hash 成为公共契约。
- 新增安全事件 DTO，只保留页面审计字段；QueryService 显式映射，OpenAPI 与 TS 同步移除内部字段。
- changedTargets 当前由安全字段路径确定性派生；统一 commit 阶段将验证事件差异语义。

目标 GREEN：两个契约测试共 2 tests，0 失败。

## 阶段三：生产 Validator 直接生成定位 issue

RED：

- 新增 `productionAssessmentDoesNotDelegateToFlatCodeValidation`。
- RED 失败栈明确为 `assess → validate`，证明生产 assessment 依赖平面 code 后再定位。

根因：

- 早期兼容改造保留了旧 `validate()` 为权威入口，`assess()` 只能遍历 error/warning code，
  再扫描整份 Draft 猜测 target 和 field；多个同 code 问题可能失去准确来源。

修复：

- `assess()` 在每一条领域规则命中时直接构造 `StructuredValidationIssue`。
- dependency date issue 在检测依赖边时写入后续任务、`startDate`、前置任务和两项安全日期。
- member、source、milestone、temp key、date、hours、priority、cycle/self/duplicate dependency
  均在观察到无效值时携带目标字段。
- safeDetails 只包含日期、上下限、允许值和 source ref 等白名单值。
- 删除旧的 code→重新扫描 Draft 的 locate 路径。
- `validate()` 现在只由 assessment 派生兼容的 `ValidationResult`。

GREEN：

- 规则/目录/领域目标测试：59 tests，0 失败。
- 后端完整回归：224 tests，0 failure/error/skipped；真实 PostgreSQL 17、
  Flyway V1～V10 与 Spring Bean 测试实际执行。
- 生产源码中 `new StructuredValidationIssue` 仅存在于 Validator 构造点和 Repository 数据映射。

## 阶段四：生成阶段 scoped Patch Repair

审计结论：

- Detail 初次输出使用 `parseDetail()`；修复输出使用独立的
  `TaskPlanRepairPatchParser` 和 `TaskPlanRepairPatchApplier`，未把 Patch 当完整 Detail 反序列化。
- `RepairScope` 由结构化 issue 的目标和目录中的 repairable fields 生成；
  title、objective、tempKey、sortOrder、summary、assumptions、risks 等身份字段保持锁定。
- 修复后重新经过 normalize 和生产 Validator；仍有 HARD issue 时进入
  `DETAIL_GENERATION_FAILED`，不会提交部分成功版本。
- Repair attempt 会替换活动 attempt，Future registry 在 terminal path 清理。

RED：

- 新增 `detailPromptUsesOnlyServerProjectMembersForAssigneeWhitelist`。
- 测试证明模型 Skeleton 中捏造的 `suggestedAssigneeId` 被错误加入 Detail 规则白名单。

根因与修复：

- Detail prompt 的展示成员来自数据库，但规则白名单却从 Skeleton 反推，形成两个权威来源。
- `TaskPlanContextAssembler.memberSnapshot()` 现在用同一次服务端项目成员查询生成
  `memberId + displayName + role` 展示上下文和 `memberIds` 白名单。
- Orchestrator 不再读取 Skeleton 中的 assignee 值来生成许可集合。

GREEN：

- 生成规则、Orchestrator、Patch parser/applier 目标回归：35 tests，0 失败。
- 后端完整回归：225 tests，0 failure/error/skipped；Testcontainers PostgreSQL 17、
  Flyway V1～V10 和真实 Spring Bean 测试均实际执行。
