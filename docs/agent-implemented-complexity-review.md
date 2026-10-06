# 已实现的 Agent / 规划复杂度审查

日期：2026-10-05。基线：d5f09ab，分支 codex/context-foundation。

前四节记录基线的只读审查，第五节记录用户要求先清理过度设计后的实际精简与验证。审查范围集中在 Agent 与规划相关链路，未声称覆盖整个项目；未启动业务服务或调用真实模型。

用户后续要求 Agent 按当前配置支持中途换模型，并限定下一轮只做设计。最新方案见 [Agent 体验设计](E:/project/ai-collab/docs/agent-experience-design.md)；本文关于固定配置的描述是当前源码现状，不再作为后续设计要求。

用户要求：失败自动重试、断点恢复、暂停后继续都属于重要基础能力。简化应减少实现这些能力时的重复状态与规则，保留权限、版本、动作身份、租约和结果核对。此前修复轮的收尾结论不受本轮维护性审查影响。

## 1. 已确认的冗余和收敛点

### A. 无生产调用的旧预算与规划限流实现

证据：生产源码中 AgentBudgetPolicy 只有自身声明；AgentBudget 除该旧 policy 外没有业务引用，测试只验证对象的扣减方法。实际执行直接使用 agent_run 计数、AgentRuntimeLimits 与收敛策略。

- AgentBudgetPolicy 与 AgentBudget：可在基线 d5f09ab 的 agent/domain/policy 与 agent/domain/model 中核对；本轮已删除。

PlanningGenerationRateLimiter 为 Spring component，但仓库内未找到 check 的业务调用；当前服务使用成功生成配额 PlanningGenerationQuotaService 和短时防刷 PlanningAttemptThrottle。

- PlanningGenerationRateLimiter：可在基线 d5f09ab 的 planning/application 中核对；本轮已删除。
- [当前命令依赖](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanCommandService.java:74)

建议：清理这些闲置实现及仅为闲置对象服务的测试。成功配额与短时防刷作用不同，应保留，不能因为都叫“限流”就合并。此项不需要新增抽象或数据库迁移，属于可独立收尾的低风险清理候选。

### B. 固定参考模板被包装为带状态、版本和 Replan 的执行计划

AgentPlanService 根据 skill 生成固定三到四步模板；goal 与 pageContext 没有用于调整具体步骤。replan 只再次生成同样模板并增加版本，其 reason 未被使用，源码未找到调用点。AgentPlanStep.withStatus 没有调用点，实际工具执行不推进这些步骤。

- [计划生成与 Replan](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentPlanService.java:30)
- AgentPlanStep.withStatus：基线的闲置方法，本轮已删除；历史步骤字段继续兼容。
- [模型提示中的模板](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:666)

前端已经诚实标为“参考步骤”，实际进度另由工具事件显示，因此不能把此项当成仍在虚报完成状态的新缺陷。

- [前端参考步骤](E:/project/ai-collab/ai-collab-frontend/src/modules/agent/AgentRunTimeline.vue:37)

建议：保留简短参考说明和真实工具进度，逐步退出没有执行作用的 Replan、步骤状态和 expectedTools 包装。历史 plan_json 与事件继续可读；先清理未使用方法，不为了简化再建设动态计划引擎。任务规划模块的正式草稿、版本和人工确认与这里的 Agent 参考模板不同，不能删除。

### C. 运行限额来源重复，实际检查到处计算

运行创建与重试在 Repository 内用 skill 名分别设置 24/16 或 12/8；其他输入/输出上限依赖数据库默认值。AgentRuntimeLimits 又保存一组通用与按 skill 的限制，ContextAssembler 再按 skill 加载。各 skill 的 defaultLimits 方法有实现，但生产源码未找到消费这些方法的调用。

- [运行创建限额](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java:131)
- [重试创建限额](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java:176)
- [运行限制定义](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentRuntimeLimits.java:37)
- [上下文再加载限制](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextAssembler.java:111)

例：PROJECT_HEALTH 的数据库运行 maxSteps 为 12，而运行时定义为 8；输入分别为数据库默认 50000 与运行时 40000。不同字段在不同路径使用运行值或两者的最小值，增加理解与变更成本。本轮没有将此差异直接判为已复现的预算缺陷。

建议：复用现有限制定义，统一运行创建、重试和有效限额的来源，明确快照值和系统硬上限的关系。为输入、工具、时间等限制保留各自作用；不要删掉事务内检查，也不要为了“统一”未经评估就改变当前实际限额。单次模型窗口与整次运行额度作用不同，仍应区分。

### D. 自动重试的计数与规则需要明确统一

模型临时失败在 Agent 层可自动重试。recordFailure 同时维护 agent_run.retry_count 与 agent_recovery_counter.MODEL_RETRY；前者控制门槛，后者用于界面展示。门槛为 2，旧注释与数据库计数封顶仍为 10。

- [重试判定与双处计数](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRunEventRecorder.java:285)
- [恢复计数读取与限制](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java:46)

不能直接称两份计数完全等价：最后一次失败也会增加 retry_count，而不再增加 MODEL_RETRY，因此终态可能分别是 3 与 2。问题是“失败次数”与“已安排重试次数”的含义不明确，又都参与同一能力，后续维护容易混用；本轮未复现由此造成的新运行错误。

建议：明确一次失败、一次实际尝试和一次安排重试的定义，为同类重试保留一个权威门槛与计数；旧 API 字段可由该事实派生或保留兼容。网络重试、格式修复、参数纠正依旧分开，因为需要的补救输入不同。所有自动补救共用原运行剩余额度与动作身份，避免恢复后无条件刷新次数。

HTTP 客户端含 maxAttempts 重试循环，但核对的生产适配器使用默认单次调用。不能凭有循环就声称当前存在“HTTP 重试三次乘 Agent 重试三次”；本轮没有发现这样的真实叠加调用链。

### E. 前端当前运行存在两份需手动同步的状态

useAgentWorkspace 保存 activeRun 与 timeline.run；加载、事件回调、持久状态核对和重试成功后多次手动同步。runDetail 还包含快照、计数等独立信息，应继续保留。

- [双份运行状态](E:/project/ai-collab/ai-collab-frontend/src/modules/agent/use-agent-workspace.ts:92)
- [事件后的同步](E:/project/ai-collab/ai-collab-frontend/src/modules/agent/use-agent-workspace.ts:328)

建议：让 timeline.run 成为当前运行状态的唯一来源，activeRun 改为派生读取；runDetail 保留诊断与其他详情。现有作用域校验、AbortController、事件序号去重、持久状态核对不能删。本轮未做浏览器复现，不将此项表述为已发现新的页面错乱。

## 2. 复杂但暂不宜直接删的部分

双上下文组装实现：composer-v2 默认开启，旧 buildMessageHistory 专门用于部署回退，文档和测试明确依赖该契约。它是过渡复杂度；后续确认不再需要旧组装回退时可退出旧实现，同时保留旧 working_state 数据兼容。本轮未检查真实部署环境，不建议立即移除开关。

- [旧组装入口](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:118)
- [回退约定](E:/project/ai-collab/docs/deployment-recovery.md:17)

工作状态的中文规则：数量、日期、负责人、否定和旧要求替代已形成较多专用规则，具有继续膨胀的风险。但这些规则保护用户明确要求，已有真实验收与回归证据；不应简单删除后全部交给模型猜测。保留当前边界，避免按每个句式无限补规则，不再把它扩成通用中文语义解析器。

- [约束抽取规则](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentWorkingState.java:337)
- [核心动作识别](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:495)

模型适配：现有窗口处理只是可选数值映射，原生工具与 Legacy 路由按能力选择，暂未发现逐型号的 Agent 策略库。它不是当前最大的复杂源。不同提供商协议、运行固定配置和授权差异仍有真实作用，无需为简化强行抹平。

已有用量结算修复：原子身份、异常透传和防重复结算已经交付，停止扩展精确费用边角。不要回退已完成正确性修复，也不要把继续完善计费列为恢复功能的交付前置条件。

## 3. 核心能力的真实进度

| 用户关心的能力 | 当前实现 | 处理原则 |
| --- | --- | --- |
| 模型短暂失败自动重试 | Agent 对 AI_MODEL_TIMEOUT / AI_PROVIDER_ERROR 自动补救，最多安排两次，间隔 30 秒；格式修复另有一次额度 | 保留，统一决策、语义和显示，不以精简为由移除 |
| 完整模型轮次与已完成工具恢复 | 已有持久轮次、调用身份、结果复用 | 以这些事实补强恢复，不新增一套重复日志 |
| 程序退出后接管 | 已有过期租约接管，但停机等待可能耗尽运行时长 | 优先修计时与隔离，不能宣称完整恢复体验已完成 |
| 用户主动暂停后继续 | 尚无通用 PAUSED / resume | 核心待实现能力，按简化设计在动作边界恢复 |
| 后台规划短暂提供商错误自动补救 | 生成入口遇到提供商异常通常直接记录失败；格式/领域输出有一次修复；排队恢复不是网络失败自动重试 | 需要独立补齐有界补救，保留 plan/generation/attempt 身份 |
| 预算终态后只继续剩余工作 | 当前是派生新运行重新尝试，尚无可靠跨运行断点续接 | 需求保留，单独交付，界面不把重新尝试宣传为断点续跑 |

证据：[Agent 错误分类](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:309)、[轮次恢复](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java:284)、[规划提供商失败](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java:433)。这些为代码能力边界，未用历史测试通过数替代新的真实故障验收。

## 4. 建议执行顺序与收尾条件

1. 优先补强自动重试、崩溃恢复计时和暂停继续。每项只改必要路径，保留已提交动作不重复、当前目标不丢失、等待不消耗执行时间的验收。
2. 清理无调用的预算对象、旧限流器和 Replan 方法；范围小、可单独提交，不绑全套恢复重构。
3. 在改动相关模块时统一限额来源、重试计数语义和前端运行状态，先保持行为再简化结构。
4. 旧上下文实现等有真实回退契约的部分，待退出条件满足后再移除。中文规则和费用边角停止无边界扩展。

不做全项目“架构洁癖”重构，不新增通用策略工厂、通用工作流引擎或全局轮询框架。每项改动按自己的有效测试收尾，不把闲置代码清理和所有后续能力绑定为一次无尽验收。

## 5. 按用户最新顺序实施的精简

用户随后要求先处理已经实现的过度设计，再补基础能力。本轮据此完成：

- 删除无生产调用的 AgentBudget、AgentBudgetPolicy 和旧 PlanningGenerationRateLimiter，移除只验证已删除预算对象的两项测试。真实运行预算、成功配额和短时防刷保留。
- 移除 AgentSkill.defaultLimits 及六个未被消费的实现；现有 AgentRuntimeLimits.forSkill 继续提供运行时限制，限额数值未改变。
- 删除闲置 Replan、withVersion、withStatus；固定参考模板收敛在一个创建函数中，不再附带无消费者的旧工具名。历史 JSON、事件与参考步骤展示保持可读。
- 系统提示把固定步骤明确标为参考内容，不再把始终未推进的 PENDING 状态输出为执行状态。
- 前端 activeRun 改为由 timeline.run 派生，删除各回调中的手动同步赋值；项目/会话作用域保护、事件序号及持久状态核对保留。
- 修正模型重试计数注释：失败次数与已安排重试次数确有不同，不能直接当作重复数据删除。本轮保留其门槛、间隔、持久计数及历史字段。

本次没有改变数据库结构，也未新增框架、策略工厂、预算等级或恢复点表。存量兼容与上下文回退已有明确用途，继续保留；暂停/续接能力没有被本次清理冒充实现。

### 课程参照

参考用户提供的本地课程仓库 E:/project/LangChain_Official_Course/lca-lc-foundations。该工作区的部分 notebook 已有中文注释和模型替换，因此同时读取 Git HEAD 中的原始单元，未修改课程文件，也未执行 notebook。

- [3.2 消息管理](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.2_managing_messages.ipynb)：摘要通过一个入口应用，保留近期消息；示例 trigger 为 100 token、keep 为 1 条消息，是演示参数。本轮沿用已有 Java 摘要与当前请求保护，没有复制该演示阈值或删除所有工具消息。
- [2.2 状态](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-2/2.2_state.ipynb)：业务信息共享同一份状态，工具返回状态增量；对应本轮前端运行状态收敛。
- [3.3 人工介入](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.3_hitl.ipynb)：沿原 thread_id 恢复审批对象；对应保留原操作与审批身份的原则，不能用普通新请求代替恢复。

课程示例采用 InMemorySaver，不能用它证明进程退出后仍可恢复。仓库预算关键词主要出现在婚礼规划的业务金额输出，未发现能直接替代本项目执行限额的预算实现。动态模型课确有按消息数切换模型的演示；本项目将按用户后续要求允许配置变化在下一轮调用生效，不增加系统按消息数自行择模的策略。

### 验证

前端 vitest：35 个文件、148 项全部通过；vue-tsc -b 通过。

后端执行 mvnw clean test，退出码 0；Surefire XML 实际汇总为 149 个测试类、1062 项：1051 通过、11 跳过、0 失败、0 错误。11 项跳过均因未启用对应 opt-in 环境变量，不是缺少 Docker。闲置预算对象的两项测试随对象删除，真实运行预算检查没有放宽。

关键 PostgreSQL 回归实际执行、零跳过：AgentRunRetryPostgresIntegrationTest 6 项、TaskPlanGenerationOrchestratorIntegrationTest 5 项、AgentRepositoryIntegrationTest 39 项、AgentActualTokenUsageIntegrationTest 19 项，全部通过。测试结束后 docker ps 为空，隔离容器已自动清理。

git diff --check 通过。本轮未启动业务服务、调用真实模型、重跑 24 轮或进行浏览器验收；没有修改数据库迁移。改动保留在当前工作区，尚未提交或推送。
