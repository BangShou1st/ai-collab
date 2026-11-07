# RAG V2 Gemini 双模型接入与智能检索优化专项（RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01）

> 日期：2026-11-07 ~ 11-08 ｜ 分支 dev ｜ 最终裁决：`OPTIMIZATION_PARTIAL`（详见 final-acceptance-report.md）

## 报告导航

| 文件 | 内容 |
| --- | --- |
| `00-baseline-and-git-audit.md` | Git 同步/安全审查 + 14 个关键类现状审查 |
| `01-gemini-chat-integration.md` | 阶段 1：thinkingConfig 能力位契约 + 错误语义修复 + 8 场景真实冒烟 |
| `02-gemini-embedding-ab-evaluation.md` | 阶段 2：原生 batchEmbedContents 协议接入 + A/B（配额阻断 NOT_VERIFIED） |
| `03-adaptive-query-router.md` | 阶段 3：复杂度×检索策略分离、共享执行组件、路由诊断 |
| `04-lexical-sql-performance.md` | 阶段 4：词法 SQL 索引化（语义等价 diff=0，6 倍提速） |
| `05-fusion-ablation.md` | 阶段 5：RRF 有据不改动决策记录 |
| `final-acceptance-report.md` | 最终裁决、逐阶段 SHA/CI/前后指标、已知问题 |
| `evidence/` | 冒烟指标、P9 复测原始结果等 |

## 提交链（dev，未合并 main，未部署）

```
40c0bde4  基线推送（公开 Benchmark 评测）
a1a3ec3f  阶段 1 Gemini Chat 接入优化
589b7fb8  CI 门控修复 + 阶段 2 嵌入协议代码
ab9e73d9  阶段 3 策略化 Query Router
1015eba0  阶段 4 Lexical SQL 索引化
```

## 复现要点

### 阶段 1 冒烟（真实 Gemini 免费 API，经系统代理）

```bash
cd ai-collab-backend
AI_GEMINI_LIVE_TESTS=true GEMINI_API_KEY=<key> ./mvnw test -Dtest=Gemini35FlashLiveSmokeTest
# 离线契约：-Dtest='GeminiThinkingPolicyContractTest,GeminiTurnContractTest,GeminiStreamContractTest'
```

### 阶段 2 A/B（配额恢复后续跑，checkpoint 保留）

```bash
docker run -d --name ai-collab-bench-pg -p 5433:5432 \
  -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench pgvector/pgvector:pg17
AI_GEMINI_EMBED_AB=true AI_DU_BENCH_DB_URL='jdbc:postgresql://127.0.0.1:5433/bench' \
AI_DU_BENCH_DB_USER=bench AI_DU_BENCH_DB_PASSWORD=bench GEMINI_API_KEY=<key> \
./mvnw test -Dtest=GeminiEmbeddingABBenchmarkPostgresTest
```

### 阶段 3/4 验证

```bash
AI_RAG_V2_QUAL_EVAL=true ./mvnw test -Dtest=RagV2QualityEvaluationPostgresTest   # P9 冻结协议（真实 Ollama）
./mvnw test -Dtest='QueryRouterTest,QueryRouterStrategyMappingTest,RetrievalQualityHardGatePostgresTest'
# 词法等价性对照与 EXPLAIN：见 04 报告（隔离库 psql + SET pg_trgm.similarity_threshold=0.03）
```

## 出站与许可边界

- Gemini 免费 API 仅接收：公开 Benchmark 语料（DuRetrieval 查询与抽样文本）、P9 合成语料；无任何用户/企业私有数据外发。
- 生成/嵌入密钥经环境变量注入，零落仓库/日志/报告。
- 生产数据库、Flyway 历史迁移、P9/P10 冻结资产、生产模型路由：零改动。
