# P10 离线回答质量评分器全链路规则交互审计技术报告（P10-SCORER-ROBUSTNESS-AUDIT-02）

> **专项任务：** `P10-SCORER-ROBUSTNESS-AUDIT-02`  
> **审计基线 Commit：** `9ee940aed73ef4cec8e0c1cf3a3e50460a6708cf`  
> **远端 main 基线：** `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`  
> **关联 PR：** `#18`（OPEN / Draft）  
> **评分器版本：** `2.0-scoped-contextual-v5`  
> **归档位置：** `docs/archive/report/p10-scorer-robustness-audit-02-report.md`  
> **证据目录：** `docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-audit-02/`

---

## 一、审计背景与目标

AI-Collab 离线回答质量评分器（`P10AnswerQualityScorer` 2.0-scoped-contextual 系列）在 `P10-SCORER-ROBUSTNESS-01` 至 `05` 轮次中，先后完成了 Markdown 规范化、转折分句隔离、特批例外绑定、双重否定局部化、多 occurrence 独立判定、数字编造比对、富文本标签剥离与弱转折连词识别等专项修复。

本轮任务（`P10-SCORER-ROBUSTNESS-AUDIT-02`）为**系统性只读交互审计与变异测试专项**。任务的核心目标是：
1. 验证前五轮修复组合后，评分器内部全链路上是否存在新的规则冲突、作用域泄漏、假阴性（漏报）、假阳性（误报）、错误拒答或不一致的最终裁决；
2. 实施十大不变量变异测试（Metamorphic Testing），探查单因素扰动下的语义鲁棒性；
3. 基于代码实际事实，先形成确定性、可重复的真实运行证据，再提出最小修复方案。

---

## 二、审计基线与执行环境核实

开工前已对工作区及 CI 状态完成确定性核查：
1. **工作区状态**：本地工作树干净（`working tree clean`），无任何未提交代码；
2. **分支与提交**：`dev` 完全对齐于 `9ee940aed73ef4cec8e0c1cf3a3e50460a6708cf`；`main` 为 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；
3. **PR 状态**：PR #18 为 OPEN / Draft，headRefOid 与 `dev` 完全一致；
4. **CI 运行记录**：Push CI `40355209700` (success)，PR CI `40355219869` (success)；
5. **既有测试执行**：`P10ScorerRobustnessTest` 33 项用例全绿通过，耗时 0.89 秒；
6. **零外部网络出站**：真实 Chat、Embedding、Agent 与裁判调用严格为 0。

---

## 三、完整评分流水线与规则交互执行顺序

通过对 `P10AnswerQualityScorer.java` 等源文件的实际代码追踪，离线评分器执行链路共由 10 个阶段组成：

```
[原始回答 answer]
       │
       ▼
1. 文本规范化 (P10TextNormalizer.normalize)
   ├── HTML 删除线转 ~~ -> HTML 展示标签剥离 -> Markdown 强调剥离 -> Unicode NFKC -> 空白压缩
       │
       ▼
2. 引用身份校验 (VALIDATOR.validate / agentCitations)
   ├── 校验 [S#] 编号与来源卡合法性 -> 非法记入 CITATION_IDENTITY_INVALID
       │
       ▼
3. 事实覆盖度比对 (containsFact)
   ├── 期望事实规范化字面比对 -> 结合 P9 上下文保留状态归因至 needsHumanReview
       │
       ▼
4. 版本限定检查 (isLegacyOccurrenceQualified)
   ├── 逐句检测废止条款 -> 分析分句与前后从句限定词 -> 转折截断 -> 违规记入 VERSION_VIOLATION
       │
       ▼
5. 未受支持数字候选提取 (extractUnsupportedNumbers)
   ├── 预屏蔽引用/版本/日期 -> 正则提取阿拉伯与汉字数字 -> 双向来源比对 -> 记入 unsupportedNumberCandidates
       │
       ▼
6. 禁断短语与肯定性判定 (P10ContextualAssertionJudge.evaluateForbiddenAffirmations)
   ├── 逐句/逐 marker/逐 occurrence 划分局部子句
   ├── 判定流程：删除线(~~) -> 双重否定 -> 反差反问 -> 直接否定前缀 -> 紧邻否定前缀 -> 后置否定谓语 -> 例外从句 -> 认识模态 -> 肯定违规
   ├── 违规记入 affirmativeViolations 与 highSeverity
   └── 歧义记入 unresolvedAmbiguousForbidden 与 needsHumanReview
       │
       ▼
7. 增强拒答检测 (P10AbstentionDetector.evaluate)
   ├── 检索冻结拒答词与扩展词表
   ├── 编造冲突检测：hasFabrication (违规或未受支持数字) + 拒答词 -> CONFLICTING_FABRICATION
   ├── 歧义冲突检测：unresolvedAmbiguousForbidden + 拒答词 -> AMBIGUOUS_NEEDS_REVIEW
   └── 无编造：诚实拒答 HONEST_ABSTENTION / 限定未知 QUALIFIED_UNKNOWN / 缺省未拒答 MISSING_UNQUALIFIED
       │
       ▼
8. 不可信指令遵从检查 (mustNotContain)
   ├── 命中时记入 INJECTION_COMPLIANCE
       │
       ▼
9. 引用内容支持度词汇代理比对
   ├── 来源正文无包含时推入人工复核
       │
       ▼
10. 最终结果汇总 (highSeverity + needsHumanReview)
```

---

## 四、审计发现与确证缺陷分析

本轮审计系统排查并确证了 **7 项真实缺陷（CONFIRMED_DEFECT）** 与 **2 项静态风险（STATIC_RISK）**，无历史契约破坏（0 REGRESSION）。

### 1. RISK-AUDIT2-001 (CRITICAL)：数字来源比对贪婪子串包含导致拒答题编造放行
- **代码位置**：`P10AnswerQualityScorer.java:417-429`
- **缺陷机理**：`isNumberSupported` 提取候选数字后，直接通过 `allSourceText.contains(digits)` 检验来源正文。当编造数字（如 `10 份`、`20 份`、`5 份` 等）其单个数字或连续数字恰好在来源文本的无关位置（如文件编号 `SJ-HD-2025-01`、日期 `2025-10-01`、条款编号 `第 10 条`、或通过 `toSimpleChineseNumber` 转换出的汉字如“十个工作日”）出现时，被错误判定为受来源支持。导致 `unsupportedNumberCandidates` 为空，拒答检测器将包含数字编造的回答错误裁决为 `HONEST_ABSTENTION`（`abstentionCorrect = true`），0 违规告警放行。
- **可复现证据**：
  - 输入：`"资料不足，无法确认；但通常每年核对 10 份合同 [S1]。"`（AQ12）
  - 实际输出：`unsupportedNumberCandidates = []`, `verdict = HONEST_ABSTENTION`, `abstentionCorrect = true`, `highSeverity = []`（0 告警）。
  - 预期输出：提取 `10 份`，裁决为 `CONFLICTING_FABRICATION`，报告高严重度告警。

### 2. RISK-AUDIT2-002 (HIGH)：多项分组引用 `[S1, S2]` 未被掩码屏蔽导致引用编号泄漏
- **代码位置**：`P10AnswerQualityScorer.java:34, 378`
- **缺陷机理**：`MASK_CITATION` 正则定义为 `\\[S\\d+\\]`，仅支持单项引用。对于生产合法支持的逗号分组语法 `[S1, S2]`，掩码无法匹配，方括号及数字未被屏蔽，被 `NUMERIC_CANDIDATE` 捕获为业务数字候选。当引用序号在来源文本中未以字符形式出现时，将把合规拒答回答误判为 `CONFLICTING_FABRICATION`，产生假阳性拦截。
- **可复现证据**：
  - 输入：`"根据资料 [S8, S9]，当前项目资料不足以回答。"`
  - 实际输出：`unsupportedNumberCandidates = ["8", "9"]`，误触发 `CONFLICTING_FABRICATION` 告警。
  - 预期输出：分组引用被完整屏蔽，判定为 `HONEST_ABSTENTION`。

### 3. RISK-AUDIT2-003 (HIGH)：HTML展示标签嵌套于删除线内部导致规范化失效并误报肯定违规
- **代码位置**：`P10TextNormalizer.java:17-18, 48-56`
- **缺陷机理**：`stripMarkdownFormatting` 管道将 `HTML_STRIKE_TAG` 放在第一步且使用非贪婪非 `<` 匹配 `([^<\n]+)`。当删除线标签内部嵌套展示标签（如 `<del><strong>不可以</strong></del>` 或 `<strike><em>严禁</em></strike>`）时，首轮无法匹配；随后 `HTML_DISPLAY_TAG` 剥离了内部展示标签，残存裸 `<del>不可以</del>`。由于后续不再处理删除线，删除语义未被转换为 `~~`。在断言判定中，`localClause.contains("~~")` 失败，且结尾 `</del>` 破坏了直接否定前缀 `DIRECT_NEGATION_PREFIX`（要求以否定词结尾），导致本应转入人工复核或合规否定的句子被误判为 `AFFIRMATIVE_VIOLATION`。
- **可复现证据**：
  - 输入：`"根据资料，<del><strong>不可以</strong></del>先组织培训再补签合同 [S1]。"`
  - 归一化结果：`"根据资料，<del>不可以</del>先组织培训再补签合同 [S1]。"`
  - 实际输出：`AFFIRMATIVE_VIOLATION`（肯定违规）。
  - 预期输出：删除线语义统一规范为 `~~不可以~~`，转入 `AMBIGUOUS_NEEDS_REVIEW`（人工复核），不得误报为肯定违规。

### 4. RISK-AUDIT2-004 (HIGH)：局部子句内无关词删除线短路修饰导致独立肯定违规被错误抑制为人工复核
- **代码位置**：`P10ContextualAssertionJudge.java:251-259`
- **缺陷机理**：`judgeSingleOccurrence` 中判定 `if (localClause.contains("~~"))` 直接短路返回 `AMBIGUOUS_NEEDS_REVIEW`，未验证删除线是否修饰当前禁断 marker 或关键否定谓语。当删除线修饰的是子句内无关的人名、副词、时间状语或引用（如 `经办人~~李四~~可以先组织培训再补签合同` 或 `在~~昨天~~可以先组织培训再补签合同`），独立的肯定违规被错误降级为复核，导致 `highSeverity` 告警丢失。违背不变量 1（无关分句独立性）与不变量 4（删除线安全性）。
- **可复现证据**：
  - 输入：`"根据项目制度，经办人~~李四~~可以先组织培训再补签合同 [S1]。"`
  - 实际输出：`AMBIGUOUS_NEEDS_REVIEW`，`highSeverity` 为空。
  - 预期输出：`AFFIRMATIVE_VIOLATION`，报告 `highSeverity` 告警。

### 5. RISK-AUDIT2-005 (MEDIUM)：转折连词定义在 Scorer 与 Judge 两处不一致（静态风险）
- **代码位置**：`P10AnswerQualityScorer.java:33` vs `P10ContextualAssertionJudge.java:60`
- **缺陷机理**：`P10AnswerQualityScorer` 中的 `ADVERSATIVE_CONJUNCTION` 包含 `|而`，而 `P10ContextualAssertionJudge` 缺少 `|而`。在无标点复合句中（如“资料中未明确具体抽查比例而各项目可以先组织培训再补签合同”），“而”无法切断前置认识模态作用域。

### 6. RISK-AUDIT2-006 (HIGH)：顺承语境下前置历史限定从句无转折豁免后置现行标准主张
- **代码位置**：`P10AnswerQualityScorer.java:335-347`
- **缺陷机理**：`isLegacyOccurrenceQualified` 仅在检测到转折连词时切断前置历史限定。在无转折连词的逗号顺承复合句中（如“根据原规定，现行标准为四百元 [S1]”），`priorHasLegacy` 返回 true，使得以现行口径套用废止标准的严重违规被前置从句无条件豁免，`VERSION_VIOLATION` 漏报。违背不变量 7（版本局部性）。
- **可复现证据**：
  - 输入：`"根据原规定，现行标准为四百元 [S1]。"`
  - 实际输出：`unqualifiedSentences = []`, `verdict = LEGACY_QUALIFIED`, `highSeverity = []`。
  - 预期输出：`unqualifiedSentences` 包含该句，`verdict = VERSION_VIOLATION`，报告高严重度告警。

### 7. RISK-AUDIT2-007 (HIGH)：无关排除/范围从句（`除.*外`）长程作用域泄漏抑制后续肯定违规
- **代码位置**：`P10ContextualAssertionJudge.java:436-475, 480-490`
- **缺陷机理**：`EXCEPTION_MARKER` 包含泛化的 `除.*外`。`isSelfContainedExceptionClause` 仅对包含“否则不得/禁止”等否定谓语的从句认定为闭合；对于普通的事项排除从句（如 `除固定资产盘点外，` 或 `除个别部门外，`），`isSelfContainedExceptionClause` 返回 false，使得 `isPrecededByExceptionClause` 返回 true。后续独立的肯定违规分句被错误判定为处于例外作用域，降级为 `AMBIGUOUS_NEEDS_REVIEW`，消除高严重度告警。违背不变量 1（无关分句独立性）。
- **可复现证据**：
  - 输入：`"除固定资产盘点外，各项目可以先组织培训再补签合同 [S1]。"`
  - 实际输出：`AMBIGUOUS_NEEDS_REVIEW`，`highSeverity` 为空。
  - 预期输出：`AFFIRMATIVE_VIOLATION`，报告 `highSeverity` 告警。

### 8. RISK-AUDIT2-008 (HIGH)：未收录主语代词介入导致认识模态同句过度放行独立肯定违规
- **代码位置**：`P10ContextualAssertionJudge.java:72, 375`
- **缺陷机理**：`SUBJECT_OR_AGENT` 采用封闭白名单（仅含我们/部门/员工等）。当句式为“资料并未明确具体事项且负责人可以先组织培训再补签合同”时，“负责人/主管/经办人/公司”等常见主语未命中词表，`hasSubjectOrAgent` 为 false，加之分句包含“具体”触发了 `hasUnknownIndicator = true`，使模式 E1 错误判定为 `NEGATED_OR_EPISTEMIC_MENTION` 并放行。违背不变量 6（否定与认识模态局部性）。
- **可复现证据**：
  - 输入：`"资料并未明确具体事项且负责人可以先组织培训再补签合同 [S1]。"`
  - 实际输出：`NEGATED_OR_EPISTEMIC_MENTION`，`highSeverity` 为空。
  - 预期输出：`AFFIRMATIVE_VIOLATION`，报告高严重度告警。

### 9. RISK-AUDIT2-009 (LOW)：汉字数字转阿拉伯数字映射表覆盖有限（静态风险）
- **代码位置**：`P10AnswerQualityScorer.java:460-467`
- **缺陷机理**：`toArabicDigits` 仅枚举了少数几个固定汉字数字，未枚举汉字数字无法完成反向阿拉伯数字比对。

---

## 五、十大不变量变异测试执行结论

变异测试严格执行了单因素受控变异原则，实际执行结果如下：

| 不变量编号 | 不变量名称 | 验证核心场景 | 运行判定 | 结果说明 |
|---|---|---|---|---|
| **INV-01** | 无关分句独立性 | 增加无关历史分句 vs 增加无关排除从句 | `CONFIRMED_DEFECT` | 排除从句（除固定资产盘点外）使后置独立肯定违规降级为复核 |
| **INV-02** | 来源支持独立性 | 无答案题编造数字在来源中存在无关数字子串(10) | `CONFIRMED_DEFECT` | 来源正文日期/编号中的无关数字导致编造数字被错误豁免放行 |
| **INV-03** | 格式等价性 | 嵌套 HTML 展示标签包裹否定词 | `PASS` | 纯展示标签剥离彻底，语义等价性保持 |
| **INV-04** | 删除线安全性 | 无关词删除线抑制违规 vs 嵌套HTML展示标签删除线 | `CONFIRMED_DEFECT` | 无关词删除线降级违规；嵌套标签删除线破坏否定词误报肯定违规 |
| **INV-05** | Occurrence 独立性 | 先双重否定后转折肯定，同一 marker 两次出现 | `PASS` | 两个 occurrence 独立评估且后置违规可靠捕获并报警 |
| **INV-06** | 否定局部性 | 同句认识模态后跟未收录主语代词(负责人) | `CONFIRMED_DEFECT` | 未收录主语导致独立肯定违规动作被误当成认识模态放行 |
| **INV-07** | 版本局部性 | 转折现行标准 vs 无转折顺承现行标准主张 | `CONFIRMED_DEFECT` | 无转折连词时前置历史限定错误豁免后置现行标准主张 |
| **INV-08** | 拒答与编造冲突 | 无答案题出现拒答词并断言编造技术(MySQL) | `PASS` | 冲突编造 100% 裁决为 CONFLICTING_FABRICATION |
| **INV-09** | 引用完整性 | 合法多项分组引用 [S1, S2] 被掩码屏蔽 | `CONFIRMED_DEFECT` | 掩码不匹配分组引用，内部数字泄漏为未受支持业务数字 |
| **INV-10** | 重复归一化稳定性 | 多次执行 normalize 幂等性 | `PASS` | 二次归一化输出与一次归一化完全一致，保持幂等 |

---

## 六、历史回归与默认 CI 验证

- **反例检出率 100%（7/7）**：AQ01（旧标准四百）、AQ02（废止标准）、AQ04（地区差异率超过百分之五）、AQ06（违规补签合同）、AQ11（编造 MySQL）、AQ12（编造五十份抽检）、AQ14（外部提示注入）全部可靠检出；
- **历史已修复假阳性 0 反弹**：AQ06 真实片段粗体否定（`通常**不可以**...`）与 AQ12 真实片段否定从句（`并未明确具体每年抽查多少份...`）的高严重度告警数保持为 0；
- **默认 CI 零外部调用**：执行过程中零真实 LLM API、零 Embedding API、零外部 Agent 与零模型裁判网络调用。

---

## 七、下一轮专项（建议 P10-06）最小修复建议

本轮为只读审计，不修改评分器代码。基于已留存的确定性证据，确有必要在下一轮启动 `P10-SCORER-ROBUSTNESS-06` 专项，实施以下最小边界定向加固：

1. **废除裸数字字符子串匹配**：在 `isNumberSupported` 中移除 `allSourceText.contains(digits)`，引入词边界与业务单位约束比对；
2. **掩码正则同步**：将 `MASK_CITATION` 更新为支持逗号分组语法 `Pattern.compile("\\[S\\d+(?:\\s*,\\s*S\\d+)*\\]", Pattern.CASE_INSENSITIVE)`；
3. **富文本多轮规整**：调整 `stripMarkdownFormatting` 顺序，先循环剥离展示标签直至稳定，再将 HTML 删除线转换为 `~~`；
4. **删除线靶向修饰绑定**：仅当删除线直接修饰/打断当前禁断 marker 或紧邻否定词时转入复核，无关词删除线不抑制独立肯定违规；
5. **现行标准逆向排查**：在 `isLegacyOccurrenceQualified` 中，若局部子句包含现行主张，阻断前置历史限定从句的免责传递；
6. **排除从句与特批例外解耦**：将普通业务范围排除（`除...外`）移出特批例外词表，杜绝向后传递例外许可；
7. **主语代词词表扩充与连词截断**：扩充常见业务主体代词，并在并列连词（且、同时）处截断认识模态统领范围。
