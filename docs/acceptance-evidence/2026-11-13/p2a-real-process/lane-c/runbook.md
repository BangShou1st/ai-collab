# P2-A Lane C 真实进程 E2E Runbook

> 适用范围：本地/CI 测试环境；不是生产 canary。
> 证据等级：真实模式输出只能写 `TESTED(REAL_PROCESS)`；启动失败或任一矩阵 FAIL 时不得改写为 PASS。

## 1. 前置

- 已合并 Lane A（`agent.mcp.test-loopback-enabled`、HTTP 206 -> `PARTIAL`）与 Lane B（`ai_collab_worker.mcp_host_app:app`）。
- `ai-collab-postgres` 容器可用，默认测试库 `ai_collab_p2a_e2e`；脚本默认运行后删除该库。
- 根 `.env` 至少包含 `POSTGRES_PASSWORD`、`JWT_SECRET`、`MODEL_CONFIG_MASTER_KEY`、`DEMO_OWNER_PASSWORD`。
- 不连接外部 MCP，不使用第三方凭据；本地 TLS 证书由脚本生成到 `target/p2a-e2e/`，不提交。

## 2. 构建与运行

```powershell
cd E:\project\ai-collab
cd ai-collab-backend
bash ./mvnw -B -ntp -DskipTests package
cd ..
cd ai-collab-ai-worker
uv run python tests/e2e/run_p2a_real_process.py `
  --mode real `
  --java-jar ..\ai-collab-backend\target\ai-collab-backend-0.0.1-SNAPSHOT.jar `
  --env-file ..\.env `
  --pg-container ai-collab-postgres `
  --pg-database ai_collab_p2a_e2e `
  --output ..\docs\acceptance-evidence\2026-11-13\p2a-real-process\lane-c\raw
```

脚本会：

1. 生成自签 TLS 证书与 PKCS12 truststore；
2. 创建独立测试库，启动真实 Spring Boot jar、真实 Uvicorn Host 与本地 TLS MCP Server；
3. 等待 Java `ping`、PostgreSQL demo owner 和 Host `/healthz`；
4. 种入项目 A（静态测试绑定）与项目 B（管理员 schema drift 场景）；
5. 执行 M-01～M-12 适用场景，记录 PID、端口、时间戳、请求 ID 和 JSON 结果；
6. 回收全部进程并删除测试库（`--keep-db` 可保留，仅用于本地排障）。

## 3. 关键参数

| 参数 | 说明 |
| --- | --- |
| `--java-jar` | 已构建的 Spring Boot jar；也可用 `--java-command` |
| `--env-file` | 测试环境文件，只读取到进程环境，不写入证据 |
| `--pg-database` | 专用测试库；脚本会删除/重建，禁止指向业务库 |
| `--reuse-db` | 复用已有测试库（仅调试） |
| `--keep-db` | 保留测试库（仅调试；交付前必须清理） |
| `--gateway-port` / `--host-port` / `--mcp-port` | 默认自动找空闲端口 |

## 4. 预期证据

输出 `raw/real-process.json` 包含：

- `processes`：组件、命令、PID、启动/停止时间、退出码、日志相对路径；
- `ports`：Gateway / Management / Host / MCP；
- `cases`：每个矩阵场景的 PASS/FAIL、原始结果摘要或错误；
- `matrix`：M-01～M-12 聚合状态；
- `artifacts`：Java/Host `tools/list` 原始结果与期望 catalog。

日志、TLS 私钥与 truststore 位于 `target/p2a-e2e/real-*/`，该目录被 `.gitignore` 忽略；不得复制私钥进证据目录。

## 5. 失败处理

- 任一进程未启动：`E2E-BOOTSTRAP` FAIL，保留日志并写 `NOT_RUN`/`FAIL`，不得改用 stub。
- `M-05` 未停用或未审计：FAIL；
- `M-08` 在途结果 `SUCCEEDED` 或新调用成功：FAIL；
- `M-09.partial` 返回 SUCCEEDED（Lane A 未合并/未生效）：FAIL；
- 真实模式因环境不可用无法执行：写 `NOT_RUN` 并说明原因。
