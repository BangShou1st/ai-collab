# P2-B2 生产 canary 检查点（2026-11-13）

> **2026-11-14 更新**：本检查点已被 `docs/archive/report/w6-production-decision-2026-11-14.md` 取代，
> canary 状态改为 `CANCELLED_BY_DECISION`；生产动作仍为 0。

> 状态：**WAITING_FOR_CANARY_APPROVAL**
> 性质：检查点，不是 canary 开始、不是生产 PASS
> 证据基座：`dev @ caf0d28b`（最终文档提交另行记录）
> CI：`43449307527` success @ `39ac7fd5`
> 生产动作：**0**。未启用 Gateway、未写入生产状态、未清空任何生产资源。

## 1. 当前判定

| 项 | 状态 |
| --- | --- |
| Lane A 功能/安全矩阵 | **PASS**（12/12 真实进程 case） |
| Lane B 本地生产 readiness | **CONDITIONAL PASS** |
| Lane C 收尾 | **PASS（1 项基础设施 Deferred）** |
| Lane D 只读盘点 | **PASS（未删除/未 rewrite）** |
| 生产 canary | **WAITING_FOR_CANARY_APPROVAL / NOT_STARTED** |

## 2. 已通过

- `agent.mcp.gateway.enabled=false` 默认值未改；
- V99 Provider 准入、双 Server、租户隔离、Hash fence、在途撤权、审计脱敏；
- Ed25519 真实 verifier、篡改签名/错误 scope 拒绝；
- Nginx `/internal/**` 与 `/actuator` 显式 404，TLS 1.2/1.3；
- Gateway 指标真实暴露、7 条 Prometheus 规则实际加载；
- Gateway-off 真实 Spring Boot 回退旧 Provider；
- 100 次本地调用 P95 98.901ms；
- N5 Reranker 管理 UI、JMeter 口径收口、Testcontainers 资源最小缓解；
- 仓库盘点无历史 rewrite 需求。

## 3. 批准前必须确认的两个外部前置

### 3.1 生产调用方与短令牌获取

`compose.prod.yml` 没有 MCP Host/worker，Java Agent 尚未把 Gateway 接入工具执行
主路径。必须明确：

- canary 由哪个内部客户端发起；
- 该客户端如何获得绑定真实 `projectRef/runRef/catalogVersion` 的 Ed25519 短令牌；
- 该调用是否代表真实用户路径，还是仅协议探针。

在该决策完成前，批准也不能等价为“生产已 ready”。

### 3.2 告警送达

规则已加载，`promtool` 与 Prometheus API 已验证 7 条规则；但仓库没有生产
Alertmanager/通知渠道。必须提供一次“告警从 Prometheus 到值班渠道”的送达证据。

## 4. 批准后的执行边界

用户必须明确回复“批准 canary”才可开始。开始后：

- 只开一个非关键项目、一个只读 Provider、一个连接；
- `agent.mcp.gateway.enabled=true`，allowlist 只含该 Provider host；
- 观察至少 30 分钟，每 5 分钟记录错误率、P95、denied、drift、egress、inflight、审计；
- 任一未授权/跨租户/审计失败/Schema 漂移/回滚失败立即停止；
- 不自动扩项目、不自动扩 Provider、不自动确认 Schema。

## 5. 回滚

1. `AGENT_MCP_GATEWAY_ENABLED=false` 或回滚镜像；
2. 滚动重启 app；
3. 确认 Gateway Bean 缺席、`/internal/ai/v1/mcp` 不可用；
4. 验证旧 `McpAgentToolProvider` 可枚举/执行已绑定只读工具；
5. 保留审计与 Schema 快照，记录指标、时间、镜像/提交 SHA。

## 6. 未执行

- 生产私钥注入、生产证书链、生产安全组；
- 生产 Gateway 启用；
- 生产 canary；
- `main` 合并、force push、历史重写。

## 7. 当前停止点

```text
WAITING_FOR_CANARY_APPROVAL
```

没有用户批准前，不继续生产动作。该检查点不关闭任何生产会话资源。
