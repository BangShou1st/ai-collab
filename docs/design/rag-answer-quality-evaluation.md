# P10-RAG-ANSWER-QUALITY-01 — 真实回答质量、版本冲突与引用可信度评测设计

> **状态：OFFLINE 层 IMPLEMENTED / `P10_OFFLINE_EVALUATION_ACCEPTED`；真实生成层 `REAL_GENERATION_NOT_EXECUTED`（本轮无外部 Chat 授权）**
> **基线：** `dev@efafbc2`（P9 证据之上）　**证据：** `docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/`
> **范围：** 从"检索到了什么"进入"模型最终回答得对不对"的可验证工程阶段。不修改生产回答算法。

## 1. 两层评测架构

| 层 | 内容 | 执行条件 |
| --- | --- | --- |
| 离线契约层（本轮完成） | 问题→Prompt 证据映射（P9 冻结）、冲突标注、Rubric、评分器、拒答/注入判定、脚本化回答确定性回归 | 默认 CI，零外部调用 |
| 真实生成层（本轮未执行） | 真实 Chat 模型按题集生成，机械+人工双层评分 | 显式 opt-in + 用户明确授权（Provider/模型/题集/请求上限/Token 预算/重试策略） |

`model-call-ledger.json` 记账：本轮 authorized=false、外部调用 0。P7 历史授权不自动延续。

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

真实层实现为后续扩展点：`AI_REAL_CHAT_ANSWER_QUALITY=true` 显式门控 + 授权参数校验
（超出即停止），沿用 P8/P9 的 opt-in 模式；本轮未获授权故未启用任何外部调用。

## 7. 最小真实评测预算建议（供授权决策，未执行）

14 题 × 1 次 QA 调用 = **14 次请求**（覆盖版本/冲突/否定/拒答/注入全部风险类别）；
若加 Agent 路径另计 14 次 + 少量工具轮次（≈20 次模型调用）。
输出上限建议 1024 token/题。总计 ≤34 次请求、≈50k output token 以内可完成首轮全量机械+人工评分。
