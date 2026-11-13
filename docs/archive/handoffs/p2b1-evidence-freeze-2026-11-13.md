# P2-B1 真实进程证据补全冻结（2026-11-13）

> 状态：**COMPLETE**
> 基线：`dev @ 750b3a05`（以实际 HEAD 为准）
> 范围：只补 P2-A 遗留的 M-01 external-negative、M-02 真实解析变化、M-12 Gateway-off 回退
> 证据目录：`docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/`
> 性质：本地/测试环境的真实进程证据；**不是生产 canary、灰度或上线证明**。

## 1. 三条证据与通过标准

| 编号 | 必达项 | REAL_PROCESS PASS 标准 | 不得作为 PASS 的证据 |
| --- | --- | --- | --- |
| M-01 | metadata/private/allowlist 外目标的真实 external-negative | 真实 Java 进程构造并调用三类目标；记录策略拒绝码、socket 尝试计数与本地监听器连接数；控制项必须证明真实连接可被观测；三类负向目标的连接尝试数与监听器 accept 数均为 0 | 只跑 `McpEndpointPolicyTest`；只记录 `NOT_EXECUTED` 而没有 socket/监听器观测；只看静态配置 |
| M-02 | 真实进程中的 DNS 重绑定/解析变化 | 真实 Java 进程使用受控解析器与两个真实 TLS 监听地址；首次解析指向合法地址，随后解析（若被错误复用）指向重绑定地址；合法调用成功、重绑定监听器连接数为 0、解析器调用次数可审计；另证明重绑定地址本身确实可达 | 只跑 `McpEndpointPolicyTest`；只断言 `pinnedDns()` 返回固定列表而不建立真实 socket |
| M-12 | Gateway 关闭回退旧 Provider | 真实 Spring Boot 应用进程（含真实 PostgreSQL/Flyway/嵌入式 HTTP 端口）以 `agent.mcp.gateway.enabled=false` 启动；Gateway Controller/Service Bean 不存在、`/internal/ai/v1/mcp` 不可用；旧 `McpAgentToolProvider` Bean 存在且实际完成一次工具枚举/执行；无 Gateway 外部 MCP 调用；记录 PID、端口、时间与日志 | 只跑 `McpGatewayConfigurationTest`；只看 property；只检查静态 Bean 定义而不启动进程 |

## 2. 证据等级与命名

只允许以下等级：

- `REAL_PROCESS`：独立进程、真实 TCP/TLS 连接或真实 Spring Boot 应用上下文；必须有 PID/端口/时间/原始输出。
- `JAVA_UNIT`：JUnit 单测，不能计入 M-01/M-02/M-12 的真实进程完成项。
- `CONFIG`：只验证属性、Bean 装配或静态配置，不能计入真实进程完成项。
- `NOT_RUN`：未执行或环境无法稳定构造；必须写原因与已有最低证据。

禁止把 `JAVA_UNIT` / `CONFIG` / `NOT_RUN` 写成 `REAL_PROCESS` 或 `PASS`。

## 3. 证据目录与不可变约束

新证据只写：

```text
docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/
  raw/
    m01-real-process.json
    m02-real-process.json
    m12-real-process.json
    processes.json
    logs/
  report.md
  security-matrix-delta.md
```

- P2-A 原始 `real-process.json` 及其 SHA-256 不得改写。
- 新增 sidecar 必须标明 `sidecarFor`、`rawModified=false` 与证据等级。
- 日志中的凭据、Bearer、数据库密码、证书私钥必须脱敏；私钥/truststore 只放 `target/`。
- 测试库使用独立名称，结束后删除；进程使用临时端口，结束后释放。

## 4. Lane 与资源锁

| Lane | worktree | 写入范围 | 资源 |
| --- | --- | --- | --- |
| A `p2b1/network-evidence` | `E:\project\ai-collab-worktrees\p2b1-network-evidence` | 仅测试/fixture/harness；不得改产品逻辑、默认开关或历史迁移 | `p2b1-process`、临时端口 |
| B `p2b1/gateway-off` | `E:\project\ai-collab-worktrees\p2b1-gateway-off` | 仅测试/fixture/harness；不得改产品逻辑、默认开关或历史迁移 | `p2b1-process`、`p2b1-db`、独立测试库 |
| C 中心 | 主工作树 `dev` | 证据聚合、矩阵、报告、清理、cherry-pick、CI | `repo-main`、最终进程运行 |

资源规则：

- `p2b1-process`：同一时刻只允许一个 Lane 启动真实 Java/Python/MCP 进程组；开发与运行分开，运行前确认端口空闲。
- `p2b1-db`：M-12 使用独立测试库；不得连接业务库，不得复用 P2-A 的数据库。
- 不占用 Magpie；没有模型调用时完全不触碰 Magpie。
- 子 agent 不 push、不合并 `dev`、不修改主工作树；中心统一 cherry-pick 与 push。

## 5. 实现边界

- M-01/M-02 可以使用仅测试可见的 Java/Python harness、受控解析器、TLS/监听器 fixture；不得绕过 `McpEndpointPolicy`、`HttpMcpClientFacade` 或修改生产出网逻辑。
- M-12 可以使用测试专用启动入口，但必须启动真实 Spring Boot 应用上下文、真实 PostgreSQL/Flyway 与真实 HTTP 端口；旧 Provider 的实际调用必须经过生产 `McpAgentToolProvider`。
- 如果 M-12 要通过修改产品代码才能保持旧 Provider 可用，立即停止并转设计评审。
- 任何测试专用的 resolver/监听器必须只存在于测试类路径或证据 harness，不得进入生产运行时。

## 6. 停止条件

1. 无法构造真实 external-negative，或无法观测“没有建立连接”：M-01 写 `NOT_RUN`，保留 PREFLIGHT/STATIC 证据。
2. 无法在真实进程中稳定模拟解析变化：M-02 写 `NOT_RUN`，保留 JAVA_UNIT 证据。
3. M-12 启动失败或需要产品代码改动才能回退：写 `NOT_RUN` / `FAIL`，交中心评审。
4. 需要生产凭据、外部 MCP、生产 canary、TLS/ingress 或多租户：停止，改用本地 fixture。
5. 半天内未闭环：收口已完成项，其余写 `NOT_RUN` / Deferred，不硬编证据。

## 7. 最终验收

- 三条证据均有独立 raw/sidecar、PID/端口/时间/命令/日志摘要。
- 定向测试通过；中心复跑证据与测试，确认可重复。
- P2-A 原 raw SHA-256 不变。
- `security-matrix-delta.md` 明确最终状态与证据等级。
- 生产 W6 仍为 `NOT_READY`；不得因本轮结果宣称可 canary。

## 8. 收口结果

- M-01：`REAL_PROCESS PASS`。metadata / private-resolution / allowlist-outside 三类目标均为
  `AGENT_MCP_ENDPOINT_FORBIDDEN`；负向 socket 检查与 sentinel accept 均为 0，控制项真实 TLS 连通。
- M-02：`REAL_PROCESS PASS`。合法 TLS 调用成功；产品调用期间外部重解析 0 次，
  重绑定监听器 accept 0；显式解析 `127.0.0.2` 后 TLS 可达，监听器 accept 1。
- M-12：`REAL_PROCESS PASS`。真实 Spring Boot/PostgreSQL/Flyway/Tomcat 启动，Gateway 404/Bean
  缺席，旧 `McpAgentToolProvider` 实际枚举并执行 `echo_text`，Python Host 返回 502/`INTERNAL`。
- 最终证据：`docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/`。
- CI：[run 43433130155](https://github.com/BangShou1st/ai-collab/actions/runs/43433130155)
  `HEAD 517f1183` success；生产 W6 仍 `NOT_READY / BLOCKED`。
