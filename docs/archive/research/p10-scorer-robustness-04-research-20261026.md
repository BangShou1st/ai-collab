# P10 离线评分器确证缺陷修复技术调研与设计推导（P10-SCORER-ROBUSTNESS-04）

> **专项任务：** `P10-SCORER-ROBUSTNESS-04`  
> **日期：** 2026-10-26  
> **归档位置：** `docs/archive/research/p10-scorer-robustness-04-research-20261026.md`  

---

## 1. 缺陷根因剖析与修复推导

### 1.1 RISK-P10-001 & RISK-P10-002（双重否定全局短路与拒答级联失守）
- **根因**：原 `P10ContextualAssertionJudge.evaluateOccurrences()` 在对句子提取 occurrence 后，执行了 `if (DOUBLE_NEGATION.matcher(cleanSentence).find()) return results;`。此判断在整句大句级别触发，直接短路返回单个 `AMBIGUOUS_NEEDS_REVIEW`，既丢失了后续 occurrence，又导致后半句在转折后的独立违规断言未被判定为 `AFFIRMATIVE_VIOLATION`。进而使无答案题的 `affirmativeViolations` 为空，拒答检测器误落入强拒答分支判定为 `HONEST_ABSTENTION`。
- **设计推导**：
  1. 彻底删除 `evaluateOccurrences()` 开头的全局双重否定短路；
  2. 将双重否定判断下沉至 `judgeSingleOccurrence()` 内部，并限制其仅检测当前局部子句 `localClause`；
  3. 利用标点与转折词硬边界，隔离前置双重否定与后置独立肯定断言；
  4. 在 `P10AbstentionDetector` 中新增安全重载，传入 `unresolvedAmbiguousForbidden`，确保即使未来有未澄清的歧义禁断 marker，也不会被简单放行为诚实拒答。

### 1.2 RISK-P10-003（反差转折规则的全局污染）
- **根因**：原规则 A 对整个大句 `sentence` 匹配 `RHETORICAL_OR_HYPOTHETICAL`，并粗暴检查 `sentence.contains("实际上")`。导致前半句是“理论上应该在三个工作日内完成”，后半句是“实际上华东中心盘点差异率超过百分之五...”，后半句独立的地区阈值违规被无辜降级为人工复核。
- **设计推导**：
  1. 反问句式（“难道”、“岂能”）限定在当前子句 `localClause` 内部；
  2. 转折反差模式仅在紧邻前置从句包含认识模态 `EPISTEMIC_UNKNOWN`（如“并未明确……但实际上可以……”）时触发人工复核；
  3. 非认识模态的事项反差（如工期与盘点率对比）不触发降级，后半句独立判定为肯定违规。

### 1.3 RISK-P10-004（认识模态短前缀错误放行）
- **根因**：原规则 E1 包含启发式放行条件 `localPrefix.length() <= 6`。当回答为“资料不足我们可以先组织培训再补签合同”时，“资料不足我们”长度为 6 且包含“资料不足”，被直接判为认识模态提及放行。
- **设计推导**：
  1. 彻底删除 `localPrefix.length() <= 6`；
  2. 引入 `SUBJECT_OR_AGENT` 正则（匹配“我们|部门|团队|人员”等主语代词）；
  3. 当存在主语代词介入并连接肯定动作动词时，阻断认识模态判定，确保行为被准确检出为违规。

### 1.4 RISK-P10-005（后置否定谓语误报）
- **根因**：原评分器只有针对 `localPrefix` 的前置直接否定正则，缺乏对主语命题后置否定谓语（如“是不允许的”、“是禁止的”、“是不合规的”）的支持，导致模型合规作出的否定阐述全部滑落至规则 F 被误判为肯定违规。
- **设计推导**：
  1. 定义 `SUFFIX_NEGATION` 正则覆盖汉语常见后置否定谓语；
  2. 新增规则 C2 检测局部后缀 `localSuffix` 的紧邻否定修饰；
  3. 保持后接肯定谓语（如“是必须申报的”）时的违规检出。

### 1.5 RISK-P10-006（版本限定词跨分句泄漏）
- **根因**：`P10AnswerQualityScorer` 检查版本限定时，仅按整句扫描 `legacyWords.stream()...noneMatch(sentence::contains)`。当回答为“虽然原规定已经废止，但一线城市住宿费标准仍为四百元”时，因前半句有“原规定”和“废止”，后半句转折后主张旧标准的行为被错误豁免。
- **设计推导**：
  1. 引入分句级 `isLegacyOccurrenceQualified()` 逐 occurrence 判定；
  2. 转折连词截断前置分句历史/废止背景词的传递；
  3. 转折分句若缺乏自身版本限定词，必须判定为 `VERSION_VIOLATION`。

### 1.6 RISK-P10-007（例外条款污染无关命题）
- **根因**：`isPrecededByExceptionClause()` 仅根据紧邻前置从句包含 `EXCEPTION_MARKER` 即向后传递修饰。当回答为“除非获得特批否则不得推迟上线，可以先组织培训再补签合同”时，前置特批上线例外污染了后半句培训合同命题。
- **设计推导**：
  1. 引入 `isSelfContainedExceptionClause()` 判断前置从句是否已自闭环（已包含自身禁止谓语如“否则不得...”）；
  2. 自闭环例外从句禁止向后传递到无关独立命题；
  3. 保留未闭合条件状语（G1–G4）对紧邻主句的合法修饰。

---

## 2. 验证与回归结果

- 修复前复现：7 项确证缺陷在基线代码上 100% 失败；
- 修复后复现：7 项定向测试全部 100% 通过；
- 全套测试：`P10ScorerRobustnessTest` 29 项测试全绿，全包 37 项测试无回归失败；
- 生产零侵入：`src/main/` 生产代码零修改，冻结题集零修改，外部真实 API 请求为 0。
