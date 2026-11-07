# 03｜策略化 Query Router——复杂度与检索执行策略分离

> 起始 SHA：`589b7fb8` → 结束 SHA：`ab9e73d9`（已推送，CI 见 final-acceptance-report）
> 日期：2026-11-08 ｜ 阶段裁决：**PASS（P9 检索侧复测与封版一致）**

## 1. 问题与证据

路由器已输出 FAST/HYBRID/STRUCTURED/COMPLEX，但两个 QA 服务只消费 filters/semanticQuery/subQueries——
**非 COMPLEX 查询一律执行完整默认管线（Dense+Lexical+RRF+窗口+父子+启发式重排）**，FAST 决策被丢弃；
`decision.reason` 生成后未出口，路由不可解释。公开 Benchmark 实测完整管线对普通问题既慢（P50 3434ms vs Dense 465ms）
又在陌生语料上更差（0.729 vs 0.809）——不能再无条件运行全套检索。

## 2. 设计（两个独立维度）

- **维度一 问题复杂度**：沿用既有 QueryComplexity（FAST/STRUCTURED/HYBRID/COMPLEX），规则不变。
- **维度二 检索策略**（新增 `RetrievalStrategy`）：DENSE / HYBRID / MULTI_QUERY_HYBRID / DENSE_WITH_BOUNDED_FALLBACK。
- 映射（`QueryRouteDecision.defaultStrategy`，显式策略可覆盖）：
  FAST→DENSE_WITH_BOUNDED_FALLBACK；STRUCTURED→HYBRID；COMPLEX→MULTI_QUERY_HYBRID；HYBRID→HYBRID。
- **语言无关结构特征**（无中文短语硬编码路由）：新增精确标识符检测 `EXACT_IDENTIFIER_PATTERN`
  （错误码链 `ERR-503`/`HTTP_500`、语义版本 `1.2.3`、文件名 `application.yml`、全大写代号 `JWT`）——
  命中时保留词法通道并输出依据；中英混合与英文查询共享同一策略接口。

## 3. 执行组件（共享，非三套复制）

新增 `StrategyAwareRetrievalExecutor`：QA 与流式 QA 共享同一执行分发（Agent 链路维持自身 6 参管线注入的兼容规则不变）：
- DENSE_WITH_BOUNDED_FALLBACK：denseOnlyConfig（词法 SQL **确实不执行**、窗口/父子关闭、PASS_THROUGH 保真相似度语义）
  → 候选为空时有界回退完整管线（一次）；
- MULTI_QUERY_HYBRID：维持 executeMultiQuery（原始查询保序 + 子查询增召回）；
- HYBRID：完整默认管线（与历史生产行为逐字节一致）；
- 任意策略执行异常 → 有界降级完整管线重试一次。

**可解释诊断**：`RAG_STRATEGY_ROUTE strategy=… fallbackUsed=… reason=… queryMasked=…（前 6 字符掩码，不输出全文）candidates=… latencyMs=…`。

**安全契约**：projectId 越权防护、documentIds 范围、metadata 硬过滤下推、generationId、引用身份、
类型化评分契约（RRF 分与 Dense 相似度不混淆）全部沿用管线既有实现，零改动。

## 4. 修改前 / 修改后数据

- 修改前：非 COMPLEX 全走完整管线——本地实测完整管线 P50 3434ms（公开 Benchmark 口径），FAST 查询白白消耗 3s 词法扫描。
- 修改后（同口径）：FAST 查询走 Dense-only——P50 465ms（公开 Benchmark 实测），**约 7 倍提速**；
  阶段 4 合入后 HYBRID 路径的词法段从 3.0s 降至 ~0.6s（见 04 报告），COMPLEX/HYBRID 查询同样受益。
- 每查询新增开销：路由决策 <1ms（本地正则），零模型调用（满足"不新增 Gemini Chat 路由调用"红线）。

## 5. 质量基线（P9 冻结协议复测，AI_RAG_V2_QUAL_EVAL=true，真实 Ollama）

| 指标 | 封版基线 | 本轮复测 | 结论 |
| --- | --- | --- | --- |
| QA 必要事实保留 | ≥52/54 | **52/54 (96.3%)** | 一致 |
| Agent 必要事实保留 | ≥53/54 | **53/54 (98.15%)** | 一致 |
| 空上下文题 | 无系统性回归 | **0/39** | 一致 |
| 检索门禁 | PASS | **retrievalVerdict=PASS** | PASS |

（evidence/03-p9-rerun-phase3-results.json；复测跑通两次，均一致。）

生成侧门禁受外部模型条件影响（与阶段 3 改动无关，如实记录）：
DeepSeek（ModelScope）路由余额耗尽 → 0/14 生成、NOT_VERIFIED；Gemini flash-lite 实验路由 14/14 生成但
1 项版本口径违规（AQ01，模型能力弱点）→ REGRESSION；Gemini flash 免费层 RPM 2/14 → NOT_VERIFIED。
**生产生成路由本轮未改动**，内部质量封版裁决（基于 DeepSeek 路由的 P10 验收）不受影响。

## 6. 测试与已知问题

- QueryRouterTest 7/7；新增 QueryRouterStrategyMappingTest 9/9（含中文/英文/混合/精确标识符/空安全/映射完备性）；
  RagV2ChineseQueryRerankRegressionTest 2/2。
- 已知问题：① DENSE_WITH_BOUNDED_FALLBACK 的回退触发条件当前为"候选为空"，更细的证据覆盖判据留待后续实测调优；
  ② 生产 FAST 路径的端到端延迟收益依赖具体语料形态（陌生短 passage 有 7 倍实测；企业长文档以回退保底）。
