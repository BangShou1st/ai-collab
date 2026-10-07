# 委派边界与回答展示修复交付记录（2026-10-07 第二轮）

> 基线：`codex/context-foundation` / `00a85fe` + 未提交的 A/B/C 交付。
> 本轮按 [工具契约、工作区与子 Agent 交付代码审查](agent-tool-workspace-delegation-review-20261007.md)（R1–R11）完成生产修复、正式回归测试与浏览器验收。
> 证据类型：[测试] 为自动测试，[浏览器] 为 browser-skill 实测截图，[环境] 为运行环境事实。

## 1. 后端修复（R1–R5、R7–R9）

### R1 子运行答案不再进入主会话

- `AgentRunEventRecorder.recordFinal`：depth>0 的子运行不再向共享会话写 ASSISTANT 消息；产出只持久化在子运行自身的 FINAL_ANSWER step，由父运行综合后以父身份落一份最终回答。
- `recordBudgetPartialAnswer`、`recordWaitingForInput`：同一隔离边界——子运行不写会话消息、不推进主会话工作状态（`AgentWorkingState.question` 仅限父运行）。

### R2 会话恢复/摘要/控制入口区分主运行与子运行

- `AgentRepository.findLatestRun` 与 `listSessionSummaries` 的最新运行子查询均限定 `depth=0`：委派子运行不能被恢复、订阅或控制入口当成主运行，父运行不会孤立在等待状态。前端 `restoreSession` 经 `latestRun` 自动受益。

### R3 子工具窄白名单不被基础集合扩大

- `AgentRuntimeCoordinator.restrictedChildSkill` 改为返回具名的 `CHILD_DOCUMENT_RESEARCH` 场景视图（`isChildResearchSkill` 公开判定，无反射）。
- `AgentToolRegistry.definitionsFor`：受限子研究场景严格按自己的白名单暴露，不并入 `BASE_READ_ONLY_TOOLS`；基础只读集合只扩大主运行。
- `AgentToolCallExecutor.validateToolCall` 执行端复核同一规则（经 `AgentToolRegistry.isChildResearchSkill`），暴露与执行一致。
- 子运行的基础场景固定为 PROJECT_RESEARCH（子目标中的"任务/创建"等宽泛词不再触发父场景路由）。

### R4 混合批次委派拒绝后不伪成功

- `AgentToolCallExecutor`：被拒绝的委派从只读批次剔除（`readOnlyBatch` 装配排除已处置调用与委派工具），不再落入普通执行。
- `DocumentResearchDelegateAgentTool.execute` 改为防御性拒绝：受理只经 `executeDelegation → repository.documentResearchDelegationResult` 单一事务完成；普通批次到达即抛错，不产生"没有创建子运行的成功 DELEGATED 回执"。

### R5 父暂停时子用量仍回收

- `resumeParent` 拆分：用量回收独立于唤醒——父处于任何状态（含 RUNNING 竞争窗口、PAUSED）都如实累计子运行已发生消耗（幂等键：`DELEGATION_COMPLETED` step 的 childRunId 防重复回收）；唤醒部分单独执行，仅 CREATED/QUEUED 被转回 QUEUED，PAUSED 不被自动解除。

### R7 子引用身份进入父最终回答

- `resumeParent` 把子运行成功工具结果的有效 citations（真实 chunk/document 投影）随 `DELEGATION_COMPLETED` 持久化。
- `AgentRuntimeCoordinator.childCitations` 读取该字段并在父收尾时移交给 `recordFinal`；`recordFinal` 将移交来源与本运行持久证据合并投影到来源查看。模型不能编造引用 ID——不在持久集合中的来源不出现。

### R8 委派计入工具配额

- `documentResearchDelegationResult` 受理事务同时累加父运行 `tool_calls_used/steps_used`（LEAST 封顶），与普通 `recordToolResult` 同一配额语义，不出现免费调用；与子预算切分、父收尾预留、回收保持一致。

### R9 子上下文不继承父对话

- `AgentModelMessageComposer.composeV2`：子研究运行（depth>0 或受限子场景）不读取会话 working_state、不注入父对话历史、不触发会话摘要（summaryCandidates 置空）；系统提示追加子任务边界（只见委派目标与自身工具观察、不提问、不输出 [QUESTIONS]）。

## 2. 前端修复（R6、R10、R11）

### R10 正文转义保持原样，协议处理限定已识别区域

- `agent-prose.ts` 重写为 `parseAgentProse`：只在明确识别的 `[QUESTIONS]` 协议区域内还原字面 `\n`；普通正文、Windows 路径（`C:\new\notes.txt`）、JSON 转义、代码中的字面 `\n` 一律原样保留。删除了全局 `\\n → 换行` 替换。
- 兼容入口 `visibleAgentProse`、`hasAgentQuestions`、`agentQuestionLines` 供视图层独立渲染追问。

### R11 章节/步骤/追问层级整理

- 输出约定（后端）：`ProjectResearchSkill.outputContract` 改为 Markdown 小标题分节（章节不再用 "1. 2. 3." 编号表达），步骤独立从 1 编号，追问选项与正文步骤分开；Composer 系统提示追加通用回答结构约定（章节用 `##`、列表只用于真正列表项、短问短答不强制模板）；子研究场景有独立的回答结构要求（## 发现/## 覆盖缺口、禁 [QUESTIONS]）。
- 前端展示：追问区域从正文剥离为独立的"需要你的确认"块（`question-block`，有序列表原生编号），不再混入正文编号列表；正文排版增强（15px/1.8 行高、标题分级、列表节奏）。
- 流式预览与最终回答共用 `parseAgentProse` + marked + DOMPurify 同一安全管线；DOMPurify、防注入、来源查看、过程活动全部保留。
- 历史混合样本的有边界兼容：旧回答中模型用编号写章节的部分不做中文短句猜测改标题（按审查要求），追问区域仍正确剥离并独立分段。

### R6 中等桌面窗口遮挡修复

- 761–1280px：对话始终占满主区（`minmax(0,1fr)`），详情改为右侧覆盖层并新增真实关闭按钮（`data-test=agent-inspector-close`）；不再出现"300px 窄列 + 400px 固定详情"的双层遮挡，关闭入口不再被覆盖。
- 全尺寸（>1280px）布局不变：三栏 static，无关闭按钮。
- 排版与 `markdown()` 管线、暂停/重试/审批/输入/两侧栏操作全部保留。

## 3. 测试（[测试]）

| 套件 | 结果 | 覆盖 |
|---|---|---|
| `AgentDelegationPostgresTest`（Testcontainers pgvector:pg17） | **14 项通过，0 失败/错误** | 原有 4 项 + 新增 10 项：R1 子消息零泄漏、R2 latest-run 主运行身份、R3 受限白名单（暴露与复核一致）、R4 混合批次不伪成功、R5 父暂停用量回收且不解暂停、R7 引用跨父子存活（DELEGATION_COMPLETED 携带 citations → 父 message citations=1）、R8 委派消耗 tool_calls、R9 父对话标记不进子模型请求、重复回收幂等、取消子运行回收用量。审查探针全部转为正式测试，不依赖反射私有方法 |
| 后端受影响回归批次（12 个类） | **198 项通过，BUILD SUCCESS** | 协调器 32、行为 25、ComposerV2 16、配置切换 9、暂停续跑 19、持久恢复 23、Repository 39、契约 9、Registry/Depth/Limits/Mapping 26 |
| `agent-prose.test.ts` | **12 项通过** | 新增：Windows 路径保持、JSON 转义保持、普通列表字面 \n 不被全局反转义、只在协议区域内反转义、追问独立分段（agentQuestionLines 与正文步骤分离） |
| 前端全量 vitest | **40 文件、230 项通过** | 含 AgentView streaming/pause-resume/restore/retry/approval 等既有套件 |
| vue-tsc | 通过 | 类型检查零错误 |

## 4. 浏览器验收（[浏览器]，docs/acceptance-evidence/2026-10-07/prose-fix/）

环境（[环境]）：后端本轮构建 `mvnw spring-boot:run -Dspring-boot.run.profiles=local`（8080），vite dev（5173），Docker 容器 ai-collab-postgres/redis/minio 正常；browser-skill 后台会话（--no-focus）。

| 证据 | 验证内容 |
|---|---|
| agent-fullsize-fixed-sample.png | 全尺寸（inner 1685x880, DPR 1.5）审查固定样本：追问块独立渲染、无 4–10 混合列表 |
| agent-fullsize-question-block.png | 追问块有序编号（1–4），问题与选项分行 |
| agent-1100-after.png / agent-1100-closed.png | 1100x800 中等窗口：详情覆盖层 + × 关闭入口可点；关闭后对话占满（convW 733px），无遮挡 |
| agent-1100-live-answer.png / agent-1100-answer-closed.png | 中等窗口带真实新回答复测：`C:\new\notes.txt`、`{"a":1,"b":"x\ny"}` 原样显示 |
| agent-escape-preserved.png | 全尺寸真实模型回答：路径与 JSON 转义未被改写，字面 `\n` 保持 |
| agent-fullsize-restored.png | 全尺寸详情恢复 static，三栏 228/614.7/316，无关闭按钮 |
| agent-restore-after-refresh.png | 刷新后 restoreSession 恢复主运行：回答、追问块、来源查看完整，`[QUESTIONS]` 零泄漏 |
| agent-fullsize-table.png | 表格/标题/加粗渲染正常，结论优先 |

DOM 量化断言（browser-skill evaluate）：中等窗口 `convW=733, insX=663, closeVisible=true`（修复前对话仅 300px 且关闭按钮被覆盖）；全尺寸 `cols=228px/614.667px/316px, insPos=static, closeVisible=false`；整页 HTML 无 `[QUESTIONS]` 泄漏；固定样本不再存在章节+步骤+追问合并的单一 `ol[start=4]`（该 ol 仅含模型原文用编号写的第 4 章节名与其三个步骤，属历史样本的有边界兼容）。

## 5. 仍未验证 / 剩余限制

- **委派子 Agent 的真实模型端到端质量对照仍未做**：需要 embedding 配置与有效项目资料；本轮隔离 Testcontainers PostgreSQL 的 14 项证明的是状态机与权限边界，不是回答质量收益。本地嵌入模型未配置（页面真实回答仍显示"系统未配置嵌入模型"）。
- 历史样本中模型用编号写章节的旧内容不会被动改写（不做中文短句猜测转标题）；新回答按新输出约定用 Markdown 标题。
- 前端 FAILED_RETRYABLE 刷新恢复入口（研究文档第 11 节源码推断）仍是待验证项。
- R5 的父 RUNNING 竞争窗口用量回收由实现覆盖（回收不再依赖状态），但未用真实并发复现该窗口。
- V62 仍为占位迁移；子运行进度不在主会话时间线展示（维持既有设计）。

## 6. Git 提交

1. `feat(agent)`：委派隔离/恢复/白名单/批次/用量/引用/上下文后端修复 + 委派回归测试转正（含既有 A/B/C 后端文件）。
2. `fix(agent-web)`：中等窗口布局、正文转义边界、追问独立分段与排版（含既有 A/B/C 前端文件）。
3. `docs(agent)`：本轮交付记录与证据。

历史 525 项通过、历史截图与模型表现不冒充本轮结果；本轮数字以第 3 节实测为准。
