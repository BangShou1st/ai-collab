# Agent 维护性与课程思路对照审查

日期：2026-10-05。审查对象：`E:\project\ai-collab` 当前工作区，分支 `codex/context-foundation`、HEAD `d5f09ab`，包含最近完成但未提交的稳定性与资料完整性修改。

本轮只读业务源码、测试、已有报告及课程源码，新增本文；没有修改业务代码、运行测试、启动服务、操作数据库、调用真实模型或提交。本文中的问题分为维护债务、已有功能缺口和质量待验证项，三者不混用。

## 1. 决策

**继续保留自编排，先完成一轮有明确终点的 Agent 核心维护，再开发暂停与可靠继续。当前没有证据支持立即迁移执行框架。**

框架能够减少通用代码，但迁移后仍要维护项目权限、审批、业务幂等、规划受理身份、模型动态配置以及前端事件协议。若原执行器没有退出，再叠加框架状态、检查点和工具循环，会增加需要同步的边界。当前还没有本项目的替换对照，不能声称维护工时会显著下降。

此前 [框架调研](E:/project/ai-collab/docs/agent-runtime-framework-decision-20261005.md) 将框架验证排在扩建暂停恢复之前。对照最新源码与课程后，本文将优先级收敛为“现有实现的有限维护 → 已知恢复边界验证 → 基础体验开发”。旧文中的框架组合保留为候选，不作为本轮开发任务；旧文中经源码确认的恢复缺口仍然有效。

自编排当前不是无法维护：已经有 `api / application / domain / infrastructure` 分层，工具执行、消息组装、摘要、模型路由也已分开。最新修改使组装器不再重读模型配置，请求快照贯穿准备与出站；前端运行状态已由 timeline 派生。这些都是正确的收敛。维护困难主要集中在少数核心类的职责重叠、隐式装配、遗留入口以及跨层契约，不能仅凭文件多或测试多判定架构失败。

用户最新取舍已纳入：代码行数不作为本轮目标，文中规模仅帮助定位职责。以改一个需求时需要理解和修改多少边界、依赖是否明确、关键行为是否容易验证来衡量维护收益。

## 2. 实际看了哪些课程内容

重点目录：[本地课程](E:/project/LangChain_Official_Course/lca-lc-foundations/README.md)。核对了仓库的 `origin` 指向 `langchain-ai/lca-lc-foundations`。本地若干 notebook 已改模型并增加中文注释；关键摘要、审批、动态模型示例同时读取了该课程仓库 HEAD 中的版本，以区分原示例、中文解释和本地适配。

| 课程内容 | 值得借鉴的思路 | 对本项目的取舍 |
| --- | --- | --- |
| [1.3 memory](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-1/1.3_memory.ipynb) | 用会话身份关联持续状态 | 保留现有 session/run/message 记录，聊天历史与执行进度各有职责 |
| [2.2 state](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-2/2.2_state.ipynb) | 跨轮业务状态独立于消息正文；工具结果关联调用身份 | 保留结构化工作状态和 invocation 身份；当前约束不只靠自由文本摘要 |
| [2.2 runtime context](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-2/2.2_runtime_context.ipynb) | 每次调用由服务端注入可信上下文 | 用户、项目、权限和配置由服务端确定；模型不能改调用者身份 |
| [3.2 managing messages](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.2_managing_messages.ipynb) | 最新内容保留原文，旧对话压缩；压缩职责独立 | 保留当前增量摘要，借鉴清楚的触发与保留规则；不直接复制演示参数 |
| [3.3 HITL](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.3_hitl.ipynb) | 待审批动作保存后，在同一会话恢复 | 保留业务审批身份、权限复核和结果复用；审批等待与主动暂停区分 |
| [3.4 dynamic models](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.4_dynamic_models.ipynb) | 每次模型调用有统一请求准备边界 | 按用户当前 AGENT 配置选择；同请求快照一致，在途请求完成后下一次生效，不加自动择模 |
| [3.4 dynamic tools](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.4_dynamic_tools.ipynb) | 工具可见性随可信上下文调整 | 工具暴露和实际执行都校验权限；恢复旧调用还按其来源模式处理 |
| [3.4 dynamic prompts](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/3.4_dynamic_prompts.ipynb) | 通用规则与本次上下文组合 | 保留 Skill 任务要求；收敛提示词来源，无需自建通用 middleware 框架 |
| [2.3 multi-agent](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-2/2.3_multi_agent.ipynb)、[2.4 wedding planners](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-2/2.4_wedding_planners.ipynb) | 专项任务封装；到限后返回已有发现 | 不为已有确定性业务服务另套 Agent。服务端管理硬限制，模型负责在允许范围内总结 |
| [bonus RAG](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-2/bonus_rag.ipynb) | 需要资料时检索相关片段 | 文档检索和会话压缩分开；任务、里程碑完整清单仍按数据库分页读取 |
| [Agent Chat UI](E:/project/LangChain_Official_Course/lca-lc-foundations/notebooks/module-3/agent-chat-ui/src/providers/Stream.tsx:70) | 流连接集中管理，页面从流状态派生 | 借鉴状态归约与订阅所有权；保留现有 Vue 页面和已验收的过程可视化 |

课程中需要按业务重新判断的内容：

- 摘要示例是 `trigger=(tokens,100)`、`keep=(messages,1)`，属于为了立即观察机制的演示设置；不适合作为本项目默认参数。
- memory/HITL 示例使用 `InMemorySaver`。它说明状态恢复接口，不证明进程重启后仍能恢复。官方也明确内存检查点重启后丢失，生产需要持久存储。[LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- `3.2` 的删除示例直接移除 ToolMessage，不能直接照搬到原生工具协议中；我们仍需保持调用与结果配对，并保留聊天及业务审计记录。
- wedding 搜索工具由模型提供 `search_number` 和 `max_search_number`，然后比较两者。它没有可靠的服务端累计计数，模型可以报错次数或更改上限。可借鉴“到限提示收尾”，不能用它替换现有硬保护。
- 课程 UI 的 `streamResumable` 是流恢复能力；单看前端调用不能证明已完成业务动作不会重复。它也依赖 LangGraph 服务和 SDK，并非直接可接本项目接口。

## 3. 维护债务：按收益安排，而非全面重构

### M1：统一生产装配，消除隐式可变依赖

证据：[协调器构造函数](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:61) 接收 15 个参数，再内部 `new` 消息组装器、工具执行器和摘要器。摘要器同时标有 [@Component](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java:46)，协调器实际使用自己新建的实例。生产容器装配与手工构造测试因此存在两套路径。

[工具执行器](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentToolCallExecutor.java:54) 默认使用 commonPool，协调器再通过 setter 注入 scheduler/limiter；[模型路由](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/RoutingAgentModelExecutor.java:36) 也通过后置方法注入配置存储，保留 null 时的另一条解析路径。这不是当前生产调用必然出错的证据，但会让开发者难以判断哪些依赖是必须的、哪些行为只是测试兼容。

建议：生产装配集中在现有 `AgentRuntimeConfiguration`，协调器显式注入三个已有协作者；复用容器中的摘要器。工具调度器、配置存储成为明确的构造依赖。单测显式提供受控协作者/执行器，不让生产依赖为空来迁就测试。普通纯函数辅助类仍可直接构造，不要求每个小类都注册 Spring Bean。

结束条件：生产使用的摘要器只有一条装配路径；执行器调度与配置来源在构造时确定；当前请求一致性、来源模式恢复、并行查询及串行写入行为不变。

### M2：把工具结果投影从消息组装中分离

证据：[AgentModelMessageComposer](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:199) 约 880 行，承担消息挑选、查询历史、状态渲染、提示词、原生调用配对及工具结果缩减。其中 [467 起](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:467) 的约 280 行处理列表页、规划输出、正文和通用 JSON 投影。改分页契约要理解整套上下文组装，改组装又可能伤到分页，这是实际维护耦合。

建议：仅抽出一个具体的 `AgentToolOutputProjector`，输入工具结果与容量，返回模型可见结果；不读取模型配置，不访问数据库，不决定执行下一步。组装器继续负责选取哪些内容和维持消息协议。资料是否过期仍由原查询边界判断，再把结果传给投影器。

保留最新 `ListPageContract`：游标表示第一条未返回记录，模型可见记录、计数、taskFacts 与续页位置必须一致。无需为了两个工具创建通用分页插件、项目级数据投影引擎或一组策略接口。

结束条件：工具投影可以独立阅读和验证；现有“工具分页 → 清洗 → 模型视图 → 续页”串联回归继续通过，协议配对和当前请求保留不变。新测试只覆盖抽取后真正改变的装配或行为风险，不镜像实现。

### M3：清理已经无生产调用的提示词入口

证据：当前 main 源码中 `AgentPromptFactory` 的引用只剩 [Bean 创建](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRuntimeConfiguration.java:16)；其 `systemPrompt/userPrompt` 调用出现在自身测试。当前实际提示词在组装器等生产路径中。它看起来仍是可用入口，并有绿灯测试，容易让接手者改错位置。

建议：开发时再核对全仓与动态 Bean 使用，确认没有外部契约后删除旧工厂、注册和仅服务旧实现的测试。其他 Legacy 兼容不能顺带删：CHAT-only 模型路径与旧数据库中没有 source_mode 的调用恢复仍有真实用途。`RoutingAgentModelExecutor.isNativeToolError` 当前也没有调用，可同批核对清理。

结束条件：开发者从运行入口能追到实际生效的提示词；公共规则只有明确来源，Skill 仅增加任务要求，不保留两套貌似在用的 Agent 提示词。

### M4：修正两处目录边界，立下少量约定

证据：[AgentLoopGuard](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentLoopGuard.java:4)、[AgentConvergencePolicy](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentConvergencePolicy.java:3) 放在 domain，却导入 application 的 RunView/StepView。这是包层级反向依赖；不等于整个应用存在无法启动的循环。

建议：这两个策略用于 Agent 执行控制，移到 `application/runtime` 即可；不要为了层级图新增同字段 DTO 与映射器。保留原有业务分层，不全仓改名搬文件。

目录约定：

| 位置 | 负责什么 | 新增代码的边界 |
| --- | --- | --- |
| `agent/api` | HTTP/SSE 与请求响应转换 | 不承担执行循环或直接拼数据库操作 |
| `agent/application` | 会话、运行、审批、规划受理等用例 | 调用已有业务应用服务，不让模型参数决定权限 |
| `agent/application/runtime` | 本轮请求准备、上下文、模型/工具推进、执行控制 | 各职责有唯一入口；不重复解析同一请求的配置 |
| `agent/domain` | 业务语义、状态规则、工具及 Skill 契约 | 不导入 application/api/infrastructure |
| `agent/infrastructure` | SQL 持久化、工具对业务服务的适配、外部协议 | 维持原子写入与调用身份，不隐含新的调度循环 |
| 前端 `modules/agent` | 工作区编排、纯归约、可视组件 | timeline 为运行视图来源；订阅统一管理；异步结果核对项目/会话/恢复序号 |

结束条件：这两处 domain 反向导入消失，约定落在一个维护文档或现有项目规范入口，关键路径能够用一张调用图解释。

### 下一轮不强行处理的结构

- `AgentRunEventRecorder` 约 1020 行，包含状态、步骤、事件、摘要尝试及用量写入；确实集中，但不少行为必须在同一事务内。先标明事务契约，不为降低行数拆成多个相互调用的 Bean，避免损坏原子性。
- `AgentRepository` 的写路径委托并不自动构成需要改造的“双系统”；不要为每个方法补一个接口，也不机械消除所有事务注解。
- `AgentWorkingState` 的约束提取、历史修正和版本兼容较复杂，但服务于已验收的需求记忆。后续改该能力时可提取纯约束处理函数，当前不替换成一套语言解析引擎或自由模型状态。
- 用量值类型嵌在 Recorder、工作状态 schema 常量在摘要器有副本，属于小范围耦合。可在相关代码修改时收敛，不扩建 token 精确结算。
- 前端 workspace 约 510 行、页面约 400 行，已有 activity/conversation/timeline 的拆分，运行状态已从 timeline 派生。无需再引全局状态框架或复制课程 React 页面。

## 4. 现有摘要值得保留吗

**值得保留。针对本项目，工程保障较完整；真实模型的语义保留质量仍未验证到可以称为优秀。**

当前流程是：保留最新对话和当前请求，把未入选的旧对话交给有界增量摘要；工作状态中的有效约束独立保留；摘要与资料检索各自承担职责。

| 方面 | 当前实现与证据 | 判断 |
| --- | --- | --- |
| 当前目标与约束 | 生成时携带最新 goalRevision、activeGoal、latestRequest 与有效约束；主请求也独立注入工作状态 | 保留，避免把所有要求寄托于摘要自由文本 |
| 覆盖真实性 | 长消息记录分段偏移，跨运行推进；短消息完整覆盖；读取不只限最近历史；已有摘要全文进入下一次生成 | 保留；覆盖元数据表示提供给摘要器的范围，不保证模型逐项保留，也不表示文档全文已读 |
| 并发安全 | 生成期间不持数据库行锁；按 stateRevision/goalRevision 做 CAS，仅更新 summary 节点 | 保留，防止迟到摘要覆盖新需求 |
| 有界失败 | 每个运行一次摘要尝试，超长最多再压缩一次；容量/取消不允许时跳过，失败保留旧摘要及覆盖位置 | 保留，不新增无限修复循环 |
| 来源区分 | 提示词区分用户要求、旧文档、真实工具事实、助手未核验陈述，并要求保留局部读取范围 | 方向正确；这是提示要求，不是已证明模型始终遵守 |
| 语义质量 | 提交校验实际是非空、长度不超过 1200 字符；24 项摘要测试主要验证提示输入、覆盖、CAS、失败等机制 | 不能由通过数推导真实摘要无遗漏、无错误 |
| 触发与时机 | 旧内容未入选时尝试摘要；主 messages 已先组装，新摘要本次没有重组进入主请求 | 本次会承担额外等待，收益主要在后续请求。属于需判断的性能取舍，不因此立刻新增后台任务系统 |

框架现成组件的实际差别：

- LangChain Python `SummarizationMiddleware` 提供触发阈值、最近内容保留、摘要模型、自定义提示词等通用机制；也会额外调用模型。借鉴其清晰参数与职责边界，不推导其摘要一定更准。[官方摘要中间件](https://docs.langchain.com/oss/python/langchain/middleware/built-in#summarization)
- Spring AI 默认 `MessageWindowChatMemory` 是滑动窗口及淘汰，不能与语义摘要等同。[Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- Spring AI Alibaba 确实有 `SummarizationHook`，可配置摘要模型、触发 token 上限与保留消息数。若采用其 Agent，还需接好我们自己的业务状态与持久化契约。[官方 Hook 说明](https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks/)
- LangChain4j 的标准 ChatMemory 文档列出的现成实现是消息窗口、token 窗口；完整历史另存，扩展也可自行实现，不能宣称“引入后自动拥有完整可靠摘要”。[LangChain4j Chat Memory](https://docs.langchain4j.dev/tutorials/chat-memory/)

不建议为了摘要一个组件迁移整个执行器，也不建议同时让两套摘要机制维护同一份记忆。

后续质量验证采用少量真实需求：旧“最多 10 项”被新“最多 6 项”覆盖、取消旧约束、助手误称已创建后被真实记录纠正、长内容尾部决定、分页/片段范围。看真实模型生成的摘要及下一次回答有没有保住这些要点。复用现有隔离环境与测试材料，获得授权真实配置后执行；不新增评分平台、多模型竞赛或第二个模型裁判。

在验证前，6000 输入字符、600 长消息片段、1200 输出字符是现有有界配置，不随意调整。一次模型请求的上下文窗口约束仍需尊重，但无需逐型号扩建预算策略或自动模型选择。

## 5. 维护完成后还要处理的基础能力

代码质量改善可以减少修改时的牵连，不能代替功能修复。以下在最新工作区仍需分别处理：

1. **崩溃恢复计时**：生产租约为 [6 分钟](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRuntimeJob.java:28)；[过期接管](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java:345) 把旧 claim 到租约到期累计为执行时长；[协调器](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:157) 最大只允许 5 分钟且先查时长再恢复工具。这会使接管很容易直接超时终止，属于高优先级体验缺口。需要故障注入和明确计时语义，单纯调大限额不能作为修复。
2. **已落库纯文本结果的恢复**：[pendingModelTurn](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java:284) 只恢复含工具调用的轮次。保存纯文本 MODEL_TURN 到另一次最终提交之间若退出，没有相同恢复分支。这是源码推导风险，尚未做本轮故障注入；优先验证后决定最小补齐方式，不立即加全套 checkpoint 表。
3. **主动暂停/继续**：目前取消、等待用户输入和终态派生重试不等于主动暂停。需要持久记录暂停意图，在动作边界停住；继续读取现有进度，已完成的动作与审批不重复。已受理的后台规划继续完成，恢复时读取原规划身份结果。
4. **到限继续剩余工作**：目前 BUDGET_EXCEEDED 的终态重试创建新运行，尚未实现剩余工作的续接。后续先读已有结果和未完成目标，只做剩余部分；不得重放已完成写入。原问题太大、单次放不下时要说明缺口与缩小范围选项。
5. **自动重试体验**：现有暂时模型失败的自动重试不是空白；需要明确展示重试等待和最终失败，恢复位置可验证。不能把所有异常一律重试，也不能给已有重试再叠一层框架默认重试。

多用户调度的串行等待风险保留在后续负载验证；没有压测证据，不先引分布式队列。逐字流式、精确费用、自动择模仍是独立待办，不塞进这轮维护。

关于之前“回答太短”：截图的 `acceptance-b` 来自脚本模型；[ScriptedAcceptanceModel](E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/acceptance/ScriptedAcceptanceModel.java:97) 写死了相同收尾句。它证明展示链路，不证明真实 Agent 分析能力。新报告也明确真实模型三类问题质量未验收。资料链路已经补齐，下一步要用实际配置观察真实回答，不能据这张截图继续推导工具或框架是根因。

## 6. 开发顺序与验收终点

### 维护批次：仅 M1–M4

先保留当前已验收工作区，记录本批次起始快照；按四项维护逐步实施，每步可单独复核。它们都不需要修改 API、数据库 schema、产品交互或模型行为。维护批次可以独立交付，不等待所有后续功能。

验收：

- 所有生产必需依赖在装配时确定；摘要器没有重复生产实例；测试不再依赖生产组件的隐式 null 分支。
- 组装器只调用具体投影组件，投影不依赖数据库或配置读取。
- 确认无生产消费者的旧提示词入口退出；两处 domain 反向依赖修正。
- 动态模型请求快照、原来源模式恢复、分页完整性、审批和已完成结果复用、原子事件写入的既有关键回归通过。
- 前端若没有变更，沿用本轮已有验收证据，不为后端纯结构维护重跑全部浏览器流程或 24 轮。
- 受影响回归通过后，结束时做一次约定的全量校验；成功后只有新增改动或未解决失败才扩大/重复测试。
- 本批次没有新增执行框架、通用 middleware 引擎、每类一个接口、全仓目录迁移、额外状态副本或计费功能。

### 下一开发批次：恢复可靠性与用户体验

维护结束后，先验证并修复崩溃计时和纯文本结果恢复，再设计主动暂停与继续；真实回答质量可作为独立验收，不阻塞无模型依赖的恢复工作。保持用户已认可的需求：下一次按当前配置换模型、自然过程说明、真实活动可视化、自动重试、审批保护、暂停后后台规划继续、继续时复用已完成动作。

只有当具体实现表明通用执行恢复仍迫使我们在多处重复维护同一机制，或确实需要复杂图/子图执行时，再针对单个职责做框架替换对照。决策依据是能退出哪些旧职责、剩下多少适配、同一故障场景是否更可靠，不能以几行教程代码或功能目录判定收益。

## 7. 证据边界

本轮审读了 [最新完成报告](E:/project/ai-collab/docs/agent-stability-and-quality-report-20261005.md)，未独立重跑其后端全量、前端或浏览器验收。现有 Surefire XML 中摘要 24 项、配置切换 PG 集成 9 项、分页串联 9 项均零失败、零错误；这些为既有运行产物，不计为本轮新测试。缓存报告时间不完全一致，不把所有 XML 机械汇总为一次新全量结果。

真实模型的回答及摘要语义质量、并发负载、本文列出的纯文本崩溃窗口未在本轮运行验证。结构维护建议基于源码依赖和职责，框架比较基于官方文档及本地课程，不包含迁移收益实测。
