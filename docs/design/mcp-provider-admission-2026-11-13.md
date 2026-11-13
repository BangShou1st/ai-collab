# MCP Provider 只读准入（P2-B2 Lane A）

> 日期：2026-11-13
> 状态：已实现，default-off 不变
> 迁移：`V99__agent_mcp_provider_admission.sql`
> 证据：`docs/acceptance-evidence/2026-11-13/p2b2-release-readiness/lane-a/`

## 1. 目标

把“连接已启用且 Schema 已确认”提升为“Provider 已经人工完成只读准入”。连接存在、
远端声明 `readOnlyHint=true` 或管理员执行过 `test/discover` 都不再足以让工具进入 Agent
上下文；必须存在一条完整的 Provider 审核记录。

本轮不改变 `agent.mcp.gateway.enabled=false`，也不把 W6 标为生产可灰度。Gateway
关闭时旧 `McpAgentToolProvider` 仍可用，但同样受 Provider 准入约束。

## 2. 数据模型

新表 `agent_mcp_provider_admission` 与 `agent_mcp_connection` 一对一：

| 字段 | 含义 |
| --- | --- |
| `connection_id` | 主键，外键指向连接；连接删除时级联删除 |
| `provider_type` | `EXTERNAL_READ_ONLY` 或 `LOCAL_FIXTURE`，由服务端根据已通过出网策略的 endpoint 推导 |
| `provider_owner` | Provider 责任团队/人 |
| `data_scope` | 允许读取的数据范围 |
| `retention_policy` | 远端数据保留策略 |
| `terms_url` | Provider ToS/审核材料；本地夹具使用 `urn:ai-collab:local-fixture` |
| `least_privilege_scope` | 最小只读凭据 scope |
| `read_only_endpoint` | 人工确认的只读端点声明 |
| `status` | `DRAFT` / `APPROVED` / `REVOKED` |
| `reviewed_by/at` | 批准人与时间 |
| `revoked_by/at` | 撤销人与时间 |
| `version` | 乐观锁；每次草稿/批准/撤销递增 |

数据库 CHECK 约束保证 `APPROVED` 行必须包含完整字段、`read_only_endpoint=true` 和审核人。
客户端不能提交 `provider_type`；它由服务端根据 endpoint host 推导，避免调用方把外部
Provider 冒充成本地夹具。

## 3. 生命周期

```text
连接创建
  -> (无准入行 / DRAFT)
  -> PUT admission（填写元数据，状态回到 DRAFT）
  -> discover（至少一个白名单只读工具）
  -> POST approve（人工确认）
  -> enable（确认 Schema Hash）
  -> EXPOSED

任何连接编辑、Provider 撤销、Schema 漂移或人工停用
  -> 不再暴露；重新审核后才能再次启用
```

规则：

1. `saveAdmission` 每次都会把状态重置为 `DRAFT`，并要求重新批准。
2. `approveAdmission` 要求元数据完整、只读端点、已发现至少一个白名单只读工具，
   且请求版本匹配。
3. `setEnabled(true)` 只接受 `APPROVED` 且完整有效的准入记录。
4. 撤销准入会同时停用连接、清空连接缓存；新调用返回 `TOOL_DISABLED` /
   `SCHEMA_DRIFT` / `TOOL_REVOKED`，在途结果返回 `TOOL_REVOKED` 或
   `OUTCOME_UNKNOWN`。
5. 连接编辑会撤销既有准入，避免改 endpoint/凭据后沿用旧审核。

## 4. 读路径强制

两条暴露路径都检查同一记录：

- Java Gateway `McpGatewayCatalogService`：目录只包含连接启用、Schema 确认、
  `McpProviderAdmission.active()` 为 true 的工具。
- 旧 `McpAgentToolProvider`：`tools()` 隐藏未获准连接；执行前再次检查，防止暴露后撤权。

`active()` 同时要求状态为 `APPROVED`、元数据完整、`reviewedBy/At` 存在、
`revokedAt` 为空。撤权后即使连接行仍 `enabled=true`，也不会进入目录；Gateway
在调用前后重建目录，形成版本 fence。

## 5. 管理 API

均要求项目 `ADMIN`，且连接必须属于 URL 中的项目：

```text
GET  /api/v1/projects/{projectId}/agent/mcp-connections/{id}/admission
PUT  /api/v1/projects/{projectId}/agent/mcp-connections/{id}/admission
POST /api/v1/projects/{projectId}/agent/mcp-connections/{id}/admission/approve?version=N
POST /api/v1/projects/{projectId}/agent/mcp-connections/{id}/admission/revoke?version=N
```

审计动作：

- `AGENT_MCP_PROVIDER_ADMISSION_DRAFTED`
- `AGENT_MCP_PROVIDER_ADMISSION_APPROVED`
- `AGENT_MCP_PROVIDER_ADMISSION_REVOKED`

审计不包含凭据、完整 Provider 响应或工具参数/结果。

## 6. 验收证据

真实进程证据使用：

```text
Python Host -> Java Gateway (Ed25519 claims) -> 两个独立 TLS MCP Server
```

数据库同时包含：

- 一个已批准 `EXTERNAL_READ_ONLY` Provider；
- 一个已批准 `LOCAL_FIXTURE` Provider；
- 一个 `DRAFT` Provider；
- 一个 `REVOKED` Provider；
- 一个属于项目 B 的 Provider。

验收断言：

- Java/Host 目录一致，只出现前两个连接的工具，共 3 个；
- 外部与本地工具均真实调用成功；
- 草稿/撤销/跨租户工具不可见，直调均被拒；
- Hash 不一致时工具立即消失；
- 在途调用撤权后为 `TOOL_REVOKED`，新调用被拒；
- Gateway 审计有 13 行记录，detail 不含参数或结果标记。

原始进程、端口、PID、case 与 catalog 见：
`docs/acceptance-evidence/2026-11-13/p2b2-release-readiness/lane-a/raw/real-process.json`。

## 7. 边界

- “外部只读 Provider”在 Lane A 真实进程中是**独立合成 TLS MCP 进程 + 
  `EXTERNAL_READ_ONLY` 准入记录**，不是第三方生产 Provider；未使用第三方凭据。
- 本轮仍未执行生产 Ed25519 部署、生产 TLS/ingress、监控告警或 canary。
- `agent.mcp.gateway.enabled=false` 仍是默认值；生产 W6 readiness 仍为 `NOT_READY`。
