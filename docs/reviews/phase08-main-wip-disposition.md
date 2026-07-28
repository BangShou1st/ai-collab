# Phase 08 主目录合并前 WIP 处置记录

日期：2026-07-28

## 保存证据

- stash：`3e9afeb4fb4136ff22f7d887aaaeb21427434dde`
- stash message：`phase08-main-pre-merge-wip-20260728`
- 原始 patch：`E:\phase08-main-uncommitted.patch`
- staged patch：`E:\phase08-main-staged.patch`
- 状态记录：`E:\phase08-main-status.txt`
- stash 审查 patch：`E:\phase08-main-stash-review.patch`

未执行 `stash pop`、整包 apply、reset 或 clean。

## 逐文件处置

| 文件 | 结论 | 证据与后续动作 |
|---|---|---|
| `ModelOutputContractException.java` | `ALREADY_INCLUDED` | 合并后的旧分支版本已经包含 validation codes 安全摘要；stash 版本与合并结果在该文件上等价，无需重复应用。 |
| `TaskPlanGenerationOrchestrator.java` | `REJECTED_AS_REGRESSION`，保留诊断意图 | stash 会撤回 `PlanningPromptPolicy`、normalizer、结构化 assessment、原子版本提交和 `READY_WITH_ISSUES` 降级。不能覆盖。错误码保留和 Repair 诊断意图改由合并后实现上的自动化测试验证。 |
| `PlanningView.vue` | `REJECTED_AS_REGRESSION` | stash 会删除 structured issues、events、局部修复入口以及 `REPAIRING`/`READY_WITH_ISSUES` 展示。不能覆盖。 |
| `planning-api.ts` | `REJECTED_AS_REGRESSION`，重做中文映射 | stash 会删除 edit、events、partialRegenerate API；unknown code 直接返回英文内部枚举。不能覆盖。后续通过集中中文映射和前端测试实现有效用户体验。 |

## 合并与清理证明

- 合并提交：`6f7dca7 merge: integrate Phase 08 editable planning worktree`
- `git merge-base --is-ancestor eba204f HEAD`：退出码 0
- `git branch --contains eba204f`：包含 `feat/phase-08-ai-task-planning`
- 旧 worktree 的三个 ZIP 生成物已删除。
- `git worktree remove` 已解除旧 worktree 注册；测试产生的残留依赖目录经确认后删除。
- `Test-Path E:\ai-collab-phase08-editable`：`False`
- `git worktree list`：仅 `E:\ai-collab`

## Stash 生命周期

当前继续保留 stash 作为审计恢复点。只有在后端诊断、前端中文映射与最终全量验证完成，
且本文件补充最终测试证据后，才删除对应 stash。

## 最终验证与删除授权

2026-07-28 已完成以下最终验证：

- 后端 `mvnw.cmd test`、`mvnw.cmd verify`、`mvnw.cmd clean package` 均通过，共执行 222 个测试。
- 默认测试集包含真实 `@SpringBootTest`、生产 Bean、Spring 事务代理、Flyway V1～V10 和 PostgreSQL 17 Testcontainers。
- 前端 `pnpm typecheck`、`pnpm test`（33 个测试）和 `pnpm build` 均通过。
- 真实 HTTP 已验证生成、版本冲突、手动保存、局部 AI 修复、确认落地、权限拒绝和事件时间线。
- 真实 Edge 已验证注册、会话刷新、项目创建、规划页加载和中文界面。

因此，stash `3e9afeb4fb4136ff22f7d887aaaeb21427434dde` 中四个文件的有效意图均已按上表处置，
不再需要作为恢复点；允许只删除这一条 stash。仍然禁止 `stash pop`、整包 apply、reset 或 clean。
