# P10-SCORER-ROBUSTNESS-01：真实回答质量评分器稳健性专项

> **任务标识：** `P10-SCORER-ROBUSTNESS-01`  
> **归档位置：** `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-01/README.md`  
> **基线状态：** 在 `P10-REAL-GENERATION-02` 真实生成验收完成的基础上进行。  
> **核心原则：** 提高评测工具的可信度，而不是为了让模型得分好看；**不修改生产代码、不修改 System Prompt、不修改检索算法或预算、不发起任何真实外部模型请求**。

---

## 1. 任务背景与核心结论

上一轮真实模型端到端验收（`P10-REAL-GENERATION-02`）首次取得了 8 份真实的 Gemini 模型回答。在逐题语义复核过程中，证实原离线评分器（`1.0-legacy`）存在机械包含误判（假阳性）：
1. **AQ06-UNLESS**：模型真实回答包含“通常**不可以**先组织培训再补签合同”，否定词被 Markdown 粗体包裹。旧评分器在剥离格式或直接匹配时，无法识别前置否定助动词，误报禁断短语肯定违规。
2. **AQ12-ABSTAIN-INDIRECT**：模型真实回答明确表达“年度专项核对的抽查样本量的最终比例尚未确定……资料中并未明确具体每年抽查多少份合同”。旧评分器因命中了“每年抽查”这四个字，误报肯定违规；同时因冻结题集短语列表未收录“尚未确定/并未明确”，误报未诚实拒答（合计 2 项机械假阳性高严重度）。

本专项设计并落地了**评分器 2.0-robust 架构**：
- **统一文本规范化机制（`P10TextNormalizer`）**：安全剥离 Markdown 强调（粗体、斜体、代码），统一 Unicode (NFKC) 与连续空白，保持事实 marker 与回答文本的对齐规范化，等价格式变体评分 100% 一致。
- **上下文肯定性判定器（`P10ContextualAssertionJudge`）**：明确区分肯定断言（`AFFIRMATIVE_VIOLATION`）、否定/未知语境（`NEGATED_OR_EPISTEMIC_MENTION`）与双重否定/例外复杂语境（`AMBIGUOUS_NEEDS_REVIEW`）。只有确凿肯定断言才触发高严重度违规。
- **增强拒答与未知状态检测（`P10AbstentionDetector`）**：冻结 `abstentionPhrases` 规则原样保留并记录追溯（`frozenPhraseHit`），扩展识别诚实拒答与限定未知状态（`QUALIFIED_UNKNOWN`），并增加了防范携带编造断言的冲突假拒答检测（`CONFLICTING_FABRICATION`）。
- **人工复核兜底（Human-in-the-loop）**：对于双重否定、复杂假设与例外条款，不机械判定通过或失败，显式转入 `needsHumanReview`。

### 验收核心量化结论（来自确定性回归矩阵）

| 指标项 | 评测结果 | 说明依据 |
| --- | --- | --- |
| **已证实假阳性修复数** | **3 处全部修复** | AQ06 粗体否定（1处）、AQ12 否定从句（1处）、AQ12 拒答漏报（1处） |
| **原有肯定违规反例检出率** | **100%（7/7）** | 版本违规、错误地区阈值、肯定违规补签、编造数据库、回避但编造数字、注入遵从全部准确检出 |
| **新增漏报反例数** | **0** | 无任何肯定违规被误判为合格 |
| **新增人工复核边界案例** | **1 类** | 双重否定（如“并非不可以”）与复杂例外转入人工复核 |
| **模范回答（Golden）误报数** | **0** | 14 题受控模范回答高严重度依然全为 0 |
| **外部真实模型请求数** | **0** | 纯确定性离线回归，默认 CI 执行 |
| **生产代码 / Prompt / 检索变更** | **0** | 未触碰生产路径，题集 JSON 冻结规则原样保留 |

---

## 2. 证据索引

| 文件 | 内容说明 |
| --- | --- |
| [scorer-version-comparison.json](scorer-version-comparison.json) | 新旧评分器（1.0-legacy vs 2.0-robust）确定性对比统计快照 |
| [false-positive-regressions.json](false-positive-regressions.json) | 已证实的已知假阳性（AQ06、AQ12）在旧版与新版下的确定性对比与修复证据 |
| [false-negative-regressions.json](false-negative-regressions.json) | 7 个肯定违规反例在新版评分器下的 100% 检出回归证明（防漏报证据） |
| [manual-review-boundaries.md](manual-review-boundaries.md) | 双重否定、复杂例外、歧义拒答等人工复核边界的详细定义与规范 |
| [manifest.sha256](manifest.sha256) | 本目录证据文件的 SHA-256 完整性清单 |

---

## 3. 关联任务与文档指引

- **Errata 勘误归档**：[`../p10-real-generation-02-errata/README.md`](../p10-real-generation-02-errata/README.md)（澄清 P10-02 README 预检草稿遗留与裁决阶段演进）
- **设计文档**：[`docs/design/rag-answer-quality-evaluation.md`](../../../design/rag-answer-quality-evaluation.md)
- **任务书归档**：[`docs/archive/prompts/p10-scorer-robustness-01-task-20261026.md`](../../../archive/prompts/p10-scorer-robustness-01-task-20261026.md)
- **研究记录归档**：[`docs/archive/research/p10-scorer-robustness-01-research-20261026.md`](../../../archive/research/p10-scorer-robustness-01-research-20261026.md)
- **交付报告归档**：[`docs/archive/reports/p10-scorer-robustness-01-delivery-20261026.md`](../../../archive/reports/p10-scorer-robustness-01-delivery-20261026.md)

---

## 4. 边界与后续建议

1. **不可替代人工语义审查**：虽然评分器 2.0-robust 修复了字面包含缺陷，但自然语言的复杂语义等价性与跨文档逻辑支持依然无法完全由离线规则代替，必须保留人工复核机制。
2. **生产端演进建议**：本次专项证明模型在 AQ06、AQ12 上的回答逻辑高度保真。后续演进应重点关注检索召回阶段的完整性（如 AQ01 版本正文条款选择补全），无需调整生成 Prompt。
