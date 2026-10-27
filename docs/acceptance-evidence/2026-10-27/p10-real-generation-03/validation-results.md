# 本地验证结果

验证命令按仓库约定执行；模型生成门禁环境变量未设置，默认验证没有重放真实 QA/Agent 模型调用。

| 命令 | 结果 | 备注 |
| --- | --- | --- |
| `bash ./mvnw -B -ntp verify`（WSL 首轮） | **未通过**：1864 tests，0 failures，3 errors，446 skipped | 首轮完整失败已保留。3 个 error 均来自 Testcontainers 找不到 Docker：`AgentRemoteOutcomeMigrationPostgresTest`、`AgentRemoteOutcomePostgresTest`、`AgentObservabilityPostgresTest`。Docker Desktop 后恢复后，WSL 重跑仍因 Ubuntu 未启用 Docker Desktop 集成而重复同一环境错误。 |
| `.\mvnw.cmd -B -ntp verify`（门禁修正后 Windows 全量重跑） | **通过**：1906 tests，0 failures，0 errors，43 skipped | 通过 Docker Desktop Windows 连接端点完成 PostgreSQL 集成测试。新增 6 项 Agent/QA 授权门禁单测通过；P10 真实生成类按默认双门禁跳过；`P10ScorerReplay03Test` 通过。 |
| `pnpm lint` | 通过 | 0 errors，76 warnings。 |
| `pnpm typecheck` | 通过 | `vue-tsc -b` 成功。 |
| `pnpm test` | 通过 | 52 个测试文件、409 项测试通过。 |
| `pnpm build` | 通过 | Rollup 注释与大 chunk 有警告；生成包约 1.51 MB（gzip 490 KB）。 |

Docker Desktop 由主程序启动（没有使用 Windows 服务）；恢复后 Windows `docker version` 显示 Server 29.8.0、`docker-desktop` WSL 发行版为 Running，但 Docker CLI 未集成到 Ubuntu。切换到 Windows Maven wrapper 后，完整后端校验通过。

各轮完整控制台输出临时写入被忽略的 `ai-collab-backend/target/p10-real-generation-03/validation/`。本文件保留首轮失败和最终完整通过的测试计数及原因；没有把选择性重跑描述为完整验证通过。提交后的 GitHub CI 结果另行登记。
