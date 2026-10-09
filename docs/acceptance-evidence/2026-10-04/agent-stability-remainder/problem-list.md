# Agent 稳定性剩余缺陷集中修复——问题清单

日期：2026-10-04。基线 `39595d5`（origin/codex/context-foundation）。批次目录：`agent-stability-remainder`。
问题来源：`root-cause-repair/rootfix-fixed24/quality-review.json`（第 3 项中间状态、第 6 项规划正文）、turn-10.json、turn-23.json、用户指定五组问题。

## A 摘要重压缩不受控、失败丢账

- 触发条件：第一份摘要输出超长（fixed24 第 22/23 轮真实命中），进入 `AgentContextSummarizer` 的一次重压缩分支；或重压缩调用异常/取消/预算不足。
- 根因：`maybeSummarize` 在发起第二次模型调用前不重新核算剩余输入预算、不检查取消状态、不校验重压缩请求自身大小；第二次调用异常时 catch 用估算输入+零输出覆盖整次尝试，第一次调用已返回的真实 usage 丢失；合并 usage 的 estimated 标志用单个 boolean 混合两次调用来源。
- 修改位置：`AgentContextSummarizer`（重压缩前置检查、按调用分段记账、降级原因）、`AgentRunEventRecorder.completeSummaryAttempt`（显式 usage_basis，新增重压缩调用行 `CONTEXT_SUMMARY_RECOMPRESS`，各自 ATTEMPTED→终态一次性入账）、`AgentRepository` 包装。
- 对应测试：`AgentContextSummarizerTest` 新增：二次超时/异常保第一次真实用量；取消后不发起第二次且入账；剩余预算不足不发第二次；二次成功合并用量；CAS 冲突用量保留；重复完成不重复结算。`AgentActualTokenUsageIntegrationTest`（真实 PG）补重压缩调用行对账。
- 通过标准：第二次调用前预算/取消/大小复查可测；第一次真实 usage 在任何二次失败路径下不丢；未知部分标 ESTIMATED/UNKNOWN 不冒充 PROVIDER 或零；重压缩请求有界；不足时保留上一份摘要、覆盖不推进、降级原因持久化；尝试上限与一次性结算保持。

## B 数量替代：真实原句"取代"关系未识别，8/10 被标待澄清且旧 10 仍生效

- 触发条件：真实第 10 轮原句"把本目标任务数量上限调整为最多8项；这取代原来最多10项。"——`countFact` 在"取代"子句中把旧值 10 也收为新候选，found={8,10} 标 `needsClarification`，旧 10 条目保持 active。
- 根因：`AgentWorkingState.countFact` 按全文收集数字，不区分子句语义；显式"取代/替代"关系和"从旧值改为新值"结构未被识别为"旧值退位"。
- 修改位置：`AgentWorkingState.countFact`（子句级取代标记：含"取代/替代/代替"子句中的数值记为旧值进 `detail.supersedes`，不作为当前候选；"从N改成M"拆新旧；多值无标记仍保守待澄清；假设句/疑问/否定继续跳过）、`mergeConstraint`（显式取代关系下对匹配旧值的历史条目置 superseded 并保留溯源）。
- 对应测试：`AgentWorkingStateConstraintIntegrationTest`（真实 PG）新增逐步中间状态断言：10 active → 原句改 8 后 8 active 且 10 superseded → 目标切换 → 6 active；沿用既有歧义/疑问/否定保守用例；`AgentRepositoryIntegrationTest` 相关序列回归。
- 通过标准：真实原句后中间态 8 active、10 superseded（带来源消息与取代关系）；多值无"取代"标记仍不猜；普通询问不修改当前约束；兼容既有"待澄清/旧值 active"会话，无需清历史。

## C 日期/负责人保护按值比较替代，不同对象互相覆盖

- 触发条件：任务 A 锁日期 D1、任务 B 锁日期 D2（或负责人甲/乙）先后登记——`supersedesDifferentObject` 只比较 dates/assignee 值不同即替代，B 的保护撤销 A 的保护。
- 根因：`AgentWorkingState` 约束条目未把"被约束对象"存入 detail，替代规则退化为"值不同→替代"；"沿用此前"在全部历史中找唯一值，可能把别的对象的值套过来。
- 修改位置：`AgentWorkingState`（dateFact/assigneeFact 写入 `detail.object`；替代规则改为按对象：同对象明确修改才替代、不同对象并存、对象不明不覆盖已有明确对象条目、双方泛指才按同作用域更新；`reuseUniquePriorDetail` 按对象范围唯一定位；`repairLegacyV2Entries` 从旧 value 前缀渐进提取 object）。
- 对应测试：`AgentWorkingStateConstraintIntegrationTest` 新增：A/B 日期两条 active；A/B 负责人两条 active；明确修改只影响 A；对象含糊不撤销 A/B；跨目标历史不错误复用；legacy 条目渐进补 object；composer-v2=false 渲染兼容。
- 通过标准：不同对象保护并存；同对象只有明确修改/撤销才替代；"沿用此前"只在对应对对象范围内定位；泛指与具体任务约束不混为一条；quote/detail 可追溯且值不串作用域。

## D 用量来源倒推、取消补记无身份无幂等

- 触发条件：摘要器先把缺失 usage 补成估算值再交给 `completeSummaryAttempt`，`usageBasis(in,out)` 按非空倒推成 PROVIDER；`settleOrphanModelUsage` 缺失值直接记零、无持久调用身份，取消检查与正常结算之间存在竞争窗口（模型返回→isCancelRequested=false→throwIfRequested=true 时两边都不入账）。
- 根因：来源（提供商值/估算值/未知值）没有作为独立字段随原始 usage 显式传递；孤儿结算没有稳定调用身份与唯一约束，"调用方保证一次性"不能覆盖重试/恢复。
- 修改位置：新增迁移 `V57__agent_usage_settlement.sql`（`agent_usage_settlement`：run_id+call_id 唯一、kind、actual 双列、usage_basis、latency）；`AgentRunEventRecorder`（`usageBasis` 改为由原始 usage 显式计算并透传；新 `settleOrphanUsage(callId,…)` 用 INSERT ON CONFLICT 幂等入账；`completeSummaryAttempt` 收显式 basis）；`AgentRuntimeCoordinator`（取消路径用请求内容哈希作 callId，先结算后取消，消除两边都不记窗口）；`AgentContextSummarizer` 显式传 basis。
- 对应测试：`AgentActualTokenUsageIntegrationTest`（真实 PG）：取消补记幂等（同 callId 两次只入账一次、账本行唯一）、部分 usage 来源正确、重复结算、取消竞争（先结算后 recordCanceled 与直接 recordCanceled 两路径）；摘要 attempt 行 basis 与值一致。
- 通过标准：来源字段来自原始 usage 判定并显式传递；缺失时按请求/响应证据估算、无证据显式 UNKNOWN；同一调用不重复入账也不漏记；业务状态终结不妨碍结算；V56 actual 可超上限语义保留（used 封顶、actual 如实）；历史 V56 回填为下界与新行分开说明。

## E 预算强制收尾未做核心动作却记 SUCCEEDED

- 触发条件：真实第 23 轮：只查一次里程碑后 `needsFinalRequest` 触发 FINALIZE，工具被移除；模型再花 16263 输入回答"未调用 start_task_plan"，`recordFinal` 记 SUCCEEDED；用户要求生成规划，未完成。
- 根因：Coordinator 把"预算策略进入收尾"与"业务目标完成"混同——只要模型返回文本即记成功；收尾判断依据 `successfulToolCalls>0`（任意成功工具）；单次上下文体积大（系统提示+全量 Schema+历史+工作状态+摘要+工具观察叠加）加速预算收敛且无体积可观测性。
- 修改位置：`AgentSkill`（新增 `coreActionTools()` 默认空）、`IterationPlanningSkill`（start_task_plan）、`AgentRuntimeCoordinator`（确定性判断"当前目标要求生成规划且核心动作未发生"——依据 goal 文本否定式与 `agent_tool_invocation` 持久化结果，不扫描最终回答；收尾强制下核心动作未发生时走 `recordBudgetPartialAnswer`→BUDGET_EXCEEDED，不伪报成功；非收尾正常只读不受影响）；`AgentModelMessageComposer`（CompositionStats 分层体积：system/state/summary/page/proposals/toolObservations/history/memory，附加 UNVERIFIED 包装瘦身，MODEL_STARTED 事件携带 inputBreakdown 供真实运行测量）；未执行目标必需动作时注入一次确定性提示（信息足够即启动规划）。
- 对应测试：`AgentRuntimeCoordinatorTest` 新增：预算收尾+核心动作未执行→BUDGET_EXCEEDED 且不 recordFinal；核心动作已发生→正常 SUCCEEDED；只读问题正常完成；composer 体积分析单测；收敛策略既有测试保持。
- 通过标准：预算收尾与任务完成状态一致（BUDGET_EXCEEDED/部分回答 ≠ SUCCEEDED）；"运行结束/规划已受理/规划已生成/正式任务已创建"判断依据持久工具结果与控制原因；普通只读完成不受影响；不为完成目标绕过审批；上下文体积有实测数据并完成安全瘦身。
