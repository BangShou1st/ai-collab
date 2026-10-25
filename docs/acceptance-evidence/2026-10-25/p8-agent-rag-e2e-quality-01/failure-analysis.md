# 失败分层与最小修复候选

## F1 Q02：FAIL / RETRIEVAL_MISS（主因已确认）

本轮真实 Ollama 与隔离 pgvector 均确认文档已索引；目标“只有最近一次回滚演练通过的项目才允许继续发布”在全量第 23 名，相似度 0.3643。QA Top8、Agent Top20 均缺失，最终上下文、实际工具 quote 和脚本模型观察均不含该条件。SOURCE_CAP 是其他候选的丢弃统计，不能当作原生产 Q02 的主因。

复现：按 README 执行 retrieval / integration，检查 retrieval-results.json questions[Q02] 的 facts、qaCandidates、agentCandidates、diagnosticFullRanking，再对照 integration-results.json 的 Q02 factTransport。原始冻结资料尾部含大量重复步骤，稀释语义是与证据一致的解释，但“具体 embedding 因果机制”未通过消融实验证实。

既有回归能稳定记录该失败，未要求 Q02 通过；未来修复须新增“最终有效 quote 必含条件”准入断言。候选最小修复为受控查询分解或已命中文档有界补读关键章节，先做测试侧对照；不能用 gold 标签、题号或硬编码 docId 特判。R1b 本轮复测可将证据补入池，但已有 legacy QA/Agent 选择仍丢弃，最终收益为0。R1+当前 S2 联合收益尚未测，不能排除。

风险：额外查询/Token、误召回、source cap 再次挤压、指纹与范围过滤遗漏。下一轮要求在相同冻结输入、20 候选/8000预算和明确额外I/O上界下，Q02 最终事实 0→1；同时 Q03/Q05/Q09 与既有反例重要事实零退化。证据足以进入独立修复实验，尚不足以批准某个生产实现。

## F2 Agent Q03/Q04/Q05：FAIL / CONTEXT_MISS（主因已确认）

Top20 中已有关键块，生产跨文档代表优先选择在默认8来源下丢失 Q03 上线门禁及回滚触发条件、Q04 导出审计、Q05 旧版条款。真实工具和最终模型观察与诊断一致，没有新增清洗/回流丢失。逐题 marker 与 SOURCE_CAP 见 retrieval-results.json；实际 quote、消息及引用身份见 integration-results.json。

Q03 逐短语审查：QA 同时包含自动化验收、阻断缺陷关闭、迁移核对、两小时、连续两次阈值、或不可恢复异常、暂停和最近稳定版；Agent8 仅保留灰度阶段，不能判完整。

复现：上述两命令；按 Q03/Q04/Q05 facts.agentStage 对照候选及 sources。既有选择器反例测试、P6 冻结回归与新增实际工具传递一致性断言均覆盖该边界。

同候选对照：Agent12 从22/27到25/27（+3事实，+11.11个百分点），救回 Q03 回滚、Q04 审计、Q05旧版；Q03 上线门禁仍缺失，Q02 仍缺失。该收益只来自真实向量上的构建器诊断，尚未把limit12接入完整Runtime或真实生成，不作为上线依据。

候选最小修复：有界互补片段选择/章节补读，或同预算下对两阶段代表分配做测试侧对照；简单扩大limit不能独立解决问题。风险是独立文档覆盖下降、旧版/第二事实被替换、quote截断、Token增加。未来必须逐题 gained/lost、E1–E11、CE反例与真实工具回流对照，关键事实零退化；真实生成收益另需授权。值得独立专项。

## F3 Q09：本轮未复现 CITATION_FAILURE

QA 与实际 Agent 工具均有甲乙两个独立 documentId/chunkId；相同正文去重没有合并文档身份，最终持久化身份均可回查本项目真实块。无需再修历史缺陷。实际生成语义支持仍 NOT_MEASURED；不能把脚本摘录当作真实模型比较正确。

## F4 Q07/Q12：证据充分性不可由引用数判断

Q07 有 8 个相关引用，但没有“八十年”所需事实。脚本按题目拒答标签表达不足，并非模型自主识别。没有发现新的编排或持久化缺陷；真实生成层错误/拒答与资料充分性状态的关系 INCONCLUSIVE。建议下一轮设计独立的“逐事实可支持”契约，再决定是否改变不足状态，禁止关键词式状态推断。

## F5 外部层：EXTERNAL_BLOCK

GeminiKnowledgeAnswerQualityPostgresTest、RealAgentLoopPostgresTest 未获本轮新外部调用授权，BLOCKED_EXTERNAL，不记通过或生成失败。没有尝试429重试、切换供应商或读取私密配置。

## F6 测试装配限制（已修正，非生产缺陷）

初步跨层测试未显式脚本化 Q02/Q07 的辅助摘要入口，Mockito null触发生产降级。保留初始日志，不以断言绿色掩盖额外调用；最终明确 EMPTY 摘要、计辅助调用并重跑。最终原始结果为唯一当前链路裁决依据。

## F7 Q13：脚本输出文本编号 FAIL / CITATION_FAILURE（机械现象确认）

text-citation-audit.json 实测 Q13 的最终文本包含无可用来源的 [S999]；来源编号按实际 sources 顺序核对，其他题未发现越界编号。S999 来自冻结恶意资料，经逐字复制脚本进入最终回答，数据库 content 原样保留。结构化 citations_json 中没有伪造 chunk/document，来源卡身份仍全部有效。AgentRunEventRecorder.recordFinal 的现行契约投影实际读取来源并保存模型文本，没有复用 QA 的 KnowledgeCitationValidator 对文本做最终编号校验。

复现直接查看 integration-results.json Q13 的 toolResult、persistedAnswer、persistedCitations；这不是本轮真实模型注入遵从证据，也不证明资料指令被执行。已确认的是未知文本标号可穿过实际 Runtime 被保存；编号语义契约与真实生成安全性须分开。现有单测钉住身份集合，尚未钉住 Agent 最终文本未知编号。下一轮可先制定 Agent 引用编号映射/源卡展示契约，再以脚本负例测试最小校验；直接套用 QA 清理器可能损坏正常引用文档原文中的字面编号，必须增加“引用文本 vs 文档摘录”的反例和语义审查。建议进入独立契约修复调查，未经该调查不直接改生产清洗。
