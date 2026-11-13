# P1 W6 default-off 垂直切片报告（2026-11-13）

> 基线：`dev @ e160f6d2` → 冻结提交 `540a029d`
> 证据等级：`IMPLEMENTED < TESTED(offline) < TESTED(STUB) < LIVE_VERIFIED`
> 结论：**P1 最小闭环完成（default-off；生产 W6 仍 `NOT_READY / BLOCKED`）**

## 1. 完成表

| 范围 | 结果 | 证据等级 | 证据 |
| --- | --- | --- | --- |
| W6 P0：Java↔Python 契约、schema/hash、错误码 | PASS | TESTED(offline) | `docs/api/mcp-gateway-internal-v1.md`；`tests/fixtures/mcp/catalog-v1.json`；Java/Python 契约测试 |
| W6 P1：本地只读测试 MCP Server、两端 `tools/list` | PASS（客户端契约 + Java 服务侧） | TESTED(STUB) / TESTED(offline) | `tests/fixtures/mcp/local_readonly_server.py`；Lane B/C 证据 |
| W6 最小 P2：只读调用、结果清洗、失败分类 | PASS（Java Gateway 服务级 + Python stub E2E；真实进程 E2E 未跑） | TESTED(STUB) | `McpGatewayServiceTest`；`run_readonly_e2e.py` 9/9 |
| 安全最小项：私网/元数据、描述/Schema 注入、在途撤权 | PASS | TESTED(offline) | `McpEndpointPolicyTest`、`McpGatewayCatalogContractTest`、`McpGatewayServiceTest` |
| D4：Provider test/publish P50/P95/超时/失败准入 | PASS | TESTED(UNIT + POSTGRES) | `ProviderPublishAdmissionTest`、`ProviderAdmissionPostgresIntegrationTest` |
| default-off + 旧 `McpAgentToolProvider` 回退 | PASS | TESTED(offline) | `McpGatewayConfigurationTest` |
| 全量 Java/Python、前端、CI、清理 | 进行中 / 以 §3 最终结果为准 | — | — |

### 1.1 明确未完成

- 真实 Java Gateway 进程 ↔ Python Host 联调：**NOT_RUN**；Lane C harness 已提供
  `--mode real` 入口，但本地只读 fixture 为 HTTP 回环测试服务，生产 `McpEndpointPolicy`
  要求 HTTPS/公网解析，不能把 stub 结果写成真实 Gateway E2E。
- W6-B 完整安全/E2E、多 MCP Server、多租户、生产 canary、TLS/ingress：未做。
- D4 只有 3 个默认样本，不是容量、SLO 或长期稳定性证明。
- N5 Reranker UI、JMeter per-label 重跑：未做（Stretch）。

## 2. 交付内容

### 2.1 Java MCP Gateway

- 新增 `agent.mcp.gateway.enabled=false`，默认关闭时 `/internal/ai/v1/mcp` 不装配，
  旧 `McpAgentToolProvider` 继续提供已绑定只读工具。
- 内部 JSON-RPC：`initialize`、`ping`、`tools/list`、`tools/call`。
- 身份只取内部令牌 claims 的 `projectRef/runRef/catalogVersion`；请求体自报身份字段拒绝。
- 逐工具 `toolSchemaHash` 与 `catalogVersion`，与 Python canonical 实现对齐冻结 fixture。
- 连接/项目白名单、确认 Hash、只读声明；描述/Schema NFC、限长、限深、注入片段中和。
- 调用前与结果返回前双 fence；撤权返回 `TOOL_REVOKED`/`OUTCOME_UNKNOWN`。
- 审计持久化 fail-closed：准入审计失败零外部调用；结果审计失败不返回成功正文。
- 出网采用 OkHttp 固定解析结果、HTTPS 443、禁重定向、私网/回环/元数据拒绝。

### 2.2 Python MCP Host

- 只调用固定 `/internal/ai/v1/mcp`，Bearer 逐调用注入，不重试、不跟随重定向。
- 契约模型、NFC、canonical JSON、工具/目录 Hash、Schema 限制与注入中和。
- fixture 断言：
  - `toolSchemaHash = sha256:6e0e51dc...82054`
  - `catalogVersion = sha256:e38d3391...d1441`
- 无 Provider 密钥、无业务库/Redis/MinIO、无生产路由。

### 2.3 本地只读 Server 与 E2E

- `tests/fixtures/mcp/local_readonly_server.py`：4 个合成工具，含 `readOnlyHint=false`
  白名单探针；随机端口、退出回收、无外部凭据。
- E2E harness 覆盖成功、截断、拒绝、未执行、deadline、结果未知，当前 `TESTED(STUB)`。

### 2.4 D4

- 管理员 test 多次采样，持久化 P50/P95、超时数、失败数、配置版本。
- publish 只在 PASS 摘要与当前 `capability_config_version` 匹配时放行。
- 已共享 Provider 修改请求参数自动转 `REVOKED`，不能编辑绕过准入。
- V98 仅扩展既有探针 CHECK 约束，不改历史迁移。

## 3. 测试与 CI

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| Java 定向（Gateway + D4 + 内部 AI） | `mvnw ... -Dtest=... test` | 123/123 PASS |
| Java 全量 | `mvnw -B -ntp verify` | 3005 tests，1 failure（`AgentObservabilityPostgresTest` 存量 hash 断言）；单类复跑 4/4 PASS，CI 分片 1/3、2/3、3/3 全绿，按本机 Testcontainers 抖动记录 |
| Python 全量 | `uv run ruff check .` / `format` / `mypy --strict src` / `pytest` | 1259 passed（1 warning，测试需剔除宿主注入的 `*_API_KEY` 环境变量） |
| Python MCP 定向 | 契约 + host + boundary + e2e | 76/76 PASS |
| 前端 | `pnpm lint && typecheck && test && build` | lint 0 error（70 warning）；typecheck PASS；63 files / 491 tests PASS；build PASS |
| CI | GitHub Actions `dev` @ `bdaa128f` | **success**，run `43391466027`；后端三分片、Python、前端、生产配置契约全绿；dev 不触发 Image job |

## 4. 未完成 / Deferred

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 真实 Gateway 进程 E2E | NOT_RUN | 本地只读 Server 为 HTTP；生产策略只允许 HTTPS/公网解析，需另建 TLS 测试夹具 |
| W6-B 完整安全/E2E | Deferred | 本轮只做最小安全项 |
| 多 MCP Server / 多租户 | Deferred | 明确不在范围 |
| 生产 canary / TLS / ingress | Deferred | 需用户和生产环境决策 |
| N5 Reranker UI | Deferred | Stretch，Must-have 优先 |
| JMeter per-label 重跑 | Deferred | 明确不重跑容量 |

## 5. 分支 / 提交 / 工作树 / 清理

| 项 | 状态 |
| --- | --- |
| 冻结契约 | `540a029d` |
| Lane A | `p1/mcp-gateway` → `85b931a4`、`6e5d55ed` |
| Lane B | `p1/mcp-host` → `df557346` |
| Lane C | `p1/e2e-d4` → `842a6da6`、`68ba7c39` |
| `dev` 集成 | `94bfd7a5`、`1127ae71`、`f8d98b26`、`9b0c6e01`、`51a16f74`、`9138b362`、`bdaa128f` |
| 当前 `dev` HEAD | `c62e7089`（最终文档提交；代码 HEAD/CI 为 `bdaa128f`） |
| `main` | 未动 |
| force push / 历史改写 | 无 |
| worktree | 已清理，只保留主工作树 |

### 5.1 清理清单

- 已删除 `E:\project\ai-collab-worktrees\p1-mcp-gateway`、`p1-mcp-host`、`p1-e2e-d4`。
- 已打本地归档 tag：`archive/20261113/p1-mcp-gateway`、`p1-mcp-host`、`p1-e2e-d4`。
- 保留冻结契约、代码、测试和证据；不保留临时日志、临时 token、Magpie 登录态。
- 分支先打 `archive/20261113/p1-*` tag 再删除，或保留临时分支供复核。

## 6. 结论

**P1 default-off 最小垂直切片：PASS（限定于服务级/契约级与 stub E2E）。**

可以进入 P2 的**评审/计划**，但不能宣称生产 W6 已就绪，也不能把本轮结果写成
生产 canary、真实外部 MCP 或容量 PASS。生产开关保持关闭，旧 Provider 回退可用。
