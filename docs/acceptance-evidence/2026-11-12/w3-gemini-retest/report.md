# S1.5 Gemini 真实 provider 重测（thinking 关闭）

- 日期：2026-11-12（Asia/Shanghai）
- 环境：instabox `box-aae54f` staging（compose `ai-collab-deploy`，app `ai-collab-app` 8080/8081，worker `ai-collab-ai-worker-api` 9100 仅 `ai` 网）
- Provider：**GEMINI** · `https://generativelanguage.googleapis.com` · `/v1beta/models/{model}:generateContent`
- 模型：**`gemini-3.1-flash-lite`**
- thinking：**配置级关闭**（provider `capabilities=["CHAT","STREAMING"]`，不含 `THINKING` → `GeminiModelAdapter` 下发 `thinkingConfig.thinkingBudget=0`）
- 证据等级：**LIVE_VERIFIED (staging, real provider)**，门禁样本 `LOW_CONFIDENCE`
- 结论：**FAIL** —— 轻量/免费通道（免费档 15 RPM）仍不满足 3s 生产预算

---

## 0. 结论先行

| 项目 | 结果 |
| --- | --- |
| 门禁（生产 deadline 3000ms，tier 8） | **FAIL** |
| 失败率（tier 8 门禁） | 4/15 = **26.67%**（阈值 ≤1%，Wilson 95% CI 约 7.3%–55.5%） |
| known ratio（tier 8 门禁） | **73.33%**（阈值 ≥99%） |
| overhead P95/P99（tier 8 门禁） | **26.5ms / 26.5ms**（阈值 800/1500ms，达标） |
| tier 20 / 50 门禁 | **NOT_RUN**（tier 8 已触发停止规则） |
| 30s 诊断失败率 | tier 8 0%（20/20）/ tier 20 0%（15/15）/ tier 50 6.67%（1/15 仍 30s 超时） |
| 根因归属 | **provider**：Gemini 免费档尾延迟尖峰（15–30s 级），不是服务开销 |
| 是否可灰度 | **否**；`rerank.mode` 保持 `java` |

与 S1 的 DeepSeek 相比，Gemini 的中位延迟下降约 4.8–6.6 倍（tier 8：602ms vs 2903ms；tier 20：1161ms vs 7641ms；tier 50：1527ms vs 9186ms），
但**尾部**仍会出现 3s 以上、乃至 15–30s 的尖峰，导致 3s 生产门禁失败。免费档 15 RPM 是本次测量的硬约束。

---

## 1. 环境与前置

| 项 | 事实 | 证据 |
| --- | --- | --- |
| 栈状态 | app/postgres/redis/minio/worker 全 running；app Docker 健康态 `unhealthy`（已知 8080/8081 探针缺陷），`8081/actuator/health` = UP | `run.log` §0 |
| 管理账号 | `admin`（systemAdmin=true），登录 200，token 1800s | `raw/login` 已删除，仅保留结论；`raw/providers-before.json` |
| Gemini Key | 传至 `/data/.secrets/gemini.env`（600，69 bytes），全程未回显、未进命令行、未落仓库 | `run.log` §0 |
| 出网 | 沙箱宿主与 app 容器 IPv4 均可访问 `generativelanguage.googleapis.com`（无 key 时 403，符合预期）；app 容器 IPv6 无路由（快速失败，不影响请求） | 诊断命令见 `run.log` §0 |
| 免费档配额 | `generate_content_free_tier_requests`，**15 RPM / 模型**；40 次连发触发 429（`quotaValue=15`, `retryDelay=57s`） | `raw/burst-40.json` |
| 采样节奏 | 全部测量统一 5s 间隔（12 RPM），429 计数见各档统计 | `measure_paced.py --min-interval-ms 5000` |

> 说明：由于免费档 15 RPM 是硬限制，`/data/home/w3m/measure.py`（无节流）会导致大量 429 污染结果。
> 因此在仓库外写了 `raw/measure_paced.py`：**逐字复用** `ai_collab_worker.eval.rerank` 的
> `WorkerScorer / load_rerank_set / pad_candidates / p50 / p95 / p99 / summarize_latency`，
> 只增加调用间隔与原始样本落盘，未新造指标、未改门禁阈值。

---

## 2. 模型选择与 thinking 关闭证据

### 2.1 候选模型探测（短提示，`maxOutputTokens=256`）

| 模型 | `thinkingBudget=0` | 不带 thinkingConfig | 结论 |
| --- | --- | --- | --- |
| `gemini-2.5-flash-lite` | **HTTP 404**（no longer available to new users） | HTTP 404 | 不可用 |
| `gemini-3.1-flash-lite` | **HTTP 200**，`thoughtsTokenCount` 缺失，candidates=6，685.8ms | HTTP 200，`thoughtsTokenCount` 缺失，candidates=6，3874.9ms | **选用** |
| `gemini-3.5-flash-lite` | **HTTP 400**（invalid argument） | HTTP 200，`thoughtsTokenCount` 缺失，candidates=6，439.1ms | 不可用（Adapter 会 fallback 去掉字段 → thinking 常开） |
| `gemini-2.5-flash` | **HTTP 404** | HTTP 404 | 不可用 |

三个候选的模型元数据均为 `thinking=true`、input 1,048,576 / output 65,536 tokens。
**唯一接受 `thinkingBudget=0` 的候选是 `gemini-3.1-flash-lite`**，因此选定它（也是候选中最快可用者）。

5 次短提示 `thinkingBudget=0` 快速探测：685.8 / 572.9 / 480.3 / 462.1 / 670.3 ms → **P50 = 572.9ms**。
（来源：`raw/probe-gemini-3.1-flash-lite-think0.json`、`raw/thought-evidence-3.1-flash-lite.json` 的 4 个成功样本）

### 2.2 thinking 关闭证据（含对照组）

推理型提示（三元一次方程），4 种模式 × 3 次，间隔 6s：

| 模式 | 请求参数 | `thoughtsTokenCount` | `candidatesTokenCount` | `thought` parts | 耗时中位数 |
| --- | --- | --- | --- | --- | ---: |
| think0 | `thinkingConfig.thinkingBudget=0` | **缺失** | 220 | 0 | 1006.5ms |
| default | 不发送 thinkingConfig | **缺失** | 220 | 0 | 913.6ms |
| default_thoughts | `includeThoughts=true` | **缺失** | 220 | 0 | 2317.3ms |
| think0_thoughts | `thinkingBudget=0` + `includeThoughts=true` | **缺失** | 220 | 0 | 999.8ms |

**必须如实说明的限定**：`gemini-3.1-flash-lite` 在**所有模式**下都没有返回 `thoughtsTokenCount`，
即使 `includeThoughts=true` 也没有 `thought` part；对照组与关闭组的输出 token 数完全相同（220）。
因此**无法用 `thoughtsTokenCount` 数值差证明“thinking 是差异来源”**——该模型在观测层面不产生思考 token，
对照组的延迟差异只是抖动（default 中位数甚至低于 think0）。

thinking 关闭的有效证据因此分为两层：

1. **配置级（决定性）**：provider `capabilities=["CHAT","STREAMING"]`，不含 `THINKING`；
   `GeminiModelAdapter.disablesThinking()` 返回 true，`applyThinkingPolicy()` 必然写入
   `generationConfig.thinkingConfig.thinkingBudget=0`。
2. **请求级**：该模型接受 `thinkingBudget=0` 返回 200（未被 400 拒绝，因此不会走
   `postWithThinkingFallback` 去掉字段）；全部 200 响应的 `usageMetadata.thoughtsTokenCount` 缺失，
   未观察到任何 thinking 输出挤占 `maxOutputTokens` 的迹象（smoke 输出 12 tokens；门禁成功样本 model 延迟 497–2254ms）。

---

## 3. provider 创建 / 测试 / 发布 / purpose 分配

| 步骤 | 结果 |
| --- | --- |
| 创建 | HTTP 200，id `5b1d9af4-52d7-4055-8ca4-e654caa622b4`，`type=GEMINI`，`model=gemini-3.1-flash-lite`，`baseUrl/apiPath` 如上，`temperature=0`，`maxOutputTokens=1024`，`enabled=true` |
| **capabilities 原文** | **`["CHAT","STREAMING"]`**（不含 `THINKING`） |
| 详情复核 | `GET /api/v1/admin/ai/providers` 列表按 id 过滤（该 API 无 `GET /{id}`）→ `capabilities=["CHAT","STREAMING"]`、`hasApiKey=true` |
| `/test` | HTTP 200，`content="连接成功"`，`latencyMs=1943` |
| `/publish` | HTTP 200，`isShared=true`，`sharedStatus=PUBLISHED`，`publishedAt=2026-11-12T05:22:04.887164Z` |
| purpose 分配前 | `{"KNOWLEDGE_CHAT":"71386dc5-8e87-4890-ad3c-a770f67637f4"}`（ModelScope-DeepSeek-V4.1-Flash-1017） |
| purpose 分配后 | `{"KNOWLEDGE_CHAT":"5b1d9af4-…","AGENT":"5b1d9af4-…","PLANNING":"5b1d9af4-…"}`（三次 PUT 均 200） |

脱敏详情见 `provider.json`；原始响应见 `raw/provider-*.json`、`raw/purposes-*.json`。

### 真实网关冒烟（`POST /internal/ai/v1/model-calls`）

| 尝试 | HTTP | latencyMs | usage | 说明 |
| --- | ---: | ---: | --- | --- |
| 第 1 次 | **504** | — | — | `DEADLINE_EXCEEDED`（deadlineMs=30000），冷启动/瞬时尖峰 |
| 第 2 次 | 200 | 8145 | in 18 / out 12 | 首个成功 |
| 第 3 次 | 200 | 2031 | in 18 / out 12 | |
| 第 4 次（归档） | 200 | **609** | in 18 / out 12 | `modelAlias=gemini-3.1-flash-lite`，`source=PROVIDER` |

> 第 1 次 504 说明即使是 30s deadline 也会被尾部尖峰击穿；后续 3 次正常。
> usage 的 `outputTokens=12` 且无 thinking 计入，支持“thinking 未开启”的判断。

---

## 4. 门禁结果（生产 deadline 3000ms）

| 候选档 | 尝试 | 成功样本 | 失败率 | known ratio | 429 | overhead P50/P95/P99 | model P50/P95/P99 | total P50/P95/P99 | confidence | 判定 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- | --- |
| 8 | 15 | 11 | **26.67%** | **73.33%** | 0 | 14.8 / **26.5** / **26.5** ms | 602 / 2254 / 2254 ms | 615.2 / 2267.3 / 2267.3 ms | LOW_CONFIDENCE | **FAIL** |
| 20 | — | — | — | — | — | — | — | — | — | **NOT_RUN**（停止规则） |
| 50 | — | — | — | — | — | — | — | — | — | **NOT_RUN**（停止规则） |

失败全部为 `DEADLINE_EXCEEDED`（HTTP 504），实际耗时 3005–3015ms，即被 3s 截断：

```text
r01 3015.4ms   r04 3006.2ms   r11 3005.4ms   r13 3005.5ms
```

**门禁逐项**：

| 门禁 | 阈值 | tier 8 | 结论 |
| --- | --- | --- | --- |
| overhead P95 | ≤800ms | 26.5ms | ✅ |
| overhead P99 | ≤1500ms | 26.5ms | ✅ |
| 失败率 | ≤1% | 26.67% | ❌ |
| known ratio | ≥99% | 73.33% | ❌ |

按任务 §2.3「任一档失败率 >1% → 立即停止后续档位」，tier 20 / 50 **未跑门禁**，如实记为 NOT_RUN。
即使取 4/15 的 Wilson 95% 置信下界（≈7.3%），仍远高于 1%，因此 FAIL 判定不依赖点估计。

---

## 5. 30s 诊断（仅根因归因，不参与门禁）

| 档位 | 样本 | 失败率 | overhead P50/P95/P99 | model P50/P95/P99 | total P50/P95/P99 |
| ---: | ---: | ---: | --- | --- | --- |
| 8 | 20 | 0% | 17.8 / 37.3 / 49.5 ms | 795 / 1416 / 1576 ms | 832.3 / 1433.2 / 1591.0 ms |
| 20 | 15 | 0% | 16.1 / 35.1 / 35.1 ms | 1161 / **15244** / 15244 ms | 1177.8 / 15262.3 / 15262.3 ms |
| 50 | 15 | 6.67%（1×30s 超时） | 19.1 / 40.7 / 40.7 ms | 1527 / 5571 / 5571 ms | 1549.9 / 5611.7 / 5611.7 ms |

解读：

- **服务开销（overhead）稳定在 15–50ms**，比门禁阈值低 1–2 个数量级：worker HTTP、prompt 构建、
  序列化/解析都不是瓶颈；
- 30s 组里 model 仍出现 **15.2s**（tier 20）和 **>30s**（tier 50 的 1 次）尖峰；
- 也就是说，3s 门禁失败**不是实现开销**，而是 **provider 侧尾延迟**；
- tier 8 的 20 次 30s 诊断 0 失败、P99 1576ms，看似很好，但同一档在 15 次 3s 门禁里出现 4 次超时，
  说明尖峰是**随机、稀疏但不可忽略**的（tier 8 合并 35 次观测：20 次 30s 预飞 + 15 次 3s 门禁，
  4 次 ≥3s 且均为门禁截断 ≈ 11.4%；真实超时率因截断只能取该下界）。

---

## 6. 对比表（与 S1 并列）

> S1 数字直接引用 `docs/acceptance-evidence/2026-11-12/w3-measurement-b/report.md`，未重跑。

| provider / model | 档位 | 样本 | 失败率 | known ratio | overhead P50/P95/P99 | model P50/P95/P99 | total P50/P95/P99 | confidence | 判定 |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- | --- | --- |
| ModelScope / DeepSeek-V4.1-Flash-1017（门禁 3s） | 8 | 15 | 86.67% | 13.33% | 13.5 / 15.1 / 15.1 ms | 2903 / 2921 / 2921 ms | 2916.5 / 2936.1 / 2936.1 ms | LOW | FAIL |
| ModelScope / DeepSeek-V4.1-Flash-1017（门禁 3s） | 20 | 15 | 100% | 0% | null | null | null | LOW | FAIL |
| ModelScope / DeepSeek-V4.1-Flash-1017（门禁 3s） | 50 | 15 | 100% | 0% | null | null | null | LOW | FAIL |
| **Gemini / gemini-3.1-flash-lite（门禁 3s）** | **8** | **15** | **26.67%** | **73.33%** | **14.8 / 26.5 / 26.5 ms** | **602 / 2254 / 2254 ms** | **615.2 / 2267.3 / 2267.3 ms** | **LOW** | **FAIL** |
| Gemini / gemini-3.1-flash-lite（门禁 3s） | 20 | — | — | — | — | — | — | — | NOT_RUN |
| Gemini / gemini-3.1-flash-lite（门禁 3s） | 50 | — | — | — | — | — | — | — | NOT_RUN |
| ModelScope（30s 观测） | 8 | 60 | 0% | 100% | 14.6 / 20.0 / 56.7 ms | 3311 / 4895 / 5601 ms | 3332 / 4907 / 5617 ms | LOW | 观测 |
| ModelScope（30s 观测） | 20 | 60 | 0% | 100% | 12.3 / 16.3 / 35.9 ms | 7641 / 12266 / 16289 ms | 7670 / 12275 / 16304 ms | LOW | 观测 |
| ModelScope（30s 观测） | 50 | 60 | 0% | 100% | 13.8 / 20.8 / 24.0 ms | 9186 / 16548 / 18433 ms | 9200 / 16560 / 18447 ms | LOW | 观测 |
| **Gemini（30s 观测）** | **8** | **20** | **0%** | **100%** | **17.8 / 37.3 / 49.5 ms** | **795 / 1416 / 1576 ms** | **832.3 / 1433.2 / 1591.0 ms** | LOW | 观测 |
| **Gemini（30s 观测）** | **20** | **15** | **0%** | **100%** | **16.1 / 35.1 / 35.1 ms** | **1161 / 15244 / 15244 ms** | **1177.8 / 15262.3 / 15262.3 ms** | LOW | 观测 |
| **Gemini（30s 观测）** | **50** | **15** | **6.67%** | **93.33%** | **19.1 / 40.7 / 40.7 ms** | **1527 / 5571 / 5571 ms** | **1549.9 / 5611.7 / 5611.7 ms** | LOW | 观测 |

关键变化：

- **中位延迟**：Gemini 在 8/20/50 档分别为 602 / 1161 / 1527ms，比 DeepSeek 的 2903 / 7641 / 9186ms 快 **4.8–6.6 倍**；
- **服务开销**：两者同量级（10–60ms），都远低于门禁；
- **尾部**：DeepSeek 是“整体慢”（P50 已超 3s），Gemini 是“中位快、尾部尖峰”（4/15 超 3s，30s 档仍见 15.2s / >30s）；
- 因此两者都 **FAIL**，但失败形态不同：DeepSeek 是系统性超时，Gemini 是概率性尖峰。

---

## 7. 判定与根因

### 7.1 判定

**FAIL（LOW_CONFIDENCE，仅对 staging + `gemini-3.1-flash-lite` + 当前免费档 Key 成立）。**

- 门禁 tier 8：失败率 26.67% ≫ 1%，known ratio 73.33% < 99%；
- overhead P95/P99 达标（26.5ms），说明**不是实现/服务开销问题**；
- 30s 诊断显示 model 侧仍有 15.2s / >30s 尖峰，**根因归属 provider（Gemini 免费档尾延迟）**；
- 按停止规则，tier 20/50 门禁未跑，不得外推为“高 tier 更差”或“更好”。

### 7.2 为什么不是实现问题

| 观察 | 含义 |
| --- | --- |
| overhead P95 ≤ 40.7ms（三档） | worker→Java 网关→适配器链路开销稳定 |
| 成功样本 model 497–2254ms，与直连 Gemini 同量级 | 适配器请求/解析没有额外放大 |
| 失败全部是 3s 截断的 `DEADLINE_EXCEEDED` | 不是 429、不是 5xx、不是空响应 |
| 直连 Gemini（宿主 python，短提示）也出现 3.9s / 5.0s 级响应 | 尖峰在 provider 侧即可复现；15.2s / >30s 出现在经网关的 tier 20/50 观测中 |

### 7.3 限定

- 仅 **tier 8** 有门禁结论（15 样本，LOW_CONFIDENCE）；tier 20/50 为 NOT_RUN；
- 仅覆盖 **staging**、**免费档 Key（15 RPM）**、**`gemini-3.1-flash-lite`**；
- 5s 间隔限速是测量约束，生产若用付费档/更高配额，延迟分布可能不同，**本结论不得推导为生产可灰度**；
- thinking 关闭是**配置级 + 请求级**证据；该模型在观测上不产生 `thoughtsTokenCount`，无法用 token 数做对照证明；
- 未修改仓库代码、未 commit/push、未改任何默认开关（`RERANK_MODE=java` 等保持不变）。

### 7.4 结论口径

**轻量/免费通道（ModelScope 免费模型、Gemini 免费档）目前都不满足 3s 生产预算。**
Gemini 把中位延迟从 3–9s 降到 0.6–1.5s，是明显进步，但尾部尖峰仍会造成约 1/4 的 3s 超时；
若要继续推进，需要 **付费档/专用配额** 或 **把 deadline 与降级策略解耦**（均超出本泳道范围，未实施）。

---

## 8. 产物索引

| 文件 | 说明 |
| --- | --- |
| `aggregate-8.json` | tier 8 门禁汇总 + 每样本 raw（total/model/overhead）+ 30s 诊断 + 判定 |
| `aggregate-20.json` / `aggregate-50.json` | 门禁 NOT_RUN + 30s 诊断汇总与 raw |
| `raw/gate-8-3000.json` | 生产 deadline 门禁原始样本（15 次） |
| `raw/preflight-20.json` | 预飞 20 次（tier 8，30s） |
| `raw/diag-20-30000.json` / `raw/diag-50-30000.json` | 30s 诊断原始样本 |
| `raw/probe-*.json` / `raw/probe-summary.json` | 4 个候选模型 think0/默认 探测 |
| `raw/thought-evidence-*.json` | thinking 对照实验（含 includeThoughts） |
| `raw/burst-40.json` | 免费档 15 RPM 配额证据 |
| `raw/provider-*.json` / `raw/providers-*.json` / `raw/purposes-*.json` | provider 创建/测试/发布/purpose 原始响应（无 apiKey） |
| `raw/smoke-model-call.json` | 真实网关冒烟成功样本 |
| `raw/setup_provider.sh` / `raw/smoke_gateway.sh` / `raw/measure_paced.py` / `raw/probe*.py` | 可复算脚本 |
| `provider.json` | provider 脱敏详情（含 capabilities 原文） |
| `run.log` | 实际命令与关键输出（无令牌/Key） |

---

## 9. 未完成项 / 与任务的偏离

1. **tier 20 / 50 门禁未跑**：tier 8 失败率 26.67% 已触发“立即停止后续档位”规则；未用 30s 诊断替代门禁。
2. **60 次观测未跑**：仅当三档门禁全部 ≤1% 时才追加，本次不满足条件。
3. **`GET /api/v1/admin/ai/providers/{id}` 不存在**：管理 API 只有列表接口；详情改用列表按 id 过滤，并在报告中注明。
4. **thinking 对照未能用 `thoughtsTokenCount` 数值证明**：该模型在所有模式下都不返回思考 token；已在 §2.2 如实说明，未把它写成“已证明”。
5. **首次冒烟 504**：30s deadline 仍被尖峰击穿，后续 3 次成功；该次失败计入报告，未从证据中剔除。
