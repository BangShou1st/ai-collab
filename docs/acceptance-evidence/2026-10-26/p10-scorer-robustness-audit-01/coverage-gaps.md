# P10 离线评分器已有测试覆盖范围与盲区分析报告（Coverage Gaps）

> **审计任务：** `P10-SCORER-ROBUSTNESS-AUDIT-01`  
> **审计基线 Commit：** `c8c39bf554548b0fe8ee2eb237677b6eff77da47`  
> **生成日期：** 2026-10-26  
> **归档位置：** `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/coverage-gaps.md`

---

## 一、已有测试覆盖全景与统计

当前系统针对 P10 离线质量评分器的测试主要分布在三个测试类中：
1. `P10ScorerRobustnessTest.java`（22 个测试方法，包含 11 个专项回归场景与新旧对比）
2. `P10AnswerQualityOfflineEvaluationTest.java`（4 个测试方法，覆盖 14 题受控合成模范回答与 8 种失败变体）
3. `P10RealGenerationGateTest.java`（2 个测试方法，覆盖真实生成门禁鉴权与预算约束）

在上一阶段（P10-01 ~ P10-03），测试套件对以下场景建立了坚固的自动化回归防护：

| 场景分类 | 已覆盖的用例与断言 | 审计状态 |
|---|---|---|
| **Markdown 格式变体** | 粗体、斜体包裹否定词（AQ06 真实片段）消除假阳性 | `COVERED` |
| **基础认识模态** | 资料未明确、尚未确定等语境（AQ12 真实片段）消除假阳性 | `COVERED` |
| **诚实拒答识别** | 无答案题（AQ11/AQ12）识别 QUALIFIED_UNKNOWN 与 HONEST_ABSTENTION | `COVERED` |
| **汉字数字冲突编造** | 拒答短语 + 汉字数字编造（五十份）判定为 CONFLICTING_FABRICATION | `COVERED` |
| **单子句双重否定** | 单子句“并非不可以先组织培训再补签合同”转入 AMBIGUOUS_NEEDS_REVIEW | `COVERED` |
| **局部例外条款** | 除非得到特批 + 不得（禁止性主干）放行；除非得到特批 + 才可以转复核 | `COVERED` |
| **转折与认识模态隔离** | 未知在前+肯定在后（Case A）；肯定在前+未知在后（Case B）均检出违规 | `COVERED` |
| **转折与例外条款隔离** | 例外在前+肯定在后（Case G1）；肯定在前+例外在后（Case G2）均检出违规 | `COVERED` |
| **同句同一 marker 多次出现** | 针对先例外后肯定的重复 marker 进行了双 occurrence 断言 | `COVERED` |
| **引用与版本红线** | 非法来源 S9、裸用旧版本数值四百元、外部未审核注入指令均检出违规 | `COVERED` |

---

## 二、关键测试盲区与漏报成因

尽管现有 28 个测试用例在 CI 中全部通过，但由于**测试用例构造局限于已修复的特定模式**，在以下核心规则组合维度存在重大覆盖盲区：

### 盲区 1：双重否定与跨分句独立肯定违规的组合（CRITICAL）

- **测试现状**：
  现有测试套件中仅存在两个涉及双重否定的用例：
  1. `doubleNegationRoutedToHumanReview`：测试输入为单子句 `"根据资料，并非不可以先组织培训再补签合同 [S1]。"`，断言其进入 `AMBIGUOUS_NEEDS_REVIEW`；
  2. `exceptionWithDoubleNegationRoutedToReview`（Case G3）：测试输入为 `"除非获得分管副总裁的书面特批，否则并非不可以先组织培训再补签合同 [S1]。"`，断言其进入 `AMBIGUOUS_NEEDS_REVIEW`。
- **覆盖盲区**：
  **没有任何一个用例测试“整句包含双重否定词，但存在另一独立分句明确肯定违规”的情形！**
- **漏报成因**：
  实现代码在 `P10ContextualAssertionJudge.evaluateOccurrences` 开头直接加入了整句全局短路：
  ```java
  if (DOUBLE_NEGATION.matcher(cleanSentence).find()) {
      results.add(new Assessment(..., AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW, ...));
      return results;
  }
  ```
  现有的两个单子句测试恰好期望其返回 `AMBIGUOUS_NEEDS_REVIEW`，因此该缺陷在 CI 中被完全掩盖！

---

### 盲区 2：双重否定与拒答题编造冲突检测的级联交互（CRITICAL）

- **测试现状**：
  现有针对拒答冲突的测试用例 `fabricationWithEvasivePhrasesMustStillFail` 使用的坏回答为：`"资料不足，无法确认：但通常每年抽查五十份合同 [S1]。"`，验证了数字编造与拒答措辞冲突。
- **覆盖盲区**：
  **未测试拒答题中如果出现双重否定句式（如“虽然我们不得不承认资料不足...”）并伴随肯定编造（如“但确定使用 MySQL”）的级联反应。**
- **漏报成因**：
  测试套件将 `evaluateForbiddenAffirmations` 与 `P10AbstentionDetector.evaluate` 视为各自独立单元测试，未对“降级为复核的禁断表述对拒答检测的抑制作用”进行负面断言，导致拒答检测器完全失守。

---

### 盲区 3：同句相同 marker 先双重否定后转折肯定的多次出现（CRITICAL）

- **测试现状**：
  测试 `multipleOccurrencesIndependentlyAssessedWithAuditDetails` 仅测试了“先例外禁止、后转折肯定”的两个 occurrence（Case G1）。
- **覆盖盲区**：
  **未测试“先双重否定、后转折肯定”的两个 occurrence。**
- **漏报成因**：
  全局双重否定检测在进入 occurrence 循环之前直接 `return`，第二个本应判定为 `AFFIRMATIVE_VIOLATION` 的 occurrence 彻底被吞掉，测试未能捕获该行为。

---

### 盲区 4：复杂转折反差模式（RHETORICAL_OR_HYPOTHETICAL）的全局污染（HIGH）

- **测试现状**：
  测试 `repeatedMarkersAndComplexTransitionsHandledCorrectly`（Case F2）仅测试了 `"资料中并未明确，但实际上可以先组织培训再补签合同 [S1]。"`，验证其进入人工复核。
- **覆盖盲区**：
  **未测试前半句为非禁断命题的“理论上...实际上...”，后半句为独立禁断命题的情形。**
- **漏报成因**：
  代码中对 `sentence.contains("实际上")` 的判断直接作用于整个大句，缺少对“实际上”修饰对象的局部子句作用域界定。

---

### 盲区 5：后置否定谓语表达（MEDIUM）

- **测试现状**：
  所有否定测试用例（如 AQ06）均采用动词前置否定（如“不可以先组织培训”、“不得先培训”）。
- **覆盖盲区**：
  **未覆盖汉语中极其自然的后置否定句式（如“...是禁止的”、“...是不被允许的”）。**
- **漏报成因**：
  现有实现仅有 `DIRECT_NEGATION_PREFIX` 匹配 `localPrefix`，导致所有后置否定全部被误判为 `AFFIRMATIVE_VIOLATION`（假阳性拦截）。

---

### 盲区 6：版本限定词的大句跨分句覆盖（MEDIUM）

- **测试现状**：
  测试用例 `versionViolation` 仅测试了裸用 `"根据现行办法，一线城市住宿费标准为每晚四百元 [S1]。"`，缺少限定词。
- **覆盖盲区**：
  **未测试同句前半句陈述废止词（“原规定已废止”），后半句转折依然主张旧标准（“但目前依然是四百元”）的情形。**
- **漏报成因**：
  版本限定采用整句扫描 `legacyWords.stream().noneMatch(sentence::contains)`，未将限定词的作用域收敛在转折分句边界内。

---

### 盲区 7：阿拉伯数字编造与富文本格式处理（LOW）

- **测试现状**：
  数字测试均使用大写汉字（如“五十份”、“五百元”）；文本格式仅测试 Markdown 粗体与斜体。
- **覆盖盲区**：
  1. 未覆盖阿拉伯数字形式的编造（如“50份”、“500元”）；
  2. 未覆盖 HTML 标签（如 `<u>不可以</u>`）及删除线（`~~`）。

---

## 三、规则交互矩阵覆盖率统计

基于本轮建立的 20 项核心规则交互矩阵（详见 `rule-interaction-matrix.json`）：

- **已充分覆盖且行为正确（COVERED / NONE）**：9 项（**45%**）
- **已证实存在缺陷/漏报/误报（CONFIRMED / CRITICAL & HIGH & MEDIUM）**：7 项（**35%**）
- **静态分析存在风险/盲区（STATIC_RISK / LOW）**：4 项（**20%**）

### 结论

测试数量（28 个）与 CI 全绿**绝不能等同于**无漏报或语义判定完备。现有测试存在显著的“只测设计预期内的已知场景，未测多规则组合下的作用域泄漏”的结构性缺口。必须在下一轮修复中以失败用例驱动，补齐上述 7 项已证实缺陷的回归防护。
