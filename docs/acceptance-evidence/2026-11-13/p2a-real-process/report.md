# P2-A 真实进程 E2E 与安全矩阵验收报告

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 5d77d31c`；P2-A 集成提交见仓库最终报告
> 证据等级：真实三进程 `TESTED(REAL_PROCESS)`；M-02/M-12 为 Java 单测/配置测试
> 结论：**P2-A PASS（真实进程 E2E + M-01～M-12 最小安全矩阵）**；
> **生产 W6 仍为 `NOT_READY`**，不可据此宣称可 canary、灰度或上线。

## 1. 结论与边界

本轮完成：

- 真实 Spring Boot Java Gateway 进程；
- 真实 FastAPI/Uvicorn Python Host 进程；
- 本地 HTTPS/TLS 只读 MCP Server 进程；
- 21 个真实进程断言 case 全部 PASS；
- M-01～M-12 全部 PASS（M-02/M-12 为 Java 单测/配置证据，不冒充真实进程）；
- default-off 与旧 Provider 配置回退测试；
- Java/Python 定向与全量门禁、前端门禁、CI 与清理。

不在本轮范围：

- W6-B 多 MCP Server、多租户；
- 生产 canary、TLS/ingress、生产凭据、外部真实 MCP；
- N5 Reranker UI、JMeter per-label 重跑；
- Java Testcontainers 本机抖动的根因治理；
- 仓库历史重写。

## 2. 真实进程 E2E 表

> 运行时间：2026-11-13 17:25（Asia/Shanghai）；证据生成时间：`17:25:27`。
> 三个进程由 harness 的 `taskkill /T /F` 强制回收，退出码 1 是预期终止，不是应用自行崩溃。

| 组件 | PID | 端口 | 启动/停止 | 结果 |
| --- | --- | --- | --- | --- |
| Java Spring Boot Gateway | `20520` | gateway `54486` / management `54487` | `17:25:08` → `17:25:26` | PASS |
| Python Uvicorn Host | `2376` | host `54488` | `17:25:20` → `17:25:25` | PASS |
| 本地 TLS MCP Server | `56560` | mcp `54489` | `17:25:08` → `17:25:26` | PASS |

调用链（冻结契约方向）：

```text
Python Host 进程（Uvicorn :54488）
  -> Java Gateway 进程（Spring Boot :54486，唯一外部 MCP 出网点）
  -> 本地 TLS MCP Server 进程（:54489）
```

关键结果：

- Java/Host `tools/list` 工具数 11，逐项（name/description/schema/hash/annotations）相等；
- `catalogVersion = sha256:dc67c4180eb2fb3c8776400546df7a4afde11a03e87547500faa1ecd815394c9`；
- 冻结 echo fixture 的 `toolSchemaHash = sha256:6e0e51dc1473a487caf37f66d09b14a1027a037c9fb357b032d1c7513dc82054`；
- 真实只读调用 `echo_text` 经 Host → Gateway → TLS MCP Server 返回；
- 成功结果 `untrusted=true`、`truncated=false`，凭据字段 `[REDACTED]`，注入告警 `warnings` 非空；
- 结果清洗与 warning 由 Java 权威出口执行，Host 原样保留。

原始证据：`docs/acceptance-evidence/2026-11-13/p2a-real-process/lane-c/raw/real-process.json`。

## 3. 安全矩阵

| 编号 | 场景 | 状态 | 证据 |
| --- | --- | --- | --- |
| M-01 | 私网/元数据/allowlist 外拒绝 | PASS（REAL_PROCESS） | `M-01.metadata-rejected`、`M-01.allowlist-outside-rejected` → `NOT_EXECUTED` |
| M-02 | DNS 重绑定/校验后解析变化 | PASS（JAVA_UNIT） | `McpEndpointPolicyTest`，pinned DNS 复用校验地址 |
| M-03 | 描述含 injection | PASS（REAL_PROCESS） | `injected_description` 暴露描述已替换为 `[REMOVED_INSTRUCTION]` |
| M-04 | 结果含假 system/tool 指令 | PASS（REAL_PROCESS） | `warnings` 非空、`untrusted=true`、敏感字段脱敏 |
| M-05 | Schema 变化停用并审计 | PASS（REAL_PROCESS） | 管理员 discover 后 `enabled=false`、`schemaConfirmed=false`、审计行数 1 |
| M-06 | 未白名单/未绑定工具拒绝 | PASS（REAL_PROCESS） | `write_probe` 返回 `TOOL_DISABLED` |
| M-07 | Python/请求体伪造 project/user | PASS（REAL_PROCESS） | Gateway 直调伪造字段返回 `PARAM_INVALID`；Host 422 零 Gateway 调用 |
| M-08 | 撤权后新调用/在途结果 | PASS（REAL_PROCESS） | 在途 `OUTCOME_UNKNOWN`；撤权后新调用 `SCHEMA_DRIFT` |
| M-09 | 429/timeout/5xx/partial | PASS（REAL_PROCESS） | `RATE_LIMITED` / `OUTCOME_UNKNOWN` / `OUTCOME_UNKNOWN` / `PARTIAL` / `OUTCOME_UNKNOWN` |
| M-10 | 超大/二进制/路径结果 | PASS（REAL_PROCESS） | 超大结果 `TRUNCATED`；二进制/路径为 `[UNSUPPORTED_BINARY_OR_PATH]` |
| M-11 | 双端 tools/list/hash | PASS（REAL_PROCESS） | Java/Host 工具逐项相等，`catalogVersion` 与 echo fixture hash 一致 |
| M-12 | Gateway 关闭回退旧 Provider | PASS（CONFIG） | `McpGatewayConfigurationTest`、`McpGatewayStaticBindingTest` 全绿 |

说明：

- M-09 的 5xx/timeout 归入 `OUTCOME_UNKNOWN` 是保守语义：远端可能已执行，不能伪造成功；
- 429 单独为 `RATE_LIMITED`，HTTP 206 单独为 `PARTIAL`，`isError=true` 为 `OUTCOME_UNKNOWN`；
- M-02/M-12 的原始 Java 测试输出：`docs/acceptance-evidence/2026-11-13/p2a-real-process/center/raw/java-m02-m12.txt`。

## 4. 测试与 CI

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java MCP 定向 | PASS | 27/27；另有 MCP 回归 62/62、warning 定向 21/21、Postgres 3/3 |
| Java 全量 | 以最终复跑/CI 为准 | 本机曾出现 1 个存量时间断言 failure + 3 个无关 Postgres `NoSuchElement` errors；不归因于 P2-A |
| Python 全量 | PASS | **1273 passed** |
| Python Ruff / format / mypy | PASS | `ruff check`、`ruff format --check`、`mypy --strict src` 全绿 |
| 前端 | PASS | lint/typecheck/test/build；63 files / 491 tests |
| CI | 以最终 `dev` push 的 GitHub Actions 结果为准 | 推送后回填 run URL |

## 5. 证据与清理

| 项 | 路径/状态 |
| --- | --- |
| 真实进程原始 JSON | `lane-c/raw/real-process.json` |
| 离线 TLS 夹具 | `lane-c/raw/offline-tls.json` |
| M-02/M-12 Java 原始输出 | `center/raw/java-m02-m12.txt` |
| 进程日志 | `target/p2a-e2e/real-*/logs/`（忽略，不提交） |
| TLS 证书/私钥/truststore | `target/p2a-e2e/real-*/tls/`（忽略，不提交） |
| 测试数据库 | 运行后删除，`databaseDropped=true` |
| 遗留进程 | 已回收；端口已释放 |

## 6. 限制与生产判定

- 真实 E2E 使用明确的 **local/test 静态测试绑定**；生产 Ed25519 claim 路径没有在本轮真实进程中执行。
  M-07 证明了请求体伪造字段被拒，但不能替代生产身份链路的 canary。
- MCP Server 是本地合成 TLS 夹具，不是外部 Provider，没有第三方凭据。
- M-02 是 pinned-DNS 单测，M-12 是配置/Bean 回退测试，二者证据等级低于真实进程。
- 生产 W6 仍需 Ed25519 真实身份、生产 egress/TLS、外部 Provider 准入、监控告警和人工 canary 批准。

**生产 readiness：`NOT_READY`。不得把本报告写成生产 canary、灰度或上线证明。**
