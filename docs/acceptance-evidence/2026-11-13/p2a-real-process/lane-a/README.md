# P2-A Lane A：Java Gateway 证据

> 分支：`p2a/java-gateway`
> 代码提交：`4d2b267a`（首切片 `fe86fccb`）
> 基线：`720c601d`
> 时间：2026-11-13 16:50（Asia/Shanghai）
> 结论：**PASS（Java 服务/集成级）**；真实跨进程 E2E 由 Lane C 执行。

## 1. 范围

本 Lane 只补齐真实进程 E2E 所需的 Java Gateway 测试支持和安全分类：

- local/test 专用的 HTTPS 回环测试例外；
- HTTP 206 的稳定 `PARTIAL` 映射；
- 成功/截断结果透传 sanitizer 的非空 `warnings`；
- 私网/元数据/allowlist 外拒绝与 pinned DNS 测试；
- Schema 变化停用与审计的单元/Postgres 集成测试；
- 既有伪造身份拒绝、撤权 fence、default-off/旧 Provider 回退回归。

未修改 W4 Prompt、未改变任何生产默认开关、未放宽生产 `McpEndpointPolicy`。

## 2. Lane C 可用接口

| 项 | 值 |
| --- | --- |
| 属性 | `agent.mcp.test-loopback-enabled` |
| 环境变量 | `AGENT_MCP_TEST_LOOPBACK_ENABLED` |
| 默认 | `false` |
| Profile 限制 | 仅 `local`/`test`；其他 profile 启用时启动失败 |
| 允许 endpoint | `https` + 精确 `127.0.0.1`、`::1` 或 `localhost` |
| 端口 | 允许任意非 0 测试端口；普通公网 endpoint 仍只允许 443 |
| 其他约束 | allowlist 仍必须精确包含 host；仍执行 pinned DNS；解析结果必须全为 loopback；http、元数据、其他私网、allowlist 外均拒绝 |
| 证书 | E2E 用证书 SAN 应为 `127.0.0.1` 或 `localhost`；truststore 通过 JVM `javax.net.ssl.trustStore*` 注入 |
| Partial | 上游 HTTP 206 → `ErrorCode.AGENT_MCP_PARTIAL` → Gateway `PARTIAL` |

## 3. 测试结果

| 门禁 | 命令摘要 | 结果 |
| --- | --- | --- |
| 首轮定向 | `mvnw -B -ntp -Dtest=McpEndpointPolicyTest,McpLoopbackTestGuardTest,HttpMcpClientFacadeTest,McpGatewayServiceTest,McpAdministrationServiceTest test` | 27/27 PASS |
| MCP 相关回归 | `mvnw -B -ntp -Dtest=HttpMcpClientFacadeTest,McpAdministrationServiceTest,McpAdministrationTransactionBoundaryTest,McpAgentToolProviderTest,McpConnectionViewSerializationTest,McpEndpointPolicyTest,McpLoopbackTestGuardTest,McpResultSanitizerTest,McpGatewayCatalogContractTest,McpGatewayConfigurationTest,McpGatewayControllerTest,McpGatewayServiceTest,McpGatewayStaticBindingTest,InternalAiTokenFilterTest,McpGatewayScopeFilterTest test` | 62/62 PASS |
| warnings 修复 | `mvnw -B -ntp -Dtest=McpGatewayServiceTest,McpResultSanitizerTest test` | 21/21 PASS |
| warnings 后 MCP 回归 | 同一 MCP 相关回归命令 | 63/63 PASS |
| Postgres 集成 | `mvnw -B -ntp -Dtest=McpAdministrationPostgresIntegrationTest test` | 3/3 PASS |

原始 Maven 输出保留在 worktree 的 `ai-collab-backend/target/p2a-lane-a-*.log`，未提交。

## 4. 安全矩阵贡献

| 编号 | 覆盖 | 证据 |
| --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外拒绝，测试回环不能绕过 | `McpEndpointPolicyTest` |
| M-02 | 校验与连接使用同一 pinned 地址，连接期不二次解析 | `McpEndpointPolicyTest.connectionUsesTheAddressesPinnedByTheValidationThatPrecededIt` |
| M-05 | 发现结果变化使连接停用并写 `AGENT_MCP_SCHEMA_CHANGED` 审计 | `McpAdministrationServiceTest` + `McpAdministrationPostgresIntegrationTest` |
| M-07 | 请求体自报 `projectRef` 被拒且 zero external call | `McpGatewayServiceTest.rejectsSelfReportedAuthorizationFieldsWithoutCallingTools` |
| M-08 | 调用前/结果前撤权不返回成功正文 | `McpGatewayServiceTest.revokeDuringCallNeverReturnsSuccessfulBody`、`catalogRevocationDuringCallAlsoFencesTheLateResult` |
| M-09 | 429/超时/5xx/206 partial 不混算 | `HttpMcpClientFacadeTest` + `McpGatewayServiceTest.mcpErrorTimeoutAndRateLimitStayDistinct` |
| M-04 | 注入结果返回固定 warning，warning 不含远端正文/敏感值；TRUNCATED 也保留 warning | `McpGatewayServiceTest.toolsCallKeepsSanitizerWarningsWithoutLeakingSensitiveBody`、`oversizedResultIsExplicitlyTruncatedAndNeverClaimedComplete` |

## 5. 未完成 / 交接

- 真实 Java 进程、Python Host 进程、TLS MCP Server 的跨进程启动与调用：**Lane C**。
- M-01 的真实 Gateway 拒绝场景、M-04 真实结果清洗、M-10 真实截断：**Lane C**。
- TLS 证书/truststore 实机握手：**Lane C**。
- 全量 Java 门禁与 CI：中心统一执行。

生产 W6 仍为 `NOT_READY`，本 Lane 不构成 canary、灰度或上线证明。
