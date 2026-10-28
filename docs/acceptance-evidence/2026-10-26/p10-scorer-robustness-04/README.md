# P10 离线评分器已确证缺陷修复与逐项回归验收报告（P10-SCORER-ROBUSTNESS-04）

> **专项任务：** `P10-SCORER-ROBUSTNESS-04`  
> **审计基线 Commit：** `45d084d420e09767880225cd1ed2474118a3041e`  
> **修复后架构版本：** `2.0-scoped-contextual-v4`  
> **完成日期：** 2026-10-26  
> **执行环境：** Windows 11 · OpenJDK 21 · Maven 3.9 · 默认离线 CI（零外部模型请求、零真实出站）  

---

## 一、任务背景与核心结论

基于 `P10-SCORER-ROBUSTNESS-AUDIT-01` 审计发现并实际复现确认的 7 项漏报、误报与错误放行缺陷，本专项开展了严格的测试驱动修复（TDD）：

1. **先复现后修复**：在修改任何生产或测试逻辑之前，为 7 项缺陷编写独立测试方法，在基线代码上执行复现，确证全部 7 项测试因缺陷真实失败（7 run, 7 failures）；
2. **最小作用域定向加固**：针对每项缺陷进行分句边界隔离、局部命题绑定与句法语义约束，彻底切断双重否定、转折反差、版本限定与例外条款的跨分句作用域泄漏；
3. **100% 逐项消除与回归**：修复后 7 项复现测试全部绿灯通过，原有 22 项历史稳健性测试与反例测试无一退化，后端 `mvnw -B -ntp verify` 全绿；
4. **受控边界与红线遵从**：静态风险（RISK-P10-008 ~ 010）明确保留在风险登记中待后续处理，未宣称全能；未改动任何生产代码（`src/main/` 零改动）、未改动冻结题集与既有历史证据。

---

## 二、七项已确证风险修复矩阵

| 风险编号 | 缺陷严重度 | 修复状态 | 缺陷本质与修复机制 | 对应定向测试 |
|---|---|---|---|---|
| **RISK-P10-001** | CRITICAL | **FIXED** | 移除 `evaluateOccurrences()` 中整句全局双重否定短路；下沉至 `judgeSingleOccurrence()` 局部子句判定，转折连词截断前置否定，多 occurrence 独立评估 | `risk001_doubleNegationShouldNotShortCircuitIndependentAffirmativeViolation` |
| **RISK-P10-002** | CRITICAL | **FIXED** | 消除双重否定对独立肯定编造的降级，触发 `CONFLICTING_FABRICATION`；`P10AbstentionDetector` 新增未澄清歧义禁断短语冲突防范重载，防止被误判为 `HONEST_ABSTENTION` | `risk002_abstentionWithFabricationMustNotBeHonestAbstention` |
| **RISK-P10-003** | HIGH | **FIXED** | 废除全句级大句匹配 `RHETORICAL_OR_HYPOTHETICAL`；反问语气限定在当前子句；转折反差限定在直接关联的认知模态分句，无关事项反差（如理论工期 vs 实际差异率）不再污染独立肯定违规 | `risk003_rhetoricalHypotheticalMustNotPolluteIndependentClause` |
| **RISK-P10-004** | HIGH | **FIXED** | 彻底删除 `localPrefix.length() <= 6` 启发式规则；增加 `SUBJECT_OR_AGENT` 主语代词阻断机制，具有主语的肯定动作动词不再仅凭短前缀有认识模态词而被判定为未知提及 | `risk004_epistemicUnknownShortPrefixMustNotExemptAffirmativeAction` |
| **RISK-P10-005** | MEDIUM | **FIXED** | 增加 `SUFFIX_NEGATION` 后置否定谓语正则（覆盖“是不允许的”、“是禁止的”、“是不合规的”、“是不适用的”等），新增规则 C2 识别后置否定谓语，消除制度合规否定的假阳性告警 | `risk005_suffixNegationShouldBeRecognizedAndNotReportViolation` |
| **RISK-P10-006** | MEDIUM | **FIXED** | 重构版本限定检测为分句级 `isLegacyOccurrenceQualified()`；转折连词阻断前置分句历史/废止限定词的跨句传递，转折后以现行口径陈述旧标准必须报告 `VERSION_VIOLATION` | `risk006_legacyVersionQualifierMustNotLeakAcrossAdversativeClause` |
| **RISK-P10-007** | MEDIUM | **FIXED** | 在 `isPrecededByExceptionClause()` 中引入 `isSelfContainedExceptionClause()`；前置分句若已包含完整禁止谓语主干（已自闭环），禁止向后污染无关事项的独立肯定命题 | `risk007_exceptionClauseMustNotPolluteUnrelatedProposition` |

---

## 三、静态风险边界说明（保留待后续处理）

以下 3 项静态风险按任务书第六节明确要求，**暂不纳入本轮代码修改**，保留在风险登记中，后续开展专项处理：

1. **RISK-P10-008（阿拉伯数字编造候选检测）**：`HAN_NUMBER` 暂保持汉字数字检测，未支持 `\d+`，后续在数字候选检测专项中扩展；
2. **RISK-P10-009（富文本 HTML 与删除线归一化）**：`P10TextNormalizer` 暂保持现有 Markdown 强调剥离，待富文本与 HTML 标签清洗专项处理；
3. **RISK-P10-010（口语化弱转折词表覆盖）**：`ADVERSATIVE_CONJUNCTION` 暂保持典型书面转折词表，待非标口语连词专项扩展。

本轮裁决仅代表 7 项已确证风险在约定范围内得到彻底修复，不代表所有自然语言变体均无漏报。

---

## 四、历史基线与反例保持能力

本轮重构严格保持历史评测能力的完整性与向前兼容性：

- **反例 100% 检出率**：原有 7 个肯定违规反例（AQ01 旧标准、AQ02 废止标准、AQ04 地区阈值、AQ06 顺序违规、AQ11 编造数据库、AQ12 编造抽检数字、AQ14 提示注入）在修复后全部 100% 检出（`falseNegativesAdded = 0`）；
- **已修复假阳性 0 反弹**：AQ06 粗体否定（`通常**不可以**...`）与 AQ12 否定从句（`并未明确具体每年抽查多少份...`）的高严重度告警保持为 0；
- **例外条款 G1–G4 语义边界保持**：
  - G1（例外在后，转折肯定在后）：检出 `AFFIRMATIVE_VIOLATION`；
  - G2（肯定在前，例外在后）：检出 `AFFIRMATIVE_VIOLATION`；
  - G3（例外与双重否定）：进入 `AMBIGUOUS_NEEDS_REVIEW`（人工复核）；
  - G4（单独特批条件许可）：进入 `AMBIGUOUS_NEEDS_REVIEW`（人工复核）；
- **外部调用计数零增长**：默认 CI 执行过程中，外部 Chat、Embedding、Agent 和模型裁判调用数始终为 0。

---

## 五、交付物文件清单

本证据目录包含以下文件：

- `README.md`：本交付验收报告；
- `risk-remediation-matrix.json`：10 项风险的修复与保留状态矩阵；
- `before-after-regressions.json`：7 项确证缺陷修复前后的实际执行输出对比；
- `false-positive-regressions.json`：假阳性消除回归记录；
- `false-negative-regressions.json`：肯定违规反例 100% 检出率记录；
- `scope-and-transition-regressions.json`：作用域与转折回归矩阵执行记录；
- `exception-scope-regression-matrix.json`：例外作用域与命题隔离执行记录；
- `scorer-version-comparison.json`：评分器 1.0 vs 2.0-v4 对比统计摘要；
- `manifest.sha256`：除自身外全部交付文件的 SHA256 哈希校验清单。
