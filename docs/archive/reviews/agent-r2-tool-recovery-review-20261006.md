# R2 限定补修后的工具恢复复核

> **状态更新（2026-10-06）：下面两处回归已修复，本批 R1/R2 已收口。** 总工具额度在恢复时只计未完成调用，单轮上限仍检查原批次；旧工具响应继续进入原恢复校验，旧文本保留保守回退。23 项恢复回归实际执行且通过，其中新增用例使用真实工具执行器与安全替身验证剩余执行次数、全部复用及来源模式拒绝。全量日志与 surefire XML 均为 1117 项、1106 通过、11 显式跳过、零失败/错误。详见 `docs/agent-recovery-reliability-report.md` 第 11 节。本文件下文为当时的缺陷和补修提示词，保留作历史记录，不应重复执行。此次复核仅检查源码与已有测试证据，没有重新跑全量或调用真实模型。

日期：2026-10-06。分支 `codex/context-foundation`，HEAD `d5f09ab`，复核基线为当前全部继承未提交改动。本次仅新增审查文档与复现证据，未改生产代码。

## 已核实完成的部分

上一份复核列出的三个缺陷，其主链已经修正：

- 未消费响应的恢复分支在下一次模型请求的 `decide` 之前。
- 真正出站请求采用的 `finalizing` 值随响应同事务持久化，恢复不再用落库后的计数改写该意图。
- 正常与恢复共用 `afterResponseSaved` 的实际输入超限检查与分派。
- `recoveryBatch` / `requestLegacyMode` 正确传到工具执行器；来源模式校验仍在执行器内。
- 新增 7 项正式回归和既有 9 项均执行且通过；协调器和测试源码无 TEMP-PROBE、ScratchR2BoundaryProbeTest 残留。

归档全量日志为 1110 项、1099 通过、11 跳过、零失败/错误，BUILD SUCCESS。当前 surefire 目录另保留一个已删除 Scratch 测试的旧 XML（时间为 00:51），直接累计所有 XML 会得到 1111；它不是本次全量新增测试，不能据此否定日志的 1110。排除这一份旧报告后再核对统计。本复核没有重新运行后端全量。

但是，共享入口新增的检查和旧意图回退改变了原工具恢复行为。下面两处均已在隔离 PostgreSQL 上实际复现，因此本批结果恢复还不能整体收口。

## 1. 部分完成的工具批次被重复计入总额度（P1）

源码：

- `AgentRuntimeCoordinator.java:531–537`：恢复时仍把 `turn.toolCalls().size()` 作为本次新增调用数传入 `validateToolBatch`。
- `AgentConvergencePolicy.java:63–65`：校验 `run.toolCallsUsed() + batchSize`。
- `AgentRunEventRecorder.java:662`：每个已提交工具结果都已经推进 `tool_calls_used`。
- `AgentToolCallExecutor.java:131–135`：执行器本来会按持久化 invocation 结果复用已完成调用，但新校验在到达执行器之前就拒绝了整个批次。

复现现场使用 ITERATION_PLANNING 的原有限额 16：

1. 已有 12 项工具完成记录，原请求返回 4 项调用，准入 `12 + 4 = 16` 合法。
2. 响应按新链路保存（`finalizing=false`、`source_mode=NATIVE_TOOLS`）。
3. 其中 2 项结果提交后退出，当前已用 14，待执行 2。
4. 正确总量为 `14 + 2 = 16`；接管却校验 `14 + 4 = 18`。
5. 协调器提前返回预算兜底，把剩余 2 项 invocation 标为 SKIPPED，工具执行器零调用。

原始输出：

```text
PROBE_PARTIAL_TOOL beforeUsed=12 budget=16 batch=4 completed=2 actualUsed=14 remaining=2 recovered=BUDGET_EXCEEDED executorCalls=0 skipped=2 modelCalls=0
```

修法边界：单轮数量约束仍校验原批次，总额约束在恢复时只计尚需执行/记结果的调用；依据原 invocation 身份和结果识别已完成项，不能按列表大小重复累计，也不能直接删除总量检查。完全完成而尚未收尾的批次同样不能重新消耗额度。

## 2. 旧规划工具响应被误判为禁止工具的收尾轮（P2）

源码：

- `AgentRuntimeCoordinator.java:561–564`：缺少 `finalizing` 元数据时直接使用 `isCoreActionPending`，不区分最终文本和待处理工具响应。
- `AgentRuntimeCoordinator.java:524–527`：`finalizing=true` 且含工具调用就拒绝分派。

复现现场：目标“请生成迭代规划草稿”，已有未消费 `start_task_plan` 调用，来源为 NATIVE_TOOLS，原请求意图元数据缺失，未实际启动规划。接管把“核心动作尚未完成”当成“这轮原本禁止工具”，最终 FAILED，工具执行器零调用。

原始输出：

```text
PROBE_OLD_TOOL finalizingMissing=true sourceMode=NATIVE_TOOLS corePending=true recovered=FAILED executorCalls=0 modelCalls=0
```

这一兼容回退用于旧最终文本时可以保守返回部分完成；不能直接套到旧工具批次。“核心动作尚未执行”也可能说明正要消费执行它的调用，而非说明该调用违反了原请求协议。

修法边界：为缺少意图标记的旧工具响应明确保留既有批次恢复路径，通过 invocation 身份、source_mode、权限、Skill 白名单、审批与执行限额校验继续处理。不要因核心动作未完成而先整体否决。对原记录中明确持久化 `finalizing=true` 的工具响应，仍按协议违规拒绝。旧文本的保守回退与已保存答案保留规则不变；无法还原的历史意图仍如实说明。

## 证据边界

一次性复现源文件和日志位于 `docs/acceptance-evidence/2026-10-06/recovery-review/tool-batch-review/`。

借用当前 `PersistedModelTurnRecoveryPostgresTest` 的隔离 PG 装配，实际调用仓储、记录器、收敛策略和协调器；使用真实 invocation 行、工具结果和新 claim，模型、事件服务、工具执行器为替身。第 1 项直接证明剩余调用在到达执行器之前被标为 SKIPPED；第 2 项证明旧规划调用在到达权限/来源模式校验之前被收尾判断拒绝，不声称已经执行真实规划写入。这些探针不替代正式回归或 Spring 事务原子性测试。

没有修改现有全量日志，没有重跑真实模型、浏览器、规划或 24 轮，没有操作业务数据库。复现容器已停止，临时源文件和参数文件在归档后清理。

## 可直接交给开发会话的提示词

请在 `E:\project\ai-collab` 继续本批 R2，仅补本文件已经复现的两处工具恢复回归。先读适用 AGENTS.md、本文件、`docs/agent-runtime-maintenance.md` 与最新 R2 报告，核对并保留完整工作区。不 reset/clean/stash/覆盖，不自动提交、推送、合并或部署。R1 和上次三个已修好的结果恢复问题保持原样。

1. 修复部分完成批次的总工具额度校验：恢复时根据已持久化 invocation 结果复用已完成项，只有剩余调用占新增额度；单轮数量仍检查原批次，真实超限仍拒绝。不移除来源模式、权限、审批或预算保护，不重复计数/业务效果。
2. 区分缺少意图标记的旧文本与旧工具响应：旧工具批次继续走原身份与来源模式校验的恢复流程，不把核心动作未发生等同于请求禁止工具；显式 `finalizing=true` 的协议违规响应仍拒绝。旧文本的保守部分完成语义保持。

在现有职责内完成最小修改，不增加框架、通用 checkpoint、独立预算服务或新的状态机，不重新做结构维护，不开发暂停/继续、到限续接、精确计费等功能。

正式回归先锁定当前行为的失败，再修复通过，至少覆盖：

- 总额度 16，已用 12，合法批次 4，先完成 2 再退出：接管仅执行剩余 2，最终计数 16；已完成结果/业务动作不重复，剩余调用不被错误 SKIPPED。
- 原批次全部完成但未收尾：恢复复用全部结果，不因 `used + 原批次大小` 再次超限，也不再次执行工具。
- 剩余调用确实超过额度时仍拒绝；原批次超过单轮上限仍拒绝；实际输入超限仍优先终止。
- 旧元数据 NATIVE_TOOLS 的合法规划调用可进入原恢复校验与执行链；LEGACY_READ_ONLY 的写调用仍拒绝；明确 `finalizing=true` 的工具响应仍拒绝。
- 旧文本保守回退、上次 16 项恢复回归、配置切换/source_mode、取消/旧 claim 与重复接管的既有保护继续通过。

使用隔离 PostgreSQL 和受控提交窗口，至少关键批次用真实工具执行器与安全替身工具验证剩余执行次数，而不是只断言 mock 参数。不要调用真实模型、业务服务或业务数据库；无前端/事件契约变化时不重跑浏览器/24 轮。必要回归通过后跑一次后端全量；统计排除已删除测试的旧报告，保留真实原始日志与跳过原因。

更新 R2 报告，区分上次三个已修边界与本次两处工具恢复回归。两处修复及对应回归通过后收口，不追加独立优化。清理本轮创建的隔离资源，全部修改保留未提交。
