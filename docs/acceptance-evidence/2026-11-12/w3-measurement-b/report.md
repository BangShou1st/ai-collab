# W3 测量 B：真实模型三行口径测量（staging）

- 日期：2026-11-12（Asia/Shanghai）
- 环境：instabox `box-aae54f` staging（app 8080/8081、worker 仅 `ai` 内网、Flyway v91）
- 模型：**`deepseek-ai/DeepSeek-V4.1-Flash-1017`**（ModelScope，OpenAI 兼容，base `https://api-inference.modelscope.cn`，path `/v1/chat/completions`）
- 证据等级：**LIVE_VERIFIED (staging, real model)**；不是 stub、不是单次调用
- 结论：**FAIL** —— `rerank.mode` 必须保持 `java`，不可宣称可灰度

---

## 0. 结论先行

| 项目 | 结果 |
| --- | --- |
| 门禁（生产 deadline 3000ms，三档 8/20/50） | **FAIL** |
| 失败率（门禁） | 86.67% / 100% / 100%（阈值 ≤1%） |
| model_latency_known_ratio（门禁） | 13.33% / 0% / 0%（阈值 ≥99%） |
| overhead P95/P99（门禁，仅 tier 8 有 2 个 known 样本） | 15.1ms / 15.1ms（阈值 800/1500ms） |
| overhead P95/P99（30s 观测，三档 60 样本） | ≤20.8ms / ≤56.7ms（服务开销本身达标） |
| 根因 | 真实模型耗时 3.3–18.4s，远超生产 3s deadline；**不是**服务开销 |
| 是否可灰度 | **否** |
| 能否进入 S2（Ed25519 联调） | **可以（有条件）**：S2 与模型延迟正交，只做短令牌联调；保持 `rerank.mode=java`，不得据此宣称 W3 可灰度 |

---

## 1. 前置（P1 / P2 / P3）

### 1.1 P1：模型档案已写入

| 字段 | 值 | 证据 |
| --- | --- | --- |
| context_window_tokens | **1048576** | 模型卡 `context_window = 1M tokens`；`config.json` `max_position_embeddings=1048576` |
| max_output_tokens | **262144** | 模型卡 `max_tokens ≥ 256K`（取下界 256×1024） |
| supports_usage | **true** | Provider 每次返回 usage（`source=PROVIDER`） |
| source | **PROVIDER_DOCUMENT** | 模型卡 + config.json；未用 ADMIN_VERIFIED |

创建结果（管理 API `/api/v1/admin/ai/model-profiles`）：

```text
HTTP=200 code=SUCCESS
id=d8fc4804-6e66-4e87-b6b6-a0ea591b3edb
contextWindowTokens=1048576 maxOutputTokens=262144 supportsUsage=True source=PROVIDER_DOCUMENT
```

### 1.2 P2：thinking 状态 = **常开**（不可在不改代码的情况下关闭）

渠道侧实测（直连 ModelScope，同一模型/提示词）：

| 请求 | reasoning 长度 | completion tokens | 说明 |
| --- | ---: | ---: | --- |
| 默认（无参数） | 47 | 30（reasoning 27 + text 2） | 默认思考 |
| `enable_thinking=false` | 0 | 2 | 可关闭 |
| `chat_template_kwargs.enable_thinking=false` | 0 | 2 | 可关闭 |
| `reasoning_effort=none` | 0 | 2 | 可关闭 |

**但** Java 侧 `OpenAiCompatibleModelAdapter.request()` 只发送
`model / temperature / max_tokens / stream / messages / response_format / tools`，
`ModelConfiguration` 也没有 thinking 字段 → staging 真实链路无法固定为"关"。

实际链路证据：provider `/test` 仅返回四个字"连接成功"，usage 为 **44 prompt / 54 completion**
（含 reasoning）；rerank 单次 output 321–1676 tokens。

判定：thinking 常开会抬高 `model`/`total`/token 成本，但 `overhead = max(0, total − model)`
已扣除模型耗时，**不污染 overhead 门禁**，因此按任务要求如实记录"thinking 常开"，不据此中止。

### 1.3 P3：预飞 20 次（tier 20，15s deadline）

```text
calls=20 ok=20 fail=0 429=0
model latency: min=5994ms max=11522ms mean=8254ms
               P50=7968ms P95=10067ms P99=11522ms
single call >10s: 3/20 = 15%
input=2476 tokens/call, output=534–1676 tokens/call
```

**触发 §P3 停止条件（单次调用 >10s）**。按任务先换模型（同一真实链路，tier 20，5 次/模型）：

| 候选 | 结果 |
| --- | --- |
| `Qwen/Qwen3.8-Flash-Next` | test 2209ms；5 次中 2 次成功（9428ms / 14988ms），3 次 15s 超时（更差） |
| `ZhipuAI/GLM-4.7-Flash` | `429 AI_PROVIDER_QUOTA_EXCEEDED`（test + 5/5） |
| `stepfun-ai/Step-3.5-Flash` | test `502 AI_PROVIDER_ERROR`；2×503 + 3×429 |
| `Qwen/Qwen3.5-35B-A3B` | test 200，但 13648ms（更慢） |

无可用的更快模型；按用户指定恢复 `DeepSeek-V4.1-Flash-1017`。

额度/时长外推（以预飞 tier 20 为基准）：

| 目标 | 调用数 | 总 token（约） | 总时长（约） |
| --- | ---: | ---: | ---: |
| 200/档 × 3 | 600 | 2.07M | 82.5 min |
| 510/档 × 3 | 1530 | 5.27M | 3.5 h |

沙箱 `maxRunMinutes=120`（免费档托管上限，`max-run 480` 被拒），510/档不可行，200/档也已逼近上限；
且首轮 tier 20 观测出现 51.7% 的 429。→ 按 §P3 **降档**：门禁探针 15/档，观测样本 60/档（confidence=LOW_CONFIDENCE）。
降档原因与决策已写入本报告；未把 510 档写成已完成。

---

## 2. 测量方法（复用冻结脚本，只替换 scorer）

- 冻结集：`ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl`（15 条，6–10 候选）。
- 打分器：`WorkerScorer` → `POST http://ai-worker-api:9100/v1/rag/rerank`（真实 worker，真实 Java 网关，真实魔搭模型）。
- 运行位置：`ai` 内网临时容器（worker 镜像 `ai-collab-ai-worker:dev`，挂载仓库 fixtures），base URL `http://ai-worker-api:9100`，
  令牌用 staging `RERANK_SERVICE_TOKEN`（`--env-file /data/.secrets/worker.env`，未打印、未落盘到仓库）。
- 口径函数：`total` 客户端实测、`model=modelLatencyMs`、`overhead=max(0,total−model)`、
  P50/P95/P99 最近秩法、`confidence` 分级，全部**直接 import 冻结模块**
  `ai_collab_worker.eval.rerank`（`summarize_latency` / `p50` / `p95` / `p99` / `pad_candidates`）。

**与 `ai-eval rerank` 的唯一差别**：`ai-eval` 是 fail-fast（任一 worker 错误直接退出），
无法统计"失败率/429 次数/known_ratio"；本波用一层薄采样器在 `scorer.rank()` 外捕获
`WorkerError` 后继续采样，其余指标与冻结逻辑逐字复用（`raw/` + `build-aggregates.py` 可复算）。
未新造指标、未改门禁阈值。

实际命令（完整清单见 `run.log`）：

```bash
# 门禁：生产默认 deadline 3000ms（与 Java RerankProperties 默认一致）
docker run --rm --network ai-collab-deploy_ai --user 0 \
  -v /data/home/ai-collab:/repo:ro -v /data/home/w3m:/out \
  --env-file /data/.secrets/worker.env -e WORKER_URL=http://ai-worker-api:9100 \
  --entrypoint /app/.venv/bin/python ai-collab-ai-worker:dev \
  /out/measure.py --tier 8 --runs 1 --deadline-ms 3000 --out /out/gate-8-3000.json

# 观测：放宽 deadline 30000ms（不参与门禁）
docker run ... /out/measure.py --tier 8 --runs 4 --deadline-ms 30000 --out /out/diag-8-30000.json
```

> 为什么门禁用 3000ms：`ai-eval --deadline-ms` 默认 3000，Java `RerankProperties.deadlineMs`
> 生产默认也是 3000；worker 用同一个 deadline 调 Java 内部网关。因此 3000ms 组就是生产路径的复刻。
> 30s 组只用于回答"服务开销本身是多少"，**不替代门禁**。

---

## 3. 门禁结果（生产 deadline 3000ms）

| 候选档 | 尝试 | 成功样本 | 失败率 | known ratio | 429 | overhead P50/P95/P99 | model P50/P95/P99 | total P50/P95/P99 | confidence | 判定 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | --- | --- |
| 8 | 15 | 2 | **86.67%** | **13.33%** | 0 | 13.5 / 15.1 / 15.1 ms | 2903 / 2921 / 2921 ms | 2916.5 / 2936.1 / 2936.1 ms | LOW_CONFIDENCE | **FAIL** |
| 20 | 15 | 0 | **100%** | **0%** | 0 | null（无 known） | null | null | LOW_CONFIDENCE | **FAIL** |
| 50 | 15 | 0 | **100%** | **0%** | 0 | null（无 known） | null | null | LOW_CONFIDENCE | **FAIL** |

失败全部为 `DEADLINE_EXCEEDED`（13 / 15 / 15 次），无 429。
tier 8 的 2 个成功样本耗时 2903/2921ms，恰好压在 3s 线内；其余 13 次被 3s 截断。

**门禁逐项**：

| 门禁 | 阈值 | tier 8 | tier 20 | tier 50 |
| --- | --- | --- | --- | --- |
| overhead P95 | ≤800ms | 15.1ms ✅ | null（无 known 样本）❌ | null ❌ |
| overhead P99 | ≤1500ms | 15.1ms ✅ | null ❌ | null ❌ |
| 失败率 | ≤1% | 86.67% ❌ | 100% ❌ | 100% ❌ |
| known ratio | ≥99% | 13.33% ❌ | 0% ❌ | 0% ❌ |

→ **三档均 FAIL**。按 §5「任意一档 FAIL → 停止后续档位」，tier 8 已触发停止；
20/50 档仅补齐同一门禁探针以形成三档表，未继续加大样本。

---

## 4. 观测结果（30s deadline，仅用于三行口径与根因归因）

> 该组 **不参与门禁判定**；作用是证明"服务开销是否达标"以及失败根因。

| 候选档 | 样本 | 失败率 | known ratio | overhead P50/P95/P99 | model P50/P95/P99 | total P50/P95/P99 | confidence |
| ---: | ---: | ---: | ---: | --- | --- | --- | --- |
| 8 | 60 | 0% | 100% | 14.6 / **20.0** / **56.7** ms | 3311 / 4895 / 5601 ms | 3332 / 4907 / 5617 ms | LOW_CONFIDENCE |
| 20 | 60 | 0% | 100% | 12.3 / **16.3** / **35.9** ms | 7641 / 12266 / 16289 ms | 7670 / 12275 / 16304 ms | LOW_CONFIDENCE |
| 50 | 60 | 0% | 100% | 13.8 / **20.8** / **24.0** ms | 9186 / 16548 / 18433 ms | 9200 / 16560 / 18447 ms | LOW_CONFIDENCE |

解读：

- **overhead 本身远低于门禁**（P95 ≤20.8ms、P99 ≤56.7ms，余量 >25 倍）——worker HTTP、Prompt 构建、
  序列化、解析、打分映射都不是瓶颈；
- **model 耗时随候选数上升**：P50 3.3s（8 档）→ 7.6s（20 档）→ 9.2s（50 档），P99 到 18.4s；
- 生产 deadline 是 3000ms，因此真实模型在 8 档就已大量超时，20/50 档 100% 超时；
- thinking 常开使 output 从理论 ~50 tokens 膨胀到 321–1676 tokens，是 model 耗时的主要组成部分。

---

## 5. 中途换 Key 记录

| 项 | 记录 |
| --- | --- |
| 触发 | tier 20 观测第 1 轮 60 次尝试中 **31 次 429**（51.67% > 10%） |
| 切换点 | 该轮第 29 个成功样本之后（29 ok / 31 限流，轮次中止） |
| 使用的 Key | 第一把 → **第二把**（`MODELSCOPE_API_KEY_2`；值不打印、不落盘） |
| 切换动作 | 管理 API `PUT /providers/{id}` 重新加密写入 → `/test` 200（2537ms）→ `/publish` PUBLISHED |
| 是否重跑 | **是**：tier 20 观测用 Key#2 重跑 60 次，60/60 成功、0 个 429；Key#1 的 29 样本保留为 `raw/diag-20-30000.json`（标注废弃轮，不进 aggregate） |
| 门禁档是否重跑 | 否：门禁探针 429=0（失败全是 `DEADLINE_EXCEEDED`），换 Key 不影响门禁结论 |

---

## 6. 判定与限定

### 6.1 判定

**FAIL。**

- 三档（8/20/50）失败率 86.67% / 100% / 100%，均远超 1%；
- known ratio 13.33% / 0% / 0%，均远低于 99%；
- 即使只看 tier 8 的 2 个成功样本，overhead P95/P99（15.1ms）达标，也无法改变"绝大多数调用超时"的事实；
- 结论：**`rerank.mode` 必须保持 `java`，不可宣称可灰度。**

### 6.2 限定

- 本结论仅覆盖 **staging + 真实魔搭模型（DeepSeek-V4.1-Flash-1017）+ thinking 常开**；
- 证据等级 **LIVE_VERIFIED (staging)**，不得推导为"生产可灰度"；
- 门禁使用生产默认 deadline 3000ms；30s 组是观测，不构成门禁证据；
- 样本量：门禁 15/档、观测 60/档，均 `LOW_CONFIDENCE`（<200）；因门禁已 FAIL，按 §5 未继续加档；
- 未修改仓库代码、未 commit、未改任何默认开关（`RERANK_MODE=java` 等保持不变）。

### 6.3 能否进入 S2（Ed25519 联调）

**可以（有条件）**：

- S2 验证的是内部短令牌的签发/验签/轮换，与模型延迟正交，不依赖 `rerank.mode=python`；
- 前提：保持 `RERANK_MODE=java` 与 `INTERNAL_AI_TOKEN_MODE=static` 基线，S2 仅以 `dual` 模式做
  `static + Ed25519` 双读联调，不据此宣称 W3 可灰度；
- 如果中心会话的规则是"W3 测量 B 必须 PASS 才允许进入 S2"，则当前**不具备条件**。

---

## 7. 产物索引

| 文件 | 说明 |
| --- | --- |
| `aggregate-8.json` / `aggregate-20.json` / `aggregate-50.json` | 每档门禁 + 观测汇总与判定 |
| `raw/gate-{8,20,50}-3000.json` | 生产 deadline 门禁原始样本 |
| `raw/diag-{8,20,50}-30000*.json` | 30s 观测原始样本（含 Key#1 429 废弃轮） |
| `raw/preflight-20.json` | P3 预飞 20 次 |
| `raw/smoke-3s.json` / `raw/smoke-30s.json` | deadline 冒烟（3s 全超时 / 30s 全成功） |
| `raw/bench-*.json` | 4 个候选模型的换模型尝试 |
| `build-aggregates.py` | aggregate 组装脚本（含门禁判定逻辑） |
| `run.log` | 实际命令与原始输出 |
