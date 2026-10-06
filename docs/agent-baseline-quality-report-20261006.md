# Agent 基线固定与真实回答/摘要质量验收报告(20261006)

任务书:`docs/agent-baseline-quality-handoff-20261006.md`。本报告为最终收口记录,统计均为本次实测,未复制历史数字。

## 1. 接手基线、提交与最终工作区

- 接手时:分支 `codex/context-foundation`,HEAD `d5f09ab`,工作区 115 处继承变更(已暂存/未暂存/新增/删除/移动混合),全部保留。
- 基线提交(本地,未推送):
  - `753750b` feat(agent): 收编继承改动为可追溯基线(动态模型配置、工具输出投影与分页、R1/R2 恢复、暂停与输入续跑,V59–V61)——93 个文件,后端+前端+迁移+正式测试整体入库,不做无法编译的中间拆分。
  - `fe45aa9` docs(agent): 归档 20261005–20261006 运行时维护、暂停续跑与恢复审查文档及验收证据——42 个文件。
  - `0639cb5` fix(agent): 运行详情读取空暂停意图不再 NPE,未处理异常补栈日志(见第 5 节;质量修复与基线分开提交)。
  - `2b48dae` test(acceptance): RealAcceptanceHostTest 增加保留副本配置的显式 opt-in(见第 5 节)。
- 最终工作区:仅剩 gitignore 覆盖的本地日志;新增证据与本报告随收口提交入库。未推送、未合并 main、未部署;业务库 `ai-collab-postgres` 全程保持停止。

## 2. 本次全量测试结果(2026-10-06 实测)

| 检查 | 结果 |
| --- | --- |
| 后端 `mvnw test` 全量 | **1140 项:0 失败、0 错误、11 跳过,BUILD SUCCESS**(4 分 42 秒) |
| 前端 `vitest run` 全量 | **38 文件 / 183 项全部通过**(6.6 秒) |
| 前端 `vue-tsc -b` 类型检查 | 零错误 |

11 项跳过全部为显式 opt-in 门控:`RealAcceptanceHostTest`、`BrowserAcceptanceHostTest`、`ReliabilityAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、`RealRetrievalBaselineTest`、`ReliabilityOperationDatabaseTest`(4)、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest`,均由环境变量控制,符合"显式 opt-in 跳过"口径。

修复后定向回归:`AgentPauseResumePostgresTest` 19 项(含新增回归 1 项)、`AgentRepositoryIntegrationTest` 39 项、`AgentRuntimeCoordinatorTest` 32 项、`AgentCrashRecoveryTimingPostgresTest` 5 项、`PersistedModelTurnRecoveryPostgresTest` 23 项,合计 **118 项 0 失败**。

## 3. 真实模型验证:三类项目问答

### 环境(隔离,与生产规则一致)

- 离线副本 PG `ai-collab-acceptance-postgres-20261003`(127.0.0.1:55432,业务库副本)+ 专用 Redis(16379)/MinIO(18090),宿主 `RealAcceptanceHostTest`(18080),无模型 mock。
- **真实模型**:`OPENCODE_ZEN_FREE` preset → `space-bunny-free`(OpenAI 兼容,opencode.ai/zen/v1,副本内已存密钥)。通过生产 API `PUT /user/ai-providers/purposes/AGENT` 将 AGENT 用途分配指向该配置,走"用途分配优先于默认"的生产解析链路(证据 `config-purposes.json`);未改写模型、未创建账号。默认 provider 虽为脚本模型 acceptance-b,但 AGENT 用途分配优先生效,生产解析规则未改动。
- 隔离场景项目 `基线质量验收场景 20261006`(基准日 2026-10-06):3 个里程碑(需求冻结 target 10-05 已逾期/联调测试 10-16/发布评审 10-30)+ 7 个任务,含逾期未完成(权限模型评审,TODO,due 10-05)、缺日期(性能压测报告无 dueDate)、缺里程碑归属(数据迁移脚本)、可验证依赖(接口联调←权限模型评审;验收报告模板←数据迁移脚本)。数据经生产 REST API 创建(证据 `scenario.json`)。

### 逐项结论

| 场景 | 运行 | 结论 | 依据摘要 |
| --- | --- | --- | --- |
| Q1 风险识别 | `7a7c0d48`,SUCCEEDED,8 步 | **通过(带备注)** | 见下 |
| Q2 本周交付影响 | `75cf97fc`,SUCCEEDED | **通过** | 见下 |
| Q3 进度总结与下一步 | `bc7f678f`,SUCCEEDED | **通过** | 见下 |

- **Q1**:正确给出逾期里程碑(需求冻结 overdue=true、target 10-05)、7 任务统计(1 done/2 inProgress/3 todo/1 blocked/overdue=1)、BLOCKED 依赖链;把"无日期""无里程碑归属"列为需确认事项而非臆断;知识检索失败(`DOCUMENT_EMBEDDING_FAILED`,本机 Ollama embedding 未启动)如实报告为工具侧限制,未虚构文档结论。**备注**:`check_project_progress` 的 recentTasks 投影仅展示 3 条(模型可见元数据 projectedTotalCount=7/projectedOmitted=4,已核实非幻觉),模型本轮未主动用 `list_tasks` 续读,而是如实说明范围并列入待确认;Q2/Q3 证明续读能力与契约本身可用,故判定为单次模型行为差异,不是链路缺陷。
- **Q2**:实际续读全部任务(list_tasks 7/7,hasMore=false)与里程碑(3/3);准确区分"证据充分"(需求冻结逾期、两条真实依赖链)与"证据不足"(性能压测无日期、数据迁移无里程碑归属、发布评审本周无窗口内证据——明确"不能判定其已延期",未把 overdue=false 的联调测试说成延期)。
- **Q3**:分页读完全部任务;里程碑-任务映射、阻塞传导(数据迁移脚本 10-08 → 验收报告模板 10-12 连带逾期)准确;"事实"与"推断"逐条标注;6 条下一步行动均可执行且明确"均为建议,未执行任何写入"——已完成业务动作与建议动作分清。
- 附加边界事实:Q2 回答正确指出 7 条任务 description 均为空(数据确实如此);模型未把 TODO 自动解释为延期。
- 环境(非链路)限制:Ollama embedding(127.0.0.1:11434)未启动,`search_project_knowledge` 全部失败;模型每次都如实声明该范围,未虚构检索结果。

证据:`docs/acceptance-evidence/2026-10-06/baseline-quality/q{1,2,3}-*-{run,events,messages}.json`(事件含工具调用序列,消息含最终回答原文)。

## 4. 真实摘要多轮场景(29 轮,摘要确实触发并进入后续输入)

### 场景与会话

同一会话(`摘要保真验收 20261006`,45+ 条消息)按序提交:目标与硬性约束(s1)→ 目标更正 10-31→10-24(s2)→ 待办(s3)→ 协作约定"每周五下午 4 点同步会、压测不外包"(s4)→ 分析/纪要填轮(s5–s6)→ 范围更正"压测取消改容量评估"(s7)→ 更多填轮(s8–s15,含把历史推过 40 条消息窗口的内容)→ 探针(s16/s22/s25/s29)。全部 29 轮 SUCCEEDED。

### 摘要触发与提交的证明

- 触发机制实测:历史窗口 `HISTORY_CANDIDATES=40` 条;超过后最早消息成为摘要候选(`listSummaryCandidates` 手工复现验证)。摘要调用与提交在 `agent_step` 留痕:`reason=CONTEXT_SUMMARY / CONTEXT_SUMMARY_RECOMPRESS`,含 `COMMITTED`(如 run `c403d859` 直接提交、`fcb61e13` 重压缩后提交)。
- 摘要进入后续模型输入:模型调用捕获(capture 69/72)中主请求含 `<CONVERSATION_SUMMARY sourceFrom=… sourceThrough=…>` 块(脱敏对照表 `model-call-composition-summary.json`)。
- 旧信息退出直接历史:终探针 s29(`0b90c1bb`)的主调用输入中,s1 目标原文、s2 更正原文("交付目标从 2026-10-31 提前到")、s4 约定原文("每周五下午 4 点")均已不在(超出 40 条原始消息窗口)。

### 实际保留/丢失(按来源分层,终探针 s29 实测)

| 事实 | 直接原始历史 | 权威工作状态 | 摘要 | 最终回答 |
| --- | --- | --- | --- | --- |
| 交付目标 10-24(更正) | 已出窗 | activeGoal 仍为旧值 10-31(见 5.3) | **携带**("由 10-31 提前至 10-24") | 正确答 10-24,并把 10-31 标为历史值 |
| 预算 ≤20 人日、迁移验证前置 | 已出窗 | 摘要+状态双路携带 | 携带 | 正确 |
| 周五 16:00 同步会 | 已出窗 | 无 | 未覆盖(摘要如实写"材料中无同步会时间信息") | 正确答"每周五 16:00",来源标注为早前约定+待审批记忆提案(提案层携带) |
| 压测取消改容量评估(s7) | 仍在窗内 | 无 | 未覆盖 | 正确(以原始消息为据) |
| 完成项(登录模块) | 已出窗 | 摘要携带并标注"用户自述,非核验事实" | 携带 | 正确,未混淆完成/未完成 |

结论:**摘要保真通过**。更正生效(10-24 被当作最新,10-31 未被误当成最新),约束保留,完成/未完成未混淆;摘要对未覆盖内容如实声明"未在已读范围",不虚构。

### 过程性观察(行为符合设计,记录备查)

- 真实模型摘要输出多次超 1200 字符上限(1240/1713/1849/1592),首次尝试按设计降级(`DOWNGRADED_UNQUALIFIED`,有界重压缩一次,仍不合格则保留上一份、不推进覆盖,下一次运行重新尝试);随覆盖推进最终成功提交。该上限是有界摘要的设计参数,不是缺陷。
- 状态抽取层出现一条来源可疑的 DATE_LOCK 约束"不改日期"。**后继任务已完成溯源与修正(见第 7 节):真实根因是约束触发正则的桥接误匹配,并非助手文本进入约束链路。**
- 权威状态 activeGoal 不随会话内更正自动改写(goalRevision=0):更正的保留完全由摘要层承担且已证明有效;**后继任务已补"用户更正优先级"声明机制(见第 7 节)**。

证据:`summary-scenario-summaries.json`、`s{1,2,4,7,22,25,29}-*`、`model-call-composition-summary.json`、`real-host-key-lines.txt`(含摘要尝试/降级/提交日志行)。

## 5. 发现的根因与修复

### 5.1 已修复:`GET /runs/{runId}` 对所有未请求暂停的运行 500(V61 回归)

- 现象:Q1 轮询运行详情返回 500。带栈日志复现:`AgentRepository.pauseRequestedAt` 行映射对 `pause_requested_at IS NULL` 返回 null,`stream().findFirst()` 内部 `Optional.of(null)` 抛 NPE——即任何从未请求暂停的运行查详情都会 500,直接影响运行详情页。
- 修复(`0639cb5`):改为判空取值(`rows.isEmpty() ? null : rows.getFirst()`);同时 `GlobalExceptionHandler` 未处理异常日志附异常栈(本次定位即受益)。回归:`AgentPauseResumePostgresTest.runWithoutPauseIntentReportsNullPauseTime`(真实 PG,修复前该断言路径抛 NPE)。修复后真实验收全程轮询正常。

### 5.2 已修复(测试设施):`RealAcceptanceHostTest` 强制改写副本配置

- 原状:启动即无条件把 OPENCODE_ZEN_FREE 改成 `space-bunny-free` 并覆盖默认/用途分配,无法用于验证"当前配置"。
- 修复(`2b48dae`):新增环境变量 opt-in `AI_REAL_ACCEPTANCE_KEEP_CONFIG=true` 时跳过改写与恢复;默认行为不变(仍强制改写并在退出时恢复)。生产配置规则未改。

### 5.3 记录不修(如实在案,避免误判为已修复)

- 状态抽取层的 DATE_LOCK"不改日期"噪声与 activeGoal 不自动改写(见第 4 节):未造成错误回答,修复点不在本轮授权入口(`AgentModelMessageComposer`/`AgentToolOutputProjector`/`AgentContextSummarizer`/Skill)内,留待状态跟踪器专项处理。
- Ollama embedding 未启动属本机环境状态,非代码缺陷。
- Q1 模型未主动续读属单次模型行为差异(Q2/Q3 已证链路支持续读),不凭单次行为改投影或提示词。

## 6. 收口清单

- 基线:全量测试通过并已本地提交(`753750b`/`fe45aa9`),工作区干净。
- 三类真实问答 + 真实摘要场景:全部完成,结论可审查(第 3/4 节),证据入库。
- 本轮确认缺陷(NPE 回归)已修复并带正式回归;质量修复与基线分开提交。
- 资源回收:验收宿主已停止(停止标记 + 进程退出),专用 PG/Redis/MinIO 容器已停止(数据卷保留供复核),业务库全程未启动、未写入;`target/real-acceptance.stop` 标记已清理;私有级模型调用明细(`long-quality-model-calls-private.jsonl`)不入库,仅以脱敏对照表形式提交。
- 未做(按任务书约束):不推送/合并/部署;不开发自动重试展示、逐字流式、到限续接、精确计费、自动择模;不重构已收口的暂停/恢复状态机;不新增评测平台。

## 7. 后继任务:状态约束来源核实与修正(2026-10-06 同日)

针对第 4/5.3 节记录的两个状态层观察,完成溯源与限定修复(提交见下):

### 7.1 溯源结论(修正此前猜测)

- **"不改日期"约束的真实来源是用户消息上的正则误匹配,不是助手文本进入约束链路。** 约束仅从 USER 消息提取(`appendUser` 全部调用点传用户文本,助手响应无写入路径)。误触发链:分析请求 s14"…按目标日期完成,分别需要什么条件…"中,DATE_LOCK 触发模式 `日期[^。；\n]{0,6}(…|别|…)` 允许"日期"后 ≤6 字桥接命中"**分别**"的单字"别"→ 误判为日期锁定;`dateFact` 的疑问过滤词表缺"能否",`interrogative()` 拦不住该子句;最终生成无日期、无对象的"不改日期"条目并被渲染为必须遵守。
- **quote 溯源被逐轮覆盖**:`repairLegacyV2Entries` 对无 `detail` 的条目每轮重新抽取,把条目原始 quote(真实原文"列出其下所有任务的标题、状态、截止日期、依赖数量",本可暴露误触发)改写成从 value 再生的"不改日期",破坏了证据链、延误了定位。

### 7.2 修复内容

1. `AgentWorkingState.constraintScopes`:DATE_LOCK 交替词移除单字"别"(补"别改"保持"日期别改"覆盖),消除"分别/差别"类误触发;`interrogative()` 增加"能否"。
2. `AgentWorkingState.repairLegacyV2Entries`:渐进修复只规范化 value/detail,不再覆盖已有 quote——来源证据保留。
3. 用户更正与旧状态的优先级(新增确定性机制):`appendUser` 检测显式更正表述(保守词表:子句以"更正/修正/再更正/再修正"开头或含"更正：/修正:"冒号形式),登记 `working_state.goalCorrections`(最近 5 条,含 sourceMessageId);**不解析更正语义、不改写 activeGoal**(显式换目标仍走"新目标:"分支)。读取端声明让位规则:Composer 在工作状态块中渲染"旧目标与旧状态中与更正冲突的内容,一律以用户更正为准,不得把旧值当成最新口径";摘要器的 `CURRENT_STATE_FOR_SUMMARY` 携带有效更正。

### 7.2.1 更正机制补修(评审探针发现的三处遗漏,同日修复)

初版更正机制经评审复现三处遗漏,已限定补修:

1. **全角标点截断**:登记只取标记子句,"更正一个关键信息：经过评审，交付目标从 10-31 提前到 10-24"被切成"更正一个关键信息：经过评审"丢失日期。补修:quote 保留**完整更正原文**(单条上限 1000 字)。
2. **多条更正只提供最后一条**:先更正日期、再更正预算时,组装器与摘要状态块都只取最后一条,早期更正在其原始消息退出窗口后可能丢失。补修:Composer 按时间顺序提供当前目标的**全部**有效更正;摘要状态块改为 `activeGoalCorrections` 数组(旧→新)。
3. **显式新目标未撤销旧更正**:换目标后状态仍注入旧目标的更正及优先规则。补修:`replaceGoal` 对旧更正批量退位(status=superseded、supersededReason=GOAL_REPLACED,历史保留);读取端只渲染 active 条目。

回归:`AgentWorkingStateConstraintIntegrationTest` +4(全角标点、多条按序、换目标退位与后续更正、完整原文)、`AgentModelMessageComposerV2Test` +1(多条按序渲染+退位不渲染)、`AgentContextSummarizerTest` +1(状态块按序携带有效更正、排除退位条目);状态/组装/摘要/仓库/协调器/暂停续跑等 10 个测试类合计 **188 项 0 失败**。

### 7.3 回归与验证

- `AgentWorkingStateConstraintIntegrationTest` 新增 4 项(真实 PG):分析请求不产生 DATE_LOCK;日期疑问句不锁定;修复保留原 quote;显式更正登记来源且旧状态让位规则可渲染。
- `AgentModelMessageComposerV2Test` 新增 1 项:工作状态块对更正优先级的渲染。
- 定向回归:状态/组装/摘要/仓库/协调器/暂停续跑等 10 个测试类合计 **183 项 0 失败**(更正机制补修后复跑扩大为 188 项 0 失败,见 7.2.1)。
