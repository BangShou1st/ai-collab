# Phase 08 Final Closure Progress

## 当前分支与基线

- **分支**: `feat/phase-08-ai-task-planning`
- **最新提交**: `94104e1` docs: add Phase 08 repair progress and verification reports
- **工作区**: 1 untracked file (`docs/superpowers/plans/2026-07-26-phase-08-repair.md`)
- **V5 checksum**: 与原始 commit `d7897ab` 完全一致（无 diff）
- **Docker**: 已安装 v29.6.1，daemon 未运行（尝试启动中）

### 后端基线
- Tests run: 25, Failures: 0, Errors: 0, Skipped: 10
- Skipped: Phase08MigrationSafetyIntegrationTest (6) + PlanningMigrationIntegrationTest (4)
- 原因: Testcontainers 无法连接 Docker daemon

### 前端基线
- typecheck: PASS
- tests: 4 passed (2 test files)
- build: PASS

## 已复核

### R1: DETAIL 不能修改骨架字段 — ✅ 已修复
- `validateSkeletonPreserved()` 扩展为检查: summary, assumptions, risks, targetDate, sortOrder, milestoneTempKey
- 新增测试: `detailMustPreserveSkeletonTargetDateAndSortOrder`, `detailPreservesSkeletonWhenOnlyDetailFieldsChange`
- 代码: `TaskPlanDraftValidator.java`, `TaskPlanDomainTest.java`

### R2: 三种数据契约拆分 — ✅ 已修复
- 新建 `SkeletonModelOutput` (只含 identity 字段) 和 `DetailModelOutput` (只含补充字段)
- `TaskPlanOutputParser` 新增 `parseSkeleton()` / `parseDetail()` 使用严格 ObjectMapper (FAIL_ON_UNKNOWN_PROPERTIES)
- orchestrator 按阶段使用不同 DTO 和 schema
- 测试: `mergeDetailIntoSkeletonPreservesIdentityFields`

### R3: DETAIL Prompt 信任边界 — ✅ 已修复
- `<SKELETON>` 只包含 identity-only 数据 (SkeletonIdentityOnly)
- `<SOURCES>` 独立分离
- `<MEMBER_CONTEXT>` 只含 userId/displayName/role
- 所有标签声明为不可信，XML 转义
- 测试: `promptEscapesClosingBoundariesAndBudgetCountsUnicodeCodePoints`

### R4: Future 注册竞态 — ✅ 已修复
- FutureTask-first: 先创建 FutureTask 放入 registry，再 executor.execute()
- queue reject 时立即从 registry 移除
- 测试: orchestrator 单元测试覆盖

### R5: 领域校验 — ✅ 已修复
- source ref 格式校验: 必须匹配 `S\d{1,2}` (S1-S12)
- 新增错误码: `SOURCE_REF_FORMAT_INVALID`
- 新增测试: `validatorRejectsInvalidSourceRefFormat`

### R6: 队列拒绝 HTTP 语义 — ✅ 已修复
- GlobalExceptionHandler 捕获 RejectedExecutionException → 503 PLANNING_QUEUE_FULL
- 不暴露堆栈或内部错误
- 不清理 activeAttemptId (保持现有逻辑)

### R7: 真实 actor — ✅ 已修复
- orchestrator.dispatch() 接收 actor 参数
- 所有 attempt.createdBy, version.createdBy 使用传入的 actor
- CommandService 传递 actor 到 orchestrator

### P2-1: attempt 指标 — ⏳ 待处理
### P2-2: activeAttemptId 清理 — ✅ 已完成
### P2-3: 人工保存事务 — ✅ 已修复 (save/restore 加 @Transactional)
### P2-4: 限流顺序 — ✅ 已修复 (validate → rateLimit → dispatch)
### P2-5: Prompt 总预算 — ✅ 已完成
### P2-6: 稳定 400 — ✅ 已修复 (UUID 格式、MissingRequestHeader、MethodArgumentTypeMismatch)

## 进行中

- [ ] R1-R7, P2-1~P2-6 系统性修复

## 未完成

- [ ] Testcontainers 测试运行（Docker daemon 未运行）
- [ ] OpenAPI 更新
- [ ] architecture.md / database.md / learning 更新
- [ ] final-closure-verification.md

## 当前失败

- Docker daemon 未运行，Testcontainers 测试 skipped

## 下一步唯一动作

启动 Docker Desktop，然后按优先级修复 R1→R2→R4→R3→R6→R7→R5→P2
