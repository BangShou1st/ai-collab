# P6-AGENT-OBSERVABILITY-01 --- Agent 可观测性与可靠性诊断设计

> **状态：IMPLEMENTED / 验收以交付报告与最终 SHA CI 回执为准**\
> 基线：`dev@0e489824358046443429ad32d53b7143c51979cc`；`main@9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`\
> 日期：2026-10-25\
> 范围：Agent Runtime 指标、持久化状态快照、异步日志关联和诊断 Runbook。


## 实现说明（2026-10-25）

以下保留原设计的指标定义和验收矩阵；实际实现优先遵守本节。

- `AgentRuntimeStateRepository` 用只读 REPEATABLE_READ 事务读取三组事实；`AgentRuntimeStateMetrics` 默认每 30000ms 刷新，配置项为 `agent.diagnostics.snapshot-delay-ms`。无成功快照时所有事实 Gauge 与 age 返回 NaN；失败保留 last-good，告警日志最多每五分钟一次，恢复记录一次。UNKNOWN 年龄及快照年龄随时间增长，不因刷新失败归零。
- V68 只添加 UNKNOWN 部分表达式索引，不修改 V1–V67 或任何业务数据。UNKNOWN count/min 与索引采用同一 COALESCE 表达式。
- Worker attempt 是一次领取后的 `process` 调用，含正常、异常、暂停、取消与等待控制状态；额外固定 outcome 为 `waiting_for_approval/waiting_for_user_input/queued`。等待不是 Run 成功终态。
- 模型请求由 Routing 作用域控制，仅在 JsonHttpModelClient/Kilo 传输层确认开始出站之后计时，主轮次与摘要分别为 turn/summary。配置解析、能力拒绝、密钥解密、SSRF 拒绝以及尚未出站的执行器拒绝在计数边界之外；Legacy 的响应解析错误归 invalid_response。无 Token Counter，不改变原有用量账本。
- 未出站且数据库尚无 execution_safety 的结果使用保守默认 UNCONFIRMABLE_SIDE_EFFECT 标签，不按工具名推断安全类别；不会回写或改变出站证据。
- Invocation Counter 只接受 `UPDATE ... RETURNING` 的实际转换行；包含普通结果、委派成功、取消、批次 SKIPPED 及不确定出站 UNKNOWN。事务使用 afterCommit，回滚/旧结果读取/覆盖拒绝不增加 Counter。
- Invocation Timer 对当前 Worker 已提交 Scheduler 的调用，从提交前起算至结果事务 afterCommit；线程局部计时账本在 Worker 退出清理。未进入 Scheduler 的拒绝/跳过/审批/委派、崩溃恢复时无法得知原提交单调时间的收口，只计结果 Counter，不制造 0 秒 Timer 样本。Timer 的样本数因此可少于 Counter；持久化 UNKNOWN 年龄补充跨重启诊断。这是避免将等待恢复时间冒充本次执行耗时的明确口径细化。
- Counter/Timer 随 JVM 重启重置；Gauge 来自数据库快照。没有 exporter、scrape target 或告警通道。
- `AgentDiagnosticScope` 显式设置 run/claim/invocation 关联；进入后台作用域不传播 HTTP MDC，退出恢复调用线程原上下文，支持嵌套与线程复用。控制台保留 requestId pattern；后台 requestId 默认 none。
- 不修改 Scheduler 算法、UNKNOWN/重试/fencing、审批策略、Actuator 暴露与认证配置，不新增前端 UI 或匿名诊断接口。

交付报告：[P6-C 交付](../archive/reports/p6-agent-observability-01-delivery-20261025.md)。

## 1. 目标与裁决

本专项不是重复添加线程池指标，而是建立一条可诊断链路：

**Agent Run → Worker attempt → Model request → Tool invocation →
持久化结算 / UNKNOWN → Lease 与恢复结果。**

出现超时、卡住、UNKNOWN、队列拥堵或旧 Worker 被 fencing
时，应能回答：当前有多少 Run / Invocation 处于异常状态；UNKNOWN
持续多久；延迟来自排队、模型还是工具；Worker
是否饱和；如何从日志定位问题。

### 1.1 推荐路线

采用三层方案：

1.  **Micrometer**：进程内计数、耗时与调度指标。
2.  **PostgreSQL 持久化事实**：Run / Invocation 状态、UNKNOWN
    数量与年龄、步骤与事件。
3.  **受控日志关联**：异步 Worker / 工具边界使用 MDC 的 Run / Invocation
    标识。

复用现有 Spring Boot Actuator、MeterRegistry、PostgreSQL 和日志设施。

### 1.2 非目标

-   不改 P6-A 调度配额、公平性、取消和执行线程释放语义。
-   不改 P6-B UNKNOWN、出站证据、重试与 Lease fencing 语义。
-   不将 UNKNOWN 自动重试、自动标记成功或自动清零。
-   不新增前端仪表盘、分布式追踪、Prometheus Registry、Loki、Grafana
    或告警 SaaS。
-   不扩大 Actuator 默认暴露面，不匿名开放 metrics。
-   不按
    `runId`、`userId`、`invocationId`、Prompt、模型名、工具名或异常消息划分
    Metrics 标签。
-   不建立新的成本定价系统；Token / estimated cost
    仍以现有持久化记账为准。

## 2. 当前源码基线（已核对）

  ------------------------------------------------------------------------------------------------------------------------------------------------------------------
  能力                    当前事实                                                                                设计影响
  ----------------------- --------------------------------------------------------------------------------------- --------------------------------------------------
  P6-A 调度指标           已有 queue wait / execution Timer、active / pending Gauge、rejected / cancelled /       保留并复用，不重复注册
                          timeout Counter

  持久化事实              `agent_run`、`agent_step`、`agent_run_event` 保存运行状态、步骤、事件及部分延迟 / Token 作为 Run 诊断来源
                          数据

  Invocation              `agent_tool_invocation`                                                                 可以计算 UNKNOWN 当前积压与年龄
                          保存状态、`dispatch_started_at`、`dispatch_claim_version`、`execution_safety`，状态含
                          UNKNOWN

  模型出站                `RoutingAgentModelExecutor.callModel` 与 `callModelWithoutTools` 是主要 Agent           在真实出站边界计时
                          模型调用入口

  Worker 饱和             `AgentRuntimeJob.tick()` 捕获 `TaskRejectedException` 后仅 debug 记录                   增加拒绝计数

  请求关联                `RequestIdMdcFilter` 只在 HTTP 请求线程设置 requestId；后台 Worker 不应假定继承它       用 Run / Invocation ID 关联后台工作

  Actuator                默认只暴露 `health,info`；metrics 未默认暴露，除 health 外端点受 JWT 保护               保持安全配置

  外部监控                没有 Prometheus Registry，也没有接通告警通道                                            只交付应用侧指标与告警建议，不宣称生产告警已上线

  AI 调用日志             `AiCallLogService` 的 feature 为 `KNOWLEDGE_QA`                                         不得将其当作 Agent 全链路模型日志
  ------------------------------------------------------------------------------------------------------------------------------------------------------------------

源码参考（均以本设计基线为准）： -
[`AgentToolScheduler.java`](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentToolScheduler.java) -
[`AgentRuntimeJob.java`](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRuntimeJob.java) -
[`AgentWorker.java`](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentWorker.java) -
[`RoutingAgentModelExecutor.java`](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/RoutingAgentModelExecutor.java) -
[`AgentRunEventRecorder.java`](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRunEventRecorder.java) -
[`application.yml`](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/ai-collab-backend/src/main/resources/application.yml) -
[P5
可观测性复核](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/docs/release-readiness/p5-release-readiness-02/observability-review.md) -
[P6-A
交付报告](https://github.com/BangShou1st/ai-collab/blob/0e489824358046443429ad32d53b7143c51979cc/docs/archive/reports/p6-agent-concurrency-01-delivery-20261025.md)

## 3. 设计原则

### 3.1 瞬时指标不等于持久化事实

Counter / Timer 在进程重启后会重置；Run / Invocation 状态以 PostgreSQL
为准。UNKNOWN 数量与最老年龄必须从数据库快照计算，不能用内存 Counter
推算。

### 3.2 事务提交一致性

代表"已持久化结果"的指标只能在事务提交后更新。可在事务外调用方收到持久化方法成功返回后计数，或在必要时用
`TransactionSynchronization.afterCommit()`。幂等重放、读取旧结果和重复调用不能重复计数；回滚不能留下虚假的成功
/ UNKNOWN 计数。

### 3.3 低基数与隐私

只允许固定枚举标签，如： - `outcome`: `success` / `failed` /
`failed_retryable` / `canceled` / `paused` / `budget_exceeded` /
`unknown` / `unexpected_error` - `purpose`: `turn` / `summary` -
`safety`: `SAFE_READ` / `IDEMPOTENT_OR_RECONCILABLE` /
`UNCONFIRMABLE_SIDE_EFFECT` - `status`: 固定的 `AgentRunStatus` 状态 -
`reason` / `phase`: 源码中明确映射的有限值

严禁将任意 `errorCode`、异常消息、模型名、工具名、URL、用户 / 项目 / Run
/ Invocation ID、Prompt、参数 JSON 或密钥作为 Metrics 标签。日志可用
`agentRunId`、`agentClaimVersion`、`agentInvocationId` 关联，但不能输出
Prompt、工具参数、原始结果、Authorization、Cookie 或 API Key。

### 3.4 不改变业务安全

指标记录失败不得改变 Run / Invocation 结果；快照刷新失败不得把 Gauge
清零；Actuator scrape 不得直接触发 SQL；不持有调度器锁进行 SQL 或网络
I/O；UNKNOWN 不得因告警自动重试。

## 4. 指标目录

### 4.1 P6-A 已有指标：保持兼容

  -------------------------------------------------------------------------------------------------------------
  指标                                类型                    当前口径
  ----------------------------------- ----------------------- -------------------------------------------------
  `agent.tool.scheduler.queue.wait`   Timer                   提交至 Callable 实际启动，无标签

  `agent.tool.scheduler.execution`    Timer                   Callable 实际执行时长，无标签

  `agent.tool.scheduler.active`       Gauge                   RESERVED + 尚未退出的 RUNNING，上限 16

  `agent.tool.scheduler.pending`      Gauge                   等待准入的 PENDING，上限 64

  `agent.tool.scheduler.rejected`     Counter                 `reason=queue_full/shutdown/executor_rejected`

  `agent.tool.scheduler.cancelled`    Counter                 `phase=not_started/running_interrupt_requested`

  `agent.tool.scheduler.timeout`      Counter                 `phase=not_started/inflight`
  -------------------------------------------------------------------------------------------------------------

不要重命名已有指标，也不要把其 Timer 口径与新指标混为一谈。

### 4.2 Worker 生命周期

  --------------------------------------------------------------------------------------------------------------------
  指标                               类型              标签                     口径
  ---------------------------------- ----------------- ------------------------ --------------------------------------
  `agent.worker.attempt`             Counter           `outcome` 固定映射       一次已领取 Run 的 Worker attempt
                                                                                返回或异常退出计一次

  `agent.worker.attempt.duration`    Timer             `outcome`                从 `AgentWorker.process` 开始到返回 /
                                                                                异常退出

  `agent.worker.dispatch.rejected`   Counter           `reason=executor_full`   `TaskRejectedException`
                                                                                导致派发被拒绝；"本轮无任务"不计拒绝
  --------------------------------------------------------------------------------------------------------------------

Attempt outcome 不等于 Run
的最终状态；暂停、等待审批和可重试失败不是最终完成。

### 4.3 模型请求

  ----------------------------------------------------------------------------------------------------------
  指标                             类型              标签                   口径
  -------------------------------- ----------------- ---------------------- --------------------------------
  `agent.model.request`            Counter           `purpose`、`outcome`   一次真实模型出站调用完成后计数

  `agent.model.request.duration`   Timer             `purpose`、`outcome`   模型调用墙钟耗时，含成功与异常
  ----------------------------------------------------------------------------------------------------------

`purpose=turn/summary`；`outcome=success/timeout/provider_error/invalid_response/interrupted/other`。从已知错误码
/
异常类型映射，不使用自由文本。配置校验失败、预算拒绝和未真正出站的请求不计作模型请求；一次调用不能在多个包装层重复计数。

Token 的 `usage_basis`、实际 / 估算用量继续以 `agent_step` / Run
持久化记录为审计来源，不额外创建可能重复计费的 Token Counter。

### 4.4 工具 Invocation 结果

  -------------------------------------------------------------------------------------------------------
  指标                               类型              标签                  口径
  ---------------------------------- ----------------- --------------------- ----------------------------
  `agent.tool.invocation.outcome`    Counter           `safety`、`outcome`   Invocation
                                                                             最终结果成功持久化后计数

  `agent.tool.invocation.duration`   Timer             `safety`、`outcome`   调度提交至 Invocation
                                                                             结果持久化完成的端到端耗时
  -------------------------------------------------------------------------------------------------------

`outcome` 固定为
`succeeded/failed/rejected/canceled/skipped/unknown`。只有实际状态转换成功提交后计数；`knownInvocationResult`
读取旧结果、幂等重放或拒绝覆盖不能重复计数。UNKNOWN 必须独立于
FAILED。`safety` 只允许 `AgentToolExecutionSafety`
的三个固定值，不用工具名作标签。

### 4.5 PostgreSQL 状态快照

新增
`AgentRuntimeStateMetrics`（名称可按代码规范微调）。后台定时刷新，Actuator
查询只读内存快照，不直接查数据库。建议默认间隔 30 秒。

  ------------------------------------------------------------------------------------------------------------------
  指标                                            类型              标签              口径
  ----------------------------------------------- ----------------- ----------------- ------------------------------
  `agent.run.status.count`                        Gauge             `status`          各持久化 Run 状态当前数量
                                                                    固定状态集合

  `agent.run.lease.expired.count`                 Gauge             无                `RUNNING` 且 Lease 已过期的
                                                                                      Run 数

  `agent.invocation.unknown.count`                Gauge             无                当前 UNKNOWN Invocation 数量

  `agent.invocation.unknown.oldest.age`           Gauge             无                最老 UNKNOWN 持续秒数；没有
                                                                                      UNKNOWN 时为 0

  `agent.diagnostics.snapshot.age`                Gauge             无                距最近一次成功刷新经过的秒数

  `agent.diagnostics.snapshot.refresh.failures`   Counter           无                快照查询失败次数
  ------------------------------------------------------------------------------------------------------------------

#### UNKNOWN 索引与查询

V47 创建的 `agent_tool_invocation` 原始定义没有按 `status`
建索引。为避免随着历史 Invocation 增长，每 30
秒对整张表扫描，建议新增连续迁移
`V68__agent_observability_unknown_index.sql`：

``` sql
CREATE INDEX idx_agent_tool_invocation_unknown_age
    ON agent_tool_invocation ((COALESCE(dispatch_started_at, updated_at)))
    WHERE status = 'UNKNOWN';
```

快照使用相同表达式：

``` sql
SELECT count(*) AS unknown_count,
       min(COALESCE(dispatch_started_at, updated_at)) AS oldest_since
FROM agent_tool_invocation
WHERE status = 'UNKNOWN';
```

`dispatch_started_at` 是通常的起始事实；历史 UNKNOWN 缺少它时用
`updated_at` 保守回退。索引仅服务于查询，不更新或收口 UNKNOWN。

实施必须用真实 PostgreSQL / Testcontainers 验证 V67 → V68、既有 UNKNOWN
/ PENDING / terminal 状态保留、索引存在及查询结果；对代表性数据检查
`EXPLAIN`。如有证据表明更合适的替代方案，可提出替代，但须说明扫描成本。

#### 快照失败语义

-   查询成功后原子替换快照并更新时间。
-   查询失败时保留 last-good snapshot，不得清零；刷新失败 Counter
    增加，snapshot age 持续增长。
-   首次刷新失败须有明确"尚无成功快照"状态，不能把初始 0 误解为空积压。
-   日志限频，不每 30 秒刷屏；恢复后记录一次恢复日志。
-   不在每次 `/actuator/metrics` 请求时执行 SQL。

## 5. 异步日志关联

在 Worker 与工具 Invocation 作用域设置并在 `finally` 清理： -
`agentRunId` - `agentClaimVersion` -
`agentInvocationId`（只在工具调用范围内）

更新 console pattern，保留现有 `requestId`。**不要假设 HTTP requestId
自动传播到后台 Worker**；Run / Invocation ID
是后台诊断主关联键。若将来需要永久关联原始 HTTP
请求，必须另行设计并持久化来源 ID，本轮不暗中改变数据库语义。

只记录 outcome、duration、固定安全类别、异常类型和 fencing
reason。禁止记录 Prompt / goal 正文、模型消息、工具参数与原始结果
JSON、Cookie、Authorization、API Key 或敏感业务正文。不得把
`exception.getMessage()` 用作 Metrics 标签。

## 6. 诊断 Runbook

更新 `docs/design/agent-runtime-maintenance.md`，增加以下排查顺序：

1.  看调度器 active / pending、queue wait / execution、rejected /
    cancelled / timeout。
2.  看 `agent.run.status.count`、`agent.run.lease.expired.count`。
3.  看 UNKNOWN count / oldest age / snapshot age。
4.  通过 `agentRunId` / `agentInvocationId` 查日志，再以 PostgreSQL
    状态、Invocation 出站证据和最后事件核对。
5.  模型延迟升高时区分 `turn` / `summary` 和 outcome；Provider
    故障不等于应用进程不健康。
6.  UNKNOWN 没有可信远端核对能力时，按 P6-B
    人工核对边界处理，不自动重试。

受控只读 SQL 示例：

``` sql
SELECT count(*) AS unknown_count,
       min(COALESCE(dispatch_started_at, updated_at)) AS oldest_since,
       extract(epoch FROM (now() - min(COALESCE(dispatch_started_at, updated_at))))
           AS oldest_age_seconds
FROM agent_tool_invocation
WHERE status = 'UNKNOWN';
```

``` sql
SELECT run_id, invocation_id, turn_sequence, ordinal, tool_name, status,
       dispatch_started_at, dispatch_claim_version, execution_safety, updated_at
FROM agent_tool_invocation
WHERE invocation_id = :invocation_id;
```

``` sql
SELECT id, status, claim_version, lease_expires_at, updated_at, error_code
FROM agent_run
WHERE status = 'RUNNING' AND lease_expires_at < now()
ORDER BY lease_expires_at
LIMIT 100;
```

诊断 SQL 是受控运维工具，不新增匿名 HTTP 查询接口；不选取
`arguments_json` 或完整 `result_json`。

## 7. 告警规则候选（不代表已接通）

当前没有 Prometheus Registry / scrape target /
告警通道，以下仅供将来部署监控平台时校准：

  ----------------------------------------------------------------------------------------
  规则候选                建议阈值                处置
  ----------------------- ----------------------- ----------------------------------------
  UNKNOWN 积压            count \> 0 持续 1 分钟  人工核对；不自动重试

  工具队列拥堵            pending ≥ 56（64 的     检查下游耗时与调度公平性，不先扩大配额
                          87.5%）持续 5 分钟

  调度器拒绝              rejected 在 5           区分 queue_full / shutdown /
                          分钟内持续增长          executor_rejected

  Lease 过期              expired count \> 0      检查 Worker、恢复调度与数据库锁
                          持续超过两个快照周期

  模型错误率              5 分钟失败率            区分 timeout / provider_error /
                          \>10%，且样本 ≥20       invalid_response
  ----------------------------------------------------------------------------------------

阈值必须按实际流量校准；没有外部采集和告警通道时，不能声称"告警已启用"。

## 8. 实施阶段

### C0 --- 基线复核

-   阅读 `AGENTS.md`、`docs/README.md`、P6-A / P6-B 设计和交付报告。
-   确认最新 dev SHA 与设计假设仍成立。
-   保存现有指标 / Actuator / 日志安全基线。
-   若 P6-B 报告仍标 `CI_PENDING`，核对最终同 SHA CI
    成功后修正最终回执和裁决；保留 `LIVE_NOT_RUN`
    残余风险，不改历史证据。

### C1 --- 持久化状态快照

-   实现 `AgentRuntimeStateMetrics`、固定 Gauge、30
    秒后台刷新、last-good / age / failure 语义。
-   新增 V68 部分索引，或提交有证据支持的等价方案。
-   用 Testcontainers 验证迁移、UNKNOWN count / age、Run 状态和过期
    Lease。

### C2 --- Worker / Model / Tool 指标

-   为 Worker attempt 和 Worker Executor 拒绝增加指标。
-   在 `RoutingAgentModelExecutor` 的真实出站边界计时，区分 turn /
    summary。
-   在工具结果持久化成功后记录 outcome 与端到端耗时。
-   fencing 仅使用固定 reason 映射；不得解析自由文本异常。
-   指标不得改变业务结果或事务语义。

### C3 --- 日志关联与安全

-   增加 MDC scope 并确保 `finally` 清理。
-   保留 requestId 行为，不假设异步传播。
-   更新日志 pattern 和维护手册；测试线程复用、异常、取消时不泄漏 MDC。
-   验证无 Prompt、参数、结果或密钥泄漏。

### C4 --- 验收

-   先跑新增单测与定向真实 PostgreSQL 测试。
-   稳定后跑完整 `cd ai-collab-backend && bash ./mvnw -B -ntp verify`。
-   无前端源码变更时不要求本地重复跑前端全套；正常 CI 仍按工作流执行。
-   不逐提交等待 CI；最终验收必须核对最终 SHA 的 push / PR CI。
-   不改 main，不将 PR #18 从 Draft 改为 Ready，不合并 PR。

## 9. 验收矩阵

### 单元测试

-   **C01**：指标目录、类型、固定标签正确；P6-A 指标名和口径不变。
-   **C02**：Worker attempt outcome / duration 成对记录，异常路径正确。
-   **C03**：模型真实出站成功、超时、Provider
    错误、无效响应、异常分类正确；配置拒绝不计模型请求。
-   **C04**：工具成功 / 失败 / 拒绝 / 取消 / 跳过 / UNKNOWN 分开计数。
-   **C05**：幂等重放、读取旧结果、拒绝覆盖不重复增加 Counter。
-   **C06**：快照刷新失败保留 last-good，age 增长且 failure Counter
    增加。
-   **C07**：MDC 在成功、异常、取消路径清理；线程复用不串 Run /
    Invocation。
-   **C08**：日志与标签不含敏感正文、高基数 ID、模型名或密钥。
-   **C09**：health 仍匿名可用且无 details；metrics
    默认不暴露，显式暴露后仍要求 JWT。
-   **C10**：Worker Executor 饱和计数准确；"本轮无任务"不算拒绝。

### PostgreSQL / 集成回归

-   **I01**：Testcontainers 执行 V67 → V68；现有 UNKNOWN / PENDING /
    terminal 行不被修改。
-   **I02**：DB 的 UNKNOWN count / age、Run status 和过期 Lease Gauge
    与查询结果一致。
-   **I03**：Lease 过期 / 恢复后指标正确，不改变 claim / fencing 行为。
-   **I04**：事务回滚不能留下伪成功 / 伪 UNKNOWN 计数。
-   **I05**：P6-A Scheduler 与 P6-B TOCTOU / UNKNOWN / 迟到结果 /
    fencing / 暂停恢复回归。
-   **I06**：完整后端 verify 实际执行，真实容器测试不得通过新增 skip
    绕过。
-   **I07**：最终同 SHA CI、文档、证据和 Git 状态一致。

完成条件：指标口径 / 标签 / 重置行为有文档；UNKNOWN Gauge 来自
DB；快照失败不伪造 0；提交一致性正确；日志可关联且无敏感正文；Actuator
安全未削弱；P6-A / P6-B 无回归；告警通道未接通的边界明确。

最终状态仅在本地证据与最终 SHA CI 都通过后可标记
`P6_AGENT_OBSERVABILITY_01_ACCEPTED`。否则使用
`IMPLEMENTED`、`LOCAL_PASS`、`CI_PENDING` 或 `NOT_ACCEPTED`。

## 10. 文档落位

遵守 `AGENTS.md` 第 9 节： -
长期设计：`docs/design/agent-observability.md` -
交付报告：`docs/archive/reports/p6-agent-observability-01-delivery-YYYYMMDD.md` -
复核：`docs/archive/reviews/` -
验收证据：`docs/acceptance-evidence/YYYY-MM-DD/p6-agent-observability-01/` -
更新 `docs/README.md` 导航。 - 更新
`docs/design/agent-runtime-maintenance.md` 诊断章节。

不得在 `docs/`
根目录平铺过程文档，不修改历史迁移，不提交明文凭据或未脱敏日志。

## 11. 风险与交接

-   Micrometer Counter / Timer 是 JVM 生命周期数据；重启后会重置。
-   UNKNOWN 是安全边界，不是自动重试信号。
-   没有 Prometheus / scrape target / 告警通道时，指标可在显式开放且 JWT
    保护的 Actuator metrics 端点按需读取，但不会自动进入外部监控平台。
-   外部监控接入需要单独决定
    exporter、网络暴露、凭据、保留周期、告警路由和运维责任。
-   P6-D 再处理 RAG 召回 / 引用质量与成本效果，不混入本次 Agent
    可观测性实现。
