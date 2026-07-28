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

## 阶段五：异步局部 AI Repair

RED：

- OpenAPI mode enum 缺少 `REPAIR_ASSIGNMENTS_AND_SOURCES`。
- 配额测试证明 `AI_REPAIR`、`AI_PARTIAL`、`AI_PARTIAL_REPAIR` 成功版本和
  `REPAIRING` 活动 attempt 均未计数。
- 真实 PostgreSQL 测试证明旧版本 issue 与跨 plan issue 都只返回通用
  `VALIDATION_ERROR`；同一请求中与 mode 不匹配的选中 issue 会被静默忽略。

根因与修复：

- 新增并贯通 Java/OpenAPI/TypeScript 的分配与来源修复 mode；前端按字段选择日期、
  分配来源或任务详情模式。
- server scope 严格执行
  `issue repairable fields ∩ mode fields ∩ client allowed fields - locked fields`；
  显式 issueIds 的每一项都必须匹配 target 与 mode。
- issue repository 增加 plan 范围 ID 判定：跨 plan/不存在返回
  `PLAN_REPAIR_ISSUE_INVALID`，同 plan 旧版本或已解决返回
  `PLAN_REPAIR_ISSUE_CONFLICT`。
- 成功配额统计四种 AI 成功来源，活动配额包含 `REPAIRING`；局部修复继续复用
  throttle 与 quota。
- 模型调用后提交再次比较 `basedOn` 与数据库 latest version；并发更新时丢弃结果，
  恢复原状态且不产生第三个版本。
- 版本、issues、event、status、attempt 的局部修复 commit 继续位于同一事务；
  注入 event 写失败的 PostgreSQL 测试证明全部回滚。

GREEN：

- 局部修复/配额/OpenAPI/生产 PostgreSQL 目标回归：41 tests，0 失败；
  原子回滚测试单独通过。
- 后端完整回归：232 tests，0 failure/error/skipped。
- 前端 `pnpm typecheck` 与 33 tests 通过。

## 阶段六：统一完整编辑、恢复版本与事件审计

RED：

- `TaskPlanVersionCommitServiceTest` 证明事件原先没有持久化真实的 `changedFields` 与 `changedTargets`。
- PostgreSQL 生产接线测试补充完整 Draft 新增/删除实体与恢复历史版本场景，要求旧版本保持不可变。
- 新增 V11 后，迁移安全测试暴露最新版本断言仍停留在 V10。

根因与修复：

- 所有完整 Draft 保存与历史恢复统一经过 `TaskPlanVersionCommitService`，执行结构化校验、创建不可变新版本、刷新 issues、更新计划状态并写入事件。
- 事件差异由提交服务对前后 Draft 做结构化比较，覆盖摘要、假设、风险、来源以及 milestone/task 的新增、删除和字段变化；不再推断目标，也不对外暴露 before/after hash。
- V11 为事件增加非空 JSONB `changed_targets_json`；Repository、View、OpenAPI 契约同步使用持久化目标。
- 恢复历史版本以当前 latest 作为乐观锁基线、历史版本作为 `basedOnVersionId`，创建 `RESTORED` 新版本，绝不覆盖旧版本。
- 受限 PATCH 明确拒绝 title/goal/constraints 等元数据和实体增删；完整编辑只通过 `POST /versions`。
- 迁移安全测试升级为验证 V1～V11 的严格顺序、全部成功及真实 JSONB 列，不是放宽断言。

GREEN：

- 事件、提交、生产 PostgreSQL、真实 Spring Bean 与迁移安全目标回归：32 tests，0 failure/error。
- 后端完整回归：235 tests，0 failure/error/skipped；Testcontainers PostgreSQL 17、Flyway V1～V11 实际执行。

## 阶段七：确认规则、权限矩阵与并发保护

RED：

- 新增 `permissionsMatchCommandGuardsForEveryStatus` 后编译失败，证明不存在统一动作策略组件。
- 初次在 Command 外层重复预读局部修复状态导致委托回归失败，暴露重复检查会扩大竞态窗口。

根因与修复：

- 新增唯一的 `TaskPlanActionPolicy`，为全部状态定义 edit、cancel、retry detail、regenerate、confirm、delete、restore 与 partial regenerate。
- `TaskPlanQueryService.permissions`、CommandService、ConfirmationService 和异步 PartialRepairService 共同使用同一策略；局部修复只在真正持有校验流程的服务内检查，不做外层重复读取。
- `READY_WITH_ISSUES` 可删除、可重新生成、不可确认；`REPAIRING` 只允许取消；`CONFIRMED` 全部只读。
- 确认 claim 事务锁计划并验证 READY、latest 和 blocking issues；land 事务再次读取并锁定 plan/latest、issues、version 和引用成员。
- 历史版本返回 `PLAN_VERSION_CONFLICT`，`READY_WITH_ISSUES` 返回 `TASK_PLAN_HAS_BLOCKING_ISSUES`。
- 两个真实 PostgreSQL 事务的并发测试证明：确认持有计划锁并进入 `CONFIRMING` 后，编辑等待锁且最终因状态冲突失败，不产生新版本。

GREEN：

- 权限/命令/确认/迁移/并发目标回归：30 tests，0 failure/error。
- 后端完整回归：243 tests，0 failure/error/skipped。

## 阶段八：前端中文界面与真实 PlanningView 交互

RED：

- 真实 `mount(PlanningView)` 的 13 项组件测试首次运行 7 项失败：状态筛选显示英文 enum、错误摘要暴露内部 code/path、`REPAIRING` 不继续轮询、无版本规划残留旧 Draft、问题面板只显示 tempKey、历史只读断言及恢复事件缺中文映射。
- 其余 6 项首次即通过，保留为版本来源、优先级、scoped Repair 请求、确认限制与假设/风险编辑的回归证据。

根因与修复：

- 状态筛选同时绑定中文 `label` 和稳定英文 `value`，补齐 `CONFIRMING`。
- 新增 `planningFailureLabel()`，只根据安全阶段/code 输出中文文案；未知摘要使用通用中文，不渲染原始内部内容。
- poller 活动态加入 `REPAIRING`；局部修复完成后仍轮询到终态。
- 打开 `latestVersionId=null` 的规划时显式清空 selectedVersionId、Draft 和 snapshot。
- 问题面板使用 Draft 中任务/里程碑名称与中文字段，不再向用户展示 tempKey；仅为服务端支持的 issue/mode 显示 AI 操作。
- Repair mode 改由 issue code 决定，局部修复继续发送 issueId、baseVersionId、expectedVersionNo、target、allowed/locked fields。
- 完整补齐 TaskPlanStatus、版本来源、优先级、ValidationIssueCatalog、事件类型、安全失败阶段/code 的中文映射；未知 code 不回显。
- 编辑表单补齐目标、日期、工时、负责人、依赖和来源的中文标签；成员与来源使用姓名/文档名。
- 前端 409 增加规划版本冲突专用中文提示。

GREEN：

- `pnpm typecheck` 通过。
- Vue Test Utils：真实 PlanningView 13 项组件测试全部通过；前端完整 6 files / 41 tests 全部通过。
- `pnpm build` 通过；仅保留现有非阻断 chunk size 警告。

## 阶段九：生产 Spring Bean + PostgreSQL 闭环

RED：

- 新增默认由 Surefire 执行的 `TaskPlanSpringBeanPostgresIntegrationTest`，首次目标运行暴露两处测试假设错误：
  issue 表实际名称为 `ai_task_plan_validation_issue`，合法确认按 Draft 正确创建 2 个正式任务而非 1 个。
- 移除非模型边界的 MinIO mock 后，Spring 上下文在 `ApplicationReadyEvent` 收到
  `InvalidAccessKeyId`，5 项测试均因真实对象存储初始化失败。

根因与修复：

- 回滚注入改为作用于实际 issue 表；正式任务断言按输入 Draft 的两个任务校正，没有修改生产逻辑或放宽业务期望。
- 测试类同时启动真实 PostgreSQL 17 与真实 MinIO Testcontainer，通过
  `DynamicPropertySource` 注入动态端点和测试容器凭据；不再依赖本机对象存储状态。
- 仅 `TaskPlanModelClient` 使用 `@MockitoBean`；Orchestrator、Command、Partial Repair、
  Confirmation、Version Commit、Repositories、ObjectMapper、事务代理与 MinIO 均为生产 Spring Bean。

GREEN：

- 目标集成测试：5 tests，0 failure/error/skipped；真实执行 Flyway V1～V11。
- 覆盖合法生成、降级后可编辑、手动解决、scoped 局部修复、历史版本确认冲突、
  确认落地任务/依赖、event/issue 写失败原子回滚及并发编辑一成功一版本冲突。
- 后端完整回归：247 tests，0 failure/error/skipped。
