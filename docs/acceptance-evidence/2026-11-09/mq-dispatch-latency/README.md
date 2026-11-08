# MQ 派发延迟本地实测证据（mq-dispatch-latency）

> 任务基线：分支 `bench/load-evidence` @ `8e2f0488`（worktree `E:\project\ai-collab-bench`）
> 实测时间：2026-11-09 02:31 – 02:58（UTC+8，文档表内时间为 UTC）
> 结论报告见 [report.md](report.md)。本文件记录方法学与复现步骤。

## 1. 环境与端口拓扑

| 组件 | 说明 |
| --- | --- |
| 后端实例 | 本机 Java 21 进程，`local` profile，业务 API `http://localhost:8080`，**actuator 独立管理端口 `http://localhost:8081`**（8080 上 `/actuator/**` 404 是预期行为） |
| 双实例（指标 3） | 第二实例 `local,dual` profile：业务 8082 / 管理 8083（`dev.ps1 start-dual`） |
| RocketMQ | Docker 容器 `ai-collab-bench-rocketmq-namesrv`（127.0.0.1:9876）+ `ai-collab-bench-rocketmq-broker`（DefaultCluster/broker-a，RocketMQ 5.3.3）；topic `document-processing` / `plan-generation` / `agent-run-queued` 由 topic-init 预建 |
| 数据库 | Docker 容器 `ai-collab-postgres`（PostgreSQL 17 + pgvector，`localhost:5432/ai_collab`）；`ai-collab-bench-pg`(5433) 是更早一轮压测遗留，本次未使用 |
| MinIO / Redis | `localhost:9000` / `localhost:6379`（与后端同机） |
| 总开关 | 仓库根 `.env` 的 `MQ_ENABLED`（true/false），Spring `application.yml` 以 `${MQ_ENABLED:false}` 读取；`document.dispatch-interval=300s`、`dispatch-initial-delay=0s`（恢复对账 Job），stale 判定 15 分钟（`DocumentRecoveryService`） |
| Embedding | 系统已配置真实嵌入模型（外部 HTTPS 调用），单文档处理耗时 ≈2.4–2.6s（解析+分块+嵌入），是本测压测下排队时延的主导因素 |

测试数据全部落在独立 project（`BENCH-MQL-*` 前缀），不混入业务数据。凭据只存在于 `.env` 与 `target/` 下的临时文件，未写入本目录任何文件。

## 2. 工具与方法

- 负载与观测脚本：`bench.mjs`（Node 22+，零第三方依赖；由本次会话基于前任代理同款脚本核对后使用）。
  - `setup`：登录引导管理员（`POST /api/v1/auth/login`，带 `Origin: http://localhost:5173`——登录属 Cookie 端点，缺 Origin 会被 `AuthRequestOriginValidator` 403）→ 创建独立 project。
  - `docs`：并发上传 N 份 ~2KB 纯文本（multipart，`POST /api/v1/projects/{projectId}/documents`），同时以固定间隔轮询文档列表接口，记录每份文档**首次离开 UPLOADED**（即 claim 完成，UPLOADED→PARSING）的客户端时刻；`--kill-after N --kill-pid <pid>` 支持在第 N 份上传响应后 `taskkill /F` 后端进程（指标 2 故障注入）。
  - `watch`：后端宕机期间循环重试登录，首次登录成功即记为 `healthyAt`（"API 恢复服务"时刻），随后记录每个目标文档首次离开 UPLOADED 的时刻。
- 时钟口径：全部时间戳为压测进程的 `Date.now()`（与后端同机，无跨机偏移）。
- 时延口径（指标 1）：`latencyMs = 首次观测到 status != UPLOADED 的时刻 − 上传响应到达时刻`。轮询粒度 40ms + 单次列表请求耗时（数十 ms），故每个样本是该值的**上界**；首拍状态多为 INDEXING，说明真实 claim 早于观测，结果按上界解读。
- 指标 3 断言口径：每份文档在 `idempotency_record`（endpoint=`mq:document-processing:parse`，幂等键=documentId）中**恰好 1 条**，且 `document_chunk` 行数与 `chunk_count` 一致、终态非卡死。
- 百分位计算：`P_x = 升序第 ⌈x·n⌉ 个样本`（最近邻法）；可由 `data/*.csv` 与 `data/summary-metrics.json` 复算。

## 3. 复现步骤

1. `dev.ps1 start-dual`（或单实例 `dev.ps1 start`）；确认 `curl http://localhost:8081/actuator/health` 返回 `UP`，`docker exec ai-collab-bench-rocketmq-broker sh -c '/home/rocketmq/rocketmq-5.3.3/bin/mqadmin clusterList -n rocketmq-namesrv:9876'` 可见 broker-a。
2. 指标 1（MQ 开）：`.env` `MQ_ENABLED=true` 下，`node bench.mjs setup ... && node bench.mjs docs --count 40 --concurrency 8 --interval 40`（突发口径）与 `--count 20 --concurrency 1 --pace-ms 500`（逐份口径）。
3. 指标 1 对照（MQ 关）：`.env` 改 `MQ_ENABLED=false`，重启后端，同口径重复。
4. 指标 3：`dev.ps1 start-dual` 起双实例，`docs --bases http://localhost:8080,http://localhost:8082 --count 32 --concurrency 8`（两实例轮转上传），随后按 §2 断言口径查库。
5. 指标 2：单实例 MQ 开，`docs --kill-after 20 --kill-pid <pid>` 注入宕机；启动 `watch` 后重启后端，记录恢复时延与逐份对账。
6. 收集 `data/*.csv`，按 §2 口径复算百分位。

## 4. 数据文件清单

| 文件 | 内容 |
| --- | --- |
| `data/docs-mq-on.csv` / `data/docs-mq-off.csv` | 指标 1 突发口径（N=40，并发 8），MQ 开/关 |
| `data/docs-mq-on-paced.csv` / `data/docs-mq-off-paced.csv` | 指标 1 逐份口径（N=20，并发 1，间隔 500ms），MQ 开/关 |
| `data/docs-dual.csv` | 指标 3 上传与 claim 观测（N=32，双实例轮转） |
| `data/dual-zerodup.csv` | 指标 3 断言原始数据：每份文档 status/chunk_count/chunk 实际行数/parse 幂等记录数 |
| `data/watch-recovery.csv` | 指标 2 watcher 输出（healthyAt、每份首次离开 UPLOADED 时刻） |
| `data/recovery-accounting.csv` | 指标 2 对账：每份文档终态/created_at/parse 幂等记录数/chunk 数 |
| `data/recovery-presnapshot.txt` | kill 后、重启前的状态快照（UPLOADED 24 / READY 20 / INDEXING 4） |
| `data/summary-metrics.json` | 全部汇总指标（由 CSV 复算） |
| `../bench.mjs` | 负载与观测脚本 |
