# P3-GEMINI-COMPAT-03：工具调用 ID 全运行周期唯一性验收

日期：2026-10-21（Asia/Shanghai）
目标分支：`dev`
结论：**CONFIRMED_AND_FIXED**

## 一、基线与范围

- 起始 `dev` / `origin/dev`：`0bbfd38f5f8ab4fd5a533d542a48878d774a5d1b`；起始工作区干净。
- `main` 与 `origin/main` 始终为 `6303b4a8011ebb1b3f9617df3e910ef7c7f65df7`，未修改。
- 代码修复提交：`cde8fea88b9e9f64f8f25eb208ae0e19e111c092`。
- 本轮没有数据库迁移、索引、API、前端或真实业务数据库改动。PostgreSQL 行为由 Testcontainers 临时 PostgreSQL 17 验证。

## 二、根因与实际影响

`GeminiModelAdapter` 先前用本次 `ModelTurnCommand.messages()` 里可见的 assistant functionCall 数量生成 `gemini-call-N`。RUN_CONTEXT 摘要会覆盖已完成工具调用的 step；V2 组合器随后不再将这些旧 assistant/tool 消息放进下一次请求。Legacy 组合器也只保留最近一段历史。因此，同一 Run 的下一次 Gemini 响应会在适配器层重新从 `gemini-call-0` 开始。

真实 PostgreSQL 追踪显示，`agent_tool_invocation` 的持久身份是独立的 `invocation_id`，并受 Run、轮次和 ordinal 约束；`tool_call_id` 本身没有全局唯一索引。`knownInvocationResult` 与结果更新还限定当前 MODEL_TURN，并核对工具名和参数，因此这次确定性复现没有误取上一轮结果或污染上一轮数据库结果。重复的 `tool_call_id` 仍违反 Run 周期唯一约定；活动事件在部分失败路径会仅携带 `callId`，前端按 `invocationId` 缺失时回退到 `callId` 分组，重复值可能把不同轮次的生命周期事件合并。

## 三、确定性复现与修复证据

新增真实 PostgreSQL 回归测试 `PersistedModelTurnRecoveryPostgresTest.geminiToolCallIdRemainsUniqueAfterRunContextCoversPreviousCallAndRecoveryReusesIt`，模型响应由受控替身提供，不访问 Provider：

1. 首轮持久化 `gemini-call-0`，记录参数和结果，再提交覆盖该工具观察的 RUN_CONTEXT 摘要。
2. 后续 V2 请求包含摘要但不包含旧 assistant toolCall；修复前回归断言实际得到重复的 `gemini-call-0`，期望 `gemini-call-1`，测试失败（1 test，1 failure）。
3. 修复后以持久化 `run.toolCallsUsed()` 加本批次 ordinal，为新的 Gemini 调用分配 `gemini-call-1`、`gemini-call-2`。测试验证两组参数、三项结果、三个不同 `invocation_id` 均对应正确，并验证第二个并行调用的 `thoughtSignature` 保留。
4. 在已保存 MODEL_TURN 尚未消费时再次接管，Coordinator 复用持久化响应中的原 ID，模型替身调用次数仍为 1。
5. `GeminiTurnContractTest.adapterVisibleIdCounterRestartsWhenRunContextHidesPriorCall` 单独证明适配器可见历史计数会重置；运行级 ID 则在 Coordinator 持久化前重定界。

修复仅在 Coordinator 收到**新鲜 Gemini 响应**后、MODEL_TURN 入库前重定界 ID。其他 Provider 原样返回；同一响应内用 ordinal 保证并行调用互异；`thoughtSignature` 随调用复制。恢复路径读取已持久化响应，不重新分配 ID。既有计数已由成功、失败和拒绝的工具结果递增，无需迁移或随机 ID。

## 四、测试与 CI

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 修复前确定性复现 | `mvnw.cmd -B -ntp "-Dtest=PersistedModelTurnRecoveryPostgresTest#geminiToolCallIdRemainsUniqueAfterRunContextCoversPreviousCallAndRecoveryReusesIt" test` | 1 项失败：摘要覆盖后实际仍为 `gemini-call-0` |
| 定向 PostgreSQL 回归（修复后） | 同上 | 1 项通过，0 失败/错误/跳过 |
| Gemini Provider 合约 | `mvnw.cmd -B -ntp -Dtest=GeminiTurnContractTest test` | 26 项通过，0 失败/错误/跳过 |
| V2 消息组合器 | `mvnw.cmd -B -ntp -Dtest=AgentModelMessageComposerV2Test test` | 17 项通过，0 失败/错误/跳过 |
| PostgreSQL 恢复与 Repository | `mvnw.cmd -B -ntp -Dtest=PersistedModelTurnRecoveryPostgresTest,AgentRepositoryIntegrationTest test` | 64 项通过，0 失败/错误/跳过 |
| Provider 契约（8 类） | `mvnw.cmd -B -ntp -Dtest=ModelProtocolHttpTest,KiloGatewayModelExecutionStreamContractTest,KiloGatewayModelTest,ZenKnowledgeStreamContractTest,AnthropicTurnContractTest,OpenAiTurnContractTest,OpenAiTurnStreamingTest,ModelProviderAdapterContractTest test` | 92 项通过，0 失败/错误/跳过 |
| 完整后端验证（本机安全运行） | `mvnw.cmd -B -ntp "-Dtest=!GeminiLiveAcceptanceTest" verify` | BUILD SUCCESS；1574 项，1556 通过、0 失败、0 错误、18 跳过。整个 live 类有 8 项，因其会读取本机凭证而排除；18 项为既有环境门控测试。 |
| Testcontainers 守卫 | 对 CI 的 10 个 required Surefire 类逐项检查 | 10/10 实际运行，无整类跳过；本机守卫通过 |
| 差异检查 | `git diff --check` | 通过 |

首次未加排除项的 `mvnw.cmd -B -ntp verify` 在本机触发 `GeminiLiveAcceptanceTest`。该测试的 `@BeforeAll` 会直接读取 `C:\Users\32962\Desktop\key2.txt`；看到首条 live 请求成功输出后立即中断了 Maven。记录确认至少 1 次 `gemini-3.1-flash-lite` 请求收到响应；停止时是否已有后续请求在途无法确认，未将该调用或该 live 测试套件记作验收通过。该次运行在更早的 `PersistedModelTurnRecoveryPostgresTest` Surefire 报告中另有 1 项本机 Testcontainers 连接错误（Windows `BindException: Address already in use`）；随后不触发 live 类的完整运行中，该类 24 项全部通过。没有手动打开或复制 key 文件内容。

本轮没有前端改动，也没有本机前端测试命令；CI 的 Frontend Job 有执行 Lint、Typecheck、Test 与 Build。

**GitHub Actions：**代码推送 SHA `cde8fea88b9e9f64f8f25eb208ae0e19e111c092` 对应 [Run `39540934011`](https://github.com/BangShou1st/ai-collab/actions/runs/39540934011)，attempt 1，completed success。Backend Job `119028252955` 与 Frontend Job `119028253148` 均成功。Backend Surefire：**1582 项、1557 通过、0 失败、0 错误、25 跳过**；205 个测试类报告。容器守卫通过 10/10 required 类。`GeminiLiveAcceptanceTest` 8 项中 7 项 live 测试跳过，1 项本地错误分类测试通过，没有由 CI 发出的真实 Gemini 调用。最终文档提交 SHA 与其 CI 留在交付消息中核实，不在自身内容中引用。

## 五、P3-GEMINI-COMPAT-02 历史记录纠正

- P3-02 实际文档提交 SHA 为 `0bbfd38f5f8ab4fd5a533d542a48878d774a5d1b`，此前交付消息中的 `0bbfd3824bb8d363290680321aa16d80d2ca2cb0` 不正确。
- 两次历史 Backend CI（代码 Run `39497711679` / attempt 1 / head `872a7ad6c9d9b005223b717700449eaf50e0850c`；文档 Run `39498724955` / attempt 1 / head `0bbfd38f5f8ab4fd5a533d542a48878d774a5d1b`）均为 1580 项、1555 通过、0 失败、0 错误、25 跳过。25 项 = 原有 18 项环境门控跳过 + `GeminiLiveAcceptanceTest` 的 7 项 live 跳过；该类另有 1 项本地错误分类测试通过。两次 10 项容器守卫均通过。
- 已修正 P3-02 README 的 1580/1580、18 skipped、最终 SHA 和 CI head SHA 记录。普通 `git revert` 可用作回退，但是否冲突及是否精确恢复某个历史工作树取决于后续差异。

## 六、剩余风险与回退

- `GeminiLiveAcceptanceTest` 当前会因桌面 `key2.txt` 存在而自动进行 live 调用。为避免本地完整验证再次发起请求，本轮本地完整套件显式排除该类；代码 SHA 的 CI 报告确认 7 项 live 测试跳过、1 项离线测试通过。
- 本轮未更改旧数据、数据库架构或运行环境，不需要数据迁移或环境恢复。
- 回退代码可执行普通命令 `git revert cde8fea88b9e9f64f8f25eb208ae0e19e111c092`；先检查差异并按需解决冲突，该命令不保证无冲突或恢复到精确历史快照。
- CI 全绿后，Gemini 工具调用 ID 唯一性专项可关闭。本轮结束后不自动启动其他功能工作。
