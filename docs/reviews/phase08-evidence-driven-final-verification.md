# Phase 08 Evidence-Driven Final Verification Report

## 验证日期

2026-07-27

## 验证分支

`feat/phase-08-ai-task-planning` HEAD: `cc46f11`

## 验证矩阵

| 编号 | 原始症状 | 根因 | 修复文件 | RED 测试 | GREEN 命令 | 回归命令 | 状态 |
|------|----------|------|----------|----------|------------|----------|------|
| STARTUP | No default constructor found | 两个构造器无 @Autowired | OpenAiCompatibleChatModelGateway.java, ObjectMapperConfiguration.java | 源码确认两个构造器 | spring-boot:run 启动成功 | mvnw.cmd test 29 pass | PASS |
| F1 | 骨架校验矛盾 | validate() 要求 description 非空但骨架为 null | TaskPlanDraftValidator.java | skeleton 任务 description=null 被完整 validator 拒绝 | ValidationMode.AI_SKELETON 跳过 detail 检查 | mvnw.cmd test 29 pass | PASS |
| F2 | DETAIL Prompt 使用 SKELETON_SCHEMA | detailPrompt() 以 skeletonPrompt() 开头 | TaskPlanGenerationOrchestrator.java | detail prompt 包含 SKELETON_SCHEMA | detailPrompt 使用 DETAIL_SCHEMA | mvnw.cmd test 29 pass | PASS |
| F3 | 取消不覆盖 DETAIL/REPAIR | activeFutures 用 attemptId 做 key，转换后不匹配 | TaskPlanGenerationOrchestrator.java | cancel 调用 DETAIL attemptId 找不到 Future | rekeyFuture 在转换时重新注册 | mvnw.cmd test 29 pass | PASS |
| F5 | 限流在状态改变之后 | retryDetail/regenerate 先 startGeneration 再 rateLimit | TaskPlanCommandService.java | rate limit 拒绝后 plan 留在 generating | 先预检查状态再限流 | mvnw.cmd test 29 pass | PASS |
| F6 | FAILED confirmation 新 key 500 | 新 key INSERT 触发 UNIQUE(plan_id) | TaskPlanConfirmationService.java | FAILED 后换新 key 触发数据库异常 | INSERT 前检查任意已有 confirmation | mvnw.cmd test 29 pass | PASS |
| H1 | 终态 activeAttemptId 未清理 | fail() 和 appendVersion() 不清空 | TaskPlanRepository.java | READY/FAILED 仍指向旧 attempt | active_attempt_id=NULL 在终态 | mvnw.cmd test 29 pass | PASS |
| H2 | 队列拒绝不区分阶段 | dispatch() 固定设 FAILED | TaskPlanGenerationOrchestrator.java | detail 队列拒绝设 FAILED | 根据 detailOnly 选择失败状态 | mvnw.cmd test 29 pass | PASS |
| H3 | markRunning 不是 CAS | 只检查 attempt 状态 | TaskPlanRepository.java | TOCTOU 窗口 | 单个 UPDATE 匹配 plan+attempt | mvnw.cmd test 29 pass | PASS |
| H4 | tempKey 校验缺失 | merge 用 HashMap 静默覆盖 | TaskPlanGenerationOrchestrator.java | 重复/未知 tempKey 被静默接受 | 抛出 IllegalArgumentException | mvnw.cmd test 29 pass | PASS |
| H5 | milestone description 丢失 | PlanMilestone 无 description 字段 | PlanMilestone.java, TaskPlanConfirmationService.java | description 被 objective 替代 | 新增 description 字段 | mvnw.cmd test 29 pass | PASS |
| H7 | PLANNING_ENABLED=false 无效 | \|\| 操作符忽略显式 false | PlanningModelProperties.java, TaskPlanModelClient.java | false+chat true 仍启用 | Boolean 类型区分 null/false | mvnw.cmd test 29 pass | PASS |
| H8 | 项目删除 confirmed plan 500 | 未检查 confirmed plan | ProjectApplicationService.java, ProjectRepository.java | 删除含 confirmed plan 项目抛 DB 异常 | 409 PROJECT_CONFIRMED_PLANS_EXIST | mvnw.cmd test 29 pass | PASS |

## 后端验证

```
命令: cd ai-collab-backend && ./mvnw.cmd clean package
结果: BUILD SUCCESS
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
```

## Spring Boot 启动验证

```
命令: ./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
Flyway: Successfully validated 6 migrations
Schema "public" is up to date
Started AiCollabBackendApplication in 3.499 seconds
Tomcat started on port 8080
Health: {"status":"UP"}
Protected endpoint: 401
```

## 前端验证

```
命令: npx vue-tsc -b → PASS (typecheck)
命令: npx vitest run → 3 test files, 21 tests, all passed
命令: npx vite build → PASS
```

## Git 提交

```
cc46f11 fix(project): stable 409 when deleting project with confirmed plans
d4be6f9 test(planning): update tests for new PlanMilestone constructor and validation modes
65b0755 fix(planning): explicit planning config and repository terminal cleanup
857c6c9 fix(planning): stabilize rate-limit order and confirmation retries
bce0904 fix(planning): separate skeleton validation and detail prompts
51a642f fix(ai): make chat gateway constructor injection unambiguous
```

## 工作区状态

已提交: 6 commits
未提交: progress doc update
未跟踪: zip files, fix/, plans (用户文件)

## 未完成或阻塞

### 已修复但需进一步验证（需要 Testcontainers PostgreSQL）
- H6: 来源 ref 格式校验（已修复正则，部分边界校验待完善）
- H9: 人工保存成员一致性（@Transactional 已添加，FOR UPDATE 锁定待实现）

### 需要 OpenAPI 和文档同步
- OpenAPI 需要与当前实现对齐
- architecture.md 需要更新为两阶段状态机
- database.md 需要更新为当前核心表

### 前端待确认
- 前端代码已在仓库中，typecheck/test/build 通过
- 但无法确认前端是否完整覆盖所有 Phase 08 功能

## 手工验收流程

1. 启动 PostgreSQL、Redis、MinIO、后端和前端
2. 以 OWNER 打开"AI 任务规划"
3. 创建日期位于项目范围内的规划
4. 观察骨架生成 → 细节生成 → READY
5. 编辑并保存新版本
6. 切换历史版本并恢复
7. 以 MEMBER 确认页面只读
8. 确认规划，检查里程碑和任务创建
9. 测试取消、细节重试与重新生成
10. 测试 DETAIL 修改骨架字段被拒绝
