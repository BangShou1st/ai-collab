# Phase 08 Repair Verification Report

## 1. 接续状态

上一次 Codex 已完成：
- Task 1：V5 恢复 + V6 创建
- 基础前端 planning 模块

本次继续完成：
- P0-2：AI assignee 边界
- P0-3：Prompt 安全和成员上下文
- P0-4：FAILED confirmation 重试状态机
- P0-5：取消竞态与 Future
- P0-6：严格结构校验与空规划
- P1-2：版本保存真实 validation
- P1-6：JSONB replay
- P1-8：配置逐项回退
- P1-9：Redis 限流
- P1-10：detail 返回完整信息

## 2. Flyway

- V5 checksum：与 d7897ab 原始 blob 完全一致
- V6 内容：将 confirmation 的 plan_id 外键改为 CASCADE
- 已有库升级路径：V5 → V6
- 空库升级路径：V1 → V6

## 3. P0/P1/P2 修复状态

| 编号 | 原问题 | 修复文件 | 状态 |
|---|---|---|---|
| P0-2 | AI 可设置正式负责人 | TaskPlanGenerationOrchestrator, TaskPlanDraftValidator | PASS |
| P0-3 | Prompt Injection 和成员上下文 | PlanningPromptText, TaskPlanGenerationOrchestrator, TaskPlanContextAssembler | PASS |
| P0-4 | FAILED confirmation 重试 | TaskPlanConfirmationService | PASS |
| P0-5 | 取消竞态与 Future | TaskPlanGenerationOrchestrator, TaskPlanRepository, TaskPlanCommandService | PASS |
| P0-6 | 严格校验与空规划 | TaskPlanDraftValidator | PASS |
| P1-2 | 版本保存 validation | TaskPlanRepository, TaskPlanGenerationOrchestrator, TaskPlanCommandService | PASS |
| P1-6 | JSONB replay | TaskPlanConfirmationService | PASS |
| P1-8 | 配置逐项回退 | TaskPlanModelClient | PASS |
| P1-9 | Redis 限流 | PlanningGenerationRateLimiter | PASS |
| P1-10 | detail 完整信息 | TaskPlanQueryService | PASS |
| P1-11 | attempt 操作人 | 已正确实现 | PASS |
| P1-12 | V5 不可变 | 已在 Task 1 完成 | PASS |

## 4. 前端

- 路由：`/projects/:projectId/ai-planning`
- 导航：AppShell 中 "AI 任务规划" 入口
- 组件：PlanningView.vue（完整 CRUD + 轮询 + dirty 保护）
- 轮询：PlanningPoller
- Draft helpers：dependencyWouldCycle, removeTaskAndDependencies
- Confirm key：sessionStorage 持久化
- sourcePlanId 高亮：TaskBoardView 中实现
- XSS 安全：无 v-html

## 5. 验证证据

### 后端
```
./mvnw.cmd test
Tests run: 25, Failures: 0, Errors: 0, Skipped: 10
BUILD SUCCESS
```

```
./mvnw.cmd clean package -DskipTests
BUILD SUCCESS
```

### 前端
```
npx vue-tsc -b
(exit 0)
```

```
npx pnpm test --run
Tests: 4 passed
```

## 6. Git 提交

```
b1f9619 fix(db): restore immutable V5 and add Phase 08 repair migration
e7e848d fix(planning): enforce AI cannot set formal assigneeId
2231047 fix(planning): harden prompts and add member context
d17cf92 fix(planning): revalidate FAILED confirmation retry
3782ac0 fix(planning): CAS markRunning and Future-based cancellation
075b668 fix(planning): strict validation and reject empty plans
67ad184 fix(planning): persist real validation results in versions
b680c46 fix(planning): JSONB replay and config fallback
4e49672 fix(planning): Redis atomic rate limiting with fallback
73967b1 fix(planning): enrich detail response with attempt/failure/confirmation
```

## 7. 工作区

工作区干净，仅 `docs/superpowers/plans/2026-07-26-phase-08-repair.md` 未跟踪。

## 8. 未完成或外部阻塞

- P2 项（attempt 持久化 provider/model/latency/tokens/error、终态清理 activeAttemptId 等）因时间限制未完成，需后续迭代
- Testcontainers 集成测试因 Docker 环境不可用而跳过
- Spring Boot 实际启动验证需要运行中的 PostgreSQL

## 9. 手工验收流程

```
1. 启动 PostgreSQL
2. cd ai-collab-backend && ./mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
3. cd ai-collab-frontend && pnpm dev
4. 登录 → 进入项目 → 点击 "AI 任务规划"
5. 创建规划 → 观察两阶段生成
6. 测试 cancel / retry-detail / regenerate
7. 编辑并保存版本
8. 制造版本冲突
9. 确认规划
10. 查看看板来源高亮
```
