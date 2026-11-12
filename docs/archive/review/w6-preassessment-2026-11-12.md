# W6 前置评估评审：MCP Gateway + Python Host

> 日期：2026-11-12（Asia/Shanghai）
> 基线：`dev @ 79e4839b`（共同代码基线 `d4247c34`）
> 被评审文档：[W6 MCP Gateway + Python MCP Host 前置设计](../../design/w6-mcp-gateway-host-2026-11-12.md)
> 性质：**设计/风险评审**。本会话未改代码、未改配置、未改迁移、未碰服务器、未连接真实 MCP、未执行运行时验收。
> 判定：**设计评审 PASS；W6 实现 BLOCKED；运行时/灰度结论 NOT_VERIFIED。**

---

## 0. 结论

### 0.1 一句话结论

W6 的职责边界已经可以冻结：**Java Gateway 独占凭据、出网、工具目录、Schema、授权和审计；Python Host 只做协议/归一化且无外部出口**。但现有 Java MCP 实现仍有必须在实现前修掉的安全缺口（尤其 DNS 重绑定窗口、工具描述/Schema 注入面、在途撤权），且 W3/W5 前置条件未清零，因此**现在不能开始 W6 实现，更不能宣称可灰度**。

### 0.2 判定表

| 评审项 | 判定 | 证据等级 | 依据 |
| --- | --- | --- | --- |
| 职责与信任边界是否完整 | **PASS（设计）** | IMPLEMENTED（文档设计） | 设计 §2/§3 |
| 只读白名单、Schema/Hash、漂移 | **PASS（设计），实现待做** | IMPLEMENTED（现状代码审计） | 设计 §5/§6；`McpAdministrationService`、`McpAgentToolProvider` |
| 凭据、出网、SSRF | **PASS（设计），G1 未修** | IMPLEMENTED（现状代码审计） | 设计 §8；`McpEndpointPolicy`、`HttpMcpClientFacade` |
| 提示注入与结果清洗 | **部分覆盖，G2/G3 未修** | IMPLEMENTED（现状代码审计） | 设计 §9；`McpResultSanitizer` |
| 撤权、审计、失败语义 | **设计完整，G4 未修** | IMPLEMENTED（现状代码审计） | 设计 §10/§11 |
| 观测、降级、回滚、默认关闭 | **PASS（设计）** | IMPLEMENTED（文档设计） | 设计 §11/§12 |
| W6 实现前置条件 | **BLOCKED** | LIVE_VERIFIED / NOT_VERIFIED 混合 | 下方 §3 |
| W6 运行时安全与双客户端互通 | **NOT_VERIFIED** | 未执行 | 本会话无实现/服务器 |
| 生产灰度 | **未具备资格** | NOT_VERIFIED | 无实现、无 canary |

### 0.3 重要限定

- 本评审只审阅源码与既有文档，**没有运行任何 MCP 服务、Java 测试、Python 测试或 staging 探针**。
- “PASS（设计）”只表示设计边界自洽，不表示实现完成；不得把设计 PASS 外推为 W6 实现 PASS。
- 现有代码审计为 `IMPLEMENTED` 级别；没有 `TESTED`/`LIVE_VERIFIED` 的新证据。

---

## 1. 审阅范围与证据

### 1.1 必读材料

- `AGENTS.md`
- `docs/archive/handoffs/next-block-freeze-20261112.md`
- `docs/archive/report/w3-5-blockers-20261112.md`
- `docs/archive/report/w3-w5-consolidated-20261112.md`
- `docs/design/python-ai-capability-plane-2026-11-11.md` §4/§7.8/§9/§10
- `docs/design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md` 的权限/撤权/审计章节

### 1.2 代码事实（只读）

| 组件 | 已核对内容 |
| --- | --- |
| `McpAdministrationService` | 管理员权限、连接 CRUD、测试/发现、Schema Hash、启用前确认、审计 |
| `McpEndpointPolicy` | HTTPS、精确 host allowlist、user-info/fragment 拒绝、DNS 私网拒绝 |
| `HttpMcpClientFacade` | 不跟随重定向、响应字节上限、Bearer、超时、`Mcp-Session-Id` |
| `McpAgentToolProvider` | `readOnlyHint`、连接级/项目级白名单、执行前 Schema 校验、`isError -> UNKNOWN`、结果清洗 |
| `McpResultSanitizer` | `untrusted` 标记、敏感字段脱敏、控制字符清理、数组/字节上限、注入 warning |
| `McpConnectionManager` | 按 connection/version/schemaHash 缓存；停用/更新时失效 |
| `McpConnection` / V29 | `schemaHash`、`confirmedSchemaHash`、加密凭据、超时/结果上限 |

### 1.3 未执行的验证

- 未运行 `McpEndpointPolicyTest`、`McpResultSanitizerTest`、`McpAgentToolProviderTest` 等现有测试。
- 未启动后端或 MCP Server；未做 DNS 重绑定实验；未做真实 Provider 调用。
- 未验证 Python Host（当前仓库没有 W6 Host 实现）。

因此本评审不能给 W6 运行时安全、性能、可用性或双客户端互通任何 PASS。

---

## 2. 设计与代码对齐结论

### 2.1 已对齐

1. **Java 是权威安全边界**：现有凭据加密、HTTPS allowlist、只读声明、两级白名单、Schema 确认、审计都在 Java，符合“Python 不持密、不授权”的目标。
2. **只读不是仅靠 Prompt**：当前代码同时使用远端只读端点（运维约定）、`readOnlyHint=true`、连接白名单、项目白名单，方向正确。
3. **Schema 变化 fail-closed**：`schemaHash != confirmedSchemaHash` 时 `McpAgentToolProvider` 和 `McpConnectionManager` 都会拒绝；更新连接会清空确认值并停用。
4. **结果默认不可信**：`McpResultSanitizer` 已标记 `untrusted`，并对敏感字段/控制字符/大小做处理；设计将其强化为结构化不可信通道，方向正确。
5. **不把 `isError` 当成功**：当前代码把 MCP error 映射为 `UNKNOWN`，避免把“未证明执行”写成成功；这与设计的失败分类一致。

### 2.2 尚未对齐（必须作为实现前置）

| 编号 | 发现 | 严重度 | 代码证据 | 为什么不能留到实现后 |
| --- | --- | --- | --- | --- |
| F1 | DNS 校验与 HTTP 连接分离，存在 rebinding TOCTOU | **高** | `McpEndpointPolicy.validate()` 解析 DNS；`HttpMcpClientFacade.send()` 仍按 hostname 交给 `HttpClient` | 攻击者可在校验后改解析到私网/元数据地址；必须在实现前改成固定解析/egress proxy |
| F2 | 工具 `description` 与 `inputSchema` 原样进入 Agent 工具定义，未做上下文侧限长/净化 | **高** | `McpAgentToolProvider` 直接使用 `discovered.path("description")` 与 `inputSchema` | 外部描述/Schema 可注入模型或造成超大 Schema/DoS；设计 §9 的不可信包装必须在实现时落地 |
| F3 | Python Host 目前没有 Java Gateway 契约、内部身份或双客户端 fixture | **高** | 仓库仅有 Java MCP；`ai-collab-ai-worker` 无 W6 Host | 无法满足“两个客户端逐项一致”；必须先冻结契约再实现 |
| F4 | 撤权/停用会使缓存失效，但在途外部调用没有统一 fence/取消 | **中** | `McpConnectionManager.invalidate()` 只关闭缓存客户端；执行中的 HTTP 请求不被取消 | 撤权后迟到结果可能进入上下文；需 `catalogVersion + bindingVersion + lease` 结果前复核 |
| F5 | 聚合 Schema Hash 缺少逐工具 Hash 与目录版本 | **中** | `McpAdministrationService.discover()` 对排序后的完整工具数组计算 SHA-256；`McpConnection` 只有 `schemaHash`/`confirmedSchemaHash` | 难以做工具级漂移定位/审计，也无法支持双客户端逐工具比对 |
| F6 | 注入检测是正则 warning，不是边界 | **中** | `McpResultSanitizer` 的 `INJECTION` 正则只加 warning | 不能以“未命中”作为安全；结构不可信包装必须独立成立 |
| F7 | MCP 结果是否可持久化到 Agent 历史/记忆未决 | **中** | 设计当前只规定不写长期记忆；现有 Agent 历史可能保留工具输出 | 撤权/删除后旧正文仍可能出现；需产品与保留策略决策 |
| F8 | 只读端点与最小 scope 是运维约定，不是代码可证明事实 | **中** | `McpAdministrationService` 不验证远端是否 `/readonly` 或 token scope | 需要 Provider 准入清单与部署检查，不能仅靠 `readOnlyHint` |

### 2.3 没有发现的问题（正向确认）

- 当前 `STDIO` 被 `McpAdministrationService.validate()` 明确拒绝。
- 当前 `HttpMcpClientFacade` 不跟随重定向，并拒绝非 2xx；3xx 的 Location 不会自动执行。
- 当前 `MCP` 工具调用在 `McpAgentToolProvider` 内被标记为 `writesBusinessData=false`，不存在内置写路径。
- 当前管理操作和 Schema 变化会写审计；审计不包含凭据正文。

---

## 3. W6 实现前置条件核对

| # | 条件 | 状态 | 说明 |
| --- | --- | --- | --- |
| 1 | W3 真实模型 3s 预算 + 真实用户 canary | **未清零** | W3.5 最后为 tier 8 失败率 13.33%、429=0；canary 未做 |
| 2 | Ed25519 staging 双端联调 + 轮换 | **已清零** | 14/14 PASS，staging Live Verified |
| 3 | W5 Job 触发/recovery/候选发布闭环 | **未清零** | D8 `/v1/jobs` 仍 NOT_VERIFIED；dispatcher/recovery/审核发布未完成 |
| 4 | 内部调用身份与 run/project 绑定 | **待决策** | 设计建议 Ed25519 + 来源网络；static 仅过渡 |
| 5 | G1/G2/G4 设计与测试 | **未完成** | 本设计已列前置，不允许直接进入实现 |
| 6 | Provider/合规/最小 scope 审核 | **待人工** | 需 owner、数据范围、保留与 ToS 决策 |
| 7 | Gateway 默认关闭与旧 Provider 回退 | **设计已定义** | 需实现时用开关与回滚演练证明 |

**结论：W6 实现 BLOCKED。** 仅完成 W6 前置评估，不能把设计评审 PASS 当作实现开工许可。

---

## 4. 必须人工裁决的设计问题

| # | 问题 | 当前建议 | 不裁决的后果 |
| --- | --- | --- | --- |
| H1 | Python Host 是否进入 Java Agent 同步主路径 | 首版不进入；仅契约/归一化/评测 | 增加一跳、延迟和故障面，且难以证明权限边界 |
| H2 | 内部身份用 Ed25519 服务令牌还是 mTLS | 先 Ed25519 + 来源网络；mTLS 作为后续强化 | static 只证明服务身份，不能绑定 run/项目 |
| H3 | MCP Provider 准入清单和最小 scope | 官方只读端点 + 最小只读 token | 误接写端点或过大 scope |
| H4 | MCP 结果是否可进入 Agent 历史/记忆 | 默认不持久化；如需持久化另立保留/删除设计 | 撤权/删除后旧正文残留 |
| H5 | 在途调用撤权后的结果语义 | 结果前复核；返回 `TOOL_REVOKED`/`UNKNOWN` | 迟到的撤权前数据被当新事实 |
| H6 | W6 是否可在 W3/W5 未清零时开工 | 不可以 | 违反路线图与总包停止条件 |

---

## 5. 设计覆盖度核对

| 冻结要求 | 设计章节 | 结果 |
| --- | --- | --- |
| Java Gateway 与 Python Host 职责/信任边界 | §2/§3 | 已覆盖 |
| 只读工具白名单 | §5 | 已覆盖 |
| 工具 Schema/Hash/漂移 | §6 | 已覆盖；逐工具 Hash 为实现新增 |
| 参数校验 | §7 | 已覆盖；Python 不构成授权 |
| 内部 MCP 线协议/claims/错误 | §7.4 | 已覆盖；实现前需冻结 |
| 凭据与出网/SSRF | §8 | 已覆盖；G1 未修 |
| 提示注入清洗 | §9 | 已覆盖；结构不可信优先 |
| 撤权/审计 | §10 | 已覆盖；G4 未修 |
| 失败/降级/回滚 | §11 | 已覆盖 |
| 观测指标 | §12 | 已覆盖 |
| 默认关闭 | §0/§11/§14 | 已覆盖 |
| 前置条件/风险/人工决策 | §13/§14/§16 | 已覆盖 |
| 不做实现/服务器/真实 MCP | 全文/§15 | 已遵守 |

---

## 6. 最终判定

| 问题 | 答案 |
| --- | --- |
| W6 前置设计是否完成？ | **是**（设计文档 + 本评审） |
| 设计是否可进入实现？ | **否，BLOCKED** |
| 现有 Java MCP 安全边界是否完整？ | **否**：G1/G2/G4 等前置缺口未修 |
| 是否已实现 Java Gateway/Python Host？ | **否**，本波明确不实现 |
| 是否已验证双客户端互通？ | **NOT_VERIFIED** |
| 是否可宣称生产可灰度？ | **否** |
| 是否改变生产默认开关？ | **否** |
| 是否碰仓库代码/迁移/前端/服务器？ | **否**（仅新增本设计与评审两份文档） |

**评审结论：设计评审 PASS；实现 BLOCKED；运行时/灰度 NOT_VERIFIED。**
