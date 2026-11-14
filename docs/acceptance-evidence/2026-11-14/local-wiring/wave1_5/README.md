# Wave 1.5 清债与集成门禁

- 日期：2026-11-14
- 基线：`dev @ d6982958`（Wave 1 收口 `93a8e756` 之后）
- 目标：清掉 Wave 1 遗留的环境、SQL 性能与 Agent PostgreSQL 时序问题，先取得完整
  `mvnw verify` exit=0，再进入 Wave 2 真进程联调。

## 状态

| 项 | 事实 | 状态 |
| --- | --- | --- |
| A 环境清理 | `bsk session list` 为空后停止空闲 daemon（PID 40324）；删除空目录 `E:\project\ai-collab-wt\p1-approval-inbox`；根 `.env` 的 `JWT_ACCESS_TOKEN_MINUTES` 由 30 改为 15（`.env` 被 gitignore，不入库） | `IMPLEMENTED` |
| B 任务分页 SQL | `TaskMapper.listPageScoped` 先只对 `project_task` 分页 id + total，再回连页内最多 200 行；每页仍 2 条 Mapper SQL | `IMPLEMENTED` / `TESTED(INTEGRATION)` / `TESTED(REAL_PROCESS)` / `MEASURED_LOCAL` |
| B 真进程复测 | 10,000 任务、`GET /tasks?page=0&size=200`、JIT=on：HTTP P50 `17.348ms` / P95 `20.228ms` / P99 `22.745ms`，响应 `102,311 bytes`；EXPLAIN 中位 `6.300ms` | `MEASURED_LOCAL` |
| C 时钟口径 | 实测运行中容器 PostgreSQL 比 Windows 宿主快约 `5.074s`（178ms 窗口）；`claimNext` 的候选判定、租约、重试时间与时间戳全部统一为数据库 `now()` | `IMPLEMENTED` / `TESTED(INTEGRATION)` |
| C 测试调度噪音 | 新增 `agent.scheduling-enabled`（生产默认 true）；测试 profile 只关闭动态调度，保留 `agent.runtime-allowed=true`、`agentRunTaskExecutor` 与全部业务 Bean | `IMPLEMENTED` / `TESTED(INTEGRATION)` |
| D 配置小修 | `application.yml` 补 `retrieval.qa-mode=${RETRIEVAL_QA_MODE:DENSE_LEGACY}`；`ArchitectureGuardTest` 注释由四条改为实际的五条冻结边 | `IMPLEMENTED` |

## 集成提交

| 顺序 | 提交 | 内容 |
| --- | --- | --- |
| 1 | `d6982958` | Wave 1.5 配置与架构注释小修（直接提交 `dev`） |
| 2 | `0921b6e1` | Lane B：任务分页在 JIT=on 下的执行开销修复 |
| 3 | `2f5159de` | Lane C：统一 claim 时钟并关闭测试后台调度 |

## 门禁原始结果

| 门禁 | 事实 | 原始证据 |
| --- | --- | --- |
| cherry-pick 后定向（分页 + 4 个 Agent 时序类） | `Tests run: 87, Failures: 0, Errors: 0, Skipped: 0`，exit=0 | [java-targeted-after-cherry-pick.txt](../center/raw/java-targeted-after-cherry-pick.txt) |
| 集成后完整后端 `mvnw verify` | `Tests run: 3123, Failures: 0, Errors: 0, Skipped: 61`，exit=0，23:08 min | [java-verify-integrated-final.txt](../center/raw/java-verify-integrated-final.txt) |

## 证据索引

- Lane B：[demo6-sql-jit/README.md](demo6-sql-jit/README.md)
- Lane C：[agent-postgres-timing/README.md](agent-postgres-timing/README.md)
- 中心门禁：[center/raw/java-verify-integrated-final.txt](../center/raw/java-verify-integrated-final.txt)

## 约束与遗留

- 未新增 Flyway 迁移，仍为 V1–V101；未改历史迁移、未碰 `main`、未 force push。
- 仓库生产/CI 默认开关保持不变。
- 任务分页当前仍对项目内全部任务做一次扫描用于 total 与页 id 排序；更大数据量需要索引或
  keyset 分页，受“不新增迁移”约束本轮未处理。
- 非 Agent 的缓存上下文中调度（系统配置同步、Connector OAuth 刷新等）仍可能在容器回收后
  产生连接告警；不影响本轮 0 failures / 0 errors。
