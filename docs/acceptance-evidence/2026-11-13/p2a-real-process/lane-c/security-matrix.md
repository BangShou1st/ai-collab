# P2-A 安全矩阵（证据口径修正）

> 结论：**P2-A CONDITIONAL PASS**。
> M-03～M-11 为真实三进程证据；M-01 为 preflight/static 拒绝；M-02 为 pinned-DNS
> Java 单测；M-12 为 default-off/旧 Provider 配置测试。
> **12/12 PASS 不等于 12/12 REAL_PROCESS**；M-02/M-12 的 REAL_PROCESS 状态保持 NOT_RUN。

| 编号 | 场景 | 状态 | 证据 |
| --- | --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外拒绝 | **PASS（PREFLIGHT/STATIC）** | `raw/real-process.json`：`PASS/NOT_EXECUTED`，Java 在外部 socket 前拒绝；REAL_PROCESS external-negative `NOT_RUN`；`raw/real-process.corrected.json` |
| M-02 | DNS 校验后解析变化 | **PASS（JAVA_UNIT）** | `../center/raw/java-m02-m12.txt`：`McpEndpointPolicyTest` 5/5；`REAL_PROCESS NOT_RUN` |
| M-03 | 描述含 injection | PASS（REAL_PROCESS） | `raw/real-process.json`：`M-03.description-injection`，描述已被 `[REMOVED_INSTRUCTION]` 清洗 |
| M-04 | 结果含假 system/tool 指令 | PASS（REAL_PROCESS） | `raw/real-process.json`：`M-04.result-sanitization*`，`warnings` 非空、凭据脱敏 |
| M-05 | Schema 变化停用并审计 | PASS（REAL_PROCESS） | `raw/real-process.json`：`M-05.schema-drift-disable`，`enabled=false`、`auditRows=1` |
| M-06 | 未白名单/未绑定工具拒绝 | PASS（REAL_PROCESS） | `raw/real-process.json`：`write_probe` 返回 `TOOL_DISABLED` |
| M-07 | 请求体伪造 project/user | PASS（REAL_PROCESS） | `raw/real-process.json`：`PARAM_INVALID` |
| M-08 | 撤权后新调用/在途结果 | PASS（REAL_PROCESS） | `raw/real-process.json`：在途 `OUTCOME_UNKNOWN`，新调用 `SCHEMA_DRIFT` |
| M-09 | 429/timeout/5xx/partial | PASS（REAL_PROCESS） | `raw/real-process.json`：`RATE_LIMITED` / `OUTCOME_UNKNOWN` / `PARTIAL` / `OUTCOME_UNKNOWN` / `OUTCOME_UNKNOWN` |
| M-10 | 超大/二进制/路径 | PASS（REAL_PROCESS） | `raw/real-process.json`：`TRUNCATED`/清洗占位符，二进制与路径不原样返回 |
| M-11 | 双端 tools/list/hash | PASS（REAL_PROCESS） | `raw/real-process.json`：Java/Host `catalogVersion` 与工具逐项相等，echo fixture hash 一致 |
| M-12 | Gateway 关闭回退旧 Provider | **PASS（CONFIG）** | `../center/raw/java-m02-m12.txt`：`McpGatewayConfigurationTest` 3/3、`McpGatewayStaticBindingTest` 3/3；`REAL_PROCESS NOT_RUN` |

## 证据等级说明

| 等级 | 覆盖 | 数量 |
| --- | --- | ---: |
| `REAL_PROCESS` | M-03～M-11，真实 Java/Python/MCP 三进程 HTTP + TLS 调用 | 9 |
| `PREFLIGHT/STATIC` | M-01，连接前策略拒绝；不是 external-negative 真实调用 | 1 |
| `JAVA_UNIT` | M-02 pinned-DNS 单测 | 1 |
| `CONFIG` | M-12 default-off/旧 Provider 配置测试 | 1 |
| `REAL_PROCESS NOT_RUN` | M-02、M-12；M-01 仅缺 external-negative 真实调用 | 2+1 |

原始 `real-process.json` 未修改；M-01 的 `PASS + NOT_EXECUTED` 只按
`PREFLIGHT/STATIC` 解释，详见 `raw/real-process.corrected.json`。
