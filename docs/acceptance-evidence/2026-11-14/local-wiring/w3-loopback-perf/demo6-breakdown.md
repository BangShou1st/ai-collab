# demo6 任务分页 346ms 拆因

- 日期：2026-11-14
- 基线：`wiring/loopback-perf-archunit @ 1f95447e` 派生的 W3 worktree
- 接口：`GET /api/v1/projects/{id}/tasks?page=0&size=200`
- 隔离库：`ai_collab_w3_perf`（PostgreSQL 17，容器 `ai-collab-postgres`，宿主端口 5433）
- 隔离后端：18093，management 18096；本机 Magpie 3425 未参与本测量
- 数据：单项目 10,000 个任务，首页 200 条，响应 `87,495` bytes
- 状态：`MEASURED_LOCAL`；本轮未修改业务 SQL、分页阈值或生产配置

## 结论先行

本次在本机复现的 P95 为 `243.0–267.8ms`（另见 `303–315ms` 的单次 curl 客户端尾部），
不把历史上的单点 346ms 当作新的门禁。拆因结果指向 **PostgreSQL JIT**，而不是 Jackson、
DEBUG 日志或 HTTP 客户端：

- 同一页查询在 `jit=on` 时 `EXPLAIN ANALYZE` 执行时间中位数约 `217.1ms`；
- 在 `jit=off` 时同一查询降到 `20.8–33.3ms`，中位数约 `24.7ms`；
- 整个 HTTP 请求在 `jit=off` 时 P95 为 `29.7–30.0ms`，与历史服务层 `29ms`
  同一量级；
- 87,495 bytes 响应只用真实 record 类型做 Jackson 序列化，P95 `0.391ms`；
- DEBUG 下日志增长约 `15.5–19.3KB/请求`，但 `jit=off` 时 DEBUG/INFO 的 HTTP P95
  没有可区分差异（29.7ms vs 30.0ms）；
- pooled 连接与每次强制新连接的 HTTP 延迟相近，客户端读 body 的 P95 增量小于 1ms；
  本轮 curl 进程开销 P50 约 `10.2ms`，没有复现历史 `1356.5ms`。

## HTTP 测量

使用同一个 `.NET HttpClient`、warm-up 10 次、50 个正式样本；每次读取完整响应体。
表中 P50/P95/P99 均为客户端总耗时（含 TTFB 和响应体读取）。

| 日志级别 | DB `jit` | P50 (ms) | P95 (ms) | P99 (ms) | 响应 bytes | 原始文件 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| DEBUG | on | 196.1 | 267.8 | 277.9 | 87,495 | `raw/demo6-debug.json` |
| INFO | on | 171.8 | 243.0 | 304.5 | 87,495 | `raw/demo6-info.json` |
| DEBUG | off | 23.9 | 29.7 | 31.7 | 87,495 | `raw/demo6-debug-jit-off.json` |
| INFO | off | 22.2 | 30.0 | 30.7 | 87,495 | `raw/demo6-info-jit-off.json` |

`jit=on` 的 DEBUG 与 INFO 差值为 P95 `24.8ms`、P50 `24.3ms`；`jit=off` 时两者差值消失。
因此日志不是 200ms 级差异的解释项，且没有据此修改日志级别或阈值。

## 数据库拆因

`EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)` 在同一隔离库、同一项目数据上重复 5 次。
分页 SQL 的 CTE 会对 10,000 行过滤结果执行 count，并对首页 200 行做相关依赖子查询；
依赖边查询在本次数据上没有行，耗时可以忽略。

| 查询 | DB `jit` | 5 次 Execution Time (ms) | 中位数 (ms) | 证据 |
| --- | --- | --- | ---: | --- |
| 首页 CTE + count + 200 行 | on | 273.249, 189.307, 217.138, 211.197, 234.304 | 217.138 | `raw/demo6-db-explain.txt` |
| 首页 CTE + count + 200 行 | off | 23.228, 24.802, 33.314, 20.794, 24.662 | 24.662 | `raw/demo6-db-explain-jit-off.txt` |
| 200 个 task id 的依赖边查询 | on | 0.152, 0.169, 0.112, 0.095, 0.106 | 0.112 | `raw/demo6-db-explain.txt` |

`jit=on` 的 EXPLAIN 计划显示 `JIT: Total 231.074ms`（首轮），且页面 CTE 的
`Seq Scan on project_task` 与相关子查询是主要执行路径。该测量只说明当前本机库的
JIT/查询执行组合，不把单次 EXPLAIN 当作生产容量结论。

## Jackson 序列化

证据探针 `SerializationProbe.java` 使用实际 `ApiResponse<PageView<TaskView>>` record
反序列化捕获的 87,495 bytes 响应，再测 200 次 `ObjectMapper.writeValueAsBytes`：

| 指标 | 数值 |
| --- | ---: |
| 样本 | 200 |
| 序列化后 bytes | 87,495 |
| P50 | 0.238ms |
| P95 | 0.391ms |
| P99 | 0.748ms |

原始结果：`raw/demo6-serialization.json`。该探针只测 JSON 序列化，不含 DB、MVC、
Tomcat 或客户端。

## DEBUG 日志与客户端

| 项 | 结果 | 证据 |
| --- | --- | --- |
| DEBUG 日志增量 | JIT on：966,449 bytes / 50 样本 = 19,329 bytes/样本；JIT off：772,920 bytes / 50 = 15,458 bytes/样本 | `raw/demo6-debug.json`、`raw/demo6-debug-jit-off.json` |
| INFO 日志增量 | 本轮测量窗口约 299 bytes / 50 样本（含后台日志，不能当作每请求固定成本） | `raw/demo6-info.json` |
| pooled vs 新连接 | JIT on DEBUG：pooled P95 267.8ms，`Connection: close` P95 229.7ms；INFO：243.0ms vs 220.6ms | `raw/demo6-debug.json`、`raw/demo6-info.json` |
| TTFB vs 完整 body | DEBUG JIT on：TTFB P95 266.9ms，总 P95 267.8ms（body 读取增量 <1ms） | `raw/demo6-debug.json` |
| curl 客户端 | 10 次 transfer P50/P95 = 184.0/298.5ms；进程 wall P50/P95 = 194.2/314.8ms；进程开销 P50 = 10.2ms | `raw/demo6-curl-client.json` |

历史记录中的 `curl P95 1356.5ms` 未在本轮复现；本轮把 curl 进程本身也计入时只增加约 10ms。
因此没有把该历史值归因给服务端或客户端，也没有改动超时/阈值。

## 方法边界

- `jit=off` 是对隔离测试库 `ai_collab_w3_perf` 的临时诊断设置，测量后已 `RESET`；
  仓库代码、默认配置和共享 `5433` 基础设施的参数均未修改。
- 没有新增 Flyway 迁移，数据库仍为 V1–V101。
- 没有为让结果变好而修改 SQL、日志级别、分页大小或任何门禁。
- `raw/app-18093-*.txt` 是隔离进程的原始 stdout/stderr；本轮结束时进程已停止。
