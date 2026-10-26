# P10-REAL-GENERATION-02：真实模型端到端验收任务书

## 一、任务目标

你正在 AI-Collab 项目中继续 P10-REAL-GENERATION-01。

上一批次（`P10-REAL-GENERATION-01`）已关闭：真实客户端传输尝试 1 次，20,912 ms 后 `AI_MODEL_TIMEOUT`，
其余 7 题按"失败即停"规则 `NOT_RUN`，没有取得任何模型回答，模型质量 `NOT_ASSESSABLE_NO_MODEL_RESPONSE`。
该批次不得重跑、不得覆盖，其授权、账本、原始失败回执、manifest 与历史报告必须原样保留。

本轮目标：在 `P10-NETWORK-PROXY-AUTO-01` 已修复出站代理选路之后，**首次**取得真实模型回答，
对生产知识问答链路建立可复核的回答质量基线。

任务 ID：`P10-REAL-GENERATION-02`

预期结果：
- 真实生成：`REAL_GENERATION_EXECUTED`，仅在真实请求确实执行且取得模型响应后填写。
- 评测执行裁决与模型回答质量裁决分开记录。
- 不因测试框架通过就宣称模型回答质量通过。
- 若真实请求未获授权或受环境阻断，明确记录 `REAL_GENERATION_NOT_EXECUTED`，不得伪造结果。

## 二、开工前必须核对的基线

先完整阅读仓库根目录 `AGENTS.md`，再阅读 `docs/README.md`，严格遵守项目现有文档、Git、测试和安全规范。

重点阅读：

1. `docs/design/rag-answer-quality-evaluation.md`
2. `docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/README.md`
3. `docs/acceptance-evidence/2026-10-26/p10-real-generation-01/`（README、账本、原始结果、人工审查记录）
4. `docs/acceptance-evidence/2026-10-26/p10-network-proxy-auto-01/README.md`
5. `docs/development/model-network-proxy.md`
6. `docs/archive/reports/p10-real-generation-01-delivery-20261026.md`
7. `docs/archive/research/p10-real-generation-01-research-20261026.md`
8. `tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json`
9. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityScorer.java`
10. `P10AnswerQualityOfflineEvaluationTest.java`
11. `P10RealGenerationGate.java`、`P10RealGenerationGateTest.java`、`P10RealGenerationPostgresTest.java`
12. 生产知识问答链路：`KnowledgeQuestionApplicationService`、`KnowledgeContextBuilder`、
    `KnowledgeCitationValidator`、`DocumentSearchService`、`RoutingChatModelGateway`、`GeminiModelAdapter`、
    `JsonHttpModelClient`、`UserAiProviderService`。

Git 基线参考值：

- 上轮 `dev`：`65139e2c53a08c3f7e860577d99f05af94bfb62e`
- 上轮 `main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`
- PR #18：OPEN / Draft / 未合并。

以上是上轮核实值，开工时必须重新查询本地与远端的实际状态，不得假定它们没有变化。

先执行只读 Git 检查，确认分支、HEAD、`origin/dev`、`origin/main`、工作区及未跟踪文件。
若存在用户 WIP，保护它，不覆盖、不清理、不重置。

## 三、范围与禁止事项

本轮只验收 **真实 QA 生成路径**。

明确不做：

- 不执行 Agent 真实生成，不额外消耗 Agent 模型调用。
- 不执行额外 LLM-as-a-Judge 调用。
- 不修改生产 System Prompt。
- 不修改检索、来源选择、上下文预算、分块、Embedding 或引用校验算法。
- 不调整默认来源数量、TopK、Token 上限或生产模型路由。
- 不修改 P9/P10 历史冻结题集、语料、历史测量 JSON、原始报告和既有 manifest。
- 不使用真实用户业务文档进行实验。
- 不部署，不修改 `main`，不合并 PR #18。
- 不通过直接调用任意 Chat API、手工拼接文档上下文来替代生产 RAG 链路。
- 不擅自切换 Provider、模型或修改用户的用途分配。
- 不删除旧 ledger、清空旧输出或绕过已有账本的拒绝重跑保护。

如果发现生产代码需要修复，先记录问题和证据，不在本轮顺手修改；修复应作为独立专项。

## 四、阶段 A：环境预检与授权门禁

**本阶段不得发送任何真实 Chat 请求、不得发送外部 Embedding 请求。**

### A1. 确认实际模型路由

核实当前本地环境中，测试用户的 `ModelPurpose.KNOWLEDGE_CHAT` 实际解析到哪个 Provider 和模型。

优先使用系统现有 Provider 配置和模型用途路由，不绕过用户级分配，不擅自切换到其他 Provider。

只报告必要的脱敏信息：

- Provider 类型与名称。
- 实际模型 ID。
- 当前配置是否可用。
- 可确认的免费额度、速率限制与费用边界。
- 是否支持设置单次输出 Token 上限。
- 是否存在自动重试、自动切换模型或其他额外请求行为。

不得输出、复制或写入 API Key、Authorization Header、主密钥及其他凭据。
不得为了测试连通性而提前发起真实生成请求。

模型是否仍受 Provider 支持，可用只读 GET `/models` 与 GET `/models/{model}` 核实；
这类发现调用不消耗推理配额，且不得携带 Chat 语义。

### A2. 确认代理与传输前置

检查实际执行 JVM 的出站代理选择与启动配置。
只有诊断证据不足或环境已改变时才重新进行**无 Chat**的 CONNECT/TLS 探针。

网络前置通过**不代表** Provider 已接收 Chat 请求、**不代表**免费额度可用、**不代表**模型可生成。

### A3. 确认 Embedding 和数据隔离

优先使用已配置的本地 Ollama Embedding 与 P9 冻结语料，外部 Embedding 请求上限为 **0**。

使用隔离的测试数据库或独立临时测试项目。不得删除或覆盖用户已有项目、文档、会话和索引。

严格遵守 P9 每题的语料与临时索引协议，尤其注意 F4 未审核外部摘录的临时索引范围。
不得为了方便把原本临时注入的文档永久混入所有题目的常驻语料。

如果无法隔离数据，或本地 Embedding 不可用，不得改用外部 Embedding 顶替；报告阻断原因。

### A4. 完成预检后暂停

在实际模型调用前，先完成代码审查、测试实现方案、隔离环境准备和离线检查。

向用户提供一份简短的调用确认单，明确：

- Provider 和模型，以及当前路由 SHA-256。
- 为什么它适合本轮基线测量。
- 最多 8 次 Chat 请求，单次输出最多 1,024 tokens。
- 预计输入/输出 Token 与费用；若无法准确估算，明确说明。
- 能否确认使用现有免费额度且不会产生付费。
- 外部 Embedding 请求为 0。
- 不自动重试、不额外运行模型裁判、不执行 Agent。
- 实际 Prompt、来源、原始回答和最终回答的证据采集方式。
- 证据采集方式与潜在风险。

**等待用户明确批准后，才可以发送真实模型请求。** 如果免费额度或费用状态不能确认，必须先询问，不得默认允许付费。
上一批次的授权**不适用于**本批次。

## 五、阶段 B：建立真实 QA 测量路径

必须复用生产实际链路，而不是仅复用离线评分器。

验证路径：

1. 生产文档索引与检索。
2. 生产 `DocumentSearchService` 候选召回。
3. `KnowledgeContextBuilder.buildBalanced` 实际来源选择与上下文预算。
4. 生产 `KnowledgeQuestionApplicationService` 的非流式 QA 路径。
5. 生产 `ModelPurpose.KNOWLEDGE_CHAT` 路由及真实 `ChatModelGateway` 调用。
6. 生产 `KnowledgeCitationValidator` 引用校验。
7. 最终用户可见回答与既有持久化行为。

当前已知实现为检索候选 TopK=8、QA 默认来源上限 5、上下文预算 8,000 code points。
不得为了提高测试成绩调整这些参数。

必须捕获同一次真实运行中的：

- 实际检索候选与入选来源。
- 真实 documentId/chunkId 和来源正文。
- 实际系统 Prompt、用户 Prompt/上下文及其版本或哈希。
- 模型原始回答。
- 引用校验后的最终回答。
- 引用编号、身份核验和强制拒答结果。
- 实际出站代理选路诊断（脱敏）。
- 可取得的用量数据（Token、延迟、finishReason）；不可取得的一律标记 `UNAVAILABLE` / `UNKNOWN`。

可通过测试专用 Spy/Decorator 或隔离测试观察点采集信息。
不得为了采集证据新增会泄露 Prompt、凭据或敏感内容的生产日志。

如果不能证明走的是上述真实生产链路，必须将结果标记为未执行或受限执行，
不得用直连模型的结果冒充端到端验收。

## 六、阶段 C：首轮 8 道冻结题

使用 P10 冻结题集中的原始题目文本与规则，不改写问题，不调整预期事实。
每题使用独立会话或等价的无历史上下文环境，避免前题答案泄漏到后题。

固定题目：

| 题目 ID | 验收风险 |
| --- | --- |
| `AQ01-CURRENT` | 现行版本与旧版本辨别 |
| `AQ02-REPEALED` | 已废止条款与现行规定冲突 |
| `AQ04-REGION-THRESHOLD` | 不同地区的阈值冲突 |
| `AQ06-UNLESS` | 否定条件、例外和合同顺序 |
| `AQ07-AND-OR` | 复合条件、金额与份数约束 |
| `AQ11-ABSTAIN-NONE` | 完全无答案时是否诚实拒答 |
| `AQ12-ABSTAIN-INDIRECT` | 存在相关资料但仍缺少答案时是否拒答 |
| `AQ14-INJECTION` | 未审核文档中的提示注入与来源可信度 |

每道题严格执行一次 Chat 请求，使用同一实际模型，顺序执行并遵守该 Provider 的速率限制。

硬性预算：

- 最多 8 次 Chat 请求。
- 每次输出上限 1,024 tokens。
- 外部 Embedding：0 次。
- Agent 模型调用：0 次。
- 自动重试：0 次。
- 额外 LLM-as-a-Judge 调用：0 次。

任何 401、429、5xx、超时或其他失败均如实记账，不自动重试、不自动切换模型。
如果发生额度或授权问题，停止后续调用，保留已取得的证据，并将剩余题目标为 `NOT_RUN`。

如果 Provider 实际不支持请求输出上限，必须在执行前报告；不能把配置中的上限冒充为已生效限制。

## 七、阶段 D：评分与归因

继续复用 P10 的 Rubric 与确定性评分器，但必须针对本次真实运行采集的证据进行评分。

P10 离线评分器部分来源是合成身份、文档级全文和 P9 历史 `contextAvailability`。
这些不能直接替代本轮真实 Prompt 中的来源集合。

本轮必须重新计算每道题的实际证据可得性：

- 关键事实实际进入上下文：回答缺失或错误，才可归入生成层问题。
- 关键事实未进入上下文：归入检索/上下文缺口，不能直接判模型错误。
- 回答包含未进入上下文的事实：标记为无上下文证据的回答，进一步调查是否存在泄漏、历史上下文或幻觉。
- 来源身份有效不等于正文语义得到支持。
- 引用编号合法不等于引用支持该条结论。

每道题至少评价：

1. 关键事实覆盖率。
2. 现行版本与废止条款辨别。
3. 地区及组织归属辨别。
4. 否定、例外、复合条件保真。
5. 无答案与间接缺口题的拒答准确性。
6. 提示注入抵抗。
7. 引用身份正确性与逐条语义支持。
8. 最终回答与原始模型回答之间的校验差异。
9. 延迟、Token 用量与实际费用。

机械评分与语义审查分开记录。对同义改写、条件翻转、冲突叙述质量和引用语义支持，
必须明确哪些已经人工核对、哪些仍待人工复核。模型自身或同一模型辅助给出的判断不算独立人工验证。

## 八、阶段 E：证据、报告与项目文档

本轮证据统一放入：

`docs/acceptance-evidence/2026-10-26/p10-real-generation-02/`

至少包含：

- `README.md`：范围、执行方式、裁决和证据索引。
- `authorization.json`：本批次用户明确授权记录。
- `model-call-ledger.json`：授权状态、Provider/模型、实际请求数、Token、延迟、费用及失败记录。
- `retrieval-context.json`：逐题真实检索、来源身份、上下文与证据可得性。
- `generation-results.json`：原始回答、最终回答、引用校验结果及逐题评分。
- `manual-review.md`：语义审查与未决问题。
- `manifest.sha256`：冻结本轮证据完整性。
- `raw/real/`：测试直接写出的原始文件，后续构建逐字节校验不覆盖。
- `build-real-evidence.cjs`：**本批次独立**的证据组装与完整性校验流程。

**不得直接复用旧批次的 `build-real-evidence.cjs`**：它绑定旧目录与旧任务 ID，
复用会污染旧批次的 manifest 与任务归属。新批次必须有自己的组装脚本，
只读取本批次 `target/p10-real-generation-02/real/`，只写本批次目录。

新账本必须独立，不得删除旧 ledger、清空旧输出或绕过已有账本的拒绝重跑保护。

所有证据不得包含密钥、Authorization Header 或其他凭据。
无法取得的 Token/费用数据标记为 `UNAVAILABLE`，不得推算后冒充真实用量。

研究报告存入 `docs/archive/research/`，交付报告存入 `docs/archive/reports/`，
本任务提示词存入 `docs/archive/prompts/`。如更新长期评测状态，
应同步维护 `docs/design/rag-answer-quality-evaluation.md` 和 `docs/README.md` 导航。

不得修改 P9/P10 历史冻结文件；新结果只能以独立目录归档。

## 九、测试、Git 与 CI

- 新增真实生成测试必须默认关闭外部调用；仅显式开启 opt-in 且预算、授权参数均有效时才允许执行。
- 默认 CI 不得因为新增测试而产生任何外部 Chat/Embedding 请求。
- 先运行新增定向测试及必要的离线回归，确认 opt-in 关闭时调用数为 0。
- 按 `AGENTS.md` 与本轮改动风险执行必要的提交前检查；不重复无关前端测试。
- 不必为了等待无关 CI 而停止其他可做的研究与证据整理，但最终裁决必须如实记录 CI 的实际状态，
  不得将 `PENDING` 写成 `SUCCESS`。
- 遵守仓库的 `dev` 开发分支策略；只提交本轮相关文件，禁止 `git add -A`。
- 不 force push、不 reset/clean、不改写历史、不覆盖用户 WIP。
- 不改 `main`，不合并 PR #18，不改变 Draft 状态。
- 仅在工作区安全、测试结果可复现且证据齐全时提交并推送 `dev`。

## 十、最终交付必须回答

1. 网络预检是否通过？
2. Provider 是否实际接收请求，模型是否成功生成？
3. 实际执行、失败和未运行的题目分别有哪些？
4. 每题检索和上下文证据是否充分？
5. 是否发现版本、地区、否定条件、拒答、注入或引用语义错误？
6. 原始回答与最终回答是否一致？
7. Token、费用、延迟有哪些真实测量值？
8. 哪些结论经过人工复核？
9. 执行裁决与模型质量裁决分别是什么？
10. 最终 Git SHA、工作区、CI 和 PR 状态如何？

**验收原则：** 先测量当前生产行为，再决定是否改造。不能为了让模型得高分而先修改 Prompt、测试题、
来源选择或评分规则。8 道合成题的结果只代表本轮受控样本，不得宣称真实用户总体质量已经得到证明。
