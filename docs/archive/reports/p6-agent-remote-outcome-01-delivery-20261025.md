# P6_AGENT_REMOTE_OUTCOME_01 交付报告

日期：2026-10-25。任务范围：工具出站结果确认、UNKNOWN 与安全恢复；不扩展 P6-A 调度架构。

后续并发复核发现并补充修复了未提交 dispatch 的 TOCTOU 与缺失 Invocation 的孤立结果问题，详见
[并发复核补充报告](../reviews/p6-agent-remote-outcome-01-toctou-review-20261025.md)。下文统计及 SHA 保留为首轮交付快照；
最新补丁验收与源码 Manifest 以补充报告为准，最终裁决仍为 CI_PENDING。

## A. 根因与安全不变量

旧超时记录统一为 FAILED/retryable=true，不能证明远端未成功。PENDING 没有出站阶段，崩溃后可能重发；
旧批次/取消收口可把已出站 PENDING 写成 SKIPPED/CANCELED。结果写入也没有阻止终态覆盖。
修复前真实 PostgreSQL 复现：UNKNOWN 后的新 ToolCall 额外执行一次；迟到成功可覆盖 UNKNOWN。
旧主 Worker 的结果 fencing 已有正确保护，但 Scheduler 线程不继承 ThreadLocal claim，规划受理还需显式 fencing。

不变量：先提交出站证据再执行；按可信宿主能力分类；UNKNOWN 不重发、不普通覆盖、不当作成功证据；
旧 claim 不能写结果或受理规划；取消 Future 不代表远端取消；运行中额度直到 Callable 实际退出才释放。

工具清单：

| 实际工具族 | 安全类别与处理 |
| --- | --- |
| 项目概览/进度/风险/仪表盘、任务/里程碑/成员/审计/记忆查询、周报统计、知识检索及其兼容别名 | SAFE_READ，实际实现显式声明 |
| 文档目录、提纲、正文段落；规划列表/详情/进度；澄清工具返回 | SAFE_READ，宿主声明，不依赖模型自述 |
| start_task_plan / repair_task_plan / cancel_task_plan_generation | IDEMPOTENT_OR_RECONCILABLE，原 Invocation 查询真实 operation，不自动重新提交 |
| MCP 工具及未声明类别的新工具 | 默认 UNCONFIRMABLE_SIDE_EFFECT；仍只暴露现有 MCP 只读白名单；readOnlyHint/isError 不构成无副作用证明 |
| 审批提案、文档研究委派 | 保留既有事务受理、proposal/child invocation 幂等专用路径，不绕过审批或引入远端写入口 |

修复前证据见 [before-unit.json](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/before-unit.json) 与
[before-postgres.json](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/before-postgres.json)。
B01 超时误判失败；B02 取消后 Callable 仍能完成副作用（原 Scheduler 额度保护正确）；B03 新 ID 重复执行；
B04/B05 使用原有 Lease/规划受理刻画测试，旧暂停 21 项和结果恢复 24 项全过，没有人为制造缺陷；
B06 原 TOOL_RETRY 分支保留，真实 PostgreSQL 验证初次查询 + 一次重试，第三次拒绝。

## B. 生产实现

- `AgentToolCallExecutor`：持久化出站、显式传递 claim、恢复只查询原结果、分类超时/异常、UNKNOWN 停止推进。
- `AgentRepository`：Invocation 行 CAS 单次出站，核对 Run/用户/Lease/暂停/取消/工具名/JSONB 参数；读取持久化执行阶段。
- `AgentRunEventRecorder`：结果只允许写未结算且身份匹配的 Invocation；预算、Step、Event 与状态在事务内提交。
  UNKNOWN 或未确认出站不能成功收口、转普通自动重试或跳过；批次/取消保留 UNKNOWN 失败事件。
- `AgentRuntimeCoordinator`：UNKNOWN 在上下文组装和新模型调用前终止，避免模型生成新的业务操作。
- `AgentToolExecutionSafety` / `AgentTool`：三种安全类别与可选只读 reconcile；内置查询工具显式声明。
- `AgentPlanningOperationService` / `PlanningAgentTools`：原 Invocation → 原业务 Operation 查询，继续核对项目、用户、工具和参数；规划写入校验原 claim。
- `McpAgentToolProvider`：不增加写权限；不把 MCP isError 解释成“确定没有部分副作用”。
- `V67__agent_invocation_dispatch_evidence.sql`：只新增现有 invocation 的 dispatch_started_at、dispatch_claim_version、execution_safety 与 CHECK。
  旧 STARTED 事件不能承担带安全类别/claim 的单次执行账本，故使用行级持久化字段，不新增执行表。
  按旧开始事件精确回填历史调用，保护旧 PENDING 及已开始的超时/取消/跳过；未知历史 claim=-1。

Invocation 身份仍为 run + 持久化 MODEL_TURN sequence + ordinal，工具名/参数另行严格核对；不在恢复时重新生成。
Business Operation ID 是真正的 planning operation.id，invocation_id 唯一约束用于关联去重；两者不混同。
JSONB 等值比较在同 Run/项目/用户范围内核对，不按参数跨项目合并业务。

状态机、事务边界、晚到响应、暂停/取消与保证边界详见 [正式设计](../../design/agent-remote-outcome.md)。
Scheduler 生产文件与基线完全一致；未修改配额、默认参数、审批、MCP 白名单和模型 Token 结算路径。
前端源码未改，复用现有 UNKNOWN 中文展示。

## C. 故障注入

T01–T14 的方法、实际操作计数及状态断言逐项见 [状态机验证矩阵](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/state-machine-matrix.md)。
本轮还覆盖取消先提交、参数身份冲突、UNKNOWN 后重新排队、响应仍阻塞时查询既有受理结果，以及 V66 → V67 升级。
PostgreSQL 测试类使用各自隔离的容器数据库，类内按 fixture/Run 隔离；不连接生产库，不执行真实 MCP 写入、不使用付费模型进行故障测试。

P6-B PostgreSQL 21 项与迁移 1 项全部执行、无失败/跳过；结果单测 2 项通过。
矩阵的副作用次数与数据库状态均由通过的断言支持，无重复副作用；不是仅检查异常类型。

## D. 回归

定向回归：78 项、0 失败、0 错误、0 跳过（Scheduler 13、暂停 21、持久化模型轮次 24、远端结果 PostgreSQL 18、结果单测 2）。
后续补充的升级、历史超时与 MCP 错误语义测试以最终完整验收结果为准。
前端 lint/typecheck/test/build 全部通过：51 个测试文件，393 项；lint 75 条现有 warning、0 error；构建有现有 Rollup/chunk size warning。
前端无源码改动，不重复进行无关 UI 重构或浏览器操作。

后端完整 `bash ./mvnw -B -ntp verify`：BUILD SUCCESS，11 分 14 秒，1730 项，0 失败、0 错误，34 跳过（实际执行 1696 项）。
16 个 CI 要求的关键类全部实际执行，守卫通过；统计按本次 Maven 实际输出筛选，排除 target 中两个陈旧报告（合计 5 项）。
新增历史回填规则和 MCP 错误契约最后另跑定向测试：4 项、0 失败/错误/跳过。
这两项是在完整回归进行期间补充，最终定向验证针对最后的 SQL/测试版本；不把两次统计简单相加冒充全量。

具体回归：Scheduler 13、暂停 21、模型轮次恢复 24、审批升级 1、Run Retry PostgreSQL 6、
Delegation PostgreSQL 40、Run Context Commit 10、Research Quality PostgreSQL 7 全部通过。
34 项跳过来自既有真实 Host/外部模型/Ollama/质量实验/性能基准 opt-in 条件，未新增或扩大 skip；
逐类结果及原因见 [完整统计](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/backend-verify.json)，
最后补充检查见 [定向回执](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/final-targeted.json)。

## E. GitHub 与交付证据

基线：`968db8ce0f50d6a028793002386cb23cfb9a2024`，开工时 dev 与 origin/dev 同步，工作区及 untracked 为空。
main 基线：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；本轮不修改 main。
PR #18 开工核实为 OPEN / Draft / 未合并；不改 Ready、不合并、不创建额外 PR。
不 force push、不 rebase、不 reset/clean、不 add -A，无用户 WIP 被覆盖。

最终实现提交：`19eaa2fb900c2e244572d0e705f529e68224adcc`，
[Commit](https://github.com/BangShou1st/ai-collab/commit/19eaa2fb900c2e244572d0e705f529e68224adcc)。已正常快进推送 origin/dev。
本报告与 GitHub 回执随后单独作文档提交；最终交付 HEAD SHA 在最终回复给出，不把文档提交伪装成新的生产实现。

记录时实际 CI：

| 运行 | Backend | Frontend | Image | 裁决 |
| --- | --- | --- | --- | --- |
| [dev push 40119378640](https://github.com/BangShou1st/ai-collab/actions/runs/40119378640) | in_progress | in_progress | skipped（既有 dev push 条件） | CI_PENDING |
| [Draft PR 40119381651](https://github.com/BangShou1st/ai-collab/actions/runs/40119381651) | in_progress | in_progress | in_progress | CI_PENDING |

这是实现 SHA 的时间点快照，见 [GitHub 回执](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/github-code-receipt.json)。
文档回执推送后以最终回复核实的最新 HEAD CI 为准；不手动重跑/dispatch 工作流，不为增加 CI 产生空提交。
提交前复核 dev 与 origin/dev 同步，main 本地/远端均仍为上述基线；PR #18 仍 OPEN/Draft/未合并。
起始无用户 WIP，未覆盖用户修改；本轮未生成 *.log，既有受忽略的历史 target 日志保留。

证据目录：[p6-agent-remote-outcome-01](../../acceptance-evidence/2026-10-25/p6-agent-remote-outcome-01/)。
包含修复前失败、源码 SHA-256 Manifest、调度器 Git blob 一致性、故障矩阵、前后端检查及 GitHub 回执。
测试输出保存在受控 target 目录，未向根目录/源码目录生成日志；交付证据不包含凭据。
未改写 P6-A 历史交付报告。

## F. 最终裁决与保证边界

`P6_AGENT_REMOTE_OUTCOME_01_IMPLEMENTED`、`P6_AGENT_REMOTE_OUTCOME_01_LOCAL_PASS`。
最终远端 CI 尚未成功确认前保持 `P6_AGENT_REMOTE_OUTCOME_01_CI_PENDING`，不签署 ACCEPTED。

真实外部 MCP / Provider 的网络取消确认：`LIVE_NOT_RUN`。本地 Fake 只证明宿主恢复规则，不证明远端撤销。
没有新增分布式配额或跨服务 Exactly-Once 承诺。
缺少可信幂等/查询能力时保持 UNKNOWN，需要人工核对；规划查询时受理事务若尚未提交也保守 UNKNOWN，
不自动复活已终结 Run，不用迟到 HTTP 200 覆盖新状态，不提供通用远端补偿接口。
