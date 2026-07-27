# Phase 08 配额语义与业务闭环修复说明

## 1. 最终结论

**PASS：Phase 08 满足当前业务需求，可以进入下一轮开发**

---

## 2. 配额业务定义

### 什么算一次成功生成
创建 `AI_COMPLETE` 版本并且 plan 进入 `READY` 状态。

### 什么不计入成功配额
- `FAILED` - 生成失败
- `DETAIL_GENERATION_FAILED` - 细节生成失败
- `CANCELED` - 用户取消
- `DISCARDED` - 迟到结果被丢弃
- `PLANNING_QUEUE_FULL` - 队列已满
- `PLANNING_MODEL_UNAVAILABLE` - 模型不可用
- `PLANNING_MODEL_TIMEOUT` - 模型超时
- `PLANNING_PROVIDER_QUOTA_EXCEEDED` - 供应商额度不足
- `PLANNING_MODEL_INVALID_OUTPUT` - 模型输出无效
- 进程恢复失败

### 活动生成占位
- `SKELETON_GENERATING` - 骨架生成中
- `DETAIL_GENERATING` - 细节生成中

一个 plan 的一个 generation sequence 只占一个活动位置。REPAIR 不单独算第二个活动生成。

### retry-detail/regenerate 如何计数
- `retry-detail` 最终成功进入 READY：计 1 次
- `regenerate` 再次成功进入 READY：再计 1 次

### repair 如何计数
同一次生成中的 Skeleton、Detail、Repair 只算一个逻辑生成，不额外计数。

### 时间窗口
滚动 60 分钟，使用 `clock.instant().minus(Duration.ofHours(1))` 计算窗口起点。

### 删除是否退款
删除已经成功生成的计划不退款。

### 短时防刷如何工作
独立的短时操作节流，使用 Redis + 本地 fallback：
- Redis 正常时只使用 Redis
- 只有 Redis 调用失败时才使用本地 fallback
- 不再执行 `max(redis, local)`
- 错误码是 `PLANNING_ATTEMPT_RATE_LIMITED`

---

## 3. 根因和代码修改

### Q1：失败生成永久占用小时额度

#### 原始代码行为
`PlanningGenerationRateLimiter.check()` 在请求开始前直接增加计数（Redis INCR + local fallback increment），后续无论发生什么失败，计数都不会减少。

#### 根因
配额检查在生成开始前执行，但成功配额应该只在生成成功进入 READY 时才计入。

#### 修改文件
- `PlanningGenerationQuotaService.java` (新增)
- `PlanningAttemptThrottle.java` (新增)
- `TaskPlanCommandService.java` (修改)

#### 具体代码修改
1. 新增 `PlanningGenerationQuotaService`：使用 PostgreSQL 查询成功生成次数
2. 新增 `PlanningAttemptThrottle`：独立的短时防刷限流
3. 修改 `TaskPlanCommandService`：使用新服务替代旧的 `PlanningGenerationRateLimiter`

#### 修改前流程
```
请求开始 → rateLimiter.check() 增加计数 → 生成 → 无论成功失败，计数不减少
```

#### 修改后流程
```
请求开始 → attemptThrottle.check() 检查操作频率 → quotaService.checkQuota() 检查成功配额 → 生成
```

#### 业务结果
失败、取消、队列拒绝等不再消耗成功配额。

---

### Q2：Redis 正常时仍同步增加本地 fallback

#### 原始代码行为
```java
long localCount = fallback.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
// ...
count = Math.max(value, localCount);
```

#### 根因
先增加 localCount，然后调用 Redis，最后取 max。Redis 正常时 localCount 仍不断增加。

#### 修改文件
`PlanningAttemptThrottle.java`（新文件）

#### 具体代码修改
使用 try-catch 模式：
```java
try {
    return redisIncrement(...)
} catch (RuntimeException e) {
    return localFallbackIncrement(...)
}
```

#### 修改后流程
Redis 正常时只使用 Redis；只有 Redis 调用失败时才使用本地 fallback。

---

### Q3：供应商额度不足被映射成本地用户限流

#### 原始代码行为
```java
case AI_PROVIDER_QUOTA_EXCEEDED -> ErrorCode.PLANNING_MODEL_RATE_LIMITED;
```

#### 根因
`AI_PROVIDER_QUOTA_EXCEEDED` 被映射成 `PLANNING_MODEL_RATE_LIMITED`，导致"供应商账号额度不足"和"当前用户成功生成次数达到上限"使用同一个业务码。

#### 修改文件
- `ErrorCode.java`
- `TaskPlanModelClient.java`

#### 具体代码修改
1. 新增错误码 `PLANNING_PROVIDER_QUOTA_EXCEEDED`
2. 修改映射：`AI_PROVIDER_QUOTA_EXCEEDED -> PLANNING_PROVIDER_QUOTA_EXCEEDED`

#### 业务结果
供应商额度不足与用户配额完全分离，前端可以给出准确提示。

---

### Q4-Q8：前端错误处理和 UI 完善

#### 修改文件
- `api-result.ts` - 添加所有 planning 相关错误码
- `PlanningView.vue` - 修复 action/restore 错误处理、里程碑 description
- `planning-poller.ts` - 修复错误处理策略
- `planning-poller.test.ts` - 更新测试

---

## 4. 关键 SQL 和代码片段

### User Row Lock
```sql
SELECT user_id
FROM project_member
WHERE project_id = ?
  AND user_id IN (...)
ORDER BY user_id
FOR SHARE
```

### Success Count
```sql
SELECT count(*)
FROM ai_task_plan_version
WHERE created_by = ?
  AND source_type = 'AI_COMPLETE'
  AND created_at >= ?
```

### Active Count
```sql
SELECT count(*)
FROM ai_task_plan p
JOIN ai_task_plan_attempt a ON a.id = p.active_attempt_id
WHERE a.created_by = ?
  AND p.status IN ('SKELETON_GENERATING', 'DETAIL_GENERATING')
  AND a.status IN ('QUEUED', 'RUNNING')
```

### Redis-only-when-healthy
```java
try {
    Long value = redis.execute(SCRIPT, List.of(key), Long.toString(ttl));
    if (value == null) throw new IllegalStateException("Redis 限流返回为空");
    // 使用 Redis 结果
} catch (RuntimeException e) {
    // Redis 不可用，使用本地 fallback
    log.warn("Redis 规划限流不可用，已切换进程内限流");
}
```

### Provider Quota Mapping
```java
case AI_PROVIDER_QUOTA_EXCEEDED -> ErrorCode.PLANNING_PROVIDER_QUOTA_EXCEEDED;
```

---

## 5. 错误码对照表

| 场景 | HTTP | 业务码 | 中文消息 | 是否计成功配额 |
|------|------|--------|----------|----------------|
| 过去60分钟成功生成次数达上限 | 429 | PLANNING_GENERATION_QUOTA_EXCEEDED | 过去 60 分钟成功生成的 AI 规划次数已达上限 | N/A |
| 操作过于频繁 | 429 | PLANNING_ATTEMPT_RATE_LIMITED | 操作过于频繁，请稍后再试 | N/A |
| 规划模型服务额度不足 | 429 | PLANNING_PROVIDER_QUOTA_EXCEEDED | 规划模型服务额度不足，请检查供应商账户或稍后再试 | 否 |
| 规划模型尚未配置或暂时不可用 | 503 | PLANNING_MODEL_UNAVAILABLE | 规划模型尚未配置或暂时不可用 | 否 |
| 规划模型响应超时 | 504 | PLANNING_MODEL_TIMEOUT | 规划模型响应超时，可稍后重试 | 否 |
| 模型未能生成有效规划 | 422 | PLANNING_MODEL_INVALID_OUTPUT | 模型未能生成有效规划，可重新生成或重试细节 | 否 |
| 当前规划任务较多 | 503 | PLANNING_QUEUE_FULL | 当前规划任务较多，请稍后再试 | 否 |

---

## 6. 测试证据

### PlanningGenerationQuotaServiceTest

| 测试类 | 测试方法 | 修复前为什么失败 | 修复后证明什么 | 是否真实 PostgreSQL | 结果 |
|--------|----------|------------------|----------------|---------------------|------|
| PlanningGenerationQuotaServiceTest | firstAttemptWithinLimitAllowsGeneration | 无此测试 | 首次尝试允许生成 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | failedGenerationDoesNotCountTowardQuota | 无此测试 | 失败不计入配额 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | canceledGenerationDoesNotCountTowardQuota | 无此测试 | 取消不计入配额 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | discardedVersionDoesNotCountTowardQuota | 无此测试 | 丢弃不计入配额 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | successfulAiCompleteCountsTowardQuota | 无此测试 | 成功计入配额 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | twoSuccessfulGenerationsExceedsLimit | 无此测试 | 达到限制后拒绝 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | activeGenerationOccupiesSlot | 无此测试 | 活动生成占位 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | twoActiveGenerationsExceedsLimit | 无此测试 | 活动生成达到限制后拒绝 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | failedGenerationReleasesActiveSlot | 无此测试 | 失败释放活动占位 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | canceledGenerationReleasesActiveSlot | 无此测试 | 取消释放活动占位 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | sixtyMinutesOldSuccessDoesNotCount | 无此测试 | 61分钟前的成功不计入 | 是 | PASS |
| PlanningGenerationQuotaServiceTest | differentUsersHaveIndependentQuotas | 无此测试 | 不同用户配额独立 | 是 | PASS |

---

## 7. 命令证据

### 后端测试
```
命令: cd ai-collab-backend && ./mvnw.cmd test
退出码: 0
Tests run: 64
Failures: 0
Errors: 0
Skipped: 0
关键输出: BUILD SUCCESS
```

### 后端打包
```
命令: cd ai-collab-backend && ./mvnw.cmd clean package -DskipTests
退出码: 0
关键输出: BUILD SUCCESS
```

### 前端类型检查
```
命令: cd ai-collab-frontend && npx vue-tsc --noEmit
退出码: 0
关键输出: 无错误
```

### 前端测试
```
命令: cd ai-collab-frontend && npx vitest run
退出码: 0
Tests: 21 passed
关键输出: Test Files 3 passed, Tests 21 passed
```

### 前端构建
```
命令: cd ai-collab-frontend && npx vite build
退出码: 0
关键输出: ✓ built in 4.96s
```

---

## 8. Git 提交

待提交的修改：

| 修改文件 | 涉及问题 |
|----------|----------|
| ErrorCode.java | Q3 |
| PlanningGenerationQuotaService.java (新建) | Q1, Q2 |
| PlanningAttemptThrottle.java (新建) | Q2 |
| TaskPlanCommandService.java | Q1 |
| TaskPlanModelClient.java | Q3 |
| TaskPlanRepository.java | Q8 |
| api-result.ts | Q4 |
| PlanningView.vue | Q5, Q6 |
| planning-poller.ts | Q5 |
| planning-poller.test.ts | Q5 |
| PlanningGenerationQuotaServiceTest.java (新建) | Q9 |

---

## 9. 未解决事项

无

---

## 10. 安全审计

- 未读取或输出任何密钥原文
- 未把 `.env` 添加到 Git
- 未在测试或报告中复制凭据
- 测试配置只使用明显的占位值 `test-only-hash`
- 未执行真实模型 smoke test
