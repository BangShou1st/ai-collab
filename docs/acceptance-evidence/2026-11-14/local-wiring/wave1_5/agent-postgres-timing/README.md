# Agent PostgreSQL 时钟口径与测试调度收口（Wave 1.5 / Lane C）

- 日期：2026-11-14
- 基线：`dev @ d6982958`
- 分支：`fix/agent-postgres-timing`
- Worktree：`E:\project\ai-collab-wt\fix-agent-postgres-timing`
- 状态：`IMPLEMENTED` / `TESTED(UNIT)` / `TESTED(INTEGRATION)` / `MEASURED_LOCAL`

## 结论

1. `AgentRepository.claimNext` 原先同时使用两套时钟：JVM `OffsetDateTime.now()` 用于
   租约过期、重试可领取时间与写入时间戳；PostgreSQL `now()` 用于 claim 起点、委派受理
   释放租约（`lease_expires_at=now()`）等状态。容器/宿主时钟出现秒级偏差时，已可领取
   的运行会被误判为租约仍有效，四个时序用例在 `orElseThrow()` 处得到空结果。
2. 测试中的后台调度噪音来自 Spring TestContext 缓存：Testcontainers 类结束后容器被回收，
   但缓存上下文仍存活，`AgentRuntimeJob` 继续按秒 tick 并对已关闭端口取连接。现通过
   `agent.scheduling-enabled`（默认 true）在测试 profile 关闭动态调度，同时保留
   `agent.runtime-allowed=true`、`agentRunTaskExecutor` 与全部业务 Bean。

## 根因证据

### 时钟偏差

`raw/clock-skew-measurement.txt` 记录了对运行中 PostgreSQL 容器的紧窗口测量：

```text
container_minus_host_midpoint_ms=5074
exec_window_ms=178
```

容器时钟在 178ms 的测量窗口内比 Windows 宿主快约 5.07 秒。另一次 `docker run`
启动窗口测量显示约 1.9 秒偏差。偏差随 Docker Desktop/WSL2 负载漂移，不是固定的
时区或 JDBC 转换问题。

### 失败形态

修复前定向复跑四个类：

```text
Tests run: 82, Failures: 0, Errors: 1, Skipped: 0
AgentCrashRecoveryTimingPostgresTest.normalRoundEndAndRetryWaitUseTheSameAccumulation:184
java.util.NoSuchElementException: No value present
```

原始日志：`raw/targeted-before-fix.txt`，exit=1。

对应构造：

- `AgentCrashRecoveryTimingPostgresTest` 把 `retry_after` 写成数据库 `now()-1s`，claim
  用 JVM 时间比较；数据库快约 5 秒时仍判为“重试窗口未到”。
- `AgentDelegationLedgerPostgresTest` / `AgentDelegationPostgresTest` 的委派受理用
  数据库 `now()` 释放父租约，claim 用 JVM 时间判断 `lease_expires_at < now`。
- `AgentRemoteOutcomePostgresTest.t07` 同样用数据库 `now()-1s` 构造过期租约。

## 改动

### 统一 claim 时钟

- `AgentRepository.claimNext(String workerId, Duration lease)`：候选判定、租约到期、
  `FAILED_RETRYABLE.retry_after`、`claim_started_at`、`started_at`、`updated_at` 与
  `lease_expires_at` 全部使用 PostgreSQL `now()`；租约窗口以
  `now()+?::interval` 写入。
- `AgentRecoveryJob.claim` 不再构造 JVM 时间，直接调用新签名。
- 所有测试调用点同步移除时间参数；依赖未来时间的两个用例改为直接构造数据库租约状态：
  `AgentDualInstanceClaimPostgresTest` 与 `AgentRepositoryIntegrationTest` 的
  `expiredRunningLeaseCanBeReclaimed`。

### 测试调度收口

- `AgentDynamicScheduleRegistrar` 新增 `agent.scheduling-enabled`（默认 true，字段初值
  true 以兼容直接构造的单元测试）；为 false 时 `start()` 跳过 Worker 轮询与规划恢复注册。
- `application.yml` 暴露 `AGENT_SCHEDULING_ENABLED`，缺省 true。
- `src/test/resources/application.properties` 置 `agent.scheduling-enabled=false`；
  `agent.runtime-allowed` 保持默认 true，执行器与业务 Bean 完整装配。
- 曾尝试全局 `agent.runtime-allowed=false`，该方案会同时删除 `agentRunTaskExecutor`
  并导致 153 个上下文错误，已弃用；摘要保留在
  `raw/full-verify-superseded-runtime-switch-summary.txt`。

## 验证结果

| 范围 | 事实 | 原始证据 |
| --- | --- | --- |
| 修复前 4 个时序类 | `Tests run: 82, Failures: 0, Errors: 1`，exit=1 | `raw/targeted-before-fix.txt` |
| 修复后 4 个时序类 round 1 | `Tests run: 82, Failures: 0, Errors: 0`，exit=0 | `raw/targeted-after-fix-round1.txt` |
| 修复后 6 类反向顺序 | `Tests run: 124, Failures: 0, Errors: 0`，exit=0 | `raw/targeted-after-fix-round2.txt` |
| 修复后 6 类随机顺序（seed=20261114） | `Tests run: 124, Failures: 0, Errors: 0`，exit=0 | `raw/targeted-after-fix-round3-random.txt` |
| 3 个上下文类 + 原 4 个时序类 + 仓储/双实例 | `Tests run: 131, Failures: 0, Errors: 0`，exit=0 | `raw/targeted-after-scheduling-fix.txt` |
| 调度注册器/运行时单元 | `Tests run: 18, Failures: 0, Errors: 0`，exit=0 | `raw/scheduling-unit-after-fix.txt` |
| 后端完整 verify | `Tests run: 3121, Failures: 0, Errors: 0, Skipped: 61`，exit=0，23:38 min | `raw/full-verify-final.txt` |

完整 verify 中 `AgentRuntimeJob.tick` 出现 0 次；`agent.scheduling-enabled=false` 跳过注册
日志 31 次。最终日志仍有无测试影响的非本 lane 噪音：`CannotGetJdbcConnectionException`
19 次，来源为 `SystemConfigService` 动态配置同步与 `ConnectorOAuthService.refreshDue`
等非 Agent 调度任务，以及 Redis 重连告警；没有 Agent Worker tick 连接死库的堆栈。

## 迁移与配置

- Flyway 仍为 V1–V101，未新增或修改迁移。
- `agent.runtime-allowed` 默认仍为 true；`agent.scheduling-enabled` 默认 true。
- 测试 profile 仅关闭动态调度，不改变生产调度、租约、审批与执行器语义。

## 遗留风险

- 非 Agent 的缓存上下文调度器（系统配置同步、Connector OAuth 刷新等）在容器回收后仍会
  产生连接告警日志；本轮未扩大范围处理。
- `AgentRuntimeJob.tick` 的审批过期收口仍以 JVM 时间作为参数
  （`AgentApprovalRepository.expirePending`）。本轮只统一了 claim 路径；该路径在真实
  生产环境仍受 JVM/数据库时钟偏差影响，建议后续单独评估是否改为数据库时钟。
- 未执行真实进程/浏览器验收：本 lane 只涉及后端仓储时钟与测试调度，`NOT_RUN`。
