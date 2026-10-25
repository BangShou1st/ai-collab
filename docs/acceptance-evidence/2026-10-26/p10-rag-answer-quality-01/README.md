# P10-RAG-ANSWER-QUALITY-01 验收证据

> 任务：真实 AI 回答质量、版本冲突与引用可信度验收——离线契约层。
> 基线：`dev@efafbc2`（P9 之上）　裁决：离线框架 `P10_OFFLINE_EVALUATION_ACCEPTED`；
> 真实生成 `REAL_GENERATION_NOT_EXECUTED`（本轮无授权，外部调用 0）。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| `evaluation-question-set.json`（fixtures 内，证据处为引用） | 14 题冻结评测子集（版本/地区冲突/否定/复合/拒答/注入），派生自 P9 冻结标注 |
| `rubric.md` | 机械可判 vs 必须人工的完整评分规则与归因分层 |
| `evidence-availability-matrix.json` | 逐题 QA/Agent 证据完整度（P9 冻结注入）：区分证据缺口与生成责任 |
| `conflict-case-matrix.json` | 版本/跨来源冲突矩阵 + P9 冲突暴露实测（QA 6/7、Agent 7/7） |
| `offline-evaluation-results.json` | 14 题脚本化模范基线（0 高严重度）+ 8 个坏回答变体（10 项高严重度全部命中） |
| `citation-contract-results.json` | QA/Agent 引用协议契约结论与未定义行为清单 |
| `model-call-ledger.json` | 授权=false，外部 Chat/Embedding 调用 0 |
| `regression-results.json` | 新增 4 项默认 CI 测试；既有门槛未关闭；生产 Java 零改动 |
| `failure-analysis.md` | Prompt 版本辨别缺口、机械边界、AQ09 归因、QA 强制替换、Agent 未定义行为 |
| `limitations.md` | 8 条边界（verbatim 代理、枚举限制、文档级来源、Holdout 语义等） |
| `baseline.json` | 两层架构执行状态与协议摘要 |
| `manifest.sha256` | 全部产物哈希 |
| `build-evidence.cjs` | 证据生成脚本（可复现） |

## 关键结论

1. 8 类高严重度失败模式（版本现行错误、废止当现行、地区套用、否定翻转、编造答案+未拒答、
   编造引用编号、无引用实质回答被强制替换、注入遵从）全部被确定性检出（零漏检零误报于枚举范围）。
2. 未枚举措辞的否定翻转实测漏检（"允许先组织培训再补签"）——语义级必须人工，已入 limitations。
3. QA 现行 system prompt 无版本/废止/组织归属辨别指令；P9 实测冲突条款几乎全部同时可见，
   辨别责任完全在生成层（无真实模型证据，待授权验证）。
4. AQ09 型证据缺口题：缺失归因系统层而非模型；即使回答包含未进上下文的事实，
   评分器标记泄漏/幻觉观察。

## 复现

```bash
cd ai-collab-backend && bash ./mvnw -B -ntp test -Dtest=P10AnswerQualityOfflineEvaluationTest
node docs/acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/build-evidence.cjs
```

## CI 回执

见本目录 `README.md` 更新段（最终 SHA 推送后补记）。
