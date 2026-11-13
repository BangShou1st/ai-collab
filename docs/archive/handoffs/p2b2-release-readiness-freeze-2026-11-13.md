# P2-B2 功能安全补强与生产 readiness 冻结（2026-11-13）

> 状态：**FROZEN / COMPLETE（canary 等待批准）**
> 基线：`dev @ 94672900`（以实际 HEAD 为准）
> 性质：在 P1 default-off、P2-A 真实进程安全矩阵、P2-B1 三项真实进程补证之上，
> 完成 W6-B 功能/安全补强与生产 readiness 证据；**不是**生产 canary 或上线批准。
> 证据根目录：`docs/acceptance-evidence/2026-11-13/p2b2-release-readiness/`

## 1. 目标与硬边界

本轮目标按顺序为：

1. **Lane A**：补齐外部只读 MCP Provider 准入、多 Server 边界、租户隔离、审计/撤权/在途结果、
   egress/TLS/allowlist、schema/hash 漂移与 default-off 证据。
2. **Lane B**：仅在 Lane A 通过后，完成生产 Ed25519 真实链路、TLS/ingress、监控告警、
   回滚演练、容量/延迟与 canary 前置检查。没有 A/B 证据不得启动 canary。
3. **Lane C**：N5 Reranker 管理 UI 最小版、JMeter per-label 口径收口、
   Java Testcontainers 资源耗尽定位与最小修复；任一项做不完标 `Deferred`，不阻塞 A/B。
4. **Lane D**：仓库只读盘点（对象/大文件/引用），只交报告与备份方案。

硬约束：

- 不改 W4 Prompt v5，不改六个生产默认开关；
- 不修改任何历史 Flyway 迁移；如需数据库变更只能新增 `V99+`；
- 不碰 `main`、不 force push、不改写历史；
- 仓库 111 MB 只做只读盘点，不做 `git filter-repo`、BFG 或历史重写；
- 任何模型调用只用 Magpie，不用 Gemini；不 logout、不重启、不改 provider；
- 生产 canary 必须由用户明确回复“批准 canary”后才能启动；
- 未经批准不得启用生产 Gateway、不得清空生产状态、不得关闭会话资源。

## 2. 冻结输入

| 输入 | 本轮用途 |
| --- | --- |
| `docs/design/w6-mcp-gateway-host-2026-11-12.md` | Gateway/Host 信任边界、安全矩阵、失败语义与生产前置条件 |
| `docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/report.md` | M-01/M-02/M-12 已为 `REAL_PROCESS PASS`，不得降级或改写 |
| `docs/acceptance-evidence/2026-11-13/p2b1-real-process-evidence/security-matrix-delta.md` | P2-A M-03～M-11 沿用；本轮只新增 W6-B/生产 readiness 证据 |
| `docs/archive/report/p1-default-off-2026-11-13.md` | default-off、旧 Provider 回退与 P1 边界 |
| `docs/acceptance-evidence/2026-11-13/w4-continuous-tuning/report.md` | Prompt v5 只读冻结；本轮不重跑、不修改 |
| `docs/api/mcp-gateway-internal-v1.md` | 内部 JSON-RPC、Hash、错误码与审计字段的唯一契约 |

## 3. Lane、分支、worktree 与写入范围

> 子 agent 只在自己的 worktree 写入并提交；不 push、不合并 `dev`、不修改主工作树。
> 中心负责 cherry-pick/合并、冲突处理、全量门禁、CI、证据复核与清理。

| Lane | 目标 | 分支 | worktree | 写入范围 | 证据目录 |
| --- | --- | --- | --- | --- | --- |
| A | W6-B 功能/安全补强 | `p2b2/lane-a` | `E:\project\ai-collab-worktrees\p2b2-lane-a` | Java Gateway、MCP 管理/准入、Python Host 契约、安全测试与本地 TLS fixture | `.../lane-a/` |
| B | 生产 readiness | `p2b2/lane-b` | `E:\project\ai-collab-worktrees\p2b2-lane-b` | Ed25519 真实链路、生产配置/ingress 模板、监控告警、回滚/容量脚本与证据 | `.../lane-b/` |
| C | 收尾项 | `p2b2/lane-c` | `E:\project\ai-collab-worktrees\p2b2-lane-c` | N5 前端最小 UI、JMeter 文档口径、Testcontainers 最小诊断/修复 | `.../lane-c/` |
| D | 仓库只读盘点 | 不建分支 | 主工作树只读 | `git count-objects`、大文件、引用关系；不删除/不 rewrite | `.../lane-d/` |
| Center | 冻结、集成、验收、canary 闸门 | `dev` | `E:\project\ai-collab` | 冻结文档、集成、最终报告、CI、清理 | `.../center/` |

Lane B 在 Lane A 安全矩阵未全绿前保持 `BLOCKED_BY_A`，不得启动真实生产身份或 ingress 变更。

实际执行说明：本轮由中心串行执行 A → B → C/D，在唯一主工作树完成写入；未保留
长期 Lane worktree 或临时分支。这样避免了共享迁移/证据目录的写冲突，满足最终只留
`dev`/`main` 与主 worktree 的清理要求。

## 4. 资源锁

| 资源 | 规则 |
| --- | --- |
| `repo-main` | 中心独占；子 agent 不进入主工作树写文件 |
| `p2b2-lane-a-process` | Lane A 的真实 Java/Python/MCP 进程独占；其他 Lane 不得抢同一组端口 |
| `p2b2-lane-b-process` | Lane B 的 Java/Python/ingress/observability 进程独占；A 未完成时锁定 |
| `p2b2-postgres` | 各 Lane 使用独立测试库 `ai_collab_p2b2_<lane>`；不得污染业务库或复用 P2-A 库 |
| `p2b2-tls` | 各 Lane 使用临时自签证书与独立 truststore；私钥只放 `target/`，不提交 |
| `magpie-auth` | 中心独占；不 logout/重启/改 provider；预计本轮不调用模型 |
| `staging` | 单 Lane 写操作；Lane B 只在 A 通过后申请，且不得启用生产 Gateway |
| `production` | **锁定**；只有用户明确“批准 canary”后由中心解锁只读前置检查 |

冲突一律排队，不并行硬抢；所有临时进程退出后必须核验端口、数据库与临时证书已回收。

## 5. Lane A 完成口径

### 5.1 功能/安全必达

| 编号 | 必达项 | 最低证据 |
| --- | --- | --- |
| A-01 | 外部只读 Provider 准入：HTTPS、只读端点、最小 scope、owner/data-scope/retention/ToS 元数据与人工审核状态 | 管理 API + 持久化 + Java 集成测试 |
| A-02 | 多 Server 边界：至少 1 个外部只读 Provider 与 1 个本地 fixture 同时存在，工具命名空间/连接归属不串线 | 真实进程或明确 `CONFIG` 证据 |
| A-03 | 租户隔离：项目 A/B、run/project 绑定、跨项目连接/工具/目录不可见、不可调用 | DB + 服务级负向测试；有真实进程则标 `REAL_PROCESS` |
| A-04 | 审计、撤权、在途结果：撤权后新调用拒绝；在途结果返回 `TOOL_REVOKED`/`OUTCOME_UNKNOWN`；审计无参数/结果正文 | `REAL_PROCESS` 或明确 `JAVA_UNIT` |
| A-05 | egress/TLS/allowlist：私网/元数据/allowlist 外零连接；TLS 校验不关闭；不跟随重定向 | 沿用 P2-B1 真实证据 + 新增 Provider 准入测试 |
| A-06 | schema/hash 漂移：逐工具 Hash、目录版本、工具增删/描述/Schema 变化立即停用并要求人工确认 | 管理 discover + Gateway 拒绝 + 审计 |
| A-07 | default-off 不变：`agent.mcp.gateway.enabled=false`，旧 Provider 回退 | 配置/真实启动证据；不得修改六个默认开关 |
| A-08 | 证据等级诚实：每项标 `REAL_PROCESS`/`JAVA_UNIT`/`CONFIG`，不得用单测冒充真实进程 | 报告矩阵 + raw |

### 5.2 安全矩阵

P2-A/P2-B1 的 M-01～M-12 继续保持；本轮新增或加强：

- `M-01/M-02` 不重复造证据，引用 P2-B1 raw SHA-256；
- `M-03～M-11` 继续要求有真实进程证据或明确标注降级原因；
- `M-13` 外部 Provider 准入拒绝（未审核/非只读/超 scope/数据范围缺失）；
- `M-14` 多 Server 命名空间与项目隔离；
- `M-15` 租户/run/project 伪造与跨项目调用拒绝；
- `M-16` Provider 元数据/Schema 漂移后停用与审计；
- `M-17` Provider 撤权/停用后的在途结果语义；
- `M-18` 审计失败 fail-closed，零外部调用或结果不返回成功正文。

任一项 FAIL 或关键证据 `NOT_RUN`，Lane A 判 `FAIL`，立即停止，不进入 Lane B。

## 6. Lane B 完成口径

只在 Lane A 全绿后开始。至少包含：

| 编号 | 项目 | 达标线 |
| --- | --- | --- |
| B-01 | 生产 Ed25519 真实链路 | 真实 Java 签发、真实 Python Host 验签、project/run/catalog/scope claims 绑定；错误 audience/kid/过期/篡改均拒绝 |
| B-02 | TLS/ingress | 内部端点不暴露公网；TLS 终止/转发头/健康检查/上传限制与超时明确；无明文静态 token 作为长期唯一身份 |
| B-03 | 监控/告警 | 至少覆盖 requests、latency、denied、schema_drift、egress_blocked、inflight、revocation_age、circuit_state；告警阈值与 runbook 可执行 |
| B-04 | 回滚演练 | `enabled=false` 一键回退旧 Provider、连接/缓存清理、审计保留；演练结果 PASS |
| B-05 | 容量/延迟 | 本地或 staging 容量基线，记录 P50/P95/P99、并发、错误率、超时与首个饱和点；不外推生产容量 |
| B-06 | canary 前置 | 检查表、观测窗口、停止条件、回滚命令、责任人与用户批准记录齐备 |

生产身份/TLS/告警/回滚任一失败，B 判 `FAIL`，不得 canary。

## 7. 生产 canary 检查表（未批准前保持关闭）

### 7.1 启动前

- [ ] Lane A 安全矩阵全绿，Lane B B-01～B-06 全绿；
- [ ] 当前 `dev` CI 全绿，提交与镜像 digest 可追溯；
- [ ] `agent.mcp.gateway.enabled=false` 为默认值，旧 Provider 回退演练 PASS；
- [ ] 生产 Ed25519 密钥由安全渠道注入，私钥不入仓、不入日志、不出现在证据；
- [ ] 生产 ingress 只暴露 `/internal/ai/v1/mcp` 给内部网络，不接浏览器 JWT；
- [ ] 外部 Provider allowlist 精确 host、HTTPS 443、只读端点、最小 scope、owner/ToS/retention 审核完成；
- [ ] 项目/run/catalog/scope/白名单/Hash 全部人工确认；
- [ ] 监控面板、告警、审计查询、trace 关联可用；
- [ ] 回滚脚本/命令、值班人、观测窗口、停止阈值已冻结；
- [ ] 用户明确回复“批准 canary”。

### 7.2 canary 观测窗口

- 小流量、单个非关键项目、单个只读 Provider；
- 每 5 分钟检查请求数、错误率、P95、denied、schema_drift、egress_blocked、inflight、审计写入；
- 任一安全事件或 SLO 越线立即停止并回滚；
- 不自动扩项目、不自动扩 Provider、不自动确认新 Schema。

### 7.3 立即回滚条件

- 任一未授权/跨项目/跨租户调用成功；
- 审计写入失败仍返回成功正文；
- Schema 漂移未停用或自动放行；
- TLS/allowlist 绕过、私网/元数据连接尝试；
- 撤权后仍返回成功结果；
- 错误率 > 1%、P95 超过冻结预算、5xx/429 异常激增；
- 外部 Provider 状态不明或合规问题无法确认。

回滚动作：关闭 Gateway → 清理连接/目录缓存 → 保留审计 → 验证旧 Provider → 记录事故与证据。

## 8. Lane C 优先级与 Deferred 规则

| 顺序 | 项目 | 完成口径 | Deferred 条件 |
| --- | --- | --- | --- |
| C-1 | N5 Reranker 管理 UI 最小版 | 管理端只读列表/状态/测试/发布/回滚/停用入口；不改 `RERANK_MODE=java` | A/B 未通过或需要大改架构 |
| C-2 | JMeter per-label 口径收口 | 已有报告和阈值原样保留，明确 FAIL/噪声/复测边界；不重跑容量 | 时间不足；只交文档不阻塞 A/B |
| C-3 | Testcontainers 资源耗尽 | 最小复现/定位/修复或明确根因；不全量重构 | 环境不可复现；标 Deferred |

Lane C 只能在 Must-have A 不降级的前提下并行；不得为了 UI/性能项阻塞 A/B。

## 9. Lane D 仓库只读盘点

允许：

- `git count-objects -vH`、`git rev-list --objects --all`、`git ls-tree`、文件大小/扩展名统计；
- 识别大文件路径、历史引用、tag/branch/worktree 关系；
- 输出备份与后续维护建议。

禁止：

- `git filter-repo`、BFG、`git gc --aggressive` 之外的任何 rewrite；
- 删除对象/分支/tag、force push、修改 `main`；
- 在盘点阶段清空 reflog 或打包不可逆操作。

如瘦身必须 rewrite，停止并另开维护会话。

## 10. 停止条件

1. A 的安全矩阵失败：停，不进入 B。
2. B 的生产身份/TLS/告警/回滚失败：停，不 canary。
3. 用户未批准 canary：停在 `WAITING_FOR_CANARY_APPROVAL`。
4. 仓库瘦身需要 rewrite：停，另开维护会话。
5. 时间不足：先保 A，再保 B；C/D 标 `Deferred`。
6. 需要修改 W4 Prompt、六默认开关、历史迁移、`main`：停止并交用户决策。

## 11. 当前判定

```text
P2-B2：COMPLETE（until canary checkpoint）
Lane A：PASS
Lane B：CONDITIONAL PASS（本地 readiness；生产未应用）
Lane C：PASS（1 项 Deferred）
Lane D：PASS（只读）
Production canary：WAITING_FOR_CANARY_APPROVAL
W6 production readiness：READY_FOR_APPROVAL_WITH_PREFLIGHT_BLOCKERS
```
