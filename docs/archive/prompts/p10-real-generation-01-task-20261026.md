# P10-REAL-GENERATION-01：真实 QA 生成基线验收任务书

## 一、任务目标

你正在 AI Collab 项目中继续 P10-RAG-ANSWER-QUALITY-01。

本轮目标不是继续完善模拟评分器，也不是直接修复 Prompt，而是首次使用真实 Chat 模型，对生产知识问答链路进行受控质量验收。

任务 ID：`P10-REAL-GENERATION-01`

预期结果：
- 真实生成：`REAL_GENERATION_EXECUTED`，仅在真实请求确实执行后填写。
- 评测执行裁决与模型回答质量裁决分开记录。
- 不因测试框架通过就宣称模型回答质量通过。
- 若真实请求未获授权或受环境阻断，明确记录 `REAL_GENERATION_NOT_EXECUTED`，不得伪造结果。

## 二、开工前必须核对的基线

先完整阅读仓库根目录 `AGENTS.md`，再阅读 `docs/README.md`，严格遵守项目现有文档、Git、测试和安全规范。

重点阅读：

1. `docs/design/rag-answer-quality-evaluation.md`
2. `docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/README.md`
3. 同目录下的 `rubric.md`、`limitations.md`、`evidence-availability-matrix.json`、`model-call-ledger.json`
4. `docs/archive/reports/p10-rag-answer-quality-01-delivery-20261026.md`
5. `docs/archive/research/p10-rag-answer-quality-01-research-20261026.md`
6. `tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json`
7. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityScorer.java`
8. `P10AnswerQualityOfflineEvaluationTest.java`
9. 生产知识问答链路：`KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService`、`KnowledgeContextBuilder`、`KnowledgeCitationValidator`，以及实际 Provider 路由、Embedding、文档索引和持久化实现。
10. P9 的真实检索测试与文档索引协议。

Git 基线参考值：

- 上轮 `dev`：`d1a45ce93181fb8178f708d1611e6d3852ba059c`
- 上轮 `main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`
- PR #18：OPEN / Draft / 未合并。

以上是上轮核实值，开工时必须重新查询本地与远端的实际状态，不得假定它们没有变化。

先执行只读 Git 检查，确认分支、HEAD、`origin/dev`、工作区及未跟踪文件。若存在用户 WIP，保护它，不覆盖、不清理、不重置。

## 三、范围与禁止事项

本轮只验收 **真实 QA 生成路径**。

明确不做：

- 不执行 Agent 真实生成，不额外消耗约 20 次 Agent 模型调用。
- 不修改生产 System Prompt。
- 不修改检索、来源选择、上下文预算、分块、Embedding 或引用校验算法。
- 不调整默认来源数量、TopK、Token 上限或生产模型路由。
- 不修改 P9 冻结题集、语料、历史测量 JSON、原始报告和既有 manifest。
- 不使用真实用户业务文档进行实验。
- 不部署，不修改 `main`，不合并 PR #18。
- 不通过直接调用任意 Chat API、手工拼接文档上下文来替代生产 RAG 链路。

如果发现生产代码需要修复，先记录问题和证据，不在本轮顺手修改；修复应作为独立专项。

## 四、阶段 A：环境预检与授权门禁

本阶段不得发送任何真实 Chat 或外部 Embedding 请求。

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

不得输出、复制或写入 API Key、Authorization Header、主密钥及其他凭据。不得为了测试连通性而提前发起真实生成请求。

### A2. 确认 Embedding 和数据隔离

优先使用已配置的本地 Ollama Embedding 与 P9 冻结语料，外部 Embedding 请求上限为 **0**。

使用隔离的测试数据库或独立临时测试项目。不得删除或覆盖用户已有项目、文档、会话和索引。

严格遵守 P9 每题的语料与临时索引协议，尤其注意 F4 未审核外部摘录的临时索引范围。不得为了方便把原本临时注入的文档永久混入所有题目的常驻语料。

如果无法隔离数据，或本地 Embedding 不可用，不得改用外部 Embedding 顶替；报告阻断原因。

### A3. 完成预检后暂停

在实际模型调用前，先完成代码审查、测试实现方案、隔离环境准备和离线检查。

向用户提供一份简短的调用确认单，明确：
- Provider 和模型。
- 为什么它适合本轮基线测量。
- 最多 8 次 Chat 请求，单次输出最多 1,024 tokens。
- 预计输入/输出 Token 与费用；若无法准确估算，明确说明。
- 能否确认使用现有免费额度且不会产生付费。
- 外部 Embedding 请求为 0。
- 不自动重试、不额外运行模型裁判、不执行 Agent。
- 实际 Prompt、来源、原始回答和最终回答的证据采集方式。

**等待用户明确批准后，才可以发送真实模型请求。** 如果免费额度或费用状态不能确认，必须先询问，不得默认允许付费。

## 五、阶段 B：建立真实 QA 测量路径

必须复用生产实际链路，而不是仅复用离线评分器。

优先验证以下路径：

1. 生产文档索引与检索。
2. 生产 `DocumentSearchService` 候选召回。
3. `KnowledgeContextBuilder.buildBalanced` 实际来源选择与上下文预算。
4. 生产 `KnowledgeQuestionApplicationService` 的非流式 QA 路径。
5. 生产 `ModelPurpose.KNOWLEDGE_CHAT` 路由及真实 `ChatModelGateway` 调用。
6. 生产 `KnowledgeCitationValidator` 引用校验。
7. 最终用户可见回答与既有持久化行为。

代码审查时应确认现行参数。当前已知实现为检索候选 TopK=8、QA 默认来源上限 5、上下文预算 8,000 code points。不得为了提高测试成绩调整这些参数。

必须捕获同一次真实运行中的：
- 实际检索候选与入选来源。
- 真实 documentId/chunkId 和来源正文。
- 实际系统 Prompt、用户 Prompt/上下文及其版本或哈希。
- 模型原始回答。
- 引用校验后的最终回答。
- 引用编号、身份核验和强制拒答结果。

可通过测试专用 Spy/Decorator 或隔离测试观察点采集信息。不得为了采集证据新增会泄露 Prompt、凭据或敏感内容的生产日志。

如果不能证明走的是上述真实生产链路，必须将结果标记为未执行或受限执行，不得用直连模型的结果冒充端到端验收。

## 六、阶段 C：首轮 8 道冻结题

使用 P10 冻结题集中的原始题目文本与规则，不改写问题，不调整预期事实。每题使用独立会话或等价的无历史上下文环境，避免前题答案泄漏到后题。

首轮固定题目：

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

任何 401、429、5xx、超时或其他失败均如实记账，不自动重试、不自动切换模型。如果发生额度或授权问题，停止后续调用，保留已取得的证据，并将剩余题目标为 `NOT_RUN`。

如果 Provider 实际不支持请求输出上限，必须在执行前报告；不能把配置中的上限冒充为已生效限制。

## 七、阶段 D：评分与归因

继续复用 P10 的 Rubric 与确定性评分器，但必须针对本次真实运行采集的证据进行评分。

特别注意：P10 离线评分器部分来源是合成身份、文档级全文和 P9 历史 `contextAvailability`。这些不能直接替代本轮真实 Prompt 中的来源集合。

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

机械评分与语义审查分开记录。对同义改写、条件翻转、冲突叙述质量和引用语义支持，必须明确哪些已经人工核对、哪些仍待人工复核。模型自身或同一模型辅助给出的判断不算独立人工验证。

## 八、阶段 E：证据、报告与项目文档

本轮证据统一放入：

`docs/acceptance-evidence/2026-10-26/p10-real-generation-01/`

至少包含：

- `README.md`：范围、执行方式、裁决和证据索引。
- `model-call-ledger.json`：授权状态、Provider/模型、实际请求数、Token、延迟、费用及失败记录。
- `retrieval-context.json`：逐题真实检索、来源身份、上下文与证据可得性。
- `generation-results.json`：原始回答、最终回答、引用校验结果及逐题评分。
- `manual-review.md`：语义审查与未决问题。
- `manifest.sha256`：冻结本轮证据完整性。

所有证据不得包含密钥、Authorization Header 或其他凭据。无法取得的 Token/费用数据标记为 `UNAVAILABLE`，不得推算后冒充真实用量。

研究报告存入 `docs/archive/research/`，交付报告存入 `docs/archive/reports/`，本任务提示词存入 `docs/archive/prompts/`。如更新长期评测状态，应同步维护 `docs/design/rag-answer-quality-evaluation.md` 和 `docs/README.md` 导航。

不得修改 P9/P10 历史冻结文件；新结果只能以独立目录归档。

## 九、测试、Git 与 CI

- 新增真实生成测试必须默认关闭外部调用；仅显式开启 opt-in 且预算、授权参数均有效时才允许执行。
- 默认 CI 不得因为新增测试而产生任何外部 Chat/Embedding 请求。
- 先运行新增定向测试及必要的离线回归，确认 opt-in 关闭时调用数为 0。
- 按 `AGENTS.md` 与本轮改动风险执行必要的提交前检查；不重复无关前端测试。
- 不必为了等待无关 CI 而停止其他可做的研究与证据整理，但最终裁决必须如实记录 CI 的实际状态，不得将 `PENDING` 写成 `SUCCESS`。
- 遵守仓库的 `dev` 开发分支策略；只提交本轮相关文件，禁止 `git add -A`。
- 不 force push、不 reset/clean、不改写历史、不覆盖用户 WIP。
- 不改 `main`，不合并 PR #18，不改变 Draft 状态。
- 仅在工作区安全、测试结果可复现且证据齐全时提交并推送 `dev`。

## 十、最终交付必须回答

1. 实际执行了多少次真实 Chat 请求？是否有失败或未执行题目？
2. 实际使用哪个 Provider 和模型？费用、Token、延迟是否可获得？
3. 每题的真实检索证据是否充分？哪些错误属于检索层，哪些属于生成层？
4. 是否发生现行版本误判、地区套用、条件翻转、错误拒答或提示注入遵从？
5. 原始模型回答与最终用户可见回答是否不同？引用校验是否触发强制替换？
6. 哪些语义结论已经人工复核，哪些仍需人工确认？
7. 评测执行是否成功？模型质量是否达到要求？两者必须分别裁决。
8. 本轮的局限是什么？未执行的题目有哪些？
9. 最终 Git SHA、工作区状态、CI 状态及 PR #18 状态是什么？
10. 下一轮最值得修复的具体问题是什么？是否有证据支持改 Prompt，还是应该先修检索/上下文？

**验收原则：** 先测量当前生产行为，再决定是否改造。不能为了让模型得高分而先修改 Prompt、测试题、来源选择或评分规则。8 道合成题的结果只代表本轮受控样本，不得宣称真实用户总体质量已经得到证明。