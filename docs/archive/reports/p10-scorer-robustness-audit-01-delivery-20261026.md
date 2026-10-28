# P10-SCORER-ROBUSTNESS-AUDIT-01 阶段交付报告

> **任务标识：** `P10-SCORER-ROBUSTNESS-AUDIT-01`  
> **任务名称：** 评分器全规则组合与作用域风险审计  
> **归档位置：** `docs/archive/reports/p10-scorer-robustness-audit-01-delivery-20261026.md`  
> **关联证据：** `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/`  
> **关联提交：** `c8c39bf554548b0fe8ee2eb237677b6eff77da47`（分支 `dev`，PR #18）

---

## 一、审计达成概览

本轮任务（`P10-SCORER-ROBUSTNESS-AUDIT-01`）严格遵循**只读审计原则**，对 AI-Collab 项目 P10 离线回答质量评分器进行了系统性、多维度的全规则组合与作用域风险审查。

核心达成包括：
1. **最高优先级风险深度确证（CRITICAL / CONFIRMED）**：
   - 彻底证实 `P10ContextualAssertionJudge.evaluateOccurrences` 中在逐 occurrence 之前执行的整句全局双重否定检测（`DOUBLE_NEGATION.matcher(cleanSentence).find()`）存在严重的作用域泄漏；
   - 确定性证实了跨分句肯定违规被降级、同句重复 marker 第二个违规被吞、跨 marker 污染，以及引发拒答检测器级联失守（将包含编造的回答误判为“诚实拒答通过”）的 4 类实证场景；
2. **构建完整规则交互组合矩阵（20 项）**：
   - 覆盖否定、认识模态、例外条款、转折连词、重复 marker、标点边界、格式归一化、拒答冲突、版本与地区、注入遵从、引用身份、规则汇总优先级等全部 12 个维度；
   - 评定 9 项充分覆盖（45%）、7 项已证实缺陷（35%）、4 项静态风险与缺口（20%）；
3. **建立风险登记表（10 项）**：
   - 记录 2 项 CRITICAL、2 项 HIGH、3 项 MEDIUM、3 项 LOW 风险，均附带代码定位、复现输入、预期与实际判定及最小修复建议；
4. **测试覆盖盲区与质量边界澄清**：
   - 详细分析现有 28 个测试用例全绿背后的覆盖盲区，指出测试用例局限于单子句特定补丁模式，缺乏多规则交叉对抗测试；
   - 明确指出“CI 全绿”无法推论“无自然语言场景漏报”；
5. **零违规交付保证**：
   - 生产代码：**0 修改**；
   - 冻结题集与语料：**0 修改**；
   - 真实外部模型请求：**严格 0 次**；
   - 历史证据与哈希清单：**100% 保持未改动**；
   - 工作区纯净，不留多余日志与临时文件。

---

## 二、任务书核心问题答复

### 1. 三轮专项后，评分器有哪些规则与作用域边界？
- **文本归一化边界**：支持 Markdown 强调（***, **, *, ___, __, _）与行内反引号剥离，Unicode NFKC 统一，水平空白压缩；但未覆盖删除线与 HTML 标签。
- **大句边界**：基于 `[^。！？；\n]+` 切分大句；逗号、顿号、冒号不切分大句。
- **子句边界**：基于逗号、顿号、冒号、分号及转折连词（`ADVERSATIVE_CONJUNCTION`）寻找 `[leftBound, rightBound]`。
- **作用域隔离边界**：
  - 转折连词向左切断前置认识模态与例外修饰，向右切断后续修饰；
  - 例外条款通过 `isPrecededByExceptionClause` 向紧邻后置从句单向传递（未转折前提下）；
  - **严重缺陷**：双重否定检测未遵循局部子句边界，依然停留在整个大句切分点之前进行全局短路拦截。

### 2. 是否发现新的确定性漏报、误报或错误人工复核？
- **已证实确定性漏报（假阴性）**：
  1. 前半句双重否定，后半句转折肯定违规 -> 被降级为人工复核，漏报违规（RISK-P10-001）；
  2. 拒答题双重否定掩盖编造断言 -> 被误判为 `HONEST_ABSTENTION` 并放行，拒答检测失守（RISK-P10-002）；
  3. 认识模态短前缀（`localPrefix.length <= 6`）匹配到主谓短语 -> 肯定动作被误判为认识模态提及并放行（RISK-P10-004）；
  4. 同句前半句有废止背景词，后半句主张旧标准 -> 版本检查整句放行为 `LEGACY_QUALIFIED`（RISK-P10-006）。
- **已证实确定性误报（假阳性）**：
  - 后置否定修饰语（如“...是不允许的”）未被纳入判定器，被规则 F 误判为 `AFFIRMATIVE_VIOLATION`（RISK-P10-005）。
- **已证实错误人工复核（违规降级）**：
  1. 同句同一 marker 出现两次（先双重否定后转折肯定），第二个肯定违规 occurrence 丢失，整句仅输出复核（RISK-P10-001）；
  2. 复杂转折反差模式（RHETORICAL_OR_HYPOTHETICAL）大句级匹配，将后半句毫无争议的肯定违规降级为复核（RISK-P10-003）；
  3. 跨从句例外作用域（isPrecededByExceptionClause）受无关实体的前置特批污染，导致后续肯定违规降级为复核（RISK-P10-007）。

### 3. 全局双重否定检测是否会影响其他独立分句？
**会，且影响极其严重。**
由于 `evaluateOccurrences` 在最外层对大句调用 `DOUBLE_NEGATION.matcher(cleanSentence).find()`：
- 任何由逗号连接的分句中只要出现一个双重否定词（哪怕是“我们不得不承认今天天气不好”），整个大句内的所有分句、所有 marker、所有 occurrence 均被一刀切强制转入 `AMBIGUOUS_NEEDS_REVIEW` 并直接 return；
- 独立分句的肯定违规无法被评估；
- 同句后续 occurrence 无法被评估；
- 连锁导致拒答检测器的编造冲突检测直接失效。

### 4. 例外、认识模态和转折规则的组合是否有已证实缺陷？
**有。**
- 认识模态 + 短主语主谓句（如“资料不足我们可以先组织培训再补签合同”）：因规则 E1 的 `length <= 6` 启发式，被放行为认识模态提及（RISK-P10-004）；
- 转折词 + 复杂反差模式（如“理论上...实际上...”）：因规则 A 使用大句级全局正则，导致后半句独立违规被降级为人工复核（RISK-P10-003）；
- 例外条款 + 无关从句（如“除非获得特批否则不得推迟上线，可以先组织培训再补签合同”）：前置从句特批污染后半句独立违规，导致违规降级为人工复核（RISK-P10-007）。

### 5. 哪些风险已经有测试覆盖，哪些只是静态风险？
- **已覆盖（COVERED）**：9 项，见 `rule-interaction-matrix.json` 中 MAT-12 ~ MAT-20（单子句双重否定、单子句例外与特批许可、AQ06 粗体否定、AQ12 认识模态、汉字数字编造冲突、引用与注入防御等）；
- **已证实缺陷（CONFIRMED）**：7 项（RISK-P10-001 至 RISK-P10-007），均已在隔离实验中以真实字节码复现；
- **静态风险（STATIC_RISK）**：3 项（RISK-P10-008 阿拉伯数字支持缺失、RISK-P10-009 富文本标签未剥离、RISK-P10-010 口语化弱转折连词未收录）。

### 6. 当前证据能支持多强的质量结论？有哪些不能证明？
- **能证明的结论**：
  1. 当前评分器对已在 `P10ScorerRobustnessTest` 和题集中显式声明的特定典型场景（如 AQ06 粗体否定、AQ12 间接拒答、纯单子句特批例外）有稳定的防回归能力；
  2. 评分器对非法引用身份、注入攻击、汉字数字编造具备确定性的拦截能力；
  3. 现存评分器架构在面对多从句组合、双重否定嵌套及后置谓语时，存在确定性的漏报与误报缺陷。
- **不能证明的事项**：
  1. 不能证明“评分器在所有自然语言场景下漏报率为 0”；
  2. 不能证明“CI 全绿代表评分规则在复杂句式下鲁棒”；
  3. 不能证明“真实大模型生成的复杂长回答不会命中上述降级或漏报路径”。

### 7. 是否需要下一轮代码修复？若需要，最小修复范围是什么？
**强烈需要下一轮针对性修复（建议立项 `P10-SCORER-ROBUSTNESS-04`）。**
**最小修复范围：**
1. **重构双重否定作用域（最高优先级）**：
   - 废除 `evaluateOccurrences()` 中的整句级短路；
   - 下沉至 `judgeSingleOccurrence()`，限定在 `localClause` 范围内，并受转折词阻断；
   - 修复拒答检测器与双重否定的级联冲突；
2. **收紧认识模态 E1 规则**：
   - 移除宽泛的 `localPrefix.length() <= 6`，防止带主语的肯定违规动作被错误放行；
3. **补充后置否定修饰语识别**：
   - 检测 `localSuffix` 中的“是不允许的”、“是禁止的”等常见后置谓语，消除假阳性拦截。

### 8. 是否存在生产代码、冻结题集或外部调用变更？
- **生产代码**：0 修改；
- **测试代码与冻结题集**：0 修改；
- **外部模型请求与网络调用**：0 次；
- 本轮严格恪守只读审计边界。

### 9. 最终 SHA、工作区、CI、PR #18 和证据完整性状态是什么？
- 本地与远端 HEAD SHA：`c8c39bf554548b0fe8ee2eb237677b6eff77da47`；
- 远端 main SHA：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；
- 工作区状态：`working tree clean`（未跟踪文件严格隔离于指定交付与归档目录）；
- PR #18：Draft / OPEN / MERGEABLE；
- 证据目录：`docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/`；
- 证据散列：`manifest.sha256` 完整校验。

---

## 三、交付物索引清单

| 资产类型 | 存放路径 |
|---|---|
| **证据总览报告** | `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/README.md` |
| **规则交互矩阵** | `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/rule-interaction-matrix.json` |
| **风险登记表** | `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/risk-register.json` |
| **测试覆盖缺口分析** | `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/coverage-gaps.md` |
| **证据哈希清单** | `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/manifest.sha256` |
| **任务书归档** | `docs/archive/prompts/p10-scorer-robustness-audit-01-task-20261026.md` |
| **研究记录归档** | `docs/archive/research/p10-scorer-robustness-audit-01-research-20261026.md` |
| **交付报告归档** | `docs/archive/reports/p10-scorer-robustness-audit-01-delivery-20261026.md` |
