# P10-REAL-GENERATION-03 失败分析与归因（failure-analysis）

> 归因原则：证据未进上下文而缺失 = 系统缺口（不可归责模型）；已进上下文而缺失/错误 = 生成层责任；
> 未进上下文而"答对" = 泄漏/幻觉观察；环境/工具故障与模型行为严格分开记账。

## 一、执行失败项总表

| 项 | 状态 | 精确原因 | 归因 |
| --- | --- | --- | --- |
| QA 17 题 | 17/17 `HTTP_RETURNED` | 无失败、无重试、无 NOT_RUN | — |
| Agent AG06 | FAILED | `AI_PROVIDER_OUTPUT_TRUNCATED`（Kilo 免费模型输出截断，循环内重试后仍截断） | **外部模型限制**（免费模型服务端截断），`NOT_ASSESSABLE_NO_VALID_ANSWER` |
| Agent AG15 | FAILED | 同上 | 同上 |
| Agent AG19 | FAILED | 同上 | 同上 |
| Agent attempt-1（全 20 场景） | 测量工具失败 | 测试夹具构造 Provider 时缺 `presetCode=KILO_GATEWAY_FREE`，生产路由按通用 OpenAI 适配器解密 null 密钥 → `AI_MODEL_CREDENTIAL_INVALID` 本地快速失败（20 次，未出网，全部记账） | **测量工具缺陷**（本批独立修正，历史批次 RealAgentLoopPostgresTest 不受影响；失败账本归档 `raw/agent/failed-attempts/`） |
| Agent attempt-2 | 测量工具失败 | AG01 真实执行成功（2 次模型调用）后，内嵌评分回放 NPE（评分器 AGENT 协议 citations 参数传 null） | **测量工具缺陷**（该次 AG01 的真实回答与账本归档保留） |
| Agent attempt-3 | 编译失败 | 重评分参数修正引入受检异常未捕获，0 次外部调用 | 测量工具缺陷 |
| Agent attempt-4 | 成功 | 上述工具缺陷全部修正后完整执行 | — |

## 二、检索层证据缺口（不可归责模型）

| 项 | 缺口事实 | 模型行为 | 裁决 |
| --- | --- | --- | --- |
| QA Q09 第 1 问 | "退回发起节点"分块未进入 QA top5 上下文（CONTEXT_MISS，实测入选 5 源中 B2 分块为节点流程+发起校验） | 明确回答"当前项目资料不足以回答该问题"，未编造 | **系统缺口 + 诚实拒答** |
| Agent AG04 第 1 问 | 同条款未进入该场景工具召回（工具候选池检索未命中该分块） | 呈现其真实所见证据（五节点流程、发起校验），未编造驳回流程 | **系统缺口，模型忠实于真实证据** |
| Agent AG17 第 2 问 | "申请自动撤回" marker 的 verbatim 载体在工具来源中部分可得 | 回答正确（"二十四小时内未补正自动撤回"） | 无缺口，语义成立 |

**关键发现**：同一"驳回后怎么处理"问题，QA（Q09，buildBalanced top5）与 Agent（AG02，KnowledgeSearchAgentTool 候选池 20）检索深度不同——Agent 检索到了 QA 未检索到的 B2 驳回处理分块并给出完整正确回答。这是 QA 与 Agent 回答质量差异的**机制性原因之一（上下文构建策略差异），而非模型能力差异**（注意本轮 QA 与 Agent 本就使用不同模型，见 comparison-report 的变量声明）。

## 三、评分器信号归因（34 条回答，v6 全程冻结未改）

| 信号 | 数量 | 归因分布 |
| --- | --- | --- |
| 高严重度 | 0 | — |
| needsHumanReview | 41 | 评分器字面局限 21（改写/Markdown 结构插入）· 列表序号与 UUID 噪声 12 · 设计内保守转复核 2（特批语境）· 检索缺口不可归责 2 · 拒绝写入/说明类 4 |
| 数字候选 | 12 | 全部为 Markdown 有序列表序号、来源身份 UUID 片段（Agent 回答呈现真实 documentId）、相似度小数；**0 项模型编造业务数字** |

### 测量工具对接缺陷（非评分器规则缺陷）

真实批次内嵌评分时，Agent 侧 `sources` 构造未覆盖两种工具返回结构（`quote` 字段名、`read_document_section` 的 `data.items` 无 filename/documentId），导致失真的 CONTEXT_MISS 与一次 CITATION_IDENTITY_INVALID（AG18）。修正由离线回放测试 `P10ScorerReplay03Test` 完成（评分器规则 v6 全程未动，源哈希冻结于 `scorer-and-corpus.sha256`；`scorer-results.json` 为权威评分）。该缺陷属本轮新增的回放/对接脚本，不涉及已修复的 v6 评分器规则本身，也未污染 QA 侧评分（QA sources 来自 retrieval-context 的完整结构）。

## 四、环境与外部故障

| 故障 | 处置 |
| --- | --- |
| `gemini-3.5-flash` 免费层配额耗尽（Judge-A 初始模型，429 quota exceeded） | 切换到 `gemini-3.5-flash-lite`；该原始裁判批次随后被来源覆盖审计限定，见下节 |
| Kilo 匿名账户无 `nvidia/nemotron-3-super-120b-a12b:free`（404） | 尝试的 Kilo 裁判批次单独归档；最终 Judge-B 原始批次使用本地 `qwen2.5:7b-instruct`，不把它误记为 Kilo 判读 |
| 裁判输入缺陷（部分 QA 来源文档未注入，Agent 判读没有来源材料） | 旧输入及结果保留在 `judge/invalidated-inputs/`；不把受影响的旧裁判结论当作有效证据，另建带来源的冻结输入并追加本地复核 |
| Kilo 网关间歇 5xx | 裁判脚本有界退避重试（≤4 次），失败条目续跑补齐，全部记账 |

## 五、模型裁判来源覆盖审计

`judge/source-coverage-audit.json` 按原判读 prompt 重建每条实际输入。Judge-A 与 Judge-B 的原始批次各有 34 条已判读结果：12 条 QA 有其对应来源材料，Q10/Q11/Q12/Q13/R3 五条 QA 缺少一个或多个相关来源；17 条成功 Agent 判读均没有来源材料。3 个失败 Agent 场景没有有效回答，记录为 `NO_ANSWER`，不计入 34 条已判读结果。因此每路旧批次只有 12 条来源完整，另外 22 条来源覆盖不足。

修正后的输入固定使用 QA 生产上下文来源与 Agent 实际工具证据/冻结文档，覆盖 5 条 QA 和 17 条成功 Agent。Qwen2.5 7B 本地批次得到 22 条结构化结果。Qwen3.5 4B 的 2,048-token 首批 4/4 截断；提高预算后计划 22 条、实际运行 13 条（5 条有效、4 条 JSON 不完整、4 条空截断），按连续空截断停止规则未运行剩余 9 条，没有重试，也没有外部 API 调用。这些本地模型输出仍是辅助判断，不能当作独立人类验收。

## 六、未决项（UNRESOLVED）

1. Provider 实际费用：`UNAVAILABLE`（无计费回执，不推算）。
2. 3 个 Agent 截断失败场景的模型质量：`NOT_ASSESSABLE_NO_VALID_ANSWER`（不纳入质量分母）。
3. AG18 的 `similarity` 值（如 0.6004）出现在回答正文中被评分器列为数字候选：属来源身份呈现，编码助手未判定为编造；该呈现风格对下游消费者是否恰当属产品设计问题，超出本轮范围。
4. 独立人类复核：0 题；文中语义结论属于编码助手复核。
