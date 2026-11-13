# E1：rerank shadow bring-up

## 配置

- 代码改动：`0`。
- worker 镜像：`ai-collab-ai-worker:dev`，`docker build` exit=0。
- 启动：`docker compose --profile ai-worker up -d ai-worker-api`，临时 override 仅把 worker 发布到 `127.0.0.1:19100`。
- `/healthz`：200；`/readyz`：200，`prompts=ok`、`auth=ok`。
- 应用进程：`18083`，独立数据库 `ai_collab_lh_e1`（从本地库复制）。
- 模式：`RERANK_MODE=shadow`、`RERANK_SHADOW_SAMPLE_RATE=0.1`、`DECIDE_MODE=shadow`、`PLANNING_DRAFT_MODE=shadow`。
- 仓库默认未改：`RERANK_MODE/DECIDE_MODE/PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、内部/worker token 均为 `static`。
- 模型：本机 Magpie `:3425`，模型 `workbuddy/deepseek-v4.1-flash`；通过一次性 Cloudflare quick tunnel 提供应用出站策略要求的 HTTPS endpoint，隧道与容器已回收。

## 本地流量

### 知识问答主链

- 160 次真实问答（8 并发）：158 次 HTTP 200，2 次 HTTP 502 `AI_PROVIDER_ERROR`。
- P95 5.13s，P50 4.03s，wall 85.25s。
- **`rerank.shadow.*` 指标前后均为 0**：知识问答主链按设计固定 `DENSE_LEGACY`，不经过 `RerankModeRouter`，因此没有 shadow 调用。
- 结论：shadow 一致率 / topK overlap / score delta `NOT_RUN`，原因是当前产品路径不触发；不以问答流量冒充 shadow 样本。

### 真实 worker rerank

| 并发 | 请求 | HTTP 200 | 失败 | worker P95 | model P95 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 30 | 30 | 0 | 4.841s | 4.795s |
| 8 | 50 | 42 | 8×503 `MODEL_UNAVAILABLE` | 4.343s | 4.224s |

8 并发首波 8 个请求均从 worker 收到 Java 模型网关 503；后续请求恢复，说明这是本地并发/网关瞬时压力路径，不据此写质量结论。所有 200 响应均返回 8 个候选分数，分数 id 覆盖输入候选，且同一请求形状内 topK 排序稳定。

## 状态

- `MEASURED`：worker 健康、真实 Magpie rerank、HTTP/模型延迟、成功/失败数与并发分布。
- `NOT_RUN`：Java shadow 一致率（无运行时触发路径）、真实 shadow 后台开销、decide/planning shadow 真实流量。
- 未切换任何默认模式，未使用 Gemini，未改 provider/Gateway/MQ。

## 证据

- `raw/worker-health.json`
- `raw/worker-image.txt`
- `raw/shadow-traffic-summary.json`（160 次问答与 shadow 指标前后值）
- `raw/worker-rerank-summary.json`（2 并发 30 次直接 worker 调用）
- `raw/worker-rerank-concurrency8.json`（8 并发 50 次，含失败分布）
- `scripts/run_traffic.py`、`scripts/run_worker_rerank.py`
