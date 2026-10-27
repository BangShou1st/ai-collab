# P10-REAL-GENERATION-03 评测计划（冻结于首次真实请求前）

> **批次：** `p10-real-generation-03`　**冻结日期：** 2026-10-27
> **基线：** `dev@ff834775a5faf235277b9df81c4cffef5a850eff`（工作区干净，origin/dev 一致，origin/main@9b4f7c5 未变）
> **PR #18：** OPEN / Draft / 未合并，CI 全绿（run 40388649131 / 40388641057）
> **授权：** 用户在任务书中明确授权本轮多轮真实调用（QA、Agent、辅助裁判、关键重复测量），不再沿用 P10-02 的 8 次硬上限；仍禁止收费模型、禁止改变生产默认路由、禁止无限重试与无界并发。

## 1. 与历史批次的关系

| 批次 | 状态 | 本轮使用方式 |
| --- | --- | --- |
| P10-REAL-GENERATION-01 | 已关闭（STOPPED_ON_REQUEST_FAILURE） | 只读引用，不复跑 |
| P10-REAL-GENERATION-02 | 已冻结（8 题 QA，MEASUREMENT_EXECUTED / PASS_WITH_SCORER_LIMITATIONS） | **回归基线**：8 题在本轮全量重测为复测（REGRESSION），保留原始证据不改写 |
| P10-SCORER-ROBUSTNESS-06 | 已冻结（评分器 v6） | **测量工具**：固定版本与源哈希，对 true 新输出测量，不在测量中途改规则 |

历史 holdout（P9）已可见，本轮所有 P10 冻结题执行均标记为**回归/复测**，不宣称盲测。

## 2. 冻结的执行入口（生产路径）

- **QA**：`KnowledgeQuestionApplicationService.ask` → 真实向量检索（Ollama `qwen3-embedding:0.6b` + pgvector）→ `KnowledgeContextBuilder.buildBalanced` → `RoutingChatModelGateway.complete`（KNOWLEDGE_CHAT purpose）→ `KnowledgeCitationValidator` → 落库 `knowledge_message` / `knowledge_citation`。
- **Agent**：`AgentRepository.createRun` → 真实 `AgentRecoveryJob.claim` 驱动 → `AgentRuntimeCoordinator.advance` → `RoutingAgentModelExecutor`（AGENT purpose 未显式分配 → 生产默认路由）→ `NativeToolCallingExecutor` → 真实工具（`search_project_knowledge` / `get_document_outline` / `read_document_section` 等 SAFE_READ 工具）→ 真实 `DocumentSearchService` 向量检索与来源身份持久化。
- **路由事实（实测）**：`p3u04_admin` KNOWLEDGE_CHAT → GEMINI `gemini-3.5-flash-lite`（路由 SHA-256 `df17eb978e42ecb533a8f1f7a6ff756c6e1521abe6308f9671dca06e9123cbbc`，与 P10-02 记录逐字节一致，本轮复算确认）；AGENT → 未分配 purpose，生产默认 Provider = Kilo Gateway Free / `nvidia/nemotron-3.5-lightning:free`（`encrypted_api_key` 为 NULL 的匿名免费网关）。
- **不改变**任何 Provider、模型路由、Prompt、检索参数、审批、SAFE_READ、租约与隔离门禁。

## 3. 评测矩阵（冻结）

### 3.1 QA 真实生成（17 次 Chat 请求）

| 组 | 题目 | 性质 |
| --- | --- | --- |
| Q01–Q14 | AQ01–AQ14 全量（P10 冻结 14 题） | Q01/Q02/Q04/Q06/Q07/Q11/Q12/Q14 = P10-02 同题**复测（REGRESSION）**；Q03/Q05/Q08/Q09/Q10/Q13 = **首次真实执行** |
| R1–R3 | AQ02-REPEALED、AQ04-REGION-THRESHOLD、AQ12-ABSTAIN-INDIRECT 各重复 1 次 | 高风险题重复测量（版本废止 / 跨地区阈值 / 拒答+编造边界），观察输出稳定性 |

执行规则：每题最多 1 次 Chat，相邻请求间隔 ≥15 s，输出封顶 1024 tokens，`AiRequestOutputCap(1024)`；任何认证错误、额度耗尽或持续性故障停止该路由并保留诊断；429 遵守服务端限流；瞬时 5xx/超时按生产行为（`maxAttempts=1` 不重试）如实记账，不伪装成功。**总预算 17 次请求，硬上限。**

### 3.2 Agent 真实执行（20 个逻辑场景，AG01–AG20）

种子语料：P9 冻结 23 篇文档（SHA-256 校验后真实入库 + 真实向量索引）。每场景有界：模型调用 ≤12、墙钟 ≤180 s、步数/工具/子运行上限由生产 `AgentRuntimeLimits` 决定（测试侧沿用 D-RAG-01C 的 `max_steps=6 / max_tool_calls=4 / max_children=1` 有界夹具）。场景清单与判定标准冻结于 `question-manifest.json`，覆盖：

- SAFE_READ 只读检索（AG01–AG04）：单来源、多文档互补、同文档互补、跨三文档复合
- 版本/地区/条件/无答案边界（AG05–AG13）：现行版本、废止版本、华东/华南阈值与频率、否定条件、超时语义、无答案拒答、未定事项诚实、注入防御
- 范围隔离与工具纪律（AG14–AG16）：硬来源范围不泄漏、工具参数正确性、空结果恢复
- 证据回流与引用（AG17–AG19）：同文档互补回流、目录/正文续读工具、拒答不夹带编造数字
- 写入禁令（AG20）：要求写任务/里程碑 → 无写路径，不得产生业务写入

每个场景记录：完整调用链、工具请求与响应、来源身份（documentId/chunkId 与数据库核对）、上下文回流、最终回答、持久化引用与 `agent_step` 证据。未执行场景标 `NOT_EXECUTED`，外部阻断标 `BLOCKED_EXTERNAL`。

**明确边界（继承 D-RAG-01C 既有验收架构）**：Agent 驱动循环使用生产持久化引擎与生产工具，测试侧固定生产默认路由的 Provider 身份；`AgentEventService`/`AgentApprovalService` 以测试替身隔离 SSE 与审批副作用（不放宽任何门禁——审批工具在本轮场景中不应被触发，触发即为违规信号）。`ProjectAccessGuard` 用真实 `DatabaseProjectAccessGuard`（隔离库内 OWNER 成员），不 mock 权限。

### 3.3 多模型辅助裁判与盲评（2 个独立批次）

| 批次 | Provider / 模型 | 说明 |
| --- | --- | --- |
| Judge-A | Gemini API（本地 key 文件直连，`gemini-3.5-flash` 优先、不可用则 `gemini-3.5-flash-lite`） | 与被评 QA 模型同 Provider；模型名不同（flash ≠ flash-lite）仍存在同厂相关性，如实标注 |
| Judge-B | Kilo Gateway 免费模型（与 Agent 被评模型不同型号，如 `nvidia/nemotron-3-super-120b-a12b:free`；不可用则记录并改用可用免费型号） | 与 Judge-A 不同 Provider |

盲评输入：题目 + 冻结来源原文（相关文档全文）+ 回答正文；不透露主评分器判定、不透露另一裁判意见。要求逐关键事实给出：直接证据/反证/无法判定 + 理由；明确区分语义支持、来源身份有效、文字相似度。结构化输出：factCoverage、versionJudgment、regionJudgment、conditionFidelity、abstention、unsupportedClaims、injectionCompliance、citationSupport、uncertainty。覆盖：QA 17 份回答 + Agent 20 份回答（每裁判最多 37 次请求；预算 74 次，硬上限）。

**模型裁判只是辅助证据（MODEL_ASSISTED_REVIEW），不替代独立人工复核（HUMAN_REVIEW）。**

### 3.4 评分器 v6 复核

- 版本冻结：`2.1-scoped-contextual-v6`，四个源文件（`P10AnswerQualityScorer.java`、`P10ContextualAssertionJudge.java`、`P10TextNormalizer.java`、`P10AbstentionDetector.java`）SHA-256 记录于 `scorer-results.json` 与 `manifest.sha256`。
- 测量：QA 17 份（QA 协议）+ Agent 20 份（AGENT 协议）真实回答。
- 统计：`FORBIDDEN_AFFIRMATION`、`VERSION_VIOLATION`、`CONFLICTING_FABRICATION`、`ABSTENTION_MISSING`、`INJECTION_COMPLIANCE`、`AMBIGUOUS_NEEDS_REVIEW`、引用身份/语义支持、数字候选误报/漏报、Markdown/HTML/删除线/分句误判。
- 每项高严重度信号回看原始回答、实际上下文与规则，区分：模型错误 / 评分器假阳性 / 评分器假阴性 / 证据缺口不可归责 / 需人工裁决 / 证据不足。
- 测量期间不修改评分器规则；确证的新缺陷只登记 + 复现 + 建议后续专项。

## 4. 调用预算汇总（硬上限）

| 类别 | 预算 |
| --- | --- |
| QA Chat（Gemini） | 17 |
| Agent 场景（Kilo 免费模型，模型轮次） | ≤ 20 × 12 = 240（预期每场景 2–5 次，预期总量 ≤ 100） |
| 裁判请求 | ≤ 74 |
| 外部 Embedding | 0（本地 Ollama） |
| 生产路由改动 | 0 |

## 5. 对比与归因计划

1. P10-02 的 8 题与本轮同题复测对比（逐题机械信号、引用、Token、延迟）。
2. P10-02 完整 14 题基线（8 实测 + 6 未执行）与本轮完整 14 题。
3. QA（Gemini）与 Agent（Kilo）路径：注意**两者被评模型不同**（生产真实路由使然），差异结论必须标注模型变量，不得归因于"QA vs Agent 机制差异"单变量。
4. 评分器机械结论 vs 模型辅助裁判（一致/分歧/无法裁定）。
5. 重复测量输出稳定性（R1–R3 与原题）。

所有比率标注分母与执行范围；题量不足以做总体泛化或统计显著性结论。

## 6. 证据与安全

- 新结果只写本目录与 `target/p10-real-generation-03*/`；历史证据目录只读。
- 密钥（key2.txt、MODEL_CONFIG_MASTER_KEY、provider 密钥）只在进程内存使用，不进入任何 Git 追踪文件、日志或报告；账本不记录 URL 查询串与认证头。
- 每次外部尝试记账（含失败与重试）；失败不伪装成功；费用无计费回执记 `UNAVAILABLE`。
- 默认 CI 零真实外部模型调用：所有真实路径由环境变量门控（`P10_LOCAL_PREFLIGHT` / `P10_REAL_GENERATION_03` / `P10_REAL_AGENT_03`），默认整类跳过。
