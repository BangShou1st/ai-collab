# P10-SCORER-ROBUSTNESS-01 任务书归档

> **归档位置：** `docs/archive/prompts/p10-scorer-robustness-01-task-20261026.md`  
> **任务时间：** 2026-10-26  
> **任务来源：** 用户明确指令

```markdown
P10-SCORER-ROBUSTNESS-01｜真实回答评分器稳健性专项
一、任务目标
继续 AI-Collab 项目，在 P10-REAL-GENERATION-02 真实模型验收完成的基础上，修复回答质量评分器已证实的机械误判。
本轮目标是提高评测工具的可信度，而不是提高模型得分。
任务 ID：P10-SCORER-ROBUSTNESS-01
最终应能证明：
Markdown 格式变化不会造成相同语义被不同评分。
否定语境、引用语境、未知状态和肯定断言可以合理区分。
拒答检测不再完全依赖有限的固定短语列表。
无法可靠判断的语境会进入人工复核，而不是机械地判定通过或失败。
历史冻结题集、真实模型原始回答和旧评分结果均保持不变。
默认 CI 不执行真实模型请求。
二、开工基线
上轮已核实：
dev：0b31d1217fc6d52eb1bfe6725d5025e12e6cf3d6
main：9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7
PR #18：OPEN / Draft / 未合并。
Push CI：40301270348，success。
PR CI：40301277726，success。
开工时必须重新核实当前本地与远端状态，以上 SHA 不能直接视为当前事实。
严格阅读 AGENTS.md、docs/README.md 以及 P10 的设计、评分器、离线测试和真实生成证据。
重点文件：
ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityScorer.java
ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityOfflineEvaluationTest.java
docs/design/rag-answer-quality-evaluation.md
docs/acceptance-evidence/2026-10-26/p10-real-generation-02/
docs/archive/reports/p10-real-generation-02-delivery-20261026.md
docs/archive/research/p10-real-generation-02-research-20261026.md
三、范围限制
本轮只修改测试侧评分器、确定性测试、评测设计说明和新增证据。
禁止修改生产回答行为：
不修改生产 System Prompt。
不修改 RAG 检索、上下文构建、来源选择或 Token 预算。
不修改 KnowledgeCitationValidator 生产实现。
不修改模型路由、Provider 配置或生产服务。
不发送任何真实 Chat、Embedding、Agent 或模型裁判请求。
不为了让 P10-02 通过而修改冻结题集、预期事实或历史结果。
不把编码助手的复核记录改称独立人工验证。
如发现生产问题，只记录证据并提出后续专项建议。
四、首先处理 P10-02 文档一致性问题
只读检查 P10-02 的 README、generation-results.json、model-call-ledger.json、manual-review.md、Git/CI 回执和 manifest。
已发现的现象：
README 开头与后续正文均记录 8 次真实调用和 8 份回答。
README 中间的批次对比表仍将 P10-REAL-GENERATION-02 写为 0 次请求、0 份回答、等待授权。
generation-results.json 的 qualityVerdict=REQUIRES_PER_QUESTION_REVIEW 与后续回执的 PASS_WITH_SCORER_LIMITATIONS 代表的阶段可能不同，必须明确其语义，不能擅自覆盖。
处理要求：
查明旧表格是否属于预检阶段遗留内容。
查明 generation-results.json 的质量状态是原始机械测量状态还是最终人工复核状态。
在新的独立目录中记录 errata，说明准确的前后关系及证据依据。
不直接修改已冻结的 P10-02 原始证据，不破坏原 manifest。
如果有无法确定的字段，明确记录 UNRESOLVED，不得猜测。
建议目录：
docs/acceptance-evidence/2026-10-26/p10-real-generation-02-errata/
如果发现其他实质性不一致，同样记录，但不要借机重写旧报告。
五、评分器改造 A：统一文本规范化
当前评分器大量使用 answer.contains(marker)、sentence.contains(marker) 和 abstentionPhrases.stream().anyMatch(answer::contains)。
这类原始字符串匹配无法稳定处理 Markdown 强调、空白和部分格式变化。
请新增独立的、可测试的规范化机制，至少覆盖：
Markdown **bold**、*italic* 等强调标记。
中英文标点与 Unicode 规范化。
连续空白、换行和不必要的格式分隔。
预期 marker 与回答文本采用一致的规范化规则。
规范化时必须保留实际词语、否定词、例外词和句子边界。不得通过删除否定词或过度移除标点来制造匹配。
引用编号、代码片段和普通文本可能有不同语义，不能用一个无差别的正则删除所有 Markdown 特殊字符。
至少增加以下回归：
通常**不可以**先组织培训再补签合同。
通常不可以先组织培训再补签合同。
含 Markdown 强调的预期事实 marker。
仅有空格或换行差异的等价事实。
实际缺失 marker 的回答仍然保持缺失。
同一语义的格式变体应得到一致的机械结果。
六、评分器改造 B：区分肯定断言与非肯定提及
本轮的核心缺陷是：发现禁断短语不等于发现模型肯定了禁断命题。
P10-02 中已出现两类误判：
AQ06-UNLESS
真实回答：
根据项目资料，通常不可以先组织培训再补签合同。
原规则包含禁断 marker：
可以先组织培训再补签
评分器应识别否定词属于该命题的作用域，而不是因为后半段字面匹配就报告肯定违规。
但不能简单规定“句子里出现不、未、无，就永远忽略违规”。双重否定、否定对象不同、例外条款以及跨分句结构都可能改变含义。
AQ12-ABSTAIN-INDIRECT
真实回答中出现：
资料中并未明确具体每年抽查多少份合同。
其中 每年抽查 虽然命中禁断 marker，但完整句子表达的是资料未明确具体份数，而非肯定一个具体抽查数量。
评分器不能仅凭局部词组认定模型编造了规则。
设计要求
请实现显式的上下文判定结果，至少区分：
AFFIRMATIVE_VIOLATION：有充分机械证据表明回答肯定了被禁止的命题。
NEGATED_OR_EPISTEMIC_MENTION：命题处于明确否定、未知状态或资料不足的语境。
AMBIGUOUS_NEEDS_REVIEW：机械规则无法可靠判定语义。
实际命名可遵循项目既有枚举风格，但必须区分上述三种含义。
要求：
只有可靠判定为肯定违规时，才直接产生相应的高严重度信号。
非肯定提及不得直接判为违规。
复杂否定、双重否定、例外或跨分句情况若不能可靠处理，必须进入人工复核。
保留触发 marker、匹配句子、命中位置、判定原因及规则版本，便于审计。
不允许通过把所有疑似违规都标成“无需复核”来减少误报。
对当前冻结题集里的每个禁断规则做兼容性回归，确保原本的肯定错误仍可检测。
请优先采用小而可验证的确定性规则。不要为了追求所谓通用中文语义理解而编写复杂、不可验证的启发式。
七、评分器改造 C：增强拒答检测
冻结题集中的 abstentionPhrases 必须保持原样。
新增的拒答识别能力不能通过修改冻结 JSON、扩大原始规则列表或改写历史结果实现。
本轮需要把两种信息分开记录：
原始冻结规则是否命中。
新版评分器是否检测到合理的拒答或未知状态表达。
例如：
尚未确定
并未明确
资料中未提及
当前资料不足以回答
现有材料无法确认
这些表达可能是正确拒答，但必须结合题目要求与句子语境判断。出现任意一个词不代表回答一定诚实。
至少区分：
明确拒答或明确承认资料不足。
有限定条件的未知状态。
回避问题但仍作出未经支持的肯定断言。
没有拒答且编造答案。
语境不明确、需要人工复核。
新增结果必须说明检测依据。旧的冻结规则命中情况仍然可追溯。
对于无答案题，只有新版判定有充分证据支持“已诚实拒答”时才消除对应的高严重度告警；不确定情况进入人工复核，不直接判通过。
八、测试矩阵与版本对照
必须增加确定性测试，默认 CI 执行，无任何外部模型请求。
至少包括：
场景
期望行为
肯定违规命题
报告高严重度违规
Markdown 包裹否定词
不因格式产生假阳性
明确“资料未明确”的表达
不把未知状态直接判为肯定违规
明确无答案且诚实拒答
新版检测为合理拒答
仍给出编造数字并附带拒答措辞
不能只因出现拒答短语就通过
双重否定
不能无条件忽略命中，必要时转人工
例外条款
不把符合例外的回答直接判为违规
标点、空白、Markdown 变化
等价文本评分保持一致
冻结 marker 确实缺失
仍能识别缺失
引用编号规则
现有合法/非法引用回归不退化
版本限定与注入规则
现有高严重度反例继续命中
AQ06、AQ12 真实回答片段
修复已知误判，并保留可审计判定依据
对每个规则保留旧版与新版评分的对照，统计：
修复的已知假阳性数量。
新增的漏报反例数量。
新增的人工复核数量。
原有高严重度反例的检出率。
模范回答的误报情况。
不得以高严重度告警减少作为唯一成功指标。 必须证明肯定违规反例仍然可以检出。
新版评分器需要有明确版本号；历史 JSON 和 P10-02 旧结果不自动重算、不覆盖。
九、证据与文档
新增证据目录：
docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-01/
至少包括：
README.md：任务范围、结论和限制。
scorer-version-comparison.json：旧版与新版的确定性对照。
false-positive-regressions.json：已修复的假阳性证据。
false-negative-regressions.json：肯定违规反例的检出证据。
manual-review-boundaries.md：必须人工复核的语境。
manifest.sha256：新增证据完整性。
同步维护：
docs/design/rag-answer-quality-evaluation.md
docs/README.md
docs/archive/prompts/ 中的任务书
docs/archive/research/ 中的研究结论
docs/archive/reports/ 中的交付报告
所有文档必须放在项目规定的位置，不得在 docs/ 根目录平铺过程文件。
十、验证与 Git 约束
先运行 P10 离线定向测试。
验证真实模型 opt-in 关闭时，外部调用数为 0。
运行必要的后端回归。
完整后端 verify 按仓库要求执行；若有环境故障，保留首次失败证据并单独记录重跑结果。
不重复无关前端测试。
不发送真实 Chat、Embedding、Agent 或模型裁判请求。
只提交相关文件至 dev。
不覆盖用户 WIP，不使用 git add -A。
不 force push、不改写历史、不执行危险的清理或重置操作。
不修改 main，不合并 PR #18，不部署。
最终核实最新 SHA 与 origin/dev 同步，且同 SHA 的 Push CI 和 PR CI 状态真实可查。
十一、最终必须回答
P10-02 的旧 README 不一致究竟是什么原因？errata 如何处理？
哪些误判已被确定性修复？
新版如何区分肯定违规、否定/未知语境与不确定语境？
拒答检测如何扩展，同时保留冻结规则的可追溯性？
AQ06、AQ12 的旧真实回答在新版下分别得到什么结果？
原有肯定违规反例是否仍全部检出？
是否增加漏报风险或人工复核负担？
是否有任何生产 Java、Prompt、检索算法或冻结题集变更？
新版评分器是否通过默认离线 CI，外部调用是否仍为 0？
最终 Git SHA、工作区、CI 与 PR #18 状态是什么？
验收原则： 修复的是评测器的误判机制，不是让模型分数变好看。真实回答、冻结规则、历史失败和原始证据均保持不可变；语义不确定时宁可要求人工复核，也不能伪造确定结论。
```
