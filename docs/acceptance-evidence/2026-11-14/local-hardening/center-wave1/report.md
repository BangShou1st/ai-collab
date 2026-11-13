# Wave 1 中心复核与本地 Demo

## 门禁结果

| 项 | 事实 | 原始文件 |
| --- | --- | --- |
| Java `mvnw verify` round 1 | exit=1；仅 `AgentActuatorSecurityTest` 2 个错误（B 新增会话依赖未在测试上下文注入） | `raw/java-verify-round1-summary.txt` |
| 修复 | `7fd84b11` 补会话状态服务桩并使用带 `tv` 的合法 JWT；定向 retest exit=0 | `raw/java-actuator-retest.txt` |
| Java `mvnw verify` round 2 | exit=0；`Tests run: 3066, Failures: 0, Errors: 0, Skipped: 61` | `raw/java-verify-round2-summary.txt` |
| 前端 lint/typecheck/test/build | 全部 exit=0；504 tests / 66 files；lint 0 error / 70 warning | `raw/frontend-*.txt` |
| Python | exit=0；1284 passed，1 个既有 StarletteDeprecationWarning | `raw/python-pytest.txt` |

## Demo 场景

| 场景 | 状态 | 实测 |
| --- | --- | --- |
| 1 MinIO 停机后删文档 | TESTED(REAL_PROCESS) | `202`；Job `PENDING\|1`；可见 DB 行 0；恢复后 `DONE\|1`、行 0 |
| 2 DB 删除失败后重试 | TESTED(REAL_PROCESS) | `202`；Job `PENDING\|1`；行仍 `DELETING`；移除注入后 `DONE\|1`、行 0 |
| 3 停用/刷新/重启用 | TESTED(REAL_PROCESS) | 停用前 200；旧 Access 401；旧 Refresh 401；重启用旧 Access 仍 401；新登录 200 |
| 4 改密 | TESTED(REAL_PROCESS) | 改密后旧 Access 401 |
| 5 双标签审批 | TESTED(REAL_PROCESS)（C） | 5.027s 轮询；审批后 1.197s 可见；后台标签冻结 REAL_PROCESS 为 `NOT_RUN` |
| 6 10k 任务/看板 | TESTED(INTEGRATION) + MEASURED + TESTED(REAL_PROCESS) | API 200 条、total 10000、hasNext；看板 VOM 显示 `加载更多（已载 200 / 10000）` |

场景 1–4 与 6 的服务进程使用合并后 JAR、独立库 `ai_collab_lh_center`、独立 MinIO `19012`；结束后已停止并清理，未修改共享基础设施。

## 场景 6 性能数字

| 层 | 结果 | 证据 |
| --- | --- | --- |
| D lane 服务层（Testcontainers） | firstCall 47ms，P95 53ms，每页 TaskMapper SQL=2 | `lane-d-task-list/raw/java-postgres.txt` |
| 合并进程 API，复用 HttpClient | 50 samples，P95 346ms，min 164.2ms，max 919.8ms，响应 77,603 bytes | `raw/demo6-http-client-retest.json` |
| 合并进程 API，curl | 20 samples，P95 1356.5ms，min 219.7ms，max 1377ms | `raw/demo6-curl-retest.json` |

服务层满足设计阈值；真实 HTTP 层高于 200ms。未调整阈值、未修改统计口径，差异原因尚未拆分到 SQL/序列化/本地日志/客户端。

## 证据索引

- `raw/demo-summary.json`：场景 1–4、6 的原始汇总。
- `raw/demo6-http-client-retest.json`、`raw/demo6-curl-retest.json`：端到端复测。
- `screenshots/demo6-board-200-of-10000.png`：BSK 看板截图。
- `scripts/run-demo.ps1`：可复现脚本（从根 `.env` 读取，不落凭据）。
- C 的双标签证据：`../lane-c-approval-inbox/`。
- A/B/D/F1 的 lane 证据：各自 lane 目录。
