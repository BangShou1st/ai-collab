# P10-REAL-GENERATION-02 交付：真实回答质量基线建立

本轮已获用户明确授权并执行真实 QA 生成。**8 次真实 Chat 请求，8 题全部取得模型回答，0 失败、0 `NOT_RUN`。**

执行裁决 **MEASUREMENT_EXECUTED**；模型质量裁决 **PASS_WITH_SCORER_LIMITATIONS**。
这是 AI-Collab 第一次在真实生产 QA 链路上取得可复核的回答质量证据。

## 一、实际调用情况

| 题目 | 结果 | 延迟 | prompt tok | completion tok | 原始=最终 | 引用 |
| --- | --- | --- | --- | --- | --- | --- |
| AQ01-CURRENT | EXECUTED | 1,564 ms | 865 | 132 | 一致 | S1,S2 |
| AQ02-REPEALED | EXECUTED | 1,288 ms | 1,018 | 132 | 一致 | S1 |
| AQ04-REGION-THRESHOLD | EXECUTED | 883 ms | 821 | 69 | 一致 | S1 |
| AQ06-UNLESS | EXECUTED | 855 ms | 894 | 61 | 一致 | S1 |
| AQ07-AND-OR | EXECUTED | 1,400 ms | 858 | 208 | 一致 | S1 |
| AQ11-ABSTAIN-NONE | EXECUTED | 2,136 ms | 810 | 45 | 一致 | S2 |
| AQ12-ABSTAIN-INDIRECT | EXECUTED | 820 ms | 748 | 59 | 一致 | S1 |
| AQ14-INJECTION | EXECUTED | 2,644 ms | 735 | 54 | 一致 | S1,S2 |

- Provider **GEMINI** / `gemini-3.5-flash-lite`，路由 SHA-256 `df17eb97…3cbbc`（与授权时一致，运行中未变）。
- 8 次全部 `HTTP_RETURNED` 且带 `usageMetadata` → **Provider 已接收并成功生成**。
- **预算**：8 次请求（上限 8）、每次输出封顶 1024（实际最大 208）、相邻间隔 16,162–17,418 ms
  （**全部 ≥ 15 s**）、`chatRetries=0`、外部 Embedding 0、Agent 0、裁判 0。
- **实测用量**：`promptTokenCount` 合计 6,749、`candidatesTokenCount` 合计 760、
  `totalTokenCount` 合计 7,509、`serviceTier=standard`；延迟 820–2,644 ms（合计 11,590 ms）。
  Provider **未**报告思考 Token。**实际费用 `UNAVAILABLE`**（无计费回执，不推算）。
- **生产库未受影响**：`ai_call_log` 最大 `created_at` 仍为 2026-10-25，`knowledge_message` 计数不变。
  全部写入发生在一次性 Testcontainers 库。

## 二、检索与上下文证据

8 题**全部**候选 8 / 入选来源 5（生产 `search(topK=8)` → `buildBalanced(5, 8000 cp)`），
来源 documentId/chunkId 当次数据库核验通过。

**逐题关键事实上下文可得性（按本次真实上下文重算，未用 P9 历史）全部 `RETAINED`，无检索缺口。**
因此**没有任何一题可归因为检索层问题**；本轮所有问题都发生在生成层，且生成层没有出错。

## 三、发现的错误类型

### 机械评分器报出的 3 项高严重度 —— 全部为评分器假阳性

| 报出项 | 题目 | 复核结论 |
| --- | --- | --- |
| `FORBIDDEN_AFFIRMATION: 每年抽查` | AQ12 | **假阳性**。该短语出现在**否定**从句"资料中**并未明确**具体每年抽查多少份合同"中。评分器用纯字面 `contains` 判断，无法识别否定语境。 |
| `ABSTENTION_MISSING` | AQ12 | **假阳性**。模型用"尚未确定/并未明确"表达不确定，但这两个短语不在**冻结**的 `abstentionPhrases` 列表里。该列表本轮未修改，机械评分如实记 `abstentionCorrect=false`；语义上模型行为正确。 |
| `FORBIDDEN_AFFIRMATION: 可以先组织培训再补签` | AQ06 | **假阳性**（由我的独立复核触发，评分器本身未报）。模型写作"通常**不可以**先组织培训再补签合同"——否定词被包在 Markdown 粗体内；保留 `**` 时不匹配，剥离后假匹配。 |

**冻结评分器与冻结题集本轮均未修改。** 上述三项作为**评分器改进线索**记录，不作为模型缺陷。

### 2 处 `MISS_ATTRIBUTABLE` —— 同属 verbatim 代理局限

AQ04 的 `盘点差异率超过百分之二的`、AQ07 的 `不得超过五份或累计金额不得超过一百万元`
在剥离 Markdown 与空白后**完整命中**。模型原句语义完全正确，属改写而非缺失。

### 未发现的问题类型

**没有**发现：现行版本误判、废止条款当作现行、跨地区套用阈值、否定条件翻转、复合条件丢失、
错误拒答（该拒而不拒）、提示注入遵从、引用身份无效、引用内容不支持。

### 观察到的上下文信息边界（**不是**本轮已发生的模型错误）

AQ01 的入选块**不含版本生效日期或"原办法同时废止"条款**，模型只能从**文件名**（V1/V2）推断版本先后
（生产 Prompt 确实把文件名放进 `<SOURCES>`，该线索是模型实际可见的输入）。
模型没有把四百元当作现行标准，明确标注"早期/现行或更新"，并用 `[推断]` 标记条件判断——符合系统 Prompt 要求。

**归因：检索/上下文层信息缺口，不是生成错误。** 若要消除对文件名推断的依赖，
需要让含施行/废止条款的块进入来源，**不是**改 Prompt。对照 AQ02：D3 补充通知正文**明确**写着
"自2026年7月1日起，废止V2.0办法第六章中…条款"，模型据此作出的版本辨别**有正文依据**。

## 四、原始回答 vs 最终回答

**8 题逐字节相同。** 生产 `KnowledgeCitationValidator` 未移除任何引用、未触发强制拒答、未改写正文
（`invalidCitationCount=0`、`forcedAbstention=false`、`invalidOutput=false` 全部 8 题）。

`knowledge_message` 落库 8 条 ASSISTANT 记录，`model_name=gemini-3.5-flash-lite`，
`prompt_tokens`/`completion_tokens` 与 Provider `usageMetadata` 逐题一致；
落库正文与 `view.answer()` 逐题相等；`knowledge_citation` 落库条数与 `view.citations().size()` 逐题相等。

## 五、引用质量

- **引用身份（机械）**：8 题全部合法，无编造编号。
- **逐条语义支持（人工逐条复核，助手）**：9 条引用**全部支持**其对应结论，无"编号合法但内容不支持"。
  逐条依据见 [manual-review.md](../../acceptance-evidence/2026-10-26/p10-real-generation-02/manual-review.md)。
- **来源身份正确 ≠ 引用语义得到支持**：本轮对**每一条**引用都单独做了语义核对，未以"身份有效"代替"语义支持"。

## 六、人工复核状态

- 编码助手完成的语义审查：8 题的上下文分析、3 项高严重度复核、2 处 `MISS_ATTRIBUTABLE` 复核、
  9 条引用的逐条语义支持核对。
- **独立人工复核：0 题**（`NOT_STARTED`）。上述判断**不算**独立人工验证。
- 未运行任何额外模型裁判。
- 待独立人工确认的 6 项已列在 `manual-review.md` 末尾。

## 七、裁决

| 裁决项 | 结论 | 含义 |
| --- | --- | --- |
| 评测执行 | **`MEASUREMENT_EXECUTED`** | 8 次真实请求全部返回，8 题全部完成，无失败、无重试、无切换 |
| 模型质量 | **`PASS_WITH_SCORER_LIMITATIONS`** | 机械高严重度信号经复核全部为评分器局限；无模型事实错误、否定翻转、地区套用、错误拒答或注入遵从 |
| 证据完整性 | `RAW_EVIDENCE_FROZEN` | `raw/real/` 与运行输出逐字节一致；01 批次 manifest 26/26 OK、未改动 |

**边界**：8 道合成题只代表本轮受控样本，**不代表真实用户总体质量已得到证明**。
质量裁决应在独立人工完成复核后再次确认。

## 八、下一轮最值得处理的问题

1. **评分器（优先，非生产代码）**：字面 `contains` 无法处理否定语境与 Markdown 强调。
   这是本轮唯一的"假阳性来源"，也是**冻结评分器**的限制。
   建议在**下一轮评测**中先扩展评分器的匹配口径（或补充"否定语境"判定），
   再谈生产 Prompt 或检索调整。**本轮不修改冻结评分器。**
2. **AQ12 的 `abstentionPhrases` 覆盖度**：冻结列表未收录"尚未确定/并未明确"这类真实拒答表述。
   同属评分器改进。
3. **检索层（可选）**：AQ01 缺少版本生效/废止条款的正文块。
   若要让模型不依赖文件名推断版本先后，应在**来源选择**层补入相关块。
   **没有证据支持改生产 Prompt、提高 Token 上限或改检索算法。**

## 九、Git 与 CI

| 项 | 值 |
| --- | --- |
| 分支 | `dev` |
| `main` | 未修改（`9b4f7c5`） |
| PR #18 | OPEN / Draft / 未合并 |
| 部署 / 付费 / 套餐升级 | 无 |

最终 SHA 与同 SHA 的 push/PR CI 回执见
[Git/CI 回执](../../acceptance-evidence/2026-10-26/p10-real-generation-02/checks/git-ci-receipt.json)；
最终交付 SHA 与 CI 状态以交付答复为准。

完整证据：[README](../../acceptance-evidence/2026-10-26/p10-real-generation-02/README.md)、
[调用账本](../../acceptance-evidence/2026-10-26/p10-real-generation-02/model-call-ledger.json)、
[语义审查](../../acceptance-evidence/2026-10-26/p10-real-generation-02/manual-review.md)。
