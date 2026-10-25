# P9-RAG-GENERALIZATION-01 验收证据

> 任务：独立 RAG 泛化评测基准建设与真实本地基线测量。
> 基线 SHA：`3891171ecfc81a3929993f9f77989fed61ea5c85`（dev，2026-10-26）。
> 裁决：`P9_GENERALIZATION_BASELINE_ACCEPTED`（基准建设）；真实 Chat 生成质量 `REAL_GENERATION_NOT_EXECUTED`。

## 文件清单

| 文件 | 内容 |
| --- | --- |
| `corpus-manifest.json` | 冻结语料清单（23 篇，SHA-256 与 code point 数；22 篇常驻 + 注入样本按 Q13 协议临时索引） |
| `question-manifest.json` / `question-manifest-holdout.json` | 冻结问题清单（dev 21 / holdout 18，54 条必答事实 + 7 条干扰条款标注） |
| `holdout-integrity.json` | Holdout 方法学保留集冻结（文档与问题文件 SHA-256，先于测量冻结） |
| `retrieval-results.json` | 主测量原始输出（与 `ai-collab-backend/target/p9-rag-generalization-01/` 逐字节一致） |
| `baseline.json` | 新独立题集基线摘要（环境、路径参数、汇总、跨运行稳定性） |
| `qa-evidence-results.json` / `agent-evidence-results.json` | 按路径拆分的逐题证据（QA / Agent 真实工具） |
| `metrics.json` | 全部指标（Recall@8/@20、双路径保留、完整覆盖、按集合/维度/长度/跨文档数/事实数分组、冲突暴露、false evidence、失败归因） |
| `failure-analysis.md` | 失败分层归因与机制分析 |
| `regression-results.json` | P4～P8 旧冻结集回归对比（同 SHA 重跑 P6，逐事实零差异，REGRESSION_CLEAN） |
| `cost-and-latency.json` | 索引与逐题延迟/上下文成本（code points 口径） |
| `source-manifest.json` | 评测实现与文档的版本绑定 SHA-256 |
| `manifest.sha256` | 本目录全部产物的 SHA-256 |
| `generate-manifests.cjs` | 语料/问题清单生成与 marker 逐字校验（可复现） |
| `compute-metrics.cjs` / `finalize-evidence.cjs` | 指标聚合与证据收口脚本（只读测量输出，可复现） |

## 关键数字

- 语料 23 篇 / 175 chunks / 约 2.15 万 code points 索引输入；39 题（dev 21 + holdout 18）/ 54 条必答事实。
- QA 52/54（96.3%，完整覆盖 35/37 题）；Agent 真实工具 51/54（94.4%，完整覆盖 34/37）；Agent limit=12 对照 53/54。
- Recall@8 53/54、Recall@20 54/54；来源身份 0 违规、范围越界 0、BODY_TRUNCATION 0。
- dev 与 holdout 表现一致（QA 31/33 vs 21/21；Agent 31/33 vs 20/21），详见 `metrics.json` bySet。

## 复现方式

```bash
# 主测量（本地 Docker + Ollama，显式 opt-in）
cd ai-collab-backend
AI_REAL_OLLAMA_RETRIEVAL=true bash ./mvnw -B -ntp test \
  -Dtest=P9RagGeneralizationPostgresTest -Drag.baseline.sha=<HEAD>
# 回归保护（旧冻结集）
AI_REAL_OLLAMA_RETRIEVAL=true bash ./mvnw -B -ntp test \
  -Dtest=P6RagQualityBaselinePostgresTest -Drag.baseline.sha=<HEAD> \
  -Drag.baseline.output=target/retrieval-baseline/p6-rag-quality-01/p9-regression-run.json
# 指标与证据
node docs/acceptance-evidence/<date>/p9-rag-generalization-01/compute-metrics.cjs
node docs/acceptance-evidence/<date>/p9-rag-generalization-01/finalize-evidence.cjs
```

默认 CI（无 `AI_REAL_OLLAMA_RETRIEVAL`）中该测试类显式跳过（本地验证：Tests run: 1, Skipped: 1）。

## 边界

- 不调用外部 Chat/付费模型；生成层结论恒为 `REAL_GENERATION_NOT_EXECUTED`。
- 拒答题的干扰文档进入上下文属工具 RELEVANT_EXCERPTS_ONLY 语义，未判为缺陷（见 failure-analysis.md §6）。
- 注入样本仅在 3 道归属题期间临时索引（Q13 协议），其余题目候选竞争不含注入样本。
