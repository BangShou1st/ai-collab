# W3 Lane B：本地 Reranker 实测（formal run 1 FAIL，等待 A adapter 修复）

- 日期：2026-11-12 / 2026-11-13（Asia/Shanghai）
- worktree：`E:\project\ai-collab-w3-reranker-local`
- 分支：`w3/reranker-local`
- 基线：`d6fa5133`；集成 A checkpoint `2412b8be`；formal 证据提交 `071f92ab`
- staging：`box-aae54f` / `ai-collab-app` 8080，镜像 `ai-collab-backend:local`；Flyway V95
- 资源锁：`local-gpu` 仍由 Lane B 持有；`staging-box` / `staging-model` 已于
  `2026-11-13T00:56:15+08:00` 释放给 D
- 当前状态：**FORMAL_RUN_1_FAIL / RERUN_PENDING_A_FIX**
- 当前最终标签：**FAIL**（formal run 1，adapter `results_count_mismatch`，2/15）
- 生产用户路径：**NOT_VERIFIED**，因为 `RERANK_MODE=java` 未改变。

---

## 0. 结论先行

| 项目 | 结果 |
| --- | --- |
| 模型文件 SHA-256 | GGUF 与 safetensors 均与冻结值一致 |
| llama.cpp Vulkan | `b12318` / commit `7021f7e09`，4 slots、每 slot ctx 8192、Flash Attention 禁用 |
| 2 文档冒烟 | 相关 `0.999993443` > 无关 `0.008117586`，方向正确；首请求 `417.180ms` |
| 8 档 preflight | 45/45 HTTP 成功、0 次 >3s，P95 `217.180ms` |
| 20 档 preflight | 45/45 HTTP 成功、0 次 >3s，P95 `1262.537ms` |
| 50 档 preflight | 45/45 HTTP 成功，但 **45/45 >3s**，P50 `3722.758ms`、P95 `4119.448ms` |
| 裸 llama.cpp 门禁 | **NOT_EVALUATED**：没有 Java `modelLatencyMs`、无法计算 known ratio / overhead |
| formal run 1（8 档 15 次门禁） | **FAIL**：2/15 `adapter_INVALID_RESPONSE / results_count_mismatch`；r10/r12 |
| formal 20 / 50 档 | **NOT_RUN**：8 档失败立即停止 |
| formal 60 次观测 | **NOT_RUN**：门禁未通过 |
| 1:1 调用计数 | PASS：proxy expected 30 / observed 30 |
| 证据等级 | preflight=`DIAGNOSTIC_ONLY`；formal run 1=`FAIL`；不得写成 W3 PASS |

根因不是模型或延迟：A 的 `LlamaCppRerankAdapter` 请求带了 `top_n=topK(8)`，
llama.cpp 对 9/10 个候选也截断为 8 个 results，adapter 却要求 results 数等于
candidateCount。冻结集的 8 档包含 `r10=9` 与 `r12=10`，因此 formal 8 档确定性失败。

---

## 1. 环境与模型

### 1.1 模型

| 项 | 值 |
| --- | --- |
| 模型 | `Qwen/Qwen3-Reranker-0.6B` |
| 量化 | Q8_0 |
| GGUF | `D:\models\qwen3-reranker-0.6b-q8_0.gguf` |
| GGUF size | `639,153,184` bytes |
| GGUF SHA-256 | `22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48` |
| safetensors | `D:\models\Qwen3-Reranker-0.6B\model.safetensors` |
| safetensors size | `1,191,588,280` bytes |
| safetensors SHA-256 | `27cd75a405b9c1b46b59abfd88aaa209e6fed2a1972cde9b70e7659537c5e65b` |
| GPU | NVIDIA GeForce RTX 4060 Laptop GPU，8188 MiB，驱动 591.74 |
| GPU 显存 | 空闲基线约 `2275 MiB`；加载模型后约 `3843–3850 MiB`；峰值观测 `4024 MiB` |

### 1.2 llama.cpp

| 项 | 值 |
| --- | --- |
| 构建 | `version: 0.9.0-dev (build 12318, commit 7021f7e09)` |
| 可执行文件 | `E:\project\ai-collab\target\perf-tools\llama-b12318-vulkan\llama-server.exe` |
| launcher SHA-256 | `7cbf01f6cd175ffd2ffad22a266eb7ebef06072c13c582f3b9cc3afa81d7ffa3` |
| `llama-server-impl.dll` SHA-256 | `684a7406092f53b315c590fedf09d0b444cc49e82e89c4019aa78429c0d06c41` |
| `ggml-vulkan.dll` SHA-256 | `7af1c75f4beac06ca10bfe023c3666bbda8c2957f840e6981274b161d5d2a1d3` |
| 参数 | `-m D:\models\qwen3-reranker-0.6b-q8_0.gguf --host 127.0.0.1 --port 18081 -ngl 99 -c 8192 --rerank` |
| slots | 4（`n_slots=4`，`n_ctx_slot=8192`，`kv_unified=true`） |
| 日志告警 | Vulkan 路径 Flash Attention 不支持，运行时设为 disabled；`n_batch=n_ubatch=512` |
| 冷启动 | 进程启动到 `listening` 约 `3.111s`；首个 2 文档请求另计 `417.180ms` |

服务在 preflight 结束后已停止，端口 `18081` 已释放；模型、二进制和日志均未删除。

---

## 2. 冻结集与补齐校验

冻结集：`ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl`

- canonical fingerprint：`f5baa744542204b13a0750f0550928644b8b75de92cc8384b23c24cc21aa43e3`
- 原始文件 SHA-256：`69ca5d90d9b24847f8cf390c21e9eefc2d3901f87bc0a91661a3ba3d80c3871f`
- cases：15（`r01`–`r15`）
- 原始候选数：6–10
- 8 档：13 个 case 精确 8 条；`r10=9`、`r12=10`（冻结 canonical 规则只补齐、不截断）
- 20 档：15/15 精确 20 条
- 50 档：15/15 精确 50 条
- 补齐候选：`labels=0`、`supports=false`、正文 ≤2000 字符，`NDCG@8` 只使用原始候选

逐 case 结果见 `raw/fixture-validation.json`（SHA-256
`783bacd8c85e1c6f3de9e050a6c7e03c7eae96f6ee11619a1e62befeca013a3c`）。

---

## 3. Preflight（诊断，不是 formal gate）

### 3.1 2 文档冒烟

query：`里程碑延期后如何重排依赖任务`

| 文档 | `relevance_score` |
| --- | ---: |
| 里程碑变更与依赖重排 | `0.9999934434890747` |
| 甘特图视图说明 | `0.008117586374282837` |

- 方向：相关 > 无关，符合预期
- 首请求 wall：`417.180ms`
- usage：`prompt_tokens=225`
- health：HTTP 200 `{"status":"ok"}`

原始文件：`raw/smoke-2-doc.json`。

### 3.2 分档结果

每个档位先跑完整 15-case warm-up，再跑 3 个 pass × 15 case = 45 个样本。
所有样本均为单请求串行，无重试。

| 档位 | warm-up | attempts | candidate 数 | HTTP 失败 | >3s | wall P50 | wall P95 | wall P99 | NDCG@8 | confidence |
| ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 8 | 15 | 45 | 8–10 | 0 | 0 | 63.750ms | 217.180ms | 217.860ms | 0.974785 | LOW_CONFIDENCE |
| 20 | 15 | 45 | 20 | 0 | 0 | 1079.338ms | 1262.537ms | 1275.779ms | 0.974785 | LOW_CONFIDENCE |
| 50 | 15 | 45 | 50 | 0 | **45** | 3722.758ms | 4119.448ms | 4278.203ms | 0.974785 | LOW_CONFIDENCE |

说明：

- 这张表只有客户端到 llama.cpp 的 wall time；**没有** Java `modelLatencyMs`，
  因此 `model_latency_known_ratio`、`overhead`、20 档/50 档 formal 判定均为
  `NOT_EVALUATED`。
- 50 档的 45 次 HTTP 都成功返回，且都通过严格分数校验（50 个有限分数、
  index 0..49 恰好一次）；“失败”列按 HTTP/响应契约计为 0，但按 3s 预算
  计为 45/45 超时。
- 样本 45 <200，全部只能标 `LOW_CONFIDENCE`。

原始文件：

- `raw/preflight-tier8.json`（SHA-256 `3f2d219e14fe0e1e30b2acec70e043783d463ce70bad7ef0241f4656b61e0afb`）
- `raw/preflight-tier20-50.json`（SHA-256 `960be09494f60734bfdcb8dc1628173ad2ff934ca0d2573a3d2bc3c9ff5f653d`）

### 3.3 一次性 warm-up 长尾

第一次 8 档 preflight 只 warm-up 一个 case，首个 pass 出现：

| case | 首次 wall | 后续同输入 wall |
| --- | ---: | ---: |
| `r03` | 3551.993ms | 约 146ms |
| `r07` | 2413.443ms | 约 236ms |

第二次改为整档 15-case warm-up 后，8 档 45 个样本 0 次 >3s（P95 217.180ms）。
该冷启动证据原样保留在 `raw/preflight-tier8-single-warmup.json`，没有删除或覆盖。
本报告不据此把任何档位写成 formal PASS；formal warm-up 口径以中心冻结协议为准。

---

## 4. Formal run 1（staging admin capability test）

### 4.1 配置与部署

| 项 | 值 |
| --- | --- |
| 镜像 | `ai-collab-backend:local`，image ID `8d1bcdebde0c891a7cb71fb1cab77a235de265e331471b5223572e00394586f9` |
| app 容器 | `87776994899b391967af63a95ed6673bdc729012468b22e3db6d33f646220e6d`，healthy |
| Flyway | V92–V95 全部 success，schema version `95` |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static` |
| admin API | `http://127.0.0.1:8080` |
| admin test 端点 | `POST /api/v1/admin/ai/capability-profiles/a6eb76ad-f36e-4ae6-8a16-e6be5ff421bc/test` |
| provider ID | `31b9941d-4add-4fe1-a048-9d87f88f6632` |
| model profile ID | `ce5efeb1-7435-4f0b-b312-07b67d19840d` |
| capability profile ID | `a6eb76ad-f36e-4ae6-8a16-e6be5ff421bc`，version 1，PUBLISHED |
| provider base URL | `http://172.23.0.1:18081`（staging-only tunnel，local network zone） |
| settings | timeout 3000ms、maxCandidates 50、batchSize 8、maxLength 2048、scoreType sigmoid、topK 8、fallback java-heuristic |
| 默认 2 文档测试 | SUCCESS；relevant `0.9962389` > irrelevant `0.0000321`；model `378ms` |
| 1:1 调用计数 | proxy expected 30、observed 30（含默认 2 文档测试共 31 次 `/rerank`） |

### 4.2 8 档门禁

- warm-up：15 次（其中 r10/r12 已出现 2 次 adapter 失败）
- 门禁：15 次
- 失败：2 次
- 失败率：`13.3333%`（>1%）
- `model_latency_known_ratio`：`1.0`
- model latency：P50 `296ms`、P95 `1258ms`、P99 `1258ms`
- e2e latency：P50 `314.846ms`、P95 `1278.381ms`、P99 `1278.381ms`
- overhead（13 个成功样本）：P50 `19.435ms`、P95 `24.999ms`、P99 `24.999ms`
- 1:1：PASS（30/30）
- **8 档判定：FAIL**

### 4.3 失败明细与根因

| case | 候选数 | outcome | reason | model latency | e2e |
| --- | ---: | --- | --- | ---: | ---: |
| `r10` | 9 | `INVALID_RESPONSE` | `results_count_mismatch` | 331ms | 348.657ms |
| `r12` | 10 | `INVALID_RESPONSE` | `results_count_mismatch` | 306ms | 333.036ms |

staging app 日志在 warm-up 与 gate 各记录一次同样的失败：

```text
rerank adapter 响应非法: providerId=31b9941d-4add-4fe1-a048-9d87f88f6632
reason=results_count_mismatch status=200 candidates=9
rerank adapter 响应非法: providerId=31b9941d-4add-4fe1-a048-9d87f88f6632
reason=results_count_mismatch status=200 candidates=10
```

直接调用 llama.cpp 的受控诊断：

| 请求 | results 数 |
| --- | ---: |
| 9 documents + `top_n=8` | **8** |
| 9 documents（省略 `top_n`） | **9** |

根因归属：**Lane A 的 adapter 契约 bug**。
`LlamaCppRerankAdapter.buildRequest` 发送 `top_n=topK`（本档 topK=8），
llama.cpp 对 9/10 个候选也按 `top_n` 截断；adapter 又要求
`results.size() == candidateCount`。冻结集 8 档包含 `r10=9`、`r12=10`，
因此失败是确定性的，不是模型延迟或随机网络问题。

### 4.4 停止与重跑

- 按冻结规则，8 档失败立即停止：**20/50 档 NOT_RUN**，**60 次观测 NOT_RUN**。
- 本次 run 的原始 FAIL 证据保留，不删除、不改写为 PASS。
- A 修复后由中心通知重跑；重跑使用新 run ID，旧 FAIL 作为历史证据。
- 当前报告标签：`FAIL`。生产用户路径仍为 `NOT_VERIFIED`。

### 4.5 formal 原始证据

| 文件 | 用途 |
| --- | --- |
| `raw/formal/formal-summary.json` | 完整 aggregate、失败明细、profile/endpoint ID |
| `raw/formal/admin-requests-w3b-formal-20261112T1652Z.jsonl` | 30 次正式 admin 请求逐条 raw |
| `raw/formal/app-rerank.log` | staging adapter 的 `results_count_mismatch` 日志 |
| `raw/formal/topn-truncation-diagnostic.json` | `top_n=8` vs 省略 `top_n` 的受控对照 |
| `raw/formal/local-proxy-rerank-calls.jsonl` | 本地代理收到的 31 次 `/rerank` |
| `raw/formal/staging-tunnel-requests.jsonl` | staging tunnel 统计的 31 次 `/rerank` |
| `raw/formal/lock-release.json` | staging 双锁释放记录 |
| `raw/formal/formal-run.log` | harness 运行摘要 |

---

## 5. 限定与未验证项

- formal run 1 的 8 档失败是 adapter 契约失败，不能归因于模型延迟；模型/成功样本
  延迟仅作诊断。
- 20/50 档和 60 次观测未运行，不能写成通过或失败。
- 仍没有并发、多实例、真实业务流量、shadow、assignment 或 canary 证据。
- 15 个门禁样本 <200，formal 结论标 `LOW_CONFIDENCE`；但 2/15 失败已超过 1% 门禁。
- Vulkan Flash Attention 禁用、4 slots、batch/ubatch 512 是本机运行特征，不能直接
  外推到其它硬件或部署参数。

---

## 6. 证据索引（preflight + formal）

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `raw/smoke-2-doc.json` | 2 文档冒烟与首请求 | `828f755de0a060712e0d5cac6a3a649d406e79c50b581d43c6e9a3da28f93b81` |
| `raw/preflight-tier8.json` | 8 档完整 warm-up + 45 样本 | `3f2d219e14fe0e1e30b2acec70e043783d463ce70bad7ef0241f4656b61e0afb` |
| `raw/preflight-tier8-single-warmup.json` | 单 case warm-up 冷长尾 | `7b16a98e9756492eaae65443057770039254f9fb9011b474e3917926fc1f772b` |
| `raw/preflight-tier20-50.json` | 20/50 档完整 warm-up + 各 45 样本 | `960be09494f60734bfdcb8dc1628173ad2ff934ca0d2573a3d2bc3c9ff5f653d` |
| `raw/fixture-validation.json` | 15 case 与 8/20/50 补齐校验 | `783bacd8c85e1c6f3de9e050a6c7e03c7eae96f6ee11619a1e62befeca013a3c` |
| `raw/llama-server.log` | preflight llama.cpp 运行日志与 slot 时序 | `396f8c239dbd3dff48df158740a4283a288837f372a73b6a04285170f9b6d5c3` |
| `raw/llama-server-version.txt` | llama.cpp 构建版本 | `44c06bace66e6ff5a600f2a0172321d0c94ae47fc27eaa1b4554bcaf9c1ffcec` |
| `raw/formal/formal-summary.json` | formal run 1 aggregate / IDs / failure detail | `dfa5a2f1b808820c1ca4c0c9d60aa917798aacaab15ac1abe9a6f78c1dcef672` |
| `raw/formal/admin-requests-w3b-formal-20261112T1652Z.jsonl` | formal run 1 逐请求 raw | `64e2eb1f0072e4299d2dffbfc27eaaf14fe4ece4b2f82530dbd1c4c6821bdd14` |
| `raw/formal/app-rerank.log` | staging adapter 失败日志 | `56b1df86a4637f70d43f7c346fd5641b55b20817bf25533e4b5aa8218554486c` |
| `raw/formal/topn-truncation-diagnostic.json` | top_n 截断对照 | `792cab29840727bf759e9d521f4fe88c9f80babc591381418abca66573664d78` |
| `raw/formal/local-proxy-rerank-calls.jsonl` | 本地 `/rerank` 调用 31 次 | `df7fdd00ca59fab8574a62369c95bb51038d2b773defe0da10fd75b1181d3a17` |
| `raw/formal/staging-tunnel-requests.jsonl` | staging tunnel `/rerank` 调用 31 次 | `9901d3bf848214b684ea321a434f51644a3a3f238eac45af2aacad2813652835` |
| `raw/formal/lock-release.json` | staging 双锁释放记录 | `73acffe6737f3d25ed7a833653cc87d56bad4810b4c2cff4995cc6295ca1663e` |

---

## 7. 收尾

- staging `staging-box` / `staging-model` 已释放；D 可接手等待 A 修复后的新 run。
- `local-gpu` 仍由 Lane B 持有；本地 llama.cpp/计数代理保留用于重跑。
- 未修改 `RERANK_MODE`、任何默认开关、历史迁移或主 worktree。
- A 修复并通知后，执行新的 formal run；旧 FAIL raw 保留，不覆盖。
