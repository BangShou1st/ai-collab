# P2-C Lane A：生产调用方与短令牌路径

> 日期：2026-11-13（Asia/Shanghai）
> 基线：`dev @ 211e4e32`
> 证据等级：`REAL_PROCESS`（本地真实进程协议探针）+ `JAVA_UNIT`（验签/过滤器定向测试）
> 结论：**BLOCKED_DECISION**。Ed25519 验签与错误拒绝已验证，但生产调用方、
> MCP 专用令牌签发/刷新/撤权路径尚未落生产。
> 边界：**不是生产调用方、不是生产密钥、不是生产 canary，未启用生产 Gateway。**

## 1. 结论先行

| 项目 | 结论 | 依据 |
| --- | --- | --- |
| 生产调用方 | **BLOCKED_DECISION** | 生产 compose 无 MCP Host/worker；Java Agent 未接 Gateway；外部调用方未定义 |
| 短令牌签发方 | **BLOCKED_DECISION** | Ed25519 原语可用，但生产主代码不签发 `mcp:list/mcp:call` 令牌；mTLS 未实现；static 被生产闸门阻断 |
| claims 完整性 | **NOT_VERIFIED** | `projectRef/runRef/aud/scope/exp/iat` 有生成与校验；`catalogVersion` 对 `tools/list` 可选；`jti` 未做唯一性/重放校验 |
| TTL 与过期拒绝 | **PASS** | TTL 60–900s、默认 300s；过期 token 由过滤器 401 拒绝 |
| 撤权与新调用 | **PASS（已验证路径）** | run 终止/成员移除、Provider 准入撤销、Hash fence 均拒绝新调用 |
| 在途结果语义 | **PASS（已验证路径）** | 撤权后在途结果 `TOOL_REVOKED`；网络结果不确定映射 `OUTCOME_UNKNOWN` |
| 真实请求探针 | **PASS（PROTOCOL_PROBE）** | 成功、过期、篡改签名、错误 scope 均为真实 HTTP |
| **Lane A 总判定** | **BLOCKED_DECISION** | 生产调用方与 MCP 令牌获取路径未定，不能进入 canary |

## 2. 生产调用方事实

### 2.1 候选逐项判定

| 候选调用方 | 是否存在真实生产路径 | 事实 | 判定 |
| --- | --- | --- | --- |
| Java Agent 内部调用 | **否** | `AgentToolRegistry` 的外部 Provider 只有 `McpAgentToolProvider`；主代码没有 Gateway 客户端、没有 `/internal/ai/v1/mcp` 调用点 | 不是已实现的生产调用方 |
| Python MCP Host | **否** | `mcp_host_app.py` 明确是测试进程，默认 FastAPI app 不装配；`compose.prod.yml` 只有 postgres/redis/minio/rocketmq/app/proxy，没有 ai-worker/MCP Host | 不是生产部署 |
| 外部服务直连 | **否** | 没有外部调用方接入契约、客户端注册、mTLS 或来源网络白名单；端点只接受内部 Bearer | 未定义、未实现 |

### 2.2 代码与部署依据

- `ai-collab-deploy/compose.prod.yml:3-247` 的服务集合为 `postgres`、`redis`、
  `minio`、`rocketmq-*`、`app`、`proxy`，没有 `ai-worker` 或 MCP Host。
- `ai-collab-ai-worker/src/ai_collab_worker/mcp_host_app.py:10-11` 明确写明：
  “这不是生产主路径。默认 FastAPI app 不装配本模块；生产拓扑若要把 Host 插入
  Agent 主路径，必须另立设计。”
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/AgentToolRegistry.java:37-47`
  把 `List<AgentToolProvider>` 注入注册表；`120-126` 只从这些 Provider 暴露外部工具。
  生产 Provider 实现为 `McpAgentToolProvider`，不是 Gateway 客户端。
- `McpGatewayController.java:21-24` 只在
  `agent.mcp.gateway.enabled=true` 时装配；`application.yml:334-340` 默认值为
  `false`。即使有调用方，默认生产路径也没有 Gateway 端点。
- 全仓非测试/非文档代码中，`/internal/ai/v1/mcp` 只出现在 Gateway 过滤器和
  Python 测试 Host，不存在 Java Agent 或生产 worker 的调用点。

### 2.3 调用方结论

```text
生产调用方：未决定 / 未接线
当前可执行的调用链：测试 harness 或测试 Host（本地 127.0.0.1）
不能把“协议探针可调用”表述为“生产调用方已就绪”
```

## 3. 短令牌签发与获取

### 3.1 Ed25519 原语已具备

- `InternalTokenIssuer` 生成 `EdDSA` JWS，固定 `iss=ai-collab-backend`、
  `sub=backend`，并写入 `aud/scope/jti/iat/exp`；`extraClaims` 可追加业务 claims
  （`InternalTokenIssuer.java:123-177`）。
- `InternalTokenVerifier` 校验 `alg/typ/kid/签名/iss/sub/aud/scope/exp/iat`
  （`InternalTokenVerifier.java:96-173`）。
- `InternalAiTokenFilter` 对 `/internal/ai/v1/mcp` 要求 `aud=backend`，且
  scope 至少包含 `mcp:list` 或 `mcp:call`（`InternalAiTokenFilter.java:71-73, 224-228`）。

### 3.2 生产 MCP 令牌签发路径不存在

| 签发候选 | 现状 | 结论 |
| --- | --- | --- |
| Ed25519 issuer | 原语存在；`InternalTokenProvider.token()` 只调用 `issueServiceToken()`，scope 为 `rerank/model-call/decide/plan`，不含 MCP scope | 不能签发 Gateway 所需令牌 |
| Job token | `AiJobTokenService` 签发 `job:* + model-call`，claims 只有 `projectRef`，没有 `runRef/catalogVersion`，也没有 MCP scope | 不能用于 Gateway |
| MCP 专用 issuer | 主代码仅定义 `SCOPE_MCP_LIST/SCOPE_MCP_CALL` 常量；没有任何生产服务用这两个 scope 签发令牌 | **缺失** |
| mTLS | Gateway 只实现 Bearer/Ed25519 过滤器，没有客户端证书校验、CA 配置或 mTLS 端点 | **未实现** |
| 两者组合 | 无实现 | **未实现** |
| static 长期令牌 | `InternalAiTokenFilter` 的 static 模式产生空 claims；`McpGatewayIdentity` 只能回退到 local/test 静态绑定；`McpGatewayStaticTestGuard` 在生产 profile 启动失败；`prod-config-check` 要求 Gateway 开启时必须是 Ed25519 | **不能作为唯一生产方案** |

关键代码：

- `InternalTokenProvider.java:10-17` 只有一个无参数 `token()`，没有
  `issueForRun(projectRef, runRef, catalogVersion)` 一类入口。
- `Ed25519InternalTokenProvider.java:21-24` 固定调用 `issueServiceToken()`。
- `AiJobTokenService.java:30-34, 58-64` 的 scope/claims 不含 MCP 所需字段。
- `McpGatewayStaticTestGuard.java:20-29` 禁止 static 绑定在 local/test 之外启用。
- `ai-collab-deploy/scripts/prod-config-check.mjs:64-79` 在 Gateway 开启时强制
  `INTERNAL_AI_TOKEN_MODE=ed25519`，并要求合法 PKCS#8 Ed25519 私钥。

### 3.3 签发方结论

```text
短令牌由谁签发：尚未定（Ed25519 issuer 是原语，不是已接线的 MCP 签发服务）
mTLS：未实现
组合方案：未实现
static：不可作为唯一生产方案
```

## 4. Claims 生成与校验矩阵

| Claim | 生成 | 校验 | 结果 |
| --- | --- | --- | --- |
| `iss` / `sub` | Issuer 固定为 `ai-collab-backend` / `backend` | Verifier 精确匹配 | **PASS** |
| `aud` | Issuer 接受 audience 列表；测试 MCP token 为 `backend` | Verifier 要求 `backend` | **PASS** |
| `scope` | Issuer 接受 scope 列表；测试 MCP token 为 `mcp:list mcp:call` | 过滤器要求 MCP scope 之一；服务层再区分 `tools/list` / `tools/call` | **PASS** |
| `projectRef` | **只有测试 harness 手写**；主代码无 MCP 生产调用点 | Gateway 要求合法 UUID；authorizer 以 project+run 查 run 并校验成员 | **生成路径缺失** |
| `runRef` | 同上 | Gateway 要求合法 UUID；run 必须属于 project、未终止、调用人是项目成员 | **生成路径缺失** |
| `catalogVersion` | 测试 harness 手写；Gateway 在 `tools/call` 要求严格 `sha256:<64hex>` | `tools/call` 要求与 DB 目录版本一致；`tools/list` 允许缺失（目录由 DB 现算） | **调用路径有校验，list 未强制** |
| `exp` / `iat` | Issuer 写入，TTL 60–900s | Verifier 校验整数、过期、未来 iat（含 60s skew） | **PASS** |
| `jti` | Issuer 每次写随机 UUID | Verifier 只解析，不校验存在性、唯一性或重放；没有 jti 撤销/去重存储 | **NOT_VERIFIED** |

关键代码：

- `McpGatewayIdentity.java:35-48` 解析 `projectRef/runRef/catalogVersion`；
  `catalogVersion` 仅在非空时校验格式，允许为 null。
- `McpGatewayIdentity.java:51-58` 区分 list/call scope。
- `McpGatewayService.java:263-285` 的 `tools/call` 要求 identity 带
  `catalogVersion`；`tools/list` 不要求。
- `McpGatewayService.java:293-301` 调用前同时比对 token 的 `catalogVersion`
  与请求体、DB 当前目录版本。
- `DatabaseMcpGatewayAuthorizer.java:26-36` 以 `projectRef+runRef` 查真实 run，
  校验项目成员；`40-55` 在结果返回前再次校验。
- `InternalTokenVerifier.java:159-171` 校验 `exp/iat`；没有 `jti` 重放检查。

## 5. TTL、刷新、撤权与在途语义

| 项 | 结论 | 证据 |
| --- | --- | --- |
| TTL 范围/默认值 | **PASS**：60–900s，默认 300s；生产 compose 默认 300 | `application.yml:295-298`；`InternalTokenProperties.java:26-30` |
| 过期拒绝 | **PASS**：过期 `exp`/未来 `iat` 返回 401 | `InternalTokenVerifier.java:159-171`；`InternalAiTokenFilterTest` 过期用例 |
| 刷新 | **未形成生产路径**：`InternalTokenProvider` 每次可重新签发通用服务令牌，但没有 MCP 专用 provider 或调用方 | `InternalTokenProvider.java:10-17`；`Ed25519InternalTokenProvider.java:21-24` |
| 撤权后新调用 | **PASS（已验证路径）**：run 终止/成员移除拒绝；Provider 准入撤销触发目录变化，新调用 `TOOL_DISABLED`/`SCHEMA_DRIFT` | `DatabaseMcpGatewayAuthorizer.java:26-55`；真实进程 `A-04.revocation-inflight` |
| 在途结果 | **PASS（已验证路径）**：外部调用返回前后复核；撤权返回 `TOOL_REVOKED`；网络结果不确定返回 `OUTCOME_UNKNOWN` | `McpGatewayService.java:306-341, 437-447`；真实进程 `inflightKind=TOOL_REVOKED` |
| jti 级撤权 | **未实现/NOT_VERIFIED**：没有 jti 黑名单或重放缓存，撤权依赖 run/成员/准入，不依赖令牌实例 | `InternalTokenVerifier` 未读取 `jti` |

真实进程结果（`raw/real-process.json`）：

```text
A-04.revocation-inflight:
  inflightKind = TOOL_REVOKED
  newCallKind  = SCHEMA_DRIFT
  inflightStatus = 502
A-06.hash-fence:
  kind = SCHEMA_DRIFT
```

说明：`TOOL_REVOKED`/`OUTCOME_UNKNOWN` 不会被当作成功结果；这符合失败可区分要求。

## 6. 真实请求证据

### 6.1 运行方式

使用现有真实进程 harness，启动本地 Spring Boot Gateway、两个独立 TLS MCP
进程、Python 测试 Host，并使用临时数据库（结束后删除）。Gateway 只绑定
`127.0.0.1`，不是生产 Gateway。

```powershell
python ai-collab-ai-worker/tests/e2e/run_p2b2_lane_a.py `
  --output docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/lane-a/raw `
  --work-dir E:\project\ai-collab\target\p2c-lane-a-3 `
  --pg-database ai_collab_p2c_lane_a_3 `
  --lane-b
```

生成的测试 token 使用测试专用 Ed25519 fixture（`tests/fixtures/auth/ed25519-test-fixture.json`），
不是生产私钥。claims 为：

```text
iss=ai-collab-backend
sub=backend
aud=[backend]
scope=mcp:list mcp:call
projectRef=11111111-1111-4111-8111-111111111111   (synthetic)
runRef=33333333-3333-4333-8333-333333333333       (synthetic)
catalogVersion=sha256:97427dc3...                 (synthetic catalog)
iat/exp=当前时间与 +300s
jti=随机 UUID（未记录原文）
```

### 6.2 结果表

| 场景 | 请求/构造 | HTTP/结果 | 证据等级 |
| --- | --- | --- | --- |
| 成功路径（外部只读 Provider） | 真实 `POST /internal/ai/v1/mcp`，`tools/call` | 200；`outcome=SUCCEEDED`；`untrusted=true`；`apiKey/authorization=[REDACTED]` | `REAL_PROCESS` |
| 成功路径（本地 fixture Provider） | 同上 | 200；`outcome=SUCCEEDED`；敏感字段脱敏 | `REAL_PROCESS` |
| 篡改签名 | 修改 JWS 签名后请求 | 401；`UNAUTHORIZED` | `REAL_PROCESS` |
| 错误 scope | 只含 `mcp:list` 的 token 调 `tools/call` | 401；`UNAUTHORIZED` | `REAL_PROCESS` |
| 过期 token | `exp` 早于当前时间（`iat=当前`，TTL 取负） | 401；`UNAUTHORIZED` | `REAL_PROCESS` |
| 未授权/未绑定工具 | 直调 draft/revoked/跨租户工具 | `TOOL_DISABLED` | `REAL_PROCESS` |
| Schema 漂移 | 篡改连接 Hash 后调用 | `SCHEMA_DRIFT` | `REAL_PROCESS` |
| 撤权在途 | 慢调用期间撤销 Provider 准入 | 在途 `TOOL_REVOKED`；新调用 `SCHEMA_DRIFT` | `REAL_PROCESS` |

原始结果：

- `raw/real-process.json`：17 个 case 全部 `PASS`，`evidenceLevel=TESTED(REAL_PROCESS)`，
  `commit=211e4e321bcfab69e0631bd9879613f439fd3547`。
- `raw/java-token-gateway-tests.txt`：62/62 PASS（过滤器/验签/配置/Gateway Service）。
- `raw/java-token-claims-tests.txt`：22/22 PASS（Issuer/Catalog/Static Binding）。

### 6.3 首轮失败的诚实记录

首轮使用相对 `--work-dir target\p2c-lane-a` 启动时，Java 进程工作目录为
`ai-collab-backend`，相对 truststore 路径被解析到不存在的目录，导致两个真实调用
返回 `OUTCOME_UNKNOWN`。该失败是 harness 路径问题，不是调用链回归；已保留为
`raw/attempt1-relative-workdir-failure.json`。改用绝对 `--work-dir` 后全部通过。

### 6.4 生产等价性边界

```text
这是 PROTOCOL_PROBE，不是生产调用方。
使用测试 Ed25519 fixture、合成 TLS Provider、本地 127.0.0.1 和临时数据库。
没有生产私钥、生产证书链、生产 Host/worker 或真实外部 Provider。
```

## 7. 证据与校验和

| 文件 | 说明 | SHA-256 |
| --- | --- | --- |
| `raw/real-process.json` | 最终真实进程 17/17（含过期真实 HTTP） | `8FEAD5908147593D3B105F8641E52BD3DB123F58F28D8AE6A8D225AC88A554A4` |
| `raw/real-process-16-before-expiry.json` | 补测过期用例前 16/16 记录 | `B85A3B4FC066B78F882624BEF18C7B75B5DF8B072B547A5EEFA8687FBC52A9A3` |
| `raw/attempt1-relative-workdir-failure.json` | 首轮 truststore 路径失败记录 | `8820D9AC8645C8C5B82B43FDAD620376EAB5EB2B273BB800E618078B1684BC93` |
| `raw/java-token-gateway-tests.txt` | 62/62 PASS | `CC74AA0C14D4AA79D7011612F70A0EA50C36BD9A1299C0486E99C1F7D16954E9` |
| `raw/java-token-claims-tests.txt` | 22/22 PASS | `A13D65F216B93CFFE84AA0F362B4A121D285EEA1A58548063D5D61453D87E677` |

## 8. 阻塞项与最小下一步

| 阻塞项 | 决策人 | 最小下一步 |
| --- | --- | --- |
| 生产调用方未定 | 产品/架构 owner | 三选一：Java Agent 内部调用、Python Host、外部服务；书面记录调用链与故障边界 |
| MCP 令牌签发/获取未定 | 后端 owner | 设计 `issueForRun(projectRef, runRef, catalogVersion)` 或等价入口，绑定真实 run；明确由谁调用、如何传递、如何刷新 |
| 无生产密钥/证书 | 部署/安全 owner | 生产等价环境安全注入真实 Ed25519 key/cert；不得使用测试 fixture 代替 |
| `jti` 未做重放校验 | 安全 owner | 在 Verifier/Filter 增加 jti 必填、唯一性与短期重放缓存，或书面接受短 TTL + 只读工具风险 |
| `tools/list` 未强制 `catalogVersion` | 后端 owner | 决定是否要求 list 也绑定目录版本；若要求，修改 `McpGatewayIdentity` 与测试 |
| 无 MCP 令牌级撤权 | 安全/后端 owner | 明确撤权仅依赖 run/成员/准入，还是增加 jti/令牌撤销表；写入 canary 前置 |

在上述决策完成前，不得把 Lane A 写成 PASS，也不得执行生产 canary。

## 9. 判定

```text
Lane A：BLOCKED_DECISION
生产调用方：未接线
短令牌签发/获取：未落生产
Ed25519 验签与失败拒绝：PASS（本地真实进程 + JAVA_UNIT）
生产 canary：NOT_STARTED
```
