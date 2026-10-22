# 上下文容量 E1–E3 修复交付报告（2026-10-09）

- 任务来源：`docs/agent-context-capacity-e1e3-handoff-20261009.md`
  （复核问题定义：`docs/agent-context-capacity-d1d8-post-review-20261009.md`）
- 证据目录：`docs/acceptance-evidence/2026-10-09/e1e3/`（红/绿 surefire 报告、扩展回归、
  浏览器验收记录）；复核探针原始证据：`docs/acceptance-evidence/2026-10-09/d1d8-post-review/`
- 维护入口：`docs/agent-runtime-maintenance.md` 第 17 节
- 范围：**只修 E1/E2/E3 三个可复现缺陷**。未新增平台、未新增表、未改容量策略语义、
  未改前端/DTO、未迁移框架、未引入第二套编排。

## 0. 真实 Git 基线（核对结果，非沿用报告）

| 项 | 实测值 |
| --- | --- |
| 分支 | `codex/context-foundation` |
| 起点 HEAD | `9707012` |
| 起点 HEAD 父提交 | `21b6432`（D1–D8 测试）、`722737d`（D1–D8 生产修复） |
| 起点未跟踪（在范围内） | `docs/agent-context-capacity-d1d8-post-review-20261009.md`、`docs/agent-context-capacity-e1e3-handoff-20261009.md`、`docs/acceptance-evidence/2026-10-09/` |
| 起点未跟踪（**已排除，保持原样**） | `.freebuff/`、`.dsh-acl-recovery/` |

未执行任何 `reset`/`clean`，未丢弃既有改动，未 push。

## 1. 交付 A：E1 目标修订与会话摘要发布未串行化

**根因**：复核结论成立。检查 SELECT 只用 `FOR UPDATE OF r` 锁 `agent_run` 行，而目标修订的
真实存储行是 `agent_session`（`AgentWorkingState.appendUser` / `createRun` 只锁会话行）。
READ COMMITTED 下条件 UPDATE 使用自己的语句快照，**跨行不会串行化**——已提交的目标更正
无法阻止旧目标摘要发布。

**修复**（`AgentRunEventRecorder.completeRunContextAttempt`）：

- 检查 SELECT 只取 `r.session_id`，不再 join `agent_session` 取修订；
- 在运行行锁之后对**真实会话行** `SELECT ... FOR UPDATE`，并在**取得保护之后**读取
  `working_state->>'goalRevision'` 作为判据（加锁读必然看到最新已提交修订；会话行缺失或
  修订不可解析按冲突处理）；
- 锁序固定 **"运行行 → 会话行"**，与 `createRun`、`requestPause`/`requestResume`、
  `AgentLeaseScope`、`AgentWorkingState.appendUser`、`AgentPlanningOperationService`
  （`FOR UPDATE OF r` + 会话读取）一致，**未引入反向锁序**，实测无死锁；
- 发布 UPDATE 的条件谓词原样保留（epoch/租约/取消/RUNNING/目标修订/ATTEMPTED 一次转换）；
- 短事务只含读锁 + 一次 UPDATE，**不跨模型 HTTP 持锁**。

**返回语义变更**：两个重载 `void → boolean`，只有**真实发布**（条件 UPDATE 转换成功）为 `true`；
fenced / 条件丢失 / 重复完成为 `false`。`AgentRepository` 同名重载透传真实结果，
使"提交事实"对应真实有效发布，而不是"调用了 complete 方法"。

## 2. 交付 B：E2 RUN_CONTEXT 尝试/提交事实未跨 scope 保留

**根因**：复核结论成立。RUN_CONTEXT 压缩失败/不合格时其 `false` 结果被随后会话摘要分支的
`NOT_ATTEMPTED` 覆盖，已经真实发生的辅助出站事实丢失 → 协调器不刷新配置 → 实际出站顺序
退回 B→A（复核探针实测 `outbound=[model-B, model-A]`）。

**修复**（`AgentContextSummarizer`）：

- `SummaryOutcome(committed, auxiliaryAttempted)` 成为两个 scope 的公共返回类型，新增
  `merge(other)`：两个事实分别 **OR**；
- `maybeSummarizeDetailed` 未提交时返回 `outcome.merge(maybeSummarizeConversation(...))`；
- **事实边界**（按交接文档要求严格区分）：
  - **准入前**全部跳过/拒绝——无来源、输入预算不足、超自身窗口、输出/时长不足、
    `AGENT_RUN_PAUSED` 拒绝准入 → `NOT_ATTEMPTED`（不误报，保住"每请求一次解析"）；
  - `beginRunContextAttempt` **成功之后**——空输出、不合格、fencing、异常 → `ATTEMPTED_ONLY`；
  - `committed` 只在 **17.1 的真实 `boolean` 发布结果**为 true 时为 true；
- 会话摘要 `commitSummary` 由 `void → boolean`，返回**真实 CAS 结果**（CAS 冲突不算提交）。

## 3. 交付 C：E3 辅助后未重建本次主请求

**根因**：复核结论成立。协调器刷新了 `resolved`/`requestBudget`，却把重组门控在
`contextCommitted` 上；大窗口 A 切小窗口 B 时辅助失败 → 不重组 → 继续发送只装得下 A 的
可选历史 → 直接 `BUDGET_EXCEEDED`，而同样来源按 B 组装仅 2735 字符。

**修复**（`AgentRuntimeCoordinator`）：

- 抽出**统一组装入口** `assembleMainRequest(...)`：v2 走 `composeV2(..., 1.0, legacyMode)`，
  超预算时**一次**降级重组（`0.6`）；必选层放不下返回 `failureReason`，否则
  `COMPOSITION_OVER_BUDGET`；Legacy 走 `composer.buildMessageHistory(...)`。初始组装与
  重建**共用同一入口**，子研究产出、覆盖事实、收尾指令不会因第二次组装丢失；
- 重组条件 `contextCommitted → auxiliaryAttempted`：辅助**实际发起过**就按新快照重建
  窗口/输出封顶/协议模式/消息视图/估算；重建后必选层仍放不下 → 明确 `inputBudgetExceeded`；
- 无辅助出站走 `else` 分支，保持既有"每请求一次解析"，不改动已组装视图；
- 不变量保持：一次解析对应一次请求、不在 HTTP 层重读配置、必要层超限如实收口。

## 4. 真实红灯证据（先在未修代码测量，再修复转绿）

方法：**逐缺陷隔离**地把对应生产行为临时还原（保留新签名），在未修状态下运行本轮新正式用例，
记录真实失败，再恢复修复。证据文件见本目录。

### 4.1 E1（真实 PostgreSQL，`red-before-fix-postgres.txt`）

```
Tests run: 10, Failures: 2, Errors: 0
goalCorrectionCommittedDuringPublicationNeverLeavesOldGoalSummaryEffective:
  [目标更正在发布语句提交前已提交：旧目标摘要绝不能成为有效摘要]
  Expecting actual: "COMMITTED" not to be equal to: "COMMITTED"
goalWriteCannotCommitInsidePublicationCriticalSection:
  [发布进行中时目标写入必须被真实串行化（不能穿过发布的临界区提交）]
  Expecting value to be true but was false
```

与复核探针结论完全一致（`goalRevision=1 summaryStatus=COMMITTED`）：**旧目标摘要被发布了**。

### 4.2 E2（`red-before-fix-e2.txt`）

```
Tests run: 15, Failures: 4, Errors: 0
failedRunContextCompactionStillRefreshesNextMainRequest      expected: "model-B" but was: "model-A"
unqualifiedRunContextCompactionStillRefreshesNextMainRequest expected: "model-B" but was: "model-A"
fencedRunContextPublicationIsNotReportedAsCommitted          Expecting value to be true but was false
necessaryLayerExceedingSmallWindowStillClosesExplicitly      expected: BUDGET_EXCEEDED but was: SUCCEEDED
```

前两条正是复核探针实测的 B→A 顺序回归。第四条如实暴露一个真实依赖：辅助尝试事实丢失后，
必要层超限分支连重建都不会发生，于是"该收口"变成"误放行"。

### 4.3 E3（`red-before-fix-e3.txt`）

```
Tests run: 15, Failures: 2, Errors: 0
failedAuxiliaryRebuildsMainRequestForSmallerWindow        （仍发送只装得下 A 的旧历史）
auxiliaryFailureSwitchingToLegacyRebuildsReadOnlyContract （未携带新的 Legacy 只读契约）
```

## 5. 转绿与回归

复核基线为 75 项正式回归（7 个类 67 项 + 真实 PostgreSQL 类 8 项）。本轮在这 75 项之上
**新增 9 项**（D1D8 类 8→15 即 +7，PostgreSQL 类 8→10 即 +2），故本轮正式回归总量为 **84 项**，
全部通过：

| 回归集 | 数量 | 结果 |
| --- | --- | --- |
| 正式回归 7 个类（D1D8 **15**、Compaction 4、Auxiliary 3、Legacy 8、Snapshot 3、Composer 16、Summarizer 25） | 74 | **74/74 通过** |
| 真实 PostgreSQL 类（D2 8 项 + E1 2 项） | 10 | **10/10 通过** |
| 合计（复核 75 项 + 本轮新增 9 项） | **84** | **84/84 通过** |
| 扩展相关回归（协调器/领域/限额/重试/委派/计量/配置切换/暂停续跑/崩溃接管/持久恢复） | 215 | **215/215 通过** |
| **后端全量** | 1318 | **0 失败、0 错误、11 跳过**（BUILD SUCCESS） |

- 跳过 11 项为**既有环境门控**用例（`BrowserAcceptanceHostTest`、`RealAcceptanceHostTest`、
  `RealRetrievalBaselineTest`、`ReliabilityAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、
  `ReliabilityOperationDatabaseTest`(4)、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest`），
  与本轮改动无关。
- 两处既有正式用例需要**如实适配**（非放宽）：`AgentRunContextCompactionRegressionTest`、
  `AgentAuxiliaryOutboundRegressionTest` 用 mock 仓库模拟健康持久层，而发布接口现在返回真实
  业务事实，故其 `completeRunContextAttempt` 桩返回 `true`；真正的 fenced/零行场景改由
  **真实 PostgreSQL** 用例断言（`AgentRunContextCommitPostgresTest` 10 项，含两个并发窗口）。
  断言未被放松，窗口未放宽，必要来源未删减，超限路径未被屏蔽。

## 6. 浏览器验收（bsk，后台、电脑端）

详见本目录 `browser-acceptance.md`。摘要：

- 本次**真实构建并启动**后端（`:8080`，profile=local，Flyway 已 v64、无需迁移）与前端
  （`:5173`，Vite 代理 → `:8080`）；未误用旧服务；原容器、用户配置、业务资料均未改动。
- 四文档对照研究：`Steps 4/64 · Tools 4/64`，SUCCEEDED；容量/评分/脱敏三主题结论正确且逐条带来源；
  如实声明 `coverage=RELEVANT_EXCERPTS_ONLY`、`fullDocumentRead=false` 并列出未读章节，未编造。
- 刷新恢复：整页重载后登录态保留，`项目协作 2026/10/9 已完成` 会话内容与来源完整保留。
- **未验证（如实记录）**：真实多周期压缩质量。本轮运行的活跃上下文远未达软压缩触发线 T，
  后端日志无 RUN_CONTEXT / 摘要 / 压缩记录，压缩路径未被自然触发；未以普通 SUCCEEDED 冒充
  压缩质量，也未为触发而修改用户模型配置或窗口参数。E1–E3 正确性由受控组件回归 +
  真实 PostgreSQL 并发事务测试证明。

## 7. 未做与边界

- 未改 v2 累计 token 语义（只统计、无上限）；未改 H/T/L 与已确认模型窗口语义；
- 未改父子独立额度与放宽时长；下一请求仍按当前配置；暂停后仍需明确输入续跑、无继续按钮；
- 已受理后台规划继续；重试/崩溃接管/结果恢复、权限审批、工具幂等均无回退；SSE 保持已收口；
- 未 push；未把 `.freebuff/`、`.dsh-acl-recovery/` 混入提交。
