# Agent 评估语料（Test Fixtures: Agent Evals）

本目录登记 agent 评估集（golden set 与冻结评测集 v2）的语料位置与用途，遵循仓库 `tests/fixtures` 统一登记约定。

## 1. 语料位置与清单

| 语料 | 实际存放位置 | 用途 | 引用测试 |
| --- | --- | --- | --- |
| `golden-cases.json`（20 个起步 case，UTF-8 无 BOM） | `ai-collab-backend/src/test/resources/evals/golden-cases.json` | Agent 评估集定义：场景输入（用户请求 + 项目上下文 seed）+ 脚本化模型响应 + 可机械化判定的期望断言（agent_run_event 事件流 + DB 副作用 + 禁止行为清单） | `AgentEvalSuiteTest`（Testcontainers 全链路运行）、`GoldenCaseLoaderTest`（schema 契约） |
| `frozen-eval-set-v2.json`（46 case，UTF-8 无 BOM） | `ai-collab-backend/src/test/resources/evals/frozen-eval-set-v2.json` | AC-01 跨 Agent/RAG 冻结评测集 v2：由 golden-cases.json（20 条逐字继承）、P10 QA 题集（14 题逐字继承）、business-evaluation-cases.json（12 条可机械判定素材）拼装，补齐 §3.3 元数据并划分 dev(33)/holdout(13)；划分依据、继承映射与 NOT_VERIFIED 项见该目录 README | `FrozenEvalSetV2ValidationTest`（只读校验） |

## 2. Case 覆盖与回归防线

六大类目：任务写操作审批流（TASK_WRITE_APPROVAL）、任务查询工具（TASK_QUERY）、
委派研究含 P0 判重（DELEGATION_RESEARCH）、预算收敛（BUDGET_CONVERGENCE）、
循环防护（LOOP_GUARD）、记忆写入审批（MEMORY_WRITE_APPROVAL）。

每个 case 的 `guards` 字段写明守护的防护机制；其中三个【回归防线】case 在对应
P0/P1 防护被删除时必然失败：

| case id | 守护机制 |
| --- | --- |
| `delegation-duplicate-objective-rejected` | 委派判重台账（`agent_delegation_ledger` + `AgentDelegationGuard`） |
| `budget-soft-threshold-restrict-hint` | 输入预算软阈值分级与收敛提示注入（`AgentBudgetThresholdPolicy`） |
| `loop-guard-intercepts-repetition` | 循环防护滑动窗口判重（`AgentLoopGuard`） |
| `write-approval-task-approve-persists` | 提案→批准→落库审批执行链（`AgentApprovalService.approve`） |

## 3. 规范与维护约定

- case 断言只允许依赖持久化事实：`agent_run_event` 事件、`agent_tool_invocation` 状态、
  受限 DB probe 词表（`GoldenCaseLoader.DB_PROBES`）、运行器捕获的模型请求全文。
  禁止把"模型输出文本好坏"写进断言——那是无法机械化判定的。
- DB probe 只能用受限词表，禁止在 case JSON 中内嵌任意 SQL。
- 新增 case 时同步更新 `golden-cases.json` 顶部的用例数说明与 `AgentEvalSuiteTest` 中的
  数量守门断言（当前 20）。
- 运行结果写入 `ai-collab-backend/target/eval-report.json`（受控目录），报告含
  `version` 字段与逐 case 结果数组，作为"通过率 X%→Y%"对比的基线格式。
