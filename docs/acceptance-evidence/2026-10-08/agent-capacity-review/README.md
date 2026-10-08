# Agent 容量 v2 补充审查证据

- 基线：`ef2a514`，日期：2026-10-08。
- `CapacityReviewProbe.java.txt`：临时生产类探针源码，Repository/模型为 mock；不反射私有方法。
- `probe-output.txt`：探针实际运行日志，3 个预期正确行为断言失败，进程因此返回 1。
- 临时编译输出在 `ai-collab-backend/target/review-capacity-20261008/classes`，不进入 Maven test-classes。
- 使用已有 Surefire XML 的 classpath，直接 javac/java 执行；不启动 Maven、不调用真实模型、不访问生产或测试数据库。
- 这是未修复反例，不能单独表述为修复通过或完整红绿。

# 修复轮红绿复现（同日第二轮，基线 ef2a514 → 修复提交）

- `baseline-red-run.log`：探针转正式回归 `AgentRunContextCompactionRegressionTest`
  在**未修复基线**（`ef2a514` 临时 worktree，不污染工作区）上的实际运行——**4/4 失败**：
  1. `committedRunContextSummaryReachesComposerAndReplacesCoveredPrefix`（C1：摘要不进入 Composer）
  2. `unsentTailStaysUncoveredWithPartialOffsetInsteadOfWholeStepCoverage`（C3：虚报整条覆盖）
  3. `largeWindowKeepsOlderRawEvidenceWithoutPressureProjection`（C2：无压力仍裁旧正文）
  4. `compactionTriggersOnPreClipActiveSourcesEvenWhenClippedCompositionLooksSmall`（C2：裁后体积判断触发）
- worktree 中仅两处**编译适配**（测试引用了修复后新增的 API），断言语义不变：
  1. `completeRunContextAttempt` 捕获改用 6 参兼容重载（基线签名）；
  2. 删除 `stats().droppedSourceChars()` 断言行（基线无该字段），C2 触发判定仍走
     `maybeSummarize` 完整行为路径断言。
- 修复后同一测试文件（零适配）4/4 通过；另新增：
  - `AgentRunContextCommitPostgresTest`（C4/C1，Testcontainers 隔离 PostgreSQL + 真实 Flyway，6 项）；
  - `AgentAuxiliaryOutboundRegressionTest`（C5 辅助出站统一规则 + 续租生命周期，3 项）。
- `goalRevisionConflictCannotPublishButMatchingRevisionPublishes` 与
  `twoCompactionCyclesAdvanceThroughRealPersistence` 依赖修复后的 7 参提交入口，
  基线上无法编译；其对应缺陷（fencing 缺失、覆盖不推进）的红灯由上列 2/4 号用例
  与本目录探针反例覆盖，**不把这两项表述为"基线红绿复现"**。
