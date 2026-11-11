# J0-2 模型窗口驱动的软压缩触发策略报告

- 日期：2026-11-11
- 任务：J0-2 移除 v2 的 256K 绝对软压缩触发顶，未知窗口进入显式保守模式
- 基线：`dev` @ `36df686c`（J0-1 已统一 Token 计量）
- 结论：v2 触发线 `T = floor(H * 0.85)`，不再有 256k 绝对顶；v1 旧运行按持久化的
  `context_policy_version=1` 继续使用 256k 触发上限；未知窗口仍使用 50k 保守回退，
  但改为可观测的显式保守模式（`conservativeMode` + `agent.context.window.unknown` + 告警日志）。

## 1. 触发线公式

```text
H = W - R - S
v2: T = floor(H * 0.85)
v1: T = min(256000, floor(H * 0.85))      # 仅恢复旧运行，非 v2 策略
L = floor(T * 0.50)                        # 两种版本相同
```

其中 `W` 为解析到的模型上下文窗口，`R` 为本次实际出站的最大输出预留，`S` 为安全余量。
`T` 只触发旧轨迹整理，不收紧 `availableInputTokens`；达到 `T` 不构成拒绝请求的理由。

## 2. 旧 vs 新触发线

默认 `R=8000`、`S=2000`（8k 窗口用例使用本次出站输出 1000，以得到非零 H）：

| 窗口 W | H | 旧 T（min 256k） | 新 T（v2，0.85H） | 新 L | 变化 |
| --- | ---: | ---: | ---: | ---: | --- |
| 8,000（输出 1,000） | 5,000 | 4,250 | 4,250 | 2,125 | 不变（0.85H < 256k） |
| 128,000 | 118,000 | 100,300 | 100,300 | 50,150 | 不变（0.85H < 256k） |
| 200,000 | 190,000 | 161,500 | 161,500 | 80,750 | 不变（0.85H < 256k） |
| 1,000,000 | 990,000 | 256,000 | 841,500 | 420,750 | 触发后移约 3.29×，目标后移约 3.29× |
| 未知 | 50,000（回退） | 42,500 | 42,500 | 21,250 | 数值不变，新增显式保守模式 |

v1 路径：

| 窗口 W | v1 旧 T | v1 新 T | v1 可用输入 |
| --- | ---: | ---: | ---: |
| 1,000,000 | 256,000 | 256,000（legacy） | 仍受应用单次上限 50,000 与运行剩余额度收紧 |

## 3. 行为变化

1. **v2 大窗口压缩更晚**：1M 窗口的 `T` 由 256k 变为 841.5k，压缩后软目标由 128k 变为 420.75k。
   这是移除绝对顶的直接结果：大窗口模型可以携带更多原始上下文，直到接近自身安全可用输入。
2. **小窗口行为不变**：窗口 ≤ 约 301k 时 `0.85H < 256k`，v1/v2 触发线数值一致
   （128k、200k、8k 用例均有断言）。
3. **v1 完全不变**：持久化为 `context_policy_version=1` 的运行继续使用 256k 触发上限，
   同时保留应用单次 50k 上限与运行剩余输入额度收紧；恢复/续跑不重算历史。
4. **未知窗口不再静默使用固定数字**：仍回退 `H=50000`、`T=42500`，但 `Budget.conservativeMode=true`；
   协调器在初次与刷新解析后记录 `agent.context.window.unknown`（provider/model 标签，空值归入
   `unknown`）并输出不含正文/密钥的告警日志；`RUN_BUDGET_EXCEEDED` 的
   `PER_REQUEST_INPUT` 载荷新增 `conservativeMode` 字段。未新增事件类型。
5. **未验证档案与未知窗口区分**：`windowEstimated=true` 仍覆盖“未知或未验证档案”，
   `conservativeMode` 只在窗口真正未知时为 true；未验证档案有数值可用，不进入 50k 回退。
6. **辅助摘要请求**：窗口未知时同样告警（runId/provider/model/fallback），仍使用同一 50k 回退；
   摘要器不新增 metrics 依赖。

## 4. 旧运行兼容性

- `agent_run.context_policy_version`（V64）是唯一持久化策略维度；J0-2 没有新增列、没有新增迁移。
- 旧运行按自己的持久化版本解释：v1 继续使用 `V1_LEGACY_SOFT_COMPACTION_TRIGGER_TOKENS=256000`，
  不会因为部署新代码而按 `0.85H` 重算历史压缩节奏。
- v2 运行（含新根运行与终态重试派生运行）使用新公式；每次请求都从
  `run.enforcesCumulativeTokenLimits()` 得到同一版本判据。
- **边界说明**：J0-2 之前创建的 v2 运行没有独立的“256K 开关”持久化维度，恢复后的后续
  请求按 v2 新公式解释（触发更晚、不会更早丢上下文）；已持久化的摘要、覆盖进度与历史事件
  不重算。若未来需要逐运行冻结旧 v2 触发线，必须新增持久化策略维度，本批按设计不新增迁移。
- 未修改任何历史 Flyway 迁移，未修改数据库行/历史数据。

## 5. 回滚

- 回滚方式：revert 本任务提交即可；无数据迁移、无数据库状态需要回退。
- 回滚后旧代码会重新使用 256k 绝对触发顶；未知窗口仍按 50k 回退（届时无
  `conservativeMode` 字段与 `agent.context.window.unknown` 指标）。

## 6. 验证

- `mvnw -B -ntp -o -DskipTests compile`：见任务最终报告。
- 目标单测：`AgentContextBudgetTest`、`AgentContextReliabilityD1D8RegressionTest`、
  `AgentAuxiliaryOutboundRegressionTest`、`AgentRunContextCompactionRegressionTest`、
  `AgentRuntimeMetricsTest`、`AgentContextSummarizerTest`、`AgentRuntimeCoordinatorTest`。
- Testcontainers/PostgreSQL 用例（含 `AgentRunContextCommitPostgresTest`）：本环境不运行，
  列为未验证项；仅完成编译期构造器更新，行为断言未改动。
