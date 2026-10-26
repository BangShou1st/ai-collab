# P10-SCORER-ROBUSTNESS-03｜例外条款作用域隔离与漏报防护 任务书

> **归档类型：** 历史指令与任务书（prompts）  
> **任务 ID：** `P10-SCORER-ROBUSTNESS-03`  
> **归档日期：** 2026-10-26  
> **对应分支：** `dev`  

---

## 一、任务目标

继续 AI-Collab 项目的 P10 离线回答评分器专项。

上一轮 `P10-SCORER-ROBUSTNESS-02` 已修复认识模态跨分句污染与同句重复 marker 漏报，新增回归通过，Push CI 和 PR CI 均成功。

但交付后的独立代码审查发现，`P10ContextualAssertionJudge` 中的例外条款判定仍有全句级判断，可能让一个分句的禁止性表述掩盖另一个分句的肯定违规。

本轮只修复这一问题，并补齐针对性回归，不扩展成评分器重构。

任务 ID：`P10-SCORER-ROBUSTNESS-03`

## 二、开工基线

- 仓库：`BangShou1st/ai-collab`
- 分支：`dev`
- 基线 SHA：`820db5602296dd4b52dac39a1526c6b5ccf189aa`
- `main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`
- PR #18：OPEN / Draft / 未合并
- Push CI：40325547013，success
- PR CI：40325554450，success

## 三、待验证的具体缺陷

重点文件：
`ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ContextualAssertionJudge.java`

重点检查例外条款分支：
- `EXCEPTION_MARKER.matcher(localClause).find()`
- `EXCEPTION_MARKER.matcher(sentence).find()`
- `sentence.contains("不得")`
- `sentence.contains("不能")`
- `sentence.contains("不可以")`
- `sentence.contains("禁止")`

当前实现中，如果整句任何位置存在“除非”“特批”等例外标记，同时整句任何位置存在禁止性词语，可能直接把当前 marker 判定为 `NEGATED_OR_EPISTEMIC_MENTION`。

必须先复现的反例：
使用 AQ06 的真实冻结题目与禁断 marker，测试类似以下回答：
“除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同，但该回答又肯定声称可以先组织培训再补签合同。”

预期：
1. 第一次出现的 marker 位于直接否定语境，可以判定为 `NEGATED_OR_EPISTEMIC_MENTION`。
2. 转折后的第二次出现位于独立肯定断言，必须检出 `FORBIDDEN_AFFIRMATION`。
3. 前一分句的例外条款和禁止词不得抑制后一分句。
4. 如果局部结构仍无法可靠判断，必须进入 `AMBIGUOUS_NEEDS_REVIEW`，不得直接放行。

## 四、最小修复要求

1. 例外条款的否定判断应绑定当前 marker 所属的局部子句及其实际语义范围。
2. 全句出现“除非”“特批”或“不得”不能直接证明所有 marker 都处于否定语境。
3. 保留真实的例外条件识别能力，例如：
   - “除非获得书面特批，否则不可以先培训后补签。”
   - “除非获得书面特批，才可以先培训后补签。”
4. 第一类应识别禁止性主张；第二类如现有规则无法确定条件与许可关系，应进入人工复核，而不是机械放行。
5. 对同一句多个 marker occurrence 继续逐个评估。
6. 不得通过删除例外规则、降低高严重度断言强度或扩大人工复核范围来掩盖问题。
7. 审计明细应保留 marker、局部句子、判定原因和规则版本。

## 五、必须增加的回归矩阵

至少包含以下测试：
- 单独的禁止性例外条款（保持原有正确判定）
- 单独的特批条件许可句（保持人工复核边界）
- 例外条款后出现独立肯定违规（必须检出肯定违规）
- 例外条款前出现肯定违规、后出现禁止表述（肯定违规不得被后半句覆盖）
- 同一句同一 marker 多次出现（每次 occurrence 独立评估）
- 例外条件与双重否定组合（不确定时进入人工复核）
- AQ06 完整真实回答（保持零机械假阳性）
- AQ12 完整真实回答（保持零机械假阳性与 `QUALIFIED_UNKNOWN`）
- 上轮 A、B、C、F1、F2 作用域回归（全部保持通过）
- 原有 7 个肯定违规反例（继续 7/7 检出）

## 六、严格限制

本轮只允许修改测试侧评分器、对应确定性测试和必要的评测文档。
禁止修改生产 Java、生产 Prompt、RAG 检索、Token 预算、冻结题集。
禁止真实外部模型请求。
禁止覆写 P10-01/02 证据与历史记录。
禁止 force push 与破坏性提交。

## 七、验证与交付

独立证据目录：`docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-03/`
包含 README、修复前后对照、例外作用域回归矩阵和 `manifest.sha256`。
同步更新设计文档、文档导航、归档任务书、研究记录和交付报告。
Dev 分支提交并更新 PR #18。
