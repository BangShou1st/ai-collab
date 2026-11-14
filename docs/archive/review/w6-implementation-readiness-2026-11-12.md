# W6 实现 readiness：Java MCP Gateway + Python MCP Host

> 日期：2026-11-13（Asia/Shanghai；初稿 2026-11-12）
> 基线：`w6/readiness @ d6fa5133`（Release Blocker Closure 冻结件提交）
> 性质：**只读 readiness 评估，不是 W6 实现**。本报告未改代码、未改配置、未新增迁移、未启动生产实现、未连接真实 MCP、未执行 staging 测量。
> 当前判定：**NOT_READY / BLOCKED（生产 readiness 不通过；W3 `3S_FAIL / FUNCTIONAL_PASS`；W5 `PASS`；W4 相邻 `FAIL`；只有用户明确批准才可开始 default-off 实现）**
> 证据等级：`IMPLEMENTED < TESTED(offline) < TESTED(STUB) < LIVE_VERIFIED`；`PENDING_EVIDENCE`、`NOT_VERIFIED`、`INCONCLUSIVE`、`NOT_EVALUATED`、`BLOCKED` 均不得写成 `PASS`。

---

## 0. 结论先行

1. **W6 当前不能生产启用。** Ed25519 已有 staging `PASS` 证据；W5（C DREAMING 任务级、`sourceRef` 治理、删除 500）已由终稿证据确认 `PASS`；W3 的 3s 门禁保留 **`FAIL`**（run 1 `adapter results_count_mismatch`；run 2 8 档 PASS、20 档 `adapter_TIMEOUT`、50 档 `NOT_RUN`），30s functional 门禁 **`PASS`**（8/20/50 各 15/15、0 失败、known 1.0、1:1 30/30、overhead P95 45/109/72ms；50 档补测 60/60、model P50/P95/P99 17.8/19.6/21.4s、overhead P95 23.6ms）。生产 `RERANK_MODE=java` 未变，用户路径仍 `NOT_VERIFIED`。
2. **设计评审 PASS 不等于实现 PASS。** 既有评审只证明职责边界与威胁模型自洽；现有 Java MCP 代码仍有 G1–G7 实现前缺口，且仓库中没有 W6 Gateway 内部端点或 Python MCP Host 模块。
3. **W4 只是相邻阻塞，不是 W6 前置。** Gemini 历史 `FAIL` 保留；Lane C 的 Magpie 3 轮 pooled **43/60 = 0.7167**、JSON **1.0**、5xx/504/429 **全 0**、`invalidToolCalls=0`，仍低于 agreement ≥0.98，判定 **W4 `FAIL`**；根因归项目侧模型决策/prompt，不是 provider。每轮 r1 `0.75`、r2 `0.70`、r3 `0.70`；用户已决定不做 v4，17 个 mismatch 转后续专项。本报告不会把 W4 写成 `PASS`，也不把它混入 W6 前置表。
4. **本次没有开始 W6 实现。** 没有新增 `agent.mcp.gateway.enabled` 开关、没有改六个生产默认开关、没有新增迁移、没有改前端或部署配置。
5. **判定规则是硬门禁。** 生产 readiness 始终是 **`NOT_READY / BLOCKED`**：W3 3s 未达、W4 相邻 `FAIL`。W3 functional `PASS` + W5 `PASS` 只表示“若用户明确批准，可开始 default-off 实现”；不得称生产可灰度、可启用或可 canary。

---

## 1. 判定口径

| 判定词 | 含义 | 本报告用法 |
| --- | --- | --- |
| `PASS` | 冻结门禁、指定环境、指定证据等级全部满足 | 只有原始证据可回查时才使用 |
| `FAIL` | 已执行但未达到门禁 | 不得用“剔除失败样本”后的诊断值替代 |
| `PENDING_EVIDENCE` | 责任泳道尚未交付最终报告/原始证据 | W3 仍使用；W4 已由终稿确认 `FAIL`（相邻）；W5 已由终稿证据确认 `PASS` |
| `NOT_VERIFIED` | 环境不可达或未执行，不能推定 | 不得写成 `PASS` |
| `INCONCLUSIVE` / `NOT_EVALUATED` | 评测口径无效 / 对照未执行 | 不得写成 `PASS` |
| `BLOCKED` | 明确前置未清零 | W6 当前 readiness 状态 |

**W6 readiness 的唯一放行条件（按 2026-11-13 用户口径调整）：**

```text
W3 3s 门禁 = FAIL（保留，不再作为本轮功能验收的阻塞项）
  AND W3 30s functional 8/20/50 = PASS（已实测；50 档 60 次观测补测 60/60）
  AND Ed25519 既有 staging 证据 = PASS（不重做）
  AND W5 Lane D 的 C DREAMING 任务级门禁 = PASS
  AND W5 sourceRef 治理 = PASS
  AND W5 删除 500 修复 = PASS
```

满足上述条件后，也只有**用户明确批准**时才可开始 default-off 实现；生产 readiness 仍为 **`NOT_READY / BLOCKED`**，不得输出生产可灰度/可启用/canary 结论。W3 30s functional 若失败则 W6 继续 `BLOCKED`。W4 不在上述合取式中；它是相邻阻塞项，必须单列且不得写成 `PASS`。

---

## 2. 冻结前置核对

### 2.1 总表

| # | 前置 | 冻结清零条件 | 当前状态 | 证据路径 | 限定 / 下一步 |
| --- | --- | --- | --- | --- | --- |
| 1 | **W3 Lane B** | 3s 门禁保留 `FAIL`；30s functional 8/20/50 验收（硬件不换、延迟可接受、先做功能）；functional `PASS` + W5 `PASS` 后，只有用户明确批准才可开始 default-off 实现；生产用户路径仍 `NOT_VERIFIED` | **`3S_FAIL / FUNCTIONAL_PASS`** | `docs/acceptance-evidence/2026-11-12/w3-reranker-local/report.md` 与 `raw/`；`raw/functional/functional-summary.json`、`obs50-retry-summary.json`（首批 `obs50-summary.json` 保留） | 3s run 1：8 档 2/15 `adapter results_count_mismatch`，20/50 `NOT_RUN`；3s run 2：8 档 PASS、20 档 15/15 `adapter_TIMEOUT`（adapter 上限 3003ms，本地真实 `/rerank` 3.9–5.8s）、50 档 `NOT_RUN`；30s functional：8/20/50 各 15/15、0 失败、known 1.0、overhead P95 45/109/72ms、1:1 30/30；50 obs 补测 60/60、model P50/P95/P99 17.8/19.6/21.4s、overhead P95 23.6ms。3s FAIL 是正式证据，不得删除；生产 readiness 不通过 |
| 2 | **Ed25519** | 既有 staging 双端联调、轮换、回滚证据 `PASS`；本波不重做 | **`PASS`（引用既有证据）** | `docs/acceptance-evidence/2026-11-12/w3-ed25519-staging/report.md`、`matrix.md`、`probe.log` | 14/14 PASS；只引用，不重新执行 |
| 3 | **W5 Lane D** | C DREAMING 任务级门禁 `PASS` + `sourceRef` 治理 `PASS` + 测试项目删除 500 修复并回归 | **`PASS`** | `docs/acceptance-evidence/2026-11-12/w5-memory-c/report.md` 与 `raw/` | 终稿：分支 `w5/memory-c` HEAD `a97709b3`，staging 部署代码 `4d0efa4b`（已合 `dev@99c47d5e`）；C `10/11` > B `8/11` > A `4/11`，11/11 人工审批、autoPublished=0；B/C 泄露/冲突 0、citation 1.0。`LOW_CONFIDENCE`：每 arm 11 tasks；live sourceRef 仅 `fact:project`，`memory:` 由 Testcontainers 覆盖 |
| 4 | **W4 decide（相邻）** | 不作为 W6 前置；只记录状态，且不得写成 `PASS` | **`FAIL`（相邻阻塞，不计入 W6 前置）** | `docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md` 与 `raw/`；已 cherry-pick 到 `dev@ee102d1d`（6 笔，含 Prompt `.3`、Gemini 历史 FAIL、Magpie 3 轮 raw） | Gemini 历史 `FAIL` 保留；Magpie 3 轮 pooled `43/60=0.7167`、JSON `1.0`、5xx/504/429 全 0、`invalidToolCalls=0`，仍低于 `0.98`；每轮 `0.75/0.70/0.70`；根因归项目侧模型决策/prompt，不是 provider。用户已决定 v4 不再调优，17 个 mismatch 分析转后续专项；W6 readiness 不受 W4 影响 |

### 2.2 W5 子门禁拆分

Lane D 的 `PASS` 必须同时覆盖以下三项，缺一不可：

| W5 子项 | 验收条件 | 当前状态 | 需要的原始证据 |
| --- | --- | --- | --- |
| C DREAMING 任务级门禁 | `revocationLeaks=0`、`crossProjectLeaks=0`、`conflictMisuseRate=0`、`citationValidityRate=1.0`；C 候选 100% 经人工审批、自动发布数 0；C 任务级结果不低于 B，且不低于 A 的同一任务基线；必须是真实 Job + 真实模型 + 真实审批 | **`PASS`** | `report.md` §1；`raw/staging/output-c2/raw/measurement.json`、`job-status.json`、`job-status.jsonl`、`candidates-*.json`、`approvals.json`、`approval-map.json`、`task-calls.jsonl` |
| `sourceRef` 治理 | 候选 `sourceRef` 逐条回查同一 Job/attempt 快照；只允许命中该快照的 `facts[].ref` 或 `existingMemories[]`（`memory:<id>`）；前缀/格式/跨 Job/跨项目引用拒绝；提交时与审批时都校验；拒绝有固定错误码与审计 | **`PASS`** | `raw/source-ref-rejection-matrix.json`；`raw/staging/c-db-snapshot.txt`；11/11 live `fact:project`；`memory:`/跨 Job/跨项目/篡改路径由 Testcontainers 覆盖 |
| 测试项目删除 500 | 复现并修复项目删除 500；覆盖有 Job/候选/记忆/审计引用的项目；删除仍校验权限，不得用级联删除绕过审计/所有权 | **`PASS`** | `raw/offline/java-deletion-500.txt`；`raw/staging/c-deletion-verify.txt`；`raw/staging/fk-delete-rule.txt`；staging cleanup HTTP 204、无残留 |

### 2.3 证据等级与限定

- W3 的 Lane B 30s functional 终稿必须明确：8/20/50 functional 验收只在 **staging + 指定本地 Reranker + capability 测试路径** 成立；3s 门禁仍为 `FAIL`；**生产用户路径仍为 `NOT_VERIFIED`**，因为 `RERANK_MODE=java` 未变。
- W5 的 C 是真实链路：真实 Job `e65fbaa4-938b-44cf-99e1-0574370018f8`、真实 Gemini AGENT、项目 owner 11/11 显式 approve；attempt 1 的 provider 400/503 失败证据保留在 `raw/staging/output-attempt1/`，最终 C 使用新的真实 Job，未把失败样本改写成 PASS。
- W5 的限定：每 arm 11 tasks，`LOW_CONFIDENCE`；A/B 使用冻结 fixture 的确定性选择器；C 项目快照没有既有记忆，因此 live sourceRef 只覆盖 `fact:project`，`memory:` 回查由 V96/V97 Testcontainers 矩阵覆盖；不由此外推生产。
- W3 的 3s `FAIL` 是保留的结论：run 1 为 adapter 契约失败，run 2 为 20 档 3s timeout；本轮 30s functional acceptance 只改变“功能可用性”判定，不把 3s 延迟失败洗成 PASS。即使 functional `PASS` + W5 `PASS`，也只有用户明确批准才可开始 default-off 实现；生产 readiness 仍 `NOT_READY / BLOCKED`，生产用户路径仍 `NOT_VERIFIED`，不得 canary/启用。
- W3 30s functional 的 8/20/50 门禁已 PASS；50 档 60 次观测补测 60/60（`obs50-retry-summary.json`），model P50/P95/P99 17.8/19.6/21.4s、overhead P95 23.6ms；首批 `obs50-summary.json` 的 18/60 admin JWT 401 保留为观测脚本鉴权失败，不是 reranker 失败，也不构成 functional 门禁失败。该结果仍只属于 staging + 本地 Reranker + capability 测试路径。
- 本报告不把 30s functional `PASS` 写成生产 `READY`；终稿只做证据核对与状态更新，不重跑 B/D 的测量。

---

## 3. 只读代码与设计审计

### 3.1 已存在的 Java MCP 能力

以下均为基线代码事实，不代表 W6 已完成：

| 组件 | 现有能力 | 对 W6 的意义 |
| --- | --- | --- |
| `McpAdministrationService` | 管理员连接 CRUD、测试、发现、启停、Schema Hash 记录、审计 | 可复用为 Gateway 控制面基础；需要抽出 Gateway 边界并补人工审核/逐工具 Hash |
| `McpEndpointPolicy` | 仅 HTTPS、精确 host allowlist、拒绝 user-info/fragment、DNS 解析后拒绝 loopback/私网/链路本地/元数据地址 | 基础正确；但 DNS 校验与连接分离，存在 G1 |
| `HttpMcpClientFacade` | 不跟随重定向、响应字节上限、Bearer、`Mcp-Session-Id`、连接/读取超时 | 可复用传输层；需要在连接期固定解析结果或经 egress proxy |
| `McpAgentToolProvider` | `readOnlyHint=true` + 连接级 `toolAllowlist`、执行前重新校验、Schema Hash 不一致拒绝、`isError -> UNKNOWN`、结果清洗 | 当前 Agent 的外部 MCP 只读入口；W6 首版保留为回退路径 |
| `McpConnectionManager` | 按 project/connection/version/schemaHash 缓存；停用/更新时失效 | 缓存失效基础；没有在途请求 fence/取消 |
| `McpResultSanitizer` | `untrusted=true`、敏感字段脱敏、控制字符清理、数组/字节上限、注入 warning | 结果侧基础；注入正则不是安全边界（G3） |
| `V29__add_agent_mcp.sql` | `agent_mcp_connection` 表、schema hash、confirmed hash、加密凭据、超时/结果上限 | 现有数据模型；需要扩展逐工具 Hash/catalogVersion |
| `V31__per_project_model_and_embedding.sql` | 将 MCP 连接改为 project 级，并删除 `agent_project_mcp_binding` | 与设计中的“连接级 + 项目级白名单”存在模型漂移，见 D3 |

### 3.2 实现前必须处理的缺口

| 编号 | 缺口 | 严重度 | 代码/设计证据 | 为什么不能留到实现后 |
| --- | --- | --- | --- | --- |
| G1 | DNS 校验与 HTTP 连接分离，存在 rebinding TOCTOU | **高** | `McpEndpointPolicy.validate()` 在第 45 行解析 DNS；`HttpMcpClientFacade.send()` 第 108 行再次校验后，第 115 行仍按 hostname 交给 `HttpClient` | 校验后解析可被改为私网/元数据地址；必须在 W6 启用前用固定解析/egress proxy 关闭窗口 |
| G2 | 工具 `description` 与 `inputSchema` 原样进入 Agent 工具定义，未做上下文侧限长/净化 | **高** | `McpAgentToolProvider` 第 49–51 行直接使用发现结果的 description/inputSchema；`McpClientFacade` 第 62 行原样返回 | 外部描述/Schema 可注入模型或造成超大 Schema/DoS；不可信包装必须成为实现门禁 |
| G3 | 注入检测只是正则 warning，不是边界 | **中** | `McpResultSanitizer` 第 20 行定义正则，第 31/41 行仅加 warning | 未命中正则不能作为放行依据；结果必须始终作为不可信数据包装 |
| G4 | 撤权/停用会使缓存失效，但在途外部调用没有统一 fence/取消 | **中** | `McpConnectionManager.invalidate()` 第 39 行只关闭缓存客户端；`HttpMcpClientFacade.close()` 为空实现 | 撤权后迟到结果可能进入上下文；需要 `catalogVersion + bindingVersion + lease` 结果前复核 |
| G5 | 只有聚合 Schema Hash，缺少逐工具 Hash 与目录版本 | **中** | `McpAdministrationService.discover()` 第 105 行对排序后的完整工具数组计算 SHA-256；`McpConnection` 只有 `schemaHash`/`confirmedSchemaHash` | 无法做工具级漂移定位、精确白名单绑定与双客户端逐工具比对 |
| G6 | 没有 Java Gateway 内部契约、没有 `/internal/ai/v1/mcp`、没有 Python MCP Host 模块 | **高** | 基线搜索 `internal/ai/v1/mcp`、`McpGateway`、Python `mcp` 依赖均为 0；Python worker 无 MCP 模块 | 双客户端互通与逐项一致无法验收；必须先冻结契约再实现 |
| G7 | OAuth 只保存凭证，未定义只读 scope、刷新、撤销与最小权限 | **中** | `McpAuthType` 有 `OAUTH21`，但当前没有 refresh/scope 设计；设计 §8.1 只给规则 | 凭据轮换与撤权语义不完整；OAuth 只能先限定只读 scope |
| G8 | 现有连接是 project 级，`agent_project_mcp_binding` 已在 V31 删除 | **中（设计漂移）** | `V31` 第 53 行 `DROP TABLE IF EXISTS agent_project_mcp_binding`；`McpRepository.listByProject()` 第 26 行直接按 `project_id` 查询连接 | 设计中的“连接级 + 项目级白名单”与当前数据模型不一致；需决定是否把 project 级连接视为绑定，或重新引入共享连接 + binding |
| G9 | 没有 W6 Gateway 默认关闭开关；现有 `McpAgentToolProvider` 对已启用连接始终生效 | **中** | `application.yml` 只有 `agent.mcp.allowed-hosts`；未发现 `agent.mcp.gateway.enabled` | 无法安全灰度/回滚；W6-01 必须先加默认 `false` 的开关并保留旧 Provider |
| G10 | 工具调用审计/指标不完整 | **中** | 当前只有管理操作审计；`mcp.gateway.*` 指标尚未实现 | 设计 §10/§12 要求每次 `tools/list`/`tools/call` 可追溯；需在 Gateway 统一出口补齐 |
| G11 | MCP 结果是否可持久化到 Agent 历史/记忆未决 | **中** | 设计 §9.6 明确“不回灌”；现有 Agent 历史可能保留工具输出 | 撤权/删除后旧正文可能残留；需产品与保留策略决策（D7） |

### 3.3 现有测试覆盖与缺口

基线仓库已有 7 个 MCP 相关测试套件；`docs/acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/raw/verify/` 中的历史记录显示：

| 测试 | 历史结果 | 覆盖内容 |
| --- | --- | --- |
| `HttpMcpClientFacadeTest` | 2/2 pass | 响应字节上限 |
| `McpAdministrationPostgresIntegrationTest` | 2/2 pass | 创建/编辑/凭据轮换/发现/启用、项目隔离 |
| `McpAdministrationTransactionBoundaryTest` | 1/1 pass | `discover` 事务边界 |
| `McpAgentToolProviderTest` | 3/3 pass | 只读暴露、停用后拒绝、MCP error 为 UNKNOWN |
| `McpConnectionViewSerializationTest` | 1/1 pass | 视图序列化 |
| `McpEndpointPolicyTest` | 2/2 pass | host allowlist、私网/元数据拒绝 |
| `McpResultSanitizerTest` | 2/2 pass | 脱敏与截断基础 |

**未覆盖的关键场景：** DNS 重绑定（G1）、工具描述/Schema 注入与深度/长度（G2）、在途撤权竞态（G4）、逐工具 Hash/双客户端逐项比对（G5/G6）、内部 MCP 端点鉴权与跨项目拒绝（G6）、工具调用审计/指标（G10）。这些缺口不能由既有“7 个套件全绿”推出 W6 安全。

---

## 4. W6 实现边界

### 4.1 Java MCP Gateway（唯一安全边界）

**控制面：**

- 管理端连接/Provider 的创建、测试、发现、人工审核、启用/停用；
- `ModelSecretCipher` 加密凭据；接口、日志、异常、审计、指标均不出现明文；
- 只读白名单与项目绑定的权威校验；写工具、审批工具永远不进入 MCP 白名单；
- 逐工具 `toolSchemaHash` + 聚合 `catalogHash` + `catalogVersion`；人工确认后冻结；漂移自动停用；
- 发现结果、审核动作、启停、Schema 漂移、工具调用审计。

**数据面：**

- 唯一的外部 MCP 网络出口；HTTPS allowlist、DNS/重定向/私网/元数据防护；
- 内部 MCP JSON-RPC 端点（建议 `POST /internal/ai/v1/mcp`）：`initialize`、`ping`、`tools/list`、`tools/call`；不开放 `resources/*`、`prompts/*`；
- 以可信 `runId`、`projectId`、`catalogVersion`、binding/lease 为准复核权限，不接受 Python 自报授权；
- 工具参数最终 JSON Schema 校验、结果清洗/不可信包装、失败分类、在途撤权 fence、审计与指标；
- Gateway 默认关闭；关闭时回退现有 `McpAgentToolProvider`。

### 4.2 Python MCP Host（只做协议适配，不做安全决策）

- 通过内部网络访问 Java Gateway，只实现标准 MCP 客户端的 `initialize` / `ping` / `tools/list` / `tools/call`；
- 做 Schema 归一化、形状/大小/深度初筛、结果结构化；不判断项目/用户权限，不擅自补默认值；
- **不直接连接外部 MCP、不持有 Bearer/OAuth/refresh token、不读写 PG/Redis/MinIO、不审批、不发布、不落长期记忆**；
- 首版**不进入 Java Agent 同步主路径**；只作为契约互通、归一化与评测客户端。若未来进入主路径，必须另立延迟预算与故障隔离设计。

### 4.3 信任与调用拓扑（v1）

```text
用户/浏览器
  -> Java Agent 运行与审批
     -> Java Tool Registry / ProjectAccessGuard
        -> Java MCP Gateway（唯一凭据/出网/审计出口）
           -> 外部 MCP Server（HTTPS 只读）

Python MCP Host/Client
  -. 内部 MCP 契约/归一化/评测 .-> Java MCP Gateway
  -. 默认不进入 Java Agent 同步执行主路径 .-> Java Agent
```

### 4.4 非目标

- 不实现 MCP 写工具，不把 MCP 调用包装成审批写操作；
- 不实现 STDIO MCP 或本地进程执行；
- 不实现对外 MCP Server；
- 不让 Python 直接出网、持有 MCP 凭据、读写业务库、审批或发布；
- 不自动确认 Schema、不自动放行白名单、不自动启用连接；
- 不改六个生产默认开关（`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`）；
- 不改前端、不碰 `main`、不改历史迁移、不在本波做 staging canary。

---

## 5. 实现任务拆分（按可独立验收的 PR）

> 以下只是实现计划，不是本波已完成的代码。每个 PR 必须独立可验证、可回滚，且默认关闭/无生产调用；W6 实现仍未启动。

| PR | 范围 | 依赖 | 独立验收 | 回滚/开关 |
| --- | --- | --- | --- | --- |
| **W6-00 契约冻结** | 冻结内部 MCP JSON-RPC `schemaVersion=1`、方法/错误码/`outcome`/`untrusted`/`truncated`；冻结 `toolSchemaHash`/`catalogHash`/`catalogVersion` 的规范化规则（NFC、键排序、数组稳定排序）；Java/Python 双客户端 fixture | 无 | 两语言契约测试逐字节一致；不发起任何外部 MCP 调用；文档入 `docs/api/` 或 `docs/design/` | 纯文档/测试，无运行时开关 |
| **W6-01 Gateway 控制面抽取 + 开关** | 从 `McpAdministrationService` 抽出 Gateway 目录/审核边界；新增 `agent.mcp.gateway.enabled=false`（名称在 W6-00 冻结）；保留 `McpAgentToolProvider` 回退 | W6-00 | 默认关闭时现有 MCP 行为不变；开启时只走新目录接口；管理操作/权限/审计测试通过 | 开关默认 `false`；关闭即回退旧 Provider |
| **W6-02 目录身份与 Hash 模型** | 增加逐工具 `toolSchemaHash`、`catalogVersion`、confirmed 版本；如现有表无法复用，新增迁移（版本必须由中心分配，禁止占用 V92–V97） | W6-00、D3、D4 | 单工具/聚合 Hash 测试向量；Java/Python 归一化一致；漂移检测失败即停用；不自动确认 | 迁移只增列/表；旧镜像兼容；不修改历史迁移 |
| **W6-03 出网加固（G1/G7）** | 在连接期固定 DNS 解析结果并保持 Host/SNI，或接入受控 egress proxy；禁止重定向；OAuth 仅只读 scope，refresh/撤销仅在 Java | D5、D6 | M-01/M-02：allowlist 外、私网、元数据、重定向、DNS 重绑定全部拒绝；无凭据泄漏 | 开关关闭时走旧传输；出网策略不可用时 fail closed |
| **W6-04 不可信元数据与结果边界（G2/G3）** | 工具描述/Schema 进入模型前做 NFC、控制字符清理、长度/深度/数组上限；结果始终结构化标记 `untrusted`；注入 warning 仅辅助，不是放行条件 | W6-00、W6-02 | M-03/M-04/M-10：注入文本不获得系统指令权威；超大/二进制/路径被拒绝或截断并标记 | 纯净化层；异常时拒绝该工具结果，不回退为原始文本 |
| **W6-05 撤权与在途 fence（G4）** | 每次调用携带 `catalogVersion + bindingVersion + lease`；发送前/结果前各复核一次；撤权取消在途连接；结果分类 `TOOL_REVOKED` / `OUTCOME_UNKNOWN` | W6-02、W6-03 | M-08：撤权后新调用拒绝；在途迟到结果不进入上下文；审计记录版本与原因 | 失败安全优先；撤权/审计不可用时拒绝新调用 |
| **W6-06 内部 MCP 端点与鉴权（G6）** | `POST /internal/ai/v1/mcp`；只接受内部网络；Ed25519 短期服务令牌（`aud=mcp-gateway`、`sub=backend`，claims 含 run/project/catalog/scopes）；不接浏览器 JWT；`static` 只允许显式测试或 fail closed | W6-01、W6-05、D1 | M-06/M-07/M-09/M-11：未授权/跨项目/伪造 run 拒绝；`tools/list` 与 Java 目录逐项一致；错误码可区分 | 端点默认关闭；关闭不影响旧 Provider |
| **W6-07 Python MCP Host（P4）** | 新增 Python MCP Host/Client 模块：只访问 Java Gateway；协议适配、Schema 归一化、结果结构化；不重试、不调度、不审批、不持久化；依赖选择（官方 SDK 或最小内部客户端）由 D9 决定 | W6-00、W6-06、D2、D9 | 双客户端 fixture；`tools/list` 逐项一致；无外部 MCP 出口；无 DB/密钥环境；Python 崩溃不影响 Java 主路径 | 独立开关；关闭时 Java 直连路径不变 |
| **W6-08 观测、审计与验收 harness** | `mcp.gateway.*` 指标、工具调用审计、告警；M-01–M-12 自动化 fixture；回滚演练；默认关闭验证 | W6-01–W6-07 | M-01–M-12 全部有 raw 证据；审计不含参数/结果/凭据；指标低基数；回滚演练成功 | 审计/指标失败时安全拒绝；回滚 = 关 Gateway |

**顺序建议：** W6-00 → W6-01 → W6-02 → W6-03/W6-04 可并行 → W6-05 → W6-06 → W6-07 → W6-08。W6-03 与 W6-06 都需要先完成对应人工决策；W6-07 不得在 W6-06 契约测试通过前接入任何执行路径。

**迁移约束：** 当前迁移到 V91；Lane A 预留 V92–V95，Lane D 预留 V96–V97。W6 若确实需要新列/表，**必须先向中心申请版本号**；不得直接占用保留区间，不得修改任何历史迁移。

---

## 6. 风险登记

| # | 风险 | 触发/影响 | 缓解 | 残余风险 |
| --- | --- | --- | --- | --- |
| R1 | DNS 重绑定 TOCTOU | 校验后解析改到私网/元数据，导致 SSRF | 连接期固定 IP + 正确 Host/SNI，或受控 egress proxy；M-02 回归 | 代理配置错误仍需部署检查 |
| R2 | 工具描述/Schema 提示注入 | 模型误选工具、参数误导、上下文污染 | 不可信结构化包装、限长/限深、净化；不把外部文本拼成系统指令 | 语义注入无法完全检测 |
| R3 | 在途撤权竞态 | 撤权后迟到结果被当作新事实 | 版本 fence、连接取消、结果前复核；`TOOL_REVOKED/UNKNOWN` | 网络在途不可撤回，只能标记未知 |
| R4 | 跨语言 Hash 规范化不一致 | Java/Python 对同一目录给出不同 Hash | 冻结规范化规则 + 双语言测试向量 + 逐字节 fixture | Unicode/JSON 边界仍需持续回归 |
| R5 | 内部令牌重放/静态令牌滥用 | Python 代表用户调用未授权项目 | 短期 Ed25519、`aud/sub/scopes/run/project` claims、来源网络；static 仅过渡 | 令牌被盗用仍需最短 TTL 与审计 |
| R6 | 迁移版本冲突 | 占用 A/D 预留版本，或修改历史迁移 | 先申请中心分配；只新增；空库/生产快照前向验证 | 发布顺序与旧镜像兼容需演练 |
| R7 | 连接/项目绑定模型漂移 | 设计说“项目级白名单”，代码已删除 binding 表 | D3 明确：project 级连接即绑定，或重新引入共享连接 + binding | 旧数据迁移与权限语义需评审 |
| R8 | Python Host 进入同步主路径 | 增加一跳、延迟与故障面，难以证明权限边界 | 首版不进入主路径；仅契约/归一化/评测；如进入另立设计 | 产品压力可能导致路径变更 |
| R9 | 外部 Provider 合规/最小 scope 不足 | 误接写端点或过大 scope，数据越境/ToS 风险 | Provider 准入清单、官方只读端点、最小 token、人工审核 | 外部服务行为变化 |
| R10 | 结果持久化到历史/记忆 | 撤权/删除后旧正文残留 | 默认不持久化；如需持久化另做保留/删除设计 | Agent 历史现有行为需单独审计 |
| R11 | 现有测试被误读为 W6 安全 | “7 个套件全绿”被当成 G1–G7 已覆盖 | 本报告明确未覆盖场景；W6-08 增加 M-01–M-12 | 评审者仍可能只看绿测 |
| R12 | 默认开关被实现 PR 意外改变 | 生产行为提前切换 | W6-01 固定 `enabled=false`；CI/验收断言六个默认开关未变 | 部署 override 误配 |

---

## 7. 待决策

| # | 决策 | 推荐 | 不裁决的后果 | 阻塞 PR |
| --- | --- | --- | --- | --- |
| D1 | 内部身份用 Ed25519 短期服务令牌还是 mTLS | 先 Ed25519 + 来源网络；mTLS 作为后续强化 | static 只能证明服务身份，不能绑定 run/项目 | W6-06 |
| D2 | Python Host 是否进入 Java Agent 同步主路径 | 首版不进入；只做契约/归一化/评测 | 增加一跳、延迟与故障面，权限边界难证明 | W6-07 |
| D3 | 连接/项目绑定模型 | 明确“project 级连接即项目绑定”，或重新引入共享连接 + binding 表；不得两套并存 | 设计/代码漂移，白名单语义不清 | W6-02/W6-06 |
| D4 | W6 迁移版本号 | 先尝试复用现有列/JSONB；确需新列/表时向中心申请 | 占用 A/D 预留版本或改历史迁移 | W6-02 |
| D5 | 出网强制方式 | 固定解析 + Host/SNI，或受控 egress proxy；二选一并测试 | G1 无法关闭 | W6-03 |
| D6 | MCP Provider 准入清单与最小 scope | 只接官方只读端点与最小只读 token；记录 owner/数据范围/ToS | 误接写端点或过大 scope | W6-03/W6-06 |
| D7 | MCP 结果是否进入 Agent 历史/记忆 | 默认不持久化；如需要另立保留/删除设计 | 撤权/删除后旧正文残留 | W6-04/W6-08 |
| D8 | 在途撤权后的结果语义 | 结果前复核；撤权后 `TOOL_REVOKED` 或 `OUTCOME_UNKNOWN` | 迟到数据被当成新事实 | W6-05 |
| D9 | Python Host 依赖选择 | 优先官方 MCP Python SDK 的 Host/Client transport；若不引入，使用最小内部 JSON-RPC 客户端并更新依赖守卫 | 依赖膨胀或协议不兼容 | W6-07 |
| D10 | W3/W5 未清零时是否可开工 | **不可以**；保持 `BLOCKED` | 违反路线图与总包停止条件 | 全部 |

---

## 8. 实现验收门禁（未来，不执行于本波）

以下矩阵来自设计 §17；本报告**没有执行任何一项**。只有实现 PR 产出原始证据后，才可逐项标 `PASS`/`FAIL`。

| 编号 | 场景 | 预期 | 证据要求 |
| --- | --- | --- | --- |
| M-01 | HTTPS allowlist 外主机/私网/元数据地址 | 拒绝，零外部连接 | 原始请求日志 + 连接计数 |
| M-02 | DNS 在 validate 后改解析 | 连接仍被固定/代理拒绝 | 重绑定测试原始日志 |
| M-03 | 工具描述含 injection | 仅作为不可信数据，不改变系统规则 | 模型上下文快照/结构化包装 |
| M-04 | 结果含假 system/tool 指令 | 不可执行，结构化包装，warning | 结果清洗测试 |
| M-05 | Schema 变化 | 自动停用、审计、不可调用，等待人工确认 | 漂移测试 + 审计 |
| M-06 | 未确认/未白名单/未绑定工具 | 不暴露；直调被拒 | 目录/调用测试 |
| M-07 | Python 伪造 project/user | Gateway 以可信 run 上下文拒绝 | 跨项目/伪造 claims 测试 |
| M-08 | 撤权后的新调用/在途结果 | 新调用拒绝；在途结果 `revoked/unknown` | 竞态测试 + 审计 |
| M-09 | 429/timeout/5xx/部分结果 | 分类记录，不混算，不伪造成功 | 失败分类原始输出 |
| M-10 | 结果超 bytes/二进制/路径 | 截断/拒绝/脱敏，带标记 | 结果清洗测试 |
| M-11 | Python Host 与 Java `tools/list` | 逐项一致，Hash 一致 | 双客户端 fixture diff |
| M-12 | Gateway 关闭 | 回退现有 Provider；默认路径无行为变化 | 开关/回退测试 |

**附加门禁：**

- 六个生产默认开关全程不变；
- 不修改 V1–V91 历史迁移；新迁移版本由中心分配；
- 审计不含完整参数、结果、凭据、cookie、Prompt 或文档正文；
- Python 容器保持 `ai` internal 网络、无宿主端口、无 DB/Redis/MinIO 凭据；
- 前端不新增 MCP 写入口；MCP 永不进入审批写路径。

---

## 9. 回滚

1. **Gateway 开关回滚：** `agent.mcp.gateway.enabled=false`（名称待 W6-00 冻结）→ 回退现有 `McpAgentToolProvider`；旧已绑定只读工具语义保持不变。
2. **Python Host 回滚：** 独立开关关闭即可；Java 直连路径不受影响。首版不让 Python Host 进入主路径，因此无需回滚 Java Agent 执行链。
3. **数据回滚：** 新迁移只增列/表，不删除旧列；回滚镜像必须兼容新增的可空列/表。已确认 Hash 与审计记录不删除；回滚后重新确认真实目录，不自动信任旧 Hash。
4. **安全失败：** 撤权、审计写入、Schema 校验失败时安全优先，拒绝新调用；不以可用性为由继续使用旧目录。
5. **应急关闭：** 可停用连接或整体关闭 Gateway；内置工具与 Agent 主流程继续可用。

---

## 10. 证据与限定

### 10.1 本次已读

- `AGENTS.md`
- `docs/archive/handoffs/release-blocker-freeze-2026-11-12.md`
- `docs/archive/review/w6-preassessment-2026-11-12.md`
- `docs/archive/report/w3-5-blockers-20261112.md`（W6 前置部分与 W3.5 判定）
- `docs/design/w6-mcp-gateway-host-2026-11-12.md`
- `docs/design/python-ai-capability-plane-2026-11-11.md` §4/§6.3/§7.8/§9/§10
- 现有 Java MCP 源码与测试、Python worker 装配/配置、`ai-collab-deploy/docker-compose.yml`
- W5 终稿：`docs/acceptance-evidence/2026-11-12/w5-memory-c/report.md` 与 `raw/`（只读核对）
- W3 functional 原始 aggregate：`docs/acceptance-evidence/2026-11-12/w3-reranker-local/raw/functional/functional-summary.json`、`obs50-retry-summary.json`（首批 `obs50-summary.json` 保留为脚本失败证据）
- W4 终稿：`docs/acceptance-evidence/2026-11-12/w4-decide-final/report.md` 与 `raw/`

### 10.2 本次未做

- 未运行 Java/Python 测试（避免占用 `local-cpu` 资源锁）；
- 未启动后端、Python worker 或任何 MCP 服务；
- 未连接真实 MCP、未做 DNS 重绑定实验、未做 staging 测量；
- 未把 B 的 formal run 1 中间 FAIL 当作最终结论；未把 D 的 attempt 1 失败当作最终 C；D 终稿只核对证据，不重跑；
- 未修改任何代码、配置、迁移、前端或部署文件。

### 10.3 现有测试证据的时效性

§3.3 的 7 个套件结果来自 `docs/acceptance-evidence/2026-10-25/` 的历史运行，不是本次新执行结果；它们只证明当时基线的局部行为，不覆盖 G1–G7。任何实现 PR 都必须重新运行相关测试并保留新的原始输出。

---

## 11. 最终判定

### 11.1 当前判定

| 前置 | 状态 |
| --- | --- |
| W3 Lane B | `3S_FAIL / FUNCTIONAL_PASS`（30s 8/20/50 门禁 PASS；50 档 obs 60/60 补测确认） |
| Ed25519 | `PASS`（引用既有 staging 证据，不重做） |
| W5 C DREAMING 任务级 | `PASS`（LIVE_VERIFIED，LOW_CONFIDENCE） |
| W5 `sourceRef` 治理 | `PASS`（live `fact:project` + Testcontainers 矩阵） |
| W5 删除 500 修复 | `PASS`（staging 204 / 无残留 / 回归测试） |
| W4 decide（相邻，不计入） | `FAIL`：Gemini 历史 FAIL 保留；Magpie 3 轮 pooled `43/60=0.7167`、JSON `1.0`、5xx/504/429 全 0、`invalidToolCalls=0`；根因项目侧模型决策/prompt；不得写 `PASS` |

**生产 readiness 结论：`NOT_READY / BLOCKED`。** W3 3s 未达、W4 相邻 `FAIL`；W3 functional `PASS`、W5 `PASS`、Ed25519 `PASS` 不改变生产结论。

**Default-off 实现：** 只有用户明确批准时才可开始；本报告不自行授权。即使获批，也只能保持 Gateway 默认关闭、无生产调用、无 canary。

### 11.2 何时可以改为 READY_FOR_DEFAULT_OFF_IMPLEMENTATION

只有同时满足以下条件，才具备“申请 default-off 实现”的资格；仍需用户明确批准：

1. Lane B 给出 30s functional 8/20/50 `PASS`；3s `FAIL` 作为已知限制保留，报告明确生产用户路径仍 `NOT_VERIFIED`；
2. Lane D 终稿给出 C DREAMING 任务级 `PASS`、`sourceRef` 治理 `PASS`、删除 500 修复 `PASS`（已满足，见 §2）；
3. Ed25519 既有 `PASS` 证据仍可回查；
4. 终稿的原始 aggregate/JSONL/日志与报告结论一致，没有把 `NOT_VERIFIED`/`INCONCLUSIVE` 改成 `PASS`。

即使达到上述条件，也只有用户明确批准后才能按 §5 的 PR 计划做**默认关闭**的实现；**不表示 W6 已实现、已验证、可灰度、可启用或可 canary**；生产 readiness 始终不是 `READY`。G1–G7、D1–D9 仍需在实现 PR 中关闭；生产 canary 需要另立验收。

### 11.3 本次交付性质

本报告是 readiness 交付，不是实现交付。W6 仍未实现；本报告不构成生产开工许可。即使 §11.2 全部满足，也必须由用户明确批准 default-off 实现，且不得生产启用。

---

## 12. 变更记录

| 日期 | 变更 | 作者 |
| --- | --- | --- |
| 2026-11-12 | 初稿：只读核对代码/设计，建立前置表、PR 拆分、风险、待决策、验收与回滚骨架；W3/W5 保持 `PENDING_EVIDENCE`，判定 `NOT_READY / BLOCKED` | Lane E |
| 2026-11-13 | 定稿：W5 `PASS`（C 0.909、11/11 审批、泄露/冲突 0、sourceRef/删除 500 PASS）；W4 相邻 `FAIL`（Magpie pooled 0.7167，项目侧 prompt/policy）；W3 `3S_FAIL / FUNCTIONAL_PASS`（30s 8/20/50 PASS，50 档 obs 补测 60/60）；生产 readiness `NOT_READY / BLOCKED`，仅用户明确批准时可开始 default-off 实现 | Lane E |
