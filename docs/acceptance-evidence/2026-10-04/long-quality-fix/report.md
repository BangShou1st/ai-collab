# 四类真实长对话失败修复与复验

日期：2026-10-04。原基线 `7cddb1fb74b2afdb9731b2447a31913f4ab9126a`，可追溯分支 `codex/long-quality-baseline-20261004`。最后固定运行代码 `92cedb8df0134181cda6bf374a3eefbb95b565f4`。本轮只推送开发分支，不部署、不合并 main、不迁移正式库、不确认正式任务。**整体真实质量未通过，不能进入部署验收。**

## 四类问题与处理结果

| 问题 | 实际原因与修复 | 真实复验与边界 |
| --- | --- | --- |
| A 骨架无效输出 | 原第23轮只有 INVALID_OUTPUT/失败 attempt，缺正文、finish reason、解析诊断，不能追认原具体输出。原规划同类复现得到 `SKELETON / PROVIDER_STREAM / UNEXPECTED_TOOL_CALLS`：Zen 适配层给纯 JSON 和原生最终回答请求加入传输工具，却没有禁用工具选择。无业务工具的两类请求明确 `tool_choice=none`，保留协议包装；增加阶段、空正文、截断、finish reason 等脱敏诊断与 usage。未放宽 Schema、必填字段、权限或业务校验。 | 原规划在浏览器重试到 READY/v2；最终固定24轮骨架 SUCCESS。最终详情第一次 FAILED，实际诊断是 `DEPENDENCY_DATE_CONFLICT`（T5 开始10-18，依赖T4截止10-19）；已有一次修复调整后 READY/v2，原失败 attempt 保留。业务校验仍能阻止错误输出。详情业务失败的 attempt.error_summary 仍为空，详细原因在修复输入，不宣称全部诊断已完善。 |
| B 预算与收敛 | 原连续目录/正文读取增长耗尽累计预算。实际原生路径增加根据本次估算及前次真实 usage 提前收尾、为主请求和最终请求保留空间、摘要计入父运行；预算不足的部分成果明确 BUDGET_EXCEEDED，不把工具碎片记成成功。不是提高预算或固定一次工具调用。 | 原1–3轮定点已可形成回答，提供商超量工具请求也明确预算结束。最终24轮都有最终回答，但第14轮实际输入 52,289 > 50,000，运行字段却是50,000且SUCCEEDED：现有 `AgentRunEventRecorder` 用 LEAST 截住计数，Coordinator 只核对返回输出超额，未对实际返回输入超额作相同判定。估算不足和计数遮蔽仍是部署阻断项。见 usage-audit.json，不用截住的数值证明不超预算。 |
| C 历史蓝图误当当前能力 | 旧提示修复位于 AgentPromptFactory，而原生路径独立组装系统提示，实际没有生效。改真实 composer 系统提示与工具证据：注册工具只表明可调用，成功结果只表明具体对象当时事实，文档是快照描述。保留历史原文并标记 UNVERIFIED_ASSISTANT_HISTORY；摘要输入标记助手未核验，加入当前目标、最新请求、有效约束且计预算。 | 最终第6/8/21轮区分历史蓝图与当前工具，第21轮实际列表为空。原错误会话纠正第21/24轮使用新工具事实，旧失败仍保留。残余：第21轮凭空提到 offset（实际只有 cursor）；摘要仍把当前6项与旧10项列为待确认，没有正确优先当前状态；不算完全通过。 |
| D 部分读取过度结论 | 文档全文/章节/检索/投影范围保留进实际模型；工具带快照来源、scopeRule，投影标记 PARTIAL、不把 FULL 消息摘要覆盖解释为全文。末端系统提示要求核心否定结论也限定范围，禁止只在末尾补免责声明。历史回答全文保留并标未核验，未用关键词替换或固定答案过滤。 | 最终第14/16/22轮核心结论限定已读/已检索范围，没有把未找到变为全文不存在。原错误会话14/16纠正通过，不新增暗示或清空会话。仍有第19轮把历史4.4片段称“读全”，第3轮把投影 originalChars（序列化工具 JSON 字符数）误作正文长度；读取定位空结果/TOOL_EXECUTION_FAILED仍存在，回答已说明未取得，不算资料完整核查。 |

## 批次与人工检查

所有批次使用本项目授权 `space-bunny-free`，温度0.2、规划输出配置6000；原生 Agent 每运行输入上限50000、输出20000、步骤24、工具16。未用 ling 或其他项目凭据。模型请求、步骤、工具输入输出、usage、原始用户问题、工作状态及判断证据分批保留。原场景问题取自 reliability/conversation-turns.json，不加答案提醒。

- `minimal` / `minimal-fixed`：原局部问题加必要历史。初始 embedding 未启动、超量工具请求的预算终止、一次模型超时及同 run 有限恢复均保留，不抹除失败。嵌入服务随后恢复，未改原业务库。
- `fixed24`：早期轮询把 FAILED_RETRYABLE 当终态，过早提交下一轮，只完成3轮；控制顺序无效，不计完整验收。修正脚本等待原运行有限重试，无重复 POST。
- `fixed24-final`：第一份严格顺序24轮，费用14/16等仍过度结论，摘要仍保留旧10项/只讨论，最终草稿还带旧阶段语义。因此补来源与现行状态输入后再冻结新代码，旧证据不覆盖。
- `fixed24-final2`：新空项目、新会话，原蓝图同字节 hash `9216932a8f034a17bcb4d1935b2fda89ea51df763c9134cb8f783e5c6087a8b4`，24轮同会话严格顺序，中途未改代码、提示、模型或预算。24个运行均SUCCEEDED、retryCount=0，但语义及预算审查仍失败，不能记整体通过。
- `correction`：旧错误会话第一次纠正仍继承“全文无费用”；原22轮曾超时后同 run 自动恢复，过程状态均保存。
- `correction-fixed`：保留原错误会话全部前置，按原14、16、21、18、22、24问题纠正。费用范围、当前工具事实、现行6项及规划从FAILED到READY得到修正。第24轮仍必须看真实 usage，不用SUCCEEDED代表预算通过。

最终固定场景日期、Local Owner、10→8→6、费用目标切换和回到可靠性目标均正确反映在后续回答及规划头部。新项目只创建一份规划，无重复生成或确认。第24轮工具查询时骨架仍在生成，回答正确承认尚未完成；后续只读查询至 READY，没有再次请求生成。

最终草稿6个里程碑、6项任务，2026-10-05～10-25，6个 suggestedAssigneeId 均为真实 Local Owner ID；assigneeId 为空所以保留6项 TASK_UNASSIGNED 草稿警告。逐项核对 dependencyTempKeys 无环且任务均在区间，最终 validation.errors=[]。任务 objective/description 有明确判定条件。**内容未整体通过**：MS-CONSTRAINT 与 T2 的验收文字仍要求最早“最多十项”在末轮生效，即使后续已经改6项，混淆历史与现行约束；还出现替换字符。头部6项及 READY 不能覆盖这些内容错误。规划 sources 实际重新检索到了6.2/6.4完整片段，但草稿沿用 Agent 旧“未取得”边界，来源状态同步也需后续核对。

## 摘要真实输入与输出

最终4次摘要实际输入、模型原输出见 fixed24-final2/summary-input-output.json；持久摘要与 segments 见 database-final-state.json，FULL/PARTIAL只代表消息覆盖。summary-source-audit.json 逐条以 Java UTF-16 偏移和原消息核对实际输入，10个片段全部匹配；最终PARTIAL摘要不表示所有消息或资料全文读完。摘要 usage 实际计入所属运行，usage-audit.json分列摘要与主步骤。

来源和范围提示已进入实际摘要输入，旧助手标记未核验；但当前状态中的 activeGoal 明示6取代8、latestRequest明确现在生成，模型原摘要仍认为6/8/10关系、是否允许生成待确认。activeConstraints输入当时为空（目标文本含约束，结构节点未完全提取），不是用户必须补内部字段。这是有证据的剩余问题，不通过删除摘要或每轮额外审查调用来掩盖。

## 浏览器与数据库

bsk 使用当前连接 Edge，在原失败规划页看到零版本失败、修复后可点重新生成，在原plan恢复v2；旧操作仍显示“版本尚未生成”和自己的 PLANNING_MODEL_INVALID_OUTPUT。截图 browser-retry-enabled.png、browser-latest-plan-final.png、browser-old-error-detail-final.png及API planning-old-operation-final.json可复核。planning-retry-verified.json是修版本归属之前的旧快照（当时错误借用新v2），保留为 BEFORE，不作为最终通过证据。没有点正式确认。

新真实 PostgreSQL 回归覆盖零版本失败→同规划重试成功→旧操作仍失败/零版本/自身错误和attempt，且正式任务与确认零写入；历史故障恢复无需全量重做。最终新项目 database-final-state.json 的 formalTasks=0、confirmations=0，终态 active_attempt_id=null。原业务 PostgreSQL/Redis/MinIO 全程停止，所有验收写入仅副本。

## 测试与环境

最终后端全量145类982项：971通过、11显式opt-in跳过、0失败/错误，包含真实PostgreSQL集成；清单 validation-results.json 从最后全量stdout提取，未被并行宿主Surefire覆盖。针对性测试与业务PostgreSQL回归已执行，不与全量重复相加。前端34文件143项通过，vue-tsc、Vite生产构建与后端打包通过；既有bundle大小提示保留。真实模型、浏览器不是模拟单测结果。早期测试失败和复验失败证据保留私有 target及分批公开状态。

验收宿主正常关闭，打印 REAL_ACCEPTANCE_CONFIGURATION_RESTORED；5项提供商完整字段（含密钥仅私有比对）、用途映射及原updated_at比对通过，见config-restoration.json。本轮转发器、Ollama serve、Vite和3个隔离容器已停止，本轮端口18080/15173/55432/16379/18090/11434无监听；隔离卷和私有日志保留。Docker及共享bsk daemon保持既有可用状态，没有杀原业务进程。

## 部署准备的剩余条件

1. 实际输入usage不得被运行上限截断遮蔽；返回输入超预算须明确结束，并完善估算/最终预留后重验。
2. 摘要按当前用户目标、数量与最新请求纠正旧状态；草稿验收项不能要求被替代的10项继续有效。
3. 消除片段读全、工具参数虚构及来源状态不同步；保证错误阶段与业务校验诊断可定位。
4. 冻结修复后的代码配置重新执行原24轮及旧会话纠正，逐项人工检查内容通过后，才进入单独授权的部署准备。不能用换模型成功替代space-bunny失败。

本轮开发分支按功能提交并推送既有origin，具体最终SHA和远端核对结果由Git记录及交付回复给出。没有正式版本验收通过声明。
