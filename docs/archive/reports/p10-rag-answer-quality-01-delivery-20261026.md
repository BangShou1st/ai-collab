# P10-RAG-ANSWER-QUALITY-01 交付报告

> 日期：2026-10-26　基线：`dev@efafbc2`（P9 之上）
> 裁决：**`P10_ANSWER_QUALITY_BASELINE_ACCEPTED`**（限实际执行范围＝离线契约层）；
> 离线框架 `P10_OFFLINE_EVALUATION_ACCEPTED`；真实生成 **`REAL_GENERATION_NOT_EXECUTED`**（无授权，外部调用 0）。

## 1. 前置：P9 口径补记（独立提交 36d27da）

长文档实际 721–3,353 cp（6,000+ 未覆盖）；唯一 23 篇/161 chunks（175=累计入库事件）；
泛化结论边界收敛。冻结原始证据零改动。见
`docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01-errata/`。

## 2. 交付内容

| 项 | 位置 |
| --- | --- |
| 设计 | `docs/design/rag-answer-quality-evaluation.md` |
| 冻结评测子集（14 题） | `tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json` |
| 评分器+确定性测试 | `ai-collab-backend/src/test/.../retrieval/P10AnswerQualityScorer.java` + `P10AnswerQualityOfflineEvaluationTest.java`（默认 CI，4 项，零外部调用） |
| 证据 | `docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/`（13 文件） |
| 报告 | 本文件 + 研究（并入证据 failure-analysis 与设计文档） |

生产 Java 零改动；未调 Q02 特判/来源上限/选择器/Embedding/分块；历史证据与 P9 冻结标注零改动。

## 3. 离线层结果

- 14 题脚本化模范基线：0 高严重度、拒答题全部正确、AQ09 证据缺口观察正确标注。
- 8 个坏回答变体 → 10 项高严重度全部命中（版本现行错误 / 废止当现行 / 地区套用 / 否定翻转 /
  编造+未拒答 / 编造引用 / 无引用被强制替换 / 注入遵从）。
- 机械边界实测：未枚举措辞的否定翻转漏检 → 语义级必须人工（limitations #3）。

## 4. 对任务书第八节问题的回答（摘要）

1. **P9 口径补记**：完成，独立提交。
2. **证据链是否支持回答评测**：QA 12/14、Agent 11/14 题证据完整（availability-matrix）；
   AQ09 等缺口题已有归因分层，不会误判为模型错。
3. **Prompt 现状**：QA 有引用/拒答/注入防御指令，**无版本现行性/废止/组织归属辨别指令**；
   冲突条款 P9 实测几乎全部同屏可见，辨别全靠模型（待真实评测验证）。
4. **机械 vs 人工**：见 rubric.md（枚举措辞、编号身份、拒答短语、注入禁词=机械；
   语义支持、改写等价、冲突叙述=人工）。
5. **无答案识别**：QA 有统一拒答文案与 validator 强制替换；拒答**判断本身**仍靠模型 +
   Prompt 指令，系统无独立"答案存在性"判定——机械层只能验证拒答行为发生后是否诚实。
6. **来源卡 vs 正文引用**：Agent 无编号协议，正文与来源卡零机械绑定（既有契约行为）；
   QA 强绑定（无引用即被替换为拒答）。两者差异已固化为契约测试。
7. **确定性回归**：本轮 4 项 + 既有 KnowledgeCitationValidatorTest +
   AgentFinalCitationContractPostgresTest 11 项（默认 CI）。
8. **缺真实模型证据的项**：全部生成层指标（事实正确性、版本辨别、冲突解决、条件保真、
   拒答判断、注入抵抗的真实发生率、延迟与成本）。
9. **最小真实预算**：QA 14 次；+Agent ≈20 次（含工具轮）；≤34 次请求、≈50k output token。
   逐项计划见设计文档 §7，未执行。

## 5. Git 与 CI

- 提交于 `dev`：`36d27da`（P9 errata 独立补记）→ `33fe569`（P10 交付）；
  `main` 未动（9b4f7c5）；PR #18 保持 OPEN/Draft/未合并；无 force push；未部署。
- 同 SHA CI（33fe569）：Push run [40229147671](https://github.com/BangShou1st/ai-collab/actions/runs/40229147671) success；
  PR #18 run [40229152629](https://github.com/BangShou1st/ai-collab/actions/runs/40229152629) success。
- 本地完整 verify：BUILD SUCCESS（默认 CI 语义下新增 4 项离线测试全部执行）。
