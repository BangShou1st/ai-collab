# AC07-P1 验收门禁补齐

> 任务:AC07-P1 门禁补齐(**仅新增测试,零生产代码变更**)
> 日期:2026-11-11
> 代码基线:`dev` @ `4d56d08c`(工作区干净,CI success)
> 依据:[AC07-P0 Memory 基线审计](../2026-11-10/AC07-P0-memory-baseline/README.md) §8.5「P1 必须同步落地的代码项」与 §4.2「撤权 fail-closed 边界」
> 设计依据:[AC-07 Agent Memory V2 设计补充](../../../design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md) §9.2、§13(M-04/M-05/M-07/M-13)

---

## 1. 门禁核对(P1 两波之后)

P0 报告 §8.5 把以下 5 项列为 P1 验收门禁。对照 P1 第一波(`77fed4bc`)、第二波(`b6a8f066`/`422c2ba2`/`ad3586cf`)落地后的实际测试:

| # | 门禁 | 对应矩阵 | P1 已有覆盖 | 本次处置 |
| --- | --- | --- | --- | --- |
| 1 | 来源校验最小实现 | M-09 | ✅ `AgentMemoryVerificationIntegrationTest#verificationStateLifecycleAndSourceGovernance`(非法类型、不存在/跨项目文档、update 路径、禁止客户端伪造 VERIFIED) | 无需补 |
| 2 | `version` CAS 并发 | M-13 | ⚠️ 仅有顺序旧版本断言(`disable` 用过期 version) | **补:同版本并发恰好一个赢家** |
| 3 | `disable` 后不可召回 | M-04 | ⚠️ 仅断言 `list(activeOnly=true)` 不含 | **补:`context()` 与只读工具路径** |
| 4 | `list()` 撤权过滤回归 | M-05 边界三 | ❌ 只测过 `find()`;`list()` 三段子查询、授权过期、外部文档 BLOCKED 均无直接测试 | **补:直接钉住列表 SQL** |
| 5 | 成员移除负向 | M-07 | ❌ 权限守卫在既有测试中为 mock | **补:真实 `DatabaseProjectAccessGuard` + 真实 `project_member`** |

## 2. 新增测试

文件:`ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentMemoryRecallGovernanceIntegrationTest.java`

真实 PostgreSQL 17(pgvector)+ Flyway V1–V88 全量迁移 + 真实 `AgentMemoryRepository` / `AgentMemoryService` / `ListMemoriesAgentTool`,零模型调用。

| 测试方法 | 门禁 | 断言要点 |
| --- | --- | --- |
| `removedMemberLosesRecallListAndToolAccessWhileOwnerKeepsIt` | M-07 | 真实 `DatabaseProjectAccessGuard`(经 `SqlSessionTemplate`,与生产一致地每次操作清理会话缓存);成员移除后 `list` / `context` / `list_project_memories` 工具三条入口均抛 `PROJECT_NOT_FOUND`;owner 不受影响 |
| `repositoryListPathIsFailClosedForBlockedSourceExpiredAuthorizationAndBlockedDocument` | M-05 | 不经过 `blockSource` 隐藏表,直接改数据后断言 `list()` 过滤:①`connector_source.status='BLOCKED'`;②`connector_oauth_authorization.access_expires_at` 过期;③`external_document.status='BLOCKED'`。三条路径均使召回/列表/工具为空、`find()` 为空;来源恢复后重新可见 |
| `disabledMemoryIsNotRecalledButStaysVisibleInManagementView` | M-04 | `disable` 后新构造的 Service 实例 `context()` / `list(activeOnly=true)` / 工具均为空,`list(activeOnly=false)` 仍可见(管理复核) |
| `concurrentUpdatesWithTheSameVersionProduceExactlyOneWinner` | M-13 | 6 线程同一 version 并发 `update`,恰好 1 个成功、5 个 `VERSION_CONFLICT`;`version` 终值 +1,`agent_memory_revision` 恰好 1 条 |

### 2.1 顺带钉住的边界(P0 §4.2 边界二)

`connector_source` 屏蔽后,**创建时间晚于撤权事件**的记忆仍可见(SQL 时间条件方向);该行为此前只有推断、无测试,现由 `repositoryListPathIsFailClosed...` 场景 A 断言。

### 2.2 测试实现说明

- `DatabaseProjectAccessGuard` 通过 `SqlSessionTemplate` 获取 `ProjectMemberMapper`:直接持有裸 `SqlSession` 会命中 MyBatis 一级缓存,外部 `DELETE project_member` 后仍返回旧角色;`SqlSessionTemplate` 与生产一致,每次操作后清理缓存,保证"每次召回都重新校验成员身份"被真实测量。
- 撤权场景通过**直接修改来源/授权/外部文档状态**触发,而非调用 `ConnectorVisibilityService.blockSource`——后者的隐藏表已由 `ConnectorLifecycleMigrationIntegrationTest` 覆盖,本次要测的是 `AgentMemoryRepository.list()` 自身的 fail-closed SQL。

## 3. 执行结果

| 项 | 结果 |
| --- | --- |
| `-Dtest=AgentMemoryRecallGovernanceIntegrationTest` | 4/4 PASS(Testcontainers PostgreSQL 17 + Flyway 88 个迁移) |
| 后端全量 `mvnw -B -ntp verify` | `PASS`:Tests run 2551, Failures 0, Errors 0, Skipped 61, BUILD SUCCESS(19m20s;日志落 `target/`,未入库) |
| 生产代码变更 | 0(仅新增一个测试类与本文档) |
| 迁移变更 | 0(复用 V1–V88) |

> 状态口径遵循仓库约定:`TESTED` 仅代表自动化测试通过,不代表已上线;真实模型质量见 §4。

## 4. NOT_VERIFIED / 不在本次范围

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 21 个冻结场景(FC-01～FC-25)的端到端质量与 Recall@K | NOT_VERIFIED | 需真实模型与冻结语料测量,属 P3 / 质量报告范围 |
| `PREFERENCE` 无条件 +1 召回噪声、>100 条窗口外不可召回 | NOT_VERIFIED(逻辑已核实) | 基线测量方案见 P0 报告 §7.1,尚未执行 |
| Dreaming(M-11/M-12)、Redis(M-19)、私人记忆 | N/A | AC07-P2 / P4 范围 |
| `ListMemoriesAgentTool` 描述"最多 10 条"与实现 100 条的漂移 | 已知缺陷 | P0 §2.5,留待 AC07-P3 按需召回改造时一并修正 |

## 5. 复现

```bash
cd ai-collab-backend
bash ./mvnw -B -ntp -Dtest=AgentMemoryRecallGovernanceIntegrationTest test
bash ./mvnw -B -ntp verify
```
