# Agent 稳定性剩余缺陷集中修复与真实复验

日期：2026-10-04 至 2026-10-05。基线 `39595d5`（origin/codex/context-foundation），本轮最终代码 `6edb3c3`（V57 后）。隔离副本沿用卷 `ai-collab-acceptance-20261003`（容器 ai-collab-rootfix-pg/redis/minio，localhost:55432/16379/18090，Flyway 已应用至 V57）；原业务 PostgreSQL/Redis/MinIO 全程停止。模型 space-bunny-free，温度 0.2，规划输出 6000；Agent 输入上限 50000/输出 20000/步骤 24/工具 16，与本轮前一致。问题清单见 [problem-list.md](problem-list.md)。

## 五组根因与修复

| 组 | 根因 | 修复（提交） | 真实复验 |
| --- | --- | --- | --- |
| A 摘要重压缩 | 第二次调用前不复查预算/取消/请求大小；第二次失败时 catch 用估算覆盖第一次真实 usage；混合来源用单 boolean 冒充 | `4e75833`：重压缩前复查取消/剩余时长/剩余预算（扣除首次真实用量），请求自身有界（草稿+预留估算）；每次实际模型请求独立持久化（CONTEXT_SUMMARY + CONTEXT_SUMMARY_RECOMPRESS），各自 ATTEMPTED→终态一次性入账；`UsageSettlement` 按原始 usage 分侧判定 PROVIDER/ESTIMATED/UNKNOWN 并显式传递；降级原因持久化 | 真实 fixed24：9 次摘要尝试中 8 次超长触发重压缩（8 行 RECOMPRESS 均 SETTLED、PROVIDER 记账），1 次仍超长 DOWNSGRADED_UNQUALIFIED（note=RECOMPRESS_STILL_UNQUALIFIED，覆盖未推进、旧摘要保留），1 次 COMMITTED；首次真实用量在失败/取消/CAS 路径不丢失（单测+真实 PG 回归） |
| B 数量替代 | 真实第 10 轮原句"调整为最多8项；这取代原来最多10项"被 `countFact` 收成 8/10 双值待澄清，旧 10 仍 active | `d6d36a6`：子句级"取代/替代/代替"标记——该子句中的数值进 `detail.supersedes` 溯源、不作为当前候选；"从N改成M"拆新旧；假设/疑问/否定继续保守；多值无标记仍待澄清 | 真实 minimal 第 10 轮与 fixed24 第 10 轮（原句）：8 active（supersedes=[10]，来源消息=第10轮消息 ID）、10 superseded，回答复述"8 取代 10"；替代链 10→8→6 在库可追溯；兼容既有待澄清会话（无需清历史） |
| C 日期/负责人保护 | `supersedesDifferentObject` 按"值不同"替代，任务 B 的保护撤销任务 A 的 | `d6d36a6`：约束条目落 `detail.object`；替代按对象判断——同对象明确修改才替代、不同对象并存、对象不明不覆盖已有明确对象条目、双方泛指才按同作用域更新；"沿用此前"只在同对象范围内唯一定位；旧 value 前缀渐进补 object | 真实三批终态 active 恰为 最多6项+日期区间+Local Owner；A/B 对象保护并存、明确修改只影响 A、跨对象不复用、泛指与具体对象不混条（AgentWorkingStateConstraintIntegrationTest 14 项，真实 PG） |
| D 用量来源与取消结算 | `usageBasis` 按数字非空倒推；`settleOrphanModelUsage` 无身份无幂等、缺失记零；两次取消检查间存在两边都不记的窗口 | `4e75833`：新增 V57 `agent_usage_settlement`（run_id+call_id 唯一）；`settleOrphanUsage` 幂等入账；取消路径先按请求内容哈希结算再取消，竞争窗口内由同一 callId 兜底补结算（最多入账一次）；缺失 usage 按请求/响应证据估算、无证据显式 UNKNOWN；业务状态终结不阻碍结算 | 真实批次审计（46 运行）：shadowed=0、步骤账本与 actual 完全对账、settlement 行=0（本轮真实运行未发生取消竞争，该路径由真实 PG 回归覆盖：重复补记/取消竞争/证据估算三用例）；第 15 轮真实输入 53437>50000：used 封顶、actual 如实、BUDGET_EXCEEDED |
| E 提前收尾伪成功 | 预算策略进入收尾且核心动作未发生时，仅凭模型文字记 SUCCEEDED（上一轮真实第 23 轮）；上下文体积无观测 | `d23876c`：`AgentSkill.coreActionTools()`（ITERATION_PLANNING=start_task_plan）；核心动作是否被要求由目标文本确定性判定（否定短语优先），是否发生依据 `agent_tool_invocation` 持久结果；预算收尾+核心动作未发生 → `recordBudgetPartialAnswer`+BUDGET_EXCEEDED（scope=CORE_ACTION_NOT_PERFORMED）；未收尾时注入一次确定性提示（证据足够即受理规划）；`CompositionStats` 分层体积+MODEL_STARTED `inputBreakdown` 实测事件；助手历史包装瘦身 | 真实 fixed24 第 23 轮：模型第一次 start_task_plan 失败（PLANNING_DOCUMENT_NOT_READY，持久化保留），重试成功受理（effect=PLANNING_OPERATION_ACCEPTED，requiresHumanConfirmation=true），运行 SUCCEEDED 属真实成功；只读轮（4/10/18/19/22）正常完成不受影响；协调器测试 23 项含三个新用例 |

## 固定批次真实结果

- `stability-minimal`（1–10 轮，新项目/新会话）：10/10 SUCCEEDED、retry=0。第 10 轮中间状态见上。
- `stability-fixed24`（1–24 轮严格顺序，同字节蓝图，中途零改动，代码 `6edb3c3`）：22/24 SUCCEEDED、retry=0；**第 9 轮 BUDGET_EXCEEDED**（验收宿主 JVM 意外退出，恢复任务接续原运行后时长预算已耗尽——真实中断路径如实记录，states.jsonl 留痕，未重复提交）；**第 15 轮 BUDGET_EXCEEDED**（真实输入 53437>50000，超额如实终止）。24/24 SUCCEEDED 不是本轮的结果，两个 BUDGET_EXCEEDED 均为诚实结果而非五组缺陷复发。
- `stability-correction`（原失败会话+全部历史，第 14/16/18/21/22/24 轮）：全部 SUCCEEDED。第 24 轮只读区分"后台生成完成（READY）"与"规划未完成（未人工确认）"，未重复提交、未确认正式任务。第 18 轮证据从其原运行恢复落盘（批处理被中断时已提交，未重发）。
- 规划正文（fixed24 项目，READY v2）：6 项任务+6 个里程碑，日期全部落在 2026-10-05..10-25，suggestedAssigneeId 均为真实 Local Owner ID，依赖 T1→…→T6 线性无环，validation.errors=[]（TASK_UNASSIGNED 警告为设计内），**草稿全文 0 处"10项/最多十项"残留**，assumptions 明示 6 项来自当前生效约束；confirmations=0、正式任务=0。规划服务两次真实模型调用（SKELETON 1806/3778、DETAIL 5683/2575）均 SUCCESS。**证据口径说明**：第 23 轮两次 start_task_plan 并非同一参数的重试成功——第一次携带 documentIds=[3e247376…]（该文档 processingStatus=FAILED、检索不可用）→ PLANNING_DOCUMENT_NOT_READY；第二次**去掉了 documentIds**，把此前已读片段写入 constraints 后成功。这证明的是降级检索条件下草稿内容符合当前约束，不是"同一资料关联请求重试成功"。
- 来源链路**单独环境验证**（`sources-available`，与冻结批次分开记录，不并入冻结 24 轮结论）：冻结批次运行时宿主启动阶段 Ollama 未运行，embedding 失败、检索全程不可用（降级条件如实沿用）；Ollama 可用后**另建**新项目上传蓝图索引 READY，单轮规划受理后规划服务**实际检索取得 9 条真实来源**（S1–S9，含 chunkId/heading/similarity），草稿 READY v2。首次骨架生成 FAILED（PLANNING_MODEL_INVALID_OUTPUT，零版本）按既有规划页路径重新生成，原失败 attempt 保留。该验证使用与冻结批次不同的项目、会话与环境条件。

## 测试统计（不重复相加）

- 后端全量：1022 项，通过 1011，显式 opt-in 跳过 11，失败 0（含真实 PostgreSQL Testcontainers 集成；3 个迁移计数断言随 V57 对齐）。
- 新增/更新回归：AgentWorkingStateConstraintIntegrationTest 14 项（新增 8）、AgentContextSummarizerTest 21 项（新增 5）、AgentActualTokenUsageIntegrationTest 9 项（新增 2、改造 3）、AgentRuntimeCoordinatorTest 23 项（新增 3）、迁移计数断言 4 处对齐。
- 前端：vitest 34 文件 143 项通过、vue-tsc 通过、Vite 生产构建通过（既有 chunk 大小提示保留）。
- 真实模型 46 个运行与规划服务调用不计入上述统计。

## 与旧结论的纠正

- 上一轮"10→8→6 全链路正确"的说法不准确：当时真实第 10 轮 8/10 被标待澄清、旧 10 仍 active，仅最终 6 项正确。本轮以原句复现并修复，中间状态 8 active/10 superseded 已在真实运行验证。
- 上一轮"规划正文旧数量残留未验"已补验：本轮真实草稿零旧数量残留；上一轮第 23 轮"未执行 start_task_plan 但 SUCCEEDED"的伪成功根因已修复并有回归。
- 检索可用环境下的"资料→规划来源"链路本轮首次取得真实证据（9 条来源），与降级环境结果分开记录，不混淆条件。

## 修补轮（2026-10-05，代码 `e3e3603`）：外部评审四项缺口的封堵与复验

外部只读评审发现四项缺口并确认（不重跑已通过验收）：

1. **取消竞争漏记窗口**：最后一次取消检查之后、落库之前取消时，`recordModelTurn` 的租约校验先抛取消、未推进总额，费用随取消丢失；且内容哈希 callId 会把"相同内容的真实第二次请求"与"同一次请求的重复结算"错误共享身份。修复：每次实际出站请求先经 `beginModelCall` 落库独立持久身份，正常记账（`markModelCallSettled` 只标记）与取消/落库失败补记（`settleOrphanUsage` 入账）共用同一身份一次性结算；落库失败（租约过期/版本冲突）同样补结算。真实 PG 回归：落库前取消补结算、真实第二次请求独立入账并再次计费。
2. **重压缩异常输入按未知零值**：请求正文已构造完成，输入改为按实际请求大小估算（ESTIMATED），仅输出侧无响应证据标 UNKNOWN；原错误断言一并纠正。
3. **摘要输出预算未核算**：初始摘要与重压缩各自独立检查剩余输出预留（新增 `RECOMPRESS_SKIPPED_OUTPUT_BUDGET`）；摘要入账后协调器同时复查输入与输出预算，输出预留不足即 `SUMMARY_CONSUMED_OUTPUT_BUDGET` 停止，不再继续主调用。
4. **核心动作否定意图误判**：肯定与否定覆盖同一动词集合（生成/起草/制定/启动），按子句评估并跳过疑问/假设语气；"不要起草规划""无需制定计划""是否需要生成规划"不再判为生成要求，"先不生成；现在生成草稿"以后一子句为准。

**修补后受影响链路真实复验**（`patch-reverify`，代码 `e3e3603`，隔离副本，embedding 可用 doc=READY）：新会话按原措辞执行第 10 轮（负向）与第 23 轮（正向）——第 10 轮 SUCCEEDED、0 次工具调用、无规划操作（否定意图未被注入提示误导）；第 23 轮模型 3 次 `start_task_plan` 均被业务结构化校验 `VALIDATION_ERROR` 拒绝（新会话无约束历史，参数不满足），模型如实报告"草稿未生成"后预算收尾 BUDGET_EXCEEDED——**这是预算保护正确停止，不是用户目标完成**；未调高预算凑成功。账本审计：2 个运行、7 次模型调用、7 条已结算身份行、0 未结算、0 对账差异（正常路径"记账+标记不重复入账"在真实链路成立）。测试增量：AgentContextSummarizerTest 23 项、AgentRuntimeCoordinatorTest 27 项、AgentActualTokenUsageIntegrationTest 11 项（新增落库前取消回归与独立身份回归），后端全量 1030 项 0 失败。

## 结算原子性闭环轮（2026-10-05，代码 `b2d6f2d`）：外部二次评审三项缺口的封堵与复验

外部二次只读评审确认三项缺口并限定本轮闭环范围：

1. **正常记账与"已结算"标记不在同一事务**：修复为原子记账——身份行首次结算（条件更新独占）与 `recordModelTurn`／`recordBudgetExceeded` 的运行累计、步骤写入在同一事务（`recordModelTurnWithSettlement`／`recordBudgetExceededWithSettlement`）；事务中途失败整体回滚（身份行与总额共同回滚），同身份后续补结算不会重复累计；模型网络请求不在事务内。真实 PG 故障注入：注入版本冲突后身份行仍为未结算且总额未变、随后同身份补结算恰好一次；正常记账后重复补结算总额不变；输出超限原子记账恰好一次。
2. **请求异常与输出超限分支未接入结算**：格式/提供商/超时异常与请求中取消路径现在先按证据结算（输入按实际请求规模估算、输出显式 UNKNOWN、身份行终态）再走失败/恢复流程；输出超限分支使用原子版本并带同身份兜底。恢复任务接管运行时把未结算身份行收口为 0/0+UNKNOWN 显式终态，不虚构消耗、不长期停留"调用中"。
3. **规划工具业务错误不可纠正**：`recordToolFailure` 对 BusinessException 透出代码作者编写的确定性原因（如"规划日期超出项目范围"），参数校验附字段路径（如 `maxTaskCount,planDueDate`），非业务异常仍为通用提示，不透出堆栈或 SQL。**三次 VALIDATION_ERROR 的实际根因已查明**：模型三次传入 planDueDate=2026-11-27，超出项目窗口 2026-10-04..10-31，被 `validateCreate` 拒绝——并非"新会话缺约束历史"；此前错误反馈丢失了可纠正信息，模型只能盲改 goal/constraints。

**完整前置状态复验**（`planning-reverify`，代码 `b2d6f2d`，隔离副本，embedding 可用）：新会话按原措辞重放第 1/7/10/18 轮（建立日期/负责人/10→目标切换→8→新目标+6 的完整前置），工作状态与冻结 fixed24 终态一致（active=最多6项+日期区间+Local Owner，10/8 superseded 可追溯）后执行第 23 轮：start_task_plan **一次受理成功**（无重复盲试），规划服务链路 SKELETON SUCCESS → DETAIL FAILED（PLANNING_MODEL_INVALID_OUTPUT）→ REPAIR SUCCESS（既有局部修复路径）→ READY v2：**6 项任务、3 个里程碑、日期全部落在 2026-10-05..10-25、负责人齐备、依赖无环、validation.errors=[]、草稿零旧数量残留、6 条真实检索来源**；confirmations=0、正式任务=0。账本审计：5 个运行、9 次模型调用=9 条已结算身份行、0 未结算、0 对账差异。测试：AgentActualTokenUsageIntegrationTest 15 项（新增 4 项故障注入/原子回归），后端全量 1034 项 0 失败。

## 结算顺序闭环轮（2026-10-05，代码分支 `codex/context-foundation` 最新提交）：外部三次评审剩余三项结算问题的封堵

外部三次只读评审确认（按"同一调用无论按什么顺序结算，总额最终只增加一次，已有真实证据不丢失"组织验证）：

1. **原子入口未检查身份占用结果**：`claimIdentity` 更新 0 行时原子入口仍继续累计。修复：`claimIdentityOrThrow` 占用失败（已结算或身份不存在，区分报错）即在事务内抛出回滚，累计不会发生。真实 PG 顺序回归：正常→同身份再次正常记账（刷新版本绕过业务 CAS，检验身份独占本身）→ 拒绝且总额只增加一次、步骤只写一条；补结算→正常记账 → 拒绝且总额不变、零步骤。
2. **恢复收口永久拒绝迟到真实用量**：收口语义改为"用量未确认"（0/0+UNKNOWN），`settleOrphanUsage` 的占用谓词从"未结算"放宽为"未确认"（UNKNOWN 且两侧无已确认数值）——恢复收口的行与未知结算的行都允许迟到证据幂等补全一次（此前入账为 0，补全后总额恰为真实值）；PROVIDER/ESTIMATED 属已确认结算，拒绝覆盖。原测试把"恢复后拒绝真实用量"断言成正确行为，已改正。
3. **异常结算丢掉异常携带的提供商用量**：`ProviderResponseFailure` 携带的 prompt/completionTokens 优先保留（含单侧：提供商侧 PROVIDER、缺失侧 UNKNOWN/按证据估算），协调器两个异常分支与摘要的首次调用/重压缩异常路径统一走该规则。

测试组织为顺序矩阵（真实 PostgreSQL）：正常→正常重复、补结算→正常、正常→补结算、恢复收口→迟到证据、未知结算→迟到证据、估算已确认→迟到拒绝，每种顺序下总额恰增加一次、真实证据不丢失；另补异常携带完整用量与仅单侧用量两回归（协调器）与摘要异常携带用量回归。AgentActualTokenUsageIntegrationTest 19 项、AgentRuntimeCoordinatorTest 29 项、AgentContextSummarizerTest 24 项；后端全量 1042 项 0 失败/0 错误、11 显式 opt-in 跳过。按评审意见未重跑规划链路或 24 轮批次。

## 环境恢复

见 [database-final-state.json](database-final-state.json)。宿主经 stop 标记正常停止（finally 恢复模型配置），隔离容器停止但保留，卷未删除；未部署、未合并 main、未迁移正式库。私有登录缓存、批次状态文件（target 下）与宿主日志保留在忽略的 target 下。
