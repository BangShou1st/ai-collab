# D-RAG-01C / AGENT-QUALITY-01 第二轮 — 质量测准与真实改进联合验收（2026-10-19）

- 仓库：BangShou1st/ai-collab
- 唯一分支：`dev`（无附加 Worktree）
- 上轮交付 SHA：`425b7c85d4dc4e08e88bad2a962c7bb4f908de10`
- 本轮起始 SHA：`425b7c8`（dev）
- 交付性质：**修正评估口径 + 建立双层回归机制 + 真实模型完整 Agent-loop 验收 + SOURCE_CAP 策略对照**
- 生产代码：本轮**未改动生产算法**（见第 6 节：策略对照存在取舍，按任务规则保留现状并提交量化报告）
- 诊断产物只写 Maven `target/retrieval-baseline/2026-10-19/`，**不覆盖** 2026-10-18 旧证据

---

## 1. 修正后的检索范围统计（阶段 A）

### 1.1 问题（上轮口径缺陷）

`AgentRetrievalQualityPostgresTest` 的 15 个用例中只有第 9 个设置了 scope。其余用例的
`scoped` 搜索与「全项目」搜索实际传入的是**同一条件（scope=null）**，两个汇总指标看似独立，
实则是**同一批数据的重复测量**。因此上轮“指定范围与全项目质量相同”的结论是**口径假象**，不成立
（已在 `docs/acceptance-evidence/2026-10-18/.../README.md` 第 4 节加注修正）。

### 1.2 修正

- 把 `ALL_PROJECT`（用户未指定文档）与 `DOCUMENT_SCOPED`（用户问题**明确点名**某文档）拆成**两个独立维度**。
- 只有问题本身点名文档时才传 `documentIds`；**不把参考答案里的文档 ID 反填成“用户范围”**。
- 新增 **5 个**业务化指定文档用例（《发布回滚预案》回滚条件、《安全与脱敏规范》只读下载权限、
  《容量与性能约束》性能指标、《任务预算规则》运行期额度、《合同模板甲》审批主体），并把原第 9 例
  问题改写为明确点名《批量导出规范》。
- 指定范围问题**额外跑一次全项目检索作对照**，对照结果单独归入 `scopedAllProjectReference`，
  **不并入任一样本主口径**。

### 1.3 修正后各范围指标（真实 `qwen3-embedding:0.6b`，1024 维，fingerprint `20f29e8d…`）

| 维度 | 样本数 | 期望证据数 | Recall@8 | Recall@20 | Retention 旧入口@8 | Retention 新入口@20 | Document Coverage(保留) | sourceCapDrop@20 | DB miss@20 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ALL_PROJECT | 20 | 28 | 0.9643 | 0.9643 | 0.8571 | 0.8929 | 0.9583 (23/24) | 2 | 1 |
| DOCUMENT_SCOPED | 6 | 6 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 (6/6) | 0 | 0 |
| scopedAllProjectReference（对照，不计入主口径） | 6 | 6 | 1.0 | 1.0 | 0.8333 | 1.0 | 1.0 | 0 | 0 |

**结论**：修正口径后，两个范围维度**确实不同**——指定范围（Recall@20 = 1.0）优于全项目
（0.9643 / Retention 0.8929）。这证明上轮“两者相同”的结论来自重复测量，而非真实等价。

逐用例失败/偏低归因（ALL_PROJECT）：
1. **#7 长分块尾部条件**：`DB_MISS`（Top8 与 Top20 都是 0，`droppedByContext=0`）——长分块由重复填充文本稀释，语义信号密度低，非窗口也非上下文丢弃。
2. **#2 容量权限性能约束、#15 多源综合**：`SOURCE_CAP`×2——证据已在候选（#2 容量规划 rank 8；#15 上线门禁 rank 6），但 12 个来源名额被占用（见第 5 节量化的选择策略代价）。
3. **#3/#13 旧入口塌缩 0.5→1.0**：跨文档入口已消除全局 contentHash 合并，真实 Embedding 下确认修复有效。
4. **#11 不存在的事实**：`UNDEFINED_NO_EXPECTED_EVIDENCE`（无期望证据），未伪装成召回失败、未补造来源。

---

## 2. 真实 Embedding 质量回归门槛（阶段 B2）

修正口径后的**新版本化基线**（`AI_REAL_OLLAMA_RETRIEVAL=true`，本地 Ollama，模型 `qwen3-embedding:0.6b`）：

| 维度 | Recall@20 基线 | 门槛 | Retention@20 基线 | 门槛 | Coverage 基线 | 门槛 |
| --- | --- | --- | --- | --- | --- | --- |
| ALL_PROJECT | 0.9643 (27/28) | **≥ 0.90** | 0.8929 (25/28) | **≥ 0.80** | 0.9583 | **≥ 0.85** |
| DOCUMENT_SCOPED | 1.0 (6/6) | **≥ 0.95** | 1.0 (6/6) | **≥ 0.90** | 1.0 | **≥ 0.90** |

- 门槛 = 基线向下留出**退化容忍度**（ALL_PROJECT 容忍约 6.4 / 9.3 个百分点），**不是**把旧的
  22/23、20/23 当作所有新范围的无条件断言。
- 门槛在测试中为**版本化常量**（`ALL_PROJECT_*_FLOOR` / `DOCUMENT_SCOPED_*_FLOOR`），并断言
  `sampleCount` 等于该范围用例数，防止把重复测量当成两组独立实验。
- **门控与跳过**：整类由 `AI_REAL_OLLAMA_RETRIEVAL=true` 门控——未启用时整类跳过（不启动容器、不访问 Ollama），
  普通 CI 不会因缺少本地模型而失败；**环境不可用即跳过**，不伪装通过。

### 2.1 普通 CI 确定性硬门槛（阶段 B1）

新增 `RetrievalQualityHardGatePostgresTest`（真实 PostgreSQL + pgvector + `SyntheticEmbedder`，**不门控**，
已在 `ci.yml` 的“容器用例必须真跑”守卫名单中，整类被跳过即判失败）。钉住 7 条硬门槛：

1. 来源 `documentId`/`chunkId` 身份一致；
2. 指定文档范围不越界（含反向对照）；
3. 索引指纹/代际**双向**隔离；
4. 丢弃原因合计恒等于 `droppedByContext`（且夹具必须真产生丢弃）；
5. 上下文正文预算 ≤ 8000 code point 且来源数 ≤ 12；
6. 跨文档相同正文保留**不同来源身份**；
7. 默认知识问答 `build()` 入口不回归（仍为 5 来源、预算不超限）。

> 其余第 8 条“工具失败不得当成功证据”由 `AgentResearchQualityPostgresTest#failedReadToolIsNotTreatedAsSuccessfulEvidence`
> （真实 PostgreSQL + 真实工具，**非门控**，已加入 CI 守卫名单）钉住。

---

## 3. 直接研究与子 Agent 委派的同题对照（阶段 C1）

`AgentResearchQualityPostgresTest` 新增 `directResearchVersusDelegatedResearchOnTheSameQuestionAndBudget`：
**同一问题、同一批文档、同一组输入事实、同一总预算（12 步 / 8 工具 / 2 子运行）**，只差“是否把研究动作委派给只读子 Agent”。
脚本化模型轮次，度量的是**机制**（事实覆盖、来源支持、错误引用、无依据断言、父子回收、预算/耗时），不是真实模型回答质量。

### 3.1 对照结果（`target/.../agent-research-quality-paired.json`）

| 场景 | 模式 | 状态 | 事实覆盖 | 无依据断言 | 来源引用(支持/总) | 错误引用 | 子运行 | 回收完整 | 模型调用 | 工具调用 | 步 | 输入 token | 耗时 ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| S1 上线与回退综合 | 直接 | SUCCEEDED | 2/2 | 0 | 2/2 | 0 | 0 | — | 2 | 1 | 3 | 1100 | 1109 |
| S1 上线与回退综合 | 委派 | SUCCEEDED | 2/2 | 0 | 2/2 | 0 | 1 | 是 | 4 | 1 | 3 | 2426 | 2108 |
| S2 相同条款来源身份 | 直接 | SUCCEEDED | 1/1 | 0 | 2/2 | 0 | 0 | — | 2 | 1 | 3 | 1327 | 803 |
| S2 相同条款来源身份 | 委派 | SUCCEEDED | 1/1 | 0 | 2/2 | 0 | 1 | 是 | 4 | 1 | 3 | 2442 | 1691 |
| S3 指定文档范围 | 直接 | SUCCEEDED | 1/1 | 0 | 1/1 | 0 | 0 | — | 2 | 1 | 3 | 1342 | 828 |
| S3 指定文档范围 | 委派 | SUCCEEDED | 1/1 | 0 | 1/1 | 0 | 1 | 是 | 4 | 1 | 3 | 2457 | 1489 |
| S4 资料不足拒绝编造 | 直接/委派 | SUCCEEDED | 0/0 | 0 | 0/0 | 0 | 0/1 | 是 | 2/4 | 1 | 3 | — | — |
| S5 工具失败诚实降级 | 直接/委派 | SUCCEEDED | 0/0 | 0 | 0/0 | 0 | 0/1 | 是 | 2/4 | 1 | 3 | — | — |

**结论**：同题下两种模式**事实覆盖、来源支持、无依据断言、错误引用完全相同**；差异集中在**成本**——
委派**固定多 2 次模型调用、多约 1.1k 输入 token、耗时约翻倍**（父子各一轮）。委派的价值在于可回收的
**子研究结构化覆盖事实**（`DELEGATION_COMPLETED.coverage.gaps`，本基线 S1/S2/S3/S4 各 1 条、S5 2 条），
而非更高的最终事实覆盖。**不追求委派调用率越高越好**。

---

## 4. 真实 Chat Agent-loop 逐用例结果（阶段 C2）

`RealAgentLoopPostgresTest`（`AI_REAL_AGENT_LOOP=true` 门控，Opt-in）：用**生产 Agent 模型路由**
（`RoutingAgentModelExecutor` + `NativeToolCallingExecutor`）穿过**真实 Kilo 免费模型**，驱动完整循环：
**用户问题 → 模型决策 → 真实工具调用 → 证据回收 → 最终综合 → 持久化引用**。

- 供应商：`KILO_GATEWAY_FREE`（匿名、free-only）。实际模型：`nvidia/nemotron-3-super-120b-a12b:free`。
- 真实工具调用协议已**先行确认支持**：真实 `POST /chat/completions` + tools 返回 `finish_reason=tool_calls` 与真实 `tool_calls`。
- 有界执行：每场景 6 步 / 4 工具 / 1 子运行；模型调用上限 12；墙钟上限 180s。
- 未绕过 429、未换 IP/账号/身份、未用付费模型；不使用写工具；隔离合成资料与数据库；不输出凭证。

### 4.1 逐用例结果（`target/.../real-agent-loop.json`）

| 场景 | 状态 | 模型调用 | 真实工具调用 | 持久化引用 | 结果 |
| --- | --- | --- | --- | --- | --- |
| R1 完整循环-搜索并综合 | SUCCEEDED | 2 | `search_project_knowledge` × 1（SUCCEEDED） | 真实 chunkId × 2 | 正确综合两条事实（自动化验收+阻断缺陷关闭；连续两次不达标→暂停回滚），引用真实来源 |
| R2 指定文档范围 | SUCCEEDED | 2 | 同上（SUCCEEDED） | 真实 chunkId × 2 | 正确回答《批量导出规范》保留二十四小时；引用均为真实分块 |
| R3 资料不足拒绝编造 | SUCCEEDED | 2 | 同上（SUCCEEDED） | 无 | 如实“资料不足、无法给出具体年限”，**未编造** |

- **诚实标注**：R2 中模型未强制传入 `documentIds` 范围，工具按全项目返回，引用集合含 2 个真实分块；
  本用例断言的是“引用必须来自真实种子文档/分块（不得编造）”，**未**把“模型是否主动限定范围”当作断言。
- **检索后端**：为可复现与有界成本，`DocumentSearchService` 用确定性关键词解析替代向量检索；
  Agent 循环、工具调用、证据回收、引用持久化均为真实实现。
- 本轮**未出现** 429/超时；如出现，将如实标记 `BLOCKED_EXTERNAL` 且不伪造工具调用成功（本轮 3 场景均未触发）。

---

## 5. SOURCE_CAP 选择策略对照实验（阶段 D）

**实验策略仅在测试源码**（`SourceCapStrategyExperiment`），**不进入生产路径**；A（生产两阶段覆盖优先）与
B（相关度保护：相对全局第 N 名门槛 + 为全局竞争预留名额）在**相同候选、相同 source limit、相同正文预算**下对照。

### 5.1 确定性固定反例（`sourcecap-strategy-experiment.json`）

| 用例 | A 期望证据保留 | B 期望证据保留 | Δ | A 文档覆盖 | B 文档覆盖 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| E1 弱代表挤占第二分块（13 文档 ≥ 12 名额） | 0 | 1 | **+1** | 1 | 1 | B 能保留被弱代表挤掉的更相关第二分块 |
| E2 单文档 | 1 | 1 | 0 | 1 | 1 | 无差异 |
| E3 跨文档相同正文 | 2 | 2 | 0 | 2 | 2 | 均保留两身份，不合并 |
| E4 同文档互补分块 | 2 | 2 | 0 | 1 | 1 | 无差异 |
| E5 弱相关干扰文档 | 1 | 1 | 0 | 1 | 1 | 无差异 |
| **E6 次文档证据不可被高分文档挤掉（limit=3）** | **1** | **0** | **−1** | **1** | **0** | **B 丢失 D-RAG-01B 的覆盖保证（重要退化）** |

### 5.2 真实 Embedding 样本对照（同一批真实候选）

| 维度 | A 期望证据保留 | B 期望证据保留 | Δ | A 文档覆盖 | B 文档覆盖 | A 意外来源 | B 意外来源 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ALL_PROJECT | 19/22 (0.8636) | 21/22 (0.9545) | **+2** | 17 | 17 | 149 | 147 |
| DOCUMENT_SCOPED | 6/6 | 6/6 | 0 | 6 | 6 | 12 | 12 |

**取舍**：B 在本语料可多保留 2 条期望证据且不损文档覆盖，但在 **E6（D-RAG-01B 的核心覆盖保证）** 上会退化。
按任务规则“**有取舍则保留现状并提交量化报告**”，**本轮不改动生产默认算法**，
策略选择留待 ChatGPT 审核（见第 7 节剩余风险）。

---

## 6. 实际实施的最小生产修复

**本轮未改动生产代码。** 依据“先用真实问题证明哪里质量不足，再针对性改进；不引入无回归覆盖的改动”：

| 项 | 判定 | 依据 |
| --- | --- | --- |
| 范围指标口径缺陷 | **修复在测试侧**（非生产） | 重构 `AgentRetrievalQualityPostgresTest` 的范围维度与汇总口径，新增 5 个指定文档用例 |
| 检索质量回归缺机制 | **修复在测试侧 + CI 守卫** | 新增 B1 硬门槛类、B2 版本化门槛，并把新硬门槛类加入 CI 非跳过守卫 |
| #2/#15 `SOURCE_CAP` 损失 | **保留现状（取舍）** | 策略 B 在真实样本 +2，但在 D-RAG-01B 覆盖保证（E6）−1；有取舍，按规则不改生产 |
| #7 长分块 DB miss | 语料构造特征 | 低密度长分块被稀释；非生产缺陷 |
| 旧入口跨文档塌缩 | 已修复（D-RAG-01B） | 真实 Embedding 下 0.5→1.0 复现确认 |

---

## 7. 回归测试、外部模型限制与剩余风险

### 7.1 回归结果

- 目标测试类（本地实跑，Docker 可用）：

  | 测试类 | 结果 |
  | --- | --- |
  | `CrossDocumentSourceSelectorTest` | Tests run: 11, Failures: 0 |
  | `KnowledgeContextBuilderTest` | Tests run: 18, Failures: 0 |
  | `RetrievalCoverageTest` | Tests run: 19, Failures: 0 |
  | `RetrievalCoveragePostgresTest` | Tests run: 16, Failures: 0, Skipped: 1（仅门控方法） |
  | `RetrievalQualityHardGatePostgresTest`（新，非门控） | Tests run: 7, Failures: 0 |
  | `CrossDocumentSourceSelectorStrategyExperimentTest`（新） | Tests run: 1, Failures: 0 |
  | `AgentResearchQualityPostgresTest`（含同题对照） | Tests run: 7, Failures: 0 |
  | `AgentRetrievalQualityPostgresTest`（Ollama） | Tests run: 3, Failures: 0 |
  | `RealAgentLoopPostgresTest`（Kilo，Opt-in） | Tests run: 3, Failures: 0 |

- 全量后端 `mvn clean verify`、前端 CI、GitHub CI 结果见第 8 节。

### 7.2 外部模型限制

- Kilo 免费模型可用性与价格随时变化；本轮实际模型为 `nvidia/nemotron-3-super-120b-a12b:free`（匿名、cost 0）。
- `poolside/laguna-s-2.1:free`（库内缺省模型）在探测时返回 **429**；本轮**不绕过 429、不换模型规避**，
  改用另一可用免费模型，并如实记录。
- 真实模型回答**有随机性**：本轮仅 3 个代表人场景，不构成统计意义上的质量保证。

### 7.3 剩余风险与下一轮建议

1. **SOURCE_CAP 策略选择**：A/B 各有取舍，量化报告已给出，待审核决定是否引入“相关度保护 + 覆盖保证”折中（如仅对**无代表文档**名额做保护）。
2. **真实语料规模小**：20 用例 / 13 文档 / 38 分块，召回率不代表生产全量语义质量。
3. **真实 Agent-loop 样本有限**：3 场景、单模型，建议下一轮扩展到委派/多轮追问的真实模型场景。
4. **长分块信息密度**：属分块/摘要侧问题，超出本轮范围。
5. **相似度阈值 0.15 偏低**：无答案问题仍返回无关来源，依赖模型拒答；阈值调优属另一轮。

---

## 8. 回归与 CI（待第 8 节回填实跑结果）

见最终交付报告。本地全量 `clean verify`、前端 `pnpm lint/typecheck/test/build`、GitHub CI（Backend/Frontend）结果将一并给出。

## 9. 可复查产物

- 真实运行命令：
  - `AI_REAL_OLLAMA_RETRIEVAL=true` + `AgentRetrievalQualityPostgresTest`；
  - `AI_REAL_AGENT_LOOP=true` + `RealAgentLoopPostgresTest`（Opt-in，需本地能访问 `ai-gateway.kilo.ai`）；
  - 直接运行 `AgentResearchQualityPostgresTest`、`RetrievalQualityHardGatePostgresTest`。
- 诊断文件（`ai-collab-backend/.gitignore` 已忽略，**不写回仓库快照**）：
  - `target/retrieval-baseline/2026-10-19/real-retrieval-quality-scoped.json`
  - `target/retrieval-baseline/2026-10-19/agent-research-quality-paired.json`
  - `target/retrieval-baseline/2026-10-19/sourcecap-strategy-experiment.json`
  - `target/retrieval-baseline/2026-10-19/real-agent-loop.json`
- 仓库既有快照（`2026-10-16/d-rag-01a`、`2026-10-17/d-rag-01b`、`2026-10-18/agent-retrieval-quality`）**未被覆盖**；
  旧结论以加注方式保留，不静默改写。
