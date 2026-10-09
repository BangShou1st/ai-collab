# 委派结果资料覆盖传递交付记录（2026-10-07）

> 基线：`codex/context-foundation` / `e572b9c`。
> 本轮目标：完善委派结果的资料覆盖传递——让父运行拿到子运行真实的\"已读/未读范围\"事实，
> 不再只凭子运行的文字转述。不扩新功能、不改预算体系、不重开 SSE、不重设计前端。
> 证据类型：[测试] 自动测试（真实 PostgreSQL/JUnit）；[实验] 真实模型 + 真实文档浏览器实测；[环境] 运行环境事实。

## 1. 问题（已确认）

单文档委派实验 B-narrow2（`f76098d9`/`5a506353`）中，子运行实际取得了提纲（4 节
`HEURISTIC_HEADINGS`）并读完 4 节正文，但父综合**两次**声称\"未取得提纲\"（见
`docs/acceptance-evidence/2026-10-07/budget-semantics/bnarrow2-answer.txt`）。
父运行只能读到 `DELEGATION_COMPLETED` 的结论文本与结构化引用，看不到子工具级覆盖事实。
这不是预算缺陷，而是用户可见的回答准确性问题：父回答会把\"未知范围\"表述成\"未取得\"。

## 2. 设计：`DELEGATION_COMPLETED` 附带结构化覆盖事实（无新表/新平台）

不新建平台或表。在既有回收步骤 `DELEGATION_COMPLETED` 的 `output_json` 上新增 `coverage` 字段：

- **来源**：子运行持久化的成功/失败工具结果（`agent_step` 的 `TOOL_CALL_COMPLETED`），
  由纯函数提取，**不由模型填写**、**不追加任何统计用工具调用**、**不回传工具原文**。
- **内容**：文档/版本身份（`documentId`/`snapshotId`/`processingStatus`）、提纲取得状态与可信度
  （`OBTAINED`/`FAILED`/`NOT_ATTEMPTED`，`trust=HEURISTIC`）、已读章节（数量与 heading）、
  分页/截断限制、已知覆盖缺口、检索命中计数、结束原因（子运行终态）。
- **边界**（与文档工具契约一致）：
  - 检索命中（`RELEVANT_EXCERPTS_ONLY`）**不等于**正文读完，不计入已读章节；
  - `HEURISTIC_HEADINGS` 提纲是启发式标题识别，**不是**保证完整的目录；
  - 未取得提纲时未读范围未知，**不得**编造未读清单；只有取得提纲后才比较\"列出 vs 已读\"；
  - 旧回收记录缺 `coverage` 字段时按**未知**渲染，绝不解释成\"未取得提纲\"。

实现：`agent/domain/model/DelegatedResearchCoverage`（提取 + 渲染，纯函数，可单测）。
`AgentRunEventRecorder.resumeParent` 在写 `DELEGATION_COMPLETED` 时聚合覆盖事实；
`AgentRuntimeCoordinator.childResearchEvidence` 把覆盖事实渲染成独立的
`<CHILD_RESEARCH_COVERAGE>` 块，与 `<CHILD_RESEARCH>`（UNTRUSTED 研究文字）分开注入父上下文。

## 3. 父上下文注入

- `<CHILD_RESEARCH>`：子运行文字结论，保持 UNTRUSTED 数据块语义不变；
- `<CHILD_RESEARCH_COVERAGE source="persisted-tool-results" verified="true">`：已校验覆盖事实，
  与 UNTRUSTED 正文块分开；结尾统一说明：不得把提纲级证据说成正文完整覆盖、检索命中不等于正文读完、
  覆盖块缺失或标记未知时按\"范围未知\"处理（不断言提纲是否存在、不枚举未读章节）。
- 保留既有来源身份校验（citations）、预算部分产出回传、父子隔离与幂等恢复；
  覆盖块只含结构化元数据，**不**回传全部工具原文。

## 4. 测试（[测试]）

**口径说明**：本轮新增用例与实现同期落地，未单独在未修复代码上跑一遍红灯（新增字段之前不存在，断言无对应实现）；完整性由集成用例对真实 PostgreSQL 的断言保证。

**本轮新增（按本轮实际结果单独统计）**：

| 用例类 | 数量 | 覆盖点 | 结果 |
| --- | --- | --- | --- |
| `DelegatedResearchCoverageTest`（新增，纯函数） | 9 | 成功提纲、启发式提纲、完整/分页/截断读取、工具失败、未读清单只在取得提纲后、旧记录按未知、0 工具结果如实为空 | 9 通过 |
| `AgentDelegationPostgresTest` 新增用例 | 5 | 覆盖随回收附带、父综合分离注入、旧记录按未知、重复回收覆盖唯一、四文档提纲级降级 | 5 通过（该类 23→28） |

新增集成用例：`delegationCoverageIsDerivedFromChildPersistedToolResults`、
`parentSynthesisReceivesVerifiedCoverageSeparateFromUntrustedChildText`、
`legacyDelegationRecordWithoutCoverageIsInjectedAsUnknown`、
`repeatedChildCollectionKeepsSingleCoverageRecord`、
`outlineOnlyCoverageIsReportedAsIncompleteBodyCoverage`。

**受影响回归**（全部通过）：委派 `AgentDelegationPostgresTest` 28（单独跑）；
定向批次 184 项 = 暂停续跑 19 + 仓库 `AgentRepositoryIntegrationTest` 40 +
持久恢复 `PersistedModelTurnRecoveryPostgresTest` 23 + 崩溃接管 `AgentCrashRecoveryTimingPostgresTest` 5 +
协调器 `AgentRuntimeCoordinatorTest` 32 + 行为 `AgentRuntimeBehaviorTest` 25 +
消息组装 `AgentModelMessageComposerV2Test` 16 + 续跑识别 `ResumeIntentRecognizerTest` 5 +
用量 `AgentActualTokenUsageIntegrationTest` 19。

**全量**：`mvnw test` 一次运行 **1221 项，0 失败，1 错误，11 跳过**；唯一错误为
`PersistedModelTurnRecoveryPostgresTest.trulyOverBudgetRemainingCallsStillRejected` 的
Testcontainers JDBC 连接抖动（`CannotGetJdbcConnection`），单独重跑该类 23/23 通过。
不写成\"一次全量零失败\"。

## 5. 真实模型验收（[实验]，browser-skill/bsk 实测 Web UI，仅电脑端）

[环境] 后端本机 `mvnw spring-boot:run -Dspring-boot.run.profiles=local`（8080），vite dev（5173）；
Ollama `qwen3-embedding:0.6b`（1024 维）活跃；项目「Zen规划冒烟」4 份文档 READY；
模型 `mimo-v2.6-flash-free`（Zen 预置，与生产一致）。浏览器经 `bsk` 后台会话操作，验收后已 `session stop`。

| 实验 | 委派 | 结果 | 子运行行为 | 用户可见交付 |
| --- | --- | --- | --- | --- |
| B-narrow3（`fcfcd044`/`edaf73c7`） | 是（单文档） | 父子双 SUCCEEDED | 提纲 + 逐节读完 4 章（`read_document_section`×4，均 `hasMore=false`） | 3166 字：**\"提纲 4 节，已逐节读完全部 4 章\"**，4 条真实 chunk 引用；启发式提纲边界声明；无\"未取得提纲\" |
| B5（`fa9ac404`/`732284e4`） | 是（四文档，降级样本） | 父子双 SUCCEEDED | 仅 4 份文档提纲（`read_document_section` 未执行） | 3825 字：声明\"只完成提纲读取、未读任何章节正文\"、15 节未读清单、0 引用、启发式提纲边界 |

**关键验证点**：

1. **误称消除**：B-narrow3 父最终回答中 `grep \"未取得提纲\"` = 0（上一轮 B-narrow2 为 2 次）；
   父回答依据覆盖块写\"提纲 4 节、已读完全部 4 章\"，并按覆盖块的边界提醒保留
   \"HEURISTIC_HEADINGS 不是完整目录\"的限定。
2. **覆盖事实与真实工具序列一致**：该 run 的 `DELEGATION_COMPLETED.coverage` 为
   `documents=1`、`outline.status=OBTAINED`、`structure=HEURISTIC_HEADINGS`、`sectionsListed=4`、
   `sectionsRead.count=4`（headings = 架构概述/评分算法/与验收标准的对应关系/性能设计）、
   `gaps=[]`、`limits=[]`、`endReason=SUCCEEDED`（证据 `b-narrow3-coverage.json`）。
3. **提纲级证据不冒充正文覆盖**：B5 覆盖块对 4 份文档逐条给出
   \"已取得提纲但未读取任何正文\"；父回答据此声明只覆盖提纲级、列出未读章节、0 引用
   （`grep \"全文读完|正文完整覆盖\"` = 0）。
4. **旧记录兼容**：集成用例把无 `coverage` 字段的旧格式 `DELEGATION_COMPLETED` 注入父上下文，
   断言包含\"覆盖事实：未知\"且不含\"未取得提纲\"。

证据：`docs/acceptance-evidence/2026-10-07/delegated-coverage/`。

## 6. 仍未验证 / 已知限制

- **模型可能再次发起委派**：另一次单文档尝试（`3c812574`/`b3471ebb`）中，父运行在综合前再次请求
  `delegate_document_research`，被预算拒绝受理后运行终态为 FAILED（未产出最终回答）。
  该拒绝后整轮停止的收尾口径属既有行为、非本轮覆盖传递引入；是否改为\"拒绝后保留运行、用剩余预算直接研究\"
  未在本轮实现，列为后续评估。该次运行的覆盖事实本身正确（2 文档提纲、0 正文读取，gaps 如实标注）。
- 本轮只把覆盖事实用于**父综合上下文**；`completeFromEvidence` 的预算兜底路径仍只用子运行文字产出
  （未把覆盖块并入兜底文本），未做真实模型验证。
- 覆盖提取只识别文档研究工具（outline/read/search）；其他只读工具的覆盖语义未纳入。
- 真实 HTTP Last-Event-ID 补回、父 RUNNING 竞争窗口用量回收等保持既有待验证清单，本轮未动。

## 7. Git 提交

1. `feat(agent)`：委派覆盖事实提取与分离注入（新增 `DelegatedResearchCoverage`、
   `resumeParent` 附带 `coverage`、协调器 `<CHILD_RESEARCH_COVERAGE>` 注入、单测与委派集成回归）。
2. `docs(agent)`：本交付记录 + 证据 + 维护文档与预算语义交付记录的口径更正。
