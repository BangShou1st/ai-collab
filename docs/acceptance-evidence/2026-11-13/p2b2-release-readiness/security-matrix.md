# P2-B2 W6-B 安全矩阵

> 代码基线：`dev @ ba23dd18`
> 状态：`PASS`（Lane A）
> 原始真实进程证据：`lane-a/raw/real-process.json`

## 1. 延续项

| 编号 | 场景 | 状态 | 证据等级 | 依据 |
| --- | --- | --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外拒绝 | PASS | `REAL_PROCESS` | P2-B1 raw SHA-256 未变；本轮 TLS 仍受 `McpEndpointPolicy` 约束 |
| M-02 | DNS 重绑定 | PASS | `REAL_PROCESS` | P2-B1 pinned-DNS 证据保留 |
| M-03 | 描述注入 | PASS | `REAL_PROCESS` | P2-A M-03 + P2-B2 目录仍返回清洗后的 `[REMOVED_INSTRUCTION]` |
| M-04 | 结果注入/敏感字段 | PASS | `REAL_PROCESS` | `A-02.external-call` / `A-02.local-call`：`untrusted=true`、凭据 `[REDACTED]`、warning 保留 |
| M-05 | Schema 漂移停用 | PASS | `REAL_PROCESS` | P2-A M-05；本轮 P2-A 回归通过 |
| M-06 | 未白名单/未绑定工具 | PASS | `REAL_PROCESS` | 草稿/撤销/跨租户工具均不可见，直调 `TOOL_DISABLED` |
| M-07 | 伪造 project/user | PASS | `REAL_PROCESS` | P2-A M-07；Gateway 请求体身份字段拒绝逻辑未变 |
| M-08 | 撤权后新调用/在途 | PASS | `REAL_PROCESS` | `A-04.revocation-inflight`：在途 `TOOL_REVOKED`、新调用 `SCHEMA_DRIFT` |
| M-09 | 429/timeout/5xx/partial | PASS | `REAL_PROCESS` | P2-A 回归通过 |
| M-10 | 超大/二进制/路径结果 | PASS | `REAL_PROCESS` | P2-A 回归通过 |
| M-11 | 双端 tools/list/Hash | PASS | `REAL_PROCESS` | `A-02.java-list` / `A-02.host-list` 逐项一致 |
| M-12 | Gateway 关闭回退 | PASS | `CONFIG` + P2-B1 `REAL_PROCESS` | `McpGatewayConfigurationTest` 3/3；默认值未变 |

## 2. W6-B 增量

| 编号 | 场景 | 状态 | 证据等级 | 依据 |
| --- | --- | --- | --- | --- |
| M-13 | 外部 Provider 未审核/非只读/元数据缺失拒绝 | PASS | `REAL_PROCESS` + `POSTGRES` | `A-01.draft-provider-blocked`；V99 CHECK 与 approve/enable 集成测试 |
| M-14 | 多 Server 命名空间/连接归属 | PASS | `REAL_PROCESS` | 外部 + 本地 fixture 同时暴露，3 个工具无冲突 |
| M-15 | 跨项目/租户隔离 | PASS | `REAL_PROCESS` | 项目 B 工具不出现在 A 目录；直调 `TOOL_DISABLED` |
| M-16 | Provider Schema/Hash 漂移 | PASS | `REAL_PROCESS` | `A-06.hash-fence`：`SCHEMA_DRIFT`；P2-A discover 漂移保留 |
| M-17 | 撤权/停用后的在途结果 | PASS | `REAL_PROCESS` | `A-04.revocation-inflight` |
| M-18 | 审计失败 fail-closed | PASS | `JAVA_UNIT` | `McpGatewayServiceTest` 3 个审计失败分支；真实进程未注入数据库故障 |

## 3. 不扩大解释

- 外部只读 Provider 是合成 TLS 进程，不是第三方生产服务。
- M-18 的真实进程证据未执行；按 `JAVA_UNIT` 标注，不作为生产 canary 证明。
- 生产 Ed25519 私钥、生产 TLS/ingress、监控告警与 canary 属于 Lane B，本轮没有启动。
- `agent.mcp.gateway.enabled=false` 未改；生产 W6 仍 `NOT_READY`。
