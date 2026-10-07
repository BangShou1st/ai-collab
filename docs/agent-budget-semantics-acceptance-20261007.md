# 预算语义分离与委派收尾契约交付记录（2026-10-07）

> 基线：`codex/context-foundation` / `156090d`。
> 本轮目标：把"模型推进预算"与"工具调用预算"分开，让窄范围文档委派能在既定预算内完成有依据的总结，同时保持资源边界、恢复可靠性与诚实降级。不扩新功能、不引入第二套循环。
> 证据类型：[测试] 为自动测试（真实 PostgreSQL），[实验] 为真实模型 + 真实文档的浏览器实测，[环境] 为运行环境事实。

## 1. 背景：上一轮遗留的预算问题

上一轮（R12/R12b/R12c）修复后仍有两个结构性问题：

1. **并行工具批次逐项消耗推进步**：一轮 4 个并行 `get_document_outline` 记 4 步，子运行（提纲+检索+正文读取）在 8 步预算终点提前耗尽（真实 B-narrow 实验：7 步/5 工具即死）。
2. **批次超额度直接硬停**：模型一批请求超过剩余工具额度时整批被拒，直接进入证据兜底/预算终止，即使已有可信证据且模型预算尚足也不会尝试无工具总结。

## 2. 设计：`budget_semantics` 兼容标记（V63）

`agent_run` 新增列 `budget_semantics`（`COMBINED` = 旧语义，既有行默认；`SEPARATED` = 新语义），最小持久化标记，不改任何历史数据：

| 场景 | 规则 |
|---|---|
| 新根运行（createRun / createRetryRun） | `SEPARATED`：一次模型轮计一次推进，最终回答落库保留一次收口消耗；普通工具结果只计 `tool_calls_used`，不再逐项 +1 推进步（`TOOL_CALL_COMPLETED` 步骤、事件、invocation 身份全部保留，不减计数、不删事件） |
| 子运行（`documentResearchDelegationResult` 与旧 `recordDelegation` 两条路径） | 继承父运行的 `budget_semantics`（SQL 子查询取父值） |
| 旧运行恢复 / 崩溃接管 / 暂停续跑 | 保持 `COMBINED`：`steps_used` 按旧含义继续累计，不重算、不静默重解释 |
| 终态手动重试 | 派生的是全新运行（steps_used=0），采用 `SEPARATED` |

实现方式：`AgentRunEventRecorder` 中两处 `recordToolResult`、旧 `recordDelegation` 受理与 `documentResearchDelegationResult` 受理的 `steps_used` 赋值改为 `CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END`；模型轮路径（`recordModelTurn`、`recordFinal`、`recordInvalidDecision`、`recordBudgetExceeded(completion)`、`recordDecisionFailure`）保持 +1 不变。`AgentWorker` 预算前置检查与 `AgentConvergencePolicy` 公式对两种语义一致（数值型）。

## 3. 委派切分与受理门槛

`documentResearchDelegationResult` 重写预算切分（COMBINED/SEPARATED 都预留）：

- **委派自身占一次工具调用**（保持 R8 语义；SEPARATED 下不占推进步，COMBINED 下另占 1 步）；**子工具额度 = min(8, 父剩余工具 − 1)**——从扣除委派自身调用后的父剩余额度分配（修复旧代码在扣 1 之前切分导致的超额认购）。
- **父综合收尾预留 2 个推进步**（收尾模型轮 + 回答落库）：子步数 = min(8, 父剩余推进步 − 委派步成本 − 2)。回收后即使子运行用满全部预算，父运行仍留有综合收尾空间。
- **子输入/输出 = 父剩余的一半**（封顶 30000/8000），沿用 R12 的运行级等比预留思路，父综合请求需携带子证据与既有上下文；不再用与运行自身预算无关的大固定值卡死小运行。
- **拒绝受理**：剩余额度连"子最小研究（1 轮）+ 子收尾（轮+落库）+ 父综合收尾"都容纳不了（childSteps<3 / childToolCalls<1 / childInput<1000 / childOutput<1000）时，明确抛 `AGENT_TOOL_NOT_ALLOWED`（"父运行剩余预算无法容纳子运行最小研究与收尾，已拒绝受理"），不先启动再注定失败。模型收到拒绝后可用剩余预算直接研究。
- 父纯综合不强制预留补读工具；确需补读仍受剩余工具额度限制。回收仍只计一次（`resumeParent` 幂等键不变）。

## 4. 终止统一：无工具总结优先于硬停

- **批次超总额度**（单轮数量合规、新增调用超出剩余工具额度）且有可信证据（父自有成功工具结果 **或已回收子研究产出**）且收尾轮可负担（剩余推进步 ≥2 且模型轮次未耗尽）时：按请求批次规模消耗工具额度（LEAST 封顶）、整批跳过（PENDING 调用 SKIPPED、轮次标记已消费，恢复不重放）、落 `TOOL_BUDGET_BATCH_REJECTED` ERROR 步骤（诊断可见，不占推进步）并重新排队；下一次准入由收敛策略判定为 FINALIZE → **无工具总结轮**，不再"批次执行后才发现连收尾都无法完成"或直接硬停。新方法 `consumeToolBatchQuotaAndRequeue`。
- **单轮数量超限仍是协议违规**，不走无工具总结路径（模型应少调重试），维持原证据兜底/预算终止。
- **`decide`/`needsFinalRequest` 纳入子证据**：新增 `decide(run, limits, steps, summarizableChildEvidence)` 重载；已回收子研究产出（DELEGATION_COMPLETED 非占位内容）可作为 FINALIZE 依据。**不伪造父工具成功次数**——`successfulToolCalls` 仍只统计父自己持久化的 `TOOL_SUCCESS`；子来源仍以结构化 citations 进入父最终回答。
- **`inputBudgetExceeded` 先走证据兜底**：请求准入失败（组装超预算/摘要挤占/收尾超预算）时先尝试 `completeFromEvidence`（含 R12c 的子产出兜底），把已有产出作为预算部分回答交付；无证据才按原语义明确终止（事件带约束来源与原因）。已有的部分回答回传、证据兜底与缺口说明全部保留。

## 5. 测试（[测试]）

**红-绿流程**：先在未修复代码上复现失败再修复。

| 用例 | 红灯（修复前） | 绿灯 |
|---|---|---|
| `childParallelToolBatchesMustNotExhaustProgressionBudget`（新增） | `expected: SUCCEEDED but was: BUDGET_EXCEEDED`（与真实 B-narrow 一致） | 子运行 4+2 并行检索两轮 + 文本收尾 SUCCEEDED，steps=4（3 轮+落库）、tools=6 |
| `overQuotaToolBatchMustEnterNoToolSummaryWhenEvidenceExists`（新增） | `expected: QUEUED but was: BUDGET_EXCEEDED` | 批次被拒 → 额度按请求封顶消耗（→8/8）→ 重排队 → FINALIZE 无工具总结 → SUCCEEDED |
| `delegationSplitChargesDelegationToolAndReservesParentSynthesis`（新增） | 委派受理占推进步、子额度未扣委派自身、输入/输出未预留 | 父 steps=0/tools=1；子 8 步/7 工具/输入 25000/输出 8000；子继承 `SEPARATED` |
| `delegationRejectedWhenRemainingBudgetCannotFitChildResearchAndClosing`（新增） | 旧门槛 childSteps≥1 放行注定失败的委派 | 明确拒绝受理，子运行 0 条 |
| `legacyCombinedSemanticsKeepsOldStepAccounting`（新增） | 保护性用例 | COMBINED 运行工具结果仍占推进步；子运行继承 `COMBINED` |
| `AgentRepositoryIntegrationTest` 3 项更新 + `legacyCombinedSemanticsStillBumpsStepsOnToolResults` 新增 | 编码旧语义 | SEPARATED 下工具结果不占推进步；COMBINED 镜像保持旧记账 |
| `pausedParentMustStillCollectChildUsage`（R5）更新 | 委派受理不再占推进步 | 父 stepsUsed 4→3，回收语义不变 |
| `trulyOverBudgetRemainingCallsStillRejected` 更新 | 总量检查不放松（整批不执行），但额度耗尽+有证据时进入无工具总结 | 接管→QUEUED（tools 16/16 封顶、3 项 SKIPPED）→ 总结轮 SUCCEEDED |
| `stepBoundaryFinalizeAnswerSurvivesTakeoverOnBothPaths` 更新 | 接管消费不依赖准入判定的不变量保持 | 以 steps_used=max−1 重建边界现场后不变量仍成立 |

**回归规模**：全量后端 `mvnw test` **1207 项，1200 通过，0 错误，11 显式 opt-in 跳过**；唯一 7 处失败为迁移版本清单断言（Phase08/TaskPlanBean/LegacyApprovalCitation/NextStage 硬编码最新版本 61/计数 16/8），全部更新为纳入 V62/V63 后复验通过（日志 `ai-collab-backend/target/full-test-budget-semantics.log`）。R1–R12c 既有回归全部保留并通过（委派 23 项、持久恢复 23 项、暂停续跑 19 项、协调器/行为/仓库等全绿）。

## 6. 真实模型验收（[实验]，browser-skill 实测 Web UI）

[环境] 后端本轮构建 `mvnw spring-boot:run -Dspring-boot.run.profiles=local`（8080），vite dev（5173）；Ollama `qwen3-embedding:0.6b`（1024 维）活跃；项目「Zen规划冒烟」4 份文档 READY；模型 `mimo-v2.6-flash-free`（Zen 预置，与生产一致）。问题文本与上一轮实验固定一致以保证可比。

| 实验 | 委派 | 结果（上轮对照） | 消耗 | 用户可见交付 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| B-narrow2（`f76098d9`/`5a506353`） | 是（单文档） | **父子双 SUCCEEDED**（上轮 `0c7c64a0`/`f8ca9b43` 双 BUDGET_EXCEEDED） | 父 8 步/8 工具/33216in/5618out；子 4 步/6 工具/17887in/3554out | 2174 字逐条对照报告：**8 条真实 chunk 引用**、差距清单、未读章节声明 | 129s |
| B2（`8dd4161d`/`4cd0bee5`） | 是（四文档） | **父子双 SUCCEEDED**（上轮 `3e92ed11`/`3d816801` 双 BUDGET_EXCEEDED） | 父 8 步/8 工具（封顶）；子 4 步/6 工具 | 2239 字诚实降级报告：声明"仅提纲级证据、完成标准未达成"、15 节未读清单、0 引用如实标注 | 136s |
| A2（`421d92b4`） | 否（直接研究） | SUCCEEDED（上轮 A 同样 SUCCEEDED，89s/16 引用） | 5 步/8 工具/44803in/3704out | 2453 字四文档对照报告，11 条真实 chunk 引用；中途 1 次 FAILED_RETRYABLE 自动重试并恢复 | 146s |

**关键验证点**：

1. **验收主目标达成**：单文档委派在合理既定预算内完成完整子结论（子运行：提纲 → 检索 → 4 次正文读取 → 有依据结论）+ 父综合（逐条判定/差距/未读声明）+ 有效引用（8 条经 `collectEvidence` 校验的真实 chunk 身份），全程未提高任何限额。
2. **无工具总结路径在生产首次生效**：B2 子运行第 2 轮请求 4 个并行 `read_document_section`（4+4 > 子额度 6）被拒 → `TOOL_BUDGET_BATCH_REJECTED` → 额度按请求封顶消耗 → 重排队 → FINALIZE 无工具总结轮如实产出"仅提纲级"研究发现。旧语义下该场景直接 BUDGET_EXCEEDED。
3. **四文档委派的诚实降级**：子运行预算内只能覆盖到提纲级（输入等比预留 + 工具额度 6 的真实边界），回答明确声明"完成标准未达成、正文引用全部缺失、不得表述为文档中不存在"，并给出补读步骤。没有为换取"成功"状态而隐藏缺口或突破上限——这与验收目标一致：全库级四文档对照**仍更适合直接研究**（A2 完成、11 引用 vs B2 提纲级、0 引用），委派的价值场景是范围收窄的研究子任务（B-narrow2 已完整达成）。
4. **恢复可靠性**：A2 中出现一次 AI_PROVIDER_ERROR → FAILED_RETRYABLE → 30s 后自动重试成功，恢复链路在新语义下正常。

证据：`docs/acceptance-evidence/2026-10-07/budget-semantics/`（三个实验 JSON + 回答全文 + 截图）。

## 7. 剩余限制

- **父综合只见子运行回传内容**：B-narrow2 中父回答称"未取得文档提纲"，而子运行实际已取得（4 节 `HEURISTIC_HEADINGS`）——父运行依据的是 `DELEGATION_COMPLETED` 的结论与结构化引用，不含子运行工具级细节。这是委派接口的转述边界（模型质量/接口设计问题），不是预算缺陷；如需改善应考虑在回收内容中附带子运行的覆盖元数据，属后续独立评估。
- **四文档全库对照仍不适合委派**：子运行 6 次工具额度内"4 提纲 + 4 正文读取"不可同时完成（输入侧收尾预留也会在两轮大输入后触发收尾）。这是真实资源边界，不是缺陷；需要全库对照时直接研究（A2 形态）仍是正确入口。
- 父 RUNNING 竞争窗口用量回收、FAILED_RETRYABLE 刷新恢复入口、真实 HTTP Last-Event-ID 补回验收：保持既有待验证清单，本轮未动。
- `estimated_cost` 列与精确计费维持低优先级，未动。

## 8. Git 提交

1. `feat(agent)`：预算语义分离——V63 迁移、SEPARATED/COMBINED 记账分支、委派切分预留与拒绝受理、批次额度消耗与无工具总结、子证据可总结判定、测试更新与新增回归。
2. `docs(agent)`：本交付记录 + 证据 JSON/截图 + 维护文档与路线图更新。
