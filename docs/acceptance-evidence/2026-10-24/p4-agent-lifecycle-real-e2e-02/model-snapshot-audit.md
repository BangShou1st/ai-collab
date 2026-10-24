# P4-AGENT-LIFECYCLE-REAL-E2E-02：Agent 模型快照审计

> 上轮观察项：部分 Run 的 `agent_run.model_name / model_provider / model_configuration_snapshot` 为空，页面模型芯片却正常显示。本轮以源码 + 全量数据库核查定论。

## 1. 数据库实况（2026-10-24 本轮核查）

`agent_run` 全量 21 行（13 SUCCEEDED / 7 FAILED / 1 WAITING_FOR_USER_INPUT）：

| 列 | 非空计数 | 说明 |
| --- | --- | --- |
| `model_name` (varchar) | **0 / 21** | 全部 NULL |
| `model_provider` (varchar) | **0 / 21** | 全部 NULL |
| `model_configuration_snapshot` (jsonb) | **21 / 21** | 全部有值 |
| `model_configuration_id` (uuid) | 21 / 21 | 全部有值 |

`model_configuration_snapshot` 内容示例（24b71f55）：`{"mode":"NATIVE_TOOLS","model":"nvidia/nemotron-3.5-lightning:free","provider":"OPENAI_COMPATIBLE","updatedAt":"...","budgetEnforced":false,"configurationId":"6854fb22-...","maxOutputTokens":4000}`。

逐轮权威记录：`agent_step` 中全部 68 行 `MODEL_TURN` 的 `model_name/model_provider` **100% 有值**（实测抽查 5 行均为 nemotron-3.5-lightning:free / OPENAI_COMPATIBLE）。

## 2. 源码归因（为什么 Run 级 varchar 列为空）

两条记账路径并存：

- **v1 遗留路径会写 Run 级列**：`AgentRunEventRecorder.recordFinal(run, ChatCompletionResult, answer)`（:863-900）与 `recordToolResult(run, completion, call, result)`（:903-938）的 UPDATE 带 `model_provider=?,model_name=?`（:888、:928）。
- **v2 现行路径不写**：所有新 Run 固定写入 `context_policy_version=2` + `budget_semantics='SEPARATED'`（`AgentRepository.createRun` :144），协调器走 `recordModelTurn`（:1089-1150）与 `recordFinal(run, String content, List citations, boolean)` 重载（:1206+）——这两个方法的 UPDATE **不含** model 列。
- v2 的模型身份落在三处：①`agent_step.MODEL_TURN` 行级持久化（:1113-1128）；②`MODEL_COMPLETED` 持久事件 payload 携带 provider/model，"事件重放与刷新恢复共用"（:1140-1147）——这正是前端模型芯片的来源（`use-agent-workspace.ts` currentModelFromEvents，事件流优先、runDetail.modelConfiguration 兜底）；③`AgentModelConfigurationStore.require` 在配置身份（id+updated_at）变化时回写 `model_configuration_id/_updated_at/_snapshot`（`AgentModelConfigurationStore.java:29-40`），首次模型轮次即写入。

类注释明确设计取向："配置编辑/分配/默认变更在下一轮生效；agent_run 快照字段仅作诊断，不再锁定运行"（`AgentModelConfigurationStore.java:13-14`）。

## 3. 结论

**定性：设计行为 + 遗留 schema，不是快照缺失缺陷。**

- 快照没有缺失：配置级快照（jsonb）21/21 覆盖、逐轮模型身份（agent_step）100% 覆盖、事件流可重放（MODEL_STARTED/MODEL_COMPLETED 均带身份）。审计追溯问题（"这个 Run 当时用的什么模型、每一轮是谁"）可由 `agent_step` + `agent_usage_settlement` + 事件权威回答，且**比 Run 级单值列更准确**（Run 级单值在多轮换配置场景只能表达最后一轮）。
- 暂停恢复后允许模型配置变化是明确设计（每轮重新解析，`require` 每轮读当前配置），逐轮记录恰好是这种设计下唯一正确的审计形态。
- `model_name/model_provider` 两个 varchar 列成为**仅 v1 路径写入的死列**（新 Run 永远 NULL）。不建议本轮改动：回填历史值属于伪造审计数据（任务书禁止），新写入点改动收益低于其引入的双源不一致风险。建议后续版本二选一：v2 收尾时顺带写入最后一轮身份（仅诊断用途），或迁移中明确标记两列为 legacy 并从展示层移除引用——均属独立小任务，不阻塞发布。

## 4. 审计问题逐条回答（任务书第十一节）

1. 预期何时写入：仅 v1 recordFinal/recordToolResult；v2 设计上不写（见 §2）。
2. 故意还是遗漏：v2 不写是刻意的（"仅作诊断，不再锁定运行"）；列本身遗留未清理。
3. Run 创建时不写；首次模型调用经 require 写入 config 三元组；响应轮次写 step/event。
4. 用户默认模型：同样经 `providers.resolve(requesterId, AGENT)` 解析，快照/step 照常覆盖（实测默认模型 U04 全部有值）。
5. 不同轮次可切换 Provider：可以（每轮重解析），step 逐轮记录可还原。
6. Run 历史可准确追溯每轮实际模型：是，权威来源 `agent_step.MODEL_TURN`。
7. 暂停恢复允许配置变化：是，逐轮记录随之更新。
8. 已完成 Run 独立追溯：是，不依赖当前配置。
9. `agent_step`/event 是否已具备更权威逐轮记录：是，且已 100% 落实（本轮实测）。
