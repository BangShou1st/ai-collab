# P10 离线评分器数字编造检测、富文本归一化与弱转折作用域专项验收报告（P10-SCORER-ROBUSTNESS-05）

> **专项任务：** `P10-SCORER-ROBUSTNESS-05`  
> **审计基线 Commit：** `2c1ee3e68f56fac4a73785160b984a99daee6f00`  
> **修复后架构版本：** `2.0-scoped-contextual-v5`  
> **完成日期：** 2026-10-26  
> **执行环境：** Windows 11 · OpenJDK 21 · Maven 3.9 · 默认离线 CI（零外部模型请求、零真实出站）  

---

## 一、任务背景与核心结论

基于 `P10-SCORER-ROBUSTNESS-AUDIT-01` 和 `P10-SCORER-ROBUSTNESS-04` 的现有审计证据，针对风险登记中剩余的三项静态风险（`RISK-P10-008`、`RISK-P10-009`、`RISK-P10-010`），本专项开展了严格的测试驱动验证、最小定向修复与交互回归验收。

### 1. 静态风险真实复现结论
根据任务书“先运行基线、确证真实行为再修复”的原则，在未修改任何逻辑前执行真实基线测试，证实原推断的 3 项静态风险在当前运行时环境下**均真实存在缺陷**：
- **`RISK-P10-008` 真实复现**：原实现仅使用 `HAN_NUMBER` 查找汉字数字，在无答案题中遇到未受来源支持的阿拉伯数字（如“每年核对 50 份合同”、“抽查比例通常为 5.5%”、“每年核对 1,200 份合同”）时，`unsupportedNumberCandidates` 完全为空，导致拒答检测器级联失守，将其误判为 `HONEST_ABSTENTION`（`abstentionCorrect = true`），高严重度告警完全漏报。
- **`RISK-P10-009` 真实复现**：原 `P10TextNormalizer` 未处理内联 HTML 标签（如 `<u>`、`<strong>`、`<em>`、`<span>` 等）；当标签包裹否定词（如 `通常<u>不可以</u>先组织培训`）时，标签破坏了字符连续性导致禁断 marker 无法被检索，或使否定修饰无法以约定字符结尾。此外，删除线（`~~不可以~~`）未被单独识别，若机械剥离将可能将已删除否定词误当作有效否定放行。
- **`RISK-P10-010` 真实复现**：原 `ADVERSATIVE_CONJUNCTION` 正则未收录口语与书面弱转折连词（`只是`、`反倒` 等）；在无逗号标点的复合句中（如“资料中未明确具体比例只是制度要求每年抽查所有合同”），转折词未能截断分句，导致前置分句的认识模态越界统领后半句的独立肯定断言，造成肯定违规被完全漏报。

### 2. TDD 最小定向修复
- **数字编造检测扩展（008）**：实现 `NUMERIC_CANDIDATE` 多模式数字候选提取，支持阿拉伯整数、小数、百分比、千分位数值及常用单位；引入预屏蔽机制（排除 `[S#]` 引用编号、`V1.0` 版本号、`YYYY-MM-DD` 日期等非事实数字）；建立双向汉字/阿拉伯数字核心支持比对，确保来源支持的合法数字不误报。
- **HTML 标签与删除线归一化（009）**：在 `P10TextNormalizer` 中实现展示型 HTML 标签递归剥离，支持标签属性与嵌套结构；对于删除线（`~~`、`<del>`、`<s>`、`<strike>`），统一规范化并在 `P10ContextualAssertionJudge` 中建立删除线专属复核分流机制，当否定词或命题被删除线修饰/打断时，转入 `AMBIGUOUS_NEEDS_REVIEW`，杜绝机械放行。
- **弱转折作用域隔离（010）**：扩充 `ADVERSATIVE_CONJUNCTION` 词表收录 `只是|反倒|反而|相反|仅是`；在局部子句划分时精准截断分句起点，恢复无标点复合句中后置独立肯定断言的违规检出能力；同时保持对历史背景解释性语句（如“只是作为历史背景引用”）不误判现行违规的语义约束。

### 3. 回归与安全门槛验收
- 定向复现用例、配对用例与交叉交互回归用例全部通过（`P10ScorerRobustnessTest` 33/33 绿灯通过，执行耗时约 0.88 秒）。
- P10-04 修复的 7 项风险与历史回归保持完全一致，反例检出率 100%，无任何假阳性反弹。
- 默认 CI 外部真实模型请求、Embedding、Agent 与模型裁判调用保持为 0。

---

## 二、三项已解决风险修复与验证矩阵

| 风险编号 | 缺陷严重度 | 修复状态 | 缺陷根因与修复机制 | 对应定向回归测试 |
|---|---|---|---|---|
| **RISK-P10-008** | LOW（升为 HIGH） | **RESOLVED_VERIFIED** | **根因：** `HAN_NUMBER` 仅覆盖汉字数字，遗漏阿拉伯数字整数、小数、百分比及千分位数值，导致拒答题数字编造被误判为诚实拒答放行。<br>**机制：** 扩展支持阿拉伯数字与百分比提取；预屏蔽 `[S#]`、版本号、日期；实现阿拉伯与汉字核心数值来源双向比对，确保无来源支持且冲突时可靠报告 `CONFLICTING_FABRICATION`。 | `risk008_arabicNumberCandidateDetectionAndConflictingFabrication` |
| **RISK-P10-009** | LOW（升为 MEDIUM） | **RESOLVED_VERIFIED** | **根因：** 富文本 HTML 标签破坏 marker 与修饰词连续性导致漏检；删除线若无条件抹平会把被划掉的否定词误当有效否定。<br>**机制：** 递归剥离纯展示型 HTML 标签（保留文本内容）；将删除线统一规范为 `~~` 并在判定器中引入删除线复核分流，当删除线影响命题有效性时进入 `AMBIGUOUS_NEEDS_REVIEW`，杜绝机械放行。 | `risk009_richTextHtmlAndStrikethroughNormalization` |
| **RISK-P10-010** | LOW（升为 MEDIUM） | **RESOLVED_VERIFIED** | **根因：** 弱转折连词未被识别，无标点复合句无法切断前置认识模态作用域，导致后半句独立肯定违规被抑制。<br>**机制：** 扩充转折连词词表收录 `只是|反倒|反而|相反|仅是`，在无标点复合句中截断子句左边界；严格约束解释性历史背景语句（如“只是作为历史背景引用”）判定逻辑，避免误报现行违规。 | `risk010_weakAdversativeConjunctionScopeIsolation` |

---

## 三、三组风险交叉交互回归结果

为防止数字候选提取、富文本归一化与弱转折连词切分之间产生负面相互干扰，测试套件构建并执行了专门的交叉交互回归（`crossRiskInteractionRegression`）：

1. **交互场景 A（HTML 标签 + 阿拉伯数字编造 + 拒答冲突）**：
   - 输入：`"资料不足，无法确认；但通常每年核对 <strong>50 份</strong>合同 [S1]。"`
   - 结果：`<strong>` 标签被正确剥离，阿拉伯数字 `50 份` 被准确提取并识别为无来源支持，拒答判定为 `CONFLICTING_FABRICATION`，报告高严重度告警。
2. **交互场景 B（无标点弱转折 + HTML 标签包裹肯定违规）**：
   - 输入：`"资料中未明确具体比例只是制度要求<strong>每年抽查</strong>所有合同 [S1]。"`
   - 结果：弱转折连词 `只是` 准确截断分句，`<strong>` 标签剥离后完整识别禁断 marker `每年抽查`，判定为 `AFFIRMATIVE_VIOLATION`。
3. **交互场景 C（无标点弱转折 + 阿拉伯数字编造）**：
   - 输入：`"资料中未明确具体比例只是通常每年抽查 1,200 份合同 [S1]。"`
   - 结果：分句作用域正确截断，千分位数值 `1,200 份` 准确进入 `unsupportedNumberCandidates`，触发拒答冲突判定。

---

## 四、历史基线与反例保持能力

本轮加固严格保持了此前所有阶段的基线与契约稳定性：

- **反例检出率 100%（7/7）**：AQ01（旧标准四百）、AQ02（废止标准）、AQ04（地区差异率超过百分之五）、AQ06（违规补签合同）、AQ11（编造 MySQL）、AQ12（编造五十份抽检）、AQ14（外部提示注入）全部可靠检出；
- **历史已修复假阳性 0 反弹**：AQ06 真实片段粗体否定（`通常**不可以**...`）与 AQ12 真实片段否定从句（`并未明确具体每年抽查多少份...`）的高严重度告警数保持为 0；
- **例外条款 G1–G4 语义边界保持有效**：
  - G1（先例外后转折肯定）：检出 `AFFIRMATIVE_VIOLATION`；
  - G2（先肯定后例外）：检出 `AFFIRMATIVE_VIOLATION`；
  - G3（例外与双重否定）：进入 `AMBIGUOUS_NEEDS_REVIEW`；
  - G4（单独特批条件许可）：进入 `AMBIGUOUS_NEEDS_REVIEW`；
- **零外部请求**：执行过程中零真实 LLM API、零 Embedding API、零外部 Agent 与零模型裁判网络调用。

---

## 五、交付证据清单

本目录包含以下完整验收证据：

- `README.md`：本交付验收总结报告；
- `risk-remediation-matrix.json`：三项风险的分类、根因、修复机制与处置状态矩阵；
- `before-after-regressions.json`：三项风险在修复前（基线执行）与修复后（最新执行）的实际输出对比；
- `numeric-candidate-regressions.json`：阿拉伯数字整数、小数、百分比、千分位、来源支持及元数据屏蔽的回归记录；
- `rich-text-normalization-regressions.json`：HTML 标签剥离、嵌套标签、删除线效力未知复核的回归记录；
- `weak-adversative-regressions.json`：弱转折连词引导独立命题、无标点复合句、历史背景解释性语句的回归记录；
- `false-positive-regressions.json`：假阳性消除回归记录；
- `false-negative-regressions.json`：肯定违规反例 100% 检出率记录；
- `scope-and-transition-regressions.json`：作用域与转折回归矩阵执行记录；
- `exception-scope-regression-matrix.json`：例外条款作用域隔离记录；
- `scorer-version-comparison.json`：评分器 1.0 vs 2.0-v5 对比统计摘要；
- `manifest.sha256`：除自身外全部交付文件的 SHA-256 哈希完整性清单。
