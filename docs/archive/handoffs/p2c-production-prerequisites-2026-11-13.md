# P2-C 生产前置冻结（2026-11-13）

> 状态：**NOT_READY / READINESS_ONLY**
> 基线：`dev @ 211e4e32`（以实际 HEAD 为准）
> 目标：把 P2-B2 的 `CONDITIONAL PASS` 推进到 `READY_FOR_CANARY_APPROVAL`
> 边界：**不执行 canary、不打开生产流量、不启用 Gateway**

## 1. 目标与非目标

P2-C 只回答一个问题：**现在是否具备申请生产 canary 批准的前置条件**。

允许的终态只有两个：

- `READY_FOR_CANARY_APPROVAL`：Lane A/B/C 全部 PASS，或 Lane C 有用户明确
  接受的 `ACCEPTED_RISK`；Lane D 方案完整；仍未执行 canary。
- `NOT_READY`：任一 Lane 为 `BLOCKED_DECISION` 或 `NOT_VERIFIED`，并明确列出
  缺口、决策人和下一步。

非目标与硬约束：

- 不执行 canary、不切生产流量、不创建生产数据、不删除生产状态；
- `agent.mcp.gateway.enabled` 保持 `false`；
- 不改 W4 Prompt v5、不改 W3 inline 8、不改六个默认开关；
- 不碰 `main`、不 force push、不改写历史；
- 密钥/证书只允许安全注入，不进入仓库、日志、报告或截图；
- 最终只保留 `dev`、`main` 和主 worktree；
- 任何试图启用生产 Gateway 或切换流量的动作立即停止。

## 2. Lane 目标

| Lane | 目标 | 主资源锁 | 证据目录 |
| --- | --- | --- | --- |
| A | 确定生产调用方与短令牌获取路径，验证 claims、TTL、撤权与在途语义 | `prod-like-env` | `docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/lane-a/` |
| B | 验证生产等价 key/cert、TLS 链、hostname、轮换、私钥权限与泄漏边界 | `prod-secrets` | `docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/lane-b/` |
| C | 验证 Prometheus → Alertmanager → 值班渠道真实送达 | `alerting` | `docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/lane-c/` |
| D | 冻结 canary 范围、观测、停止/回滚、角色与批准边界 | 无（只读仓库） | `docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/lane-d/` |

### Lane A：调用方与短令牌

必须回答：

1. 生产调用方是谁：Java 内部调用方、Python Host、外部服务，或尚未决定；
2. 短令牌由谁签发：Ed25519 issuer、mTLS、两者组合；static 长期令牌不得作为唯一方案；
3. claims 是否包含并校验 `projectRef/runRef/catalogVersion/aud/scope/exp/iat/jti`；
4. TTL、刷新、撤权、在途结果语义；
5. 生产等价客户端的成功、过期、篡改、错误 scope 请求与脱敏错误码。

若产品/架构未定，写 `BLOCKED_DECISION`；不得现场发明调用方或 token 获取接口。

### Lane B：生产密钥与证书

必须验证：

1. 真实 key/cert 在生产等价环境注入；
2. TLS 1.2/1.3、证书链、hostname、有效期；
3. 轮换流程与私钥权限；
4. 密钥不出现在日志、仓库、崩溃信息；
5. 无真实证书/私钥时写 `NOT_VERIFIED`，禁止用自签证书冒充生产 PASS。

### Lane C：Alertmanager 真实送达

必须验证：

1. Prometheus 规则已加载；
2. Alertmanager 接收端已配置；
3. 一条真实测试告警从 Prometheus 到值班渠道；
4. 送达时间、通道、接收人、告警内容可追溯；
5. 不接 Alertmanager 时只能由用户明确接受“人工监控 + 风险”，写
   `ACCEPTED_RISK`，不得写 PASS。

### Lane D：Canary 与回滚方案

只写方案，不执行：

- canary 范围：测试项目、单租户、只读工具；
- 持续时间与样本量；
- 成功率、timeout/429/5xx、schema drift、撤权、审计、用户侧延迟；
- 停止阈值与回滚阈值；
- Gateway 关闭及旧 `McpAgentToolProvider` 回退步骤；
- 批准人、观察人、回滚人；
- 生产 canary 必须等用户明确批准。

### Lane E（可选，非阻塞）

仅在 A/B/C/D 完成后处理 Testcontainers 共享容器/连接池资源耗尽；最小修复并
本机全量复跑。做不完写 `Deferred`，不阻塞 P2-C。

## 3. 资源锁

| 锁 | 范围 | 规则 |
| --- | --- | --- |
| `prod-like-env` | 生产等价环境、真实进程、独立端口/数据库 | 独占；Lane A 持有，释放后其他 Lane 才可复用 |
| `prod-secrets` | 私钥、证书、Secret Manager、权限检查 | 独占；只允许元数据/指纹检查，不输出私钥内容 |
| `alerting` | Alertmanager、Prometheus 规则、通知渠道 | 独占；Lane C 持有，禁止两个 Lane 同时改配置 |
| `canary` | 生产 canary | **P2-C 禁止使用** |

发生冲突时排队，不并行硬抢；任何 Lane 不得自行解除其他 Lane 的锁。

## 4. Canary 准入检查表

以下全部满足才可提交用户批准：

- [ ] Lane A 调用方、签发方、claims、TTL/刷新/撤权、在途结果语义明确；
- [ ] 生产等价成功请求与过期/篡改/错误 scope 负向请求有脱敏证据；
- [ ] Lane B 真实证书链、hostname、有效期、轮换与私钥权限已验证；
- [ ] 密钥未进入仓库、日志、报告、崩溃信息；
- [ ] Lane C 告警真实送达值班渠道，或用户书面接受人工监控风险；
- [ ] Lane D canary 范围、样本、指标、停止/回滚阈值、角色完整；
- [ ] 当前 `dev` CI 全绿，镜像 digest 与源码 SHA 可追溯；
- [ ] `agent.mcp.gateway.enabled=false` 默认值未改，旧 Provider 回退可用；
- [ ] 外部 Provider 只读、最小 scope、allowlist、owner/ToS/retention 已审；
- [ ] 用户明确回复“批准 canary”。

## 5. 回滚条件

任一条件触发立即停止并回滚：

- 未授权/跨项目/跨租户调用成功；
- Schema drift 未自动停用或未审计；
- 审计写入失败、审计不可查询或审计内容含敏感正文；
- egress 出现私网/元数据连接或 allowlist 外连接；
- 错误率、P95、inflight 超过已冻结阈值；
- 调用结果无法区分 `NOT_EXECUTED/OUTCOME_UNKNOWN/PARTIAL/TRUNCATED`；
- 撤权后新调用仍成功，或在途结果被当作成功；
- 回滚演练失败、Gateway 关闭后旧 Provider 不可用；
- 任何需要改默认开关、`main` 或生产数据才能继续的动作。

## 6. 停止条件

- 无生产调用方/令牌方案：`BLOCKED_DECISION`，不 canary；
- 无真实证书/私钥：`NOT_VERIFIED`，不 canary；
- 无 Alertmanager 且用户未接受风险：`NOT_VERIFIED`，不 canary；
- 任何试图打开 Gateway 或切流量的动作：立即停止；
- 需要改默认开关或 `main`：停止，交用户决策。

## 7. 证据与提交

- 证据根目录：`docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/`
- 每个 Lane 的结论必须包含：状态、证据等级、命令/时间、脱敏原始输出、缺口。
- 敏感值只记录指纹/长度/存在性，不记录内容。
- 所有改动提交到 `dev`；不创建长期分支，不保留临时 worktree。

## 8. 执行结果（2026-11-13）

| Lane | 结果 | 说明 |
| --- | --- | --- |
| A 调用方/短令牌 | **BLOCKED_DECISION** | Ed25519 验签、17/17 协议探针（含过期/篡改/错误 scope）通过；生产调用方、MCP 专用令牌签发/刷新路径不存在 |
| B 密钥/证书链 | **NOT_VERIFIED** | 无真实生产证书/私钥；仅本地自签 localhost 材料；生产 key/cert 注入、链、hostname、轮换、权限未验证 |
| C Alertmanager | **NOT_VERIFIED** | 规则加载与本地管道模拟通过；无生产 Alertmanager/值班渠道/送达回执，用户未接受风险 |
| D Canary/回滚 | **PASS（方案完整）** | 范围、样本、指标、阈值、回滚与角色齐全；未执行 canary |
| E Testcontainers | **NOT_STARTED** | 可选且非阻塞；A/B/C/D 未全部通过，未启动 |

```text
最终状态：NOT_READY
canary：NOT_STARTED
READY_FOR_CANARY_APPROVAL：未达成
```

当前**不应请求 canary 批准**。必须先关闭 A/B/C；Lane D 不能替代前置。
