# P10-RAG-ANSWER-QUALITY-01 — 真实回答质量、版本冲突与引用可信度评测设计

> **状态：OFFLINE 层 IMPLEMENTED / `P10_OFFLINE_EVALUATION_ACCEPTED`；P10-REAL 第 2 轮已授权并完成；P10-SCORER-ROBUSTNESS-01 已完成评分器 2.0-robust 稳健性重构（已证实假阳性全部确定性修复，肯定违规反例 100% 检出）**
> **基线：** `dev@0b31d12`（P10-02 证据之上）　**证据：** `docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/`、`docs/acceptance-evidence/2026-10-26/p10-real-generation-02/`、`docs/acceptance-evidence/2026-10-26/p10-real-generation-02-errata/`、`docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-01/`
> **范围：** 从"检索到了什么"进入"模型最终回答得对不对"的可验证工程阶段。不修改生产回答算法。

## 1. 两层评测架构

| 层 | 内容 | 执行条件 |
| --- | --- | --- |
| 离线契约层（本轮完成） | 问题→Prompt 证据映射（P9 冻结）、冲突标注、Rubric、评分器、拒答/注入判定、脚本化回答确定性回归 | 默认 CI，零外部调用 |
| 真实生成层（P10-REAL 第 1 轮中止、第 2 轮完成） | 真实 QA 生产链路 8 题全部取得 Gemini 回答，机械评分 + 逐题语义复核 | 显式 opt-in + 用户明确授权（Provider/模型/题集/请求上限/Token 预算/重试策略） |

历史 P10 离线目录的 `model-call-ledger.json` 仍记录 authorized=false、外部调用 0，历史证据不改写。
P10-REAL 每轮获得独立明确授权，记账见第 8 节；P7 历史授权不自动延续。

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

## 9. 真实 QA 第 2 轮（P10-REAL-GENERATION-02）

新增独立[真实运行证据目录](../acceptance-evidence/2026-10-26/p10-real-generation-02/README.md)。
第 1 轮的出站选路缺口由 P10-NETWORK-PROXY-AUTO-01 修复后，本轮以**新批次标识**独立执行，
门禁显式拒绝复用 `p10-real-generation-01`，01 批次的账本、原始回执与 manifest 原样保留。

**执行结果**：8 题固定顺序、每题最多 1 次 Chat、间隔 ≥15 s、输出封顶 1024，全部 `HTTP_RETURNED`
且带 `usageMetadata`。0 失败、0 `NOT_RUN`、0 重试、0 模型切换、外部 Embedding/Agent/裁判均为 0。
实测 `promptTokenCount` 合计 6,749、`candidatesTokenCount` 合计 760、`serviceTier=standard`，
延迟 820–2,644 ms；Provider 未报告思考 Token；**实际费用 `UNAVAILABLE`**（不推算）。
执行裁决 `MEASUREMENT_EXECUTED`。

**质量裁决 `PASS_WITH_SCORER_LIMITATIONS`**：机械评分器报出的 3 项高严重度经逐条复核
**全部为评分器字面匹配局限**——否定语境（AQ12 的 `每年抽查` 出现在"资料中**并未明确**…"的否定从句）
与 Markdown 强调（AQ06 的 `**不可以**`）；另 2 处 `MISS_ATTRIBUTABLE` 在归一化后完整命中。
**无任何一题构成模型事实错误、否定翻转、地区套用、错误拒答或注入遵从。**

**归因分层**：8 题关键事实上下文可得性全部 `RETAINED`，**本轮没有检索层缺口**。
唯一系统性边界是 AQ01 缺版本施行/废止条款正文块，模型只能依据 Prompt 中可见的**文件名**区分版本——
属**检索/上下文层**，不是生成错误；需要从来源选择层补入，**不改 Prompt**。
9 条引用逐条语义支持全部成立，未以来源身份有效代替语义支持。
原始回答与最终回答 8 题逐字节相同，引用校验未改写正文。

**边界**：独立人工复核 0 题；8 道合成题不代表真实用户总体质量。
下一轮优先项是**评分器**改进（否定语境与 Markdown 归一化、`abstentionPhrases` 覆盖度），
不是生产 Prompt、检索算法或输出上限的改动。

## 10. 评分器稳健性专项（P10-SCORER-ROBUSTNESS-01）

新增独立[评分器稳健性证据目录](../acceptance-evidence/2026-10-26/p10-scorer-robustness-01/README.md)与[P10-02 文档勘误](../acceptance-evidence/2026-10-26/p10-real-generation-02-errata/README.md)。

**核心成果：落地评分器 2.0-robust 确定性架构**：
1. **统一文本规范化机制（`P10TextNormalizer`）**：剥离 Markdown `**`、`*`、`__`、`_`、``` ` ``` 强调标记，统一 Unicode (NFKC) 兼容形式与连续空白换行，消除格式变体导致的匹配差异；
2. **上下文肯定性判定器（`P10ContextualAssertionJudge`）**：严格区分肯定断言（`AFFIRMATIVE_VIOLATION`）、明确否定与认识模态（`NEGATED_OR_EPISTEMIC_MENTION`）以及复杂双重否定/转折/例外（`AMBIGUOUS_NEEDS_REVIEW`），从根源上消除了 AQ06 粗体否定与 AQ12 否定从句的误报；
3. **增强拒答与未知状态检测（`P10AbstentionDetector`）**：保留题集冻结 `abstentionPhrases` 命中追溯（`frozenPhraseHit`），扩展识别“尚未确定”、“并未明确”等合法未知表达（`QUALIFIED_UNKNOWN`），并新增带有未支持编造的冲突拒答检测（`CONFLICTING_FABRICATION`）；
4. **人工复核边界清晰受控（`manual-review-boundaries.md`）**：双重否定、复杂例外条款等机械不可可靠判定场景统一转入 `needsHumanReview`，绝不擅自放行或盲目报警；
5. **确定性指标验证**：3 项已证实假阳性全部清零，7 项肯定违规反例保持 100% 检出率（0 漏报），14 题模范回答误报数为 0。全部运行于默认离线 CI，零外部真实调用，生产链路代码零修改。

## 11. 评分器作用域与转折分句修复专项（P10-SCORER-ROBUSTNESS-02）

新增独立[评分器作用域与转折修复证据目录](../acceptance-evidence/2026-10-26/p10-scorer-robustness-02/README.md)。

**核心成果：落地评分器 2.0-scoped-contextual 架构**：
1. **转折连词与分句作用域隔离**：在 `P10ContextualAssertionJudge` 中引入子句标点与转折词（“但”、“但是”、“然而”、“实际上”等）硬性边界切分，解决“资料中并未明确合同抽查比例，但制度要求每年抽查所有合同”因前半句认识模态导致后半句肯定违规被抑制的漏报缺陷；
2. **多出现位置（Multiple Occurrences）独立评估**：支持单句中同一禁断 marker 的多次独立扫描与判定，对确凿违规断言赋予最高判定优先级；
3. **认识模态判断范围收紧**：移除整句宽泛检查，严格限定在同分句直接修饰（含未知指示词或紧邻动词）或无转折跨从句修饰；
4. **复杂转折反差安全转复核**：对“并未明确……但实际上可以……”等灰色口径冲突，安全进入 `AMBIGUOUS_NEEDS_REVIEW` 并记录于 `needsHumanReview`，绝不直接放行；
5. **确定性指标验证**：新增 6 类回归场景（A-F，7 个子案例）100% 通过；7/7 既有肯定违规反例保持 100% 检出率；AQ06/AQ12 历史假阳性保持为 0；外部模型调用数为 0，生产代码零修改。

## 12. 例外条款作用域隔离与漏报防护专项（P10-SCORER-ROBUSTNESS-03）

新增独立[评分器例外作用域隔离证据目录](../acceptance-evidence/2026-10-26/p10-scorer-robustness-03/README.md)。

**核心成果：落地评分器 2.0-scoped-contextual-v3 架构**：
1. **例外条款作用域收敛**：在 `P10ContextualAssertionJudge` 中彻底废除全句级 `EXCEPTION_MARKER.matcher(sentence).find()` 扫描，限定例外条款修饰范围仅作用于当前局部子句自身或紧邻的前置从句（且未被转折连词切断）；
2. **禁止性词汇限定局部子句**：禁止性否定词（“不得”、“不能”、“不可以”、“禁止”）严格限定在当前局部子句 `localClause` 中检测，杜绝一个分句中的禁止词掩盖另一分句中的肯定违规（如“除非获得特批，否则不可以……，但该回答又肯定声称可以……”）；
3. **特批条件许可安全转入人工复核**：单独特批条件许可句（如“除非获得特批，才可以……”）由于无法机械确证特批事实，统一判定为 `AMBIGUOUS_NEEDS_REVIEW` 并记录于 `needsHumanReview`，绝不机械放行；
4. **多 occurrence 独立评估与完整审计**：同句多次出现的禁断 marker 逐一独立界定局部子句并产出审计明细（含 marker、局部子句、判定理由与规则版本）；
5. **确定性指标验证**：新增 4 类例外场景（G1-G4）全部通过；上轮 7 类作用域场景（A-F2）100% 保持通过；7/7 既有肯定违规反例保持 100% 检出率；AQ06/AQ12 历史假阳性保持为 0；外部真实模型调用数为 0，生产代码与冻结题集零修改。

