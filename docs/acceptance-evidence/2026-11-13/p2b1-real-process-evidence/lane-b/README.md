# P2-B1 Lane B：M-12 Gateway-off 真实进程证据

本 Lane 提供一个仅测试类路径可见的 Spring Boot 进程入口，以及一个真实进程编排脚本。

## 入口

- `ai-collab-backend/src/test/java/p2b1probe/P2B1GatewayOffMain.java`：启动生产
  `AiCollabBackendApplication` + 测试探针；不进入生产 jar。
- `ai-collab-backend/src/test/java/p2b1probe/P2B1RuntimeProbe.java`：在
  `ApplicationReadyEvent` 后写入固定测试项目，调用生产 `McpAgentToolProvider.tools`，
  并对本地 TLS MCP Server 执行一次 `echo_text`。
- `ai-collab-ai-worker/tests/e2e/run_p2b1_gateway_off.py`：启动真实 PostgreSQL/Flyway、
  Java 应用、Python Uvicorn Host 和本地 TLS MCP Server，记录 PID、端口、时间、日志与
  `m12-real-process.json`。

## 命令

```powershell
cd E:\project\ai-collab-worktrees\p2b1-gateway-off
uv run --project ai-collab-ai-worker python `
  ai-collab-ai-worker/tests/e2e/run_p2b1_gateway_off.py `
  --env-file E:\project\ai-collab\.env `
  --work-dir ai-collab-backend/target/p2b1-gateway-off
```

脚本使用独立数据库 `ai_collab_p2b1_gateway_off`，运行结束删除；真实进程启动前获取
`E:\project\ai-collab-worktrees\.locks\p2b1-process` 互斥目录。证书、truststore、日志和
原始 JSON 只写 `target/`，不提交。

Java 进程默认以 `--env-file` 同级的 `ai-collab-backend` 为工作目录，以兼容
`application-local.yml` 的 `file:../.env` 导入；也可用 `--java-cwd` 显式覆盖。

## 判定字段

- `probe.gatewayEnabled=false`
- `probe.gatewayBeans` 均为 `false`
- `probe.legacyProvider.beanPresent=true`，且工具列表含
  `mcp.local_readonly.echo_text`
- `probe.execution.status=SUCCEEDED`
- `/internal/ai/v1/mcp` 返回 HTTP 404
- Python Host `/v1/mcp/list` 返回 502 / `INTERNAL`，证明没有可用 Gateway 端点
- `databaseDropped=true`，所有进程已回收

本 Lane 的 pilot 输出不作为最终中心证据；最终 raw 由中心在集成后复跑生成。
