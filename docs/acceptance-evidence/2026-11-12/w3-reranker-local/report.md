# W3 Lane B：本地 Reranker 实测（preflight 完成，formal 未运行）

- 日期：2026-11-12 / 2026-11-13（Asia/Shanghai）
- worktree：`E:\project\ai-collab-w3-reranker-local`
- 分支：`w3/reranker-local`
- 基线：`d6fa5133`
- 资源锁：`local-gpu`（owner=lane-b）；未占用 `staging-box` / `staging-model`
- 当前状态：**PREFLIGHT_COMPLETE / FORMAL_NOT_RUN**
- 最终标签：**尚未生成**。formal 完成后只能是
  `LIVE_VERIFIED (staging, local reranker, admin capability test)` 或 `FAIL`。
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
| formal admin capability test | **NOT_RUN**：Lane A 尚未合并到 `dev`，且 staging 被 Lane C 占用 |
| 证据等级 | preflight=`DIAGNOSTIC_ONLY`；formal=`NOT_RUN`；不得写成 W3 PASS |

50 档本地诊断是明确的提前风险信号：即使不计 Java/HTTP 服务开销，单次
50 文档的模型端到端已整体超过 3s。该数字不能替代 staging formal gate。

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

## 4. Formal（未运行）

| 项 | 状态 |
| --- | --- |
| 管理 API 创建/发布 RERANK Provider + Model Profile + Capability Profile | NOT_RUN |
| admin capability test 8/20/50 各 15 次门禁 | NOT_RUN |
| 8/20 通过后的 60 次观测 | NOT_RUN |
| formal 标签 | PENDING |

阻塞：

1. Lane A 的配置平台与 Adapter 仍是未提交工作区，`dev` 仍为 `d6fa5133`。
2. `staging-box` 与 `staging-model` 由 Lane C 持有；Lane B 不能抢占。
3. 裸 llama.cpp 没有 Java model latency / overhead，不能替代 formal。

formal 执行时必须满足：

- 管理员 API 创建/发布 Provider、Model Profile、Capability Profile；
- 使用已发布 profile 的 admin capability test 端点，一次请求一次 `/rerank`；
- 8/20/50 各 15 次门禁；8 档失败立即停；8/20 过、50 失败即整体 FAIL；
- 指标分列 model / e2e / overhead；
- 失败包含 timeout / 5xx / 非法响应；429/5xx 不从分母剔除；
- formal 标签只能是 `LIVE_VERIFIED (staging, local reranker, admin capability test)` 或 `FAIL`；
- 生产用户路径仍写 `NOT_VERIFIED`，因为 `RERANK_MODE=java` 未变。

---

## 5. 限定与未验证项

- 本地 preflight 是**串行、单机、直接 llama.cpp**，不是 staging admin 路径；
  不包含 Java 路由、HTTP 序列化、admin controller、反向隧道/relay 开销。
- `model_latency_known_ratio`、`overhead` 未测；formal 前不得用 0 或本地 wall 估算。
- 没有并发、多实例、真实业务流量、shadow、assignment 或 canary 证据。
- 15/45/60 样本均 <200，不能外推生产置信区间。
- Vulkan Flash Attention 禁用、4 slots、batch/ubatch 512 是本机运行特征，不能直接
  外推到其它硬件或部署参数。

---

## 6. 证据索引

| 文件 | 用途 | SHA-256 |
| --- | --- | --- |
| `raw/smoke-2-doc.json` | 2 文档冒烟与首请求 | `828f755de0a060712e0d5cac6a3a649d406e79c50b581d43c6e9a3da28f93b81` |
| `raw/preflight-tier8.json` | 8 档完整 warm-up + 45 样本 | `3f2d219e14fe0e1e30b2acec70e043783d463ce70bad7ef0241f4656b61e0afb` |
| `raw/preflight-tier8-single-warmup.json` | 单 case warm-up 冷长尾 | `7b16a98e9756492eaae65443057770039254f9fb9011b474e3917926fc1f772b` |
| `raw/preflight-tier20-50.json` | 20/50 档完整 warm-up + 各 45 样本 | `960be09494f60734bfdcb8dc1628173ad2ff934ca0d2573a3d2bc3c9ff5f653d` |
| `raw/fixture-validation.json` | 15 case 与 8/20/50 补齐校验 | `783bacd8c85e1c6f3de9e050a6c7e03c7eae96f6ee11619a1e62befeca013a3c` |
| `raw/llama-server.log` | llama.cpp 运行日志与 slot 时序 | `396f8c239dbd3dff48df158740a4283a288837f372a73b6a04285170f9b6d5c3` |
| `raw/llama-server-version.txt` | 构建版本 | `44c06bace66e6ff5a600f2a0172321d0c94ae47fc27eaa1b4554bcaf9c1ffcec` |

---

## 7. 收尾

- llama-server 已停止；`18081` 已释放；GPU 回落到约 `2274 MiB`。
- 未修改 `RERANK_MODE`、任何默认开关、历史迁移或主 worktree。
- `local-gpu` 仍由 Lane B 持有，等待 formal；staging 锁未占用。
- 正式测量完成后，本文件会更新为 formal 结果，并按最终标签冻结。
