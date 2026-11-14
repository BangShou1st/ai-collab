# G Planning Python Skeleton 主链

> 分支：`hardening/g3-planning-mainchain`
> 基线：`c6eff5b1`（F2 合入后的 `dev`）
> 默认：`PLANNING_DRAFT_MODE=java`；六开关、MQ、Gateway、Provider 未修改。

## 实现

| 能力 | 状态 |
| --- | --- |
| `PlanningDraftMode.PYTHON` 可绑定启动 | IMPLEMENTED |
| `PlanningDraftRouter` 只接管 skeleton；Python 草稿经 `PlanningDraftParser` 后重跑 `TaskPlanDraftValidator(AI_SKELETON)` | IMPLEMENTED |
| 合法候选仍走既有 `appendGeneratedVersion` / attempt / version / 人工确认链 | IMPLEMENTED |
| detail 继续 Java 生成；Python 契约缺少 skeleton 身份与 `S1..S12` 来源命名空间，不硬塞 | IMPLEMENTED |
| 非法 JSON / 未知字段 / 形状不符 / Validator 失败 / worker 超时/不可用/HTTP/未知 / deadline / snapshot / 活跃复核失败回退 Java | IMPLEMENTED |
| 稳定 fallback marker 写入 attempt `diagnostics_json`（`python_fallback:*` / `python_canceled`） | IMPLEMENTED |
| 路由指标 `planning.draft.router.calls/fallback/latency` | IMPLEMENTED |
| Python 调用前后复核 attempt 活跃状态；取消不交付草稿 | IMPLEMENTED |
| deadline 取 `min(planning.draft.deadline-ms, remainingMillis)`；当前规划主链无运行级剩余预算，编排器传无界值，实际取配置上限 | IMPLEMENTED |
| 默认 `java`/`shadow` 不进入 router，不读 validation context，不产生 router 指标 | IMPLEMENTED |
| 无新迁移；V103 未占用 | IMPLEMENTED |

## 测试

| 命令 | 结果 | 原始输出 |
| --- | --- | --- |
| `mvnw -B -ntp -DskipTests compile` | exit=0 | `raw/g-compile-final.txt`、`raw/g-compile-final.exit.txt` |
| 定向 planning/router/gateway/shadow/orchestrator 6 类 | `Tests run: 59, Failures: 0, Errors: 0, Skipped: 0`；exit=0 | `raw/g-targeted-final4.txt`、`raw/g-targeted-final4.exit.txt` |
| 清理测试 import 后 Router 快速回归 | `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`；exit=0 | `raw/g-router-final.txt`、`raw/g-router-final.exit.txt` |
| 全量中 4 个既有 Agent Postgres 类单独复跑 | `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`；exit=0 | `raw/g-agent-flake-retest.txt`、`raw/g-agent-flake-retest.exit.txt` |
| 全量 `mvnw verify` 第二轮（旧代码） | `Tests run: 3080, Failures: 1, Errors: 5, Skipped: 61`；exit=1；5 个既有 Agent Postgres 时序错误 + 1 个 planning gateway 测试连接抖动 | `raw/g-verify-round2-failures.txt`、`raw/g-verify-round2-summary.txt` |
| 全量 `mvnw verify` 第三轮（本 lane 最终代码） | `Tests run: 3090, Failures: 0, Errors: 3, Skipped: 61`；exit=1；3 个错误均为既有 Agent Postgres 时序用例 | `raw/g-verify-round3-summary.txt`、`raw/g-verify-round3-failures.txt`、`raw/g-verify-round3.exit.txt` |

第三轮全量中 G 相关 6 类均未出现 failure/error：
`TaskPlanGenerationOrchestratorIntegrationTest` 14、`TaskPlanGenerationOrchestratorTest` 7、
`PlanningDraftGatewayClientTest` 8、`PlanningDraftPropertiesBindingTest` 6、
`PlanningDraftRouterTest` 15、`PlanningDraftShadowServiceTest` 9。

定向覆盖：

- `python` mode 可绑定启动；默认仍 `java`；未知 mode 仍启动失败。
- 合法 Python 草稿经真实 `PlanningDraftRouter` → `PlanningDraftParser` → `TaskPlanDraftValidator` → 既有提交路径；Java 只执行 detail；confirmation 行数为 0。
- 非法 plan JSON、未知字段、空/超限 shape、Validator HARD/异常、worker timeout/unavailable/schema/unexpected、deadline、snapshot、活跃复核失败均回退 Java 并产生稳定 marker。
- 调用前取消与调用后取消均不交付草稿；调用后取消保留 `python_canceled` 诊断。
- 任务数超过 `maxTaskCount` 时回退；deadline 参数取运行预算与配置上限较小值。
- Python 回退时 Java skeleton + detail 各执行一次，最终仍为 `READY` 且 confirmation 行数为 0。
- 既有 W5 shadow 的 java 零成本、触发/截断/饱和、Validator 重跑、指标与失败隔离回归。
- 测试稳定性修复（仅测试代码，不改断言语义）：shadow 指标等待同时覆盖 failure counter 与
  latency timer；短读超时用例预热 stub handler 并把本地 connect 上限放宽到 5s，避免全量负载
  把“请求尚未到达”误判成产品超时。

## 未执行 / 缺口

- `NOT_RUN`：真实 Python worker 的 `shadow → python` 本地流量与一致率/失败率/P95；由中心 E1 用真实 worker 补测。
- `NOT_RUN`：真实 Python worker 的启动/契约 E2E；本 lane 只用 Java 侧 stub/契约测试。
- `NOT_RUN`：前端门禁；本 lane 未改前端。
- `NOT_RUN`：规划质量判定；本轮只记录路由与回退数字，不宣称质量结论。
- `NOT_RUN`：全量门禁 exit=0。第三轮 `exit=1` 仅因 3 个既有 Agent Postgres 时序用例
  （`AgentCrashRecoveryTimingPostgresTest`、`AgentDelegationPostgresTest`、
  `AgentRemoteOutcomePostgresTest`）在整套并发负载下报 `NoSuchElementException`；同 4 个类
  （含 `AgentRepositoryIntegrationTest`）单独复跑 `116/116`、exit=0。G 相关 6 类在全量中
  均为 `Failures: 0, Errors: 0`。

## 关键边界

- Python 不写业务表、不创建审批、不改计划版本/状态机；Java router 只追加 attempt 诊断。
- Python `sourceRefs` 为空；`withSources` 仍由 Java 服务端上下文绑定并重跑 Validator。
- Python 响应为完整草稿，skeleton 候选会剥离 detail 字段；detail 继续 Java，避免把 Python 自校验当成 Java 事实。
- 默认 `java` 路径零外呼、零快照、零 router 指标；`shadow` 保持既有异步只读双跑。
- 当前规划主链没有运行级剩余预算，router 的 `remainingMillis` 由编排器传无界值；配置上限仍是硬 deadline。

## 证据文件

- `raw/g-compile-final.txt` / `.exit.txt`：最终编译。
- `raw/g-targeted-final4.txt` / `.exit.txt`：59 项定向测试。
- `raw/g-agent-flake-retest.txt` / `.exit.txt`：全量中 4 个既有 Agent Postgres 类复跑。
- `raw/g-verify-round2-failures.txt`：第二轮全量失败原始摘要。
- `raw/g-verify-round2-summary.txt`：第二轮全量总数与 BUILD 结果。
- `raw/g-verify-round3-summary.txt` / `raw/g-verify-round3-failures.txt` / `raw/g-verify-round3.exit.txt`：
  第三轮全量结果（最终）与仅剩的 3 个既有 Agent Postgres 错误。
- `raw/g-verify-round3-g-tests.txt`：第三轮全量中 G 相关 6 类的逐类结果。
- `raw/g-shadow-retest.txt`、`raw/g-gateway-retest.txt`：测试竞态修复前后的单类复跑。
- `docs/design/planning-python-skeleton-mainchain-2026-11-14.md`：路由语义、预算与回退矩阵。
