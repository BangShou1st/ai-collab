# P10-SCORER-ROBUSTNESS-02：认识模态作用域与转折分句漏报修复

> **任务标识：** `P10-SCORER-ROBUSTNESS-02`  
> **归档位置：** `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-02/README.md`  
> **基线状态：** 在 `P10-SCORER-ROBUSTNESS-01` 评分器 2.0 架构升级基础上推进。  
> **核心原则：** 修复评分器认识模态作用域过宽与转折分句漏报缺陷；**不修改生产代码、不修改 System Prompt、不修改检索算法或预算、不修改冻结题集、不发起任何真实外部模型请求**。

---

## 1. 任务背景与核心结论

上一轮评分器稳健性改造（`P10-SCORER-ROBUSTNESS-01`）引入了上下文肯定性判定器 `P10ContextualAssertionJudge`，成功消除了 AQ06 粗体否定与 AQ12 否定从句的机械假阳性。但在深入代码审查中发现，原有实现存在**认识模态判断作用域过宽**与**转折分句隔离不充分**的潜在缺陷：

1. **整句无条件抑制漏洞**：原 `judgeSentence()` 包含 `cleanSentence.contains("并未明确") || cleanSentence.contains("尚未确定") || cleanSentence.contains("资料不足")`，只要大句任意位置出现此类短语，即直接将整句所有禁断 marker 判为 `NEGATED_OR_EPISTEMIC_MENTION`；
2. **转折分句作用域泄漏**：原实现未按逗号与转折连词（“但”、“但是”、“然而”、“实际上”等）切分语义作用域，导致转折前后的独立断言相互污染；
3. **同句重复 marker 遗漏**：原 `sentence.indexOf(marker)` 仅评估第一次出现，若同一句中前一次为未明确提及、后一次为肯定违规断言，后半句的肯定违规会被彻底漏报。

### 验收量化结论

| 评测维度 | 验收指标 | 实际达成 | 说明依据 |
| --- | --- | --- | --- |
| **缺陷复现成功率** | 暴露旧版漏报缺陷 | **100% 确认（3/3 失败）** | 场景 A、B、F1 在旧版实现下均产生假阴性漏报，断言失败确凿 |
| **新增作用域/转折用例通过率** | 覆盖 A/B/C/D/E/F 场景 | **100% 通过（7/7 用例）** | 转折分句违规全部检出，认识模态与复杂转折精准分流 |
| **既有肯定违规反例检出率** | 7/7 门禁保持不退化 | **100%（7/7 检出）** | 版本违规、错误地区阈值、肯定违规补签、编造数据库等全部检出 |
| **已修复假阳性回归状态** | AQ06 / AQ12 真实回答 | **稳定为 0（无机械假阳性）** | 粗体否定与合法未知状态持续保持 0 高严重度 |
| **模范回答（Golden）回归** | 14 题受控模范回答 | **0 误报（highSeverity 为空）** | 离线回答质量评测全部通过 |
| **外部真实模型请求数** | 离线测试外部调用 | **0 次** | 纯确定性离线回归，默认 CI 执行 |
| **受控生产与配置代码变更** | 生产源码 / Prompt / 题集 | **0 变更** | 严格限制在 `test` 作用域 |

---

## 2. 缺陷复现（先复现，再修复）

在未修改评分器前，针对新增的回归测试矩阵执行 `mvn test -Dtest=P10ScorerRobustnessTest`，成功复现了 3 处确定性漏报失败：

```
[ERROR] Failures: 
[ERROR]   P10ScorerRobustnessTest.unknownBeforeAffirmativeMustBeDetected:359 [后半句明确肯定违规断言，必须检出 FORBIDDEN_AFFIRMATION] 
Expecting any elements of:
  []
to match given predicate but none did.
[ERROR]   P10ScorerRobustnessTest.affirmativeBeforeUnknownMustBeDetected:372 [前半句肯定违规必须检出，不能被后半句认识模态覆盖] 
Expecting any elements of:
  []
to match given predicate but none did.
[ERROR]   P10ScorerRobustnessTest.repeatedMarkersAndComplexTransitionsHandledCorrectly:424 [同一句重复 marker 中后半句肯定违规必须检出] 
Expecting any elements of:
  []
to match given predicate but none did.
[INFO] Tests run: 18, Failures: 3, Errors: 0, Skipped: 0
```

### 复现机理分析

1. **案例 A（未知在前，肯定在后）**：  
   输入：“资料中并未明确合同抽查比例，但制度要求每年抽查所有合同。”  
   旧实现因前半句包含“并未明确”，直接执行了 `cleanSentence.contains("并未明确")`，将后半句“每年抽查”误判为 `NEGATED_OR_EPISTEMIC_MENTION`，`highSeverity` 为空，发生漏报。
2. **案例 B（肯定在前，未知在后）**：  
   输入：“制度要求每年抽查所有合同，但资料中并未明确抽查比例。”  
   旧实现因后半句包含“并未明确”，逆向覆盖了前半句的断言，发生漏报。
3. **案例 F1（同一句重复 marker）**：  
   输入：“资料中并未明确是否每年抽查，但制度要求每年抽查所有合同。”  
   旧实现只索引了第一次出现的“每年抽查”，且整句被判定为未知模态，后半句确凿的违规断言被彻底吞掉。

---

## 3. 修复方案设计与实现

在 `P10ContextualAssertionJudge` 中落地了 **分句边界与局部作用域判定机制**：

### 3.1 分句边界识别与转折隔离
- 引入子句标点（`，,、:：;；`）与转折连词正则（`但(?:是)?|然而|不过|可是|却|实际上|其实|事实是|实质上|反之`）；
- 从 marker 的出现位置向左、向右动态收敛寻找所属的局部子句（`localClause`），将转折词作为硬性边界切分出独立的命题主张；
- 彻底删除了原代码中 `cleanSentence.contains("并未明确")` 的全局宽松放行逻辑。

### 3.2 支持多出现位置独立判定
- 新增 `evaluateOccurrences(sentence, marker)`，循环扫描句中 marker 的全部出现索引；
- 每一个 occurrence 分别绑定其局部前缀（`localPrefix`）与局部后缀（`localSuffix`）；
- 最终判定遵循 **违规断言具有最高优先级** 原则：只要任意一次出现属于确凿违规（`AFFIRMATIVE_VIOLATION`），即判定该 marker 构成违规，杜绝漏报。

### 3.3 认识模态判断收紧
只有满足以下严格条件之一时，才认定命题处于认识模态（`NEGATED_OR_EPISTEMIC_MENTION`）：
1. **同分句直接修饰**：在同一个 `localClause` 内部，`localPrefix` 包含 `EPISTEMIC_UNKNOWN`，且带有 `UNKNOWN_INDICATOR`（“具体”、“是否”、“多少”等）或认识模态词紧邻 marker（距离 <= 6 字符）；
2. **跨逗号从句修饰（无转折阻断）**：前置分句以认识模态词结尾，当前分句无转折词、无强制肯定词（“制度要求”、“必须”等），且包含 `UNKNOWN_INDICATOR`。

### 3.4 复杂转折反差安全转入人工复核
- 扩展 `RHETORICAL_OR_HYPOTHETICAL` 模式，匹配如“并未明确……但实际上可以……”等具有实践反差或口径冲突的表述；
- 对此类复杂转折反差，返回 `AMBIGUOUS_NEEDS_REVIEW` 并记录于 `needsHumanReview`，**绝不直接放行**。

---

## 4. 证据文件清单

| 文件 | 说明 |
| --- | --- |
| [scope-and-transition-regressions.json](scope-and-transition-regressions.json) | 案例 A-F（共 7 个子案例）在修复后的实际判定结果与详细原因 |
| [scorer-version-comparison.json](scorer-version-comparison.json) | 新旧评分器版本对比统计快照（1.0-legacy vs 2.0-scoped-contextual） |
| [false-negative-regressions.json](false-negative-regressions.json) | 7 个既有肯定违规反例 100% 检出率的防漏报回归证据 |
| [false-positive-regressions.json](false-positive-regressions.json) | AQ06、AQ12 历史假阳性持续稳定修复证据 |
| [manifest.sha256](manifest.sha256) | 本目录证据文件 SHA-256 完整性校验清单 |

---

## 5. 人工复核边界与声明

1. **复核边界**：对于双重否定（如“并非不可以”）以及具有复杂实践反差的转折（如“并未明确……但实际上可以……”），评分器输出 `AMBIGUOUS_NEEDS_REVIEW`，确保留有人工裁决入口；
2. **独立第三方复核声明**：本任务为评分器离线稳健性专项，所有判定由确定性测试套件验证，**独立第三方人工复核未执行**，特此明确注明。

---

## 6. 最终裁决

- **缺陷复现**：已通过真实测试失败证据证实原有作用域过宽漏报缺陷；
- **修复效果**：所有新增回归测试通过，既有 7/7 门禁保持 100% 检出，历史假阳性未退化；
- **环境安全**：0 次外部模型请求，0 项生产代码/题集变更；
- **裁决结论**：`P10-SCORER-ROBUSTNESS-02` 达到交付标准，准予合并。
