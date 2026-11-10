# MQ 派发延迟本地实测报告（mq-dispatch-latency）

> 分支 `bench/load-evidence` @ `8e2f0488` · 实测 2026-11-09 02:31–02:58 (UTC+8) · 方法学见 [README.md](README.md)
> 所有数值可由 `data/` 原始 CSV 复算；取不到的指标如实标注"未测"，无推算值。

## 结论总表

### 指标 1：派发时延 UPLOADED→PARSING（claim）

| 口径 | 模式 | n | P50 | P90 | P95 | P99 | max |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 逐份（并发 1，间隔 500ms） | **MQ 开** | 20 | **46 ms** | 64 ms | 64 ms | **68 ms** | 68 ms |
| 逐份（并发 1，间隔 500ms） | **MQ 关（进程内）** | 20 | **68 ms** | 107 ms | 107 ms | **113 ms** | 113 ms |
| 突发（并发 8） | **MQ 开** | 40 | 10.80 s | 21.46 s | 24.06 s | 24.17 s | 24.17 s |
| 突发（并发 8） | **MQ 关（进程内）** | 40 | 14.10 s | 26.57 s | 27.95 s | 29.29 s | 29.29 s |

核心读法：

1. **无排队（逐份口径）下，MQ 派发时延 P50≈46ms / P99≈68ms，优于进程内路径（68/113ms），两者同为几十毫秒量级**，均含轮询粒度误差（40ms 间隔 + 列表请求耗时），按上界解读。MQ 开略快的原因：MQ 消费线程直接执行 claim，进程内路径多一层 `documentTaskExecutor` 线程池中转。
2. **突发口径下两种模式的时延都被"有界处理并发 × 单文档处理时长"主导**：MQ 消费线程 4 条、进程内 executor 2–4 条（队列 100 深时池不扩容，实际约 2 条），单文档解析+嵌入 ≈2.4–2.6s（真实外部 embedding HTTPS 调用）。40 份突发在 MQ 开下按 4 条消费线程分批 claim（时延呈 ~2.5s 阶梯），MQ 关下更慢（约 2 条工作线程）。此时延反映的是处理管线容量，不是派发机制差异；突发下 MQ 开反而略优（P50 10.8s vs 14.1s）。
3. 逐份口径的时延阶梯（MQ 开 46→68ms、MQ 关 68→113ms 尾部）仍受上一份文档占用消费/工作线程的影响，严格意义上是"近无排队"而非零排队。

### 指标 2：kill 后端 → 重启恢复（MQ 开，故障注入）

| 项目 | 实测值 |
| --- | --- |
| 故障注入 | 第 20 份上传响应后 `taskkill /F` 后端 JVM（实际 25 份拿到响应、28 份已落库，3 份响应丢失但落库成功） |
| 重启前快照 | UPLOADED 24 / READY 20（含逐份轮次）/ INDEXING 4（kill 时处理中） |
| 进程启动 → API 可登录（healthyAt） | 13.2 s |
| 进程启动 → actuator 8081 UP | 14.2 s（含 MQ 消费者接入） |
| **24 份 UPLOADED 文档恢复（重新 claim）** | **24/24 在 healthyAt 后 ≤15s 内全部离开 UPLOADED**；其中 6 份在首次轮询（healthyAt+59ms）时已 claim，其余 18 份在 4.0–12.2s 间分批 claim（P50 7.7s；阶梯 ≈ 4 条消费线程 × 2.4s/份的处理节奏） |
| 恢复路径构成（对账表） | 16 份经 MQ 消息重投递（有 parse 幂等记录）+ 8 份被启动即跑的恢复对账 Job（`dispatch-initial-delay=0s`）经进程内路径抢到（无 MQ 幂等记录，claim CAS 仲裁，无重复） |
| **恢复后零重复** | 48 份文档 parse 幂等记录数**无一 >1**；44 份 READY、4 份卡 INDEXING |
| kill 时处理中的 4 份 INDEXING | 未被重新派发（防重复处理的保守设计）；按 `DocumentRecoveryService` 15 分钟 stale 窗口应被标记 FAILED 供用户重试——实测确认见下文"补充确认" |

对照说明（机制层，非实测对照）：旧轮询时代的唯一恢复通道是周期对账扫描，UPLOADED 文档的恢复时延上限 = 轮询周期（本项目历史上曾为 30s 主派发 + 10–15min stale 窗口）；M1 迁移后主通道是 MQ 重投递（秒级，本实测 ≤15s 且受处理节奏而非机制限制），对账 Job 仅作 300s 兜底。旧机制的实测 before 数字本环境无法复现，不作编造。

### 指标 3：双实例零重复（MQ 开，8080+8082 共享消费组）

| 断言 | 结果 |
| --- | --- |
| 上传 | 32 份并发上传（两实例轮转），32/32 成功 |
| 消费组拓扑 | `mqadmin consumerConnection` 确认 2 个客户端（两实例）同组 CLUSTERING 消费 |
| 恰好处理一次 | 32/32 文档 parse 幂等记录数 = 1（max=1，无一份 >1） |
| 数据一致性 | 32/32 终态 READY，`document_chunk` 实际行数 = `chunk_count`（每份 19），无一卡中间态 |
| 消费积压/重投 | `consumerProgress` Diff=0，RETRY 队列 0 条 |
| 顺带测得 | 双实例下 claim 时延 P50=6.46s / P99=22.3s（突发口径，两实例 8 条消费线程瓜分 32 份，同样被 embedding 处理时长主导） |

## 局限性（诚实清单）

1. **单机噪声**：后端、RocketMQ、PostgreSQL、MinIO、压测脚本同机运行，CPU/磁盘互相争抢；同一口径未做多次重复取中位。
2. **无生产对照**：本数据仅代表"本地开发机 + Docker 基建 + 单/双实例"环境，不能外推生产吞吐与时延。
3. **观测粒度**：claim 时延以 40ms 轮询间隔的列表接口观测，每个样本是上界；毫秒级差异（如 46 vs 68ms）在该粒度下应视为同量级。
4. **样本量小**：每口径 20–40 份，P99 实为尾部 1–2 个样本，波动大。
5. **处理时长主导突发时延**：外部 embedding 调用（~2.4s/份）是有界并发下的排队主导因素；若更换嵌入模型/网络，突发口径数字会明显变化。逐份口径受其影响小。
6. **agent run 场景（QUEUED→RUNNING）未测**：需要真实模型调用（每 run 消耗模型额度且时延受模型服务波动支配），本次以机制说明代替：agent run 入队通知走 `agent-run-queued` topic（消费线程同理 4 条），claim 由 `agentClaimVersion` CAS 与租约机制保护，与文档链路同一套幂等/对账骨架。
7. **kill 注入时点有随机性**：`--kill-after 20` 触发时刻受并发上传进度影响，25 份拿到响应、28 份落库；恢复数字以"重启前 UPLOADED 24 份"为分母，单次故障注入不构成故障率统计。
8. **压测文档进入共享库**：本次测试数据落在主库的 4 个独立 `BENCH-MQL-*` project，任务收尾时已连同全部文档删除（无残留）；未使用独立的压测数据库实例（`ai-collab-bench-pg:5433` 为更早轮次遗留，本次未用）。更早的 `BENCH-MQ*` project 属前任会话产物，未在本任务中处理。

## 补充确认（19:03 UTC+8 实测确认）

- 4 份 kill 时卡 INDEXING 的文档：15 分钟 stale 窗口到期后的第一个恢复对账周期内（kill 后约 15–18 分钟）被标记 FAILED，error_message = "文档处理心跳超时，请重试"，用户手动重试即走新消息重新处理——"失败可区分"要求满足。原始数据见 `data/stale-failed-marking.csv`。

## 环境快照

- Windows 11 (10.0.26200) · Java 21.0.10 · Node 24.14 · Docker Desktop (WSL2)
- RocketMQ 5.3.3（broker-a，单主）· PostgreSQL 17.11 (pgvector) · Redis 7 · MinIO
- 后端：Spring Boot 4.1.0，`local` profile，`MQ_ENABLED=true/false` 按 `.env` 切换
- 原始日志：`ai-collab-backend/target/local-dev/{backend,backend2,mqoff,mqon2,mqon3}-stdout.log`（任务收尾时按规范清理临时产物，日志文件属 `target/` 不入库）
