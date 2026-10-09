# D1-D8 交付后复核（2026-10-09）

## 1. 基线与结论

- 实际分支：`codex/context-foundation`。
- 实际 HEAD：`9707012`，前置 `21b6432`（测试）、`722737d`（生产修复）。
- 开始时工作区只有 `.freebuff/`、`.dsh-acl-recovery/` 两个既有未跟踪目录。
- 本轮是只读代码审查与隔离实验，不修改生产代码、正式测试、用户 AI 配置或业务库，不提交、不 push。
- 已阅读全文：[D1-D8 交付报告](agent-context-capacity-d1d8-delivery-20261008.md)，复核五个生产文件的实际差异、新增正式回归与持久化调用关系。

原报告所列的修复确实已经落地，既有正式回归本轮重跑也通过。不过，D2、D8 仍有三个可复现的遗漏分支。下一轮应局部补齐它们，而非再设计一套预算、摘要或编排体系。

## 2. Findings

| 编号 | 优先级 | 当前真实缺陷 | 本轮证据 |
| --- | --- | --- | --- |
| E1 | P1 | RUN_CONTEXT 发布与会话目标修订未串行化 | 真实 PostgreSQL：目标修订 1 已提交，随后旧摘要仍 COMMITTED，摘要目标修订为 0 |
| E2 | P1 | RUN_CONTEXT 压缩失败丢失 auxiliaryAttempted 事实 | 实际生产协调器出站顺序 `[model-B, model-A]`，后一主请求仍使用旧配置 |
| E3 | P1 | 辅助失败后虽刷新配置，却不按新窗口重新 compose | 实际生产协调器返回 BUDGET_EXCEEDED；同样来源按 B 窗口重新 compose 可用，仅 2735 字符 |

### E1：条件 UPDATE 不等于跨行串行化

入口：`AgentRunEventRecorder.completeRunContextAttempt`。

- 检查 SELECT 在 194-203 行使用 `FOR UPDATE OF r`，只锁 `agent_run`，没有锁 `agent_session`。
- 发布 UPDATE 在 258-273 行通过 `LEFT JOIN agent_session` 检查 `goalRevision`。
- 目标的真实写入路径 `AgentWorkingState.appendUser` 锁会话行，并更新 `agent_session.working_state`。
- READ COMMITTED 的发布语句使用自己的语句快照；另一事务更新会话行不会被运行行锁阻挡，也不会使正在执行的发布语句重新获取会话快照。

本轮用 Testcontainers `pgvector/pgvector:pg17`、真实 Flyway v1-v64、真实事务、真实 Recorder/Repository 复现。临时数据库触发器仅在生产发布 UPDATE 到达写入前暂停执行，以稳定放大并发窗口；没有替换生产 SQL、返回值或判据。

交错：发布检查通过并进入真实 UPDATE → 触发器等待 → 另一事务调用生产 `repository.createRun(..., "新目标: ...")` 推进工作状态并提交 → 放行发布 → 旧摘要发布成功。

输出：

```text
goalRevision=1 summaryStatus=COMMITTED summaryGoalRevision=0
PROBE_RESULT tests=1 failures=1
```

影响：过期目标的辅助产物仍可能成为有效摘要并推进覆盖。该证据是受控并发复现，不是生产环境事故或浏览器自然触发记录。

建议：在短发布事务内，把目标修订的真实会话行也纳入串行化保护，并在取得相应保护后读取/校验修订。先梳理运行行、会话行及现有目标写入的锁序，不直接给外连接加不可用的 `FOR UPDATE OF s`，不跨模型 HTTP 持锁。保留发布与结算一次性转换、fenced 用量结算、已受理暂停语义。

### E2：详细结果只覆盖会话摘要分支

入口：`AgentContextSummarizer.maybeSummarizeDetailed`，198-203 行。

- `compactRunContext` 仍只返回 boolean。
- 成功时构造 `(true, true)`；失败、不合格等返回 false 后直接返回会话摘要分支的结果。
- 如果没有旧会话摘要候选，该结果为 `(false, false)`，抹掉前面已发生的 RUN_CONTEXT 出站事实。
- 协调器只在 `auxiliaryAttempted` 为 true 时刷新主请求配置。

探针走真实 Composer、Summarizer、Coordinator、Routing；仅仓库、配置存储和 Native 出站受控。旧工具轨迹使裁前估算达到触发线，RUN_CONTEXT 使用当前模型 B 并受控失败，会话摘要候选为空。结果：

```text
run-compaction outbound=[model-B, model-A] outcome=SUCCEEDED
expected: "model-B"
 but was: "model-A"
```

影响：下一次请求未按当前配置执行。现有 D8 正式用例只覆盖会话摘要失败，所以不会捕获该分支。

建议：两个 scope 共用可表达提交与尝试事实的结果；在合并阶段对 `auxiliaryAttempted` 做逻辑 OR。实际出站失败与资格不合格不得被下游无候选分支覆盖；准入前跳过/拒绝则保留未尝试事实。兼容现有 boolean 调用入口，无需第二套摘要器。

### E3：刷新预算后仍沿用旧消息视图

入口：`AgentRuntimeCoordinator`，395-425 行。

- `auxiliaryAttempted` 分支刷新 `resolved` 与 `requestBudget`。
- `composeV2` 却仍受 `if (contextCommitted)` 控制。
- 辅助失败或不合格且配置切换时，主请求继续使用旧模型窗口/协议模式下组装的消息，再直接检查新预算。

本轮受控模型窗口 A=1000000、B=20000，B 单次输出 1024、安全余量 2000，故 H=16976。A 组装可选旧历史 90000 字符；B 的会话摘要请求很短且能发送，但受控失败。

结果：

```text
fresh B composition chars=2735 H=16976
smaller-window outbound=[model-B] outcome=BUDGET_EXCEEDED
expected: SUCCEEDED
 but was: BUDGET_EXCEEDED
```

同样来源按 B 窗口调用实际 Composer 可成功组装，当前协调器却停止，未发出主请求。不是 B 真的容不下必选层，也不是累计输入限制。

建议：辅助实际发生后，按重新解析的同一快照重建主请求消息视图、预算估算与统一尾部/子证据层，复用原有一次降级重组策略。真正必选层超 H 时仍明确收口；不要为了让用例通过放宽窗口或屏蔽超限。无辅助请求时保持每请求一次解析契约。

## 3. 本轮验证范围

正式回归本轮重新执行：

| 类别 | 项数 | 结果 |
| --- | --- | --- |
| AgentContextReliabilityD1D8RegressionTest | 8 | 全通过 |
| AgentRunContextCompactionRegressionTest | 4 | 全通过 |
| AgentAuxiliaryOutboundRegressionTest | 3 | 全通过 |
| LegacyReadOnlyAgentExecutorTest | 8 | 全通过 |
| AgentRuntimeRequestSnapshotTest | 3 | 全通过 |
| AgentModelMessageComposerV2Test | 16 | 全通过 |
| AgentContextSummarizerTest | 25 | 全通过 |
| AgentRunContextCommitPostgresTest | 8 | 全通过 |
| 合计 | 75 | 0 失败、0 错误、0 跳过 |

额外审查探针：两个行为探针与一个真实 PostgreSQL 并发探针，3 项均以预期不变量断言失败。它们尚未修复，不能称为已完成红绿回归，也不能与正式 75 项混为全量失败。

- 本轮未执行后端全量；1309 项全量通过是交付报告的历史证据，不冒充本轮执行。
- 本轮未做浏览器验收、未调用真实模型、未观察多周期真实摘要质量。
- 本轮 Docker 原未启动，按本机约定后台启动 Docker Desktop 后使用隔离容器；容器在实验结束后已停止。未触碰业务数据库或既有前后端服务。
- 探针初版只识别会话摘要提示，误把 RUN_CONTEXT 返回为普通成功响应；修正夹具识别后重新执行，上面的归档日志来自修正后的运行。夹具问题不算产品缺陷。

证据：[本轮审查探针与正式报告](acceptance-evidence/2026-10-09/d1d8-post-review/README.md)。

## 4. 下一轮有限范围

建议新会话，只补 E1-E3：

1. E1 真实会话目标行并发保护，增加目标写入先提交/发布先提交的真实事务镜像；不得造成死锁、重复结算或长事务。
2. E2 两个 scope 的辅助尝试事实完整传递；失败、不合格、准入前跳过与无候选镜像必须分别测试。
3. E3 辅助后按新快照重新组装；覆盖大窗口切小窗口、Native/Legacy 切换、真实必选层超限与完全未发辅助的单解析镜像。

完成后再进入有界真实多周期质量观察。现有容量 v2、父子独立额度、暂停输入续跑、重试恢复、审批权限与幂等、SSE 收口保持不变。不要把这轮扩大为框架迁移、预算平台或新 UI 开发。
