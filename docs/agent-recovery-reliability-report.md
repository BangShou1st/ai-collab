# 恢复可靠性修复报告（R1 崩溃接管计时 / R2 已持久化模型结果恢复）

日期：2026-10-05。分支 `codex/context-foundation`，HEAD `d5f09ab`。本轮在工作区已有 M1–M4 与继承未提交改动之上，只修复两项恢复可靠性问题。改动保留未提交，未推送、未合并、未部署。

## 1. 现场基线与范围

- 交接时 HEAD `d5f09ab`，工作区含大量继承未提交修改、M1–M4 改动、新文件与暂存删除/移动。核对后以现场为准，未回退任何已有代码。
- 本轮只做：R1 崩溃接管计时、R2 已持久化模型结果恢复。未开发主动暂停/继续、到限续接、逐字流式、自动择模、精确计费；未改摘要算法、未迁移框架、未新增前端交互或对外 API。
- 不调用真实模型、不启动业务服务、不触业务数据库；隔离 PostgreSQL 使用 Testcontainers（`pgvector/pgvector:pg17`）。

## 2. R1：崩溃接管计时

### 2.1 缺陷与触发链（已核对源码）

1. `AgentRuntimeJob.tick` 使用 6 分钟租约（`Duration.ofMinutes(6)`）。
2. 旧实现 `AgentRepository.claimNext` 接管过期 RUNNING 时，把
   `[claim_started_at, LEAST(lease_expires_at, now())]` 累加进 `active_elapsed_ms`——
   也就是把"等待租约到期"的时间当成了已执行时间。
3. `AgentRuntimeCoordinator.advance` 首先按
   `min(300000, Skill.maxRunDuration) - activeElapsedMillis(run)` 判定剩余时长，
   `<=0` 直接 `BUDGET_EXCEEDED`；ITERATION_PLANNING 上限即 5 分钟。

结果：worker 只执行几秒后退出，接管也会立刻拿到约 6 分钟的"已执行时长"并直接到限，恢复永远无法推进。

### 2.2 计时语义（本轮确定）

- `active_elapsed_ms` 只累计**已确认执行时长**：接管时区间为 `[claim_started_at, 最后一次确认进度]`；该 claim 没有确认进度则累加 0。
- **租约有效期不是执行上界**：租约到期、进程离线、排队与重试等待都不计入执行时长。
- 未能确认的尾段（最后一次确认进度 → 进程退出）**没有持久证据，不冒充已执行时长**；这是本实现的已知粒度，不声称精确计时。该尾段上界为租约长度（6 分钟），下界为 0。
- 同一套语义贯穿正常运行、正常轮次结束、自动重试与接管：终态写入把当前 claim 段结清后清空锚点（`accountActiveTime`：`claim_started_at=NULL, last_progress_at=NULL`），接管只负责"上一个 claim 的已确认段"。
- 真实执行仍受原有限额保护：`advance` 的 deadline、步骤/输入/输出/重试上限、收敛策略一律未改，未调大任何限额，也未清零任何已确认时长。

### 2.3 最小修法

新增持久字段（迁移 `V60__agent_last_progress_at.sql`）：`agent_run.last_progress_at TIMESTAMPTZ`。

用途：记录**本 claim 内最近一次已持久化的执行进度**，作为接管结算的可信上界。它必须与通用审计列 `updated_at` 区分：`updated_at` 也会被审批决议、外部命令等**非执行进度**的写入推进，用它当执行锚点会把外部等待再次算成执行时间；因此选择独立、只在 worker 推进运行状态的落库事务内更新的字段。

写入边界（都在既有原子写路径内，不新增事务 Bean）：

| 位置 | 语义 |
| --- | --- |
| `AgentRunEventRecorder.recordModelTurn` | 模型轮次（含工具调用）落库 = 进度 |
| `AgentRunEventRecorder.recordToolResult` | 工具结果落库 = 进度 |
| `AgentRunEventRecorder.accountActiveTime` | 终态结清当前段并清空锚点 |
| `AgentRepository.claimNext` | 结算上一个 claim 的已确认段，并把锚点重置为 NULL（新 claim 尚无进度） |

结算表达式（`claimNext`）：

```sql
GREATEST(0, extract(epoch FROM (
  GREATEST(r.claim_started_at, COALESCE(r.last_progress_at, r.claim_started_at)) - r.claim_started_at
)) * 1000)::bigint
```

未新增计时服务、分布式心跳或通用执行框架；`activeElapsedMillis` 的读取语义与限额检查位置未变。

### 2.4 故障注入方式

在真实 PostgreSQL 上直接构造数据库状态（`claim_started_at` / `last_progress_at` / `lease_expires_at` 与 `now()` 的相对关系），等价于"旧 worker 执行一小段后进程退出、租约已过期"，再调用真实 `claimNext` 接管并由真实协调器 `advance` 推进。不等待真实 6 分钟，不加测试专用执行引擎，不加 sleep。

### 2.5 核心验收（`AgentCrashRecoveryTimingPostgresTest`，5 项）

| 用例 | 断言 |
| --- | --- |
| 租约等待不累计、接管后仍有剩余预算 | 已确认 10 秒 → 接管后仍为 10 秒（旧实现约 360 秒），`300000 - elapsed > 0` |
| 多次接管不重复累计、不清除既有时长 | 20 秒 → 无进度再接管仍 20 秒 → 新确认 2 秒段后 22 秒 |
| 正常轮次结束与自动重试计时一致 | 终态后 `active_elapsed_ms` 为真实执行区间（<5 秒），非 6 分钟窗口；重试等待领取不追加 |
| 真正耗尽时长仍到限 | 已确认 300 秒 → 接管后 `advance` 返回 `BUDGET_EXCEEDED` |
| 旧 worker 不能污染新 claim | 旧 claim 版本的模型轮次与失败提交均被租约拦截（`IllegalStateException`），状态/步骤/时长保持新 claim 现场；新 claim 自己的提交生效 |

## 3. R2：已持久化模型结果恢复

### 3.1 缺陷与窗口（已核对源码）

`advance` 先 `recordModelTurnWithSettlement` 提交 MODEL_TURN 与调用结算，之后才分别处理工具批次、`[QUESTIONS]`、预算部分回答与普通最终文本。旧 `pendingModelTurn` 只返回"含工具调用且批次未处理"的轮次，因此无工具调用的文本响应若在"MODEL_TURN 已提交 → 最终收尾尚未提交"之间退出，接管无法复用它，会再次请求模型。

### 3.2 修法

1. **恢复入口**：`AgentRepository.pendingModelTurn` 扩展为"最近一轮**未被消费**且**有正文或有工具调用**的 MODEL_TURN"。空/纯空白正文的轮次保持原错误语义，不作为可复用结果。
2. **正常与恢复共用收尾判定**：抽出 `AgentRuntimeCoordinator.completeTextTurn(run, turn, finalizing, coreActionPending, steps)`，正常路径（第 11 步）与接管恢复分支调用同一方法。~~`finalizing`（收敛策略）与 `coreActionPending` 都由持久事实推导，因此恢复时重新推导得到与正常路径一致的结果~~——**该说法不成立，已在 2026-10-06 限定补修中纠正**：`finalizing` 依赖"下一次请求"的输入估算、输出预留与剩余预算，模型轮次/步骤在响应落库后已变化，恢复时重新 `decide` 得到的是落库后的准入结论，不是原请求的收尾意图（复现见第 10 节）；补修后 `finalizing` 随响应持久化，恢复不再重新推导。`coreActionPending` 仍由持久工具结果推导，该半句成立。未复制第二套收尾逻辑。
3. **恰好一次消费**：结果消费标记与终态写入在同一事务内完成——`recordFinal(…, consumePersistedTurn=true)`、`recordWaitingForInput(run, question, true)`、`recordBudgetPartialAnswer(run, content, true)`；工具批次继续沿用既有 `batchHandled`。任一步失败整体回滚，重复接管/重复推进不会二次消费同一轮响应。
4. **不再重新解析模型配置**：恢复文本收尾在 `resolveRequest` 之前返回，因此结果保存后配置被禁用/删除也不影响一个无需再调用模型的有效结果；未来确需新请求时仍按当前配置解析。
5. **保持的保护**：取消检查、`AgentLeaseScope` 的 claim 版本/租约校验、版本 CAS、业务权限与幂等（工具侧未改）、原 source_mode 恢复语义（工具批次仍走 `recoveryBatch=true`）一律保留。

`[QUESTIONS]` 仍进入 `WAITING_FOR_USER_INPUT`；`finalizing && coreActionPending` 的文本仍是预算部分完成（`BUDGET_EXCEEDED` + `scope=CORE_ACTION_NOT_PERFORMED`）；空内容/无效响应仍是原错误语义。

### 3.3 故障注入方式

在真实 PostgreSQL 上先用真实仓储提交 MODEL_TURN，再把该运行置回未消费现场（清除 `batchHandled`、保持 RUNNING），等价于"MODEL_TURN 事务已提交、最终收尾尚未提交时进程退出"；随后由真实仓储接管并由真实协调器 `advance` 恢复。模型执行器为受控替身（记录调用次数），不使用真实模型。

### 3.4 核心验收（`PersistedModelTurnRecoveryPostgresTest`，9 项）

| 用例 | 断言 |
| --- | --- |
| 普通文本恢复 | 复用已保存文本；`callModel`/`resolveRequest` 零调用；终态 SUCCEEDED；FINAL_ANSWER 步骤、ASSISTANT 消息、RUN_SUCCEEDED 事件各恰好一次 |
| 重复接管 | 消费后 `pendingModelTurn` 为空、步骤不增、消息不重复、`claimNext` 不再领取、事件不重复 |
| `[QUESTIONS]` | 恢复到 `WAITING_FOR_USER_INPUT`，问题文本与消息一次，零模型调用 |
| 预算部分回答 | 收尾边界 + 核心动作未受理 → `BUDGET_EXCEEDED`，答复为已保存文本，消息一次，轮次已消费 |
| 已取消 | 恢复入口保持 `CANCELED`/`RUN_CANCELLED`，不消费结果、不请求模型、不写 ASSISTANT 消息 |
| 旧 claim 迟到提交 | 旧 claim 的 `advance` 被租约拦截；新 claim 收尾一次并正确终态 |
| 保存后删除模型配置 | `resolveRequest` 抛不可用仍恢复成功（恢复路径根本不解析配置） |
| 工具批次恢复 | 走工具执行器 `executeCalls(..., recovery=true)`，零模型调用 |
| 空内容轮次 | 查询条件排除空/纯空白正文轮次 |

## 4. 故障注入证据（先锁定旧问题，再修复）

同一批回归在"临时回退修复"的旧行为上执行，用于锁定原问题：

| 项 | 旧行为结果 | 修复后结果 |
| --- | --- | --- |
| R1（5 项） | 3 失败：租约等待被累计（10 秒变约 360 秒）、多次接管累计错误、真实到限提前 | 5 通过 |
| R2（9 项） | 6 失败 + 3 错误：普通文本/`[QUESTIONS]`/预算部分回答/配置删除/旧 claim 全部走了"再次请求模型"，`resolveRequest` 返回 null | 9 通过 |

回退只临时改动 `claimNext` 的结算表达式与 `pendingModelTurn` 的条件 + 协调器恢复分支，验证后立即用备份恢复固定版本并重新确认两套回归通过（`mvn -o test -Dtest=AgentCrashRecoveryTimingPostgresTest,PersistedModelTurnRecoveryPostgresTest` → 14/14 通过）。

## 5. 继承修改与本轮修改的区分

本轮改动的文件：

| 文件 | 本轮内容 |
| --- | --- |
| `db/migration/V60__agent_last_progress_at.sql` | 新增（本轮唯一迁移） |
| `AgentRepository.java` | `claimNext` 结算语义与锚点重置；`pendingModelTurn` 复用条件；`recordFinal/recordWaitingForInput/recordBudgetPartialAnswer` 的消费标记重载 |
| `AgentRunEventRecorder.java` | `markProgress`；进度写入边界；`markModelTurnConsumed`；三个终态写入的消费标记；`accountActiveTime` 清空锚点 |
| `AgentRuntimeCoordinator.java` | 恢复分支放到配置解析前、复用已保存文本；抽出并共用 `completeTextTurn`；`completeFromEvidence` 的消费标记 |
| `AgentCrashRecoveryTimingPostgresTest.java`、`PersistedModelTurnRecoveryPostgresTest.java` | 新增两项故障回归 |
| `NextStageMigrationPostgresTest.java`、`LegacyApprovalCitationUpgradeTest.java`、`TaskPlanSpringBeanPostgresIntegrationTest.java`、`Phase08MigrationSafetyIntegrationTest.java` | 迁移清单/最新版本断言随 V60 对齐（6→7、14→15、59→60、版本列表追加 60） |
| `AgentRuntimeBehaviorTest.java`、`AgentRuntimeCoordinatorTest.java`、`CrossTickToolCallTest.java` | 终态写入新增消费标记参数后的桩与验证对齐 |

继承未提交改动（本轮未改其语义）：M1–M4 装配/投影/目录归属、`AgentToolOutputProjector`、`ListPageContract`、`V59__agent_invocation_source_mode.sql`、前端 `modules/agent`、各分页/摘要/配置切换回归等，见 `docs/agent-runtime-maintenance.md`。

## 6. 验证执行与结果

- 两项故障回归（真实 PostgreSQL，Testcontainers；不调用真实模型）：R1 5/5、R2 9/9 通过。
- 受影响既有回归：`AgentRepositoryIntegrationTest` 39/39、`AgentModelConfigurationSwitchPostgresIntegrationTest` 9/9、`AgentRuntimeCoordinatorTest` 32/32、`AgentRuntimeBehaviorTest` 25/25、`CrossTickToolCallTest` 7/7、`AgentRuntimeRequestSnapshotTest` 3/3、`AgentWriteProposalSpringIntegrationTest` 2/2、`AgentMigrationIntegrationTest` 6/6、`AgentMigrationChecksumTest` 1/1 通过。
- 迁移边界：`NextStageMigrationPostgresTest`（V53 → 当前，逐表列摘要不变）、`LegacyApprovalCitationUpgradeTest`（V45 → 当前）、`TaskPlanSpringBeanPostgresIntegrationTest`、`Phase08MigrationSafetyIntegrationTest` 通过；`ExistingDataUpgradeRehearsalTest` 保持显式 opt-in 跳过（`AI_UPGRADE_REHEARSAL`）。
- 后端全量（最终一次重跑）：**1103 项，通过 1092，失败 0，错误 0，显式跳过 11**，`BUILD SUCCESS`。
  - 跳过全部为显式 opt-in/外部依赖门控，未新增任何跳过：`BrowserAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、`RealAcceptanceHostTest`、`RealRetrievalBaselineTest`、`ReliabilityAcceptanceHostTest`、`ReliabilityOperationDatabaseTest`（4 项）、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest`，各 1 项（`ReliabilityOperationDatabaseTest` 4 项）。
  - 日志：`docs/acceptance-evidence/2026-10-05/recovery-reliability/backend-full-recovery-reliability-20261005.log`。
  - 首次全量曾在 3 个迁移清单断言上失败（V60 改变迁移条数与最新版本），对齐断言后重跑通过；首次失败保留在同一日志文件中，未改写为通过。
- 本轮关键回归在全量中实际执行（未用 skip 绕开）：`AgentCrashRecoveryTimingPostgresTest` 5 项、`PersistedModelTurnRecoveryPostgresTest` 9 项、`NextStageMigrationPostgresTest` 1 项、`AgentRepositoryIntegrationTest` 39 项、`AgentRuntimeCoordinatorTest` 32 项、`AgentRuntimeBehaviorTest` 25 项，均零失败零错误。
- 注释与可见性清理后的最终针对性复跑（6 类、111 项）：`AgentCrashRecoveryTimingPostgresTest` 5、`PersistedModelTurnRecoveryPostgresTest` 9、`AgentRepositoryIntegrationTest` 39、`AgentRuntimeCoordinatorTest` 32、`AgentRuntimeBehaviorTest` 25、`LegacyApprovalCitationUpgradeTest` 1，全部通过、零失败零错误。
- 未执行：前端构建/测试、浏览器验收、真实模型调用（本轮无事件契约或前端改动，沿用既有浏览器证据）。

## 7. 资源清理与工作区状态

- 隔离资源：本轮只使用 Testcontainers 临时 PostgreSQL（`pgvector/pgvector:pg17`），运行结束后容器与 reaper 已自动删除；未创建持久容器、卷或脚本。
- 业务数据库：`ai-collab-postgres` 等既有容器全程保持停止，未被启动、迁移或写入。
- 临时脚手架：验证旧行为用的临时回退与探针测试已删除；工作区无 `.dsh-r1r2-backup` 等临时目录，`git status` 中无本轮临时文件。
- 工作区：本轮改动（3 个主源码文件、4 个迁移断言对齐、3 个测试桩对齐、2 个新回归、1 个迁移、1 份报告 + 维护文档入口）全部保留未提交；未 `reset`/`clean`/`stash`/覆盖任何已有工作，未推送、未合并 main、未部署。
- `git diff --check` 只有两处既有测试文件的 CRLF 提示，无空白错误。

## 8. 未验收项与证据边界

- 真实模型回答与摘要语义质量、24 轮长对话、并发负载：本轮未运行，仍是独立未验收项。
- R1 的未确认尾段（最后一次确认进度 → 进程退出）按设计不计入执行时长；本实现不声称精确计时，也不声称能恢复该尾段消耗。若将来需要更细粒度，需要新的持久进度边界，不属本轮。
- 主动暂停/继续、到限续接剩余工作：未实现，本轮不声称具备。
- 浏览器/前端：本轮未改前端与事件行为，未重跑浏览器验收。
- 自动重试体验的可视化、精确计费、逐字流式、自动择模：仍为后续独立任务。

## 9. 后续：主动暂停与继续（保留需求，本轮未提前追加）

- 暂停只停止 Agent 后续动作；已受理的后台规划继续完成。
- 继续时复用原身份结果与已完成动作，不重复已完成写入与审批；下一次请求按当前模型配置选择模型。

## 10. R2 限定补修：已持久化响应的正常处理与接管处理一致（2026-10-06）

R2 主体合入后的复核（`docs/agent-recovery-review-20261006.md`）在隔离 PostgreSQL 上复现了三个边界，证明"仅凭持久计数即可还原原请求意图"不成立。本节记录只针对这三个边界的补修；第 2–8 节的结论凡与本节冲突处以本节为准。改动保留未提交。

### 10.1 复核复现的三个边界（旧行为，证据归档于 `docs/acceptance-evidence/2026-10-06/recovery-review/`）

| 边界 | 旧行为 | 探针输出 |
| --- | --- | --- |
| 第 8 次模型请求是合法收尾轮，保存后退出 | 接管按落库后的 8 轮先判 EXHAUSTED，完整答案被工具证据兜底文字覆盖 | `PROBE_CAP … recovered=BUDGET_EXCEEDED savedAnswerRetained=false` |
| 实际输入刚超过有效限额（50000/50001） | 恢复路径没有输入实际超限检查，文本被记为 SUCCEEDED | `PROBE_INPUT … recovered=SUCCEEDED savedAnswerPublished=true` |
| 输出剩余额度触发 `needsFinalRequest`（decide 仍 CONTINUE） | 恢复重新 `decide` 仍 CONTINUE，本应 BUDGET_EXCEEDED 的部分完成被记成 SUCCEEDED | `PROBE_PHASE before=CONTINUE needsFinalRequest=true after=CONTINUE … recovered=SUCCEEDED` |

### 10.2 修法（生产代码 3 处，均在既有职责边界内）

1. **区分"下一次请求准入"与"消费已提交结果"**：协调器抽出 `afterResponseSaved(...)` 作为响应保存后的唯一检查与分派入口（输入实际超限 → `RUN_INPUT_ACTUAL_OVERSET` 预算终态、收尾轮协议校验、工具批次分派、文本收尾），正常路径（本轮刚落库）与接管恢复（5b 分支）共用；恢复分支位于 `decide` 之前，消费已落库结果不再被 EXHAUSTED 兜底覆盖，也不再为收尾调用模型或解析配置。
2. **强制收尾意图随响应持久化**：`ModelTurnResult` 增加 `finalizing` 元数据，取值是消息组装与 `needsFinalRequest` 调整之后、实际出站请求使用的值；`recordModelTurnWithSettlement` 新增 `finalizingIntent` 参数，与响应、用量结算同一事务提交。恢复按 `persistedFinalizing` 读取该值，不根据响应正文猜测。历史记录缺少元数据（`null`）时保守回退：核心动作已发生按普通轮次完成、未发生按收尾轮记预算部分完成——两种回退都保留已有答案；无法可靠还原的信息：该响应出站时是否为模型可见的收尾指令、当时的输入估算/输出预留语境。
3. **恢复批次来源区分**：`afterResponseSaved` 透传 `recoveryBatch`/`requestLegacyMode`，恢复批次的写工具继续按持久化 `source_mode` 校验（补修中间态曾把该参数丢失为 `false`，已修复并回归锁定）。

协调器、记录器、收敛策略职责未变：准入与收尾判定仍在 `AgentConvergencePolicy`，持久事实与原子写入仍在 `AgentRunEventRecorder`/`AgentRepository`；未新增迁移（`finalizing` 存于轮次 `output_json` 内）、未建通用恢复平台。

### 10.3 补修回归（`PersistedModelTurnRecoveryPostgresTest` 9 → 16 项）

新增 7 项按"同一份请求前状态分别走正常完成 / 保存后退出并接管"成对比较（答案、终态、步骤、消息、事件、新增模型调用数）：

| # | 用例 | 断言要点 |
| --- | --- | --- |
| 10 | 模型轮次边界（第 8 请求=合法收尾轮） | 两路径均 SUCCEEDED 且同答案；恢复零调用零配置解析；FINAL_ANSWER/消息/事件恰好一次 |
| 11 | 步骤边界（剩余步骤=2 时发出的收尾轮） | 保存后 decide 已是 EXHAUSTED，接管仍按原意图 SUCCEEDED |
| 12 | 实际输入超限（纯文本） | 两路径均 BUDGET_EXCEEDED 且无回答；用量恰好一次（结算 1 行、actual 不再累计） |
| 13 | 实际输入超限（工具批次） | 不新增工具执行（调用按既有语义收口 SKIPPED/BATCH_ENDED），无新工具结果步骤 |
| 14 | `needsFinalRequest` + decide=CONTINUE | 两路径均 BUDGET_EXCEEDED 部分完成，正文保留 |
| 15 | 保存后计数使 decide 变 FINALIZE | 已持久化的 false 意图不被改写，两路径均 SUCCEEDED |
| 16 | 旧元数据（无 finalizing 标记）兼容 | 无核心动作要求→普通完成；核心动作未发生→预算部分完成；均保留答案、零调用 |

**先证明现有版本失败**：分两批完成——复核阶段的一次性探针锁定边界 1–3（上表，`REVIEW_PROBES_CONFIRMED=3`）；补修阶段再把协调器临时回退为"先准入判定、恢复重新推导意图"的旧顺序，新增 7 项中 5 项（10/11/14/15/16）在旧行为上失败，边界 2 的恢复侧检查缺失已由探针 `PROBE_INPUT` 覆盖；验证后立即恢复修复版并复跑 16/16 通过。

### 10.4 补修涉及的既有测试对齐（不改语义）

- `AgentRuntimeCoordinatorTest`、`AgentRuntimeBehaviorTest`、`CrossTickToolCallTest`、`AgentRuntimeRequestSnapshotTest`：`recordModelTurnWithSettlement` 打桩/验证从 6 参对齐为 7 参（新增意图参数）。
- `AgentCrashRecoveryTimingPostgresTest`：毫秒级时间容差过紧导致偶发失败（两次连续运行各有一个不同用例越界 1ms/17ms）。放宽上界裕量（10_050→10_500、20_050→20_500、22_100→23_000、+50→+1_000，仍比被锁定的旧缺陷值 ~360 000 小两个数量级）；第 5 项改为比较持久化 `active_elapsed_ms` 而非含当前 claim 活值的实时和。计时实现与语义未动。

### 10.5 补修验证

- 新增回归：`PersistedModelTurnRecoveryPostgresTest` 16/16 通过（真实 PostgreSQL、Testcontainers，无真实模型）。
- 受影响回归：`AgentRuntimeCoordinatorTest` 32、`AgentRuntimeBehaviorTest` 25、`CrossTickToolCallTest` 7、`AgentRuntimeRequestSnapshotTest` 3、`AgentConvergencePolicyTest` 8、`AgentLoopGuardTest` 13、`AgentCrashRecoveryTimingPostgresTest` 5、`AgentRepositoryIntegrationTest` 39、`AgentModelConfigurationSwitchPostgresIntegrationTest` 9、`AgentWriteProposalSpringIntegrationTest` 2——全部通过（各项单独计数见全量日志）。
- 后端全量（补修后一次重跑）：**1110 项，通过 1099，失败 0，错误 0，显式跳过 11**，`BUILD SUCCESS`（1110 = 原批 1103 + 新增 7 项）。日志：`docs/acceptance-evidence/2026-10-06/recovery-review/backend-full-r2-repair-20261006.log`。11 项跳过与原批完全一致，均为显式 opt-in/外部依赖门控（`BrowserAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、`RealAcceptanceHostTest`、`RealRetrievalBaselineTest`、`ReliabilityAcceptanceHostTest`、`ReliabilityOperationDatabaseTest` 4 项、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest`），未新增任何跳过；本轮关键回归（`PersistedModelTurnRecoveryPostgresTest` 16 项、`AgentCrashRecoveryTimingPostgresTest` 5 项、`NextStageMigrationPostgresTest` 1 项等）在全量中实际执行且零失败零错误。
- 未执行：前端/浏览器/24 轮（本轮无前端与事件契约改动）。

## 11. R2 工具恢复补修：部分批次额度与旧工具响应兼容（2026-10-06 第二轮）

上一节补修养合后的工具恢复复核（`docs/agent-r2-tool-recovery-review-20261006.md`）又复现两处回归，均属共享入口新增检查对原工具恢复行为的改变。本节只覆盖这两处；第 10 节的三个边界保持原样。证据归档于 `docs/acceptance-evidence/2026-10-06/recovery-review/tool-batch-review/`（探针 `PROBE_PARTIAL_TOOL` / `PROBE_OLD_TOOL`）。

### 11.1 两处缺陷与修法

1. **部分完成批次被重复计入总工具额度**：协调器恢复批次仍按 `turn.toolCalls().size()` 整批校验总额度，而已提交的工具结果在落库时已经推进过 `tool_calls_used`——"12 已用 + 合法批次 4、完成 2 项后退出"的现场被按 14+4=18 误判超限，剩余 2 项在到达执行器之前就被 SKIPPED。修法：`AgentConvergencePolicy.validateToolBatch` 增加 `alreadyCompletedCalls` 参数（单轮数量仍按原批次校验，总额只计尚需执行的新增调用，真实超限仍拒绝）；协调器恢复路径按原 invocation 身份统计已落结果调用数（新仓储方法 `AgentRepository.countSettledInvocations`，仅按 tool_call_id 计数，调用名/参数一致性仍由执行器 `knownInvocationResult` 逐项校验）。新轮次传 0，语义不变；来源模式、权限、审批与预算保护未动。
2. **旧工具响应被误判为禁止工具的收尾轮**：缺少 `finalizing` 元数据时回退一律用 `isCoreActionPending`——对旧最终文本是保守部分完成（保持不变），但套到旧工具批次会把"核心动作尚未发生"（恰说明这批调用正要执行它）当成收尾协议违规，在到达权限/来源模式校验之前整体否决。修法：`persistedFinalizing` 对工具批次回退为普通轮次（false），继续走 invocation 身份、source_mode、权限、Skill 白名单与执行限额校验的恢复链；显式持久化 `finalizing=true` 的工具响应仍按协议违规拒绝；旧文本的保守回退与答案保留规则不变。

职责边界不变：准入判定仍在收敛策略，持久事实统计在仓储，执行校验在工具执行器；无迁移（复用既有表）、无通用恢复平台。

### 11.2 补修回归（`PersistedModelTurnRecoveryPostgresTest` 16 → 23 项）

关键批次使用**真实工具执行器 + 安全替身工具**（`list_tasks` 只读替身记录真实执行参数、`start_task_plan` 审批写替身），验证剩余执行次数而非只断言 mock 参数：

| # | 用例 | 断言要点 |
| --- | --- | --- |
| 17 | 总额度 16、已用 12、合法批次 4、完成 2 项后退出 | 接管真实执行器只执行剩余 2 项；最终计数 16；16 个 invocation 全 SUCCEEDED、零 SKIPPED；结果/步骤不重复 |
| 18 | 原批次全部完成但未收尾 | 全部按原身份复用，替身零执行；不因 16+4 误判超限；运行继续 QUEUED |
| 19 | 剩余调用确实超总额度（14 已用+3 剩余=17>16） | 仍拒绝：BUDGET_EXCEEDED、替身零执行、剩余 3 项 SKIPPED（守卫：不放松总量检查） |
| 20 | 原批次 5 项超过单轮上限 4 | 仍按原批次拒绝（守卫：单轮校验不因部分完成放松） |
| 21 | 旧元数据 NATIVE_TOOLS 合法规划调用 | 进入恢复校验与执行链，提案受理（PROPOSAL_PENDING）；真实规划写入不发生（替身零执行）；旧实现在到达来源模式校验前即否决 |
| 22 | 旧元数据 LEGACY_READ_ONLY 写调用 | 仍拒绝（`LEGACY_WRITE_TOOL_FORBIDDEN`），不进入提案 |
| 23 | 显式 `finalizing=true` 的工具响应 | 仍按协议违规拒绝（守卫：回退只作用于缺元数据记录） |

实际输入超限优先终止由第 12/13 项既有用例继续覆盖；旧文本保守回退由第 16 项继续覆盖。

**先证明现有版本失败**：临时把协调器回退为"整批计入额度、旧工具响应按核心动作回退"的旧行为，7 项中 4 项（17/18/21/22）失败，守卫用例（19/20/23）通过；验证后立即恢复修复版并复跑 23/23 通过。

### 11.3 验证

- 受影响回归：`AgentConvergencePolicyTest` 8、`AgentRuntimeCoordinatorTest` 32、`AgentRuntimeBehaviorTest` 25、`CrossTickToolCallTest` 7、`AgentRuntimeRequestSnapshotTest` 3、`AgentLoopGuardTest` 13、`AgentCrashRecoveryTimingPostgresTest` 5、`AgentRepositoryIntegrationTest` 39、`AgentModelConfigurationSwitchPostgresIntegrationTest` 9、`AgentWriteProposalSpringIntegrationTest` 2、`PersistedModelTurnRecoveryPostgresTest` 23——全部通过。
- 后端全量（补修后一次重跑）：**1117 项，通过 1106，失败 0，错误 0，显式跳过 11**，`BUILD SUCCESS`（1117 = 上一轮 1110 + 新增 7 项）。日志：`docs/acceptance-evidence/2026-10-06/recovery-review/backend-full-r2-tool-recovery-20261006.log`。统计前已清理 surefire 目录中已删除 Scratch 测试的旧 XML（复核文档指出直接累计会多出 1 项）；11 项跳过与既有批次一致，均为显式 opt-in/外部依赖门控，未新增跳过；本轮关键回归（`PersistedModelTurnRecoveryPostgresTest` 23 项、`AgentCrashRecoveryTimingPostgresTest` 5 项等）在全量中实际执行且零失败零错误。
- 未执行：真实模型、浏览器、规划、24 轮、业务数据库（本轮无前端/事件契约变化）。
