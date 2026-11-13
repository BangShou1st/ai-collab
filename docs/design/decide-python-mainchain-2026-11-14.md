# Decide Python 主链（F2）

> 日期：2026-11-14  
> 基线：`dev @ e674dc0f`  
> 默认：`DECIDE_MODE=java` 保持不变

## 1. 边界

F2 只把 Python 的**单轮决策建议**接到既有 Java Agent 运行时。Python 不执行工具、不写业务表、
不创建审批、不解析租约；Java 仍是唯一控制面。

路由条件（全部满足才调用 Python）：

1. `decide.mode=python`；
2. 根运行（`depth=0`）；
3. `PROJECT_RESEARCH` 且 Scenario 不允许写工具；
4. 当前暴露集合至少有一个只读工具；
5. 不是强制收尾轮，剩余运行时间与输入预算为正。

## 2. 路由语义

`DecideRouter` 在 coordinator 发起 Java 模型调用前执行：

| Python decision | Java 行为 | run 事件标记 |
| --- | --- | --- |
| `CALL_TOOL`，工具在本次 exposed 只读集合内且参数过 Schema | 生成带 `PYTHON_DECIDE` 来源的合成模型轮次，交给既有 `AgentToolCallExecutor` 复核暴露集合、权限、预算、审批、租约、幂等和取消 | `python:CALL_TOOL` |
| `FINAL` / `ASK_USER` / `NO_OP` | 回退 Java 模型生成文本/继续决策 | `python_fallback:decision_*` |
| 未知工具 / 写工具 / Schema 不符 | 不执行，回退 Java | `python_fallback:unknown_tool|write_tool|schema` |
| 超时 / HTTP / worker 不可用 / 非法 JSON / 契约不符 | 回退 Java | `python_fallback:worker_*` |
| 剩余时间或输入预算不足 | 回退 Java | `python_fallback:deadline|budget` |
| 非根运行、非目标 Scenario、收尾轮 | 回退 Java | `python_fallback:scenario` |

回退标记写入 `MODEL_STARTED.decideRoute`，并记录低基数 Micrometer 指标
`decide.router.fallback{reason}` / `decide.router.calls{decision}` / `decide.router.latency{outcome}`。

## 3. 不变量

- **不绕过审批**：Router 的候选集合只含只读工具；即使 Python 返回写工具名，也直接回退。
- **不直接执行**：Router 只构造 `ModelTurnResult`，执行仍由 `AgentToolCallExecutor` 完成。
- **快照有界**：复用 W4 shadow 的 goal/facts/observations/tools 上限、canonical hash 与截断规则。
- **幂等**：`DecideGatewayClient` 继续使用
  `sha256(runId|attempt|turnNo|snapshotHash)[:32]` 作为 `Idempotency-Key`；工具调用 id 绑定已持久化的
  `modelCallId`。
- **deadline**：取 `min(decide.deadline-ms, 当前运行剩余毫秒)`；预算输入取
  `min(24000, 本次请求可用输入)`。
- **取消**：Python 回退时在调用 Java 模型前复核取消；Python 成功时先持久化模型轮次/用量，
  工具执行器在启动工具前再次复核取消。
- **默认零成本**：`java` 不调用 worker、不构造快照、不记录 router 指标；`shadow` 继续走既有异步观察。

## 4. 失败可区分

回退原因使用稳定词表，不把生产失败伪装成“已执行”或“已验证”。Python 成功但决策为 FINAL/ASK_USER/NO_OP
也不是 Java 的最终事实，必须由 Java 文本轮收口并记录 `python_fallback:decision_*`。

## 5. 验收

- `DecidePropertiesBindingTest`：`python` 可绑定启动，默认仍 `java`，未知 mode 仍启动失败。
- `DecideRouterTest`：合法 CALL_TOOL、未知/写工具、Schema、FINAL、超时、预算/deadline、默认 java 零外呼。
- `AgentRuntimeCoordinatorTest`：Python CALL_TOOL 不调用 Java 模型，但仍进入既有工具执行器。
- `AgentDecideShadowServiceTest`：既有 shadow 只读/指标/失败隔离不回归。
