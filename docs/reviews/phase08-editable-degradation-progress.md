# Phase 08 可编辑降级执行进度

> 基线 commit: `12d9714` (feat/phase-08-editable-degradation)
> 工作分支: `feat/phase-08-editable-degradation`
> 迁移最高版本: V7
> 后端测试基线: 207 tests, 0 failures
> 前端测试基线: 31 tests, 0 failures

---

## Task 完成记录

### Task 1: 审计并冻结当前领域规则
- RED: `TaskPlanDraftValidatorRuleCatalogTest` (10 tests) — 冻结当前 34 个错误码 + 4 个警告码
- GREEN: `docs/reviews/phase08-current-validator-rule-catalog.md` 创建
- Commit: `17cc948`

### Task 2: 结构化 Validation Issue 与严重性
- RED: `ValidationIssueCatalogTest` (19 tests) — 严重性分类、可修复字段、评估查询
- GREEN: `ValidationIssueSeverity`, `StructuredValidationIssue`, `ValidationIssueCatalog`, `ValidationAssessment` 创建
- Commit: `d0ec41a`

### Task 3: 确定性安全归一化
- RED: `TaskPlanDraftNormalizerTest` (14 tests) — trim、去重、排序、不填默认值
- GREEN: `TaskPlanDraftNormalizer` 创建
- Commit: `4d04417`

### Task 4: Prompt 规则目录与模型适配
- RED: `PlanningPromptPolicyTest` (12 tests) — 安全测试、规则生成
- GREEN: `PlanningPromptPolicy` 创建
- Commit: `97ba26e`

### Task 5: 定向 Repair Patch
- RED: `TaskPlanRepairPatchTest` (12 tests) — PatchValue、locked fields、安全校验
- GREEN: `PatchValue`, `TaskPlanRepairPatch`, `RepairScope`, Parser, Applier 创建
- Commit: `fed46b0`

### Task 6: 状态机与可编辑降级
- RED: `GenerationOutcomeDeciderTest` (16 tests) — 决策逻辑、状态转换
- GREEN: `REPAIRING`, `READY_WITH_ISSUES` 状态添加，`GenerationOutcomeDecider` 创建
- Commit: `8474b12`

### Task 7: 数据库 issues、events 与事务
- RED: `TaskPlanVersionCommitServiceTest` (5 tests) — 原子提交流程
- GREEN: V7 迁移、IssueRepository、EventRepository、CommitService 创建
- Commit: `8853ab8`

### Task 8: 用户编辑、版本与事件
- RED: `TaskPlanEditTest` (5 tests) — PATCH 编辑、乐观锁
- GREEN: `UpdateTaskPlanRequest`、`TaskPlanCommandService.edit()` 创建
- Commit: `3977409`

### Task 9: 局部重新生成
- RED: `TaskPlanPartialRegenerateTest` (5 tests) — 局部生成 API
- GREEN: `PartialRegenerateRequest`、`TaskPlanCommandService.partialRegenerate()` 创建
- Commit: `0503aab`

### Task 10: 确认落地限制
- RED: 全量测试 (98 tests) — 确认限制
- GREEN: `TASK_PLAN_HAS_BLOCKING_ISSUES` 错误码、确认服务 issue 检查
- Commit: `5e89829`

### Task 11: 查询 API、OpenAPI 和前端类型
- RED: 编译错误修复 — `TaskPlanPermissions` 缺少参数
- GREEN: 结构化 issues、事件 API、前端类型更新
- Commit: `ab8e217`

### Task 12: 前端 READY_WITH_ISSUES、编辑和事件
- RED: 全量后端测试通过
- GREEN: `PlanningIssuePanel`, `PlanningEventTimeline`, `planning-edit.ts` 创建
- Commit: `9989ee5`

### Task 13: 全链路集成、并发与安全测试
- RED: 编译错误修复 — 构造函数签名不匹配
- GREEN: `Phase08EditableDegradationIntegrationTest` (16 tests) G1-G8 全场景
- Commit: `462070a`

### Task 14: 完整验证
- RED: 迁移安全测试期望 V1-V6
- GREEN: 后端 207/207 + 前端 31/31 + 构建成功
- Commit: `3dad4f9`

---

## 真实验收状态

### 场景 A：真实 READY
- ⏳ 待手动验收：需要真实 PostgreSQL + 模型 API

### 场景 B：真实 READY_WITH_ISSUES
- ⏳ 待手动验收：需要测试 profile + 确定性测试入口

### 场景 C：编辑解决
- ⏳ 待手动验收：需要场景 B 先完成

### 场景 D：局部 AI 修复
- ⏳ 待手动验收：需要场景 B 先完成

---

## 安全检查

- ✅ 未记录敏感数据
- ✅ 未修改旧迁移 (V1-V6)
- ✅ 未捏造成员/来源
- ✅ .env 未跟踪
- ✅ 未提交 zip/target/dist/日志

---

## 工作区状态

```
git status --short
git diff --check
```

已知未跟踪文件（非代码）：
- ai-collab-backend/src-后端.zip
- ai-collab-frontend/src-前端.zip
- docs/reviews/phase08-create-plan-fk-repair-explanation.md
- docs/reviews/phase08-create-plan-fk-repair-progress.md
- docs/reviews/phase08-detail-domain-validation-explanation.md
- docs/superpowers/plans/2026-07-26-phase-08-repair.md
- fix/
