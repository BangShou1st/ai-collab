# Phase 08 最小高影响闭环报告

日期：2026-07-28  
仓库：`E:\ai-collab`  
分支：`feat/phase-08-ai-task-planning`

## 结论

本轮只处理会造成数据错误、用户误操作或公开接口崩溃的高影响问题。三项指定闭环均完成；预估工时下限因会阻塞正式任务落库，按提示词条件升级并完成最小修复。后端、真实 PostgreSQL/Spring、OpenAPI、前端组件、类型检查和生产构建均已重新执行。

## 1. AI Repair Patch 严格校验

### RED

新增并先运行以下测试：

- `repairPatchRejectsWrongFieldTypes`
- `repairPatchRejectsUnknownProperties`
- `repairPatchRejectsMissingRequiredPatchArrays`
- `repairPatchRejectsNonObjectRoot`

初次结果：`TaskPlanRepairPatchTest` 16 项中 4 项失败，错误类型、未知字段、缺失必填数组和非对象根均存在未拒绝路径。

### 根因与最小修复

解析器使用 `asText()`、`asDouble()` 和 `path()`，会做宽松转换、忽略未知字段，并把缺失数组视为空数组。现已在解析边界：

- 要求根、task patch、milestone patch 必须为对象；
- 要求 `taskPatches`、`milestonePatches` 必须存在且为数组；
- 对根、task、milestone 使用字段白名单；
- 严格检查字符串、数字、日期、UUID 和字符串数组类型；
- 统一返回不含模型原文的 `Invalid repair patch JSON`。

解析发生在 `commitPartialRepair` 之前；异常分支只执行 `failPartialRepair`，不会创建版本或修改原 Draft。

### GREEN

- `TaskPlanRepairPatchTest`、`TaskPlanPartialRegenerateTest`、`Phase08EditableDegradationIntegrationTest`：35/35。
- 真实 PostgreSQL 局部修复目标集：18/18。
- 后端全量：254/254。

## 2. 历史版本彻底只读

### RED

新增并先运行以下真实 `mount(PlanningView)` 测试：

- `historyVersionHidesLatestIssueActions`
- `historyVersionCannotTriggerManualEdit`
- `historyVersionCannotTriggerAiRepair`
- `historyVersionCanOnlyRestoreAsNewVersion`

初次结果：4/4 失败。历史版本仍显示问题动作；直接触发组件事件会定位最新 Draft 的编辑字段，并会携带最新版本 ID 调用 `partial-regenerate`；全局重新生成入口也仍可见。

### 根因与最小修复

问题面板没有接收版本只读状态，事件处理器也没有生产方法守卫。现已：

- 历史版本隐藏 AI 修复、手动编辑、重新生成相关任务及其他规划变更入口；
- `repair`、`manualEdit`、`regenerateIssue`、全局 action、删除和确认增加最新版本守卫；
- 历史态只保留“恢复为新版本”；
- 统一显示“历史版本仅供查看，请先恢复为新版本”；
- 切回最新版本后问题动作恢复。

### GREEN

- `PlanningView.test.ts`：18/18。
- 前端全量：6 files / 46 tests。
- `pnpm typecheck`：通过。

## 3. 旧公开 PATCH 接口

### 生产引用证据

修复前静态搜索结果：

- 前端只有 `planning-api.ts` 中的 `edit` 方法声明；
- 前端生产代码没有任何 `planningApi.edit` 调用；
- `UpdateTaskPlanRequest` 的其他引用只存在于后端 Controller、CommandService、测试、OpenAPI 和历史审计文档。

因此当前 UI 已完全使用 `POST /versions` 保存完整 Draft，旧 PATCH 不是生产前端业务入口。考虑仓库外调用者无法由仓库搜索排除，本轮没有删除后端公开路由。

### RED

真实 Jackson 反序列化仅包含 `baseVersionId` 和 `expectedVersionNo` 的请求时，省略的 `title`、`goal`、`constraints` 为 `null`，测试出现 3 个 NPE；嵌套 milestone/task 的省略 PatchValue 也存在同类风险。

### 最小修复与 GREEN

- 顶层和嵌套省略 PatchValue 统一归一化为 `PatchValue.absent()`；
- 省略的 milestone/task 数组归一化为空列表；
- 前端删除未使用的 `edit` 客户端方法；
- Java Controller、请求 DTO、CommandService 和 OpenAPI 标记为 deprecated；
- OpenAPI 明确推荐 `POST /versions`，并允许兼容请求省略 patch 数组；
- Jackson、编辑服务、OpenAPI 与 Repair Patch 目标集：24/24；合并工时边界后相关目标集 40/40。

## 条件升级：预估工时下限

规划 Validator 原先只拒绝 `<= 0`，但正式任务 DTO、数据库约束、OpenAPI 和前端均要求 `>= 0.5`。`0.1` 会通过规划校验，随后在确认创建正式任务时触发数据库约束失败，因此升级为高影响问题。

最小修复把规划校验和模型提示统一为 `0.5～80`；新增边界证明：

- `0.49`：`ESTIMATED_HOURS_INVALID`；
- `0.5`：接受。

## 全量回归

| 范围 | 命令 | 结果 |
|---|---|---|
| 后端全量 | `.\mvnw.cmd test` | 254/254，0 failure/error/skipped |
| 后端干净构建 | `.\mvnw.cmd clean package` | 245 个生产源重新编译，254/254，JAR 成功 |
| Spring/PostgreSQL | 全量中的 Testcontainers 集成测试 | PostgreSQL 17、Flyway V1–V11、真实 Spring Bean、MinIO 均通过 |
| 前端类型 | `pnpm typecheck` | 通过 |
| 前端全量 | `pnpm test` | 6 files / 46 tests |
| 前端构建 | `pnpm build` | 1725 modules，成功；仅既有大 chunk 警告 |
| OpenAPI | `PlanningOpenApiContractTest` | 通过，旧 PATCH deprecated 契约已锁定 |

四条现有规划主链路由真实 PostgreSQL 生产 wiring 测试覆盖，严格 Patch 后合法局部修复夹具已按生产 Schema 补齐，18/18 通过，未放宽生产校验或修改业务期望。

## 已知非阻塞限制

详见 `docs/reviews/phase08-known-nonblocking-limitations.md`：

- 事件时间线对带实体路径的嵌套字段回退为“相关字段”；
- 普通生成 attempt 成功状态在权威业务提交后单独写入；
- 非关键代码清理、样式重构和既有构建告警。

## 提交

- `e916d40` `fix(planning): reject unsafe repair patch payloads`
- `305c8aa` `fix(frontend): keep historical plan versions strictly read-only`
- `3b49cdf` `fix(api): harden and deprecate legacy plan patch endpoint`（最终报告与合法测试夹具并入该提交的后续 amend）

## 最终工作区

基线发现的 3 个未跟踪 ZIP 生成物已按明确要求精确删除。最终提交后执行 `git diff --check` 无输出，`git status --short` 无输出。

## PASS

- 三类指定高影响问题已闭环；
- 条件升级的工时边界已闭环；
- 全量测试和构建通过；
- 当前版本业务链路未回归；
- 工作区干净。
