# Rerank 延迟预算与可观测设计（W3.1）

- 日期：2026-11-12
- 状态：设计定稿，待 W3.1 实施
- 关联：[Python AI 能力平面](python-ai-capability-plane-2026-11-11.md) §6.3（P1）、§10.2（灰度门禁）、§11（成本与容量）；[J3 rerank 报告](../archive/report/j3-rerank-20261112.md) §4.3、§7.5、§11
- 结论：**P1 的 `P95 ≤ 800ms` 定义为「不含模型调用的服务开销」**；模型调用的延迟**不进门禁**，但必须单独可观测、可告警。

---

## 1. 为什么这样定

1. **模型延迟由 Provider/网络决定，工程不可控**。W3 staging 实测真实模型 8–9 候选就要 2–5s（`DeepSeek-V4.1-Flash-1017`，P50≈2s，P95≈5s）。把模型算进 800ms，门禁永远不可达，且无法区分「我们的代码退化」与「模型变慢」。
2. **设计 §11 原本就写了「单轮额外开销目标 P95 ≤ 150ms（不含模型）」**。P1 的 800ms 是服务层的宽松上限，150ms 是 worker 内部的工程目标，两者都不含模型。
3. **门禁要衡量的是我们控制的东西**：Java→worker HTTP、worker 内 Prompt 构建/解析/序列化、worker→Java 网关 HTTP、Java 路由/排序。模型慢不是不观测——它有独立的 SLO 和告警。
4. **不通过改门禁数值、缩减候选数来「凑绿」**。W3 报告已经明确拒绝这种做法，本设计保持一致。

---

## 2. 四个时间量（唯一定义）

| 符号 | 定义 | 由谁测量 | 说明 |
| --- | --- | --- | --- |
| `T_total_java` | `RerankModeRouter` 进入 → 返回排序结果给检索管线的总耗时 | Java（`rerank.total.duration`） | 只在 `python` 模式下作为门禁样本；`shadow` 的用户可见耗时是 Java 路径，另测 |
| `T_model` | Java 内部网关返回的 `latencyMs`（`ChatCompletionResult.latencyMs`，Provider 调用耗时） | Java（`rerank.model.duration` / `InternalModelCallResponse.latencyMs`） | 不含 Java 网关队列与 worker 传输；缺失时标 `UNKNOWN`，不填 0 |
| `T_overhead` | `max(0, T_total_java − T_model)`；仅当 `T_model` 已知时计算 | Java（`rerank.overhead`） | **P1 门禁量**；`model_latency_known=false` 的样本不计入门禁，但计入 unknown 比例 |
| `T_worker_internal` | worker 端点内部：Prompt 构建 + 解析 + 序列化 + 本地逻辑，**不含**调 Java 网关的 HTTP 等待 | Python（分阶段 histogram） | 工程目标 P95 ≤150ms；单独告警，不是 800ms 门禁 |
| `T_worker_total` | worker 端点总耗时（含 gateway HTTP） | Python（`ai_worker_rerank_duration_seconds`） | 辅助诊断，不单独做客 |
| `T_shadow` | shadow 后台对比耗时 | Java（`rerank.shadow.latency`） | 只观测，不参与用户可见延迟与门禁 |

> 时钟口径：`T_total_java` 与 `T_model` 都在 Java 进程内用单调时钟测量，避免跨进程时钟偏差；`T_worker_*` 只在 worker 内做相对比较。

---

## 3. 指标与门禁

| 指标 | 含义 | 是否门禁 | 判定/采样 |
| --- | --- | --- | --- |
| `rerank.overhead`（P95） | 服务开销（不含模型） | **✅ P1 门禁** | `python` 模式、成功响应、`model_latency_known=true`，≥200 请求（stub 模式 ≥500）时 P95 ≤ **800ms** |
| `rerank.worker.internal`（P95） | worker 内部处理 | ❌ 工程目标 | 目标 ≤ **150ms**；超过告警，不直接否决 P1 |
| `rerank.model.duration`（P95） | 模型调用延迟 | ❌ 观测/SLO | 告警阈值：P95 > **5s** 持续 10 分钟；P99 > 10s 严重告警 |
| `rerank.total.duration`（P95） | 端到端总耗时 | ❌ 观测 | 只进报告/看板，用于产品评估；不用来判 P1 |
| `rerank.failure.rate` | 失败率（5xx/超时/非法响应） | ✅ 稳定性门禁 | ≤ **1%**（滚动 10 分钟） |
| `rerank.model_latency_known_ratio` | `T_model` 可用的比例 | ✅ 门禁前置 | ≥ **99%**；低于则门禁样本无效并告警 |
| `rerank.shadow.topk_overlap` / `score_delta` / `failure` / `latency` | shadow 对比质量/成本 | ❌ 观测 | 3–7 天窗口、≥100 次 shadow 调用后评估 |
| `ai_worker_rerank_duration_seconds` | worker 端点总耗时 | ❌ 观测 | 与 `T_worker_total` 对齐 |
| `ai_worker_rerank_gateway_seconds` | worker→Java 网关 HTTP 耗时 | ❌ 观测 | 用于定位开销在传输还是计算 |
| `ai_worker_rerank_parse_seconds` | 模型输出解析耗时 | ❌ 观测 | 用于定位解析退化 |

**P1 判定规则**：

- `python` 模式只有在 **P95(`T_overhead`) ≤ 800ms**、**失败率 ≤1%**、**`model_latency_known_ratio` ≥99%** 三条同时满足时才允许作为默认；否则保持 `rerank.mode=java` 或 `shadow`。
- 模型 P95 >5s 只告警，不直接判 P1 失败；但如果产品要求用户可见 P95，则 `python` 模式需另做产品决策（换低延迟模型 / 只开 shadow / 移出关键路径）。
- 任何样本不足 200 的 P95 只能标 `LOW_CONFIDENCE`，不得当门禁结论。

---

## 4. 测量协议（从便宜到真实）

### 4.1 A：Stub 基准（主门禁，确定性）

- worker 的 `AI_WORKER_JAVA_GATEWAY_URL` 指向本地 **stub Java 网关**：固定返回合法 `content` 与 `latencyMs=0`，不调真实模型。
- 用 `ai-eval rerank --scorer worker --worker-url http://127.0.0.1:9100 --measure-latency --runs N`，覆盖候选数 8 / 20 / 50；15 个冻结 case × N 次，N ≥ 34（≥500 样本）。
- 读取 worker `ai_worker_rerank_duration_seconds` 与 Java `rerank.overhead`（stub 下 `T_model=0`，`T_overhead≈T_total`）。
- 判定：P95 ≤800ms；P99 ≤1500ms（建议的工程护栏，不是硬门禁）；同时输出 `T_worker_internal` P95。

### 4.2 B：Staging 真实模型（观测，不判 P1）

- 真实 worker + 真实 Java 网关 + 真实 Provider；用同一 `ai-eval` 对同一冻结集跑 `--runs 3–5`（或 `--runs 20` 若成本允许）。
- 同时抓 Prometheus：`rerank.model.duration`、`rerank.total.duration`、`rerank.overhead`、`rerank.failure`。
- 报告必须分三行：**overhead P95（门禁）**、**model P95/P99（观测）**、**e2e P95/P99（观测）**；不得混成一个数字。
- 若真实模型 P95 仍 >5s：在报告中写明「模型侧 SLO 未达」，并给出「换低延迟模型 / 长期只开 shadow / 移出关键路径」三个选项，不改门禁。

### 4.3 C：Shadow（观察期）

- `RERANK_MODE=shadow`，采样 10%–20%（有流量时），跑 3–7 天；只统计 `topk_overlap`、`score_delta`、`failure`、`shadow latency`。
- shadow 的 `T_shadow` 是后台耗时，**不进用户可见延迟**；用户可见延迟仍是 Java 路径耗时。

### 4.4 D：生产

- 只在 A + B 都达标且产品确认后，才允许 `rerank.mode=python`。
- 生产看板固定四个面板：overhead P95、model P95、e2e P95、failure/partial；模型 P95 告警独立于工程门禁。

---

## 5. 仪表落点（W3.1 施工）

### Java

- `RerankModeRouter`：`rerank.total.duration{mode,outcome}`、`rerank.overhead{mode,model_latency_known}`（仅 `python` 成功样本）、日志 `call_id/mode/total_ms/model_ms/overhead_ms/candidates/outcome`。
- `RerankGatewayClient`：`rerank.worker.duration{outcome}`，记录 HTTP 往返与重试次数（tag 不加大基数）。
- `InternalModelCallController`：`rerank.model.duration{provider,model,outcome}`，`latencyMs` 写入响应与指标；`model` 只取低基数别名，不取完整模型名。
- 指标不得改变业务结果（沿用 `PromptCatalogMetrics` 的异常吞掉约定）。

### Python

- `api/rerank.py`：`ai_worker_rerank_duration_seconds{outcome}`（已有）、`ai_worker_rerank_gateway_seconds{outcome}`、`ai_worker_rerank_parse_seconds`；`ai_worker_rerank_missing_scores_total` 保留。
- `clients/java_gateway.py`：只记录耗时/状态/错误类型，不打印 token、Prompt、候选正文。
- `ai-eval rerank`：输出 `aggregate.overheadP95Ms`、`modelP95Ms`、`totalP95Ms`、`samples`、`confidence`；`--scorer worker` 读取响应里的 `latencyMs`。

### 看板与告警

| 面板 | PromQL 示例 |
| --- | --- |
| overhead P95 | `histogram_quantile(0.95, sum(rate(rerank_overhead_seconds_bucket[5m])) by (le, mode))` |
| model P95 | `histogram_quantile(0.95, sum(rate(rerank_model_duration_seconds_bucket[5m])) by (le, model))` |
| e2e P95 | `histogram_quantile(0.95, sum(rate(rerank_total_duration_seconds_bucket[5m])) by (le, mode))` |
| failure/partial | `sum(rate(rerank_requests_total{outcome=~"model_error\|deadline"}[5m])) / sum(rate(rerank_requests_total[5m]))` |

告警：
- `rerank_overhead_p95 > 0.8` 持续 10 分钟 → 工程侧 P1 告警，保持 `java` 模式；
- `rerank_model_p95 > 5` 持续 10 分钟 → 模型侧告警（不自动切模式）；
- `failure_rate > 0.01` 持续 10 分钟 → 稳定性告警；
- `model_latency_known_ratio < 0.99` → 观测缺口告警。

---

## 6. 示例（帮助理解）

| 场景 | T_total | T_model | T_overhead | 判定 |
| --- | ---: | ---: | ---: | --- |
| worker 总 2.50s，模型 2.40s | 2.50s | 2.40s | **0.10s** | overhead P95 达标；e2e 2.5s 仅观测 |
| worker 总 5.05s，模型 5.00s | 5.05s | 5.00s | **0.05s** | overhead 达标；模型 P95 5s 触发模型告警，产品决定是否接受 |
| worker 总 1.20s，模型 0.10s | 1.20s | 0.10s | **1.10s** | overhead 超标，先修 worker/传输，不能切 python |
| 模型 latency 缺失 | 1.00s | UNKNOWN | UNKNOWN | 不计入门禁；unknown 比例上升即告警 |

---

## 7. W3.1 施工范围（只做可观测，不改行为）

1. Java / Python 增加上述指标与日志（无业务行为变化）。
2. `ai-eval rerank` 增加 `overhead/model/total` 三项 P95 输出与样本/置信度字段。
3. 增加本地 stub 网关工具与 `--runs` 基准脚本（不出现在生产镜像）。
4. 在 staging 跑 A（≥500 stub 样本）与 B（真实模型，样本量记录）并写报告 `w3.1-rerank-latency-<YYYYMMDD>.md`。
5. 更新本设计文档与主设计 §6.3/§11 的口径引用。
6. **不做**：不改 `rerank.mode` 默认值（保持 `java`）、不改 prompt、不删 `NeuralModelReranker`、不切 `python`。

---

## 8. 与旧报告的关系

W3 报告里的 `worker-e2e P95 5054ms` 是「含模型」的观测值，不是本设计的 800ms 门禁。按本设计，W3 的 P95 门禁状态应重测为：

- `overhead`：**待测**（W3 未单独测量 `T_overhead`，标 `NOT_VERIFIED`）；
- `model`：**已观测**（P95≈5s，属模型侧）；
- `e2e`：**已观测**（P95≈5.05s）。

在 W3.1 完成 overhead 测量前，**不得把 W3 的 P95 写成 PASS 或 FAIL 的最终结论**；只能写「旧口径 NOT_MET，新口径待测」。
