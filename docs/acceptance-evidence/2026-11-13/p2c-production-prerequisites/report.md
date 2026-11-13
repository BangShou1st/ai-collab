# P2-C 生产前置中心报告（2026-11-13）

> 基线：`dev @ 211e4e32`
> 性质：**readiness 取证，不是生产 canary**
> 结论：**NOT_READY**。未达到 `READY_FOR_CANARY_APPROVAL`。
> 生产动作：**0**。未启用 Gateway、未切流量、未执行 canary、未删除生产状态。

## 1. 结论先行

| 项 | 结果 |
| --- | --- |
| P2-C 总判定 | **NOT_READY** |
| Lane A 生产调用方/短令牌 | **BLOCKED_DECISION** |
| Lane B 生产密钥/证书链 | **NOT_VERIFIED** |
| Lane C Alertmanager 真实送达 | **NOT_VERIFIED** |
| Lane D Canary/回滚方案 | **PASS（仅方案完整）** |
| 生产 canary | **NOT_STARTED** |
| 是否可申请 canary 批准 | **否**；A/B/C 未闭环 |

**口径**：P2-C 只回答“是否可以申请生产 canary 批准”。当前答案是“不可以”。
本报告不代表已 canary、已灰度、已生产上线，也不代表生产 W6 ready。

## 2. 生产前置表

| Lane | 状态 | 关键事实 | 证据 |
| --- | --- | --- | --- |
| A 调用方/令牌 | **BLOCKED_DECISION** | 生产 compose 无 MCP Host/worker；Java Agent 仍走旧 `McpAgentToolProvider`；主代码没有 `mcp:list/mcp:call` 专用签发/刷新入口；mTLS 未实现；static 被生产闸门阻断 | [`lane-a/report.md`](lane-a/report.md) |
| B 密钥/证书 | **NOT_VERIFIED** | 无 `prod.env`；`TLS_*`/`INTERNAL_AI_ED25519_*` 未配置；仅本地自签 localhost 证书（2 天、链失败）；生产 key/cert 未注入 | [`lane-b/report.md`](lane-b/report.md) |
| C 告警送达 | **NOT_VERIFIED** | 7 条规则已加载；本地 Alertmanager→webhook 模拟成功；无生产 Alertmanager、通知渠道、值班人或送达回执；用户未接受风险 | [`lane-c/report.md`](lane-c/report.md) |
| D Canary/回滚 | **PASS（方案）** | 单项目/单连接/单只读工具、30 分钟起、300 次调用、5 个 run、停止/回滚阈值与角色完整；未执行 | [`lane-d/plan.md`](lane-d/plan.md) |
| E Testcontainers | **NOT_STARTED** | 可选、非阻塞；A/B/C/D 未满足前置，未启动 | 冻结记录 §8 |

## 3. 真实调用方/令牌证据

### 3.1 调用方事实

| 候选 | 是否存在生产路径 | 依据 |
| --- | --- | --- |
| Java Agent 内部调用 | **否** | `AgentToolRegistry` 的外部 Provider 仍是 `McpAgentToolProvider`；没有 Gateway 客户端或 `/internal/ai/v1/mcp` 调用点 |
| Python MCP Host | **否** | `mcp_host_app.py` 明确为测试进程；`compose.prod.yml` 没有 ai-worker/MCP Host |
| 外部服务 | **否** | 无外部调用方接入契约、mTLS 或来源网络白名单 |

结论：生产调用方 **未决定 / 未接线**。Lane A 的 17/17 是
`PROTOCOL_PROBE`，不是生产调用方，也不能证明用户侧路径。

### 3.2 短令牌路径

| 项 | 结果 |
| --- | --- |
| Ed25519 JWS 签发/验签原语 | PASS（`InternalTokenIssuer` / `InternalTokenVerifier`） |
| `internal/ai/v1/mcp` 的 `aud=backend` + MCP scope 校验 | PASS |
| TTL / 过期拒绝 | PASS；TTL 60–900s，默认 300s |
| 生产 MCP 专用 issuer | **缺失**：`InternalTokenProvider` 只签发 rerank/model-call/decide/plan；Job token 无 MCP scope |
| 生产调用方获取 token | **缺失**：没有已部署的签发入口或调用链 |
| mTLS | **未实现** |
| static 长期令牌 | 不能作为唯一方案；生产静态绑定被 `McpGatewayStaticTestGuard` 阻断 |
| `projectRef/runRef` | Gateway 校验并以真实 run/成员复核；但生产生成路径缺失 |
| `catalogVersion` | `tools/call` 强制且与 DB 目录比对；`tools/list` 允许缺失 |
| `jti` | 已签发但未做唯一性/重放校验，无 jti 黑名单 |

### 3.3 真实请求结果（本地协议探针）

基线 `211e4e32`，17 个 case 全部 PASS：

| 场景 | HTTP/结果 | 证据等级 |
| --- | --- | --- |
| 成功 `tools/call`（外部只读 Provider） | 200，`SUCCEEDED`，`untrusted=true` | `REAL_PROCESS` |
| 成功 `tools/call`（本地 fixture） | 200，`SUCCEEDED`，敏感字段脱敏 | `REAL_PROCESS` |
| 过期 token | 401，`UNAUTHORIZED` | `REAL_PROCESS` |
| 篡改签名 | 401，`UNAUTHORIZED` | `REAL_PROCESS` |
| 错误 scope（仅 `mcp:list` 调 `tools/call`） | 401，`UNAUTHORIZED` | `REAL_PROCESS` |
| 未授权/未绑定/跨租户 | `TOOL_DISABLED` / 拒绝 | `REAL_PROCESS` |
| Hash 漂移 | `SCHEMA_DRIFT` | `REAL_PROCESS` |
| 撤权在途 | `TOOL_REVOKED`；新调用 `SCHEMA_DRIFT` | `REAL_PROCESS` |
| 本地延迟（100 样本） | P50 49.047ms / P95 126.763ms / P99 132.961ms | `REAL_PROCESS` |

原始证据：[`lane-a/raw/real-process.json`](lane-a/raw/real-process.json)。
定向 Java 测试：62/62 + 22/22 PASS。

## 4. 证书/TLS 证据

| 项 | 结果 | 说明 |
| --- | --- | --- |
| 生产证书/私钥注入 | **NOT_VERIFIED** | 无 `prod.env`，`TLS_CERT_FILE`/`TLS_KEY_FILE` 未设置 |
| 生产 Ed25519 key/kid/公钥 | **NOT_VERIFIED** | 无安全注入实例；仅配置闸门存在 |
| 生产证书链/hostname/有效期 | **NOT_VERIFIED** | 无生产域名与 fullchain；现有脚本只校验 leaf，不做 CA 链验证 |
| 本地 TLS 协议模拟 | PASS（`LOCAL_SIMULATION`） | 同一份 `production.conf` 下 TLS 1.2/1.3 = 200，TLS 1.1 失败，hostname 不匹配失败 |
| 本地自签材料 | 仅模拟 | RSA 2048、SAN `localhost/127.0.0.1`、2 天、自签、链构建失败 |
| 私钥权限 | **NOT_VERIFIED** | `install.sh`/`prod-config-check` 不校验 TLS key mode/owner；本地测试 key ACL 过宽 |
| 仓库/本地日志泄漏 | PASS（仅 tracked repo + `target/` 扫描） | 无 tracked 私钥/证书私钥；生产日志/崩溃信息未取得 |
| `prod-config-check` | PASS（6/6，容器） | 只证明格式/配对/SAN/有效期闸门，不证明生产链已注入 |

**Lane B 不能写 PASS**：没有真实生产证书/私钥，也没有生产等价注入环境。

## 5. Alertmanager 送达证据

| 项 | 结果 | 说明 |
| --- | --- | --- |
| Prometheus 规则语法 | PASS | `promtool` 7 条 |
| Prometheus 规则实际加载 | PASS | 隔离 Prometheus `groups=1`、`rules=7`，全部 `health=ok` |
| Alertmanager 本地管道 | PASS（仅 `LOCAL_SIMULATION`） | 合成告警 `P2CLocalAlertPipelineProbe`，本地 webhook 收到，延迟 61.529s，指纹 `0788a407078f799c` |
| 生产 Alertmanager/通知渠道 | **NOT_VERIFIED** | 无生产实例、无 webhook/邮件/IM 凭据、无值班路由 |
| 真实值班人送达回执 | **NOT_VERIFIED** | 没有生产测试告警与接收确认 |
| 用户接受人工监控风险 | **未接受** | `ACCEPTED_RISK` 模板未签署 |

**Lane C 不能写 PASS**：本地 webhook 不是值班渠道；合成 `vector(1)` 规则不是
生产 MCP 规则。

## 6. Canary/回滚方案与 READY 结论

Lane D 方案完整，覆盖：

- 范围：单测试项目、单租户、单只读 Provider、单连接、单工具；
- 样本：30 分钟起 / 60 分钟计划，300 次可分类 `tools/call`，5 个 run，6 个 5 分钟桶；
- 指标：成功率、timeout/429/5xx、schema drift、撤权、审计行、用户侧延迟、inflight、egress；
- 停止/回滚阈值：错误率、P95、inflight、未授权/跨租户、drift、审计失败、结果未知；
- 回滚：关闭 `AGENT_MCP_GATEWAY_ENABLED`、滚动重启、404 验证、旧 `McpAgentToolProvider` 可执行、保留审计/Schema；
- 角色：批准人、指挥官、观察人、回滚操作人、安全复核人、Provider owner；
- 批准门禁：用户必须明确回复“批准 canary”。

但方案完整不等于可以执行。当前 **A/B/C 未闭环**，且用户尚未批准，故：

```text
P2-C = NOT_READY
生产 canary = NOT_STARTED
READY_FOR_CANARY_APPROVAL = 未达成
```

## 7. 缺口与决策人

| # | 缺口 | 决策/提供人 | 最小下一步 |
| --- | --- | --- | --- |
| 1 | 生产调用方未定 | 产品/架构 owner | 书面选择 Java Agent/Python Host/外部服务，定义调用链与故障边界 |
| 2 | MCP 专用 token 签发/刷新/撤权路径缺失 | 后端 owner | 设计并实现绑定真实 `projectRef/runRef/catalogVersion` 的 issuer；明确谁调用、如何传递 |
| 3 | `jti` 无重放校验 | 安全 owner | 增加 jti 必填/短期去重缓存，或书面接受短 TTL + 只读工具风险 |
| 4 | `tools/list` 不强制 `catalogVersion` | 后端 owner | 决定是否要求 list 绑定目录版本；要求则改 `McpGatewayIdentity` 与测试 |
| 5 | 真实 TLS 证书链/私钥缺失 | 运维/平台 + 域名 owner | 在生产等价环境安全注入 fullchain/privkey，验证链、hostname、有效期 |
| 6 | 真实 Ed25519 key/kid/公钥缺失 | 后端 + 运维/安全 | 经 Secret Manager/部署平台注入；验证启动失败不回退 static |
| 7 | 私钥权限与 fullchain 闸门不足 | 安全/运维 | 明确 key mode/owner；补 `openssl verify`/fullchain 与权限检查 |
| 8 | 生产 Alertmanager/值班渠道缺失 | SRE/团队负责人 | 部署 Alertmanager、配置接收端、发真实测试告警并留回执 |
| 9 | 用户未接受人工监控风险 | 用户 | 若不接 Alertmanager，签署 `lane-c/accepted-risk-template.md` |
| 10 | Canary 具名角色/范围未填 | 产品/工程负责人 | 填 `CANARY_*` 变量并评审；用户明确“批准 canary”后才可执行 |

## 8. 提交、CI、分支与清理

| 项 | 状态 |
| --- | --- |
| 代码/证据改动 | 本轮 P2-C 文档、Lane A harness 过期用例、D 方案 SQL 修正 |
| 提交 | `860f7939`（`dev`） |
| CI | `43457746614` **success** @ `860f7939`；backend 三分片、Maven verify、frontend、Python、P0 contracts 全 success；Image/Publish 按 dev 配置 skipped |
| `main` | 未动 |
| force push / rewrite | 无 |
| worktree | 仅主工作树 |
| 临时容器/数据库 | Lane A 临时 DB 已删除；Lane C `p2c-*` 容器/网络已回收 |
| Gateway | 保持 `false`；未启用 |
| Canary | 未执行 |

## 9. 最终口径

```text
P2-C 结果：NOT_READY
原因：Lane A BLOCKED_DECISION、Lane B NOT_VERIFIED、Lane C NOT_VERIFIED
可申请 canary 批准：否
已执行 canary：否
已灰度/上线：否
```

只有 A/B/C 全部 PASS（或 C 有用户明确 `ACCEPTED_RISK`）且 D 方案完整，
才可改为 `READY_FOR_CANARY_APPROVAL`；即使达到该状态，也不等于已 canary。
