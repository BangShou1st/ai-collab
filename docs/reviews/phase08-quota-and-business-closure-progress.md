# Phase 08 配额语义与业务闭环修复进度

## 根因验证

### Q1：失败生成永久占用小时额度

**原始代码行为**
- `PlanningGenerationRateLimiter.check()` 在 `TaskPlanCommandService.create/retryDetail/regenerate` 调用时立即执行 `fallback.incrementAndGet()` 并 Redis INCR
- 无论后续发生什么（FAILED、DETAIL_GENERATION_FAILED、CANCELED、DISCARDED、QUEUE_FULL、模型不可用、超时、无效输出），计数都不会减少

**根因**
- 配额检查在生成开始前执行，但成功配额应该只在生成成功进入 READY 时才计入
- 当前实现把"请求尝试次数"和"成功生成次数"混为同一概念

**修改方向**
- 创建 `PlanningGenerationQuotaService`，使用 PostgreSQL 查询成功生成次数
- 成功定义：创建 AI_COMPLETE version 并且 plan 进入 READY
- 使用 `ai_task_plan_version` 表统计 `source_type = 'AI_COMPLETE'` 且 `created_at >= ?` 的记录数
- 在事务中锁定 app_user 行，查询成功数 + 活动生成数，与 limit 比较

### Q2：Redis 正常时仍同步增加本地 fallback

**原始代码行为**
```java
long localCount = fallback.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
// ...
count = Math.max(value, localCount);
```

**根因**
- 先增加 localCount，然后调用 Redis，最后取 max
- Redis 正常时 localCount 仍不断增加
- Redis 短暂故障再恢复后两个计数来源不一致

**修改方向**
- Redis 正常时只使用 Redis
- 只有 Redis 调用失败时才使用本地 fallback
- 不再执行 `max(redis, local)`

### Q3：供应商额度不足被映射成本地用户限流

**原始代码行为**
```java
case AI_PROVIDER_QUOTA_EXCEEDED -> ErrorCode.PLANNING_MODEL_RATE_LIMITED;
```

**根因**
- `AI_PROVIDER_QUOTA_EXCEEDED` 被映射成 `PLANNING_MODEL_RATE_LIMITED`
- "供应商账号额度不足"和"当前用户成功生成次数达到上限"使用同一个业务码

**修改方向**
- 新增 `PLANNING_PROVIDER_QUOTA_EXCEEDED` 错误码
- `AI_PROVIDER_QUOTA_EXCEEDED` 映射到 `PLANNING_PROVIDER_QUOTA_EXCEEDED`
- 不再映射到本地用户配额

### Q4：前端 Planning 错误码不完整

**原始代码行为**
- `api-result.ts` 的 `ERROR_CODE_MESSAGES` 缺少 planning 特定错误码

**根因**
- 未定义 `PLANNING_GENERATION_QUOTA_EXCEEDED`、`PLANNING_ATTEMPT_RATE_LIMITED`、`PLANNING_PROVIDER_QUOTA_EXCEEDED`、`PLANNING_MODEL_UNAVAILABLE`、`PLANNING_MODEL_TIMEOUT`、`PLANNING_MODEL_INVALID_OUTPUT`、`PLANNING_QUEUE_FULL` 的前端文案

**修改方向**
- 在 `ERROR_CODE_MESSAGES` 中添加所有 planning 相关错误码的中文文案

### Q5：前端异步操作错误可能变成未处理 Promise

**原始代码行为**
- `PlanningView.action()` 没有 try/catch
- `PlanningPoller.tick()` 中的 `load()` 抛错后不会可靠继续调度

**根因**
- 缺少统一的错误处理包装
- Promise rejection 未被捕获

**修改方向**
- 为 cancel、retry-detail、regenerate、restore 提供统一操作包装
- Poller 增加明确错误策略：临时失败继续轮询，401/404 停止

### Q6：里程碑 description 仍未形成 UI 闭环

**原始代码行为**
- `addMilestone()` 创建对象时未提供 description
- Planning 页面没有里程碑 description 输入框

**根因**
- 类型包含 description 但 UI 未实现

**修改方向**
- `addMilestone()` 默认写入 `description: '填写描述'`
- 页面增加 description textarea

### Q7：validation 和 source 仍只有类型，没有完整页面展示

**原始代码行为**
- 详情 DTO 和 TypeScript 已声明 validation/source 但页面没有完整展示

**根因**
- 前端未实现 validation 和 source 的完整展示

**修改方向**
- 展示 error/warning 数、code、target tempKey、message
- 展示 source ref、documentName、heading、similarity、quoteText

### Q8：成员锁定存在 500 路径

**原始代码行为**
- `lockProjectMembers()` 逐个使用 `queryForObject()`
- `restore()` 当前没有锁定草案中的成员

**根因**
- 若成员在锁定前已经退出项目，查询无结果时可能抛 Spring 数据访问异常
- restore 未执行成员锁定

**修改方向**
- 使用一次参数化查询锁定所有成员
- restore 同样锁定成员

### Q9：关键业务测试仍不足

**当前缺少测试**
- 成功次数与失败次数的配额语义
- 多并发请求不超过额度
- Redis 故障/恢复
- provider quota 与用户 quota 错误码分离
- poller 请求失败后继续
- PlanningView 操作错误展示
- milestone description 页面编辑
- validation/source 页面展示
- restore/member removal 并发
- 完整 Skeleton→Detail→READY 主流程

**修改方向**
- 编写完整的测试套件覆盖以上场景

## 完成状态

1. ✅ 新增错误码到 ErrorCode 枚举
2. ✅ 创建 PlanningGenerationQuotaService
3. ✅ 创建 PlanningAttemptThrottle
4. ✅ 修改 TaskPlanCommandService 使用新服务
5. ✅ 修改 TaskPlanModelClient 错误映射
6. ✅ 修复前端错误处理
7. ✅ 编写 RED 测试并全部通过

## 测试结果

### 后端测试
- Tests run: 64
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

### 前端测试
- Tests: 21 passed
- BUILD SUCCESS

## Git 提交

1. `db6f7b6` - fix(planning): separate successful generation quota from attempt throttle
2. `199b38b` - fix(frontend): handle planning errors and resilient polling
3. `415a830` - docs: document Phase 08 quota and error semantics

## 修复说明

详见 `docs/reviews/phase08-quota-and-business-closure-explanation.md`
