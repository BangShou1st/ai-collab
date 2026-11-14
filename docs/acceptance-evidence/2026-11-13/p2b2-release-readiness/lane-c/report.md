# P2-B2 Lane C 收尾报告

> 日期：2026-11-13（Asia/Shanghai）
> 性质：N5 管理 UI、JMeter 口径、Testcontainers 最小诊断
> 结论：**N5 PASS；JMeter 文档收口 PASS；Testcontainers 最小缓解 PASS，
> 共享容器根治 Deferred。**

## 1. 结果表

| 项目 | 状态 | 证据等级 | 结论 |
| --- | --- | --- | --- |
| N5 Reranker 管理 UI 最小版 | **PASS** | `FRONTEND_TEST` + `TYPECHECK` | 在「AI 供应商」新增 Reranker Tab：档案列表、状态、测试、发布、回滚、停用、全局 RERANK 绑定/解绑 |
| JMeter per-label 口径收口 | **PASS** | `DOC_ONLY` | 原 per-label FAIL 保留；不重跑容量、不改阈值 |
| Testcontainers 资源耗尽最小修复 | **PASS（缓解）** | `POSTGRES_TEST` | test-only 收敛 Hikari 与 Spring Context cache；43/43 定向集成测试通过 |
| Testcontainers 共享容器/Service Mode 根治 | **Deferred** | — | 需要另开测试基础设施任务，不在 Lane C 扩大范围 |

## 2. N5 Reranker 管理 UI

### 2.1 功能

页面入口：`/admin/ai-providers` → 「Reranker 管理」Tab。

能力：

- 只展示 `capability=RERANK` 的档案；不把 CHAT/EMBEDDING 混入；
- 展示 `DRAFT / TESTED / PUBLISHED / DISABLED` 状态、Provider、协议、版本与关键参数；
- 测试：query + 两条可编辑候选文档 + topK，显示 outcome、总耗时、overhead 与分数；
- 仅 `TESTED` 档案显示「发布」；`PUBLISHED` 显示「回滚」；非 `DISABLED` 可「停用」；
- 显示全局 `KNOWLEDGE_RERANK` 绑定；可从已发布档案绑定，也可解绑；
- 不修改 `RERANK_MODE=java`，不新增后端路由或依赖。

### 2.2 改动

| 文件 | 作用 |
| --- | --- |
| `ai-collab-frontend/src/api/admin-capability-api.ts` | capability profiles / test / publish / rollback / disable / assignments 封装 |
| `ai-collab-frontend/src/modules/admin/components/RerankerAdminContent.vue` | 最小管理页 |
| `ai-collab-frontend/src/modules/admin/components/RerankerAdminContent.test.ts` | 列表过滤与发布动作测试 |
| `ai-collab-frontend/src/modules/admin/AdminAiProvidersView.vue` | 增加第三个 Tab，不修改共享导航 |

### 2.3 验证

| 命令 | 结果 |
| --- | --- |
| `pnpm vitest run src/modules/admin/components/RerankerAdminContent.test.ts` | PASS，2/2 |
| `pnpm lint` | PASS，0 errors / 70 既有 warnings |
| `pnpm typecheck` | PASS |
| `pnpm test` | PASS，64 files / **493 tests** |
| `pnpm build` | PASS |

## 3. JMeter per-label 口径

新文档：
[`docs/archive/report/perf-baseline-per-label-closure-2026-11-13.md`](../../../../archive/report/perf-baseline-per-label-closure-2026-11-13.md)

收口结论：

- 原门禁 FAIL 保留：项目详情 `4ms → 5ms（+25%）`；
- 场景级 `+7.9%` 不能替代 per-label；
- follow-up `6/5/7ms` 未回到 4ms，仍为 FAIL / not a clean pass；
- 1ms 计时粒度与 run variance 是主要解释，但没有证据证明绝不是回归；
- 不重跑容量、不改 `thresholds.csv`、不外推生产。

`tests/performance/jmeter/README.md` 已增加指向该收口文档的说明。

## 4. Testcontainers 最小诊断

新文档：
[`docs/archive/research/testcontainers-resource-exhaustion-2026-11-13.md`](../../../../archive/research/testcontainers-resource-exhaustion-2026-11-13.md)

环境事实：

```text
Docker VM: 8 vCPU / 1,995,673,600 bytes ≈ 1.86 GiB
既有容器：postgres / redis / minio
```

判断：多个 Spring TestContext 缓存默认 Hikari max=10，叠加大量独立 PostgreSQL
Testcontainers；在 1.86GiB Docker VM 上出现资源放大，导致随机
`CannotGetJdbcConnectionException`。失败类单独重跑通过、CI 分片全绿，不支持固定代码缺陷。

最小修复（仅 test profile）：

```properties
spring.datasource.hikari.maximum-pool-size=4
spring.datasource.hikari.minimum-idle=0
spring.test.context.cache.maxSize=12
```

验证：

| 命令 | 结果 |
| --- | --- |
| `mvnw -Dtest=McpAdministrationPostgresIntegrationTest,AgentRepositoryIntegrationTest test` | PASS，43/43；含真实 PostgreSQL 容器与 Flyway V99 |

边界：

- 这是资源收敛，不是共享容器/Service Mode 根治；
- 本机全量 `mvnw verify` 未在 Lane C 重跑，仍以 CI 分片为权威；
- 未跳过任何 Testcontainers 测试、未降低断言、未改生产连接池。

## 5. 未完成 / Deferred

| 项目 | 状态 | 原因 |
| --- | --- | --- |
| Testcontainers 共享容器或 Service Mode | Deferred | 涉及全套测试基础设施，超出 Lane C 最小修复 |
| JMeter 容量重跑 | Not run | 明确只做口径收口，不重跑容量 |
| 生产 Reranker 配置切换 | Not run | 必须绑定 Lane B/A 证据与人工 canary，不能由 Lane C 开启 |

## 6. 判定

```text
Lane C：PASS（含 1 项 Deferred）
N5：PASS
JMeter：FAIL 原结论保留，文档口径已收口
Testcontainers：最小缓解 PASS，根治 Deferred
```
