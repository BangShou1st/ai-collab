# P6-AGENT-OBSERVABILITY-01 交付报告（2026-10-25）

## 交付状态与版本

本轮完成C0–C4实际实施，本地状态 `IMPLEMENTED / LOCAL_PASS`。最终同SHA CI确认以前保持 `CI_PENDING`，不提前声明 `P6_AGENT_OBSERVABILITY_01_ACCEPTED`。

开工基线：dev/origin/dev=`0e489824358046443429ad32d53b7143c51979cc`，工作区干净。
main/origin/main=`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；PR [#18](https://github.com/BangShou1st/ai-collab/pull/18) OPEN/Draft/未合并。
本报告、代码、设计和证据同批提交dev。自身提交无法自包含自身SHA；最终完整SHA、origin同步/工作区状态、实际push/PR Actions链接及最终裁决由聊天交付回执给出。复核时检出该SHA，核对清单与Actions head_sha；不把先前实现SHA当作之后文档提交SHA。
没有force push、历史改写、main提交/合并、PR Ready操作、工作区清理或用户WIP覆盖；未手动重跑工作流或制造空提交。

## 已交付实现

- C0：盘点既有Actuator/MeterRegistry、P6-A七类调度指标、Run/Invocation持久化状态及requestId日志基线。P6-B最终0e48982的push/PR成功回执已补入其正式报告，保留当时CI_PENDING快照和LIVE_NOT_RUN限制。
- C1：`AgentRuntimeStateRepository`只读REPEATABLE_READ快照，`AgentRuntimeStateMetrics`默认30秒刷新。查询全成功才原子替换；首次NaN，失败保留last-good、失败Counter增长、age持续增长；日志五分钟限频、恢复单次记录。Actuator读取内存不触发SQL。
- C1：新增V68 UNKNOWN部分表达式索引；V1–V67不改。UNKNOWN年龄起点dispatch_started_at，缺失时updated_at；没有UNKNOWN为0。真实PG升级、全行指纹、索引、聚合结果与代表性EXPLAIN已验证。
- C2：Worker attempt Counter/Timer与executor_full拒绝；无任务不算拒绝。Routing建立模型观测作用域，JsonHttp/Kilo真实出站后才计数，区分turn/summary与有限异常分类。凭据解密/配置/SSRF/未出站拒绝不算请求，无Token重复计费Counter。
- C2：Invocation结果只在UPDATE RETURNING实际转换行成功提交后发布；覆盖普通结果、委派、取消、SKIPPED及UNKNOWN。回滚、幂等读取、覆盖拒绝不计。safety缺失时用保守默认UNCONFIRMABLE_SIDE_EFFECT，不回写出站证据或按工具名猜测。
- C3：Worker和工具异步作用域显式设置agentRunId/agentClaimVersion/agentInvocationId；finally恢复/清理，线程复用不串上下文。requestId pattern保留，后台不假设HTTP请求ID传播。新增日志仅固定状态/耗时/类型，所触及执行边界不打印异常正文。
- C4：正式设计、Runbook、指标目录、验收矩阵、升级/回滚/隐私/安全证据入库；CI既有守卫增加本轮关键类，不新增工作流/skip或放宽断言。

不改变P6-A调度算法/配额、公平性与执行退出语义，不改变P6-B UNKNOWN/重试/fencing，不改变审批与MCP白名单，不改前端或Actuator默认暴露。

## 新增指标目录

| 名称 | 类型 | 标签 | 统计口径 |
| --- | --- | --- | --- |
| agent.worker.attempt | Counter | outcome | 已领取Run的一次process返回或异常；不等于最终Run完成 |
| agent.worker.attempt.duration | Timer | outcome | process入口至返回/异常 |
| agent.worker.dispatch.rejected | Counter | reason=executor_full | TaskRejectedException，不包括空轮询 |
| agent.model.request | Counter | purpose/outcome | Routing作用域内真实出站完成；无出站不计 |
| agent.model.request.duration | Timer | purpose/outcome | 首次传输出站至路由返回/异常，含Legacy响应解析 |
| agent.tool.invocation.outcome | Counter | safety/outcome | 实际持久化状态转换afterCommit单计 |
| agent.tool.invocation.duration | Timer | safety/outcome | 当前Worker已知Scheduler提交至结果提交 |
| agent.run.status.count | Gauge | status | PostgreSQL各Run状态数 |
| agent.run.lease.expired.count | Gauge | 无 | RUNNING且lease_expires_at < DB now() |
| agent.invocation.unknown.count | Gauge | 无 | PostgreSQL UNKNOWN数 |
| agent.invocation.unknown.oldest.age | Gauge(秒) | 无 | UNKNOWN最早COALESCE时间至现在，无UNKNOWN为0 |
| agent.diagnostics.snapshot.age | Gauge(秒) | 无 | 距成功刷新时间；首次NaN |
| agent.diagnostics.snapshot.refresh.failures | Counter | 无 | 快照失败次数，旧值不清零 |

完整标签值在[机器可读目录](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/metrics-catalog.json)；均为固定枚举，不含run/user/invocation/project ID、工具名、模型名、URL、错误正文或密钥。
Counter/Timer在JVM重启后重置，Gauge重新读取数据库。快照新鲜度与事实Gauge首次均NaN。
Invocation Timer不为未进入Scheduler的审批/委派/验证拒绝/跳过，以及跨重启未知原提交时间的收口伪造0秒样本；这些转换仍有Counter和数据库状态。Timer.count可小于Counter。UNKNOWN年龄承担跨重启积压诊断。

## 本地测试与PostgreSQL证据

先行定向：**22项，0失败、0错误、0跳过**，包含真实PostgreSQL容器；见[targeted-tests.json](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/targeted-tests.json)。
命令经Windows Git Bash执行：`bash ./mvnw -B -ntp -Dtest=AgentRuntimeMetricsTest,AgentObservabilityPostgresTest,AgentActuatorSecurityTest,AgentModelTransportMetricsTest,RoutingAgentModelExecutorRequestConsistencyTest test`。
Windows默认bash指向无Docker socket的WSL，最初定向运行明确失败，未将其冒充数据库验收；切换Git Bash/Windows JDK连接Docker Desktop npipe后全部实际执行。
开发中修正了测试编译错误、Mockito重设桩方式、Actuator测试上下文装配和不完整出站证据/过期Lease夹具；未放宽业务断言或改为Mock数据库。

真实PostgreSQL17：V67先写30000条既有UNKNOWN/PENDING/terminal行，再迁移V68，前后全行JSONB聚合指纹一致；实际索引定义与聚合结果一致。10条UNKNOWN最老由dispatch起算，历史null dispatch由updated_at回退。EXPLAIN ANALYZE BUFFERS自然选择`idx_agent_tool_invocation_unknown_age`，不强制禁用顺序扫描；原始计划见[postgres-query-plan.txt](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/postgres-query-plan.txt)。
真实事务逐项验证六种结果回滚不发布、提交后单计、旧结果读取/重复覆盖不计；取消批次UNKNOWN与CANCELED回滚/重放同样不重复。Lease续期后expired归零，claim不变。

最终定向追加Kilo本地请求校验拒绝测试后：**47项全部通过**（专项23、持久化轮次恢复24），零失败/错误/跳过；见[最终定向回执](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/final-targeted-recovery.json)。
首轮全量1760项出现9项连接错误，集中于一次PostgreSQL进程重启后的恢复测试，非断言失败；原始失败事实见[首轮失败摘要](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/initial-full-verify-failure.json)。未观察到Docker OOM事件，根因未确定，不将其无证据归因于环境。隔离复验24项全通过且容器日志无测试期间重启；最终代码再次完整verify，不用定向通过替代全量。

完整后端：`bash ./mvnw -B -ntp verify`（Windows Git Bash，Java21，Docker Desktop真实容器）；**BUILD SUCCESS，7分33秒；1761项，0失败、0错误、34项既有opt-in跳过（实际执行1727项）**。20个CI关键类守卫全部通过，没有本轮新增/扩大skip；排除2个陈旧XML报告。逐类数量、跳过原因及守卫结果见[backend-verify.json](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/backend-verify.json)。
前端源码未改，按本次授权不重复本地完整前端套件；正常CI继续前端lint/typecheck/test/build及PR镜像冒烟。

## 验收矩阵与关键回归

[C01–C10、I01–I07逐项入口及断言](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/acceptance-matrix.md)。C01–C10、I01–I06本地通过；I07等待最终同SHA CI。

关键回归：Scheduler=13、暂停/恢复=21、持久化轮次恢复=24、P6-B远端结果=30、P6-B升级=1、Run重试=6、Run Context Commit=10，均0失败/错误/跳过。最终全量中恢复类未重现首轮数据库重启；保留首轮失败与根因未确定的限制。

Actuator使用真实SecurityConfig、实际application.yml和实际Actuator处理器：health匿名200且无details/components；默认metrics匿名401、有效JWT仍404；显式暴露后匿名/无效JWT401、有效JWT200。未扩大匿名面。
MDC：实际Worker正常/异常/取消、嵌套工具范围、单线程复用均清理；传输观测ThreadLocal也清理。日志捕获验证敏感异常正文不输出，无异常堆栈泄漏；标签固定。Scheduler源码Git blob与基线相同；无历史迁移/前端/安全配置差异，见[invariants.json](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/invariants.json)。

## 文档与证据

- [正式设计](../../design/agent-observability.md)、[运行时诊断手册](../../design/agent-runtime-maintenance.md#21-agent-可靠性诊断p6-c)、[文档导航](../../README.md)。
- [验收证据目录](../../acceptance-evidence/2026-10-25/p6-agent-observability-01/README.md)。
- [实施自审记录](../reviews/p6-agent-observability-01-review-20261025.md)，不冒充独立人工/Agent审查。
- [P6-B最终CI补记](p6-agent-remote-outcome-01-delivery-20261025.md#g-p6-c-开工补记p6-b-最终-ci-回执2026-10-25)。

临时构建输出均在target受控目录；本轮未创建*.log或根目录散落资产，既有用户/历史文件保留。不提交原始敏感输出、凭据或模型正文。

## 未执行项与残余风险

- 真实外部Provider/MCP网络取消确认仍`LIVE_NOT_RUN`；不声称Future取消等于远端撤销，也不扩展Exactly-Once保证。
- 没有Prometheus/OTel/exporter/scrape target/外部告警平台，告警规则只是待校准候选，未接通；UNKNOWN只用于人工核对，不自动重试/清零。
- 首轮全量中PostgreSQL重启根因未确定；隔离复验已通过，仍保留本地运行环境偶发不稳定风险，最终完整verify/CI须同时通过。
- 快照默认30秒存在延迟，失败保留旧值，应结合snapshot.age判断；指标为单JVM，不提供集群级总量聚合。
- 常规事务CREATE INDEX对大表升级会持有写锁；3万行计划不等于生产规模性能保证。部署窗口、数据库规模、真实长时间负载需环境专项确认。
- 未调度/跨重启调用不制造Timer样本；完整审计仍以PostgreSQL状态/事件/出站证据为准。
- 本轮无UI/前端变更，不进行浏览器验收；不混入P6-D RAG质量/成本工作。
