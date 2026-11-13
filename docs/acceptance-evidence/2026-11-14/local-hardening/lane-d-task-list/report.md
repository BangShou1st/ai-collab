# Lane D：H-4 任务列表批量化与分页

## 基线

- 仓库：`E:\project\ai-collab-wt\p1-task-list`
- 分支：`hardening/p1-task-list`
- 基线提交：`873fc6f06a4712aca32972f80a8d8a8fa83cbec5`
- 迁移：`V101__optimize_task_list_pagination.sql`

## 状态

- **IMPLEMENTED**：`PageView<T>` 契约、单 SQL 分页 + total、依赖 500 分块单查、V101、后端参数边界、前端看板按列 200 与列表服务端分页。
- **TESTED(UNIT)**：后端定向与契约 28 个用例，退出码 0；依赖失败注入 2 个用例，退出码 0；前端 496 个用例 / 64 个文件，退出码 0。
- **TESTED(INTEGRATION)**：PostgreSQL 17 Testcontainers 3 个用例，退出码 0；覆盖 10k 种子、空页 total、状态/负责人/里程碑过滤、500 分块与 V101 索引。
- **MEASURED**：10k 任务、首查 `47ms`、P95 `53ms`（50 样本，本地 Docker/Windows），每页 TaskMapper SQL `2` 条。
- **NOT_RUN**：本 lane 未启动真实 Vite + BSK 浏览器目检；页面行为由组件/组合式测试与 build 覆盖，集成后由中心 demo 场景 6 复核。

## 实现要点

### 后端

- 新增 `common.api.PageView<T>`，字段固定为 `items,total,page,size,hasNext`。
- `TaskMapper.listPageScoped` 使用 `filtered/total/page` CTE，在一条 SQL 内返回分页行与 total；空页仍保留 total 行。
- `TaskRepository.dependencyIdsByTask` 按 500 个 taskId 分块，每块一条依赖 SQL；分页上限 200，因此单页固定为 2 条 TaskMapper SQL。
- `GET /api/v1/projects/{projectId}/tasks` 新增 `page=0`、`size=50`，服务端拒绝 `page<0` 与 `size<1|>200`；越界页返回空 items 和正确 total。
- `TaskListAgentTool` 与 planning 查询改为有界分页读取，不再新增全量服务入口。
- V101 仅新增 `ix_task_project_status_updated`；V1/V75 已有 assignee、milestone 组合索引，未重复创建。
- `docs/api/openapi.yaml` 同步 `page/size` 参数与 `TaskPage` 契约。

### 前端

- `workApi.tasks` 返回 `PageView<Task>`。
- 看板每个状态列独立请求 `status + page=0 + size=200`，列内 `hasNext` 时显示“加载更多”。
- 列表使用服务端 `page/size/status`，状态筛选移入统一 filters，新增标准 `el-pagination`。
- 切项目、切视图、筛选变更均重置页码；请求代次保护旧响应不覆盖新项目/新视图。

## 证据

| 证据 | 文件 |
| --- | --- |
| 后端编译 | `raw/java-compile.txt` |
| 后端定向/契约 | `raw/java-targeted.txt` |
| PostgreSQL 集成 + 10k 测量 | `raw/java-postgres.txt` |
| 依赖失败注入 | `raw/java-failure-injection.txt` |
| 前端 lint | `raw/frontend-lint.txt` |
| 前端 typecheck | `raw/frontend-typecheck.txt` |
| 前端全量测试 | `raw/frontend-test.txt` |
| 前端构建 | `raw/frontend-build.txt` |

关键原始行：

```text
MEASURED task-list-10k firstCallMs=47 p95Ms=53 perPageMapperSql=2 samples=50
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
Test Files  64 passed (64)
Tests  496 passed (496)
```

所有命令的 `EXIT=0` 已追加在对应 raw 文件末尾；lint 为 0 error、70 warning，warning 为仓库既有或非阻断项。

## 约束核对

- 未修改历史迁移；仅新增 V101。
- 未修改六开关、`AGENT_MCP_GATEWAY_ENABLED=false` 或 `MQ_ENABLED=false`。
- 未修改 `.env`、Compose、依赖清单或 lockfile。
- 未执行 push、merge、force push，未触碰 `main`。
