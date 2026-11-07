# 00｜基线与 Git 审查（RAG V2 Gemini 自适应检索优化专项）

> 任务：RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01 ｜ 日期：2026-11-07

## 1. Git 状态核查（实测）

| 项 | 值 |
| --- | --- |
| 本地分支 | dev |
| 本地 HEAD | `40c0bde4ea25556187f4f368b03ef7f8205702cf` |
| 推送前 origin/dev | `39d1603dc1961907144e654bcf15e1042ffef00b` |
| 未推送提交 | `75fad312`（P10 封版）、`40c0bde4`（公开 Benchmark 评测），共 2 个，49 文件，+105,670/-85 |
| 分叉 | 无分叉（本地纯领先） |
| 安全扫描 | `git diff origin/dev..HEAD` 密钥模式扫描 0 命中；49 文件逐类核对：仅测试 harness、评测报告/清单（含 SHA256 manifest、查询与标准答案元数据），无第三方语料全文、无明文密钥、无临时缓存 |
| 推送 | 已正常 `git push origin dev`：`39d1603d..40c0bde4`，远端核实 `40c0bde4ea25556187f4f368b03ef7f8205702cf` |
| CI | push 触发 GitHub Actions CI（run 42371367155），结果见 final-acceptance-report |

## 2. 现状审查结论（代码事实，含 文件:行 引用）

### 已存在且已实现（复用，不重写）
- **GeminiModelAdapter**（infrastructure/ai/model/GeminiModelAdapter.java）：普通 Chat（:35）、流式 SSE（:44-106，alt=sse）、turn() 工具调用链（functionDeclarations :270、functionCall 解析 :142-163、functionResponse 回填 :242、thought signature 4 种字段名变体 :149-158/:237-239、thought part 过滤 :139）、finish reason 全量映射（:337-354）、usage/modelVersion 透传（:76-85/:167/:176-182）、JSON 结构化输出（responseMimeType+responseJsonSchema :296-301）、模型 ID endpoint 占位符（:360-367）。认证 `x-goog-api-key`（:356-358），兼容新式 `AQ.` Key。契约测试 GeminiTurnContractTest / GeminiStreamContractTest / GeminiLiveAcceptanceTest 已存在。
- **错误映射与超时**在公共层 JsonHttpModelClient（429/401/403 映射 :368-387、deadline+cancel :217-227/:327-333），非 Gemini 特有，无需动。
- **ModelProviderType.GEMINI 在列**；ProviderProfileCatalog GEMINI 档位（/v1beta/models/{model}:generateContent）已存在（:78-84）。
- **Embedding 多代次机制完整**：EmbeddingIndexService 状态机（QUEUED→BUILDING→READY→ACTIVE/RETIRED，最多 3 代退役 :41-42）、generation_id 隔离、激活时 base_fingerprint/base_generation_id 校验（:144-149）。ProjectEmbeddingGateway 批处理+3 次退避重试（5xx 才重试，401/403/429 直接抛 :150-155）、Gemini OpenAI 兼容端点 index 缺失时按序对齐的兼容逻辑（:126-141）。
- **检索 SQL**：Lexical（DocumentMapper.java:331-420，GREATEST(similarity, heading ILIKE 0.8, content ILIKE 0.6) 评分 + `content % q OR similarity>0.03 OR ILIKE` 命中条件）带 generation_id/scope/connector 授权/metadata 硬过滤下推；Dense（:229 起，cosine + provider/model/dimension/fingerprint/generationId 过滤）。V78/V79 已建 content/heading GIN trgm 索引与 metadata GIN。
- **P10 安全护栏**：KnowledgeCitationValidator、KnowledgeOutputSafetyGuard、P10AnswerQualityScorer/AbstentionDetector/InjectionComplianceJudge 及 GeminiLiveAcceptanceTest 的 phase3/5。

### 配置支持但未验证 / 缺口（本轮工作面）
1. **gemini-3.5-flash 真实调用未验证**：适配器存在，但该新模型从未在本机实测（冒烟测试是阶段 1 主体）。
2. **thinkingConfig（thinkingBudget）未发送**；thought signature 仅 turn() 链路处理，旧 complete/stream 链路未回填（:330 只过滤 thought part）——是否构成缺陷以真实验测为准。
3. **Gemini Embedding 完全没有实现**：SystemEmbeddingService provider 白名单只有 OPENAI_COMPATIBLE/OLLAMA（:197）；ProjectEmbeddingGateway 仅有 OpenAI 兼容 /embeddings 协议，**task type（RETRIEVAL_DOCUMENT/RETRIEVAL_QUERY）不可传**；fingerprint=sha256(provider|model|dimensions) 不含任务类型配对/协议版本。需要最小新增（白名单+原生协议适配+指纹扩展），并保持旧代次兼容。
4. **路由决策未生效于执行策略**：QueryRouter 输出 FAST/HYBRID/STRUCTURED/COMPLEX + reason + subQueries，但 KnowledgeQuestionApplicationService:144-160（流式 :168+）只消费 filters/semanticQuery/subQueries——**非 COMPLEX 一律执行完整默认管线（Dense+Lexical+RRF+窗口+父子+重排）**；decision.reason 未输出，无路由级诊断。
5. **Lexical SQL 性能问题**（结合公开 Benchmark：A2 P50 3032ms）：`similarity(content,#{q})>0.03` 与 `ORDER BY similarity` 无法利用 GIN trgm 索引（仅支持 %/ILIKE 谓词），疑似强制 Seq Scan——阶段 4 用 EXPLAIN (ANALYZE, BUFFERS) 定证。
6. **多查询执行路径**：executeMultiQuery 已存在且被 COMPLEX 消费（两 QA 服务），Agent 链路（KnowledgeSearchAgentTool:137）只取 filter/semanticQuery 不分解——保持。

### 完全不需要动
- Anthropic/端砚（OpenAI 兼容自定义路由）/ModelScope/Ollama 适配与 Provider 支持。
- 数据库历史迁移（V1–V80）；如需索引变更仅新增迁移。
- P10 评分器与安全护栏；KnowledgeCitationValidator 契约。
- 检索管线组件本身（DenseRetriever/LexicalRetriever/RRF/扩展器）——阶段 3 通过 PipelineConfig 组合调用，不改组件内部。

## 3. 数据出站边界（本轮约束的执行声明）

Gemini 免费层仅接收：公开 Benchmark 语料（DuRetrieval 查询与抽样文档）、P9 合成语料、官方 RGB 抽样文本。任何用户私有/企业数据不做外发；生产库零操作。

## 4. 历史质量基线（保护目标）

- P9：QA ≥52/54、Agent ≥53/54；P10：14/14 有效回答、高严重度违规 0。
- 公开 Benchmark 既有结论独立归档（docs/benchmark/2026-11-07-rag-v2-public-benchmark/），本轮 A/B 另设独立冻结子集，不用 300 题调参后同题自证。
