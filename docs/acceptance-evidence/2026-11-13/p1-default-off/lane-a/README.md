# Lane A：Java MCP Gateway 证据

> 分支：`p1/mcp-gateway`
> 提交：`85b931a4`、`6e5d55ed`
> 运行基线：`540a029d`
> 证据等级：TESTED(offline)

## 实现摘要

- `agent.mcp.gateway.enabled=false` 为默认值；关闭时不装配 `/internal/ai/v1/mcp`，
  旧 `McpAgentToolProvider` 路径保留。
- `initialize/ping/tools/list/tools/call`、Ed25519 claims、`mcp:list/mcp:call` scope。
- fixture 冻结 Hash：
  - `toolSchemaHash = sha256:6e0e51dc1473a487caf37f66d09b14a1027a037c9fb357b032d1c7513dc82054`
  - `catalogVersion = sha256:e38d33918b09368d0553ad8d6c936bdd3fff4d1bdea5e748b546ae85f10d1441`
- connection code 与工具名按 Python `normalize_tool_component` 同语义规范化。
- 描述/Schema 清洗、私网/元数据拒绝、OkHttp 固定解析出网、双 fence 撤权。
- 审计 fail-closed：准入审计失败零外部调用；调用后审计失败返回 `OUTCOME_UNKNOWN`；
  `tools/list` 审计失败返回 `INTERNAL`。

## 定向测试

```powershell
.\mvnw.cmd -B -ntp "-Dtest=McpGatewayCatalogContractTest,McpGatewayServiceTest,McpGatewayScopeFilterTest,McpGatewayConfigurationTest,McpGatewayStaticBindingTest,McpGatewayControllerTest,McpAgentToolProviderTest,McpAdministrationTransactionBoundaryTest,HttpMcpClientFacadeTest,McpEndpointPolicyTest,McpConnectionViewSerializationTest,McpResultSanitizerTest,InternalAiTokenFilterTest,InternalTokenVerifierTest,InternalAiJobControllerTest,InternalModelCallAuthTest,InternalModelCallControllerTest" test
```

结果：**105/105 PASS**；合并后主树干加入 D4 测试的联合定向为 **123/123 PASS**。

## 限制

- 真实 TLS/DNS 重绑定与 egress 代理未在本机进程级验证。
- 真实 Java Gateway 进程 ↔ Python Host 联调为 `NOT_RUN`；生产开关保持关闭。
