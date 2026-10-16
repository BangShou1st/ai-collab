# D-RAG-01A 检索覆盖基线与 Agent limit 修复

- 任务：`D-RAG-01A — 检索覆盖基线与 Agent limit 修复`
- 分支：`feat/d-rag-01a-retrieval-baseline`（基线 `24eec09`，即 PR #11 的 squash merge）
- 维护入口：`docs/agent-runtime-maintenance.md`
- 范围：只做 A（Agent 检索数量边界）与 B（检索质量基线）两部分。
  未引入 BM25、外部 Reranker、新数据库或额外 LLM 调用；
  未修改流式 UI、`CitationValidator`、Agent 委派执行策略。

## 0. 基线核对

| 项 | 实测值 |
| --- | --- |
| 起点分支 | `feat/d-sched-01-zen`（`87d8a20`，**不在** `origin/main` 上） |
| 实际使用基线 | `24eec09`（`origin/main` = PR #11 squash merge） |
| 新分支 | `feat/d-rag-01a-retrieval-baseline` |
| 工作区无关未跟踪文件 | `.dsh-acl-recovery/`、`.freebuff/`（保持原样，未 `git add .`） |

未执行 `reset`/`clean`；未创建 PR（PR 由设计者在 GitHub 创建）；未开始 D-RAG-01B。

## A. Agent 检索数量边界

### 根因

`KnowledgeSearchAgentTool` 按 `limit`（1–12）向检索层要候选，但把候选交给
`KnowledgeContextBuilder.build(hits)` 时，构建器内部的 `MAX_SOURCES = 5` 是**写死的**。
于是 `limit=12` 时检索拿回 12 条候选，真正进入上下文与 citations 的仍只有 5 条：
工具 schema 承诺的上限与真实行为不一致，多出来的召回被无声丢弃。

### 修复

- `KnowledgeContextBuilder` 新增有界重载 `build(hits, maxSources)`；
  无参 `build(hits)` 保持 `DEFAULT_MAX_SOURCES = 5`，知识问答两个服务
  （`KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService`）**未改动**，
  行为与预算完全不变。
- `maxSources` 必须在 `1..MAX_SOURCES_LIMIT(12)`，越界抛 `IllegalArgumentException`。
- `KnowledgeSearchAgentTool.execute` 把同一个 `limit` 同时用于检索 topK 与上下文来源上限。
- **放宽的只是来源条数**：正文预算（8000 字符）、相似度下限（0.15）、
  内容哈希去重、引用排名、`metadata`/页码、`ProjectAccessGuard` 项目权限全部保持原逻辑。
  允许 12 个来源不会绕过文本预算。

### 测试（确定性单元测试，无数据库、无模型）

`KnowledgeContextBuilderTest`（10 例）与 `AgentReadToolsTest` 新增两例：

| 断言 | 结果 |
| --- | --- |
| 12 个短而不同的有效来源 + `maxSources=12` 全部返回，rank 1..12 | 通过 |
| `maxSources=3` 至多 3 个；`=1` 至多 1 个；`=8` 至多 8 个 | 通过 |
| 知识问答默认仍至多 5 个（无 `[S6]`） | 通过 |
| 12 × 3000 字符长正文时来源数 < 12，且 prompt 不超 8000 字符 | 通过 |
| 同 `contentHash` 只计一次，且不占用其他来源名额 | 通过 |
| 低于相似度下限 / 空白正文仍被拒；chunkId/documentId/页码保留 | 通过 |
| `maxSources` 越界（0、13）被拒 | 通过 |
| Agent `limit=12` 返回 12 个来源与 12 条 citations；`limit=3` 至多 3 个 | 通过 |
| Agent 缺省 `limit` 仍以 8 作为候选窗口 | 通过 |

**红灯证据**：把 Agent 侧改回 `build(hits)` 后重跑，
`AgentReadToolsTest.knowledgeSearchHonoursRequestedLimitUpToTwelve` 失败：
`Expected size: 12 but was: 5`（见 `baseline-red-agent-limit.log`）。

## B. 检索质量基线

### 度量定义（`RetrievalCoverage`，纯函数）

把一次检索分成两级观察，用同一份期望证据集合分别比对：

1. **向量候选**（`DocumentSearchService.search`，Top 8 / Top 20）
2. **上下文实际保留来源**（`KnowledgeContextBuilder.build`）

| 指标 | 定义 |
| --- | --- |
| `Retrieval Recall` | 期望证据出现在向量候选中的比例 |
| `Context Evidence Retention` | 期望证据最终留在上下文来源中的比例 |
| `Document Coverage` | 含期望证据的文档被覆盖的比例（候选级与上下文级各一份） |

归因把 Top 8 与 Top 20 两次观察放在一起，区分四种结论：

- `WINDOW_TRUNCATED`（Top 8 无、Top 20 有）→ **候选窗口太小**，不是数据库没召回；
- `DB_MISS`（Top 20 也没有）→ **数据库没召回**；
- `RECALLED_AT_TOP8` → 正常召回；
- `UNDEFINED_NO_EXPECTED_EVIDENCE` → **期望证据集合为空**。

### “期望证据为空”不计为检索失败

当用例本来就没有可召回的期望证据时，Recall / Retention 的分子分母同为 0，
比率是 **0/0 未定义**。它既不是满分，也**不是检索失败**，必须能与真正的 `0.0` 区分：

- `retrievalRecall` / `contextEvidenceRetention` 写 **`null`**（不是 `0`、也不是 `1`）；
- 新增 `retrievalStatus` 字段：`MEASURED` 或 `UNDEFINED_NO_EXPECTED_EVIDENCE`；
- 归因 `verdict` 返回 `UNDEFINED_NO_EXPECTED_EVIDENCE`；
- `aggregate` 汇总时未定义用例对分子分母**都不贡献**（不拉低指标），
  同时计入 `undefinedCaseCount` 显式暴露，而不是静默消失；全部用例都未定义时汇总比率同样为 `null`。

“无有效证据”用例同时验证了这一点与真正的失败可区分：语料里没有该问题的证据 →
期望证据集合为空 → 未定义；而“有期望证据但候选一条都没有”才是 `0.0` 的真失败。

再单独用 `droppedByContext` 指出**上下文丢弃**（候选有、上下文没保留）。
`documentCoverage` 按“该文档的**期望证据**是否存活”计，同一文档里被召回的噪声分块不能替它算覆盖。

### 测试分层（不混淆口径）

| 测试 | 性质 | 观察对象 |
| --- | --- | --- |
| `RetrievalCoverageTest`（10 例） | 纯单元，合成候选 | **度量口径本身**是否正确、归因是否分得开、0/0 是否不计为失败 |
| `RetrievalCoveragePostgresTest`（11 例） | Testcontainers + 真实 pgvector | 真实 schema/排序/指纹/代际/范围限定/上下文保留；输出路径守卫 |
| `RealRetrievalBaselineTest` | `AI_REAL_RETRIEVAL=true` 门控，真实 Ollama | 真实模型召回（本次**未运行**，默认跳过） |

**合成与真实必须分清**：`RetrievalCoveragePostgresTest` 的 pgvector 检索、指纹与代际过滤、
知识上下文构建都是**真实实现**，但嵌入向量由 `SyntheticEmbedder` 合成
（词元哈希桶向量，余弦相似度≈词元重合度）。因此它实测的是
**检索链路的机械正确性与覆盖损失的位置**，**不是**模型语义召回质量，
更**不是**模型回答质量。本批没有任何一次模型调用，也没有伪造回答质量数字。

### 覆盖用例与实测结果

合成项目 / 合成文档，覆盖任务要求的全部场景：

| 用例 | Recall | Context Retention | 归因 |
| --- | --- | --- | --- |
| 单事实 | 1.0 | 1.0 | RECALLED_AT_TOP8 |
| 跨文档多事实 | 1.0 | 1.0 | 文档覆盖 3/3 |
| 精确编号（REQ-7741） | 1.0 | 1.0 | 候选 rank 1 |
| 同内容不同文档 | 1.0 | 0.5 | **上下文去重丢弃 1 条**（contentHash 相同） |
| 无有效证据 | 不适用（0/0 未定义） | 不适用（0/0 未定义） | **UNDEFINED_NO_EXPECTED_EVIDENCE**：候选无一条达相似度下限，上下文为空；不判为检索失败 |
| 指定文档范围 | 1.0 | 1.0 | 范围外证据被排除；放开范围后可见（反向对照通过） |
| 索引代际隔离 | 1.0 | 1.0 | 退役代际 chunk 搜不到；NULL 代际也搜不到带代际的 chunk |
| 深排位 Top 8 vs Top 20 | 0.0 → 1.0 | — | **WINDOW_TRUNCATED**（窗口截断，非数据库缺失） |
| 知识问答上限（5） | 1.0 | 0.4167 | **droppedByContext = 7** |
| Agent 上限（12） | 1.0 | 1.0 | droppedByContext = 0 |
| 正文预算（12 × 3000 字符） | 1.0 | 0.25 | 只保留 3 条，prompt ≤ 8000 字符 |

### 两个直接可用的结论

1. **“允许 12 个来源”没有绕过正文预算**：预算用例中 12 条长正文只留下 3 条，
   prompt 仍在 8000 字符以内（`droppedByContext = 9`）。
2. **知识问答确实是本批 Agent 修复的旁观者**：同一批 12 条候选，
   知识问答保留 5 条（丢 7），Agent 保留 12 条（丢 0）。

### 诊断数据的产出位置与“快照”性质

仓库中已跟踪的 `deterministic-baseline.json` 是**一次验收样例（快照）**，
**不是**每次运行都会字节一致的产物，也不应被当作可重复生成的构建产物来比对：

- 它包含每次运行都会变化的随机 UUID（文档/分块 ID），因此**逐次运行字节必然不同**；
- 它记录的是**记录时点**的实测值，用于人工复审该次基线，而不是断言依据
  （断言全部写在测试代码里）。

测试**不再**覆盖这份已跟踪文件。每次运行的诊断 JSON 输出到 Maven 构建目录：

```
ai-collab-backend/target/retrieval-baseline/deterministic-baseline.json
```

该目录被 `ai-collab-backend/.gitignore` 的 `**/target/` 忽略，
不回写仓库、不污染工作区。诊断字段与指标口径与快照完全一致（未改动）。
`RetrievalCoveragePostgresTest` 中有一条回归守卫用例，
断言输出路径必须位于 `target/` 且不得指向仓库中那份快照。
测试结束时会在控制台打印 `RETRIEVAL_BASELINE_DIAGNOSTICS=<绝对路径>`，
便于直接从 CI 日志定位当次产物。

机器可读诊断字段（与快照同构）：每条期望证据的 `documentId`/`chunkId`、
候选 rank 与相似度、实际保留来源清单、以及 `retrievalStatus`。

## 测试结果

| 命令 | 结果 |
| --- | --- |
| 定向测试（A + B + 相关回归，8 个类 98 例） | Tests run 98, Failures 0, Errors 0, Skipped 0 |
| `mvnw verify`（完整） | **Tests run 1407, Failures 0, Errors 0, Skipped 11** |

定向测试实际执行的类：`KnowledgeContextBuilderTest` 10、`AgentReadToolsTest` 5、
`AgentToolContractTest` 9、`KnowledgeCitationValidatorTest` 8、`RetrievalCoverageTest` 10、
`RetrievalCoveragePostgresTest` 11、`AgentToolOutputProjectorTest` 5、
`AgentDelegationPostgresTest` 40。

另一处检索来源组装 `TaskPlanContextAssembler`（规划）**不使用** `KnowledgeContextBuilder`：
它有独立的内联循环与自己的上限（`planning.max-sources` 默认 12、`source-codepoint-budget`
默认 16000），**不在本批范围内**，本批未触碰。

改动后 `KnowledgeContextBuilder` 无参重载的全部生产调用方只有知识问答两个服务
（`KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService`），
二者行为与预算逐字不变。

11 个 skipped 全部是**既有的** opt-in 门控（`AI_REAL_RETRIEVAL` / `AI_UPGRADE_REHEARSAL` /
真实宿主验收 / Ollama 与 Zen 冒烟），非本批引入，也没有本批用例被静默跳过。
CI 容器用例守卫所需的 4 个类实际运行数：`AgentRunContextCommitPostgresTest` 10、
`AgentRunRetryPostgresIntegrationTest` 6、`AgentMigrationIntegrationTest` 6、
`NextStageMigrationPostgresTest` 1；新增的 `RetrievalCoveragePostgresTest` 11 例全部实跑，
并已加入 `.github/workflows/ci.yml` 的 `required` 列表，整类被跳过即判失败。

Maven 输出中的 `ConnectException: Connection refused` 来自
`storage.minio.endpoint=http://127.0.0.1:1`（测试专用不可达端点），是既有测试的预期噪声，
不影响结果。

## 未做的事（按任务边界）

- 未引入 BM25、外部 Reranker、新数据库、额外 LLM 调用；
- 未修改流式 UI、`CitationValidator`、Agent 委派执行策略；
- 未全局调高上限，知识问答仍是 5 个来源 / 8000 字符；
- 未伪造模型真实回答质量：本批无模型调用，合成基线与真实模型基线明确分开；
- 未创建 PR：本地提交后由设计者在 GitHub 创建 PR；
- 未开始 D-RAG-01B。

## 本轮收口变更（提交前）

复核“期望证据为空”场景时发现：度量本身已对 0/0 返回 `null`，
但 Postgres 的“无有效证据”用例**凭空造了一条随机 UUID 当期望证据**，
于是 `expectedEvidenceCount = 1`、Recall 被记成 `0.0`——把**未定义**误报成了**检索失败**。
仅修正度量、测试与本文档，未扩大算法修改范围：

- `RetrievalCoverage`：新增 `retrievalStatus`（`MEASURED` / `UNDEFINED_NO_EXPECTED_EVIDENCE`），
  归因 `verdict` 同步；`aggregate` 让未定义用例不贡献分子分母并计入 `undefinedCaseCount`；
- `RetrievalCoverageTest`：把 `emptyExpectationReportsNullRatioInsteadOfZero` 升级为
  未定义语义断言，并新增“未定义 vs 真 0.0 可区分”“未定义不拉低汇总”两个用例（8 → 10 例）；
- `RetrievalCoveragePostgresTest`：“无有效证据”改用**空期望证据集合**，
  断言未定义而非失败（该轮仍为 10 例；本轮 PR 审查修复后为 11 例）；
- 本 README：更新归因说明、用例表与测试计数。

A 部分（Agent limit 修复）与其余基线算法逻辑**未改动**。

## PR #12 审查修复：证据输出不再改写已跟踪文件

审查指出 `RetrievalCoveragePostgresTest` 每次运行都会覆盖仓库中已跟踪的
`deterministic-baseline.json`。实测确认：运行前后该文件 SHA256 由
`2E57584B…` 变为 `A47CE355…`，`git status` 出现 `M`——确实会反复污染工作区。

修复（**只改证据输出行为**，诊断字段与指标口径不变）：

- 输出路径改为 `target/retrieval-baseline/deterministic-baseline.json`（被 `.gitignore` 忽略）；
- 新增回归守卫用例 `evidenceOutputGoesToTargetAndNeverRewritesTheTrackedSnapshot`，
  断言输出必须位于 `target/`、不得等于已跟踪快照路径、不得位于 `docs/` 下；
- 仓库中那份 JSON 保留为**一次验收样例**，README 已注明它是快照、
  含每次运行都变化的随机 UUID、**不是**逐次字节一致的产物；
- `@AfterAll` 结束打印 `RETRIEVAL_BASELINE_DIAGNOSTICS=<绝对路径>`，便于 CI 日志定位。

未改动检索算法、Agent limit、上下文预算或任何现有评价口径。
`RetrievalCoverageTest`（10 例）与 `RetrievalCoverage` 度量本身**未触碰**。

## PR #12 CI 偶发失败修复：调度器测试的完成信号时序

GitHub CI 偶发失败于
`AgentRuntimeJobSchedulerTest.releasingOneSlotAllowsNextTickToClaimAgain`（原第 188 行）。
该失败与本批 RAG 改动无关，是既有测试的时序假设问题，但位于本分支上，故一并修复。

**根因**：`RecordingExecutor` 在包裹的 Runnable 内部、`delegate.execute(...)` 返回<b>之前</b>
于 `finally` 中执行 `completions.countDown()`。这个信号发生在**池线程内部**：
此刻该线程尚未回到零容量（`queueCapacity=0`）队列上重新阻塞，因此
“完成信号已发出”只代表**任务体结束**，不代表**线程池已能接收下一个任务**。
紧随完成信号的那一次 tick 会被 `AbortPolicy` **正确拒绝**，于是第三个任务领取超时。

**修复（只改测试同步方式）**：

- 新增有界等待助手 `awaitNextDispatch(...)`：可靠条件是
  `RecordingExecutor.dispatchedCount()` **增长**（该计数只在任务真正被线程池接收时递增，
  被拒绝的 tick 抛 `TaskRejectedException` 故不计入）。一旦增长**立即停止**，不再多发 tick；
  上界由墙钟 `TIMEOUT_SECONDS` 约束，循环体仅 `Thread.yield()`，**不使用 `Thread.sleep`**，
  也**未放大超时**；
- 断言改为“槽位释放后，有限次数的周期 tick 最终能再次派发”，
  而不是“完成信号后的第一次 tick 必须成功”；
- `workerFailureDoesNotLeakAnExecutionSlot` 存在**同一类竞争窗口**（失败 Runnable 的完成信号
  同样在池线程内），已做同样的最小修复。

断言强度**未削弱**：新增 `dispatchedCount()` 精确断言（2 → 3、1 → 2），
并保留原有的 `thirdClaimed` / `secondEntered` / `times(n).claim(...)` 校验。
若槽位真的泄漏，`awaitNextDispatch` 会在有界时间内返回 `false` 使用例失败。

**验证方式**：先用一次性探针量化该窗口——本机 300 次 + 200 次 spin 复现均为 0 次拒绝
（本机过快、窗口极小；CI 较慢且受争用，窗口被放大），
故改用**确定性替身**验证：照搬用例结构并注入 N 次 `TaskRejectedException`，
结果 1/3/25/199 次注入均能恢复并领取第三个任务，
而注入 1000 次（远超有界上界）则**如期失败**——证明该重试确实有界，
不会掩盖真实的槽位泄漏。探针为一次性文件，验证后已删除，未进入提交。

未修改生产调度器（`AgentRuntimeJob`/`AgentRunExecutionConfiguration`）、
并发上限、数据库领取策略、RAG 代码或 CI 守卫。
