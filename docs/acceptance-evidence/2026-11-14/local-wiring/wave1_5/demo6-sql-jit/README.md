# demo6 任务分页 SQL 重构与 JIT=on 复测

- 日期：2026-11-14
- Lane：Wave 1.5 Lane B
- Worktree：`E:\project\ai-collab-wt\fix-demo6-sql-jit`
- 分支：`fix/demo6-sql-jit`
- 基线：`dev @ d6982958`
- 端点：`GET /api/v1/projects/{projectId}/tasks?page=0&size=200`
- 隔离库：`ai_collab_lane_b_demo6`（宿主机 `127.0.0.1:5433`，容器
  `ai-collab-postgres`，PostgreSQL 17.11）
- 隔离后端：`18097`，management `18098`，`local` profile

## 状态

| 项 | 状态 |
| --- | --- |
| 分页 SQL 重构与测试补充 | `IMPLEMENTED` |
| Controller 契约与失败注入 | `TESTED(UNIT)` |
| PostgreSQL 分页集成 | `TESTED(INTEGRATION)` |
| 10,000 任务隔离真进程 HTTP | `TESTED(REAL_PROCESS)` |
| JIT=on HTTP 与 EXPLAIN 采样 | `MEASURED_LOCAL` |
| 阻塞项 | `BLOCKED`：无 |
| 全量 `mvnw verify` | `NOT_RUN` |
| push | `NOT_RUN` |

## 改动

`TaskMapper.listPageScoped` 现在分两段执行：

1. `filtered` 只从 `project_task` 取 `id, created_at`，并应用
   `project_id/status/assignee_id/milestone_id` 过滤；
2. `total` 对 `filtered` 计数，`page_ids` 对 `filtered` 做
   `ORDER BY created_at, id`、`LIMIT/OFFSET`；
3. 最终结果先用 `total LEFT JOIN page_ids` 保留空页 total 行，再只对页内 id
   回连 `project_task/app_user/milestone` 并计算 `unfinished_dependency_count`。

每页仍固定为两条 `TaskMapper` SQL：

- `TaskMapper.listPageScoped`：页、total 与依赖计数；
- `TaskMapper.listDependencyEdges`：200 个页内 id 合成一批查询依赖边。

真实进程日志中 10 次 warm-up + 50 次采样共 60 个请求，两条 Mapper
各出现 60 次，见 `raw/demo6-taskmapper-sql-count.txt`。未新增或删除 SQL，
未修改历史迁移，迁移仍为 V1–V101；未关闭或调整 JIT，未放宽阈值或既有断言，
也未删除、替换既有测试用例。

## 定向测试

```powershell
cd E:\project\ai-collab-wt\fix-demo6-sql-jit\ai-collab-backend
.\mvnw.cmd -B -ntp -Dtest=TaskListPaginationPostgresIntegrationTest,TaskControllerPaginationContractTest,TaskRepositoryFailureInjectionTest test
```

结果：9 tests，0 failures/errors/skips。10k 集成用例输出
`firstCallMs=10 p95Ms=15 perPageMapperSql=2 samples=50`，新增断言覆盖：

- 空页仍返回 total，且 `hasNext=false`；
- status/assignee/milestone 过滤与 `total`；
- `created_at` 相同时按 `id` 稳定排序并跨页；
- 依赖计数只统计同项目且状态不是 `DONE/CANCELED` 的前置任务；
- 10,000 任务首页固定最多 200 行，页内关联与依赖边各一条 SQL。

原始摘要：`raw/related-tests-summary.txt`。

## 隔离真进程复测

构建与复测命令：

```powershell
cd E:\project\ai-collab-wt\fix-demo6-sql-jit\ai-collab-backend
.\mvnw.cmd -B -ntp -DskipTests package
cd ..
.\docs\acceptance-evidence\2026-11-14\local-wiring\wave1_5\demo6-sql-jit\run-demo6-jit-on.ps1 -RecreateDatabase
```

脚本从既有根 `.env` 读取本地 demo 登录口令与数据库口令，不把凭据写入证据；
创建隔离库后启动新构建的 JAR，使用 `POST /api/v1/auth/login` 取得内存中的 Bearer
token，不落盘。数据库造数脚本见 `seed-demo6.sql`：单项目 10,000 个任务、1 个
milestone、200 条页内依赖边。

结果：

| 指标 | 数值 |
| --- | ---: |
| warm-up | 10 |
| 正式样本 | 50 |
| 响应 bytes | 102,311 |
| P50 | 17.348 ms |
| P95 | 20.228 ms |
| P99 | 22.745 ms |
| TTFB P95 | 19.039 ms |
| P95 目标 | < 100 ms |

逐样本原始 JSON、状态码、bytes 与 TTFB/总耗时见
`raw/demo6-http-jit-on.json`；完整响应见 `raw/demo6-response-jit-on.json`。

## EXPLAIN 结论

`raw/demo6-jit-setting.txt` 记录 `jit=on`、`jit_above_cost=100000`、
`jit_inline_above_cost=500000`、`jit_optimize_above_cost=500000`；脚本没有
执行任何 JIT 相关 `SET`。`raw/demo6-explain-analyze-buffers-jit-on.txt` 保存了
5 次 `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)` 原文。

5 次 Execution Time 为 `6.300 / 8.271 / 6.211 / 11.760 / 5.236 ms`，中位数
`6.300 ms`。计划中：

- `filtered` 对项目内 10,000 行只投影 `id, created_at`；
- `page_ids` 的 `Limit` 只输出 200 个 id；
- 视图关联输出 200 行，`app_user` 与 `milestone` 各以 200 行参与 hash join；
- 依赖计数 `SubPlan` 的 `loops=200`，没有再对 10,000 行逐行计算。

本次计划估算总 cost 为 `4091.51`，低于默认 `jit_above_cost=100000`，因此虽然
数据库 JIT 开关为 `on`，该计划没有触发 JIT 编译段。这是新 SQL 降低执行代价后的
实际结果，不是关闭 JIT 或改阈值得到的。

## 证据索引

| 文件 | 内容 |
| --- | --- |
| `raw/demo6-http-jit-on.json` | 50 个逐样本 HTTP 原始记录与分位值 |
| `raw/demo6-response-jit-on.json` | 200 条任务、102,311 bytes 的完整响应 |
| `raw/demo6-explain-analyze-buffers-jit-on.txt` | 5 次 JIT=on EXPLAIN 原文 |
| `raw/demo6-jit-setting.txt` | JIT 与 cost 阈值、数据量 |
| `raw/demo6-project-id.txt` | 本次隔离项目 id |
| `raw/demo6-taskmapper-sql-count.txt` | 每请求两条 TaskMapper SQL 的日志计数 |
| `raw/related-tests-summary.txt` | 9 个定向测试结果与 10k 服务层测量 |
| `seed-demo6.sql` | 10,000 任务与 200 依赖边的固定造数 SQL |
| `run-demo6-jit-on.ps1` | 隔离库、登录、warm-up、采样、EXPLAIN 与停止进程的可复跑入口 |

## 遗留风险

- 当前计划仍会对本项目的 10,000 行做一次 `project_task` 顺序扫描用于 count
  和页 id 排序；在本数据量下执行时间为 5–12ms，但更大项目仍需要
  `project_id + created_at/id` 索引或 keyset 分页，本次受“不新增迁移”约束未处理。
- 压测数据是单项目合成数据，数字只代表本机 PostgreSQL 17/JIT=on 环境，
  不作为生产容量承诺。
- 本次只跑定向测试；全量后端 `mvnw verify` 由中心在 cherry-pick 后统一执行。
