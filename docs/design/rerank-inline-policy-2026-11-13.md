# Rerank 产品边界：inline 8 / 3s

- 日期：2026-11-13
- 状态：实现完成，离线验收通过
- 范围：用户请求关键路径的 Rerank 候选与 deadline 边界
- 关联：[Rerank 延迟预算](rerank-latency-budget-2026-11-12.md)、[模型配置设计](model-provider-configuration-2026-11-12.md)

## 1. 冻结边界

| 执行档位 | 候选上限 | deadline 上限 | 当前状态 |
| --- | ---: | ---: | --- |
| inline | 8 | 3000ms | 已实现；用户可见关键路径 |
| shadow | 50 | 由部署配置决定，不超过 30000ms | 已实现；只做后台对比 |
| offline | 50 | 能力测试 profile 单独配置，不超过 30000ms | 已实现；管理端离线测试 |
| async | 50 | 未定义 | **`ASYNC_NOT_IMPLEMENTED`** |

20/50 候选不是 inline 档位。当前没有 async 执行器，因此任何超过 8 条候选的
inline 调用都必须拒绝外呼并回退 Java 启发式，不得通过截断列表冒充
“8 候选 inline”，也不得把 20/50 记为 inline PASS。

## 2. 运行时行为

1. `RerankModeRouter` 的 `python` 分支在候选数超过 8 时不调用 worker；
   记录 `rerank.inline.rejected{reason=candidate_limit}` 与
   `rerank.fallback{reason=inline_candidate_limit}`，返回 Java 启发式结果并标
   `rerankerDegraded=true`。
2. inline 请求的 deadline 固定收敛到 `min(配置值, 3000ms)`；调用方不能通过
   配置放大 inline 预算。
3. `ConfiguredReranker` 同样在进入 adapter 前执行 8 候选边界，并把 profile 的
   timeout 收敛到 3000ms，避免绕过 `RerankModeRouter` 的装配路径。
4. `shadow` 继续只读、非阻塞；`offline` 管理端能力测试仍可发送 20/50 候选，
   用于功能性/延迟观测，不构成生产 inline 证据。

## 3. 管理端配置

- RERANK capability profile 继续由管理员配置 `protocol`、`timeoutMs`、
  `maxCandidates`、`batchSize`、`maxLength`、`scoreType`、`topK` 与 fallback；
- profile 先 `DRAFT`，经真实测试后 `TESTED`，再发布；
- assignment 的 `enabled` 默认 `false`，没有启用绑定即回退 Java 启发式；
- 本波不新增 `RERANK_MODE` 默认开关，生产默认仍为 `java`；
- `maxCandidates=20/50` 只允许用于 shadow/offline，不能改变 inline 硬上限。

## 4. 失败语义

- 超过 inline 候选上限：拒绝外呼，回退 Java，不回传部分模型分数；
- worker 超时/5xx/非法响应：沿用既有回退与降级标记，请求不失败；
- 无 async：明确标 `ASYNC_NOT_IMPLEMENTED`，不得排入后台假装已执行；
- 指标与日志不包含 query 全文、候选正文、密钥或 endpoint。

## 5. 验收口径

- Java 定向测试证明 9/20/50 候选均零外呼、回退启发式；8 候选只发送 8 条且
  deadline 为 3000ms；
- 本地真实模型 8 候选 smoke 记录每条响应 8 个有限分数、耗时不超过 3s；
- 20/50 的 inline 状态只能是 `REJECTED` 或 `NOT_INLINE`，不能写 `PASS`；
- 专门 Reranker 的生产用户路径继续保持 `NOT_VERIFIED`，直到另行灰度。

## 6. 明确不做

- 不实现 async 执行器；
- 不改变 3s 门禁；
- 不把 20/50 的 30s functional 结果改写为 inline 证据；
- 不改变 `RERANK_MODE=java` 或其他生产默认开关；
- 不硬编码模型名、endpoint、端口或模型文件路径。
