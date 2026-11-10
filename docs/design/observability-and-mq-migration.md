# 设计：可观测性落地与 MQ 迁移（综合路线图 阶段 1）

> 状态：草案（待评审）
> 日期：2026-11-08
> 关联：`docs/design/agent-fanout-budget-and-agentic-planning.md`（agent 深化，P0-P2 已落地）、`docs/design/agent-runtime-design.md`
> 本文"现状"均经源码与配置核实；行号以 2026-11-08 dev 分支为准。

---

## 0. 背景与目标

综合路线图（简历导向的架构演进主线：单实例轮询 → 多实例事件驱动）的阶段 1。两个子工程：

| # | 工程 | 一句话现状 |
|---|---|---|
| O | 可观测性 | 自定义 Micrometer 指标已存在（`AgentRuntimeMetrics` 等）但生产不可见：`application.yml:160` 只暴露 `health,info`，无 prometheus 端点、无分布式 trace |
| M | MQ 迁移 | 全项目零 MQ，三条异步链路全是"线程池 + @Scheduled 轮询 DB"，文档去重集合是 JVM 内的——系统绑死单实例 |

**目标**：改造前后都有量化数据（先观测后改造）；任务派发事件驱动化，故障接管从 10-15 分钟降到秒级，解除单实例绑定。

---

## 1. 现状事实（已核实）

### 1.1 可观测性现状与安全契约

- `application.yml:150-167`：生产默认 `MANAGEMENT_ENDPOINTS_EXPOSURE:health,info`；注释明确设计意图——"metrics 等需运维时通过环境变量显式打开，且即便暴露，除 health 外的所有 Actuator 端点都受 SecurityConfig 的 JWT 认证保护"。
- `SecurityConfig.java:74`：仅 `/actuator/health` 与 `/actuator/health/**` permitAll，其余 `anyRequest().authenticated()`。
- **CI 契约（不可破坏）**：
  - `ai-collab-deploy/scripts/p0-https-test.mjs:30`：`/actuator/env` 对外必须 404（nginx 阻断）；
  - `test_staging_lifecycle.mjs:216-219`：nginx 对外阻断 `/actuator`；
  - `p0-isolated-compose-smoke.sh:105`：容器内 health 匿名可达；
  - `ci.yml` production-contract job：prod/local compose 渲染结果契约校验（改 compose 需过 `prod-compose-contract.mjs`）。
- 已有 Micrometer 指标：`AgentRuntimeMetrics`（worker 计时）、`AgentRuntimeStateMetrics`（run 状态 gauge）、P0/P1 新增 `agent.budget.soft.warning`、`agent.budget.session.warning` counter——**全部因无导出端而不可见**。
- 日志已带 `requestId/agentRunId/agentClaimVersion/agentInvocationId` MDC 关联 ID（`application.yml` console pattern）。
- pom.xml：仅 spring-boot-starter-actuator，无 micrometer-registry-prometheus、无 micrometer-tracing。

### 1.2 三条伪异步链路（MQ 迁移对象）

| 链路 | 现状机制 | 关键证据 |
|---|---|---|
| Agent run 执行 | `AgentRuntimeJob.tick()` 每秒轮询 `claimNext`（`FOR UPDATE SKIP LOCKED` + 6min 租约 + claim_version CAS），满载 `TaskRejectedException` 直接跳过 | `AgentRuntimeJob.java:54-68`、`AgentRepository.java:461-491`；P1 已加事件唤醒绕过 tick |
| 文档处理 | 进程内 `ConcurrentHashMap.newKeySet()` 去重（注释自认"不是跨进程锁"）+ 30s 恢复轮询 | `DocumentTaskDispatcher.java:21-32`、`DocumentRecoveryService.java:30-41` |
| 规划生成 | 进程内 FutureTask registry + 60s 恢复轮询（10 分钟 stale 窗口标 PROCESS_RESTARTED） | `TaskPlanGenerationOrchestrator.java:215-242`、`TaskPlanRecoveryJob.java:13-49` |

### 1.3 MQ 选型

**RocketMQ**（推荐）：国内 JD 提及率最高、事务消息原生支持（文档流水线"上传落库 + 发消息"一致性直接命中）、延迟消息原生（替掉 30s/60s 轮询）、死信队列内建。
备选 RabbitMQ：运维简单、教程多（竞品简历同款），但事务消息需自建本地消息表模式。
Redis Stream：最轻，但消费确认/死信生态弱，作为降级备选。

---

## 2. 方案 O：可观测性落地

### 2.1 指标导出（尊重既有安全契约）

**暴露方式决策**（三选一，推荐 A）：

- **A（推荐）：env-var 驱动 + 组合网络隔离**——完全沿用项目已文档化的模式：
  - pom 加 `micrometer-registry-prometheus`（runtime，仅引入端点能力，不改变默认暴露）；
  - `application.yml` 默认值不变（`health,info`）；
  - compose（local/staging/prod）设置 `MANAGEMENT_ENDPOINTS_EXPOSURE:health,info,prometheus`；
  - **prometheus 端点匿名问题**：SecurityConfig 维持 JWT 保护不动；抓取走**独立管理端口**（`management.server.port: 8081`，仅 compose 内网暴露、不映射宿主机、nginx 不代理）——管理端口上的 actuator 不经应用 SecurityConfig（Spring Boot 独立管理端口有自己的安全边界），health 探针同步迁到管理端口（`/actuator/health` 语义不变，容器 HEALTHCHECK 与 smoke 脚本同步改指 8081）；
  - CI 契约同步：`p0-isolated-compose-smoke.sh`、`p0-dependency-outage.sh` 的 health 检查改 8081；`p0-https-test.mjs` 的对外 404 断言不变（nginx 对外阻断 8081 根本不监听）；`prod-compose-contract.mjs` 增加新契约（8081 不映射宿主机、健康检查指向断言）。
  - 理由：管理端口是业界标准做法，JWT 无法用于 prometheus 抓取；一次到位，后续所有指标（agent 预算、队列深度）自动可见。
- B：同端口 + nginx 内网放行 + IP 白名单——SecurityConfig 要加 IP 规则，复杂且易错。
- C：默认匿名暴露 prometheus——违背项目安全基线，否决。

### 2.2 分布式 trace

- pom 加 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`；
- 采样：`management.tracing.sampling.probability: 1.0`（单体阶段全采样，成本可控）；
- 导出：OTLP gRPC 指向 compose 内 collector（Grafana Tempo 或 Jaeger all-in-one），local profile 可关闭导出仅留日志 traceId；
- MDC 桥接：micrometer-tracing 自动把 traceId/spanId 注入 MDC——现有 log pattern 增补 `[traceId=%X{traceId:-none}]`，与既有 `requestId/agentRunId` 并存；
- **agent 链路自定义 span**：模型轮、工具执行、委派受理三处手动 span（tag: runId/stepId/toolName），这是排查 agent 问题的核心观测面；P1 的事件唤醒链路天然被串联（子终态 → 唤醒 → 父恢复在同一 trace）。

### 2.3 Grafana 面板（交付物）

最小面板集（compose 加 grafana + prometheus + tempo 三容器，仅 local/staging profile）：
1. Agent 运行总览：run 状态 gauge、worker 队列深度、预算软阈值触发率；
2. 任务派发延迟：QUEUED→RUNNING 时长分位（MQ 迁移的 before 基线）；
3. 规划管线：attempt 各 stage 时延与失败率。

---

## 3. 方案 M：MQ 迁移（RocketMQ）

### 3.1 分链路策略

| 链路 | 迁移动作 | 保留的兜底 |
|---|---|---|
| 文档处理 | 上传事务落库后发**事务消息**（半消息 + 本地事务回查），消费侧幂等（复用 `idempotency_record` 表）；重试走延迟消息指数退避；死信队列 + 告警日志 | `DocumentRecoveryService` 轮询降频为 5 分钟对账（消息丢失兜底），进程内去重集合删除 |
| 规划生成 | 编排器 dispatch 改发消息；恢复 Job 保留但降频对账 | 进程内 FutureTask registry 改为消息驱动后的运行注册表（保留取消语义） |
| Agent run | **双轨制**：P1 的事件唤醒已绕过 tick；MQ 只接管"新 run 入队通知"（run 创建 afterCommit 发消息 → 消费者触发一次 claim）；`claimNext` 轮询保留为兜底（降频 5s） | 轮询兜底永不删（消息丢失时任务不丢）；租约/SKIP LOCKED 语义原样保留 |

**明确不迁**：审批流、SSE 事件流（同步链路）。

### 3.2 基础设施

- compose 加 `rocketmq-namesrv` + `rocketmq-broker`（local/staging/prod 三层），healthcheck + 资源限制；
- 多实例解锁顺序：MQ 落地 → `DocumentTaskDispatcher` 去重集合删除 → 文档链路先多实例 → agent/规划链路因 SKIP LOCKED 天然支持多实例（验证 `AgentLeaseRenewer` 跨实例语义）→ `dev.ps1` 支持起双 app 实例做验收。

### 3.3 交付判据（简历数字来源）

- 改造前后"QUEUED→RUNNING 派发延迟"P99 对比（§2.3 面板直接出数）；
- 故障接管：kill -9 消费者后恢复时间 10-15min → 秒级（事务消息回查 + 兜底轮询降频后）；
- 双实例压测：同批文档在两实例下零重复处理（幂等表断言）。

---

## 4. 实施分期

| 期 | 内容 | 风险 |
|---|---|---|
| O1 | pom 依赖 + 管理端口 + compose 三容器 + Grafana 面板 + CI 契约同步 | 中（动部署契约，需过 prod-compose-contract） |
| O2 | tracing 桥接 + agent 自定义 span + 日志 pattern | 低 |
| M1 | RocketMQ compose + 文档链路迁移（事务消息 + 幂等消费 + 死信） | 中 |
| M2 | agent run 入队通知双轨 + 规划 dispatch 消息化 + 双实例验收 | 中高 |

O1/O2 先行（改造前基线数据开始积累），M1/M2 随后。

## 5. 关键取舍

| 决策 | 理由 |
|---|---|
| 独立管理端口而非同端口 IP 白名单 | 业界标准；JWT 不适配机器抓取；nginx 对外阻断语义天然保持 |
| agent run 双轨而非全删轮询 | 消息丢失兜底是正确性依赖；"为什么不删轮询"是面试必答题，保留兜底是更深的答案 |
| 文档链路先迁 | 去重集合是单实例绑定的最硬阻塞，且事务消息场景最典型 |
| Grafana 只进 local/staging | prod 面板属运维体系，当前项目形态不引入；指标端点已就绪 |
