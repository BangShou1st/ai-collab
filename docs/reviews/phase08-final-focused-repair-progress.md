# Phase 08 Final Focused Repair Progress

## 当前分支和基线
- 分支：feat/phase-08-ai-task-planning
- 最新 commit：8ab43f6
- 后端测试：Tests run: 29, Failures=0, Errors=0, Skipped=0
- 前端：pnpm 未在 bash shell 中，后续使用 PowerShell 执行

## 待修复
- [ ] C1 AI 完整结果使用错误校验模式
- [ ] C2 详情接口 Map.of + nullable 导致 500
- [ ] C3 多规划 generationSeq registry key 冲突
- [ ] C4 cancel 读取旧 attempt 的竞态
- [ ] C5 recovery 未清理 activeAttemptId
- [ ] C6 Detail tempKey 集合不完整
- [ ] C7 source ref 范围和重复来源校验
- [ ] C8 重复 dependency 校验
- [ ] C9 人工保存与成员移除并发一致性
- [ ] C10 前端 milestone description 缺失
- [ ] C11 validation/warnings/source 前端闭环不足
- [ ] C12 前后端 Detail/Version/OpenAPI 契约不一致
- [ ] C13 architecture/database 仍保留旧事实
- [ ] C14 缺少关键完整编排、并发和组件测试

## 当前任务
- 编号：C1
- 实际调用链：Orchestrator.runDetail → validator.validate(ctx, merged, true) → validate(ctx, draft, true) → validate(ctx, draft, AI_SKELETON, true)
- 根因：boolean aiGenerated=true 同时被解释为 AI_SKELETON，导致合并后的完整 AI draft 跳过 detail 检查
- RED 测试：合法 skeleton + 非法 detail → 不得 READY
- 计划修改：删除 boolean 重载，改用明确 ValidationMode

## 已完成
- [x] C1 AI 完整结果使用错误校验模式
  - 文件：TaskPlanDraftValidator.java, TaskPlanGenerationOrchestrator.java, TaskPlanDomainTest.java
  - 方法：validate(), runDetail(), generateDetailWithOneRepair()
  - RED：aiGeneratedTrueMustStillValidateDetailFieldsInCompleteMode, aiGeneratedTrueMustStillValidatePriority
  - GREEN：18 tests, Failures=0
  - 回归：32 tests, Failures=0, Errors=0, Skipped=0
  - commit：6c7eac6
- [x] C2 详情接口 Map.of + nullable 导致 500
  - 文件：TaskPlanDetailView.java, TaskPlanQueryService.java, TaskPlanQueryServiceTest.java
  - 方法：detail()
  - RED：detailReturnsNullFieldsGracefullyForQueuedPlan
  - GREEN：33 tests, Failures=0
  - 回归：33 tests, Failures=0, Errors=0, Skipped=0
  - commit：8ef2c6c

## 当前失败
（待填写）

## 下一步唯一动作
从 C1 开始：写 RED 测试验证 orchestrator 用 COMPLETE 模式校验完整 AI draft
