# W3.1 测量 A：rerank 服务开销（stub 基准）证据

> 日期：2026-11-12
> 契约：[W3 收尾冻结](../../../archive/handoffs/w3-final-freeze-20261112.md) §1.7
> 口径来源：[Rerank 延迟预算与可观测设计](../../../design/rerank-latency-budget-2026-11-12.md) §2/§3/§4.1
> 代码：`dev`（W3.1 合入后：Java `363202e5`、Python `5de52d3a`…`594efed0`）
> **证据等级：TESTED（stub）**。不是真实模型环境的 LIVE_VERIFIED。

---

## 1. 测什么

门禁量是 **`T_overhead = max(0, T_total_java − T_model)`**（不含模型调用的服务开销）。

stub 基准把 `T_model` 固定为 0（本地 stub Java 网关返回 `latencyMs=0`），因此
**`T_overhead == T_total`**，测到的就是"Java 之外 / 模型之外"的全部服务开销：
worker HTTP 往返、Prompt 构建、序列化、解析、打分映射。

## 2. 怎么跑

```bash
cd ai-collab-ai-worker
uv sync --frozen
uv run python tools/rerank_stub_bench.py --output target/rerank-stub-bench.json
```

脚本会：起本地 stub Java 网关（`127.0.0.1:9199`）→ 起本机 worker（`127.0.0.1:9100`，
`AI_WORKER_JAVA_GATEWAY_URL` 指 stub）→ 对冻结集逐档跑
`ai-eval rerank --scorer worker --candidate-target {8,20,50} --measure-latency --runs 34 --warmup 0`。

- 冻结集：`tests/fixtures/rerank_set/rerank_baseline.v1.jsonl`（15 条，6–10 候选）；
- 每档样本：15 × 34 = **510**（`confidence=HIGH`）；
- 候选 20/50 由 `--candidate-target` 以确定性合成候选补齐（`labels=0`、`supports=false`），
  因此**补齐档的 NDCG/支持率不具可比性**，只用于延迟。

## 3. 结果（2026-11-12 03:45 +08:00）

| 候选数 | 样本 | P50 | P95 | P99 | overhead P95 | overhead P99 | confidence | 判定 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| 8 | 510 | 6.294 ms | 10.351 ms | 11.736 ms | 10.351 ms | 11.736 ms | HIGH | **PASS** |
| 20 | 510 | 12.943 ms | 32.739 ms | 36.184 ms | 32.739 ms | 36.184 ms | HIGH | **PASS** |
| 50 | 510 | 29.349 ms | 34.861 ms | 39.984 ms | 34.861 ms | 39.984 ms | HIGH | **PASS** |

门禁：overhead P95 ≤ **800 ms**、P99 ≤ **1500 ms**（P99 为工程护栏）。
**三档均通过，余量 ≥ 20 倍。**

原始证据：[`data/rerank-stub-bench.json`](data/rerank-stub-bench.json)
（sha256=`f1cb1d1f26b0fe08493a5a5b23a10bcf2db1370cec0b962bbe788ca5a8db02f2`）。

## 4. Java 侧（A-Java）独立证据

`RerankOverheadBudgetTest`（`mvnw test`，内嵌 `com.sun.net.httpserver` stub worker，
真实 `RerankGatewayClient` + `RerankModeRouter` in `python` 模式）：

```
[overhead-budget] candidate_counts=8/20/50 samples=510 p50=3.007ms p95=5.130ms p99=6.305ms
gate=P95<=800ms,P99<=1500ms
```

- 510 样本 = 8/20/50 各 170 次（预热 12 次不计）；
- 另断言：响应缺少 `modelLatencyMs` 时 `known=false`、不产生门禁样本；
- 说明 Java 侧 `rerank.overhead` / `rerank.model_latency_known` 仪表按契约工作。

## 5. 结论与限定

- **W3.1 测量 A：PASS（TESTED，stub）**——本机确定性 stub 下服务开销远低于 800 ms；
- **测量 B（staging 真实模型）：NOT_VERIFIED**——本会话环境无 instabox/staging 访问
  （`~/.ssh/config` 无对应主机、无 instabox CLI），无法产生真实模型样本；
- 因此：**W3 的 P95 门禁不能宣称"已通过"**，只能写"overhead 在 stub 下达标、真实模型未测"；
- 不改变 `rerank.mode=java` 默认值；stub 结果不构成切换 `python` 的依据。
