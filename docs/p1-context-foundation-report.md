# P1 交付报告：上下文基础（基线整理与上下文基础）

日期：2026-10-04。依据 [ai-next-stage-blueprint.md](ai-next-stage-blueprint.md) P0＋P1 及最终批准计划的七项修订与五条补充口径执行。分支 `codex/context-foundation`，仅本地提交未推送。

## 1. 提交清单

| 提交 | 内容 | 性质 |
| --- | --- | --- |
| `6fb0d4d` | 基线快照（247 文件，此前工作成果，不属于 P1） | 恢复点 |
| `21e359d` | P0 基线记录 + 能力矩阵（baseline-2026-10-03.md、capability-matrix.md） | P0 |
| `a5f4057` | P1-1＋P1-2：单次请求预算组件 + Composer v2 分层组装 | P1 功能 |
| `606d66a` | P1-3：工作状态 v2（五分类约束 + stateRevision + 消息 ID 关联） | P1 功能 |
| `d70b8f8` | P1-4：有界增量摘要（CAS + 单独记账 + 有界输入） | P1 功能 |
| `88f6e32` | P1-5：回退验收测试（v2 数据在旧组装路径下可继续使用） | P1 验收 |
| `3a75ed7` | P1 交付报告初版 | 文档 |
| `63666cd` | 修复批一：active 约束不淘汰、摘要覆盖/记账/持久化尝试上限、回退路径 goal 补入 | P1 修复 |
| `358e5d4` | 修复批一记录与表述勘误 | 文档 |
| `88c315a` | 修复批二：摘要记账结算到所属运行、旧摘要完整保留、长消息分段覆盖、保护约束按对象并存 | P1 修复 |

后续功能提交（P2 文档读取、P3 规划接入、P4 质量收敛）从此分支继续。

注：`a5f4057`/`606d66a` 提交信息中的"46 类"为基线类数误写（当时实际为 47/48 类），测试项数正确，以本表及 surefire 报告为准；不重写提交历史。

## 2. 改了什么

### 预算与组装（a5f4057）
- 新增 `AgentContextProperties`（`agent.context.*` 配置）与 `AgentContextBudget`：单次输入预算 = `min(模型窗口 − 输出预留 − 安全余量, 运行剩余输入预算, 应用单次上限)`；窗口按"提供商类型:模型名"或"模型名"覆盖（`window-overrides`），运行时经 `AgentModelConfigurationStore` 使用本次固定的配置快照解析；窗口未知时不额外收紧（保守应用上限）并标记估算。
- `AgentModelMessageComposer` 新增 v2 组装路径：必选层（系统提示/工作状态/摘要/页面上下文/可信提案/当前请求）先预留；历史从最新到最旧填充剩余预算（废除"取 10 用 6"）；同一需求/同一工具结果去重；大工具结果确定性投影（保留标量、每数组前 3 项与计数、`projection=DETERMINISTIC` 标记），不再整条跳过。
- `AgentRuntimeCoordinator`：超预算先降级重组一次（factor 0.6），仍超限才 `BUDGET_EXCEEDED`；`RUN_BUDGET_EXCEEDED` 事件携带 `scope/binding/availableInputTokens/windowEstimated/reason`，区分运行费用、单次上限与模型窗口。
- 修复旧路径缺陷：run.goal 补入按"取回列表"而非"实际入选消息"判断。

### 工作状态 v2（606d66a）
- `AgentWorkingState` 升级 schemaVersion=2（写入时渐进升级，不批量改写旧数据）：约束五分类——持续约束（仅 TASK_COUNT/DATE_LOCK/ASSIGNEE_LOCK 三个可确定性识别的作用域，各带来源 messageId 与 active/superseded）、约束修改（同作用域新值替代旧值，supersededBy 可追溯；保护类约束并存不覆盖）、本轮表达要求（turnRequirements 每请求重算）、普通提问（不进 constraints）、明确新目标（保守词表，旧目标保留 goalHistory，旧约束批量 superseded 不删除）。
- 所有写入路径统一递增 `stateRevision`（JSONB 内）。
- `appendUser` 关联真实消息 ID：`createRun`/`continueRun` 消息先入库 `RETURNING id`，同事务传入；`continueRun` 顺带修正为先 CAS 校验再写状态。
- Composer 统一 `renderWorkingState`：v1 原样、v2 结构化渲染，两条组装路径共用。

### 有界增量摘要（d70b8f8）
- `AgentContextSummarizer`：确定性压缩后仍有旧对话放不进预算时触发；每次运行至多尝试一次；有界输入（≤20 条、每条 400 字符、总量 6000 字符）与有界输出（1200 字符）；不提供业务工具、不执行摘要输出动作；仅原生 Tool Calling 模型支持。
- 摘要存 `working_state.summary` 节点（schemaVersion/sourceFrom/sourceThrough/stateRevision/goalRevision/text/model/activeConstraints 确定性快照）；已有摘要覆盖到最新未选消息时不再生成。
- CAS 提交：stateRevision + goalRevision 匹配才落库，`jsonb_set` 只写 summary 节点；生成期间不持行锁；冲突丢弃不重算；任何异常不影响主轮次。
- 单独记账但计入运行总预算：`recordSummaryUsage` 封顶累加 token，`agent_step` type=MODEL_REQUEST + reason=CONTEXT_SUMMARY，不递增 steps_used、不干扰工具恢复与收敛判断。
- 剩余输入预算放不下摘要请求本身时不发起；主请求预算先保留。

### 回退（88f6e32）
- `agent.context.composer-v2=false` 时走旧组装路径；旧路径同样渲染 v2 结构化约束并注入既有 `<CONVERSATION_SUMMARY>`，回退覆盖数据格式（不只是组装代码）。

## 3. 保护了哪些已有行为

- 未配置任何 `agent.context.*` 覆盖时，单次输入预算与旧行为一致（50000 上限 − 已用量），不引入额外收紧。
- Legacy 组装路径保留为回退路径，行为仅增加"能读懂 v2 状态"的兼容，v1 状态渲染与旧输出一致。
- 工具消息协议配对（toolCallId + isError + STALE_OBSERVATION 失效检测）原样保留；未决工具调用不进入压缩路径（组装前已由批次执行处理）。
- 跨 Tick 恢复、租约、防重入、取消、审批、提案、规划等模块未改动。
- 无新增 Flyway 迁移；V22–V53 全部未动（`AgentMigrationChecksumTest` 通过）。
- 工作区此前 209 项未提交修改以基线快照 `6fb0d4d` 完整保全。

## 4. 实际执行的验证

- **P0 基线**（提交前）：`test-compile` 通过；agent 包 46 类 341 项测试 0 失败 0 跳过；surefire 报告与源码类清单逐一核对（CLASSES_MATCH）；Testcontainers（pgvector pg17 + MinIO）确认执行。
- **P1 各提交**：agent 包从基线 46 类逐步增至 **49 类 370 项测试全绿**（新增 3 个测试类、29 项测试：预算 7、Composer v2 11、摘要单测 5、工作状态/摘要集成 6）。P1 验收用例登记见 [capability-matrix.md](capability-matrix.md) 第 6 节。
- **交付前全量**：全量后端测试（mvnw test）结果见第 7 节（运行中/结果补记）。
- Docker 29.8.0 可用，集成测试为完整执行。

## 5. 没有验证什么（如实记录）

- 未重跑真实模型验收（按蓝图 P0 口径，沿用 space-bunny-acceptance-report）；"20–30 轮真实模型长对话保留早期约束"、"低窗口真实模型行为"属 P4 固定评测范围。
- 摘要生成仅在单测/Mock 层验证调用与 CAS 语义，未在真实模型上验证摘要质量（不宣称摘要内容可靠）。
- 约束识别是保守词表/正则：无法覆盖全部中文表述；"替换绑定作用域"以代码审查 + 保守规则验收，未做语义级评测。
- `agent.context.window-overrides` 未配置真实模型的窗口值（示例仅注释）；chars/3 估算未做跨模型校准。
- 前端未改动，未重跑前端测试。
- 摘要功能对 Legacy（CHAT-only）模型明确跳过，未验证 Legacy 会话长对话表现。

## 6. 如何关闭新功能与恢复

- **关闭 v2 组装**：`AGENT_CONTEXT_COMPOSER_V2=false`（或 yml `agent.context.composer-v2: false`）。回退路径已验证可读 v2 状态与摘要，会话可继续，不丢记录、不误认目标；无法完整表达有效约束时明确停止。
- **调整预算**：`AGENT_CONTEXT_PER_REQUEST_INPUT_CAP` / `AGENT_CONTEXT_OUTPUT_RESERVE_TOKENS` / `AGENT_CONTEXT_SAFETY_MARGIN_TOKENS`；按模型收紧窗口用 `agent.context.window-overrides`。
- **恢复**：功能开关切回即可；数据库无 DDL 变更，无需迁移回滚；如需代码级回退，按第 1 节提交逐个 revert（基线快照 `6fb0d4d` 为最终恢复点）。

## 7. 剩余未提交文件与全量测试结果

- **提交后工作区**：干净（`git status` 无未提交项）。本地 `.zcode/`、`.workbuddy/`、`.zcodeignore` 为排除项，保留在仓库外（见 baseline-2026-10-03.md 第 2 节）。
- **交付前全量后端测试**（2026-10-04 00:20，Docker 可用）：**139 个测试类、933 项测试，0 失败、0 错误、5 跳过，BUILD SUCCESS，耗时 2 分 42 秒**。933 = 历史全量口径 904 + 本轮 P1 新增 29。
  - 5 项跳过均为 opt-in 外部验收测试（需真实提供商环境变量）：`RealAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、`BrowserAcceptanceHostTest`、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest`，与历史口径一致，非本轮引入。
  - 跳过项按"未执行"单独记录，不计入通过口径。
- **前端**：未改动；本轮未重跑前端测试（上一口径 135 项 + vue-tsc + vite build，见 space-bunny-acceptance-report）。

## 8. P1 修复批次（63666cd）：审查问题的集中修正

交付审查发现五组实现与已约定验收条件不符，已作为限定范围修复批次处理（各带能触发问题的回归测试）：

| # | 问题 | 修正 | 回归测试 |
| --- | --- | --- | --- |
| 1 | active 约束被条数上限淘汰；上下界共用作用域互相替代 | `trimConstraints` 只归档最旧 superseded，active 永不删除；数量限制拆分 `TASK_COUNT_MAX`/`TASK_COUNT_MIN` | `activeConstraintsSurviveConstraintChurnWithoutEviction`、`minAndMaxTaskCountConstraintsCoexistWithoutSupersedingEachOther` |
| 2 | 摘要覆盖范围按全部候选记录但实际只读入部分；未延续旧摘要 | 覆盖范围来自实际完整读入的消息前缀（coverage=FULL/PARTIAL + uncoveredCount）；增量摘要携带旧摘要文本，sourceFrom 沿用前份起点 | `coverageComesFromActuallyIncludedMessagesNotFromCandidateList`、`incrementalSummaryCarriesPreviousSummaryForward` |
| 3 | CAS 只比较 revision，未原子递增、未校验消息边界 | 提交原子递增 stateRevision（并发提交仅第一个成功）+ 校验 sourceThrough 消息属于本会话 | `summaryCasCommitOnlyUpdatesSummaryNodeAndRejectsStaleRevision`（扩展并发重复提交与伪造边界拒绝） |
| 4 | "每运行至多一次"无持久化标记；空输出记账前返回、usage 缺失按零计；摘要后未刷新预算/取消状态 | 尝试先落 agent_step 持久化标记，上限按持久化计数校验；所有结局（COMMITTED/CAS_CONFLICT/EMPTY/FAILED）如实记账，usage 缺失按实际字符估算；摘要后刷新运行、重查取消并重新核算主请求预算，不足时以 `SUMMARY_CONSUMED_BUDGET` 明确停止 | `skipsWhenPersistentAttemptLimitAlreadyReached`、`blankOutputRecordsUsageAndMarksAttemptEmpty`、`realUsageIsRecordedInsteadOfEstimate`、`summaryAccountingTriggersBudgetRecheckBeforeMainModelCall` |
| 5 | 回退路径仍按"取回历史"判断 goal 补入 | 旧路径改为按"实际入选消息"判断 | `legacyPathInjectsCurrentRequestWhenItIsNotAmongSelectedMessages`、`legacyPathInjectsCurrentRequestWhenBudgetSkippedItsWindowEntry` |

修复后 agent 包 **49 类 380 项测试全绿**（较修复前 +10 回归测试）；修复批后的全量后端测试结果见第 9 节。

### 表述勘误（对应此前报告的不准确声明）

- ~~"三重 CAS"~~ → 准确表述：提交时校验 stateRevision 与 goalRevision 匹配、原子递增 stateRevision、校验 sourceThrough 消息边界；三者在同一条 UPDATE 内原子完成。
- ~~"每次运行至多一次摘要"~~（初版仅内存语义）→ 现以 agent_step 持久化标记 + 持久化计数校验实现，服务重启不能绕过。
- ~~"修复 run.goal 补入缺陷"~~（初版仅修复 v2 路径）→ 现两条路径均按实际入选消息判断，并有回退路径回归测试。

## 9. 修复批后全量测试结果

- **修复批一后全量后端测试**（2026-10-04 00:45，Docker 可用）：**139 个测试类、943 项测试，0 失败、0 错误、5 跳过，BUILD SUCCESS**。943 = 933 + 修复批一新增 10 项回归测试。
- **修复批二后全量后端测试**（2026-10-04 01:29，Docker 可用）：**946 项测试，0 失败、0 错误、5 跳过，BUILD SUCCESS**。946 = 943 + 修复批二新增 3 项回归测试。
  - 5 项跳过同第 7 节口径（opt-in 外部验收测试），按"未执行"单独记录。
- **提交后工作区**：干净；无新增未提交项。

## 10. 修复批次二（88c315a）：审查问题的集中修正（续）

| # | 问题 | 修正 | 回归测试 |
| --- | --- | --- | --- |
| 1 | 摘要费用未计入所属运行：`completeSummaryAttempt` 的 `UPDATE agent_run WHERE id=?` 传的是步骤 attemptId，正常情况下更新零行 | 从摘要步骤取回真实 run_id（`WHERE id=(SELECT run_id FROM agent_step WHERE id=?)`）在同一事务结算；仅 ATTEMPTED→终态转换一次，重复完成不重复扣费 | **真实数据库**断言：`summaryAttemptSettlesTokensToOwningRunExactlyOnce`（步骤字段、所属运行 input/output_tokens_used 实际变化、重复完成金额不变） |
| 2a | 上一份摘要被截到 800 字符仍标 incorporatedPrevious，尾部决定丢失 | 旧摘要文本完整进入本次请求，新增片段使用剩余预算；incorporatedPrevious 真实成立 | `previousSummaryIsIncludedInFullEvenBeyondEightHundredChars`（第 800 字符之后的尾部决定完整进入请求） |
| 2b | 首条 >400 字符的候选消息阻塞覆盖，后续内容永久无法摘要 | 长消息按消息 ID+偏移分段覆盖（每段 600 字符，跨运行推进，进度存 summary.segments）；后续短消息仍完整覆盖；未读完的消息明确列入 uncoveredMessageIds；无可新增覆盖时不再重复生成 | `longMessagesAreSegmentCoveredWhileShortOnesFullyCovered`、`longMessageCoverageProgressesAcrossRunsUntilFullyCovered`（连续更新：[0,600) → [600,875) → 跳过） |
| 3 | 相同 scope 的保护约束互相覆盖（任务 A/任务 B 的日期保护） | 同作用域替代仅限数量上下界；日期/负责人等保护类约束无法确定性区分对象，一律并存 | `protectiveConstraintsForDifferentObjectsCoexistInsteadOfOverwriting`（DATE_LOCK 与 ASSIGNEE_LOCK 各两条不同对象约束同时 active） |

修复批二后 agent 包 **49 类 383 项测试全绿**（较修复批一 +3）。

## 11. 剩余覆盖修复（2026-10-04）

覆盖策略 v4 将同消息分段合并为连续前缀，不再用 60 条容量丢弃新偏移。已完成的连续历史前缀压缩为 completedBefore，未完成消息保留偏移；兼容旧 segments。Composer 从数据库按旧到新读取未完成历史及新消息，最近 40 条只用于主请求选择，不再限制摘要续读。重读和压缩均校验项目、会话和当前成员权限；删除的来源退出候选，并在下一次摘要提交中记录 terminatedMessageIds。CAS、每运行一次持久尝试、结算与预算检查保留。

真实隔离 PostgreSQL 的 Composer→Repository→Summarizer 数据流用例覆盖 60 条旧分段、窗口外续读、数据库重载后的原偏移、删除与权限撤销。针对性三类测试实际执行 61 项，61 通过、0 失败、0 错误、0 跳过。证据：acceptance-evidence/2026-10-04/p1-coverage-test.log。模型响应在此用例为模拟，不宣称真实模型摘要质量。原业务容器保持停止，无新增迁移。
