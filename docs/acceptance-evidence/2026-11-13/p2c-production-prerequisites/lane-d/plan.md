# P2-C Lane D：Canary 与回滚方案

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 211e4e32`
> 性质：**PLAN_ONLY / NOT_EXECUTED**
> 结论：方案完整性 **PASS**；生产 canary **NOT_STARTED**，必须等用户明确批准
> 边界：本文件不启用 Gateway、不打开流量、不写生产数据、不执行 canary

## 0. 结论先行

本方案只回答“如果用户批准，canary 应如何安全执行、如何停止、如何回滚”。
它不证明 Gateway 已具备生产调用方，也不证明生产证书或告警已就绪。

以下三个前置仍然必须由 Lane A/B/C 清零：

1. Lane A：真实生产调用方，以及绑定 `projectRef/runRef/catalogVersion` 的短令牌获取路径；
2. Lane B：真实生产 key/cert、TLS 链、hostname、轮换与私钥权限；
3. Lane C：Alertmanager 真实送达，或用户明确接受人工监控风险。

**关键事实**：当前 Java Agent 的工具执行主路径仍使用旧
`McpAgentToolProvider`，Gateway 只在 `agent.mcp.gateway.enabled=true`
时装配内部 `/internal/ai/v1/mcp` 端点；仓库中没有生产代码把 Agent 的工具调用
转到 Gateway。因此：

- 若 Lane A 只能提供测试/探针客户端，本 canary 只能叫 **PROTOCOL_CANARY**，
  不能代表真实用户路径，也不能据此宣称用户侧 readiness；
- 若 Lane A 决定 Java Agent 接入 Gateway，必须先完成该接入并单独评审，
  不能在本方案中现场实现或把探针结果冒充用户路径。

## 1. 范围冻结

### 1.1 唯一 canary 范围

| 维度 | 冻结值 | 规则 |
| --- | --- | --- |
| 租户/项目 | `CANARY_PROJECT_ID`（单个非关键测试项目） | 不使用客户生产项目；项目 owner 书面同意 |
| 调用方 | `CANARY_CALLER`（Lane A 决定） | 必须是已部署、可追溯的真实内部调用方；探针需另标 `PROTOCOL_CANARY` |
| Provider | `CANARY_PROVIDER`（单个已审批只读 Provider） | owner/ToS/retention/最小 scope 已审；仅官方只读端点 |
| 连接 | `CANARY_CONNECTION_ID` / `CANARY_CONNECTION_CODE` | `STREAMABLE_HTTP`、HTTPS 443、精确 host；拒绝 STDIO |
| 工具 | `CANARY_TOOL_NAME`（单个只读工具） | `annotations.readOnlyHint=true`、已人工确认、项目级白名单仅含该工具 |
| 目录版本 | `CANARY_CATALOG_VERSION`（`sha256:<64 hex>`） | 与 `confirmed_schema_hash` 一致；canary 期间不得漂移 |
| 镜像 | `CANARY_IMAGE_DIGEST` | 不可变 digest，禁止 `latest` |
| 源码 | `CANARY_COMMIT_SHA` | 必须是当前 `dev` 的 40 位 SHA，且 CI 全绿 |
| 允许出网 | `CANARY_HOST` | `AGENT_MCP_ALLOWED_HOSTS` 只含该 host，不含通配符 |

所有占位值必须在批准前由部署/产品负责人填入并写入证据；未填值不得开始。

### 1.2 明确不做

- 不扩第二个项目、第二个租户、第二个 Provider、第二个连接或第二个工具；
- 不自动确认新 Schema、不自动迁移 Hash、不自动放宽 allowlist；
- 不把 MCP 工具接入写路径、不创建审批写工具、不绕过
  `ApprovalWriteAgentTool` / `ControlledWriteAgentTool`；
- 不把 canary 结果用于自动扩容、自动发布或自动切换 `main`；
- 不在 canary 期间修改 W4 Prompt v5、W3 inline 8 或六个默认开关；
- 不删除审计、Schema 快照、运行记录或生产数据。

### 1.3 部署形态前置

`agent.mcp.gateway.enabled` 是应用级开关。开启前必须确认“只有 canary 项目能看见
Gateway 工具”，而不是只依赖前端隐藏按钮。

二选一，优先 A：

- **A. 共享生产 app，单项目绑定**：开启前执行 §2.2 的绑定盘点；若存在任何其他
  `enabled` 且 `APPROVED` 的项目绑定，禁止直接开启，转 B。
- **B. 独立 canary app 实例**：使用同一不可变镜像、独立服务名/内部入口、
  `AGENT_MCP_GATEWAY_ENABLED=true`，只绑定 canary 项目；必须有独立资源与回滚
  负责人，且不得暴露公网入口。该形态需要单独评审，不在本方案默认路径内。

无论 A/B，`/internal/ai/v1/mcp` 只能从内部网络访问；Nginx 必须继续返回 404。

## 2. 启动前检查（T-24h 至 T-10min）

### 2.1 硬门禁

全部为“是”才可进入 T-0：

- [ ] 用户已明确回复“批准 canary”，并写明项目/连接/工具/日期；
- [ ] Lane A/B/C 已达到 `READY_FOR_CANARY_APPROVAL` 允许的状态；
- [ ] Lane D 方案已评审，批准人/观察人/回滚人已具名；
- [ ] `dev` CI 全绿，`CANARY_IMAGE_DIGEST` 与 `CANARY_COMMIT_SHA` 可追溯；
- [ ] 工作区干净，只有 `dev`/`main` 和主 worktree；
- [ ] `agent.mcp.gateway.enabled` 默认值仍为 `false`；
- [ ] 已保存旧 Provider 回退证据与本次回滚步骤；
- [ ] 已保存 canary 前的 Schema/绑定/审计基线；
- [ ] 告警值班人与升级路径已确认，测试告警已送达（或用户书面接受风险）；
- [ ] 生产证书/密钥已由安全注入完成，未进入仓库/日志/报告。

任一未满足，停止在 `NOT_READY`，不得用“先开再说”绕过。

### 2.2 绑定与 Schema 盘点

在只读副本或受控查询窗口执行；结果只记录计数和 Hash，不导出工具正文。

```sql
-- 1) canary 连接必须唯一、启用、Hash 一致、Provider 已审批且只读
SELECT c.id, c.project_id, c.code, c.enabled,
       c.schema_hash = c.confirmed_schema_hash AS hash_ok,
       c.tool_allowlist_json, c.version AS connection_version,
       a.status, a.read_only_endpoint, a.least_privilege_scope,
       a.provider_owner, a.retention_policy, a.version AS admission_version
FROM agent_mcp_connection c
JOIN agent_mcp_provider_admission a ON a.connection_id = c.id
WHERE c.id = :canary_connection_id
  AND c.project_id = :canary_project_id;

-- 2) 连接级白名单只能含 canary 工具；V31 后连接按项目隔离，无独立绑定表
SELECT c.id, c.project_id, c.code, c.enabled,
       c.tool_allowlist_json, c.version AS connection_version
FROM agent_mcp_connection c
WHERE c.project_id = :canary_project_id
  AND c.id = :canary_connection_id;

-- 3) 其他项目不得有可暴露的已审批连接；结果必须为 0，否则改用独立 canary 实例
SELECT count(*) AS other_exposed_connections
FROM agent_mcp_connection c
JOIN agent_mcp_provider_admission a ON a.connection_id = c.id
WHERE c.enabled
  AND c.schema_hash = c.confirmed_schema_hash
  AND a.status = 'APPROVED'
  AND a.read_only_endpoint = true
  AND c.project_id <> :canary_project_id;
```

同时记录：

- `CANARY_CATALOG_VERSION` 与 `confirmed_schema_hash`；
- `agent_mcp_connection.version`、`agent_mcp_provider_admission.version`；
- `discovered_tools_json` 的 SHA-256（不保存正文）；
- canary 项目当前 Agent 可见工具名列表（旧 Provider 基线）。

### 2.3 回滚预演

在 canary 前至少完成一次非生产回滚预演，记录：

- 关闭开关后 `/internal/ai/v1/mcp` 返回 404；
- 旧 `McpAgentToolProvider` 仍能枚举并执行一个已绑定只读工具；
- 审计/Schema 快照未删除；
- 回滚耗时与操作人。

已有 P2-B1/P2-B2 的 Gateway-off 证据可作为基线，但 canary 前必须确认当前镜像
仍保持同样行为。

## 3. 时间线与样本量

### 3.1 最低要求

| 项 | 要求 |
| --- | --- |
| 最短持续时间 | 30 分钟连续观察 |
| 计划持续时间 | 60 分钟（低流量时延长） |
| 最小 `tools/call` 样本 | 300 次成功/失败可分类调用 |
| 最小独立 run | 5 个不同 `runRef`；每 run 的实际工具调用上限以运行预算为准，不足则增加 run |
| 最小 5 分钟桶 | 6 个完整桶；每桶至少 20 次 `tools/call` |
| 低流量处理 | 30 分钟不足 300 次则延长至 60 分钟；60 分钟仍 <100 次则 `INCONCLUSIVE`，回滚且不得宣称 PASS |

**样本量依据**：300 次调用、0 次失败时，按“rule of three”近似，95% 置信上界约为
1%。这不是统计显著性声明，而是最低风险样本门槛；不足 300 次只能写
`INCONCLUSIVE`，不能写 “成功率 100%”。

### 3.2 时间线

| 时间 | 动作 | 记录 |
| --- | --- | --- |
| T-24h | 冻结范围/镜像/SHA/阈值；完成 §2 检查 | 冻结表、审批消息 |
| T-60m | 基线快照：Schema、绑定、审计计数、旧 Provider 工具列表 | 基线 JSON/CSV（脱敏） |
| T-30m | 确认告警可达、值班在线、回滚人待命 | 告警测试回执、值班表 |
| T-10m | 最后一次只读门禁；确认默认开关仍为 false | 检查输出 |
| T-0 | 开启 canary 配置；滚动重启；验证内部端点与 Nginx 404 | 启动日志、HTTP 状态 |
| T+0～5m | 负向探针（过期/篡改/错误 scope/跨项目），不计入成功率 | 状态码、错误码 |
| T+5m | 第一个正常 `tools/list` + 1 次 `tools/call`，确认链路 | 调用记录、审计行 |
| T+5～35m | 连续 30 分钟正常观察；每 5 分钟记录指标/审计/异常 | 观察表 |
| T+35m | 30 分钟样本检查；不足 300 则继续 | 样本计数 |
| T+35～65m | 继续观察至 60 分钟正常流量；每 5 分钟记录 | 观察表 |
| T+65m | 样本/安全门禁检查；决定结束或延长 | 决策记录 |
| T+65～125m | 仅在延长条件满足时继续；仍不足则 `INCONCLUSIVE` | 延长记录 |
| 结束 | 默认回滚到 `false`；保留证据；出结论 | 回滚记录、最终报告 |

canary 结束默认回到 `false`。若业务要求保留开启，必须另行批准，不在本方案内。

## 4. 观测指标与数据源

### 4.1 指标表

| 指标 | 数据源 | 查询/口径 | 记录频率 |
| --- | --- | --- | --- |
| 调用成功率 | Prometheus | `mcp_gateway_tool_calls_total{connectionCode="..."}` 中 `outcome="SUCCEEDED"` 占比 | 每 5 分钟 |
| 请求错误率 | Prometheus | `mcp_gateway_requests_total` 的错误/未知/截断 outcome 占比 | 每 5 分钟 |
| Gateway P50/P95/P99 | Prometheus | `histogram_quantile` on `mcp_gateway_latency_seconds_bucket` | 每 5 分钟 |
| 用户侧工具延迟 | `agent_run_event` | `TOOL_CALL_STARTED` 到 `TOOL_CALL_COMPLETED/FAILED` 的 `created_at` 差值，按 `callId` 配对 | 每 5 分钟 |
| timeout | Prometheus | `outcome="DEADLINE_EXCEEDED"` 计数/占比 | 每 5 分钟 |
| 429 | Prometheus | `outcome="RATE_LIMITED"` 计数/占比 | 每 5 分钟 |
| 5xx/上游错误 | Prometheus + 结构化日志 | `outcome="INTERNAL"` 与 `outcome="OUTCOME_UNKNOWN"` 分开统计；用 `mcp_gateway_audit` 日志复核上游 5xx/传输错误 | 每 5 分钟 |
| Schema drift | Prometheus | `increase(mcp_gateway_schema_drift_total[5m])` | 每 5 分钟 |
| 撤权传播 | 审计 + 指标 | `TOOL_REVOKED`、绑定 `version` 变化、撤权后拒绝计数 | 事件驱动 |
| 审计行 | `audit_log` | `entity_type='AGENT_MCP_GATEWAY'` 的 `TOOLS_LIST`/`TOOLS_CALL` 计数 | 每 5 分钟 |
| in-flight | Prometheus | `max_over_time(mcp_gateway_inflight[5m])` | 每 5 分钟 |
| egress blocked | Prometheus | `increase(mcp_gateway_egress_blocked_total[5m])` | 每 5 分钟 |
| denied | Prometheus | `sum by (reason) (rate(mcp_gateway_denied_total[5m]))` | 每 5 分钟 |

### 4.2 PromQL

```promql
# 调用成功率（按连接）
sum(rate(mcp_gateway_tool_calls_total{connectionCode="CANARY_CONNECTION_CODE",outcome="SUCCEEDED"}[5m]))
/
clamp_min(sum(rate(mcp_gateway_tool_calls_total{connectionCode="CANARY_CONNECTION_CODE"}[5m])), 0.001)

# 错误率（与告警规则同口径）
sum(rate(mcp_gateway_requests_total{
  outcome=~"INTERNAL|OUTCOME_UNKNOWN|NOT_EXECUTED|PARAM_INVALID|SCHEMA_DRIFT|TOOL_REVOKED|RATE_LIMITED|PARTIAL|DEADLINE_EXCEEDED|TRUNCATED|UNAUTHORIZED"
}[5m]))
/
clamp_min(sum(rate(mcp_gateway_requests_total[5m])), 0.001)

# Gateway P95
histogram_quantile(0.95, sum(rate(mcp_gateway_latency_seconds_bucket[5m])) by (le))

# in-flight 峰值
max_over_time(mcp_gateway_inflight[5m])

# Schema drift / 审计失败 / egress 拦截
increase(mcp_gateway_schema_drift_total[5m])
increase(mcp_gateway_audit_failures_total[5m])
increase(mcp_gateway_egress_blocked_total[5m])
```

### 4.3 审计查询

```sql
SELECT date_trunc('minute', created_at) AS bucket,
       count(*) FILTER (WHERE action = 'AGENT_MCP_GATEWAY_TOOLS_CALL') AS call_rows,
       count(*) FILTER (WHERE action = 'AGENT_MCP_GATEWAY_TOOLS_LIST') AS list_rows,
       count(*) FILTER (WHERE detail->>'outcome' = 'SUCCEEDED') AS succeeded_rows,
       count(*) FILTER (WHERE detail->>'outcome' IN (
         'INTERNAL','OUTCOME_UNKNOWN','NOT_EXECUTED','PARAM_INVALID','SCHEMA_DRIFT',
         'TOOL_REVOKED','RATE_LIMITED','PARTIAL','DEADLINE_EXCEEDED','TRUNCATED','UNAUTHORIZED'
       )) AS error_rows
FROM audit_log
WHERE project_id = :canary_project_id
  AND entity_type = 'AGENT_MCP_GATEWAY'
  AND created_at >= :canary_start
GROUP BY 1
ORDER BY 1;
```

审计对账规则（仅针对已成功解析授权上下文的正常流量；预注册负向探针单独记录）：

- 每个正常 `tools/call` 请求必须有一条 `AGENT_MCP_GATEWAY_TOOLS_CALL` 审计行；
- 每个正常 `tools/list` 请求必须有一条 `AGENT_MCP_GATEWAY_TOOLS_LIST` 审计行；
- 任何 `mcp_gateway_audit_failures_total` 增量 > 0，或审计行缺失，立即停止；
- 抽样检查 `detail` 只含低敏元数据，不含参数、结果正文、Token、Prompt、文档正文。

### 4.4 用户侧延迟查询

```sql
WITH calls AS (
  SELECT run_id,
         payload_json->>'callId' AS call_id,
         payload_json->>'toolName' AS tool_name,
         max(created_at) FILTER (WHERE type = 'TOOL_CALL_STARTED') AS started_at,
         max(created_at) FILTER (WHERE type IN ('TOOL_CALL_COMPLETED','TOOL_CALL_FAILED')) AS finished_at
  FROM agent_run_event
  WHERE project_id = :canary_project_id
    AND created_at >= :canary_start
    AND payload_json->>'toolName' LIKE 'mcp.%'
    AND payload_json->>'callId' IS NOT NULL
  GROUP BY run_id, payload_json->>'callId', payload_json->>'toolName'
)
SELECT count(*) AS samples,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - started_at)) * 1000) AS p50_ms,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - started_at)) * 1000) AS p95_ms,
       percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM (finished_at - started_at)) * 1000) AS p99_ms
FROM calls
WHERE started_at IS NOT NULL AND finished_at IS NOT NULL;
```

该查询是“工具墙钟延迟”代理，不包含模型思考/首 token 时间；端到端用户延迟另取
前端/APM 的 Agent 请求耗时，不能用本查询冒充。

若同一工具在旧 Provider 与 Gateway 之间切换，`agent_run_event` 本身不区分来源；
必须用 Gateway 审计中的 `connectionCode` 与时间窗交叉核对，不能把所有同名调用
都算作 Gateway canary 样本。

## 5. 停止与回滚阈值

### 5.1 立即停止（无需等待连续窗口）

任一命中即停止新调用并回滚：

- 未授权、跨项目、跨租户调用成功；
- 任何 `SCHEMA_DRIFT` 未被自动停用，或出现 drift 后仍可调用；
- 审计写入失败、审计行缺失、审计不可查询，或审计含敏感正文；
- egress 出现私网、回环、链路本地、元数据地址，或 allowlist 外连接；
- 撤权后新调用仍成功，或在途结果被标记为 `SUCCEEDED`；
- 结果无法区分 `NOT_EXECUTED` / `OUTCOME_UNKNOWN` / `PARTIAL` / `TRUNCATED`；
- 回滚预演失败，或关闭 Gateway 后旧 Provider 不可用；
- 生产证书/令牌/密钥异常，或出现任何凭据泄漏迹象；
- 用户/安全负责人要求停止。

### 5.2 质量/容量阈值

以下阈值是 canary 初始预算，必须在 T-0 前冻结；任何变更都要重新批准。

| 指标 | 停止阈值 | 说明 |
| --- | --- | --- |
| `SUCCEEDED` 成功率 | 5 分钟桶内 < 99%，且该桶 `tools/call` ≥ 20 | 连续 2 个桶命中即回滚 |
| 请求错误率 | 5 分钟桶内 > 1%，且该桶请求数 ≥ 20 | `mcp_gateway_requests_total` 错误口径；连续 2 个桶命中即回滚 |
| `OUTCOME_UNKNOWN` | 任 5 分钟桶 > 0.5%，或单桶 ≥ 2 次 | 需人工核对在途语义，不得当成功 |
| `INTERNAL` | 任 5 分钟桶 > 0.5%，或连续 2 个桶 > 0 | 上游/网关内部错误 |
| `DEADLINE_EXCEEDED` | 任 5 分钟桶 > 1%，或累计 ≥ 3 次 | 超时不可用重试掩盖 |
| `RATE_LIMITED` | 任 5 分钟桶 > 5%，或连续 2 个桶 > 0 | 429 单独统计 |
| Gateway P95 | > 2000ms 持续 10 分钟 | 与现有告警规则一致 |
| 用户侧工具 P95 | > 3000ms 持续 10 分钟 | 不含模型时间；按 §4.4 口径 |
| 用户侧工具 P99 | > 5000ms 持续 10 分钟 | 同上 |
| `max_over_time(inflight[5m])` | > 20 | 立即暂停新调用；连续 2 个采样仍 >20 则回滚 |
| 非预期 `denied` | 任 5 分钟桶 > 1%，或出现 `UNAUTHORIZED`/`TOOL_REVOKED` 非预注册负向探针 | 按 reason 区分，不放宽权限 |
| `egress_blocked` | 任 5 分钟桶 > 5 次，或出现私网/元数据目标 | 任何私网/元数据目标立即回滚 |
| `PARTIAL`/`TRUNCATED` | 任 5 分钟桶 > 0.5% | 计为失败/降级，不得重试成成功 |
| 审计行差 | 任 5 分钟桶正常流量 `call_rows` ≠ Prometheus `mcp_gateway_requests_total{method="tools/call"}`（排除预注册负向探针） | 差 1 行即停止 |

### 5.3 样本不足

- 30 分钟 < 300 次：延长至 60 分钟；
- 60 分钟 < 100 次：判 `INCONCLUSIVE`，回滚，不得写 PASS；
- 60 分钟 100～299 次：延长至 120 分钟；仍不足 300 次则 `INCONCLUSIVE`；
- 任何阶段出现样本偏差（只有健康检查、只有 `tools/list`、没有真实 run），
  不得用调用总数冒充用户路径样本。

## 6. 回滚步骤（Gateway off + 旧 Provider）

### 6.1 触发与角色

- 触发人：观察人、回滚人、批准人、安全/值班负责人都可触发；
- 执行人：`ROLLBACK_OPERATOR`（具名）；
- 复核人：`CANARY_COMMANDER`（具名）；
- 回滚目标时间：确认触发后 5 分钟内完成开关关闭，15 分钟内完成验证；
- 回滚期间不删除审计、Schema、运行记录；不自动确认新 Schema。

### 6.2 操作步骤

1. **冻结变更**：停止扩项目/扩 Provider/扩工具；通知值班人进入回滚。
2. **关闭开关**：在受控部署环境将 `AGENT_MCP_GATEWAY_ENABLED=false`（或回滚到
   上一个已知镜像 digest）；不得把值写入仓库、报告或聊天。
3. **滚动重启 app**：

   ```powershell
   # 示例：在受控部署主机执行；实际命令以部署平台为准
   docker compose --env-file .env -f ai-collab-deploy/compose.prod.yml up -d --no-deps app
   ```

4. **验证端点关闭**：

   ```powershell
   docker compose --env-file .env -f ai-collab-deploy/compose.prod.yml `
     exec -T app wget -S -O /dev/null http://127.0.0.1:8080/internal/ai/v1/mcp
   ```

   期望 HTTP 404；若返回 401/200/500，说明端点仍存在或配置未生效，继续排查，
   不得宣称回滚成功。

5. **验证公网入口不转发**：从公网入口请求 `https://<CANARY_PUBLIC_HOST>/internal/ai/v1/mcp`，
   期望 HTTP 404；该检查独立于 app 内部端点，防止 Nginx 配置漂移。

6. **验证 Gateway 指标/日志停止**：重启后不应再新增 `mcp_gateway_*` 样本；
   检查启动日志无 Gateway Controller/Service 注册。生产不开放 `/actuator/beans`，
   因此以“条件装配代码 + 404 + 指标停止 + 旧 Provider 可用”组合判定，不伪造
   Bean 内省证据。

7. **验证旧 Provider**：在 canary 项目执行一次已审批只读工具（同一工具或等价
   只读工具），期望：
   - Agent 工具列表可见；
   - 调用返回 `SUCCEEDED`；
   - `agent_run_event` 出现 `TOOL_CALL_COMPLETED`；
   - 不经过 `/internal/ai/v1/mcp`。

8. **保留证据**：
   - 导出脱敏审计摘要（时间、action、outcome、latency、计数），不导出参数/结果；
   - 保存 Schema/绑定快照的 Hash 与版本号；
   - 记录回滚开始/结束时间、触发指标、镜像 digest、commit SHA、操作人；
   - 不删除任何审计或 Schema 行。

9. **失败升级**：若 15 分钟内无法恢复旧 Provider：
   - 保持 Gateway 关闭；
   - 回滚到上一个已知镜像 digest；
   - 仍失败则停止 Agent MCP 工具能力并升级到工程/安全负责人；
   - 不得重新开启 Gateway 作为“修复”。

### 6.3 回滚后判定

| 检查 | PASS 条件 |
| --- | --- |
| 开关 | `agent.mcp.gateway.enabled=false` |
| 内部端点 | `/internal/ai/v1/mcp` 返回 404 |
| Gateway 指标 | 无新增 `mcp_gateway_*` 样本 |
| 旧 Provider | 已绑定只读工具可枚举、可执行 `SUCCEEDED` |
| 审计 | 原审计保留，可查询，无敏感正文 |
| Schema | 原 Hash/版本保留，未自动确认新 Schema |
| 生产数据 | 未删除、未清空 |

## 7. 负向探针

负向探针必须在正常流量前执行，结果单独记录，排除在成功率之外；不得用负向探针
制造“已覆盖安全矩阵”的假象。

| 探针 | 期望 |
| --- | --- |
| 过期 token | HTTP 401，`UNAUTHORIZED` |
| 篡改 JWS 签名 | HTTP 401，`UNAUTHORIZED` |
| 错误 scope（无 `mcp:call`） | HTTP 401/拒绝，不执行外部调用 |
| 缺失/非法 `catalogVersion` | JSON-RPC `PARAM_INVALID` 或 `SCHEMA_DRIFT`，不执行 |
| `projectRef` 与 `runRef` 不匹配 | 拒绝，不泄露工具存在性 |
| 无项目绑定的第二项目 token | 拒绝；不得返回工具/结果 |
| 请求体自报 `projectId/userId/role` | 被拒绝，身份只来自 claims |
| 未审批/未绑定工具直调 | `TOOL_DISABLED`/拒绝，零外部连接 |

## 8. 撤权与在途语义

### 8.1 预期语义

- 撤权后新调用：拒绝，错误码 `TOOL_REVOKED` 或 `UNAUTHORIZED`；
- 在途调用：返回前再次校验 `catalogVersion + bindingVersion + lease`；
  失败时返回 `TOOL_REVOKED` 或 `OUTCOME_UNKNOWN`；
- 在途结果不得以 `SUCCEEDED` 写入上下文、审计或用户可见结果；
- 撤权后缓存按 `catalogVersion` 失效；不得继续暴露旧目录。

### 8.2 canary 内演练限制

- 优先使用 canary 前 staging 的 M-08/M-17 真实进程证据；
- 生产 canary 中若 Provider 没有安全的慢速只读方法，**不得人为延迟/阻塞**制造
  在途场景；
- 若无法安全演练，记录 `NOT_EXERCISED`，不得写 PASS；该缺口由 Lane A/B 评审
  决定是否阻断 canary；
- 若 canary 期间发生真实撤权事件，按 §8.1 核对审计与结果；任一违反立即回滚。

## 9. 告警与人工监控

- Lane C 已真实送达：按 §4.1 观察，告警到达后 5 分钟内确认；
- Lane C 为 `ACCEPTED_RISK`：观察人每 5 分钟手工查询一次指标与审计，记录
  “人工监控”而非“自动告警”；发现阈值命中立即回滚；
- 任何情况下都不得把“规则已加载”写成“告警已送达”。

## 10. 角色与批准

| 角色 | 职责 | 具名要求 |
| --- | --- | --- |
| `CANARY_APPROVER` | 产品/工程负责人，批准范围与时间窗 | 批准前必须具名 |
| `CANARY_COMMANDER` | 总协调，决定继续/停止/回滚 | 批准前必须具名 |
| `CANARY_OBSERVER` | 每 5 分钟记录指标、审计、异常 | 至少 2 人轮班 |
| `ROLLBACK_OPERATOR` | 执行开关关闭、重启、旧 Provider 验证 | 批准前必须具名 |
| `SECURITY_REVIEWER` | 审计/egress/密钥事件升级 | 批准前必须具名 |
| `PROVIDER_OWNER` | 外部 Provider 状态/限额/合规 | 批准前必须具名 |

批准必须是对以下内容的明确回复：

```text
批准 canary：项目=<CANARY_PROJECT_ID>，连接=<CANARY_CONNECTION_CODE>，
工具=<CANARY_TOOL_NAME>，窗口=<开始>-<结束>，调用方=<CANARY_CALLER>。
```

没有这句明确批准，不得开启 Gateway；不能把“继续”“可以”“看起来没问题”
解释为批准。

## 11. 证据记录模板

### 11.1 5 分钟观察表

```csv
bucket_start,bucket_end,calls,succeeded,error_rate,p50_ms,p95_ms,p99_ms,inflight_max,denied,drift,audit_rows,egress_blocked,notes
```

### 11.2 时间线

```text
timestamp,tz,actor,action,result,evidence_path
```

### 11.3 决策记录

```text
decision=PASS|FAIL|INCONCLUSIVE
reason=<阈值/事件>
scope=<project/connection/tool>
sample=<calls/runs>
window=<start-end>
rollback_required=true|false
rollback_completed_at=<timestamp>
approver=<name>
commander=<name>
operator=<name>
```

## 12. 最终判定

| 判定 | 条件 | 后续 |
| --- | --- | --- |
| `CANARY_PASS` | 300+ 可分类调用、30+ 分钟、无立即停止事件、所有质量阈值满足、审计完整、回滚验证通过 | 只能提交“下一阶段评审”，不得自动扩项目/Provider |
| `CANARY_FAIL_ROLLED_BACK` | 任一立即停止事件或质量阈值突破，已回滚 | 保留证据，修复后重新申请批准 |
| `INCONCLUSIVE` | 样本不足、调用方不是真实用户路径、告警/证书未验证 | 不得写 PASS；补样本或补前置 |
| `PROTOCOL_ONLY` | 只有探针客户端，没有真实用户路径 | 只能证明协议，不可宣称用户侧 readiness |

**本文件当前状态：`PLAN_ONLY / NOT_EXECUTED`。**

## 13. 未执行与未验证

- 生产 canary：**NOT_STARTED**；
- `AGENT_MCP_GATEWAY_ENABLED=true`：**未执行**；
- 生产流量切换：**未执行**；
- 生产数据写入/删除：**未执行**；
- 生产 key/cert 注入：由 Lane B 记录；
- 生产 Alertmanager 送达：由 Lane C 记录；
- 生产调用方/token 获取：由 Lane A 记录。
