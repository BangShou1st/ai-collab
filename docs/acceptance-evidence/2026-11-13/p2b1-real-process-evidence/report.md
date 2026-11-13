# P2-B1 真实进程证据补全报告

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 750b3a05`
> 最终证据代码：`dev @ eade7923`（P2-B1 代码/证据集成前）
> 结论：**M-01 / M-02 / M-12 三项 Must-have 均为 `REAL_PROCESS PASS`**；
> **生产 W6 仍为 `NOT_READY / BLOCKED`**。

## 1. 结论

P2-B1 只补齐 P2-A 遗留的三项真实进程证据，不改变产品代码、默认开关、Prompt、
历史迁移或 P2-A 原始 raw。

| 项目 | 结果 | 证据等级 |
| --- | --- | --- |
| M-01 私网/元数据/allowlist 外目标拒绝 | **PASS** | `REAL_PROCESS` |
| M-02 DNS 重绑定/校验后解析变化 | **PASS** | `REAL_PROCESS` |
| M-12 Gateway 关闭回退旧 Provider | **PASS** | `REAL_PROCESS` |
| P2-A 原始 raw | 未修改 | SHA-256 一致 |
| 生产 W6 | 不可 canary | `NOT_READY / BLOCKED` |

## 2. 三项证据表

| 编号 | 状态 | 证据等级 | 关键结论 |
| --- | --- | --- | --- |
| M-01 | **PASS** | `REAL_PROCESS` | 独立 Java 进程实际调用生产 `HttpMcpClientFacade`/`McpEndpointPolicy`。metadata literal、private-resolution、allowlist-outside 三类目标均返回 `AGENT_MCP_ENDPOINT_FORBIDDEN`；三类 socket `checkConnect` 事件均为 0；private/outside sentinel accept 均为 0；控制项真实 TLS MCP 调用成功。 |
| M-02 | **PASS** | `REAL_PROCESS` | 受控 `InetAddressResolverProvider` + 两个真实 TLS 监听器。生产客户端经合法地址返回 `p2b1-good-v1`；产品调用阶段 policy lookup 3 次、外部重解析 0 次；重绑定监听器 accept 0；随后显式解析 `127.0.0.2` 并完成 TLS 握手，重绑定监听器 accept 1，排除“地址不可达”假阳性。 |
| M-12 | **PASS** | `REAL_PROCESS` | 真实 Spring Boot + PostgreSQL/Flyway + Tomcat、真实 Uvicorn Host、本地 TLS MCP Server。`gateway.enabled=false`；Gateway Controller/Service/Catalog/Authorizer Bean 全部缺席；`/internal/ai/v1/mcp` 返回 HTTP 404；旧 `McpAgentToolProvider` 实际枚举并执行 `echo_text` 成功；Python Host `/v1/mcp/list` 返回 502/`INTERNAL`。 |

## 3. 真实进程表

### 3.1 M-01 / M-02

运行时间：`2026-11-13 21:00:18` 起（Asia/Shanghai）；work dir
`ai-collab-ai-worker/target/p2b1-network-evidence/final2/run-20261113-210018/`。

| 组件 | PID | 端口 | 启动 → 停止 | 结果 |
| --- | ---: | ---: | --- | --- |
| M-01 Java harness | `54132` | — | `21:00:28` → `21:00:29` | PASS，exit 0 |
| M-01 控制 TLS MCP | `44264` | `55594` | `21:00:27` → `21:00:31` | PASS，accept 2 |
| M-01 private sentinel | `21964` | `55595` | `21:00:27` → `21:00:30` | accept 0 |
| M-01 allowlist-outside sentinel | `40568` | `55596` | `21:00:27` → `21:00:30` | accept 0 |
| M-02 Java harness | `27048` | — | `21:00:36` → `21:00:37` | PASS，exit 0 |
| M-02 合法 TLS MCP | `56848` | `60098` | `21:00:31` → `21:00:38` | accept 2 |
| M-02 重绑定 TLS MCP | `55764` | `60098`（`127.0.0.2`） | `21:00:31` → `21:00:37` | 产品调用 phase accept 0；显式探针后 accept 1 |

Python fixture 的 exit code 1 来自 harness 的 `taskkill /F` 清理，不是应用崩溃。

### 3.2 M-12

运行时间：`2026-11-13 20:59:03` 起（Asia/Shanghai）；work dir
`ai-collab-backend/target/p2b1-gateway-off/gateway-off-20261113-205903/`。

| 组件 | PID | 端口 | 启动 → 停止 | 结果 |
| --- | ---: | ---: | --- | --- |
| Java Spring Boot（测试专用入口） | `41996` | gateway `51290` / management `51291` | `20:59:25` → `20:59:40` | PASS，exit 1（强制回收） |
| Python Uvicorn Host | wrapper `40912` / uvicorn `52568` | host `51292` | `20:59:37` → `20:59:39` | PASS，exit 1（强制回收） |
| 本地 TLS MCP Server | `22104` | mcp `51293` | `20:59:04` → `20:59:40` | PASS，exit 1（强制回收） |

进程、端口、命令和日志路径见 `raw/processes.json`、`raw/m12-real-process.json`；
日志副本见 `raw/process-output/`。

### 3.3 原始文件校验

| 文件 | SHA-256 |
| --- | --- |
| `raw/m01-real-process.json` | `7FD277BC2E9854AB3C18985D9D791CDA00C14598A882BF55AB76590F70EE64D2` |
| `raw/m02-real-process.json` | `065765B52BBF5AF13897292C770DAC51F30C91423234F8805A27F8339EF0B387` |
| `raw/m12-real-process.json` | `063D115A86647F11F508D4AA728BA515900A07A67D4EEEEAC140DADBFC801B0C` |
| P2-A `raw/real-process.json` | `E37D3A7B3FCD4A954D0A7EC7B441C75E2CF71F6438FE6DCADD6D499F61DB28AC`（未变） |

## 4. 关键断言

### M-01

- `socketTapInstalled=true`，控制项 `socketConnectAttempts=4`、`listenerAccepted=2`，
  证明观测链路能看到真实 socket；
- metadata literal：`socketConnectAttempts=0`，`listenerAccepted=0`；
- private-resolution：resolver 1 次，`socketConnectAttempts=0`，sentinel accept 0；
- allowlist-outside：resolver 0 次，`socketConnectAttempts=0`，sentinel accept 0；
- `allNegativeCasesZeroSocketAttempts=true`。

### M-02

- 合法端返回 `p2b1-good-v1`；`productError=null`；
- `policyLookups=3`（initialize/notify/构造期策略校验），
  `externalLookupsBeforeProbe=0`；
- `goodAcceptedAfterProduct=2`，`evilAcceptedAfterProduct=0`；
- 显式 lookup 得到 `127.0.0.2`，`reboundResolved=true`、`reboundReachable=true`，
  `evilAcceptedAfterProbe=1`。

### M-12

- `gateway.enabled=false`；
- Gateway `controller/service/catalogService/authorizer` 全为 `false`；
- `GET/POST /internal/ai/v1/mcp` 返回 HTTP 404；
- 旧 Provider 工具列表含 `mcp.local_readonly.echo_text`；
- 执行结果 `status=SUCCEEDED`、`untrusted=true`、warning 保留；
- Python Host 返回 HTTP 502 / `INTERNAL`；
- `databaseDropped=true`，临时进程全部回收。

## 5. 证据等级与边界

| 等级 | 使用方式 |
| --- | --- |
| `REAL_PROCESS` | M-01/M-02 为独立 JVM + 生产网络类 + 真实 TCP/TLS fixture；M-12 为完整 Spring Boot 应用上下文 + PostgreSQL/Flyway + 真实 HTTP/Uvicorn 进程 |
| `JAVA_UNIT` | P2-A M-02 pinned-DNS 单测保留，不作为本轮完成依据 |
| `CONFIG` | P2-A M-12 default-off/Bean 配置测试保留，不作为本轮完成依据 |

边界：

- M-01/M-02 不是完整 Spring Boot Gateway 三进程 E2E；它们验证的是生产网络边界类在
  独立 JVM 中的真实 socket 行为。
- M-12 使用仅测试类路径可见的 Spring Boot 入口，不是生产打包 jar，也不是生产 canary。
- 本轮没有使用外部 MCP Provider 凭据，没有执行 W6-B、多租户、生产 Ed25519、
  TLS/ingress、监控告警或人工 canary。

## 6. 测试与 CI

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java 定向 | **PASS** | `P2B1ProbeConfigurationTest` + `McpEndpointPolicyTest` + `McpGatewayConfigurationTest` + `McpGatewayStaticBindingTest` + `McpAgentToolProviderTest`：15/15 |
| Python E2E 定向 | **PASS** | P2-B1 fixture + P2-A fixture/contract：7/7 |
| Python Ruff | **PASS** | `ruff check`、`ruff format --check` |
| Java 本机全量 | **PARTIAL（环境抖动）** | 本机 `mvnw verify` 在 Testcontainers/PostgreSQL 连接资源耗尽下中断；观察到 `AgentEvalSuiteTest` 2 个 JDBC 连接失败与 `AgentRepositoryIntegrationTest` 1 个 `NoSuchElementException`，均为既有集成测试，未改本轮文件；定向 15/15 通过。 |
| CI（最终判据） | **PASS** | [run 43433130155](https://github.com/BangShou1st/ai-collab/actions/runs/43433130155) / `517f1183`：后端 3 分片 + Maven verify、Python、前端、P0 contract 全部 success；镜像/发布按配置 skipped。 |
| 前端 | **PASS（CI）** | 零前端源码改动；CI `Frontend (pnpm)` lint/typecheck/test/build 全绿。 |

原始回执：`raw/tests/java-targeted.txt`、`raw/tests/python-e2e-targeted.txt`、
`raw/tests/ruff-check.txt`、`raw/tests/ruff-format.txt`、`raw/ci-run.json`；
本机全量失败摘要见 `raw/tests/java-verify-local-attempt.txt`。

## 7. Deferred / NOT_RUN

| 项目 | 状态 | 原因 |
| --- | --- | --- |
| W6-B 完整安全/E2E | Deferred | 超出 P2-B1；生产 W6 仍 `NOT_READY` |
| 多 MCP Server / 多租户 | Deferred | 本轮只补三项证据 |
| N5 Reranker UI | Deferred | 明确不做 |
| JMeter per-label | Deferred | 明确不做 |
| Java Testcontainers 根治 | Deferred | 存量问题，不归因本轮 |
| 仓库历史重写 | Deferred | 明确不做 |
| 生产 canary / TLS / ingress | Deferred | 无生产凭据与环境 |
| P2-A 真实三进程 E2E Stretch | NOT_RUN | Must-have 已闭环；未在本轮追加运行 |

## 8. 是否可进入 P2-B2

**可以进入 P2-B2 的“证据补强/功能评审”阶段。**

理由：P2-B1 三条 Must-have 均为 `REAL_PROCESS PASS`，原始进程、端口、PID、socket/TLS
计数和旧 Provider 实际执行均有证据；P2-A 原始 raw 未改写。

但不得把本结论解释为生产放行：

- 生产 W6 仍为 `NOT_READY / BLOCKED`；
- 尚未证明生产 Ed25519 身份、生产 egress/TLS、外部 Provider 准入、监控告警、
  多租户隔离和人工 canary；
- P2-B2 应继续以“本地/测试真实进程证据”为上限，不得直接跳到生产 canary。
