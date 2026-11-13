# P2-B2 功能安全补强与生产 readiness 总报告（2026-11-13）

> 基线：`dev @ 94672900` → 本轮代码基线 `66dbfa68`
> 证据：`docs/acceptance-evidence/2026-11-13/p2b2-release-readiness/`
> 结论：**Lane A PASS；Lane B CONDITIONAL PASS；Lane C PASS（1 项 Deferred）；
> Lane D PASS；生产 canary WAITING_FOR_CANARY_APPROVAL，未启动。**

## 1. Lane 结果

| Lane | 结果 | 关键交付 |
| --- | --- | --- |
| A W6-B 功能/安全 | **PASS** | V99 Provider 准入；外部只读 + 本地 fixture；租户隔离；Hash fence；审计/撤权/在途；12/12 真实进程 |
| B 生产 readiness | **CONDITIONAL PASS** | Ed25519 负向、Nginx 404/TLS、指标 + 7 条告警、Gateway-off 回滚、本地 P95 98.9ms；生产调用方/告警送达待确认 |
| C 收尾 | **PASS** | N5 Reranker 最小 UI；JMeter per-label 口径收口；Testcontainers 资源最小缓解；共享容器根治 Deferred |
| D 仓库盘点 | **PASS** | 对象/大文件/引用关系/备份方案；未删除、未 rewrite |

## 2. W6-B 安全矩阵

| 矩阵 | 结果 | 证据等级 |
| --- | --- | --- |
| M-01 私网/元数据/allowlist 外 | PASS | 沿用 P2-B1 `REAL_PROCESS` |
| M-02 DNS 重绑定 | PASS | 沿用 P2-B1 `REAL_PROCESS` |
| M-03 描述注入 | PASS | P2-A `REAL_PROCESS` |
| M-04 结果注入/敏感字段 | PASS | `REAL_PROCESS` |
| M-05 Schema 漂移停用 | PASS | P2-A + P2-B2 回归 `REAL_PROCESS` |
| M-06 白名单/未绑定 | PASS | `REAL_PROCESS` |
| M-07 伪造 project/user | PASS | P2-A `REAL_PROCESS` |
| M-08 撤权/在途 | PASS | `REAL_PROCESS` |
| M-09 429/timeout/5xx/partial | PASS | P2-A `REAL_PROCESS` |
| M-10 超大/二进制/路径 | PASS | P2-A `REAL_PROCESS` |
| M-11 双端目录/Hash | PASS | `REAL_PROCESS` |
| M-12 Gateway-off 回退 | PASS | P2-B1 + P2-B2 `REAL_PROCESS` |
| M-13 Provider 未审批拒绝 | PASS | `REAL_PROCESS` + `POSTGRES` |
| M-14 多 Server 边界 | PASS | `REAL_PROCESS` |
| M-15 租户隔离 | PASS | `REAL_PROCESS` |
| M-16 Provider 漂移 | PASS | `REAL_PROCESS` |
| M-17 撤权在途结果 | PASS | `REAL_PROCESS` |
| M-18 审计 fail-closed | PASS | `JAVA_UNIT`（真实进程未注入审计库故障） |

## 3. 生产 readiness

| 项目 | 状态 | 证据/边界 |
| --- | --- | --- |
| Ed25519 真实 verifier | PASS | 真实 Gateway；篡改/错误 scope 401；Java 37/37、Python 41/41 |
| Ed25519 生产密钥注入 | NOT_RUN | 需 Secret Manager/部署平台；prod-config-check 已强制格式 |
| Nginx/TLS | PASS（本地同配置） | `/internal/**`、`/actuator` 404；TLS 1.2/1.3；1.1 拒绝 |
| 指标 | PASS | requests/latency/tool_calls/denied/drift/audit/egress/inflight |
| 告警规则 | PASS（加载） | promtool 7 条；Prometheus API 7 条 |
| 告警送达 | NOT_VERIFIED | 仓库无生产 Alertmanager/通知渠道 |
| 回滚 | PASS | 真实 Gateway-off 9/9 checks |
| 本地延迟 | PASS | 100 样本 P50 49.672 / P95 98.901 / P99 105.435ms |
| 生产调用方/令牌获取 | BLOCKED_DECISION | compose.prod 无 MCP Host；Java Agent 未接 Gateway |
| 生产 canary | WAITING | 未启动 |

## 4. 关键代码/配置

- `V99__agent_mcp_provider_admission.sql`
- `McpProviderAdmission` / `McpAdmissionStatus` / `McpProviderType`
- `McpGatewayMetrics` + 7 条 Prometheus 告警规则
- `production.conf` 的 `/internal/` 显式 404
- `prod-config-check` 的 Ed25519/Gateway 配置闸门
- `RerankerAdminContent.vue` + `admin-capability-api.ts`

## 5. Canary 状态

```text
WAITING_FOR_CANARY_APPROVAL
```

生产动作 0。用户必须明确说“批准 canary”才能继续；且批准前需确认：

1. 生产调用方及 `projectRef/runRef/catalogVersion` 短令牌获取路径；
2. 告警送达值班渠道的证据。

## 6. 提交、CI、分支与清理

| 项 | 状态 |
| --- | --- |
| 冻结 | `5e5cbbaa` |
| Lane A 代码 | `ba23dd18` |
| Lane A 证据 | `5df9c596` |
| Lane B/C 代码/配置 | `66dbfa68` |
| Lane B 证据 | `caf0d28b` |
| 最终文档 | 本提交 |
| `main` | 未动 |
| force push / rewrite | 无 |
| worktree | 仅主工作树 |
| 临时进程/数据库 | 已回收 |

CI：`43449307527` **success** @ `39ac7fd5`；后端三分片 + Maven verify 汇总 +
Python + 前端 + P0 contract 全绿，Image job 按 dev 配置 skipped。本机全量
`mvnw verify` 受 1.86GiB Docker VM 资源抖动影响，不作为唯一判据。

### 6.1 本机门禁

| 门禁 | 结果 | 说明 |
| --- | --- | --- |
| Java MCP/准入/Ed25519 定向 | **PASS** | 85/85 |
| Java 全量 `verify` | **PARTIAL（存量抖动）** | 3018 tests，1 failure：`AgentPauseResumePostgresTest:478`；61 skipped；日志可见 Docker VM 连接/容器生命周期抖动 |
| 失败类单跑 | **PASS** | `AgentPauseResumePostgresTest` 21/21 |
| Python token verifier | **PASS** | 41/41 |
| Python Ruff/format | **PASS** | 全仓通过 |
| Python 全量 pytest | **PASS** | 1275 passed；先剔除宿主注入的 `SPEC_AGENT_TAVILY_API_KEY` |
| 前端 lint/typecheck/test/build | **PASS** | 0 errors / 70 既有 warnings；64 files / 493 tests；build PASS |
| prod-config-check 新 Ed25519 用例 | **PASS** | 1/1；全文件证书用例因本机无 `openssl` 未完整执行 |
| Prometheus rules/config | **PASS** | 7 rules；本地 Prometheus API 加载 7 条 |
| Nginx/TLS/内部 404 | **PASS** | 本地 Nginx 1.30.6 |
| CI | **PASS** | [run 43449307527](https://github.com/BangShou1st/ai-collab/actions/runs/43449307527) @ `39ac7fd5` |

## 7. Deferred

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| Testcontainers 共享容器/Service Mode | Deferred | 最小缓解已做，根治需独立基础设施任务 |
| JMeter 容量重跑 | Deferred | 原 per-label FAIL 保留，只做口径收口 |
| 生产 Provider 准入清单/法务 | Deferred | 需人工 owner/ToS/retention |
| 生产 Alertmanager 送达 | NOT_VERIFIED | 需生产通知通道 |
| 生产调用方/令牌获取 | BLOCKED_DECISION | 需架构/部署决策 |
| 仓库 rewrite | Not needed | 无值得重写的大孤儿对象；只交备份方案 |
