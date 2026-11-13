# Lane B 告警规则复核

> 日期：2026-11-13
> 规则文件：`ai-collab-deploy/observability/rules/mcp-gateway-alerts.yml`
> 结论：规则语法、配置加载与本地 Prometheus 规则加载 PASS；生产通知投递仍为 NOT_VERIFIED

## 1. 规则校验

```powershell
docker run --rm --entrypoint promtool `
  -v "E:\project\ai-collab\ai-collab-deploy\observability\rules:/etc/prometheus/rules:ro" `
  prom/prometheus:v3.5.0 `
  check rules /etc/prometheus/rules/mcp-gateway-alerts.yml
```

结果：

```text
SUCCESS: 7 rules found
```

配置校验：

```text
Checking /etc/prometheus/prometheus.yml
SUCCESS: 1 rule files found
SUCCESS: /etc/prometheus/prometheus.yml is valid prometheus config file syntax
```

证据等级：`REAL_PROCESS`（promtool 容器实际解析）。

## 2. 规则清单

| 告警 | 严重度 | 触发条件 |
| --- | --- | --- |
| `McpGatewaySchemaDrift` | critical | 5 分钟内 schema drift 计数 > 0 |
| `McpGatewayAuditUnavailable` | critical | 5 分钟内审计失败计数 > 0 |
| `McpGatewayEgressBlocked` | warning | egress 策略拦截 > 0 |
| `McpGatewayDeniedSpike` | warning | 5 分钟拒绝速率 > 0.2/s |
| `McpGatewayErrorRateHigh` | warning | 错误/未知/截断 outcome 占比 > 5% |
| `McpGatewayLatencyP95High` | warning | P95 > 2s 持续 10 分钟 |
| `McpGatewayInflightHigh` | warning | inflight > 20 |

## 3. 发现

### 3.1 规则挂载

`compose.local.yml` 与 `compose.staging.yml` 已补：

```yaml
volumes:
  - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
  - ./observability/rules:/etc/prometheus/rules:ro
```

本地 Prometheus 容器 API 实测：`groups=1`、`mcpRules=7`，规则已实际加载。
生产使用外部 Prometheus 时，也必须加载同一文件或等价规则。

### 3.2 Alertmanager 未配置

仓库没有生产 Alertmanager/通知渠道配置；当前只能验证规则 firing，不能证明告警送达。
canary 前必须提供“告警到达值班渠道”的测试证据，否则不能以“监控已就绪”放行。

## 4. 内容复核

- 标签低基数：method/outcome/reason/connectionCode，无参数/结果/文档正文；
- `runbook` 指向 `docs/deployment/mcp-gateway-production-readiness-2026-11-13.md` 的锚点；
- `schema_drift`、`audit_failures`、`egress_blocked` 都是 fail-closed 安全信号，阈值 >0 合理；
- 错误率把 `OUTCOME_UNKNOWN`/`TRUNCATED`/`PARTIAL` 纳入错误，符合“不把未知当成功”；
- 2s P95 与 20 inflight 是本地 readiness 初始预算，不是生产容量结论，需在 canary 前冻结。

## 5. 判定

```text
规则语法：PASS
规则加载：PASS（local/staging，Prometheus API 7 条）
通知投递：NOT_VERIFIED
生产调用方/令牌获取：BLOCKED_DECISION（compose.prod 无 MCP Host；Java Agent 未接 Gateway）
生产 canary：NOT_STARTED
```
