# RAG V2 P0 质量回归一轮修复｜验收证据（2026-11-07 / rag-v2-quality-regression-fix-01）

## 任务

RAG V2 P0 质量回归（裁决 `RAG_V2_QUALITY_REGRESSION`，见
`docs/archive/report/2026-11-07-rag-v2-quality-eval-final-report.md` HIGH-1）的一轮彻底修复与真实质量复验。
起始基线 `876e7ab5fe9e0028529be365d078d4f144818870`（dev，工作区干净）。

## 复现命令

```bash
# 真实质量评测（Testcontainers PG17+pgvector + 本地 Ollama qwen3-embedding:0.6b + ModelScope DeepSeek-V4.1-Flash 真实生成）
AI_RAG_V2_QUAL_EVAL=true ./mvnw test -Dtest=RagV2QualityEvaluationPostgresTest
# 输出：ai-collab-backend/target/rag-v2-quality-eval/rag-v2-quality-eval-results.json
#      日志：ai-collab-backend/target/rag-v2-quality-eval-run*.log
```

## 文件清单

| 文件 | 内容 |
| --- | --- |
| `rag-v2-quality-eval-results-fixed-final.json` | **最终修复版**完整评测结果（39 题 × 8 路径 + 消融 5 变体 + 多轮 2 组 + 元数据过滤 + P10 真实生成 14 题 + qualityGate） |
| `rag-v2-quality-eval-results-run1-pre-multiquayfix.json` | 迭代 1：评分契约/Selector/RRF/中文分词修复后（QA 48/54，暴露多查询分解合并缺陷） |
| `rag-v2-quality-eval-results-run2-maxrrf-no-primary.json` | 迭代 2：跨子查询最优 RRF 融合（QA 46/54，**质量门禁正确拦截 REGRESSION**，EVAL_EXIT=1） |
| `rag-v2-quality-eval-results-run3-sumrrf.json` | 迭代 3：标准 RRF 求和 + 原始查询入池（QA 51/54，距基线差 1） |
| `final-run-gate-excerpt.txt` | 各轮门禁结论与退出码摘要 |

## 最终指标（fixed-final）

| 指标 | V1 | 修复前 V2 | 修复后 V2 |
| --- | ---: | ---: | ---: |
| QA 必要事实保留率 | 52/54 (96.3%) | 6/54 (11.1%) | **52/54 (96.3%)** |
| Agent 必要事实保留率 | 51/54 (94.4%) | 0/54 (0%) | **53/54 (98.15%)** |
| 39 题空上下文 | 2 | 33 | **0** |
| P10 有证据题进入生成 | 13/14 | 0/14 | **12/12（14/14 次真实模型调用）** |
| 检索 P50/P95 | 82ms / 377ms | 196ms / 607ms | 157ms / 693ms（本地参考值） |

环境：Windows + Docker Testcontainers `pgvector/pgvector:pg17`（Flyway V1–V80 全量迁移）+ 本地 Ollama
`qwen3-embedding:0.6b`（1024 维，fingerprint 冻结）+ ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`
（temperature=0，密钥零落日志，路由快照从本地库只读复制）。冻结语料 manifest sha256 校验通过，未改动。
