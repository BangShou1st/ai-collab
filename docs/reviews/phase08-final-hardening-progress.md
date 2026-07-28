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
