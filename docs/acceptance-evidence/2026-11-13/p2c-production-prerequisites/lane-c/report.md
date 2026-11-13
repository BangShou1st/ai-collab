# P2-C Lane C：Alertmanager 真实送达

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 211e4e32`
> 性质：readiness 取证；**未执行 canary、未启用生产 Gateway、未切流量**
> 结论：**NOT_VERIFIED**（规则加载 PASS；本地管道模拟 PASS；生产送达未验证）

## 1. 结论先行

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| Prometheus 规则语法/加载 | **PASS** | `promtool` 7 条；Prometheus API 实际加载 `groups=1`、`rules=7`，全部 `health=ok` |
| Alertmanager 配置 | **LOCAL_SIMULATION** | 仅本地隔离容器；`amtool check-config` 通过，1 个 webhook receiver |
| 本地管道送达 | **PASS（仅模拟）** | 合成告警 `P2CLocalAlertPipelineProbe` 从 Prometheus 进入 Alertmanager，再由本地 webhook 收到 |
| 生产 Alertmanager/值班渠道 | **NOT_VERIFIED** | 仓库与环境均无生产 Alertmanager、通知渠道、接收人或值班路由 |
| 生产告警送达 | **NOT_VERIFIED** | 没有真实生产告警、真实接收端或接收人确认，不能计为 PASS |
| 用户接受人工监控风险 | **NOT ACCEPTED** | 用户尚未明确接受 `ACCEPTED_RISK` |

**判定：Lane C 不满足 PASS。** 本地模拟只证明“规则→Alertmanager→webhook”机制可用，
不能证明生产告警能到达值班人。

## 2. 规则校验与加载

### 2.1 promtool

命令：

```powershell
docker run --rm --entrypoint promtool `
  -v "${PWD}\ai-collab-deploy\observability\rules:/etc/prometheus/rules:ro" `
  prom/prometheus:v3.5.0 check rules /etc/prometheus/rules/mcp-gateway-alerts.yml
```

结果：

```text
SUCCESS: 7 rules found
```

配置校验同时通过：

```text
SUCCESS: 1 rule files found
SUCCESS: /etc/prometheus/prometheus.yml is valid prometheus config file syntax
```

原始输出：[`raw/promtool.txt`](raw/promtool.txt)、[`raw/promtool-config.txt`](raw/promtool-config.txt)。

### 2.2 Prometheus API 实际加载

启动仅加载仓库规则的隔离 Prometheus（端口 `59095`），查询
`/api/v1/rules`：

```text
checkedAt=2026-11-13T15:36:20Z
groups=1
rules=7
McpGatewaySchemaDrift        health=ok
McpGatewayAuditUnavailable   health=ok
McpGatewayEgressBlocked      health=ok
McpGatewayDeniedSpike        health=ok
McpGatewayErrorRateHigh      health=ok
McpGatewayLatencyP95High     health=ok
McpGatewayInflightHigh       health=ok
```

原始输出：[`raw/rules-loaded.txt`](raw/rules-loaded.txt)。

**边界：** 这只证明 7 条规则被 Prometheus 解析并加载，不证明生产 Prometheus
已加载、也不证明规则在真实指标上触发。

## 3. Alertmanager 本地管道模拟

### 3.1 隔离拓扑

```
Prometheus (p2c-prometheus:9090)
  -> rule: P2CLocalAlertPipelineProbe (expr: vector(1))
  -> Alertmanager (p2c-alertmanager:9093, receiver: local-webhook)
  -> Python webhook (p2c-webhook:54321/alerts)
  -> target/p2c-lane-c/received.jsonl
```

该拓扑运行在独立 Docker 网络 `p2c-alert-net`，不连接生产 Prometheus，不修改仓库
告警规则；配置和接收器均复制在 [`raw/harness/`](raw/harness/)。

`amtool check-config`：

```text
Checking '/etc/alertmanager/alertmanager.yml'  SUCCESS
Found:
 - global config
 - route
 - 0 inhibit rules
 - 1 receivers
 - 0 templates
```

原始输出：[`raw/alertmanager-check.txt`](raw/alertmanager-check.txt)。

### 3.2 送达时间线

| 字段 | 值 |
| --- | --- |
| 告警名 | `P2CLocalAlertPipelineProbe` |
| 指纹 | `0788a407078f799c` |
| Alertmanager receiver | `local-webhook` |
| Prometheus `startsAt` | `2026-11-13T15:38:13.644Z` |
| Alertmanager `updatedAt` | `2026-11-13T15:42:18.223Z` |
| Webhook 收到 | `2026-11-13T15:39:15.173Z` |
| 端到端延迟 | **61.529 s** |
| Webhook `alertCount` | `1` |
| Webhook `status` | `firing` |

Webhook 收到的脱敏 payload：

```json
{
  "alertCount": 1,
  "alerts": [{
    "status": "firing",
    "labels": {
      "alertname": "P2CLocalAlertPipelineProbe",
      "channel": "local-webhook",
      "component": "p2c-lane-c",
      "severity": "info"
    },
    "annotations": {
      "summary": "P2-C local Alertmanager pipeline probe",
      "description": "Synthetic alert; proves Prometheus rule -> Alertmanager -> local webhook only."
    }
  }]
}
```

原始记录：[`raw/received.jsonl`](raw/received.jsonl)、
[`raw/pipeline-timeline.txt`](raw/pipeline-timeline.txt)。

**延迟解释：** 61.529s 含 Alertmanager gossip settle、group wait 与本地轮询，
不是生产送达 SLO；不得据此推导生产时效。

## 4. 生产送达缺口

### 4.1 仓库扫描

```text
- ai-collab-deploy/ 下没有 alertmanager.yml / alertmanager.yaml
- compose.prod.yml 没有 alertmanager 服务
- ai-collab-deploy/backup/emit-alert.sh 是备份事件 hook，不是 Prometheus Alertmanager
- 环境变量中没有 ALERT_HOOK / ALERTMANAGER_URL / webhook 通知渠道
- .env 仅有 Feishu 业务凭据，未配置告警接收端
```

原始扫描：[`raw/config-scan.txt`](raw/config-scan.txt)。未输出任何凭据值。

### 4.2 缺口清单

| 缺口 | 当前状态 | 谁提供/决策 | 最小下一步 |
| --- | --- | --- | --- |
| 生产 Alertmanager 实例 | 不存在于仓库/环境 | 平台/运维 | 部署受控 Alertmanager，配置 TLS/认证与持久化 |
| 通知渠道与凭据 | 无 | 运维/安全 | 通过 Secret Manager 注入 webhook/邮件/IM 凭据 |
| 值班接收人/路由 | 无 | 团队负责人 | 明确 primary/secondary、group_by、silence 与升级路径 |
| 生产真实测试告警 | 未执行 | SRE/值班人 | 在生产规则上制造可回滚测试告警，记录送达回执 |
| 告警可观测闭环 | 仅规则与本地模拟 | SRE | 验证 Alertmanager 自身存活、通知失败重试、静默与抑制 |
| 用户风险接受 | 未接受 | 用户 | 若不接 Alertmanager，明确接受人工监控 + 风险并签署 `ACCEPTED_RISK` |

### 4.3 为什么不能写 PASS

- 本地 webhook 不是值班渠道，没有真实接收人；
- 合成 `vector(1)` 规则不是生产 MCP 规则，也没有真实业务指标；
- 没有生产 Alertmanager 的 URL、TLS、认证、HA、持久化与升级路径；
- 没有“告警到达值班人并确认”的回执。

## 5. ACCEPTED_RISK 模板

若用户决定暂不接 Alertmanager，必须明确接受以下风险，才能把 Lane C 从
`NOT_VERIFIED` 改为 `ACCEPTED_RISK`（仍不能写 PASS）：

```text
我接受 P2-C 生产 canary 期间不接 Alertmanager，改为人工监控。

监控频率：每 ___ 分钟检查一次 Prometheus/日志/审计
责任人：___（primary），___（backup）
升级路径：发现异常后 ___ 分钟内通知 ___，___ 分钟内决定回滚
已知盲区：
- 无法保证 7x24 即时发现
- 无法保证告警去重、抑制、升级与送达回执
- 夜间/周末依赖人工值守
- Schema drift、审计失败、egress 拦截可能延迟发现

我理解该风险由我承担，且该接受不构成“告警已就绪”或“生产 canary 已批准”。
```

模板全文：[`accepted-risk-template.md`](accepted-risk-template.md)。当前**未签署**。

## 6. 判定与边界

```text
Lane C：NOT_VERIFIED
规则加载：PASS
本地管道模拟：PASS（LOCAL_SIMULATION）
生产送达：NOT_VERIFIED
生产 canary：NOT_STARTED
```

没有真实值班渠道或用户明确 `ACCEPTED_RISK` 前，Lane C 不满足 P2-C 的
`READY_FOR_CANARY_APPROVAL` 条件。

## 7. 清理

- 本地容器 `p2c-webhook`、`p2c-alertmanager`、`p2c-prometheus`、
  `p2c-prom-rules` 与网络 `p2c-alert-net` 在取证后回收；
- 未修改 `ai-collab-deploy/observability`、`compose.prod.yml` 或任何生产配置；
- 未启用 Gateway、未执行 canary、未触碰生产流量。
