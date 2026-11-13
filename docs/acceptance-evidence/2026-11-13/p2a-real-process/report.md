# P2-A 真实进程 E2E 与安全矩阵验收报告（证据口径修正）

> **后续状态（2026-11-13，P2-B1）**：M-01 / M-02 / M-12 已在真实进程 harness 中补齐，
> 最新证据见 `docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/report.md`。
> 本报告保留 P2-A 当时的分层证据口径；P2-A 原始 raw 未改写。
> 生产 W6 仍为 `NOT_READY`。

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 5d77d31c`；P2-A 集成提交见仓库最终报告
> 证据等级：9 项 `REAL_PROCESS`、1 项 `PREFLIGHT/STATIC`、1 项 `JAVA_UNIT`、1 项 `CONFIG`
> 结论：**P2-A CONDITIONAL PASS（真实进程 E2E + 安全最小矩阵，证据等级分层）**；
> **生产 W6 仍为 `NOT_READY`**，不可据此宣称可 canary、灰度或上线。

## 1. 结论与边界

本轮完成：

- 真实 Spring Boot Java Gateway 进程；
- 真实 FastAPI/Uvicorn Python Host 进程；
- 本地 HTTPS/TLS 只读 MCP Server 进程；
- 21 个已执行 case 全部 PASS，但其中 M-01 的 2 个 case 是 **preflight/static 拒绝**，
  不是外部连接真实负向调用；
- M-01～M-12 的状态均为 PASS，但证据等级不同：M-03～M-11 为真实进程，
  M-01 为 `PREFLIGHT/STATIC`，M-02 为 `JAVA_UNIT`，M-12 为 `CONFIG`；
- M-02/M-12 的 `REAL_PROCESS` 状态为 **NOT_RUN**，不得写成“12/12 全真实进程”；
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
| M-01 | 私网/元数据/allowlist 外拒绝 | **PASS（PREFLIGHT/STATIC）** | `M-01.*` 原始为 PASS/`NOT_EXECUTED`；Java 在外部 socket 前拒绝，**REAL_PROCESS external-negative NOT_RUN** |
| M-02 | DNS 重绑定/校验后解析变化 | **PASS（JAVA_UNIT）** | `McpEndpointPolicyTest` 5/5；`REAL_PROCESS NOT_RUN` |
| M-03 | 描述含 injection | PASS（REAL_PROCESS） | `injected_description` 暴露描述已替换为 `[REMOVED_INSTRUCTION]` |
| M-04 | 结果含假 system/tool 指令 | PASS（REAL_PROCESS） | `warnings` 非空、`untrusted=true`、敏感字段脱敏 |
| M-05 | Schema 变化停用并审计 | PASS（REAL_PROCESS） | 管理员 discover 后 `enabled=false`、`schemaConfirmed=false`、审计行数 1 |
| M-06 | 未白名单/未绑定工具拒绝 | PASS（REAL_PROCESS） | `write_probe` 返回 `TOOL_DISABLED` |
| M-07 | Python/请求体伪造 project/user | PASS（REAL_PROCESS） | Gateway 直调伪造字段返回 `PARAM_INVALID`；Host 422 零 Gateway 调用 |
| M-08 | 撤权后新调用/在途结果 | PASS（REAL_PROCESS） | 在途 `OUTCOME_UNKNOWN`；撤权后新调用 `SCHEMA_DRIFT` |
| M-09 | 429/timeout/5xx/partial | PASS（REAL_PROCESS） | `RATE_LIMITED` / `OUTCOME_UNKNOWN` / `OUTCOME_UNKNOWN` / `PARTIAL` / `OUTCOME_UNKNOWN` |
| M-10 | 超大/二进制/路径结果 | PASS（REAL_PROCESS） | 超大结果 `TRUNCATED`；二进制/路径为 `[UNSUPPORTED_BINARY_OR_PATH]` |
| M-11 | 双端 tools/list/hash | PASS（REAL_PROCESS） | Java/Host 工具逐项相等，`catalogVersion` 与 echo fixture hash 一致 |
| M-12 | Gateway 关闭回退旧 Provider | **PASS（CONFIG）** | `McpGatewayConfigurationTest`、`McpGatewayStaticBindingTest` 全绿；`REAL_PROCESS NOT_RUN` |

### 3.1 证据口径修正

`real-process.json` 保留原样，不原地修改：

| 项 | 值 |
| --- | --- |
| 原 raw | `lane-c/raw/real-process.json`（原始数据保留；报告口径标记 `CORRECTED_FOR_REPORTING` / `SUPERSEDED_FOR_CLAIM_ONLY`） |
| 原 raw SHA-256 | `E37D3A7B3FCD4A954D0A7EC7B441C75E2CF71F6438FE6DCADD6D499F61DB28AC` |
| Corrected sidecar | `lane-c/raw/real-process.corrected.json` |
| 口径 | 旧 raw 的 M-01 行只证明 preflight 拒绝，不得再按 `REAL_PROCESS` 解读 |

证据等级计数：

| 证据等级 | 覆盖项 | 数量 |
| --- | --- | ---: |
| `REAL_PROCESS` | M-03～M-11 | 9 |
| `PREFLIGHT/STATIC` | M-01 | 1 |
| `JAVA_UNIT` | M-02 | 1 |
| `CONFIG` | M-12 | 1 |
| `REAL_PROCESS NOT_RUN` | M-02、M-12（以及 M-01 的 external-negative） | 2+1 |

**12/12 PASS 不等于 12/12 REAL_PROCESS。** 本轮结论按证据等级写为 `CONDITIONAL PASS`。

说明：

- M-09 的 5xx/timeout 归入 `OUTCOME_UNKNOWN` 是保守语义：远端可能已执行，不能伪造成功；
- 429 单独为 `RATE_LIMITED`，HTTP 206 单独为 `PARTIAL`，`isError=true` 为 `OUTCOME_UNKNOWN`；
- M-02/M-12 的原始 Java 测试输出：`docs/acceptance-evidence/2026-11-13/p2a-real-process/center/raw/java-m02-m12.txt`。

## 4. 测试与 CI

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java M-01 定向 | PASS | `McpEndpointPolicyTest` + `McpGatewayServiceTest` **23/23 PASS**；原始输出 `center/raw/java-m01-targeted.txt` |
| Java 全量本机 | 存量抖动 | 3012 tests：1 个已知时间断言 failure + 1 个无关 Postgres `NoSuchElement`；失败类单跑 44/44 PASS；不归因于 P2-A |
| Python 全量 | PASS | **1273 passed** |
| Python Ruff / format / mypy | PASS | `ruff check`、`ruff format --check`、`mypy --strict src` 全绿 |
| 前端 | PASS | lint/typecheck/test/build；63 files / 491 tests |
| CI（功能代码） | PASS | `dev @ 3a625f46`，[run 43405067212](https://github.com/BangShou1st/ai-collab/actions/runs/43405067212) success；docs-only 口径修正提交另行触发 CI |

## 5. 证据与清理

| 项 | 路径/状态 |
| --- | --- |
| 真实进程原始 JSON | `lane-c/raw/real-process.json`（保留原样；M-01 行仍为 PASS/NOT_EXECUTED） |
| 口径修正 sidecar | `lane-c/raw/real-process.corrected.json`（`CORRECTED_FOR_REPORTING`，不覆盖 raw） |
| M-01 定向测试 | `center/raw/java-m01-targeted.txt`（23/23 PASS） |
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
- M-01 是 Java Gateway 外部 socket 创建前的 preflight/static 拒绝；本轮没有真实 external-negative
  运行时证据，不能写成 `REAL_PROCESS`。
- M-02 是 pinned-DNS 单测，M-12 是配置/Bean 回退测试，二者证据等级低于真实进程。
- 生产 W6 仍需 Ed25519 真实身份、生产 egress/TLS、外部 Provider 准入、监控告警和人工 canary 批准。

**生产 readiness：`NOT_READY`。不得把本报告写成生产 canary、灰度或上线证明。**
