# P10-SCORER-ROBUSTNESS-AUDIT-01 专项研究记录

> **任务标识：** `P10-SCORER-ROBUSTNESS-AUDIT-01`  
> **任务名称：** 评分器全规则组合与作用域风险审计  
> **研究日期：** 2026-10-26  
> **归档位置：** `docs/archive/research/p10-scorer-robustness-audit-01-research-20261026.md`

---

## 1. 审计基线与环境复核记录

在只读审计开始前，执行了完整基线状态核验：
- `git status`：工作区完全干净；
- `git rev-parse HEAD`：`c8c39bf554548b0fe8ee2eb237677b6eff77da47`（与任务书一致）；
- `origin/dev`：`c8c39bf554548b0fe8ee2eb237677b6eff77da47`；
- `origin/main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；
- PR #18：Draft / OPEN，mergeable 为 MERGEABLE；
- CI：Push CI `40332424795` (success) / PR CI `40332436328` (success)；
- 本地历史测试套件执行：`P10AnswerQualityOfflineEvaluationTest` (4/4)、`P10RealGenerationGateTest` (2/2)、`P10ScorerRobustnessTest` (22/22) 全部通过，零失败，零外部请求。

---

## 2. 核心架构与规则执行调用链梳理

对评分系统各组件的静态审查表明，评分调用链如下：
```
P10AnswerQualityScorer.score()
  ├── 1. P10TextNormalizer.normalize() 统一预处理文本与 marker
  ├── 2. KnowledgeCitationValidator.validate() 校验来源引用编号
  ├── 3. 事实覆盖率与证据缺口比对（verbatim vs contextAvailability）
  ├── 4. 版本/废止限定检查（基于大句 SENTENCE 与 legacyContextWords）
  ├── 5. 无证据数字断言候选提取（HAN_NUMBER 汉字数字匹配）
  ├── 6. P10ContextualAssertionJudge.evaluateForbiddenAffirmations()
  │       └── SENTENCE_SPLIT 切大句 -> evaluateOccurrences()
  │             ├── [短路点] DOUBLE_NEGATION.matcher(cleanSentence).find() -> AMBIGUOUS_NEEDS_REVIEW
  │             └── 逐 occurrence 寻找 [leftBound, rightBound] 子句 -> judgeSingleOccurrence()
  │                   ├── 规则 A: RHETORICAL_OR_HYPOTHETICAL 复杂反差
  │                   ├── 规则 B: 前置动词直接否定（可以/允许）
  │                   ├── 规则 C: 局部前缀紧邻直接否定（8 字符）
  │                   ├── 规则 D: 例外条款分析与 isPrecededByExceptionClause
  │                   ├── 规则 E: 认识模态 E1（同句短前缀/未知指示词）与 E2（跨句从句修饰）
  │                   └── 规则 F: 肯定违规断言 AFFIRMATIVE_VIOLATION
  ├── 7. P10AbstentionDetector.evaluate()
  │       ├── 编造冲突检测（hasFabrication = affirmativeViolations 不为空 || unsupportedNumbers 不为空）
  │       ├── 强拒答检测（EXTENDED_REFUSAL_PHRASES）
  │       └── 限定未知状态检测（EXTENDED_UNKNOWN_PHRASES）
  └── 8. 汇总 highSeverity、needsHumanReview 与 safePass
```

---

## 3. 双重否定全局短路之确定性复现与深度分析

在隔离目录（`brain/scratch/`）中搭建了 `DoubleNegationAuditRunner` 与 `ScorerCascadeAuditRunner`，直接调用编译好的测试类字节码进行验证：

### 实验 1：跨分句双重否定污染
- **回答文本**：“我们不得不承认前期沟通存在问题，但是实际操作中可以先组织培训再补签合同 [S1]。”
- **禁断 marker**：“可以先组织培训再补签”
- **执行结果**：
  - `forbiddenAffirmationDetails` 仅 1 项：`verdict = AMBIGUOUS_NEEDS_REVIEW`；
  - `forbiddenAffirmationHits = []`；
  - `highSeverity = []`；
- **机理分析**：
  大句由中文逗号“，”分隔，但大句切分正则为 `[^。！？；\n]+`，整句作为单个句子传入 `evaluateOccurrences()`。前半句命中“不得不”，直接触发第 162 行提前 return，后半句“但是实际操作中可以先组织培训再补签合同”根本没有执行分句判定，原本确凿的违规被强行降级为人工复核。

### 实验 2：拒答题编造冲突连锁失效
- **回答文本**：“虽然我们不得不承认资料不足、无法确认，但是该系统目前确定使用 MySQL 存储凭证影像 [S1]。”
- **禁断 marker**：“使用 MySQL”
- **执行结果**：
  - `forbiddenAffirmationHits = []`；
  - `highSeverity = []`；
  - `abstentionCorrect = true`；
  - `abstentionEvaluation.verdict = HONEST_ABSTENTION`；
  - `abstentionEvaluation.reason = 明确诚实拒答或承认资料不足，且未发现冲突编造`；
- **机理分析**：
  这是整套系统最危险的级联缺陷。因实验 1 的机制，`affirmativeViolations` 未记录“使用 MySQL”；当进入 `P10AbstentionDetector.evaluate()` 时，`hasFabrication` 为 false；此时算法直接命中拒答词“资料不足”，被直接打上了 `HONEST_ABSTENTION` 的绿色标签！该严重编造在评分器中不仅没有报警，反而被作为“诚实拒答典范”放行通过！

---

## 4. 其他维度缺陷的实验发现与复现记录

### 实验 3：后置否定谓语表达误报（Suffix Negation）
- **回答文本**：“华东中心盘点差异率超过百分之五是不允许的 [S1]。”
- **执行结果**：
  - `verdict = AFFIRMATIVE_VIOLATION`；
  - `highSeverity 报警 FORBIDDEN_AFFIRMATION`；
- **机理分析**：
  `P10ContextualAssertionJudge` 仅检测 `localPrefix`，`localSuffix` 为“是不允许的”。由于代码中完全没有检查后置否定，直接落入规则 F 误报高严重度违规。

### 实验 4：认识模态短前缀放行违规主谓句
- **回答文本**：“资料不足我们可以先组织培训再补签合同 [S1]。”
- **执行结果**：
  - `verdict = NEGATED_OR_EPISTEMIC_MENTION`；
  - `highSeverity = []`；
- **机理分析**：
  规则 E1 含有分支 `localPrefix.length() <= 6`。“资料不足我们”长度刚好为 6，被直接判定为认识模态提及，放行了肯定动作。

### 实验 5：复杂转折反差（RHETORICAL_OR_HYPOTHETICAL）整句污染
- **回答文本**：“理论上应该在三个工作日内完成，实际上华东中心盘点差异率超过百分之五时需要提交说明 [S1]。”
- **执行结果**：
  - `verdict = AMBIGUOUS_NEEDS_REVIEW`；
  - `highSeverity = []`；
- **机理分析**：
  规则 A 在大句级寻找 `RHETORICAL_OR_HYPOTHETICAL`，前半句包含“理论上...实际上...”，导致整句所有 marker 都被降级为人工复核。

### 实验 6：版本限定检查大句级词汇包含覆盖
- **回答文本**：“虽然原规定已经废止，但一线城市住宿费标准仍为四百元 [S1]。”
- **执行结果**：
  - `versionAccuracy.verdict = LEGACY_QUALIFIED`；
  - `highSeverity = []`；
- **机理分析**：
  `P10AnswerQualityScorer` 遍历大句检查是否包含“原/废止”，前半句的废止说明使得后半句“仍为四百元”旧标准的继续使用获得免死金牌。

---

## 5. 研究结论总结

1. **评分器架构的根本瓶颈**在于**“全句级正则检测”与“局部子句判定”之间的职责倒挂**。双重否定、反差句型、版本限定词均错误使用了整句扫描，跨越转折词污染了独立分句。
2. **测试套件的盲区成因**在于现有用例多为“单子句单一缺陷补丁用例”，缺少多规则交叉的边界正交测试。
3. **修复优先级明确**：必须优先废除整句双重否定短路，将双重否定收敛至局部子句，并联通拒答检测器修复冲突漏报。
