# P10-RAG-ANSWER-QUALITY-01 — 真实回答质量、版本冲突与引用可信度评测设计

> **状态：OFFLINE 层 IMPLEMENTED / `P10_OFFLINE_EVALUATION_ACCEPTED`；P10-REAL 首轮已授权，真实传输尝试 1 次超时、7 题未运行，模型质量 `NOT_ASSESSABLE_NO_MODEL_RESPONSE`**
> **基线：** `dev@efafbc2`（P9 证据之上）　**证据：** `docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/`
> **范围：** 从"检索到了什么"进入"模型最终回答得对不对"的可验证工程阶段。不修改生产回答算法。

## 1. 两层评测架构

| 层 | 内容 | 执行条件 |
| --- | --- | --- |
| 离线契约层（本轮完成） | 问题→Prompt 证据映射（P9 冻结）、冲突标注、Rubric、评分器、拒答/注入判定、脚本化回答确定性回归 | 默认 CI，零外部调用 |
| 真实生成层（P10-REAL 首轮受控中止） | 真实 QA 生产链路已到 Gemini 传输，首题超时，无答案可评分 | 显式 opt-in + 用户明确授权（Provider/模型/题集/请求上限/Token 预算/重试策略） |

历史 P10 离线目录的 `model-call-ledger.json` 仍记录 authorized=false、外部调用 0，历史证据不改写。
后续 P10-REAL 获得独立明确授权，记账见第 8 节；P7 历史授权不自动延续。

## 2. 评测子集与冻结

`tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json`：
14 题（AQ01–AQ14），派生自 P9 冻结标注（未改动），覆盖版本现行/废止/变化、跨地区冲突、
否定与例外、条件组合、超时语义、跨文档复合、同文档互补、两类拒答、未定事项、提示注入。
每题冻结 expected/distract 事实、机械规则（required/forbidden/legacy 限定词/拒答短语/注入禁词）。
逐事实的上下文可得性由 P9 冻结检索结果注入（`contextAvailability`），禁止手写。

**Holdout 语义**：P9 Holdout 结果已可见，作为冻结挑战集使用，不再是盲测集；
未依据其结果调整生成 Prompt、评分策略或模型选择。

## 3. QA 与 Agent 的引用协议差异（不得互相套用）

| | QA | Agent |
| --- | --- | --- |
| 正文协议 | `[S1]`/`[S1, S2]` 编号 | 无编号协议 |
| 校验 | 生产 `KnowledgeCitationValidator`：编号合法、非法成员移除、无有效引用的实质回答**整体替换为统一拒答文案**（invalidOutput） | 来源卡=工具实际读取并持久化的 documentId/chunkId；`citationsStillValid` 防失效 |
| 语义支持 | 编号合法 ≠ 支持，须人工 | 正文与来源卡无机械绑定（available-but-uncited 为既有行为），人工审查比 QA 更缺工具 |

## 4. 机械可判 vs 必须人工

**机械可判**（`P10AnswerQualityScorer`，确定性）：verbatim 事实覆盖、旧值裸用（版本违规）、
枚举禁断表述、拒答短语存在、QA 编号合法/编造、Agent 身份存在/伪造、注入禁词、无证据数字候选。

**必须人工**：语义支持（citation_claim_supported）、改写等价、未枚举的否定翻转措辞、
冲突叙述质量、拒答理由恰当性。评分器对这类只输出 `needsHumanReview`，绝不自动判对错。

**归因分层**：证据未进上下文而缺失=系统缺口（不可归责模型）；已进而缺失/错误=生成层责任；
未进而"答对"=泄漏/幻觉观察。

## 5. 指标

Factual Correctness（verbatim 代理+人工）、Completeness、Version Accuracy、Conflict Resolution、
Condition Fidelity、Abstention Accuracy、Citation Identity（机械）、Citation Support（人工）、
Unsupported Claims（候选）、Prompt Injection Resistance（机械禁词+人工）、Request Cost/Latency
（仅真实层）。高严重度错误（废止当现行、跨地区套用、否定翻转、编造、注入遵从）单列，
不得用平均分掩盖。

## 6. 运行方式

```bash
cd ai-collab-backend && bash ./mvnw -B -ntp test -Dtest=P10AnswerQualityOfflineEvaluationTest
# 默认 CI 执行（无外部依赖）；结果写 target/p10-rag-answer-quality-01/
```

真实层由 `P10RealGenerationPostgresTest` 实现：`P10_LOCAL_PREFLIGHT=true` 开启隔离环境，
`AI_REAL_CHAT_ANSWER_QUALITY=true` 再加授权/模型/预算/免费边界/路由快照参数才可真实出站。
默认 CI 整类跳过真实路径；已有真实 ledger 时拒绝自动重跑。

## 7. 最小真实评测预算建议（供授权决策，未执行）

14 题 × 1 次 QA 调用 = **14 次请求**（覆盖版本/冲突/否定/拒答/注入全部风险类别）；
若加 Agent 路径另计 14 次 + 少量工具轮次（≈20 次模型调用）。
输出上限建议 1024 token/题。总计 ≤34 次请求、≈50k output token 以内可完成首轮全量机械+人工评分。

## 8. 真实 QA 首轮（P10-REAL-GENERATION-01）

新增独立[真实运行证据目录](../acceptance-evidence/2026-10-26/p10-real-generation-01/README.md)，
首轮固定 8 题 QA、每题最多 1024 输出 tokens；不执行 Agent 或裁判模型。
`P10RealGenerationPostgresTest` 默认跳过；本地预检只调用 Ollama，通过合成 HTTP 响应验证生产链路采集。
额外真实门禁必须校验授权、用户现有路由、模型、请求预算、费用及速率确认。
用户已确认免费 API 并明确批准；未独立核实账号计费状态。
实际首题发生一次客户端传输尝试，20,912 ms 后 AI_MODEL_TIMEOUT；按失败即停规则其余 7 题 NOT_RUN，无自动重试。
没有模型响应、Token、费用或最终落库回答；执行 `STOPPED_ON_REQUEST_FAILURE`，质量 `NOT_ASSESSABLE_NO_MODEL_RESPONSE`。
来源身份核验和五百/四百冲突证据可见不能替代回答引用语义支持。
用户指出 7897 代理后，核实默认 JVM 为 DIRECT，显式经 7897 的 CONNECT/TLSv1.3 成功（无应用 HTTP/Chat）。
下一步先修正测量进程出站代理和传输可观测性；没有证据支持改 Prompt、Token 上限或检索算法。
本次不改变历史 P10 离线结果或 P9 冻结事实；首次 verify 错误与后续全量通过独立保留。
