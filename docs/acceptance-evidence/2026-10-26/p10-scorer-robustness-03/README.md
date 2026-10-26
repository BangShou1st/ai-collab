# P10-SCORER-ROBUSTNESS-03：例外条款作用域隔离与漏报防护

> **任务标识：** `P10-SCORER-ROBUSTNESS-03`  
> **归档位置：** `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-03/README.md`  
> **基线状态：** 在 `P10-SCORER-ROBUSTNESS-02` 认识模态作用域收敛基础上推进。  
> **核心原则：** 修复评分器例外条款判定中的全句级作用域泄漏与肯定违规漏报缺陷；**不修改生产代码、不修改 System Prompt、不修改检索算法或预算、不修改冻结题集、不发起任何真实外部模型请求**。

---

## 1. 任务背景与核心结论

在上一轮 `P10-SCORER-ROBUSTNESS-02` 完成认识模态作用域修复后，独立代码审查发现 `P10ContextualAssertionJudge` 中的例外条款判定分支（D 分支）仍保留了全句级（sentence-level）的扫描逻辑：

```java
// 旧版缺陷代码：
if (EXCEPTION_MARKER.matcher(localClause).find() || EXCEPTION_MARKER.matcher(sentence).find()) {
    if (localClause.contains("不得") || localClause.contains("不能") || localClause.contains("不可以") || localClause.contains("禁止")
            || sentence.contains("不得") || sentence.contains("不能") || sentence.contains("不可以") || sentence.contains("禁止")) {
        return new Assessment(..., AssertionVerdict.NEGATED_OR_EPISTEMIC_MENTION, ...);
    } else {
        return new Assessment(..., AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW, ...);
    }
}
```

### 缺陷根本原因
1. **全句例外标记渗透**：`EXCEPTION_MARKER.matcher(sentence).find()` 盲目检查整句，只要整句任意分句存在“除非”“特批”等例外词，就会使整句的所有 marker 都被纳入例外条款分支；
2. **全句禁止词渗透**：`sentence.contains("不得") || ... || sentence.contains("不可以")` 盲目检查整句，只要整句任意分句存在禁止词，就会把当前 marker 判定为 `NEGATED_OR_EPISTEMIC_MENTION`；
3. **转折分句隔离失效**：当句子前半段陈述例外禁止（“除非获得特批，否则不可以……”），后半段由转折词引导肯定违规（“但该回答又肯定声称可以……”）时，后半段的违规断言被前半段的例外条款与禁止词彻底抑制，导致**高严重度违规严重漏报**；
4. **逆向覆盖漏洞**：若前半句先肯定违规，后半句再出现例外禁止条款，后半句的禁止词和例外词会逆向覆盖前半句，同样造成漏报。

### 验收量化结论

| 评测维度 | 验收指标 | 实际达成 | 说明依据 |
| --- | --- | --- | --- |
| **缺陷复现成功率** | 真实反例确定性失败 | **100% 确认（2/2 失败）** | 修复前针对两个反例运行测试，真实抛出 `AssertionError: Expecting any elements of [] to match`，漏报确凿 |
| **例外条款作用域回归** | 覆盖单句/转折/逆向/复核 | **100% 通过（4/4 场景）** | G1-G4 全部通过，转折后独立肯定违规与前置肯定违规 100% 检出 |
| **上轮作用域回归** | 案例 A-F2 全部保持通过 | **100% 通过（7/7 场景）** | 认识模态、转折分句、同句重复 marker 零退化 |
| **既有肯定违规反例检出率** | 7/7 门禁保持不退化 | **100%（7/7 检出）** | 版本违规、错误地区阈值、肯定违规补签、编造数据库等全部检出 |
| **已修复假阳性回归状态** | AQ06 / AQ12 真实回答 | **稳定为 0（无机械假阳性）** | 粗体否定与合法未知状态持续保持 0 高严重度 |
| **外部真实模型请求数** | 离线测试外部调用 | **0 次** | 纯确定性离线回归，默认 CI 执行 |
| **受控生产与配置代码变更** | 生产源码 / Prompt / 题集 | **0 变更** | 严格限制在 `test` 作用域 |

---

## 2. 缺陷复现（真实失败回执）

在未修改 `P10ContextualAssertionJudge` 前，针对 AQ06 反例编写新增测试，执行 `mvn test -Dtest=P10ScorerRobustnessTest`，成功获取真实失败回执：

```
[INFO] Running com.shitulelv.aicollab.retrieval.P10ScorerRobustnessTest
[ERROR] Tests run: 20, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.804 s <<< FAILURE! -- in com.shitulelv.aicollab.retrieval.P10ScorerRobustnessTest
[ERROR] com.shitulelv.aicollab.retrieval.P10ScorerRobustnessTest.exceptionFollowedByAffirmativeViolationMustBeDetected -- Time elapsed: 0.010 s <<< FAILURE!
java.lang.AssertionError: 
[例外条款后出现独立肯定违规，必须检出 FORBIDDEN_AFFIRMATION] 
Expecting any elements of:
  []
to match given predicate but none did.
	at com.shitulelv.aicollab.retrieval.P10ScorerRobustnessTest.exceptionFollowedByAffirmativeViolationMustBeDetected(P10ScorerRobustnessTest.java:450)

[ERROR] com.shitulelv.aicollab.retrieval.P10ScorerRobustnessTest.affirmativeBeforeExceptionClauseMustBeDetected -- Time elapsed: 0 s <<< FAILURE!
java.lang.AssertionError: 
[肯定违规在前，例外禁止条款在后，必须检出 FORBIDDEN_AFFIRMATION] 
Expecting any elements of:
  []
to match given predicate but none did.
	at com.shitulelv.aicollab.retrieval.P10ScorerRobustnessTest.affirmativeBeforeExceptionClauseMustBeDetected(P10ScorerRobustnessTest.java:463)

[INFO] Results:
[ERROR] Failures: 
[ERROR]   P10ScorerRobustnessTest.affirmativeBeforeExceptionClauseMustBeDetected:463 [肯定违规在前，例外禁止条款在后，必须检出 FORBIDDEN_AFFIRMATION] 
Expecting any elements of:
  []
to match given predicate but none did.
[ERROR]   P10ScorerRobustnessTest.exceptionFollowedByAffirmativeViolationMustBeDetected:450 [例外条款后出现独立肯定违规，必须检出 FORBIDDEN_AFFIRMATION] 
Expecting any elements of:
  []
to match given predicate but none did.
[INFO] 
[ERROR] Tests run: 20, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

---

## 3. 最小修复方案设计与实现

在 `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ContextualAssertionJudge.java` 中实施最小改动：

### 3.1 严格绑定局部子句与紧邻从句作用域
彻底废除全句级 `sentence.contains(...)` 和 `EXCEPTION_MARKER.matcher(sentence).find()`，定义合法的例外条款修饰范围：
1. **同分句包含**：当前局部子句自身包含例外引导词（`EXCEPTION_MARKER.matcher(localClause).find()`）；
2. **前置从句修饰（无转折阻断）**：由 `isPrecededByExceptionClause(sentence, leftBound, isAdversative)` 判定：
   - 当前分句必须**非转折连词引导**（转折词直接切断前置修饰传递）；
   - 紧邻的前置分句（或经短状语修饰的前置从句）包含例外引导词；
   - 绝不跨越已闭合的完整主干从句产生长程污染。

### 3.2 否定词限定在当前局部子句
禁止性否定词只在当前分句 `localClause` 及紧邻前缀 `localPrefix` 中检测：
```java
boolean hasLocalNegation = localClause.contains("不得")
        || localClause.contains("不能")
        || localClause.contains("不可以")
        || localClause.contains("禁止")
        || DIRECT_NEGATION_PREFIX.matcher(localPrefix).find();
```

### 3.3 语义判定与人工复核分流
- 若处于例外条款作用域且包含局部否定：判定为 `NEGATED_OR_EPISTEMIC_MENTION`（陈述制度例外条款与禁止性主干）；
- 若处于例外条款作用域但不含局部否定（如“除非获得特批，才可以……”）：判定为 `AMBIGUOUS_NEEDS_REVIEW`（特批条件许可，转人工复核是否合规，绝不机械放行）；
- 若不处于例外条款作用域：顺利进入后续判定（若分句独立做出肯定陈述，判定为 `AFFIRMATIVE_VIOLATION`，彻底消除漏报）。

---

## 4. 必须增加的回归矩阵与评测结果

| 案例编号 | 场景分类 | 测试输入 | 预期结果 | 实际结果 | 判定与理由 |
|---|---|---|---|---|---|
| **G1** | 例外后出现独立肯定违规 | “除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同，但该回答又肯定声称可以先组织培训再补签合同 [S1]。” | 检出 `FORBIDDEN_AFFIRMATION` | **通过** | Occ 1 判为否定，Occ 2 判为肯定违规，高严重度告警生成 |
| **G2** | 肯定违规在前，例外在后 | “该回答肯定声称可以先组织培训再补签合同，除非获得分管副总裁的书面特批，否则不可以先组织培训再补签合同 [S1]。” | 检出 `FORBIDDEN_AFFIRMATION` | **通过** | Occ 1 判为肯定违规，Occ 2 判为否定，不被后半句覆盖 |
| **G3** | 例外条件与双重否定组合 | “除非获得分管副总裁的书面特批，否则并非不可以先组织培训再补签合同 [S1]。” | 进入人工复核 | **通过** | 识别双重否定结构，安全转入 `AMBIGUOUS_NEEDS_REVIEW` |
| **G4** | 单独特批条件许可 | “除非得到分管副总裁的书面特批，才可以先组织培训再补签合同 [S1]。” | 进入人工复核 | **通过** | 识别特批条件许可，安全转入 `AMBIGUOUS_NEEDS_REVIEW` |
| **A** | 未知在前，肯定在后 | “资料中并未明确合同抽查比例，但制度要求每年抽查所有合同。” | 检出 `FORBIDDEN_AFFIRMATION` | **通过** | 转折后肯定断言被检出 |
| **B** | 肯定在前，未知在后 | “制度要求每年抽查所有合同，但资料中并未明确抽查比例。” | 检出 `FORBIDDEN_AFFIRMATION` | **通过** | 前置肯定断言未被后置未知覆盖 |
| **C** | 真正认识模态 | “资料中并未明确具体每年抽查多少份合同。” | 零高严重度告警 | **通过** | 精准识别未知修饰 |
| **D** | AQ12 完整真实回答 | 详见测试源码 | 零高严重度告警 | **通过** | 识别 QUALIFIED_UNKNOWN，零假阳性 |
| **E** | AQ06 完整真实回答 | 详见测试源码 | 零高严重度告警 | **通过** | 粗体包裹否定词正确识别，零假阳性 |
| **F1** | 同句重复 marker | “资料中并未明确是否每年抽查，但制度要求每年抽查所有合同。” | 检出 `FORBIDDEN_AFFIRMATION` | **通过** | 独立评估第二次 occurrence 并检出 |
| **F2** | 复杂转折反差 | “资料中并未明确，但实际上可以先组织培训再补签合同 [S1]。” | 进入人工复核或检出 | **通过** | 安全转入人工复核，未直接放行 |
| **7/7** | 既有肯定违规反例 | AQ01/AQ02/AQ04/AQ06/AQ11/AQ12/AQ14 | 全部检出违规 | **通过** | 检出率 100%（7/7） |

---

## 5. 证据文件清单

| 文件 | 说明 |
| --- | --- |
| [exception-scope-regression-matrix.json](exception-scope-regression-matrix.json) | 本轮 G1-G4 例外条款作用域案例的机器判定明细、reason 与 ruleVersion |
| [scope-and-transition-regressions.json](scope-and-transition-regressions.json) | A-G 全部 11 个作用域与转折案例的完整结果快照 |
| [scorer-version-comparison.json](scorer-version-comparison.json) | 评分器版本对比统计（1.0-legacy vs 2.0-scoped-contextual-v3） |
| [false-negative-regressions.json](false-negative-regressions.json) | 7 个既有肯定违规反例 100% 检出率证明 |
| [false-positive-regressions.json](false-positive-regressions.json) | AQ06、AQ12 历史假阳性消除证明 |
| [manifest.sha256](manifest.sha256) | 本目录证据文件 SHA-256 完整性校验清单 |

---

## 6. 人工复核边界与声明

1. **复核边界保持**：
   - 单独特批条件许可（“除非……才可以……”）属于制度边界争议，机械规则无法替业务确认是否已满足特批流程，必须安全转入 `AMBIGUOUS_NEEDS_REVIEW`；
   - 例外条款与强双重否定组合（“除非……否则并非不可以……”）反转语义极强，必须安全转入 `AMBIGUOUS_NEEDS_REVIEW`；
2. **零机械放行原则**：任何无法由当前局部确定性规则 100% 证明为直接否定的复杂语境，绝不机械判为否定，一律转入人工复核或检出肯定违规；
3. **独立第三方复核声明**：本任务为评分器离线稳健性专项，所有判定由确定性测试套件验证，**独立第三方人工复核未执行**，特此明确注明。

---

## 7. 最终裁决

- **缺陷真实复现**：未修改前真实复现 2/2 处反例漏报失败，并留存完整堆栈；
- **最小修复闭环**：通过局部子句绑定与紧邻从句修饰范围界定，彻底消除了全句级例外条款与禁止词作用域泄漏；
- **零退化与全覆盖**：11/11 作用域回归通过、7/7 既有反例 100% 检出、黄金回答 0 假阳性；
- **环境安全合规**：0 次外部模型请求、0 项生产代码/题集变更；
- **裁决结论**：`P10-SCORER-ROBUSTNESS-03` 达到交付标准。
