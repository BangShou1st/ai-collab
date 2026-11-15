# Rerank 产品边界修订：inline 8 / 5s

- 日期：2026-11-15
- 状态：已实现；取代 [2026-11-13 的 8 / 3s 冻结边界](rerank-inline-policy-2026-11-13.md)
- 范围：用户请求关键路径的 Rerank 候选与 deadline 边界
- 关联：[Rerank 延迟预算](rerank-latency-budget-2026-11-12.md)、[模型配置设计](model-provider-configuration-2026-11-12.md)

## 1. 修订结论

| 执行档位 | 候选上限 | deadline 上限 | 当前状态 |
| --- | ---: | ---: | --- |
| inline | 8 | 5000ms | 已实现；用户可见关键路径 |
| shadow | 50 | 由部署配置决定，不超过 30000ms | 已实现；只做后台对比 |
| offline | 50 | 能力测试 profile 单独配置，不超过 30000ms | 已实现；管理端离线测试 |
| async | 50 | 未定义 | **`ASYNC_NOT_IMPLEMENTED`** |

候选上限仍为 8；只把 inline deadline 从 3000ms 放宽到 5000ms。20/50 候选仍然
不得进入 inline，不得通过截断列表冒充 8 候选，也不得把 shadow/offline 结果记为
inline PASS。

## 2. 修订原因

2026-11-15 在 4 vCPU 的 staging 主机上用 Qwen3-Reranker-0.6B Q8_0 实测：

| 配置 | 6 候选单请求 | 4 候选单请求 | 两路并发 6 候选 |
| --- | ---: | ---: | ---: |
| 3 线程 / 4 slot / 2k 上下文 | 2.22–2.36s | 1.60s | 4.72s / 2.70s |
| 4 线程 / 6 slot / 2k / flash-attn | 1.78–1.87s | 1.21s | 3.78–3.96s |

3s 边界在并发时会把本来能完成的请求稳定误杀，触发 Java 启发式回退；5s 边界
能覆盖两路并发（约 3.8s）并保留硬超时兜底。4 线程/6 slot 同时把单请求从
约 2.3s 降到约 1.8s，因此 5s 不是单纯放大等待，而是配合真实吞吐提升后的
并发余量。

## 3. 运行时行为

1. `RerankExecutionPolicy.inlineDeadlineMs` 将配置值收敛到 `min(配置值, 5000ms)`。
2. `ConfiguredReranker` 仍只处理最多 8 个候选；profile 的 `timeoutMs` 建议配置
   为 4500ms，留出 HTTP/序列化余量。
3. staging 的 llama.cpp reranker 使用 `-t 4 -np 6 -c 2048 -b 512 -ub 512 -fa on`，
   容器 CPU 上限 4.0；健康检查固定探测 `18081/health`。
4. `RerankModeRouter` 的 python 分支仍使用自己的 `rerank.deadline-ms`，不因本修订
   自动放大；它只受同一 5s 硬上限约束。

## 4. 多查询 rerank 成本

QueryPlan 产生 4 个子查询时，历史实现让原始查询 + 4 个子查询各自走完整管线，
产生 5 次顺序模型 rerank（staging 实测合计约 9.2s）。新增
`retrieval.multi-query.rerank-primary-only=true` 后：

- 主查询仍走完整管线并执行 configured rerank；
- 子查询改用 PASS_THROUGH，只负责增召回，不再调用模型；
- 融合仍保持“主查询排名序置前 + 子查询独有候选按 RRF 增补”的既有语义。

该开关默认 `false` 保留历史行为，staging 通过
`RETRIEVAL_MULTI_QUERY_RERANK_PRIMARY_ONLY=true` 显式启用。

## 5. 失败语义（不变）

- 超过 inline 候选上限：拒绝外呼，回退 Java，不回传部分模型分数；
- worker 超时/5xx/非法响应：回退 Java 并标记 `rerankerDegraded`，请求不失败；
- 无 async：明确标 `ASYNC_NOT_IMPLEMENTED`；
- 指标与日志不包含 query 全文、候选正文、密钥或 endpoint。

## 6. 验收口径

- `RerankExecutionPolicyTest` 证明 4500/5000ms 被接受、9000ms 收敛到 5000ms；
- 管理端能力测试 6 文档成功，模型耗时约 2.2s（3 线程旧配置）或约 1.2–1.8s
  （4 线程新配置）；
- staging 在线 RAG 在 5s 内完成 rerank，超时回退仍可观测且回答不失败；
- QueryPlan 多查询场景的模型 rerank 调用次数从 5 次降到 1 次；
- 20/50 候选的 inline 状态仍只能是 `REJECTED` 或 `NOT_INLINE`。
