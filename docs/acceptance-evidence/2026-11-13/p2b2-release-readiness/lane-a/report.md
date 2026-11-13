# P2-B2 Lane A：W6-B 功能/安全补强

> 日期：2026-11-13（Asia/Shanghai）
> 代码基线：`dev @ ba23dd18`
> 证据等级：12 项真实进程 case 全绿；默认关闭与审计失败分类按现有
> `CONFIG` / `JAVA_UNIT` 证据保留。
> 结论：**Lane A PASS**；生产 canary 未启动。

## 1. 完成表

| 编号 | 能力 | 结果 | 证据等级 | 关键证据 |
| --- | --- | --- | --- | --- |
| A-01 | 外部只读 Provider 准入 | **PASS** | `REAL_PROCESS` + `POSTGRES` | 草稿/撤销 Provider 不进入目录；`V99` 准入表；管理 API 与 approve/enable 流程 |
| A-02 | 多 Server 边界 | **PASS** | `REAL_PROCESS` | 1 个 `EXTERNAL_READ_ONLY` + 1 个 `LOCAL_FIXTURE`，3 个工具同时暴露且命名空间不冲突 |
| A-03 | 租户/项目隔离 | **PASS** | `REAL_PROCESS` | 项目 B 工具不出现在项目 A 目录；直调返回 `TOOL_DISABLED` |
| A-04 | 审计/撤权/在途结果 | **PASS** | `REAL_PROCESS` | 在途 `TOOL_REVOKED`；新调用 `SCHEMA_DRIFT`；13 行审计且无参数/结果泄漏 |
| A-05 | egress/TLS/allowlist | **PASS** | 引用 P2-B1 `REAL_PROCESS` | M-01/M-02 原始 SHA-256 未变；本轮真实调用使用 TLS 证书校验与 pinned DNS |
| A-06 | Schema/Hash 漂移 | **PASS** | `REAL_PROCESS` + P2-A `REAL_PROCESS` | Hash 不一致后工具立即消失，调用返回 `SCHEMA_DRIFT`；P2-A discover 漂移停用保留 |
| A-07 | default-off 不变 | **PASS** | `CONFIG` + P2-B1 `REAL_PROCESS` | `McpGatewayConfigurationTest` 3/3；`agent.mcp.gateway.enabled=false` 未改；旧 Provider 回归通过 |
| A-08 | 证据等级诚实 | **PASS** | — | 本轮每项均标明 `REAL_PROCESS` / `POSTGRES` / `CONFIG` / `JAVA_UNIT`，未把单测写成真实进程 |

## 2. 真实进程

运行时间：`2026-11-13 22:09:26` 起（Asia/Shanghai）。
工作目录：`target/p2b2-e2e/lane-a-20261113-220925/`。

| 组件 | PID | 端口 | 启动 → 停止 | 结果 |
| --- | ---: | ---: | --- | --- |
| 外部只读 TLS MCP 进程 | `11344` | `49579` | `22:09:26` → `22:09:49` | PASS（强制回收 exit 1） |
| 本地 fixture TLS MCP 进程 | `36004` | `49580` | `22:09:27` → `22:09:49` | PASS（强制回收 exit 1） |
| Java Spring Boot Gateway | `58864` | gateway `49576` / management `49577` | `22:09:27` → `22:09:48` | PASS（强制回收 exit 1） |
| Python Uvicorn Host | `2616` | `49578` | `22:09:42` → `22:09:47` | PASS（强制回收 exit 1） |

调用链：

```text
Python Host
  -> Java Gateway（Ed25519 claims: projectRef/runRef/catalogVersion/mcp:list+mcp:call）
  -> 外部只读 TLS Provider / 本地 fixture TLS Provider
```

退出码 1 来自 harness 的 `taskkill /F` 回收，不是应用崩溃；数据库在结束后删除，
未使用第三方 Provider 凭据。

## 3. 12 项 case

| case | 结果 | 断言 |
| --- | --- | --- |
| `A-02.java-list` | PASS | Java `tools/list` 返回 3 个工具 |
| `A-02.host-list` | PASS | Host 与 Java 目录逐项一致 |
| `A-01/A-02/A-03.catalog-admission-tenant` | PASS | 只暴露已批准的 2 个连接；草稿/撤销/跨租户隐藏 |
| `A-02.external-call` | PASS | 外部只读工具真实调用成功，`untrusted=true`，敏感字段脱敏 |
| `A-02.local-call` | PASS | 本地 fixture 工具真实调用成功 |
| `A-01.draft-provider-blocked` | PASS | `TOOL_DISABLED` |
| `A-01.revoked-provider-blocked` | PASS | `TOOL_DISABLED` |
| `A-03.cross-tenant-blocked` | PASS | `TOOL_DISABLED` |
| `A-06.hash-fence` | PASS | 连接 Hash 不一致后调用 `SCHEMA_DRIFT` |
| `A-04.revocation-inflight` | PASS | 在途 `TOOL_REVOKED`；新调用 `SCHEMA_DRIFT` |
| `A-04.audit-metadata-only` | PASS | 13 行 Gateway 审计；detail 不含 `external-hello`、`local-hello`、`sk-synthetic`、`apiKey` |
| `A-01.admission-state` | PASS | 结束后 `APPROVED=2`、`DRAFT=1`、`REVOKED=2` |

目录 Hash：

```text
catalogVersion = sha256:97427dc3069b0f05953c3de5106c8dae0125910e633c7278ac178bce9332018a
```

原始证据 SHA-256：

```text
5F0C516E43119D5F09EC7A50B1A6C014486ECB603E35CE86BD150D30200EC880
```

## 4. Provider 准入实现

- 新增 `V99__agent_mcp_provider_admission.sql`，不修改历史迁移。
- `provider_type` 由服务端根据 endpoint 推导，客户端不能声明；本地 fixture 仅能用于
  已通过测试回环策略的 endpoint。
- `DRAFT -> APPROVED -> REVOKED` 带乐观锁；编辑连接自动撤销旧准入。
- Gateway 与旧 `McpAgentToolProvider` 都要求 `APPROVED` 且元数据完整，关闭 Gateway
  不能绕过准入。
- `setEnabled(true)` 在 Provider 未批准时拒绝；撤销准入同时停用连接与清缓存。
- 管理 API 与状态机说明见
  [`docs/design/mcp-provider-admission-2026-11-13.md`](../../../../design/mcp-provider-admission-2026-11-13.md)。

## 5. 测试与回归

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java Gateway/Provider 定向 | **PASS** | `McpGatewayCatalogContractTest` 等 7 类：43/43 |
| Java V99/Postgres 集成 | **PASS** | `McpAdministrationPostgresIntegrationTest` 3/3；Flyway v99；批准前 enable 被拒、撤销后连接停用 |
| P2-A 真实进程回归 | **PASS** | 三进程 M-01～M-12 全部 PASS，输出在 `target/p2b2-e2e/p2a-regression/`，未覆盖历史证据 |
| Python Ruff / format | **PASS** | 新增 harness、P2-A seed/admission 适配均通过 |
| 前端 | 未改 | 本轮无前端改动 |

## 6. 边界与 Deferred

- 外部 Provider 是**独立合成 TLS MCP 进程**并带 `EXTERNAL_READ_ONLY` 准入记录，
  不是第三方生产 Provider；未使用第三方凭据。
- 本轮使用测试专用 Ed25519 夹具密钥，仅用于本地真实进程；没有生产私钥、
  生产 ingress 或生产 Provider 数据。
- `agent.mcp.gateway.enabled=false` 仍是默认值；生产 W6 readiness 仍为 `NOT_READY`。
- 生产 canary、生产监控告警、生产 TLS/ingress 留给 Lane B；未获用户批准前不启动。

## 7. 判定

```text
Lane A：PASS
A 的 W6-B 安全矩阵：PASS（12/12 case）
生产 canary：NOT_STARTED
Lane B：可开始
```
