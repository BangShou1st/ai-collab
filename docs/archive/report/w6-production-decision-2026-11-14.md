# W6 生产决策：冻结为 default-off，不进入生产（2026-11-14）

> 决策人：项目 owner（用户）
> 基线：`dev @ c1f2a9a4`
> 判定：**`DELIVERED / NOT_DEPLOYED`**（交付完成，未上生产）
> 性质：**范围决策，不是缺陷关闭**
> 生产动作：0。不申请 canary、不启用 Gateway、不接生产调用方、不注入生产证书、不接生产告警。

## 1. 决策

- W6 MCP Gateway / Python Host 的功能与安全交付就此冻结，保持 `agent.mcp.gateway.enabled=false`。
- 暂不进入生产，也不申请 `READY_FOR_CANARY_APPROVAL`；P2-C 的 `NOT_READY` 作为最终前置结论保留。
- W6 生产状态由 `NOT_READY / BLOCKED` 改为 **`CLOSED_AS_DEFAULT_OFF`**：不是修好了，而是本轮不上。
- 未来若要上生产，按 §4 重新开一轮立项，不在本线继续补。

## 2. 依据：P2-C 的三个卡点

| Lane | 结论 | 事实 |
| --- | --- | --- |
| A 生产调用方/短令牌 | `BLOCKED_DECISION` | `compose.prod.yml` 只有 `app`，无 worker/MCP Host；`AgentToolRegistry` 仍走旧 `McpAgentToolProvider`；无 `mcp:list/mcp:call` 专用签发入口；mTLS 未实现 |
| B 密钥/证书链 | `NOT_VERIFIED` | 无 `prod.env`、无真实证书/私钥；现有材料为本地自签 localhost（2 天、链失败） |
| C Alertmanager | `NOT_VERIFIED` | 7 条规则已加载、本地管道模拟通过；无生产 Alertmanager、值班渠道与送达回执 |
| D Canary/回滚 | `PASS（仅方案）` | 范围、样本、指标、阈值、回滚与角色完整，未执行 |
| E Testcontainers | `NOT_STARTED` | 可选非阻塞 |

**这不是代码回归。** 协议层证据是干净的：真实进程探针 17/17 PASS、Java 62/62 + 22/22、本地 100 样本 P95 126.763ms；P2-B2 安全矩阵 M-01～M-17 为 `REAL_PROCESS`。卡点是“生产环境与产品决策不存在”，不是“实现有洞”。

## 3. 已交付、可复用的资产

| 资产 | 证据等级 | 说明 |
| --- | --- | --- |
| Java Gateway（default-off）+ Python Host 契约 | `TESTED(REAL_PROCESS)` | `docs/api/mcp-gateway-internal-v1.md` |
| 三进程 E2E | `TESTED(REAL_PROCESS)` | P2-A 21/21；Java/Host 11 工具 hash 一致 |
| 安全矩阵 M-01～M-18 | `TESTED(REAL_PROCESS)` / `JAVA_UNIT` | 私网/元数据拒绝、DNS 重绑定、注入清洗、Schema 漂移、撤权在途、审计 |
| 生产等价只读验证 | `TESTED(REAL_PROCESS)` | Ed25519 verifier/issuer、Nginx `/internal/**` 404、TLS 1.2/1.3、7 条 Prometheus 规则、Gateway-off 回退 |
| 本地延迟 | `TESTED(LOCAL)` | 100 次调用 P95 98.901ms / P99 105.435ms |
| Canary/回滚方案 | 文档 | Lane D，未执行 |

## 4. 未来上生产的前置（重新立项时逐条验收）

| # | 前置 | 归属 | 现状 |
| --- | --- | --- | --- |
| 1 | 明确生产调用方（Java / Python Host / 外部）并接线 `AgentToolRegistry` | 后端 | 缺失 |
| 2 | MCP 专用 issuer：`mcp:list` / `mcp:call` scope、刷新路径；禁止 static 长期令牌 | 后端/安全 | 缺失 |
| 3 | `jti` 唯一性与重放校验（黑名单或缓存） | 后端 | 已签发、未校验 |
| 4 | `tools/list` 的 `catalogVersion` 策略（当前允许缺失） | 后端 | 待决策 |
| 5 | 真实证书链、hostname、有效期、轮换、私钥权限 | 运维/安全 | 仅本地自签 |
| 6 | 生产 Alertmanager + 值班渠道 + 真实送达回执，或书面 `ACCEPTED_RISK` | SRE/用户 | 缺失 |
| 7 | 多租户隔离与外部 Provider 准入的生产等价复核 | 后端/安全 | 有测试，无生产等价 |
| 8 | canary 批准、观察人、回滚人 | 用户 | 未指定 |

## 5. 不变式（本次收口后仍然成立）

- 默认开关未变：`RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`、`agent.mcp.gateway.enabled=false`。
- 未启用 Gateway、未切生产流量、未执行 canary、未写生产数据。
- `main` 未动；无 force push；无历史改写；证据文件未回写。
- 分支只保留 `dev` / `main` 与主 worktree。

## 6. 项目状态总账（2026-11-14）

| 波次 | 判定 | 说明 |
| --- | --- | --- |
| W3 Rerank | `INLINE_8_PASS / 3S_20_FAIL / FUNCTIONAL_PASS` | inline 固定 8 候选 / 3s；9/20/50 拒绝 inline 回退 Java；生产路径 `NOT_VERIFIED` |
| W4 Decide | **PASS（调优验收）** | Prompt `2026-11-13.5`：golden 20/20、JSON 20/20、invalidToolCalls 0；`DECIDE_MODE=java` 未变 |
| W5 Memory | **PASS** | C 10/11 > B 8/11 > A 4/11；11/11 人工审批，autoPublished=0 |
| W6 MCP | **`CLOSED_AS_DEFAULT_OFF`** | 功能与安全交付完成；生产未部署，是范围决策 |
| JMeter | 基线完成；per-label `FAIL` 保留 | 522,806 样本、0 error、AI stub overhead P95 6ms；容量上限未建立 |
| 生产上线 | **未发生** | 无 canary、无灰度 |

## 7. 证据索引

- P2-C 总报告：`docs/acceptance-evidence/2026-11-13/p2c-production-prerequisites/report.md`
- P2-C 冻结记录：`docs/archive/handoffs/p2c-production-prerequisites-2026-11-13.md`
- P2-B2 总报告：`docs/archive/report/p2b2-release-readiness-2026-11-13.md`
- P2-B2 canary 检查点（已被本决策取代）：`docs/archive/handoffs/p2b2-canary-checkpoint-2026-11-13.md`
- W4 连续调优：`docs/acceptance-evidence/2026-11-13/w4-continuous-tuning/report.md`
- W3 inline 边界：`docs/acceptance-evidence/2026-11-13/w3-inline-policy/report.md`
- W5 Memory C：`docs/acceptance-evidence/2026-11-12/w5-memory-c/report.md`
