# Phase 08 Evidence-Driven Repair Progress

## 当前分支和提交

- **分支**: `feat/phase-08-ai-task-planning`
- **HEAD**: `cc46f11` fix(project): stable 409 when deleting project with confirmed plans
- **工作区**: 1 modified (progress doc), untracked (zip, fix/, plans)

## 已关闭

### STARTUP-BLOCKER: OpenAiCompatibleChatModelGateway 构造器歧义
- **根因**: 类有两个构造器且无 `@Autowired`，Spring 尝试无参构造器失败
- **修改**: `OpenAiCompatibleChatModelGateway.java` 添加 `@Autowired`
- **新增**: `ObjectMapperConfiguration.java` 确保 Jackson 自动配置
- **RED**: 无（直接通过 javap 和源码确认）
- **GREEN**: Spring Boot 启动成功，health UP，受保护端点 401
- **回归**: 29 tests pass, 0 failures
- **commit**: `51a642f`

### F1: 骨架校验矛盾
- **根因**: `validate()` 的 `aiGenerated=true` 要求 description/priority 非空，但骨架输出这些字段为 null
- **修改**: `TaskPlanDraftValidator.java` 新增 `ValidationMode` 枚举
- **RED**: skeleton 任务 description=null 时通过 validSkeleton 但被完整 validator 拒绝
- **GREEN**: `ValidationMode.AI_SKELETON` 跳过 detail 字段检查
- **回归**: 29 tests pass
- **commit**: `bce0904`

### F2: DETAIL Prompt 使用 SKELETON_SCHEMA
- **根因**: `detailPrompt()` 以 `skeletonPrompt(p)` 开头，嵌入 SKELETON_SCHEMA
- **修改**: `TaskPlanGenerationOrchestrator.java` detailPrompt 使用 DETAIL_SCHEMA
- **RED**: detail prompt 包含 SKELETON_SCHEMA 的 required 结构
- **GREEN**: detail prompt 只包含 DETAIL_SCHEMA
- **回归**: 29 tests pass
- **commit**: `bce0904`

### F3: 取消不覆盖 DETAIL/REPAIR
- **根因**: activeFutures 用 attemptId 做 key，骨架→细节转换后 key 不匹配
- **修改**: `TaskPlanGenerationOrchestrator.java` 添加 rekeyFuture 和 generationActiveAttempt
- **RED**: cancel 调用 DETAIL attemptId 找不到 Future
- **GREEN**: skeleton→detail→repair 转换时重新注册 Future
- **回归**: 29 tests pass
- **commit**: `bce0904`

### F5: 限流在状态改变之后
- **根因**: `retryDetail/regenerate` 先 `startGeneration` 再 `rateLimiter.check`
- **修改**: `TaskPlanCommandService.java` 先预检查状态，限流后再 startGeneration
- **RED**: rate limit 拒绝后 plan 留在 generating 状态
- **GREEN**: 限流在状态改变之前，无效请求不消耗额度
- **回归**: 29 tests pass
- **commit**: `857c6c9`

### F6: FAILED confirmation 新 key 500
- **根因**: 新 key INSERT 触发 `UNIQUE(plan_id)` 约束
- **修改**: `TaskPlanConfirmationService.java` INSERT 前检查任意已有 confirmation
- **RED**: FAILED 后换新 key 触发数据库唯一约束异常
- **GREEN**: 返回稳定 409 IDEMPOTENCY_KEY_REUSED
- **回归**: 29 tests pass
- **commit**: `857c6c9`

### H1: 终态清理 activeAttemptId
- **修改**: `TaskPlanRepository.java` fail() 和 appendVersion() 清空 active_attempt_id
- **GREEN**: READY/FAILED/DETAIL_GENERATION_FAILED/CANCELED 终态清空 active_attempt_id
- **回归**: 29 tests pass
- **commit**: `65b0755`

### H2: 队列拒绝按阶段归类
- **修改**: `TaskPlanGenerationOrchestrator.java` dispatch() 根据 detailOnly 选择失败状态
- **GREEN**: detail 阶段队列拒绝设 DETAIL_GENERATION_FAILED
- **回归**: 29 tests pass
- **commit**: `bce0904`

### H3: markRunning CAS
- **修改**: `TaskPlanRepository.java` markRunning 接受 planId/generationSeq/status 参数
- **GREEN**: 单个 UPDATE 同时匹配 attempt 和 plan 状态
- **回归**: 29 tests pass
- **commit**: `bce0904`

### H4: DetailModelOutput tempKey 校验
- **修改**: `TaskPlanGenerationOrchestrator.java` merge 前校验 tempKey 完整性
- **GREEN**: 重复、未知、缺失 tempKey 抛出 IllegalArgumentException
- **回归**: 29 tests pass
- **commit**: `bce0904`

### H5: milestone description
- **修改**: `PlanMilestone.java` 新增 description 字段；confirmation 写入 description
- **GREEN**: milestone.description 来自 draft.description
- **回归**: 29 tests pass
- **commit**: `bce0904`

### H7: PLANNING_ENABLED=false
- **修改**: `PlanningModelProperties.java` enabled 改为 Boolean；TaskPlanModelClient 修复回退逻辑
- **GREEN**: 显式 false 禁用，null 回退 CHAT_ENABLED，temperature 0.0 合法
- **回归**: 29 tests pass
- **commit**: `65b0755`

## 进行中

- [ ] H6: 来源和依赖校验（source ref 格式已修复，部分校验待完善）
- [ ] H8: 项目删除 stable 语义
- [ ] H9: 人工保存成员一致性
- [ ] OpenAPI 同步
- [ ] architecture.md / database.md 更新

## 未关闭

- [ ] H8: 项目删除前检查 confirmed plan
- [ ] H9: 人工保存事务一致性（FOR UPDATE 锁定成员）
- [ ] OpenAPI 与实现对齐
- [ ] architecture.md / database.md 更新为当前实现

## 当前验证结果

| 检查项 | 结果 |
|--------|------|
| 后端测试 | 29 pass, 0 fail, 0 error, 0 skip |
| 后端构建 | BUILD SUCCESS |
| Spring Boot 启动 | Started in 3.5s, Tomcat port 8080 |
| Health endpoint | UP |
| 受保护端点 | 401 |
| 前端 typecheck | PASS |
| 前端测试 | 21 pass (3 files) |
| 前端构建 | PASS |

## 下一步唯一动作

继续 H8/H9 修复，然后 OpenAPI 和文档同步。
