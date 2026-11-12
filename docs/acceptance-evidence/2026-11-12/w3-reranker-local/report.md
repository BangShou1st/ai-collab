# W3 Lane B：本地 Reranker 实测（run 1 adapter FAIL；run 2 20 档 timeout FAIL）

- 日期：2026-11-12 / 2026-11-13（Asia/Shanghai）
- worktree：`E:\project\ai-collab-w3-reranker-local`
- 分支：`w3/reranker-local`
- 基线：`d6fa5133`；集成 A `2412b8be`，重跑基于 `dev@99c47d5e`
- staging：`box-aae54f` / `ai-collab-app` 8080，run 2 镜像 `ai-collab-backend:local`；Flyway 97
- 资源锁：`staging-box` / `staging-model` 已释放给 C；`local-gpu` 已释放
- 当前状态：**FORMAL_RUN_1_FAIL / FORMAL_RUN_2_FAIL / STOPPED**
- 最终标签：**FAIL (20 candidates timeout)**（run 2：8 PASS、20 FAIL、50 NOT_RUN）
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
| formal run 1（8 档） | **FAIL**：2/15 `adapter_INVALID_RESPONSE / results_count_mismatch`；r10/r12 |
| formal run 2（8 档） | **PASS**：15/15；model P95 `1714ms`；overhead P95 `19.982ms` |
| formal run 2（20 档） | **FAIL**：15/15 `adapter_TIMEOUT`；adapter 上限 `3003ms`；本地真实 `/rerank` `3.9–5.8s` |
| formal run 2（50 档） | **NOT_RUN**：20 档失败立即停止 |
| formal 60 次观测 | **NOT_RUN**：run 2 门禁未通过 |
| 1:1 调用计数 | PASS：run 1/run 2 各 proxy expected 30 / observed 30 |
| 证据等级 | preflight=`DIAGNOSTIC_ONLY`；两次 formal=`FAIL`；不得写成 W3 PASS |

根因不是模型或延迟：A 的 `LlamaCppRerankAdapter` 请求带了 `top_n=topK(8)`，
llama.cpp 对 9/10 个候选也截断为 8 个 results，adapter 却要求 results 数等于
candidateCount。冻结集的 8 档包含 `r10=9` 与 `r12=10`，因此 formal 8 档确定性失败。

A 已在 `35bf52b3` 修复为 `top_n = payloadDocuments.size()`；本地
`LlamaCppRerankAdapterTest` 11/11 通过，其中 stub 覆盖 9/10/20/50 文档并断言
`top_n == 候选数`。run 2 的 8 档 15/15 PASS，确认该修复有效；但 20 档在连续
formal 负载下真实 `/rerank` 耗时 `3.9–5.8s`，全部超过 adapter 3s 上限，run 2
整体 **FAIL (20 candidates timeout)**。旧 run 1/run 2 原始证据均保留。

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

### 4.4 run 1 停止

- 按冻结规则，8 档失败立即停止：**20/50 档 NOT_RUN**，**60 次观测 NOT_RUN**。
- run 1 原始 FAIL 证据保留，不删除、不改写为 PASS。
- run 1 的根因已由 A `35bf52b3` 修复；修复后的 run 2 见下一节。

### 4.5 run 2（A 修复后）

#### 4.5.1 配置与部署

| 项 | 值 |
| --- | --- |
| 代码 | `dev@99c47d5e`，含 A `35bf52b3` top_n 修复 |
| 镜像 | `ai-collab-backend:local`，image ID `8700716f402fb3fa9015c736777213fa3c6d0b9a2a9b3a541c3f67ad9c9054b9` |
| app 容器 | `6e85846c27023a17a7661331cc6be82df8377d9d49e8683d661662812ee2f731`，healthy |
| Flyway | schema version `97` |
| 默认开关 | `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static` |
| admin test 端点 | `POST /api/v1/admin/ai/capability-profiles/39dc9343-0cda-4085-be80-22b34099982c/test` |
| provider ID | `20b0d69e-3d96-4c07-9337-5cdbbf77da04` |
| model profile ID | `ce5efeb1-7435-4f0b-b312-07b67d19840d` |
| capability profile | `39dc9343-0cda-4085-be80-22b34099982c`，version 3，PUBLISHED |
| provider base URL | `http://172.23.0.1:18081`（staging-only SSH stdio tunnel） |
| 默认 2 文档测试 | SUCCESS；relevant `0.9962389` > irrelevant `0.0000321`；model `289ms` |
| 1:1 调用计数 | proxy expected 30 / observed 30 |

run 2 的 8 档通过证明 top_n 修复有效：9/10 文档不再被服务端截断，
8 档没有 adapter 计数错误。

#### 4.5.2 门禁结果

| 档位 | attempts | failures | failure types | known ratio | model | e2e | overhead | 1:1 | 判定 |
| ---: | ---: | ---: | --- | ---: | --- | --- | --- | --- | --- |
| 8 | 15 | 0 | 无 | 1.0 | P50 594ms / P95 1714ms | P50 604.079ms / P95 1723.361ms | P50 12.427ms / P95 19.982ms | 30/30 | **PASS** |
| 20 | 15 | 15 | `adapter_TIMEOUT`×15 | 1.0（仅 timeout 值） | P50 3003ms / P95 3004ms（adapter 上限） | P50 3015.539ms / P95 3070.06ms | null（无成功样本） | 30/30 | **FAIL** |
| 50 | — | — | — | — | — | — | — | — | **NOT_RUN** |
| 60 次观测 | — | — | — | — | — | — | — | — | **NOT_RUN** |

**口径纠正**：20 档的 `modelLatencyMs=3003/3004ms` 是 adapter 的 **3s timeout
上限**，不是模型完成耗时；`model_latency_known_ratio=1.0` 只表示响应里存在这个
timeout 值，不能写成“真实 model P95 已完成”。20 档成功的 overhead 样本为 0。

#### 4.5.3 根因诊断

结论：**20 档失败是 formal 连续负载下真实超过 3s**，不是 adapter 计数/协议 bug；
8 档 PASS 说明 `top_n` 修复有效。

| 检查项 | 证据 | 判断 |
| --- | --- | --- |
| adapter 3s timeout | 15/15 `adapter_TIMEOUT`，model latency 3001–3004ms | 直接失败机制是 3s deadline；Java overhead 不是主因（8 档成功 overhead P95 19.982ms） |
| tunnel / proxy 排队 | run 2 proxy 1:1=30/30；官方 20 档本地 `/rerank` P50 4724.8ms、P95 5536.4ms、max 5793.7ms，30/30 >3s | 不是单纯 tunnel 统计/计数问题；隧道后的本地模型调用本身已超过 3s |
| 请求节奏 | harness 串行发请求；无并发评测；但被杀的首个 60s run 留有 46 次 pre-official 本地请求（13 次 20 文档），造成服务状态/排队混淆 | 不把该混淆当作 PASS 理由；正式 20 档仍全部超时，FAIL 结论保留 |
| maxLength / 文本长度 | run 2 与 preflight 都使用同一 2048 上限和 ≤2000 字符补齐文本；8 档也观察到整体变慢 | 文本长度不是唯一解释 |
| top_n 修复 | 8 档 15/15 通过；9/10 文档不再 `results_count_mismatch` | 计数/协议修复有效；20 档剩余问题是延迟预算 |

`local-proxy-rerank-calls.jsonl` 中官方 run 2 的 20 档 30 次本地 `/rerank`
全部 3.9–5.8s；preflight 同一 20 档 P95 为 1262.537ms。该差异处于正式连续负载
与本机服务状态/排队的混合区间，中心已明确禁止再次重跑；不得用 preflight 推翻
formal FAIL。

#### 4.5.4 formal run 2 原始证据

| 文件 | 用途 |
| --- | --- |
| `raw/formal-run2/formal-summary.json` | run 2 aggregate / IDs / 20 档 timeout 明细 |
| `raw/formal-run2/admin-requests-w3b-formal2-20261112T1747Z.jsonl` | run 2 逐请求 raw |
| `raw/formal-run2/app-rerank.log` | staging adapter `TIMEOUT` 日志（35 条，含 aborted run） |
| `raw/formal-run2/local-proxy-rerank-calls.jsonl` | 本地 `/rerank` 105 次，含 aborted/官方 run；官方 20 档 30/30 >3s |
| `raw/formal-run2/staging-tunnel-requests.jsonl` | staging tunnel `/rerank` 统计 |
| `raw/formal-run2/llama-server-formal.log` | llama.cpp 运行日志 |
| `raw/formal-run2/adapter-fix-stub-test.log` | A adapter 9/10/20/50 stub 回归，11/11 |
| `raw/formal-run2/lock-release.json` | staging 双锁与 local-gpu 释放记录 |
| `raw/formal-run2/formal-run.log` | run 2 harness 摘要 |

---

## 5. 限定与未验证项

- run 1 的 8 档失败是 adapter 契约失败；run 2 的 20 档失败是真实 20 候选
  `/rerank` 超过 3s，二者不能合并成同一个根因。
- run 2 的 50 档和 60 次观测未运行，不能写成通过或失败。
- 20 档 timeout 的 `modelLatencyMs` 只是 adapter 上限；没有真实完成耗时。
- 仍没有并发、多实例、真实业务流量、shadow、assignment 或 canary 证据。
- 15 个门禁样本 <200，formal 结论标 `LOW_CONFIDENCE`；但 20 档 15/15 失败
  已远超 1% 门禁。
- 本机 Vulkan Flash Attention 禁用、4 slots、batch/ubatch 512 是运行特征，不能
  直接外推到其它硬件或部署参数。

---

## 6. 证据索引（preflight + formal run 1 + formal run 2）

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
| `raw/formal-run2/formal-summary.json` | run 2 aggregate / 20 timeout | `08853357eca8cbc8231a6803d784dc09538d795040b8e9ea01f811f01dc423bf` |
| `raw/formal-run2/admin-requests-w3b-formal2-20261112T1747Z.jsonl` | run 2 逐请求 raw | `9353d39801a1df41e7c051d8a243b7ccc136b27691de05cc1de94ce68a82570d` |
| `raw/formal-run2/app-rerank.log` | run 2 adapter timeout 日志 | `08632bdfbec4452ce06cdbea54167cb67e70d1c57adc016f8e717d20e1cbfe9c` |
| `raw/formal-run2/local-proxy-rerank-calls.jsonl` | 本地 `/rerank` 105 次 | `ec31b6d666c15f8388fc4e3c1c8193ea3b2d8c604cf0c75dd195ebb072029f3b` |
| `raw/formal-run2/llama-server-formal.log` | llama.cpp 运行日志 | `6a1e06ec7cb6e101f2f8e30f0f8972cb57cec1782066965fdbdbabd82ef66864` |

---

## 7. 收尾

- run 1 与 run 2 的原始 raw 均保留，互不覆盖。
- staging `staging-box` / `staging-model` 已于 `2026-11-13T01:54:15+08:00`
  释放给 C；`local-gpu` 也已释放。
- 本地 llama-server、计数代理和 staging tunnel 已停止；staging app 保持 healthy。
- 未修改 `RERANK_MODE`、任何默认开关、历史迁移或主 worktree。
- 最终结论：**FAIL (20 candidates timeout)**；生产用户路径仍为
  `NOT_VERIFIED`。是否把产品候选上限改为 8 由中心/用户决策。
