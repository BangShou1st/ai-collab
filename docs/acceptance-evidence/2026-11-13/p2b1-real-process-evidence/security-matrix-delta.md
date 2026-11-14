# P2-B1 安全矩阵最终口径（增量）

> 本文件只更新 P2-A 遗留的 M-01 / M-02 / M-12 证据等级；M-03～M-11 仍沿用
> P2-A 的 `REAL_PROCESS` 证据。
> P2-A 原始 raw 不改写；最终证据目录为
> `docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/`。

| 编号 | 场景 | 最终状态 | 证据等级 | 判定依据 |
| --- | --- | --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外目标拒绝 | **PASS** | `REAL_PROCESS` | 三类目标均为 `AGENT_MCP_ENDPOINT_FORBIDDEN`；metadata 的 socket 检查为 0；private/allowlist 外目标的 socket 检查与 sentinel accept 均为 0；控制项真实 TLS 连接成功。详见 `raw/m01-real-process.json`。 |
| M-02 | DNS 重绑定/校验后解析变化 | **PASS** | `REAL_PROCESS` | 生产客户端经合法地址完成真实 TLS `initialize`；产品调用期间 policy lookup 3 次、外部重解析 0 次；重绑定监听器 accept 0；随后显式解析 `127.0.0.2` 并完成 TLS 握手，证明地址可达。详见 `raw/m02-real-process.json`。 |
| M-12 | Gateway 关闭回退旧 Provider | **PASS** | `REAL_PROCESS` | 真实 Spring Boot/PostgreSQL/Flyway/Tomcat 进程启动；`gateway.enabled=false`；Gateway Controller/Service/Catalog/Authorizer Bean 全缺席；`/internal/ai/v1/mcp` 返回 404；旧 `McpAgentToolProvider` 实际枚举并执行 `echo_text`；Python Host 返回 502/`INTERNAL`。详见 `raw/m12-real-process.json`。 |

## 1. 证据等级汇总

| 等级 | 覆盖项 | 数量 | 说明 |
| --- | --- | ---: | --- |
| `REAL_PROCESS` | M-01、M-02、M-12（本轮补齐） + P2-A M-03～M-11 | 12 | 本轮三项为独立真实进程证据，不再使用单测/配置替代 |
| `JAVA_UNIT` | P2-A M-02 历史补充 | 1 | 保留为回归单测，不作为本轮完成依据 |
| `CONFIG` | P2-A M-12 历史补充 | 1 | 保留为配置回归，不作为本轮完成依据 |
| `PREFLIGHT/STATIC` | P2-A M-01 历史补充 | 1 | 保留为历史证据，已被本轮真实负向覆盖 |
| `NOT_RUN` | 本轮 Must-have | 0 | 三条 Must-have 均已执行 |

**12/12 PASS 的证据构成**：M-01、M-02、M-12 为本轮新增 `REAL_PROCESS`；
M-03～M-11 为 P2-A 已有 `REAL_PROCESS`。历史 `JAVA_UNIT` / `CONFIG` / `PREFLIGHT`
不提升为本轮真实进程证据，只作为回归补充保留。

## 2. 与 P2-A 的关系

| 项 | P2-A 原口径 | P2-B1 最终口径 |
| --- | --- | --- |
| M-01 | PASS（PREFLIGHT/STATIC）；external-negative `NOT_RUN` | PASS（REAL_PROCESS） |
| M-02 | PASS（JAVA_UNIT）；真实进程 `NOT_RUN` | PASS（REAL_PROCESS），JAVA_UNIT 保留 |
| M-12 | PASS（CONFIG）；真实进程 `NOT_RUN` | PASS（REAL_PROCESS），CONFIG 保留 |
| 生产 W6 | `NOT_READY` | 仍为 `NOT_READY` / `BLOCKED` |

## 3. 不得扩大解释

- 本轮没有执行 W6-B、多 MCP Server、多租户、生产 Ed25519 claim、生产 TLS/ingress、
  第三方 Provider 准入或生产 canary。
- M-01/M-02 的 `REAL_PROCESS` 是“独立 JVM + 生产网络类 + 真实 TCP/TLS fixture”，
  不是完整 Spring Boot 三进程 E2E；不得据此宣称生产 Gateway 已全链路验收。
- M-12 使用测试专用 Spring Boot 入口，不是生产打包 jar；其结论仅覆盖
  `agent.mcp.gateway.enabled=false` 的本地回退行为。
- P2-A 原始 `real-process.json` SHA-256 仍为
  `E37D3A7B3FCD4A954D0A7EC7B441C75E2CF71F6438FE6DCADD6D499F61DB28AC`，未改写。
