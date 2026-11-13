# P2-B1 进程日志说明

本目录只保留脱敏后的测试进程输出和计数快照；TLS 私钥、truststore、临时数据库、
完整 classpath 与 argsfile 均未提交。

## M-01 / M-02

- `p2b1-m01-java.log`：独立 Java harness 的 JVM 告警；`SocketTap` 使用 JDK 21 已弃用的
  `SecurityManager` 仅做 `checkConnect` 观测，不改变生产策略。
- `p2b1-m01-*.log`：本地 TLS 控制项与两个 TCP sentinel 的输出。Python fixture 被
  `taskkill /F` 强制回收，退出码 1 是预期清理；TLS 连接关闭产生的
  `ConnectionResetError` 是客户端完成 `initialize` 后主动关闭 notify 连接的结果。
- `p2b1-m02-good-tls.log` / `p2b1-m02-evil-tls.log`：合法与重绑定 TLS 监听器输出；
  `counts/` 是 accept 计数快照。
- `p2b1-m02-java.log`：M-02 Java harness 输出；正常完成时无业务错误日志。

## M-12

- `p2b1-m12-java.log`：真实 Spring Boot 应用（测试专用入口）日志，包含 PostgreSQL/Flyway、
  Tomcat、Port 与旧 Provider 探针运行痕迹。
- `p2b1-m12-python-host.log`：真实 Uvicorn Host；Gateway 关闭时 `/v1/mcp/list` 转为
  502 / `INTERNAL`。
- `p2b1-m12-local-mcp-tls.log`：旧 Provider 执行 `echo_text` 使用的本地 TLS MCP Server。
- `m12-probe.json`：应用进程内探针原始输出，和 `raw/m12-real-process.json` 的 `probe`
  字段一致。

日志中的绝对路径仅用于本机复现，不包含长期凭据。P2-A 原始 raw 未修改。
