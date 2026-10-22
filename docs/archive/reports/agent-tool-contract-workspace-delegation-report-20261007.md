# 交付报告：工具契约、对话工作区改版与只读研究子 Agent

> 日期：2026-10-07。基线：`codex/context-foundation` / `00a85fe`（本轮开发在工作区之上进行，未提交、未收编既有未提交内容）。
> 本轮完成三项交付（A 工具契约与 Skill 边界、B 对话工作区改版、C 只读文档研究子 Agent）并完成定向测试与浏览器验收。
> 证据类型在每节标注：[测试] 为自动测试，[浏览器] 为 browser-skill 实测截图，[环境] 为运行环境事实。

## 1. 交付 A：工具契约与 Skill 边界

### 1.1 补齐生产工具的模型可见定义

以下工具从继承的泛化开放对象 Schema（type=object + additionalProperties=true）补齐为与 execute 实际要求一致的完整定义（业务说明 + 参数 Schema + 默认值 + 范围）：

| 工具 | 补齐内容 |
|---|---|
| `get_task` | required taskId（uuid format）；closed object |
| `search_project_knowledge` | required query（1-1000 字符）；可选 documentIds（≤20 uuid）与 limit（1-12，default 8）；说明返回语义 RELEVANT_EXCERPTS_ONLY |
| `answer_project_question_with_sources` | 同上（别名，说明明确"不启动独立问答模型"） |
| `list_recent_audit_summaries` | 可选 limit（1-20，default 10） |
| `draft_weekly_report` | 可选 days（1-30，default 7）；说明返回确定性统计事实而非成稿周报 |
| `update_task_after_approval` | required taskId + changes；changes 含全部业务字段与 version（乐观锁说明）；x-approval-patch 修订语义 |
| `create_milestone_after_approval` | required name；日期/状态/sortOrder 可选字段及范围 |
| `update_milestone_after_approval` | required milestoneId + changes；x-approval-patch 修订语义 |
| `get_project_overview` / `get_project_dashboard` / `check_project_progress` / `analyze_project_risks` | 显式 closed 空对象（不接受任何参数） |

### 1.2 审批工具的修订兼容（关键语义）

- 契约按调用形态区分：**新建提案**（无 approvalId）要求完整必填字段；**可信 PENDING 提案修订**（带 approvalId）由 Registry 注入的 `x-approval-patch` 标记让 `ToolArgumentValidator` 跳过根对象 required——模型可只提交本轮变化字段，未提及字段由 `AgentProposalArgumentMerger` 合并保留。
- 发现并遵守一个实现细节：`x-approval-patch` 只对根对象生效，**嵌套 changes 内的 required 无法被修订补丁跳过**，因此 changes 内不声明 required（嵌套 required 会把合法部分修订在合并前拒绝）；新建时 changes.version 必填由 DTO 校验（`UpdateTaskRequest` 的 `@NotNull version`）在执行边界保证。
- 工具名与历史调用完全兼容：未重命名任何持久 tool_name；`answer_project_question_with_sources` 保留为兼容别名。
- `AgentToolCallExecutor` 恢复批次按持久化 source_mode 校验的既有语义未动。

### 1.3 跨场景基础只读集合

- `AgentToolRegistry` 新增 `BASE_READ_ONLY_TOOLS`（项目概况/仪表盘、任务列表/详情、里程碑、成员、文档目录/提纲/正文、知识检索及别名、记忆、风险/进度/审计/周报统计、澄清、委派工具）。
- 有效工具集合 =（基础只读集合 ∪ Skill 白名单 ∪ 澄清工具）∩ 角色/运行范围允许；`AgentToolCallExecutor.validateToolCall` 的白名单检查同步引用同一集合（`baseReadOnlyTools()`），**暴露与执行复核规则一致**，消除"看得到但执行被拒"的契约漂移；执行时仍复核权限/Schema/暂停/claim/循环。
- 效果："分析项目进度，并结合需求文档解释风险"这类综合问题命中 PROJECT_HEALTH 后仍能调用知识检索与正文读取；简单事实问题不被迫套完整报告模板。
- **未放宽的能力**：写/规划工具（ControlledWriteAgentTool）仍限 depth=0 + 非 scheduled + OWNER/ADMIN；审批写工具仍限 WRITE_PROPOSAL_ROLES；Legacy 只读、MCP 白名单、scheduled 限制全部不变。

### 1.4 Skill 边界与元数据

- Composer 系统提示新增"任务场景说明"：场景是指引与建议结构、不是能力上限；用户本轮目标优先，短问短答，综合问题按需组织，不强制凑满模板栏目。
- Skill.inputSchema 维持场景元数据定位：未开发动态表单、未与工具参数混用、未改 API。
- 自动关键词路由保持提示性质（命中场景不再阻断基础只读资料读取），未做全量改名。

## 2. 交付 B：对话工作区改版

改版范围：正文、过程活动、会话侧栏、右侧详情栏、移动端（`AgentView.vue` 视图层与样式；不新增前端状态机，运行状态仍由 workspace/timeline 投影）。

- **正文**：助手长回答取消 78% 气泡约束，采用稳定阅读行宽（max-width:76ch）；用户消息保持紧凑右对齐气泡；标题 h1-h6 分级字号/间距（此前全部 15px）；表格、代码块、引用块、列表、分隔线建立层级。
- **流式预览修复**（用户验收中发现的缺陷）：临时正文预览原为纯文本插值，流式过程中 Markdown 源码（#/表格/加粗）会先闪现再跳变。现改为与最终回答同一条 marked + DOMPurify 渲染管线，Markdown 即时成型；渲染异常回退纯文本插值。渲染管线与安全策略不变，无二次模型调用。
- **过程活动**：整块"执行过程"可折叠，弱化卡片感，不与最终回答争抢；运行中自动展开；工具原名/错误诊断放技术详情。
- **会话栏**：可收起（"收起会话/会话"切换），中栏阅读宽度不随开关跳动（grid 模板跟随变化）。
- **右栏**：待审批置顶高亮；运行状态精简；底层连接诊断/用量/Run 身份收进"诊断与资源"折叠。
- **输入区**：场景选择说明改为"可选偏好，不选也能使用全部基础工具"；发送/暂停/结束状态稳定；无继续按钮（暂停经输入"继续"恢复，语义不变）。
- **移动端**（≤760px）：会话/对话/详情三视图切换，对话与输入优先；正文满宽、无横向溢出、无重叠。

### 浏览器验收证据（[浏览器]，docs/acceptance-evidence/2026-10-07/workspace-redesign/）

| 证据 | 验证内容 |
|---|---|
| agent-before-desktop.png | 改版前布局（对照） |
| agent-after-desktop.png | 改版后桌面布局：双开关、场景提示、右栏精简 |
| agent-short-answer.png | 真实模型短问短答 + 失败/自动重试状态展示（第 3 次尝试） |
| stream-during.png | 流式过程中 Markdown 即时成型（标题/表格无源码闪现） |
| stream-final.png | 运行完成收口：回答只出现一次，右栏"运行 · 已完成" |
| agent-sessions-collapsed.png | 会话栏收起后阅读区变宽、表格渲染正常 |
| agent-narrow-chat.png / agent-narrow-input.png | 窄屏（iPhone 14 模拟）对话/输入视图：无溢出无重叠 |
| agent-questions-fixed.png | [QUESTIONS] 控制标记剥离修复后（见 2.1） |

### 2.1 验收中发现并修复的缺陷

1. **流式预览闪现 Markdown 源码**（用户反馈"输出那一瞬间没处理好"）：预览改走渲染管线，修复后有 stream-during.png 为证。[浏览器]
2. **[QUESTIONS] 标记泄漏**：模型把标记写在长回答中间且带字面 `\n`（后端按 startsWith 判定未触发等待输入，运行 SUCCEEDED 收口），旧展示逻辑只剥开头标记导致标记字面出现在页面。修复：`visibleAgentProse` 剥离正文中所有控制标记行并还原字面 `\n`；只改展示层，不改持久化与后端澄清判定。新增 agent-prose.test.ts 5 项。[测试+浏览器]

### 2.2 环境事实（[环境]）

- Zen 预置模型 `mimo-v2.5-free` 已被提供商下线（HTTP 410 ModelDeprecated，官方建议 mimo-v2.6-flash-free）。本地库已切换到 `mimo-v2.6-flash-free` 后完成真实模型验收；生产部署若仍指旧模型名需在 AI 设置中同步更换。
- 知识检索/来源问答在本地返回"资料缺失"结论：本地 embedding 服务未启动（环境问题，非链路缺陷；与历史 Ollama 未启动同类）。Agent 如实声明覆盖范围，行为正确。

## 3. 交付 C：只读 document_research 子 Agent

### 3.1 设计与实现

- 新工具 `delegate_document_research`（`DocumentResearchDelegateAgentTool`）：主 Agent 需要多轮资料研究时委派；objective 必填（自包含描述，10-2000 字符）。
- **复用现有持久运行引擎**：受理在 `AgentRunEventRecorder.documentResearchDelegationResult` 一个事务内完成——创建 depth=1/role=KNOWLEDGE_RESEARCHER 的 QUEUED 子运行（复用 V22 父子结构与约束）、落 DELEGATION_REQUESTED step、委派调用 invocation 落 SUCCEEDED（幂等键）、父运行 RUNNING→QUEUED 等待、markBatchHandled（恢复不重放委派批）。未启用遗留 `recordDelegation` 分支，无第二套编排/checkpoint。
- **预算**：子运行从父运行剩余额度切出并设硬上限（steps≤8、tools≤8、input≤30k、output≤8k），剩余不足 1000 时拒绝；children_used 计入父运行 max_children=3；不预扣子运行消耗。
- **最多一层**：depth=1 不得再委派（recorder + registry 双重校验）；scheduled/子运行不可见委派工具。
- **子运行白名单**：协调器对 depth>0 运行强制 `restrictedChildSkill`——只读文档研究集合（文档目录/提纲/正文、知识检索及别名），禁写、禁外部 MCP、无核心动作；角色权限检查继续生效。
- **唤醒与回收**：子运行终态经既有 `resumeParent` 写 DELEGATION_COMPLETED step 并唤醒父运行（状态条件扩展 CREATED→含 QUEUED）；父运行被重新领取时 5c 检查（未完成委派→不发模型请求重新排队等待）；子运行完成后其发现作为 `CHILD_RESEARCH` 数据块（UNTRUSTED 边界说明）注入父运行请求，由主 Agent 综合最终回答；子运行失败同样唤醒、如实标注。
- **领取顺序**：`claimNext` 排序改为 `depth DESC, created_at, id`——委派子运行先于等待中的父运行被处理，避免父运行轮询饿死子运行；普通 depth=0 运行之间顺序不变。
- **会话隔离**：子运行目标不写 agent_message（无 USER/ASSISTANT 消息）、不推进 working_state、不进主会话消息列表；子运行产出只经父运行综合后以 ASSISTANT 消息收口。
- **暂停/取消**：父运行暂停意图先落库则拒绝受理（AGENT_RUN_PAUSED）；子运行暂停不唤醒父运行（等待条件排除 PAUSED）；取消经既有 resumeParent(CANCELED) 链。

### 3.2 测试（[测试]，真实 PostgreSQL）

`AgentDelegationPostgresTest`（testcontainers pgvector:pg17，4 项）：

1. `delegationCreatesChildRunAndParentWaitsThenCollectsResult`：父运行第一轮委派→子运行创建（depth/role/预算/幂等结果）→父运行等待轮零模型请求→子运行执行受限白名单工具→子运行文本收口 SUCCEEDED→resumeParent 唤醒（DELEGATION_COMPLETED=1）→父运行综合（CHILD_RESEARCH 注入断言、ASSISTANT 消息不重复）。
2. `delegationIsIdempotentAcrossDuplicateInvocation`：同 invocationId 重复受理返回同一子运行、children_used 只加一次。
3. `delegationRejectsPauseIntentAndChildDepth`：暂停意图拒绝受理；depth=1 拒绝嵌套委派。
4. `childRunHasRestrictedToolWhitelist`：委派工具对子运行不可见。

### 3.3 未验证/边界声明

- 子 Agent 的**真实模型质量对照**未做（需本地 embedding 服务启动后构造多文档研究场景）；现有证据为结构化集成测试，不宣称回答质量收益。
- 委派能力未在浏览器端到端演示（需 embedding 可用的项目资料）；普通查询仍直接走只读工具，委派由模型按需选择。
- 并行多子任务未开放（首期单委派语义：委派调用必须单独成批，DELEGATION_BATCH_LIMIT）。

## 4. 回归与修复影响面

### 4.1 后端（[测试]）

- 新增：`AgentToolContractTest`（9 项，真实生产工具实例 + mock 业务依赖）、`AgentDelegationPostgresTest`（4 项）。
- 修复过时测试前提：`AgentModelConfigurationSwitchPostgresIntegrationTest.recoveryStillEnforcesSkillWhitelist` 原用 `draft_weekly_report` 冒充"白名单外的写调用"——该工具现属基础只读集合，改用集合外桩名 `outside_scope_tool`，测试意图（恢复时白名单仍生效）不变。
- 回归通过（全绿）：后台全量 Agent 测试批次 **525 项，0 失败/错误，BUILD SUCCESS**（涵盖 AgentToolContractTest 9、AgentSkillToolMappingTest、ToolArgumentValidatorTest、AgentSkillRegistryTest、AgentApprovalServiceTest、AgentToolRegistryTest/DepthTest、AgentReadToolsTest、ApprovalToolRevalidationTest、AgentProposalArgumentMergerTest、AgentPauseResumePostgresTest 19、PersistedModelTurnRecoveryPostgresTest 23、AgentCrashRecoveryTimingPostgresTest 5、AgentRunRetryPostgresIntegrationTest 6、AgentModelConfigurationSwitchPostgresIntegrationTest 9、AgentRuntimeCoordinatorTest 32、AgentRuntimeBehaviorTest 25、AgentModelMessageComposerV2Test 16、AgentWriteProposalSpringIntegrationTest 2、AgentRepositoryIntegrationTest 39、AgentWorkingStateConstraintIntegrationTest 21、AgentActualTokenUsageIntegrationTest 19、AgentDelegationPostgresTest 4 及其余 Agent* 单元/集成测试）。
- 关键保留行为验证：自动重试（短问真实运行中 3 次自动重试展示正确）、已保存结果恢复（R2 23 项）、崩溃计时（R1 5 项）、暂停/输入续跑（19 项）、终态派生重试（6 项）、单次配置快照与下一请求切换（9 项）。

### 4.2 前端（[测试]）

- 全量 40 文件 / 223 项通过，vue-tsc 通过（含新增 agent-prose.test.ts 5 项；改版前基线为 39 文件/218 项）。
- AgentView 相关既有测试（streaming/pause-resume/restore/retry/approval-conflict/activities）全部保留并通过；无第二套前端状态机。

## 5. 改动文件清单

生产代码（后端）：

- `agent/infrastructure/tool/`：TaskGetAgentTool、KnowledgeSearchAgentTool、ProjectQuestionAgentTool、AuditSummaryAgentTool、WeeklyReportDraftAgentTool、UpdateTaskApprovalAgentTool、CreateMilestoneApprovalAgentTool、UpdateMilestoneApprovalAgentTool、DashboardAgentTool、ProjectOverviewAgentTool、ProjectProgressAgentTool、ProjectRiskAgentTool（definition 补齐）；AgentToolRegistry（基础只读集合 + 一致性出口 + 委派工具策略）；**新增 DocumentResearchDelegateAgentTool**
- `agent/application/runtime/`：AgentToolCallExecutor（白名单一致性 + 委派分派 executeDelegation）；AgentRuntimeCoordinator（子运行受限 Skill、5c 委派等待、CHILD_RESEARCH 证据注入）；AgentModelMessageComposer（任务场景说明）
- `agent/infrastructure/repository/`：AgentRunEventRecorder（documentResearchDelegationResult 事务受理、resumeParent 支持 QUEUED 父运行、recordFinal 唤醒父运行）；AgentRepository（documentResearchDelegationResult、childRuns、claimNext depth DESC）
- 迁移：`V62__agent_document_research_delegation.sql`（占位标记，无 schema 变更，复用 V22/V61 结构）

前端：

- `modules/agent/AgentView.vue`（布局与样式改版、流式预览渲染管线、会话栏收起、右栏重排）
- `modules/agent/agent-prose.ts`（[QUESTIONS] 中间标记剥离与字面 \n 还原）

测试：

- 新增 AgentToolContractTest、AgentDelegationPostgresTest、agent-prose.test.ts
- 调整 AgentModelConfigurationSwitchPostgresIntegrationTest（白名单外桩名）

## 6. 运行环境与访问地址

- 后端：http://localhost:8080（local profile，Docker 容器 ai-collab-postgres/redis/minio）
- 前端：http://localhost:5173（vite dev）
- 浏览器验收账号：owner（项目"Zen规划冒烟"）

## 7. 剩余限制与建议

1. 生产模型名若仍为 mimo-v2.5-free 需更换（提供商已下线）。
2. 子 Agent 质量收益对照与委派的浏览器端到端演示依赖本地 embedding 服务，待环境具备后补做。
3. V62 为占位迁移；若后续需要委派运行的前端过程展示（子运行活动进入主时间线），需独立设计事件投影，当前子运行进度不在主会话时间线显示（父运行显示"已受理委派/综合回答"两层）。
4. 前端 FAILED_RETRYABLE 刷新恢复入口问题（研究文档 11 节源码推断）本轮未复现修复，保留为待验证项。
