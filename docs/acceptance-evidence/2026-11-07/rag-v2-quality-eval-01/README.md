# RAG_V2_QUAL_EVAL-01｜RAG V2 真实质量评测原始证据（2026-11-07）

对应终审报告：`docs/archive/report/2026-11-07-rag-v2-quality-eval-final-report.md`

## 归档清单

| 文件 | 内容 | 产生方式 |
| --- | --- | --- |
| `rag-v2-quality-eval-results.json` | 核心评测原始结果：P9 39 题 × {V1 QA 协议, V2 QA 生产管线, V1/V2 Agent 工具, 5 组消融变体}逐事实归因、多轮追问 2 场景、元数据过滤 2 例、P10 14 题真实生成（DeepSeek-V4.1-Flash-1017, temp=0）、延迟汇总 | `RagV2QualityEvaluationPostgresTest`（`AI_RAG_V2_QUAL_EVAL=true`，Testcontainers pgvector + 本地 Ollama qwen3-embedding:0.6b 1024 维） |
| `p9-protocol-rerun-v2-tree.json` | P9 冻结协议在 V2 主树（HEAD=322ba9f6）复测结果：QA 52/54、Agent 51/54，与 2026-10-26 历史逐题归因 0 差异 | `P9RagGeneralizationPostgresTest`（`AI_REAL_OLLAMA_RETRIEVAL=true`） |
| `p9-protocol-rerun-v1-worktree.json` | P9 冻结协议在 V1 worktree（82078803）复测结果：与 V2 主树复测语义等价（仅 UUID/键序差异） | 同上，worktree `target/rag-v1-baseline` |
| `agent-gate-v2-tree.json` | Agent 检索质量硬门限（D-RAG-01C）：V2 主树 dense 直连路径 Recall@8=@20=0.9545（21/22），超基线下限 20/22 | `AgentRetrievalQualityPostgresTest`（`AI_REAL_OLLAMA_RETRIEVAL=true`） |

## 关键结论（详见终审报告）

1. **V1 协议数字全部复现**：P9 52/54 (96.3%)、Agent 51/54 (94.4%) 与 2026-10-26 历史记录一致且逐题归因零差异，可复现性成立。
2. **V2 生产检索管线存在 HIGH 级缺陷**：`SemanticHeuristicReranker` 对中文查询（含无标点整句与大部分带标点分句）的 `rerankScore`≈rrf×0.35≈0.006，低于装配阈值 `MIN_SIMILARITY=0.15`，候选在装配层被 BELOW_SIMILARITY_THRESHOLD 全量丢弃（39 题共 374 项丢弃），导致 V2 QA 事实保留率 6/54、V2 Agent 工具 0/54、P10 生成阶段 14/14 空上下文拒答。
3. **最小复现测试**：`RagV2ChineseQueryRerankRegressionTest`（离线确定性，随本仓库 dev 分支提交）。
4. dense 直连路径（`DocumentSearchService`）未受影响，硬门限全部达标；缺陷边界精确锁定在 RetrievalPipeline 装配链。

## 评测环境

- Git HEAD：`322ba9f6`（dev）；V1 基线 worktree：`82078803`
- Embedding：Ollama `qwen3-embedding:0.6b`（1024 维，fingerprint 冻结）
- 生成模型：魔搭 ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`（temperature=0，maxOutputTokens=1024，用户指定授权配置；路由配置从生产库 SELECT 快照复制）
- 数据库：Testcontainers `pgvector/pgvector:pg17` 一次性容器（Flyway V1–V80）
- 真实模型调用合计：14 题生成 13 次成功执行（V2 空上下文不消耗调用），Chat P50 延迟 3538ms；无 API Key 落日志
