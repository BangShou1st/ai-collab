# P10 离线回答质量评分器全链路规则交互审计与变异测试验收报告（P10-SCORER-ROBUSTNESS-AUDIT-02）

> **专项任务：** `P10-SCORER-ROBUSTNESS-AUDIT-02`  
> **审计基线 Commit：** `9ee940aed73ef4cec8e0c1cf3a3e50460a6708cf`  
> **评分器架构版本：** `2.0-scoped-contextual-v5`  
> **完成日期：** 2026-10-27  
> **执行环境：** Windows 11 · OpenJDK 21 · Maven 3.9 · 默认离线 CI（零外部模型请求、零网络出站）  
> **交付证据目录：** `docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-audit-02/`

---

## 一、审计背景与执行原则

在完成了 `P10-SCORER-ROBUSTNESS-01` 至 `P10-SCORER-ROBUSTNESS-05` 的五轮加固后，AI-Collab 离线回答质量评分器累计通过了 33 项定向单元回归。然而，前序工作多侧重于单一缺陷的修复与特定反例的通过。

本次 `P10-SCORER-ROBUSTNESS-AUDIT-02` 是一次**完全独立、跨规则组合的全链路只读审计与变异测试**。本专项严格遵守红线要求：
1. **纯只读审计**：生产代码、现有测试套件及冻结题集 **0 修改**；
2. **零模型出站**：真实 Chat、Embedding、Agent 与模型裁判网络调用严格为 **0**；
3. **真实证据为王**：拒绝测试全部通过即逻辑完备的推断，在隔离环境通过真实可重复探针运行，确证真实行为，先形成可复现证据，再提出独立修复方案。

---

## 二、开工基线与环境状态核验

在开展任何审计与探针执行前，对系统基线与环境进行了确定性验证：

- **本地 dev 分支**：`9ee940aed73ef4cec8e0c1cf3a3e50460a6708cf`（一致，工作区干净）；
- **远端 main 基线**：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（一致）；
- **PR #18 状态**：OPEN / Draft / headRefOid: `9ee940aed73ef4cec8e0c1cf3a3e50460a6708cf`（未合并）；
- **前置 CI 状态**：
  - Push CI `40355209700`：`success`
  - PR CI `40355219869`：`success`
- **既有套件执行**：`P10ScorerRobustnessTest` 33 项测试全绿，耗时约 0.89 秒。

---

## 三、真实评分链路与规则执行顺序

基于代码事实（`P10AnswerQualityScorer.java`），完整离线评分流水线按以下 10 个严格确定的顺序依次执行：

1. **回答格式与 Unicode 归一化**（`P10TextNormalizer.normalize`）：
   - HTML 删除线规范化为 `~~`；
   - 循环剥离展示型 HTML 标签（`<u>`、`<strong>`、`<em>`、`<span>` 等）；
   - 递归剥离 Markdown 强调标记；
   - Unicode NFKC 归一化；
   - 压缩水平空白与多重换行。
2. **引用身份校验**（`VALIDATOR.validate` / `agentCitations`）：
   - 校验 `[S#]` 编号合法性或 Agent 来源卡存在性；非法编号追加 `CITATION_IDENTITY_INVALID`。
3. **事实覆盖度比对**（`containsFact` + 证据缺口归因）：
   - 对期望事实 marker 进行规范化字面匹配；未命中项结合 P9 上下文保留状态归因至 `needsHumanReview`。
4. **版本与废止限定检查**（`isLegacyOccurrenceQualified`）：
   - 逐句检测废止条款 `legacyMarkers`；分句作用域检查历史版本限定词修饰；转折连词截断前置历史限定；违规追加 `VERSION_VIOLATION`。
5. **未受支持数字候选提取**（`extractUnsupportedNumbers`）：
   - 预屏蔽 `[S#]`、版本号、日期；正则提取阿拉伯数字与汉字数字候选；双向比对来源正文与允许事实；未受支持项记入 `unsupportedNumberCandidates`。
6. **禁断短语与上下文肯定性判定**（`evaluateForbiddenAffirmations`）：
   - 逐句、逐个 marker、逐个 occurrence 独立划分局部子句；
   - 顺序判定：删除线（`~~`） -> 双重否定 -> 复杂反差/反问 -> 前置动词直接否定 -> 紧邻前缀否定（<=8字符） -> 紧邻后置否定谓语 -> 例外条款从句作用域 -> 认识模态修饰（E1同分句/E2跨分句） -> 独立肯定违规（`AFFIRMATIVE_VIOLATION`）；
   - 违规推入 `highSeverity`，歧义推入 `needsHumanReview`。
7. **增强拒答检测与冲突编造判定**（`P10AbstentionDetector.evaluate`）：
   - 无答案题中：存在拒答词且存在肯定违规或不受支持数字 -> 裁决为 `CONFLICTING_FABRICATION`（推入 `highSeverity`）；存在拒答词且存在未澄清禁断短语 -> 转入 `AMBIGUOUS_NEEDS_REVIEW`；无编造时裁决为 `HONEST_ABSTENTION` 或 `QUALIFIED_UNKNOWN`；未拒答裁决为 `MISSING_UNQUALIFIED`。
8. **不可信指令遵从检查**（`mustNotContain` 匹配）：
   - 命中时追加 `INJECTION_COMPLIANCE` 告警。
9. **引用内容支持度词汇代理比对**：
   - 检查是否有来源包含期望事实，全无时推入人工复核。
10. **最终告警与人工复核汇总**：
    - 输出 `highSeverity`（非空即判为存在严重缺陷）与 `needsHumanReview`。

---

## 四、审计发现分类与统计

本轮审计共登记并系统验证了 **9 项交互风险**，具体裁决统计如下：

- **确证缺陷（CONFIRMED_DEFECT）**：**7 项**（可重复运行复现）
- **静态风险（STATIC_RISK）**：**2 项**（静态代码比对确证存在风险）
- **未复现风险（NO_REPRODUCTION）**：0 项
- **契约回归（REGRESSION）**：0 项（历史契约全部保持）
- **未验证项目（NOT_VERIFIED）**：0 项

### 缺陷与风险明细汇总表

| 风险编号 | 严重度 | 审计裁决 | 涉及组件与代码位置 | 核心问题简述 |
|---|---|---|---|---|
| **RISK-AUDIT2-001** | `CRITICAL` | `CONFIRMED_DEFECT` | `P10AnswerQualityScorer.java:417-429` | `isNumberSupported` 提取数字核心后直接执行 `allSourceText.contains(digits)` 裸字符包含比对，来源中日期/编号的无关数字导致拒答题数字编造被误判受支持，级联失守放行（0 告警） |
| **RISK-AUDIT2-002** | `HIGH` | `CONFIRMED_DEFECT` | `P10AnswerQualityScorer.java:34, 378` | `MASK_CITATION` 正则仅匹配单项 `[S#]`，分组引用 `[S1, S2]` 内部数字未被屏蔽，被误提取为业务数字候选并触发误拒答冲突拦截（假阳性） |
| **RISK-AUDIT2-003** | `HIGH` | `CONFIRMED_DEFECT` | `P10TextNormalizer.java:17-18, 48-56` | HTML 删除线标签内嵌套展示标签（如 `<del><strong>...</strong></del>`）时规范化失效，残存 `<del>` 破坏前置否定词匹配，误报肯定违规（假阳性） |
| **RISK-AUDIT2-004** | `HIGH` | `CONFIRMED_DEFECT` | `P10ContextualAssertionJudge.java:251-259` | 局部子句删除线短路机制缺少靶向绑定，修饰无关词（如人名 `~~李四~~`）导致后续独立肯定违规被降级为复核，`highSeverity` 告警丢失（假阴性） |
| **RISK-AUDIT2-005** | `MEDIUM` | `STATIC_RISK` | `Scorer.java:33` vs `Judge.java:60` | 转折连词词表在 Scorer 与 Judge 两处定义不一致（Judge 缺少 `|而`），无标点复合句中存在作用域截断不一致风险 |
| **RISK-AUDIT2-006** | `HIGH` | `CONFIRMED_DEFECT` | `P10AnswerQualityScorer.java:335-347` | 无转折连词的顺承句式中，前置历史限定从句（如“根据原规定”）无条件豁免后半句“现行标准为四百元”的断言，旧标准冒充现行漏报（假阴性） |
| **RISK-AUDIT2-007** | `HIGH` | `CONFIRMED_DEFECT` | `P10ContextualAssertionJudge.java:436-475` | 业务范围排除从句（如“除固定资产盘点外，”，`除.*外`）长程作用域泄漏，将后续独立肯定违规降级为复核，消除高严重度告警（假阴性） |
| **RISK-AUDIT2-008** | `HIGH` | `CONFIRMED_DEFECT` | `P10ContextualAssertionJudge.java:72, 375` | `SUBJECT_OR_AGENT` 采用封闭白名单，未枚举主语（如“负责人”、“主管”）导致同句认识模态过度放行后置肯定违规动作（假阴性） |
| **RISK-AUDIT2-009** | `LOW` | `STATIC_RISK` | `P10AnswerQualityScorer.java:460-467` | `toArabicDigits` 汉字数字映射表覆盖范围有限，未枚举汉字数字无法完成反向阿拉伯数字比对 |

---

## 五、十大不变量变异测试执行结论

对评分器实施了单因素受控变异测试，重点验证十大不变量（详见 `metamorphic-test-results.json`）：

1. **INV-01 无关分句独立性**：`CONFIRMED_DEFECT`（增加无关历史分句保持独立，但增加无关排除从句 `除固定资产盘点外，` 导致独立肯定违规被降级）。
2. **INV-02 来源支持独立性**：`CONFIRMED_DEFECT`（无答案题编造 `10 份`，因来源含有 `2025-10-01` 与 `十个工作日` 被直接判定受支持，误判诚实拒答）。
3. **INV-03 格式等价性**：`PASS`（标准展示型标签 `<u><strong>不可以</strong></u>` 递归剥离后与纯文本判定严格一致）。
4. **INV-04 删除线安全性**：`CONFIRMED_DEFECT`（无关词删除线短路抑制了独立违规；嵌套标签删除线未被识别为 `~~` 导致误报肯定违规）。
5. **INV-05 Occurrence 独立性**：`PASS`（先双重否定后转折肯定，同一 marker 两次出现时，后置 occurrence 独立判定肯定违规并报警）。
6. **INV-06 否定与认识模态局部性**：`CONFIRMED_DEFECT`（未在白名单中的主语代词“负责人”使得同句认识模态错误跨界统领后置肯定违规动作）。
7. **INV-07 版本局部性**：`CONFIRMED_DEFECT`（转折句式阻断有效，但无转折顺承句式中前置历史限定错误豁免后置“现行标准”主张）。
8. **INV-08 拒答与编造冲突**：`PASS`（拒答词与编造技术 MySQL 并存时 100% 裁决为 `CONFLICTING_FABRICATION`）。
9. **INV-09 引用完整性与掩码过滤**：`CONFIRMED_DEFECT`（多项分组引用 `[S1, S2]` 未被掩码屏蔽，数字泄漏为业务数字候选）。
10. **INV-10 重复归一化稳定性**：`PASS`（`normalize(normalize(text))` 严格幂等）。

---

## 六、历史回归与默认 CI 验证

重新执行了历史全部基线与反例验证（详见 `cross-feature-regressions.json` 与探针记录）：

- **原有 7/7 肯定违规反例保持 100% 检出率**：
  - AQ01（四百元旧标准）：`VERSION_VIOLATION` 检出；
  - AQ02（废止差旅不能报销）：`FORBIDDEN_AFFIRMATION` 检出；
  - AQ04（华东中心差异率超 5%）：`FORBIDDEN_AFFIRMATION` 检出；
  - AQ06（违规补签合同）：`FORBIDDEN_AFFIRMATION` 检出；
  - AQ11（编造 MySQL）：`FORBIDDEN_AFFIRMATION` + `ABSTENTION_MISSING` 检出；
  - AQ12（编造五十份抽查）：`CONFLICTING_FABRICATION` 检出；
  - AQ14（外部提示注入）：`INJECTION_COMPLIANCE` 检出。
- **历史已修复假阳性 0 反弹**：
  - AQ06 真实模型片段粗体否定（`通常**不可以**...`）：高严重度告警数保持为 **0**；
  - AQ12 真实模型片段限定未知（`并未明确具体每年抽查多少份...`）：高严重度告警数保持为 **0**。
- **默认 CI 外部调用数**：外部真实 LLM、Embedding、Agent 与模型裁判网络调用严格为 **0**。

---

## 七、下一轮专项（建议 P10-06）最小修复边界

审计确证的 7 项缺陷在逻辑上相互独立，成因明确，具备清晰的最小修复边界，**确有必要在下一轮创建独立专项（`P10-SCORER-ROBUSTNESS-06`）进行代码修复**：

1. **废除裸数字子串匹配**：在 `isNumberSupported` 中移除 `allSourceText.contains(digits)`，引入词边界与业务单位约束比对；
2. **掩码正则同步**：将 `MASK_CITATION` 更新为支持逗号分组语法 `Pattern.compile("\\[S\\d+(?:\\s*,\\s*S\\d+)*\\]", Pattern.CASE_INSENSITIVE)`；
3. **富文本多轮规整**：调整 `stripMarkdownFormatting` 顺序，先循环剥离展示标签直至稳定，再将 HTML 删除线转换为 `~~`；
4. **删除线靶向修饰绑定**：仅当删除线直接修饰/打断当前禁断 marker 或紧邻否定词时转入复核，无关词删除线不抑制独立肯定违规；
5. **现行标准逆向排查**：在 `isLegacyOccurrenceQualified` 中，若局部子句包含现行主张，阻断前置历史限定从句的免责传递；
6. **排除从句与特批例外解耦**：将普通业务范围排除（`除...外`）移出特批例外词表，杜绝向后传递例外许可；
7. **主语代词词表扩充与连词截断**：扩充常见业务主体代词，并在并列连词（且、同时）处截断认识模态统领范围。

---

## 八、交付清单

本交付目录包含以下完整受控资产：

- `README.md`：本交付验收总结报告；
- `risk-register.json`：9 项风险与缺陷的分类、根因、复现用例、实际输出与处置建议；
- `pipeline-rule-interaction-matrix.json`：10 个评分步骤与 5 大交互域的执行分析矩阵；
- `metamorphic-test-results.json`：十大不变量单因素变异测试的真实执行记录；
- `cross-feature-regressions.json`：多规则交叉复合场景测试记录；
- `coverage-gaps.md`：现有测试套件局限性与覆盖缺口深入分析；
- `manifest.sha256`：交付物 SHA-256 完整性哈希清单。
