# AI Collab：AI 能力与模型切换兼容性审查

审查开始：2026-10-02；设计更新：2026-10-03。范围：当前工作区，包括已有未提交改动。本文记录审查和目标方案，不代表生产代码已修复。

## 结论

保持 Java 编排可行。当前问题主要在模型能力声明、输出契约、修复流程、工具结果与业务状态之间的衔接，迁移到 Python 或替换框架不会自动解决。AI 是核心产品能力；RAG、任务规划、Agent 应共同验收。

没有发现规划生成固定调用千问模型的证据；实际按调用者和 PLANNING 用途选择个人配置。但存在依赖特定输出习惯、能力标记和预算的实现。以下复现能够解释“某些模型可用，换模型失败”的机制；缺少用户当时的请求和响应，不能认定其中某一条就是历史故障的唯一原因。

## 1. 优先修复：任务规划的模型兼容性

以下源码路径以 `ai-collab-backend/src/main/java/com/shitulelv/aicollab/` 为根，前端另行注明。

| 问题 | 证据与触发条件 | 影响与建议 |
|---|---|---|
| 自定义配置默认只有 CHAT，规划要求 STRUCTURED_OUTPUT | 前端 `ai-collab-frontend/src/modules/ai/UserAiSettingsView.vue:47,154`；`infrastructure/ai/model/ModelCapabilityPolicy.java:17`。临时探针证实：请求未进入适配器就返回 PLANNING_MODEL_UNAVAILABLE | 连接测试成功不等于规划可用；表单没有对应能力选项。把原生 JSON 支持与应用输出契约分开，按用途测试。此问题针对自定义配置；Zen 预设另有能力覆盖，不能套用到所有 Zen 失败 |
| 网关提前拒绝解析器能够接收的 JSON | `RoutingChatModelGateway.java:131` 直接 readTree；`planning/application/TaskPlanOutputParser.java:200` 能整理 Markdown 代码围栏。探针证实同一份带围栏骨架在解析器通过、网关失败 | 规划层收不到候选输出，无法整理或修复。保留原始响应和错误类别，把规划输出处理交给明确的契约组件 |
| 修复只覆盖部分失败路径 | `TaskPlanGenerationOrchestrator.java:345,404`：骨架在 model.generate 异常时直接失败；详情在解析、合并或硬校验异常时直接失败，未进入 startRepair | 函数名 WithOneRepair 并不表示格式错误也会修复。对可修复的格式/契约错误提供一次有预算的修复；身份越权等错误仍拒绝，限流/凭据错误不进入内容修复 |
| 规划预算配置与实际请求脱节 | `TaskPlanModelClient.java:56,75`；探针中 planning 属性为 6000，传给自定义适配器的个人配置仍为 1200，outputSchema 为 null | 长规划存在截断风险，是否发生取决于模型与输出。明确最终预算来源并记录；Zen 当前精简请求不发送 max_tokens，也不能说该路径实际受 1200 限制 |
| 失败日志可能记录旧模型 | `TaskPlanModelClient.java:75` 失败使用 PlanningModelProperties 的 provider/model，成功使用实际返回值。探针复现 selected=new-model，失败日志为 legacy-model | 用本次解析的配置快照记录实际提供商、模型、阶段、模式和错误。保留错误原因，避免只留下笼统的规划不可用 |
| 局部修复缺少更新后的业务上下文 | `TaskPlanPartialRepairService` 的 prompt 包含草稿、问题、目标和字段范围，未传当前成员/日期/约束快照；存在 APPLY_UPDATED_CONSTRAINTS 模式 | 不能期待模型修复未收到的新约束。补充当前版本、明确约束、成员候选、锁定字段和来源范围；此项为静态检查，尚无真实模型效果评测 |

此外，自定义 OpenAI 兼容路径固定发送部分参数，能力声明与各模型实际能力缺少验证；提示长度采用全局字符限制，不能等同于各模型 token 上限。应通过代表性模型用例验证，避免按模型名称堆特殊分支。

### 推荐的输出处理契约

`模型响应 → 检查完整性/结束原因 → 有界格式整理 → JSON/Schema 校验 → 业务校验 → 一次可修复错误的修复 → 再校验 → 版本化草稿`

- 应用始终要求确定的业务结构；传输模式独立选择 `PROMPT_JSON`、`JSON_MODE` 或 `JSON_SCHEMA`。
- Prompt JSON 是兼容基线；模型明确支持且实测通过时，可以使用原生模式。提示词模式并不保证更高成功率，必须评测。
- 只整理可证明不改变含义的包装，例如完整 JSON 外的 Markdown 围栏；不擅自补成员 ID、忽略未知业务字段、删除约束或把半份 JSON 当完整结果。
- 骨架、详情、局部修复分别定义契约。详情沿用已核验的骨架身份；修复收到必要原任务上下文、候选输出、精确校验错误和不可修改字段。
- 429、认证失败、超时、输出截断、JSON 错误、Schema 错误和业务不满足分别呈现。错误不能被写成成功草稿。
- 一次阶段执行固定配置快照；重试、取消和版本提交沿用现有 generationSeq/attemptId 约束。

## 2. spec-agent 对照：可借鉴原则，不能混淆调用链

只读核对 `E:/project/spec-agent` 当前源码：

- Python 全局助手 `agent-brain/src/spec_agent_brain/global_assistant/agent.py:101` 使用 create_agent 与宿主工具，此调用未设置统一结构化最终回复参数。
- Java `backend/src/main/java/com/specagent/model/provider/OpenCodeModelInferenceGateway.java:103` 按契约区分 Text、JsonObject、JsonSchema；后两者分别生成 response_format 的 json_object 和 strict json_schema。
- `HttpOpenCodeZenTransport.java:823` 会把非空 responseFormat 写入请求。不能据全局助手推断所有项目 Brain 请求都没有原生格式参数。
- Brain 的 `decision/engine.py:186` 先 JSON 解析，再 Pydantic 契约校验；还包含针对已知偏差的有限归一化。可借鉴的是契约、有限兼容和后端校验组合。

本项目 `infrastructure/ai/model/ZenModelExecution.java:62` 已采用提示词要求 JSON，再通过 TEXT 模式发送；OpenCode 预设的请求不携带 response_format。它仍受上游能力标记和返回后的网关校验约束，所以“取消 response_format”并非尚未实施的完整解决方案。

## 3. 全项目 AI 审查中的其他问题

| 优先级/证据 | 发现 | 主要位置与处理方向 |
|---|---|---|
| P1，确定性复现 | MCP 的 isError=true 被丢失，变成普通 AgentToolResult | `agent/infrastructure/mcp/McpAgentToolProvider.java`；保留工具成功/失败类型，禁止失败观察进入成功记忆 |
| P1，确定性复现 | 回答仅包含无效引用时，移除引用后仍作为非证据不足回答返回 | `knowledge/domain/service/KnowledgeCitationValidator.java:59`；现有测试也允许无引用回答。需明确证据策略，不能只检查有无正文；有效编号也不证明语义支持 |
| P1，静态确认 | 审批请求只带 nonce，修订不更换 nonce，旧页面可能批准后来修订内容 | `agent/application/AgentApprovalService.java`、`agent/infrastructure/repository/AgentApprovalRepository.java`；请求绑定用户所见 revision/hash，事务内校验 |
| P1，静态确认 | 一批模型工具调用遇到首个写提案即结束，后续调用没有结果 | `agent/application/runtime/AgentToolCallExecutor.java`；每个调用必须执行、拒绝或明确标记未执行，并维护工具消息配对 |
| P2，确定性复现 | 重建索引发布处理事件后即增加 completed；尚未处理也显示完成 | `document/application/service/BatchReindexService.java`；区分已排队和实际索引完成，失败从处理结果汇总 |
| P2，静态确认 | 查询向量与检索指纹分别读取当前配置，切换期间可能不一致 | `DocumentSearchService`、`ProjectEmbeddingGateway`；一次请求使用同一配置与指纹快照 |
| P2，静态确认 | 新 Embedding 配置立即激活，再重建文档，影响旧索引可用性 | `SystemEmbeddingService`；已有指纹隔离应保留，逐步设计候选代际、完成校验与显式激活 |
| P2，静态确认 | 知识问答请求未携带历史问题与回答，sessionId 不等于模型上下文 | 知识问答同步/流式服务；加入有界历史与指代消解，再用连续追问验证 |
| P2，静态确认 | 知识问答断连主要抑制回调与持久化，没有上游请求取消句柄 | 前后端流式链路；区分停止展示、停止模型请求和停止业务执行 |
| P2，静态确认 | 文档处理忽略 replaceAndComplete 的失败返回，可能发送不对应实际提交的成功通知 | `DocumentProcessingService`；仅在有效处理代际提交成功后通知 |

补充：知识问答前端长度上限 2000 与后端 1000 不一致；Agent 的隐式回退、模型能力测试、执行资源预算仍应纳入改造。尚未实测所有并发竞争，不把静态风险写成已发生事故。

### 2026-10-03 补查：本地 Embedding 与工具执行边界

- 当前系统级 Embedding 保存和请求均强制公网 HTTPS，保存和解密均要求密钥；V41 的 provider 约束没有 OLLAMA。默认本地 Ollama 不能仅改 base URL 接入。这是代码检查结论，未请求用户本地 Ollama。
- `AgentToolCallExecutor` 为每个只读调用新建 virtual-thread executor，缺少统一生命周期及该入口的共享并发限制；按列表顺序对每个 Future 等待固定 10 秒，超时并非从实际发起时计算，且未使用配置中的 MCP 15 秒。此为静态确认，未做负载测试，不据此声称已发生资源耗尽。
- 同一执行器的 `recordToolFailure` 将业务异常统一记录为 TOOL_EXECUTION_FAILED / retryable=false，丢失可诊断类别；`future.cancel(true)` 本身不能证明底层请求已停止。
- 参数校验仅在暴露目录找到定义时执行，未找到时没有在该分支拒绝。目标设计要求本轮未暴露即拒绝；尚未构造完整越权调用链，不能直接认定可绕过最终业务权限。
- `AgentRuntimeCoordinator` 通过正文开头的 `[QUESTIONS]` 判断澄清，依赖模型格式习惯。目标设计增加类型化交互动作并兼容旧标记。

对应细化规则见 [Agent 执行设计](agent-runtime-design.md)，Ollama 配置与访问策略见 [全局设计第 8.1 节](backend-agent-redesign.md#81-ollama-本地-embedding)。以上尚未实施修复。

## 4. 应保留的现有能力

已有价值包括：按用户与用途路由模型、多提供商适配与真实增量；文档解析、分块、页码和 pgvector 指纹过滤；知识来源、引用定位与反馈；规划骨架/详情分阶段、业务校验、草稿版本、局部 patch、锁定字段和事务确认；Agent 运行租约、事件、项目记忆、六种场景 Skill、审批；MCP 发现、白名单和参数校验。

不重写这些边界以追求框架统一。当前未发现可直接当作 Tavily 内建搜索宣传的完整实现；联网能力应以实际 MCP 接入与实测为准。场景 Skill 和确定性业务报告也不应包装成通用多 Agent 系统。

## 5. 验证记录与限制

- 后端选定 AI 相关测试：85 个普通测试类共 641 项通过；23 个 Testcontainers 测试类共 127 项通过。后者使用临时 PostgreSQL 容器。合计 768 项，无失败、错误或跳过。
- 前端选定 Agent/知识/规划测试 17 个文件 84 项，加 AI 设置/文档测试 2 个文件 5 项，合计 89 项通过。存在部分测试组件 stub 警告。
- 临时 Java 探针调用实际类并模拟依赖，复现 MCP 错误状态丢失、无效引用接受、索引排队算完成，以及上述四项规划行为。探针和日志位于 backend/target，不是提交的回归测试，也没有修改业务数据。
- 真实尝试：从本项目现有启用配置选择 `mimo-v2.5-free`，合成一个里程碑/一个任务的请求，使用生产 Zen 传输、路由校验和骨架解析器。返回 `AI_MODEL_CREDENTIAL_INVALID`，未重试；随后只读检查确认本地解密成功。没有获得模型内容，不能声称完成规划端到端验证，也不是测得了 429。
- 用户随后确认最近可用的是 spec-agent 的 OpenCode 配置。本次没有复制另一项目的凭据、修改模型设置或测试它的服务可用性。
- 为读取本项目现有配置临时启动原已停止的 ai-collab-postgres；审查结束恢复停止状态。未迁移数据库、未启动整套应用、未运行浏览器端到端用例。

测试通过说明现有测试断言成立，不能否定上述缺陷；其中部分测试恰好固定了需要修订的行为。需要先为修复建立行为回归用例，再补真实模型、三条业务链路和断连/并发验证。

## 6. 实施顺序

1. 优先落地规划输出契约、能力模式分离、错误可诊断与有界修复，用带围栏、结构错误、截断、限流和跨模型固定样本验收。
2. 修复 MCP 错误、引用策略、审批版本和批次工具遗漏；补 Embedding 快照与真实索引进度。
3. 补知识追问、检索质量评测、规划完整修复上下文、Agent 连续任务与取消。
4. 按用户最终决定，本轮不引入框架或安排框架试点，继续完善现有 Java 网关与编排；框架适配仅保留为未来参考。
5. 完成知识问答、任务规划、Agent 提案审批三条真实链路后，形成可演示、可写简历的证据。

目标架构和分阶段退出条件见 [重构设计](backend-agent-redesign.md)。
