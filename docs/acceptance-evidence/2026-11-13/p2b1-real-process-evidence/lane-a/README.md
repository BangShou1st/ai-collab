# P2-B1 Lane A：M-01 / M-02 网络证据 harness

> 分支：`p2b1/network-evidence`
> 证据等级目标：`REAL_PROCESS`
> 本文件只说明 harness 与证据边界；最终 PASS/NOT_RUN 由中心复跑后判定。

## 1. 范围

本 Lane 只补 M-01 external-negative 与 M-02 DNS 重绑定/解析变化，不改产品逻辑、
默认开关或历史迁移。P2-A 原始 `real-process.json` 未改动。

## 2. 组成

| 文件 | 作用 |
| --- | --- |
| `ai-collab-backend/src/test/java/.../mcp/P2B1NetworkEvidenceMain.java` | 独立 JVM harness；调用生产 `McpEndpointPolicy`/`HttpMcpClientFacade`，输出 M-01/M-02 原始 JSON |
| `.../mcp/P2B1RebindingResolverProvider.java` | 仅测试 overlay 注册的受控解析器；政策解析返回合法地址，政策外重解析返回重绑定地址 |
| `.../src/test/resources/p2b1-resolver-overlay/META-INF/services/...` | 仅在 M-02 classpath overlay 中启用的 ServiceLoader 注册 |
| `ai-collab-ai-worker/tests/e2e/run_p2b1_network_evidence.py` | 启动 Java/TLS/sentinel 进程、记录 PID/端口/时间/日志并聚合 raw |
| `tests/fixtures/mcp/p2b1_tcp_sentinel.py` | M-01 私网/allowlist 外目标的原始 TCP sentinel |
| `tests/fixtures/mcp/local_readonly_server.py` | 增加可选 `--server-version` / `--count-file`；默认行为不变 |

## 3. 命令

在 Lane worktree：

```powershell
cd ai-collab-ai-worker
uv run --project . python tests/e2e/run_p2b1_network_evidence.py `
  --output target/p2b1-network-evidence/pilot `
  --work-dir target/p2b1-network-evidence/work
```

需要真实进程独占锁：

```text
E:\project\ai-collab-worktrees\.locks\p2b1-process
```

脚本获取目录锁后启动：M-01 HTTP/TLS fixture、私网 TCP sentinel、allowlist 外 sentinel、
独立 Java JVM；M-02 合法/重绑定两个真实 TLS 监听器与独立 Java JVM。结束后回收进程并释放锁。

`REAL_PROCESS` 的边界是：独立 Java 进程执行生产网络类并建立真实 TCP/TLS 连接；
不是完整 Spring Boot Gateway 三进程 E2E。M-12 的完整应用回退由 Lane B 负责。

## 4. M-01 证据口径

- 控制项：`localhost -> 127.0.0.1`，真实 TLS MCP `initialize` 成功；socket tap 与监听器计数均非零。
- metadata：`https://169.254.169.254/mcp`，策略返回 `AGENT_MCP_ENDPOINT_FORBIDDEN`；socket tap 为 0。
- private：`localhost` 受控解析为本机私网 IPv4，策略拒绝；socket tap 与私网 sentinel accept 均为 0。
- allowlist 外：`127.0.0.1` 不在 `allowedHosts`，策略在解析前拒绝；resolver 调用数、socket tap、
  sentinel accept 均为 0。

## 5. M-02 证据口径

- endpoint 为 `https://localhost:<port>/mcp`，allowlist 仅 `localhost`，test-loopback 显式开启。
- 受控解析器通过栈帧区分：`McpEndpointPolicy` 内解析返回 `127.0.0.1`；政策外（若 OkHttp 重新解析）
  返回 `127.0.0.2`。
- 真实 `HttpMcpClientFacade.initialize()` 返回合法服务器版本；重绑定监听器在产品调用阶段 accept=0。
- 随后显式 lookup 得到 `127.0.0.2`，并直连该地址完成 TLS handshake，证明重绑定地址可达，
  排除“坏地址导致零连接”的假阳性。

## 6. 原始输出

- `m01-real-process.json`：PID/JDK/OS、控制与三类负向 case、socket tap 事件、sentinel 计数。
- `m02-real-process.json`：合法/重绑定监听器计数、resolver lookup 计数、解析地址与真实 TLS 可达性。
- `processes.json`：所有进程 PID、命令、argsfile、启动/停止时间、退出码、日志文件。
- `network-evidence.json`：合并摘要。

日志、TLS 私钥和 truststore 位于脚本 `--work-dir` 的临时目录；不得把私钥提交到证据目录。
Python fixture 被 `taskkill /F` 回收时退出码为 1 是预期清理行为，不是应用崩溃。
