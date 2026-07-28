# Phase 08 工作树合并审计

> 本文保留合并前现场快照；合并、删除与最终回归结论见
> `phase08-main-wip-disposition.md` 和 `phase08-final-system-repair-report.md`。

审计时间：2026-07-28（Asia/Shanghai）

## 1. 审计边界与安全约束

- 主工作目录：`E:\ai-collab`
- 旧工作树：`E:\ai-collab-phase08-editable`
- 本轮只调查、备份和制定合并策略，尚未执行 merge、删除、reset、clean 或业务代码修改。
- 未读取或输出任何 `.env` 内容；两个目录中的 `.env` 均未被 Git 跟踪，且由 `.gitignore:45` 忽略。
- 未修改任何既有 Flyway 迁移。

## 2. Worktree、分支与 HEAD

`git worktree list --porcelain` 证明：

| 目录 | 类型 | 分支 | HEAD |
|---|---|---|---|
| `E:\ai-collab` | 主检出目录 | `feat/phase-08-ai-task-planning` | `12d9714a43dc3b2d8b49ff96e3d221f25cf80f3f` |
| `E:\ai-collab-phase08-editable` | linked worktree | `feat/phase-08-editable-degradation` | `eba204f1d2538d762505156af60c783d23b1cdb1` |

主目录的 `.git` 与 common dir 均为 `.git`。旧目录的 git dir 为
`E:/ai-collab/.git/worktrees/ai-collab-phase08-editable`，common dir 为
`E:/ai-collab/.git`。

## 3. 两边工作区状态

### 3.1 `E:\ai-collab`

`git status --short`：

```text
 M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/ModelOutputContractException.java
 M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java
 M ai-collab-frontend/src/modules/planning/PlanningView.vue
 M ai-collab-frontend/src/modules/planning/planning-api.ts
?? ai-collab-backend/src-backend.zip
?? ai-collab-frontend/src-frontend.zip
?? docs.zip
?? docs/reviews/phase08-create-plan-fk-repair-explanation.md
?? docs/reviews/phase08-create-plan-fk-repair-progress.md
?? docs/reviews/phase08-detail-domain-validation-explanation.md
?? docs/superpowers/plans/2026-07-26-phase-08-repair.md
```

无 staged 修改。`git diff --check` 与 `git diff --cached --check` 均无输出。

已跟踪 WIP 共 4 个文件，统计为 82 insertions、9 deletions。

### 3.2 `E:\ai-collab-phase08-editable`

`git status --short`：

```text
?? ai-collab-backend/src-1.zip
?? ai-collab-frontend/src-2.zip
?? docs.zip
```

无 tracked/staged WIP。`git diff --check` 与 `git diff --cached --check` 均无输出。

外部现场备份已写入：

| 文件 | 大小 | 含义 |
|---|---:|---|
| `E:\phase08-editable-uncommitted.patch` | 0 bytes | 无 unstaged tracked diff |
| `E:\phase08-editable-staged.patch` | 0 bytes | 无 staged diff |
| `E:\phase08-editable-status.txt` | 158 bytes | 保存 3 个未跟踪 ZIP 的状态 |

## 4. 分支关系与独有 commits

执行 `git fetch --all --prune` 后：

```text
git rev-list --left-right --count HEAD...feat/phase-08-editable-degradation
0	27
```

merge-base：

```text
12d9714a43dc3b2d8b49ff96e3d221f25cf80f3f
```

它与主目录当前 HEAD 完全相同。因此：

- 主分支独有 commit：0；
- 旧分支独有 commit：27；
- 旧分支是从主目录当前 HEAD 线性继续的历史，不存在已提交历史的双向分叉；
- 尚未发现 cherry-equivalent 的重复提交；
- 合并范围为 61 个受跟踪文件，6534 insertions、56 deletions。

旧分支独有 commits（从新到旧）：

```text
eba204f docs: record phase 08 final continuation progress
51bf37f test(planning): verify postgres production workflows
efcd17f fix(planning): use interactive commit path for edit/restore
229bc7b fix(planning): align version source and status persistence
0d78bc3 fix(planning): complete generation failure and attempt cleanup
407c8c9 fix(planning): honor finalStatus in appendVersion instead of hardcoding READY
bbd15f4 docs: correct Phase 08 editable degradation status
9321423 test(planning): verify production wiring and transaction rollback
4a1cf22 fix(api): expose safe structured planning views
d41faae fix(frontend): wire issue editing and event timelines
52cbb1c fix(planning): make edit commits atomic and severity-safe
28a90ae fix(planning): wire editable degradation into production generation
18d1c26 docs: explain Phase 08 editable planning degradation
3dad4f9 fix(test): update migration safety test for V7
462070a test(planning): cover editable degradation and repair end to end
9989ee5 feat(frontend): edit and repair plans with unresolved issues
ab8e217 feat(api): expose structured plan issues and edit operations
5e89829 fix(planning): block confirmation while plan issues remain
0503aab feat(planning): regenerate selected plan fields with scoped patches
3977409 feat(planning): edit generated plans with versioned audit events
8853ab8 feat(planning): persist validation issues and plan events atomically
8474b12 feat(planning): degrade editable model conflicts without losing drafts
fed46b0 feat(planning): apply scoped model repair patches safely
97ba26e feat(planning): adapt prompts to domain rules and model limits
4d04417 feat(planning): add deterministic safe draft normalization
d0ec41a feat(planning): add structured validation issue severity and catalog
17cc948 test(planning): freeze current validator rule semantics
```

## 5. 旧分支需要合并的受跟踪文件

以下是 `git diff --name-status HEAD...feat/phase-08-editable-degradation` 的完整范围。

### 5.1 后端生产代码

```text
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/api/PartialRegenerateRequest.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/api/TaskPlanController.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/api/UpdateTaskPlanRequest.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/GenerationOutcomeDecider.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/ModelOutputContractException.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/PlanningPromptPolicy.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanCommandService.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanConfirmationService.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanDetailView.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanQueryService.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanRepairPatchApplier.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanRepairPatchParser.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanVersionCommitService.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/PatchValue.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/RepairScope.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/StructuredValidationIssue.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftNormalizer.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanRepairPatch.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanStatus.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanVersionSource.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/ValidationAssessment.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/ValidationIssueCatalog.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/ValidationIssueSeverity.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanEventRecord.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanEventRepository.java
A ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanIssueRepository.java
M ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanRepository.java
```

### 5.2 数据库迁移（只新增，不修改既有迁移）

```text
A ai-collab-backend/src/main/resources/db/migration/V7__add_task_plan_issues_and_events.sql
A ai-collab-backend/src/main/resources/db/migration/V8__fix_status_check_constraint.sql
A ai-collab-backend/src/main/resources/db/migration/V9__add_partial_repair_source_type.sql
A ai-collab-backend/src/main/resources/db/migration/V10__add_ai_partial_source_type.sql
```

### 5.3 后端测试

```text
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/GenerationOutcomeDeciderTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/Phase08EditableDegradationIntegrationTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/PlanningPromptPolicyTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanEditTest.java
M ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestratorIntegrationTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanPartialRegenerateTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanProductionWiringPostgresIT.java
M ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanQueryServiceTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanVersionCommitServiceTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftNormalizerTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftValidatorRuleCatalogTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanRepairPatchTest.java
A ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/ValidationIssueCatalogTest.java
M ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/infrastructure/Phase08MigrationSafetyIntegrationTest.java
M ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/infrastructure/PlanningMigrationIntegrationTest.java
M ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanRepositoryIntegrationTest.java
```

### 5.4 前端、OpenAPI 与审计文档

```text
M ai-collab-frontend/src/modules/planning/PlanningView.vue
A ai-collab-frontend/src/modules/planning/components/PlanningEventTimeline.vue
A ai-collab-frontend/src/modules/planning/components/PlanningIssuePanel.vue
M ai-collab-frontend/src/modules/planning/planning-api.ts
A ai-collab-frontend/src/modules/planning/planning-edit.ts
M ai-collab-frontend/src/modules/planning/types.ts
M docs/api/openapi.yaml
A docs/reviews/phase08-current-validator-rule-catalog.md
A docs/reviews/phase08-editable-degradation-explanation.md
A docs/reviews/phase08-editable-degradation-progress.md
A docs/reviews/phase08-final-continuation-progress.md
A docs/reviews/phase08-production-wiring-audit.md
```

## 6. 不应合并的未跟踪生成物

旧工作树：

| 文件 | 大小 | ZIP 条目 | 顶层目录 | `.env` 类条目 |
|---|---:|---:|---|---:|
| `ai-collab-backend/src-1.zip` | 358243 bytes | 387 | `src` | 0 |
| `ai-collab-frontend/src-2.zip` | 78705 bytes | 64 | `src` | 0 |
| `docs.zip` | 176229 bytes | 38 | `docs` | 0 |

主目录：

| 文件 | 大小 | ZIP 条目 | 顶层目录 | `.env` 类条目 |
|---|---:|---:|---|---:|
| `ai-collab-backend/src-backend.zip` | 297052 bytes | 355 | `src` | 0 |
| `ai-collab-frontend/src-frontend.zip` | 75155 bytes | 60 | `src` | 0 |
| `docs.zip` | 173533 bytes | 37 | `docs` | 0 |

这些 ZIP 是源码/文档快照生成物，不是 Git 历史的一部分，不应提交或用来覆盖仓库文件。当前仅记录，不删除。

未发现旧工作树中的日志、`.env`、`target`、`dist`、`node_modules` 或临时 patch。

## 7. 主目录 WIP 与旧分支的语义对比

主目录 WIP 不是旧工作树 WIP，但会直接影响安全合并，必须先保存并逐文件判断。

1. `ModelOutputContractException.java`
   - 主目录 WIP 与旧分支最终版本无差异。
   - validation codes 写入安全摘要的有效意图已被旧分支提交包含，不需要再次应用。

2. `TaskPlanGenerationOrchestrator.java`
   - 主目录 WIP保留 `ValidationResult` 错误码，但相对旧分支会撤回
     `PlanningPromptPolicy`、`TaskPlanDraftNormalizer`、`GenerationOutcomeDecider`、
     `TaskPlanVersionCommitService`、结构化 assessment 和
     `READY_WITH_ISSUES` 可编辑降级。
   - 不能在合并后整包覆盖；其中“保留错误码”的意图需在旧分支实现上用测试验证，
     缺失时再最小移植。

3. `PlanningView.vue`
   - 主目录 WIP 相对旧分支会删除结构化 issues、events、局部修复入口，
     并移除 `REPAIRING`/`READY_WITH_ISSUES` 状态筛选。
   - 不能整包保留。

4. `planning-api.ts`
   - 主目录 WIP 相对旧分支会删除 edit、events、partialRegenerate API。
   - `parseValidationErrorSummary` 的 unknown-code 分支直接返回英文内部 code，
     与“未知 code 不得暴露英文枚举”冲突。
   - 不能整包保留；中文集中映射应在后续 TDD 阶段重新实现。

主目录 3 份未跟踪说明文档记录了 active-attempt 外键修复和 detail
validation 诊断意图，可作为后续调查证据，但其中声称的历史测试结果必须重新运行，
不能作为本轮验收证据。

## 8. 安全合并策略

下一阶段按以下顺序执行：

1. 在主目录额外导出 tracked WIP patch、staged patch 和 status 到 `E:\`，
   保证主目录现场也可恢复；保留所有未跟踪文档和 ZIP，不删除。
2. 在旧工作树当前 `eba204f` 上运行提示词要求的最小后端、前端基线测试，
   证明 27 个提交自身的状态。
3. 将主目录 4 个 tracked WIP 安全保存到 Git stash（不包含未跟踪文件），
   不使用 reset、clean 或 checkout 丢弃修改。
4. 在 `E:\ai-collab` 执行保留历史的：

   ```text
   git merge --no-ff feat/phase-08-editable-degradation
   ```

   由于已提交历史是线性后继，预计 commit 本身无冲突；主目录 WIP 必须先隔离，
   否则 Git 会因 3 个重叠文件拒绝合并或造成错误覆盖。
5. 不整包 `stash pop`。以旧分支合并结果为基线，逐文件对照已保存 patch：
   - 已等价包含的 `ModelOutputContractException` 不重复应用；
   - 会撤回结构化降级、原子提交和前端编辑能力的修改不应用；
   - 尚缺失但业务有效的诊断/中文展示意图，先写失败测试，再最小实现。
6. 运行后端 `test`，以及由 lockfile 确定的前端
   `typecheck`、`test`、`build`；执行 `git diff --check`。
7. 只有在测试通过且审计确认所有有效 WIP 已等价包含或有明确处置记录后，
   才创建合并提交 `merge: integrate Phase 08 editable planning worktree`。
8. 删除旧工作树前必须再次证明：
   - `git merge-base --is-ancestor eba204f HEAD` 返回 0；
   - `git branch --contains eba204f` 包含当前分支；
   - 旧目录没有未处置的有效 WIP；
   - 3 个 ZIP 明确标记为不合并生成物。
9. 证明完成后才使用 `git worktree remove`、`git worktree prune` 和
   `git branch -d`；禁止 `-D`。删除后验证 `Test-Path` 返回 `False`。

## 9. 当前结论

- 旧分支 27 个 commits 全部需要通过保留历史的 merge 进入主目录。
- 旧工作树无 tracked/staged WIP；3 个 ZIP 为不应合并的生成物。
- 主目录存在会与旧分支语义冲突的 tracked WIP，已完成逐文件判断，但尚未保存主目录外部 patch 或执行 stash。
- 旧目录尚未删除，分支尚未合并，符合本轮只审计不实施合并的边界。
