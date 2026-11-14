# P2-B2 Lane B：生产 readiness

> 日期：2026-11-13（Asia/Shanghai）
> 性质：本地/staging-like 真实进程与生产配置证据；**不是生产 canary**
> 代码基线：`dev @ 66dbfa68`
> 结论：**Lane B CONDITIONAL PASS**。Ed25519、ingress、指标/规则、回滚与本地
> 延迟证据通过；生产调用方/令牌获取与 Alertmanager 送达仍待人工确认。

## 1. 完成表

| 编号 | 项目 | 结果 | 证据等级 | 关键依据 |
| --- | --- | --- | --- | --- |
| B-01 | 生产 Ed25519 真实链路 | **PASS（本地真实进程）** | `REAL_PROCESS` + `JAVA_UNIT` | 真实 Gateway 接受 Ed25519 claims；篡改签名/错误 scope 均 401；Java issuer/verifier 定向 37/37；Python verifier 41/41 |
| B-02 | TLS/ingress | **PASS（本地同配置）** | `REAL_PROCESS` + `CONFIG` | Nginx 1.30.6 语法通过；TLS 1.2/1.3 可用、1.1 拒绝；`/internal/**` 与 `/actuator` 均 404 |
| B-03 | 监控/告警 | **PASS（规则本地加载）** | `REAL_PROCESS` | 指标真实暴露；promtool 7 条规则；Prometheus API `groups=1`、`mcpRules=7`；生产通知投递 `NOT_VERIFIED` |
| B-04 | 回滚演练 | **PASS** | `REAL_PROCESS` | Gateway-off 真实 Spring Boot：端点 404、Bean 缺席、旧 Provider 执行成功、Host 502 |
| B-05 | 容量/延迟 | **PASS（本地）** | `REAL_PROCESS` | 100 次真实调用：P50 49.7ms、P95 98.9ms、P99 105.4ms；并发 10；非生产容量 |
| B-06 | canary 前置 | **WAITING_FOR_APPROVAL** | `CONFIG` | 检查表与回滚 runbook 已就绪；生产调用方/告警送达待确认 |

## 2. 真实进程（Lane B harness）

运行时间：`2026-11-13 22:39:53` 起（Asia/Shanghai）。
工作目录：`target/p2b2-e2e/lane-b-20261113-223953/`。

| 组件 | PID | 端口 | 启动 → 停止 |
| --- | ---: | ---: | --- |
| 外部只读 TLS MCP 进程 | `45928` | `53903` | `22:39:54` → `22:40:20` |
| 本地 fixture TLS MCP 进程 | `17064` | `53904` | `22:39:54` → `22:40:19` |
| Java Spring Boot Gateway | `26380` | gateway `53900` / management `53901` | `22:39:55` → `22:40:19` |
| Python Uvicorn Host | `55588` | `53902` | `22:40:09` → `22:40:18` |

退出码 1 来自 harness 强制回收；数据库已删除。

## 3. B-01 Ed25519 与身份绑定

真实进程使用 `INTERNAL_AI_TOKEN_MODE=ed25519`：

- 签名真值 token 可调用 Gateway；
- 篡改 JWS 签名返回 401；
- 只有 `mcp:list`、没有 `mcp:call` 的 token 调 `tools/call` 返回 401；
- Gateway 从 claims 解析 `projectRef/runRef/catalogVersion`，请求体自报身份仍被拒绝；
- Java issuer/verifier/filter 定向 **37/37 PASS**；
- Python `test_token_verifier.py` **41/41 PASS**（固定 fixture、过期、kid、scope、aud）；
- `prod-config-check` 在 Gateway 开启时强制 `ed25519` + 合法 PKCS#8 私钥。

边界：Java 签发器与 Python 验签器各有跨语言 fixture/单测；本机真实进程使用的是同一
test-only fixture 密钥，不是生产私钥。生产私钥注入仍为 `NOT_RUN`。

## 4. B-02 TLS/ingress

详见 [`ingress-tls.md`](ingress-tls.md)。

- Nginx `1.30.6-alpine` 对 `production.conf` 执行语法检查：PASS；
- `GET /actuator/health` → 404；
- `GET /internal/ai/v1/mcp` → 404（已新增显式拒绝规则，不再命中 SPA fallback）；
- TLS 1.2/1.3 → 200；TLS 1.1 → 握手失败；
- 管理端口 8081 不映射、Nginx 不代理；
- 生产证书链/真实域名/安全组为 `NOT_RUN`。

## 5. B-03 指标与告警

真实进程暴露 Prometheus 指标（HTTP 200，314,446 bytes）：

```text
mcp_gateway_requests_total
mcp_gateway_latency_seconds
mcp_gateway_tool_calls_total
mcp_gateway_denied_total
mcp_gateway_schema_drift_total
mcp_gateway_inflight
```

Gateway 代码还提供 `mcp_gateway_audit_failures_total` 与 `mcp_gateway_egress_blocked_total`。

规则：

- `promtool check rules`：`SUCCESS: 7 rules found`；
- Prometheus 容器实际加载：`groups=1`、`mcpRules=7`；
- 规则覆盖 Schema drift、审计失败、egress 拦截、denied spike、错误率、P95、inflight；
- Alertmanager/生产通知通道未配置，告警“送达值班渠道”为 `NOT_VERIFIED`。

## 6. B-04 回滚

真实 Spring Boot Gateway-off 演练：

```text
m12-gateway-off.json status=PASS
gatewayEndpointAbsent=PASS
gatewayPropertyFalse=PASS
gatewayBeansAbsent=PASS
legacyProviderBeanPresent=PASS
legacyProviderTools=PASS
legacyProviderExecution=PASS
pythonHostGatewayRejected=PASS
noGatewayExternalMcpCall=PASS
databaseDropped=true
```

进程：Java `21320`、Python Host `22128`、TLS MCP `5592`；全部已回收。

## 7. B-05 本地延迟

```text
samples=100
sequential=50
concurrent=50
concurrency=10
P50=49.672ms
P95=98.901ms
P99=105.435ms
P95 budget=2000ms
```

这是本机合成 TLS fixture 的链路延迟，不是生产容量、外部 Provider 延迟或模型延迟。

原始证据 SHA-256：

```text
real-process.json  EF836A1DB6C084952F9CB3A4533288C0E966C7375F619B1A9EC5F620277475BE
m12-gateway-off.json  DF7E032876AE4A1306B045F097830656900E93BFF0427806E93854627F37A5AA
```

## 8. B-06 Canary 前置与未决项

runbook 见
[`docs/deployment/mcp-gateway-production-readiness-2026-11-13.md`](../../../../deployment/mcp-gateway-production-readiness-2026-11-13.md)。

未决：

1. `compose.prod.yml` 没有 MCP Host/worker，Java Agent 尚未接 Gateway；生产 canary
   的调用方与 `projectRef/runRef/catalogVersion` 短令牌获取路径尚未决策。
2. 仓库没有生产 Alertmanager/通知通道；规则已加载，但不能证明告警送达。
3. 生产 Ed25519 私钥、证书链、安全组和实际 ingress 未应用。

## 9. 判定

```text
Lane B：CONDITIONAL PASS（本地/staging-like）
Production canary：NOT_STARTED / WAITING_FOR_APPROVAL
生产 readiness：READY_FOR_APPROVAL_WITH_PREFLIGHT_BLOCKERS
```

没有用户明确“批准 canary”前，不启用生产 Gateway、不清空生产状态、不关闭会话资源。
