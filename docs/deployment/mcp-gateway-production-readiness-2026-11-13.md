# MCP Gateway 生产 readiness 与 canary runbook

> 日期：2026-11-13
> 状态：**READINESS_ARTIFACTS_READY / PRODUCTION_NOT_APPLIED**
> 适用范围：W6 Java Gateway 内部只读端点、生产 Ed25519、Nginx/TLS、Prometheus 告警与回滚
> 禁止：未经用户明确“批准 canary”，不得启用生产 Gateway 或写入生产状态

## 1. 结论先行

| 项目 | 结论 | 证据等级 |
| --- | --- | --- |
| `/internal/ai/v1/mcp` 不经 Nginx 转发 | **PASS**：显式 `location ^~ /internal/` 实测 404 | `REAL_PROCESS` |
| `/actuator` 不经 Nginx 暴露 | **PASS**：实测 404 | `REAL_PROCESS` |
| 管理端口 8081 | Compose 不映射、Nginx 不引用 | `CONFIG` |
| TLS 1.2 / 1.3 | **PASS**；TLS 1.1 握手失败 | `REAL_PROCESS` |
| Nginx 配置语法 | **PASS** | `REAL_PROCESS` |
| Prometheus 规则语法 | **PASS**：7 条规则 | `REAL_PROCESS` |
| 规则实际加载 | **PASS（local/staging）**：Compose 已挂载 `rules/`，Prometheus API 返回 7 条规则；生产外部 Prometheus 仍需按同一路径加载 | `REAL_PROCESS` + `CONFIG` |
| Alertmanager 投递 | **NOT_VERIFIED**：仓库未配置生产通知通道 | `NOT_RUN` |
| 生产 Ed25519 私钥注入 | **配置闸门已加**：Compose 透传 Ed25519 变量；`prod-config-check` 在 Gateway 开启时强制 `ed25519` + 合法 PKCS#8；实际生产密钥未注入 | `CONFIG` |
| 生产调用方/令牌获取 | **BLOCKED_DECISION**：`compose.prod.yml` 没有 MCP Host/worker；Java Agent 尚未接 Gateway，当前没有已部署的生产 token 获取路径 | `CONFIG` |

本文件是生产执行 runbook，不是生产验收报告。实际生产 canary 仍需用户批准并记录执行证据。

### 1.1 Canary 前未决项

1. **调用方决策**：生产 Compose 没有 `ai-worker`/Python MCP Host，Java Agent 也尚未把 Gateway 接入工具执行主路径。
   必须明确 canary 由哪个内部客户端发起，以及它如何获得绑定 `projectRef/runRef/catalogVersion` 的短令牌。
2. **告警送达**：规则已加载，但生产 Alertmanager/通知渠道不在仓库内；必须证明告警可送达值班渠道。

> **2026-11-13 P2-C 复核**：上述两项仍未闭环，且真实生产证书/私钥也未注入。
> 详见 [`P2-C 生产前置报告`](../acceptance-evidence/2026-11-13/p2c-production-prerequisites/report.md)；
> 当前结论为 `NOT_READY`，不是 `READY_FOR_CANARY_APPROVAL`，不得据本 runbook 直接请求批准。

## 2. 目标拓扑与网络边界

```text
Internet
  -> Nginx :443（TLS 1.2/1.3，唯一公网入口）
       -> app:8080（/api/v1/**）
       -> 静态前端
       -> MinIO（仅 /storage/ 预签名 GET/HEAD）

内部自动化/运维（不经 Nginx）
  -> app:8081/actuator/prometheus（Prometheus 抓取，只读）
  -> app:8080/internal/ai/v1/mcp（仅内部网络，Ed25519）
       -> 外部只读 MCP Provider（HTTPS 443 allowlist）
```

约束：

- 不映射 `app:8081` 到宿主机；Nginx 不配置 `/internal/` 或 `/actuator` 到 app 的 proxy_pass。
- `data` 网络为 `internal: true`；生产 Nginx 固定 `edge` 地址 `172.30.173.2`，后端只信任该转发源。
- Gateway 默认 `agent.mcp.gateway.enabled=false`；canary 才允许显式改 true。
- 外部 Provider 必须使用只读端点、最小 scope、精确 allowlist，且完成人工 Provider 准入。

## 3. Ingress / TLS 复核

### 3.1 Nginx 语法

实测命令：

```powershell
docker run --rm `
  -v "E:\project\ai-collab\ai-collab-deploy\nginx\production.conf:/etc/nginx/conf.d/default.conf:ro" `
  -v "E:\project\ai-collab\target\p2b2-lane-b-ops\tls\cert.pem:/etc/nginx/tls/cert.pem:ro" `
  -v "E:\project\ai-collab\target\p2b2-lane-b-ops\tls\key.pem:/etc/nginx/tls/key.pem:ro" `
  nginx:1.30-alpine nginx -t
```

结果：`syntax is ok` / `test is successful`。

### 3.2 真实请求

本地启动同一份 `production.conf`（仅用于配置/路由验证，不代表生产环境）：

```text
GET /actuator/health        -> 404
GET /internal/ai/v1/mcp     -> 404
TLS 1.2                     -> 200
TLS 1.3                     -> 200
TLS 1.1                     -> curl exit 35（握手失败）
```

**结论：** Nginx 没有把 `/internal/ai/v1/mcp` 转发给 app，并用显式规则返回 404：

```nginx
location ^~ /internal/ { return 404; }
```

该规则已进入 `ai-collab-deploy/nginx/production.conf`，本地 Nginx 容器实测
`/internal/ai/v1/mcp` 与 `/actuator/health` 均为 404。

### 3.3 Headers 与代理信任

- `proxy_set_header Forwarded ""` 丢弃客户端转发链；
- `X-Forwarded-For $remote_addr` 只传本连接来源；
- `X-Forwarded-Proto https` 固定；
- 后端 `application-production.yml` 只信任 `172.30.173.2`，与 Compose 固定代理地址一致；
- 不把 `X-Forwarded-Host/Port` 透传给后端。

## 4. 生产 Ed25519

生产必须由部署环境注入，不得写入 Compose、仓库或日志：

```text
INTERNAL_AI_TOKEN_MODE=ed25519
INTERNAL_AI_ED25519_PRIVATE_KEY=<PKCS#8 DER base64，安全注入>
INTERNAL_AI_ED25519_KID=<轮换 kid>
INTERNAL_AI_ED25519_PUBLIC_KEYS=<kid=SPKI DER base64，轮换期可多个>
INTERNAL_AI_TOKEN_TTL_SECONDS=300
INTERNAL_AI_TOKEN_CLOCK_SKEW_SECONDS=60
```

规则：

- `ed25519`/`dual` 模式下私钥缺失或非法必须启动失败，不允许静默回退 static；
- `k1` 不允许换密钥；轮换必须换新 kid；
- 旧公钥至少保留 `2×TTL`，再移除；
- 私钥、令牌、claims 不进入日志、错误体、证据或工单截图；
- Gateway 只接受 `aud=backend`、`scope=mcp:list|mcp:call`、有效 `projectRef/runRef/catalogVersion`。

生产 Compose 当前没有内置这些值，必须由 Secret Manager/部署平台注入；没有注入时静态 token 不能通过
生产静态测试守卫（生产会拒绝 static-test 绑定）。

`prod-config-check.mjs` 已扩展：当 `AGENT_MCP_GATEWAY_ENABLED=true` 时强制
`INTERNAL_AI_TOKEN_MODE=ed25519`，并校验私钥是合法 PKCS#8 Ed25519、kid 与可选公钥列表格式合法。
生产密钥仍未注入，因此脚本通过只代表格式/配对就绪，不代表生产身份已上线。

## 5. 监控与告警

### 5.1 必备指标

| 指标 | 用途 |
| --- | --- |
| `mcp_gateway_requests_total` | 请求/拒绝/未知结果计数 |
| `mcp_gateway_latency_seconds` | 网关 P50/P95/P99 |
| `mcp_gateway_tool_calls_total` | 连接级工具调用 |
| `mcp_gateway_denied_total` | 拒绝 spike |
| `mcp_gateway_schema_drift_total` | Schema/Hash 漂移 |
| `mcp_gateway_audit_failures_total` | 审计 fail-closed |
| `mcp_gateway_egress_blocked_total` | SSRF/allowlist 拦截 |
| `mcp_gateway_inflight` | 并发/在途 |

标签不包含工具参数、结果正文、文档 ID、用户输入或错误正文。

### 5.2 规则校验

```powershell
docker run --rm --entrypoint promtool `
  -v "E:\project\ai-collab\ai-collab-deploy\observability\rules:/etc/prometheus/rules:ro" `
  prom/prometheus:v3.5.0 `
  check rules /etc/prometheus/rules/mcp-gateway-alerts.yml
```

结果：`SUCCESS: 7 rules found`。

### 5.3 规则加载

`compose.local.yml` / `compose.staging.yml` 已同时挂载 `prometheus.yml` 与 `observability/rules/`：

```yaml
volumes:
  - ./observability/prometheus.yml:/etc/prometheus/prometheus.yml:ro
  - ./observability/rules:/etc/prometheus/rules:ro
```

本地 Prometheus API 实测 `groups=1`、`mcpRules=7`。生产使用外部 Prometheus/云监控时，也必须加载
同一规则文件或等价规则。Alertmanager/通知渠道未在仓库配置，canary 前必须提供“告警可送达”的
证据，否则只能看到规则 firing，不能证明有人收到。

## 6. Canary 前置检查

- [ ] Lane A 安全矩阵全绿；Lane B Ed25519/TLS/告警/回滚证据齐备；
- [ ] 当前 `dev` CI 全绿；镜像 digest 与源码 SHA 可追溯；
- [ ] `agent.mcp.gateway.enabled=false` 默认值未变；旧 Provider 回退演练 PASS；
- [ ] 生产 `INTERNAL_AI_TOKEN_MODE=ed25519`，私钥只经 Secret 注入；
- [ ] Nginx 显式 `location ^~ /internal/ { return 404; }`；
- [ ] 外部 Provider allowlist 精确、HTTPS 443、只读端点、最小 scope、owner/ToS/retention 已审；
- [ ] 项目/run/catalog/白名单/Hash 人工确认；
- [ ] Prometheus 规则已加载；Alertmanager 通知可达；值班人与升级路径明确；
- [ ] 回滚脚本/命令已演练；数据库/审计保留策略明确；
- [ ] 用户明确回复“批准 canary”。

## 7. Canary 执行

1. 只开一个非关键项目、一个只读 Provider、一个连接。
2. `agent.mcp.gateway.enabled=true`，`AGENT_MCP_ALLOWED_HOSTS` 只含该 Provider host。
3. 观察至少 30 分钟，每 5 分钟记录：请求数、错误率、P95、denied、schema drift、egress blocked、inflight、审计行数。
4. 不自动扩项目、不自动扩 Provider、不自动确认 Schema。
5. 任一停止条件触发，立即按 §8 回滚并保留证据。

停止阈值（初始建议，可按生产 SLO 调整但必须在 canary 前冻结）：

- 错误率 > 1% 连续 5 分钟；
- P95 > 2s 连续 10 分钟；
- inflight > 20；
- 任一未授权/跨项目/跨租户调用成功；
- 任一 Schema drift 未停用；
- 审计写入失败或审计不可查询；
- egress blocked 激增或出现私网/元数据连接尝试；
- 外部 Provider 状态/合规不明。

## 8. 回滚

```text
1. 将 AGENT_MCP_GATEWAY_ENABLED 改回 false（或回滚到上一个已知 digest）。
2. 滚动重启 app；确认 Gateway Controller/Service/Catalog/Authorizer Bean 缺席。
3. 清理连接/目录缓存（重启即清）；保留审计与 Schema 快照。
4. 验证旧 McpAgentToolProvider 仍可用（已绑定只读工具可枚举/执行）。
5. 记录时间、触发指标、影响项目、回滚命令、CI/镜像 SHA。
6. 不删除审计、不自动确认新 Schema、不把失败结果写成成功。
```

## 9. 告警处置锚点

<a id="schema-drift"></a>
### Schema drift
立即确认 `mcp_gateway_schema_drift_total` 与审计中的 `connectionCode`；保持工具停用，重新 discover、人工审核并 enable，禁止自动确认。

<a id="audit-failure"></a>
### Audit failure
视为安全事件：Gateway 已 fail-closed 时不得绕过；检查审计库连接/磁盘/权限，恢复后重新核对在途结果语义。

<a id="egress"></a>
### Egress blocked
检查 `reason`、host 与 DNS 解析；不得为了恢复可用性放宽 allowlist/私网规则。确需新增 Provider，走人工准入。

<a id="denied"></a>
### Denied spike
按 `reason` 区分白名单、scope、run/project、Schema；优先排查 token 签发、项目绑定或 Provider 漂移，不要直接放宽权限。

<a id="error-budget"></a>
### Error budget
先区分 `NOT_EXECUTED`（未发出）、`OUTCOME_UNKNOWN`（可能已执行）、`RATE_LIMITED`、`TRUNCATED`；未知结果不得重试为成功。

<a id="latency"></a>
### Latency
查看 Provider 延迟、inflight、deadline 与连接池；P95 超预算先停止扩流，不通过增大超时掩盖问题。

<a id="inflight"></a>
### Inflight
检查长调用/撤销竞态/Provider 卡死；必要时停用连接并取消请求，保留审计与结果未知语义。

## 10. 证据

- Nginx 语法与真实 404/200、TLS 结果：`docs/acceptance-evidence/2026-11-13/p2b2-release-readiness/lane-b/ingress-tls.md`
- 告警规则复核：`docs/acceptance-evidence/2026-11-13/p2b2-release-readiness/lane-b/alerting-review.md`
- 生产 canary 仍未启动；本文件不构成生产 PASS。
