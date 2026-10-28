# P10 离线回答质量评分器七项确证缺陷修复与回归验收报告（P10-SCORER-ROBUSTNESS-06）

> **专项任务：** `P10-SCORER-ROBUSTNESS-06`
> **修复基线 Commit：** `953fe978e471a8ab1a36494cac8b94756aa929f2`（dev，工作区干净）
> **修复前评分器版本：** `2.0-scoped-contextual-v5` → **修复后版本：** `2.1-scoped-contextual-v6`
> **完成日期：** 2026-10-27
> **执行环境：** Windows 11 · OpenJDK 21 · Maven 3.9 · 默认离线 CI（零外部模型请求、零网络出站）
> **交付证据目录：** `docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-06/`
> **最终裁决：** `P10_SCORER_ROBUSTNESS_06_ACCEPTED`（依据见第八节）

---

## 一、专项目标与执行原则

本专项基于 `P10-SCORER-ROBUSTNESS-AUDIT-02` 的审计结果，对 AI-Collab 的 P10 离线回答质量评分器开展独立的测试驱动修复（TDD）。审计确证的 7 项缺陷为唯一默认修复范围：

- `RISK-AUDIT2-001`（CRITICAL）：数字来源支持判定的裸字符包含误判。
- `RISK-AUDIT2-002`（HIGH）：多项分组引用未被完整屏蔽。
- `RISK-AUDIT2-003`（HIGH）：删除线内部嵌套 HTML 展示标签导致否定语义破坏。
- `RISK-AUDIT2-004`（HIGH）：无关成分的删除线错误抑制独立肯定违规。
- `RISK-AUDIT2-006`（HIGH）：前置历史限定错误豁免后置现行标准主张。
- `RISK-AUDIT2-007`（HIGH）：普通业务范围排除从句污染后置独立命题。
- `RISK-AUDIT2-008`（HIGH）：未覆盖的业务主语导致认识模态规则错误放行肯定违规。

执行原则：**先逐项在基线上真实复现，再实施最小修复，最后以真实运行结果证明修复成立且历史契约未退化**。全程不修改生产 Prompt、RAG 检索策略、`src/main/` 业务代码或冻结题集。

---

## 二、开工基线核验（2026-10-27 实测）

| 核验项 | 预期 | 实测 | 结论 |
| --- | --- | --- | --- |
| 本地分支 / HEAD | dev / `953fe97` | dev / `953fe978` | 一致 |
| `origin/dev` | `953fe97` | `953fe978` | 一致 |
| `origin/main` | `9b4f7c5` | `9b4f7c53` | 一致 |
| 工作区 | 干净 | 干净 | 一致 |
| PR #18 | OPEN / Draft / 未合并 | OPEN / Draft / `mergedAt=null` / head `953fe97` | 一致 |
| Push CI | `40360772076` success | success | 一致 |
| PR CI | `40360782534` success | success | 一致 |
| Audit-02 证据与 manifest | 存在 | `docs/acceptance-evidence/2026-10-27/p10-scorer-robustness-audit-02/` 7 个文件齐备 | 一致 |

P10 评分器源码定位：`ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/` 下的 `P10AnswerQualityScorer.java`、`P10ContextualAssertionJudge.java`、`P10TextNormalizer.java`、`P10AbstentionDetector.java`——**全部位于 test scope，属于离线评测范围，`src/main/` 零改动**。

---

## 三、TDD 执行过程

1. **基线探针**（临时测试类，交付前删除）：在未修改的 `953fe97` 基线上运行 7 项缺陷反例与配对正例，输出真实评分至 `baseline-probe.json`（探针输出于基线 git worktree 中最终重建，确保 before 数据严格来自未修复实现）。
2. **基线红灯确认**：正式回归套件 `P10ScorerRobustness06Test` 在基线上运行，**22 项测试中 15 项失败**（全部对应缺陷反例断言；受仓库 `*.log` 忽略规则约束，完整失败控制台输出留存于本地未跟踪副本，其关键实际输出已结构化固化于 `baseline-probe.json` 与 `before-after-regressions.json` 的 before 侧）。
3. **最小修复**：3 个评分器源文件（见第四节）。
4. **绿灯验证**：修复后 22/22 通过；随后补充 10 组跨规则组合场景与证据导出，共 32/32 通过。
5. **历史回归**：`P10ScorerRobustnessTest` 33/33 + `P10AnswerQualityOfflineEvaluationTest` 4/4 保持全绿。

## 四、七项缺陷修复明细

### P1 RISK-AUDIT2-001：数字来源支持判定（`P10AnswerQualityScorer.isNumberSupported`）

- **根因**：提取数字核心后对来源全文执行裸子串包含（`allSourceText.contains(digits)` 与汉字等值的 `contains(chineseNum)`）。真实冻结语料下，F1 文档的“十个工作日”使编造的“10 份”经 `toSimpleChineseNumber("10")="十"` 裸包含被误判受支持（基线探针 1A 实测 `unsupportedNumberCandidates=[]`、`HONEST_ABSTENTION`、0 告警）。
- **修复**：废除裸子串免责，改为“数值核心 + 业务单位 + 词边界”绑定比对（`containsBoundNumber`：`(?<![0-9.])数值(?![0-9.])\s*单位`）。汉字等值（五百↔500）与百分比等值（百分之五↔5%）在绑定同一单位后双向支持；无单位裸数值保守判为不受支持；预期事实 marker（受控标注）保留宽松路径；多候选逐个独立判定。
- **验证**：来源含无关日期/期限时“10 份”必检出且裁决 `CONFLICTING_FABRICATION`（1A/1B/1C）；“500 元”↔来源“五百元”双向不误报（1D）；“50 份”与受支持的“五百元”并存时分别判定（1E）；日期/版本号/引用编号元数据不触发告警（1F）。

### P2 RISK-AUDIT2-002：分组引用完整屏蔽（`P10AnswerQualityScorer.MASK_CITATION`）

- **根因**：正则仅匹配单项 `[S#]`，分组引用 `[S8, S9]` 的序号泄漏为业务数字候选（基线实测候选 `["8","9"]`、拒答被误判 `CONFLICTING_FABRICATION`）。
- **修复**：对齐生产 `KnowledgeCitationValidator` 契约——`\[S\d+(?:\s*,\s*S\d+)*\]`（CASE_INSENSITIVE）。
- **验证**：`[S8, S9]`、`[S1,S2,S3]` 均被屏蔽且不进入候选；合法分组引用恢复 `HONEST_ABSTENTION`；`[S8, S9]` 的身份非法告警 `CITATION_IDENTITY_INVALID` 保持（引用身份校验不削弱）；不完整引用 `[S8,`（缺右括号）不被宽松正则整体吞掉，其中数字不享受掩码豁免（2D）；合法分组引用与真实编造数字共存时数字照常检出（2C）。

### P3 RISK-AUDIT2-003：嵌套 HTML 删除线归一化（`P10TextNormalizer.stripMarkdownFormatting`）

- **根因**：`HTML_STRIKE_TAG` 内容模式 `([^<\n]+)` 不允许内部出现 `<`，`<del><strong>不可以</strong></del>` 在展示标签剥离前无法命中；原管道“先删线后剥展示标签”的顺序使 `<del>` 永久残留。
- **基线实测与审计记录的差异（如实记录）**：审计报告记载该场景误报 `AFFIRMATIVE_VIOLATION`；本轮基线探针实测 normalizedAnswer 残留 `<del>不可以</del>`，且 `</del>` 打断禁断 marker 的连续性导致 `containsFact` 不命中、`forbiddenAffirmationDetails` 为空（0 告警，漏报方向）。**缺陷本体（删除线语义丢失、规范化失效）确凿复现**，下游表现为漏报而非误报；两种表现均源于同一根因，不影响修复方向。
- **修复**：改为多轮规整——先剥离展示型标签、再将删除线标签规范为 `~~text~~`，循环至不动点（顺序不可颠倒，已在代码注释中固化约束）。
- **验证**：`<del><strong>不可以</strong></del>` 规范化为 `~~不可以~~` 且进入 `AMBIGUOUS_NEEDS_REVIEW`（3A）；`<u><strong>不可以</strong></u>` 不产生删除线歧义（3B）；嵌套 `<span>/<em>` 与带属性标签（3C）；不完整标签不丢失否定语义（3E）；相邻删除线标签场景 marker 经删除线归一化仍被定位并转复核（3F）；标签包裹数字（`<strong>50 份</strong>`）检出不受损（3D）。

### P4 RISK-AUDIT2-004：删除线靶向绑定（`P10ContextualAssertionJudge.A00 检查`）

- **根因**：`localClause.contains("~~")` 粗粒度短路，无关人名删除线（`经办人~~李四~~可以…`）抑制同分句独立肯定违规（基线实测降级 `AMBIGUOUS_NEEDS_REVIEW`、0 告警）。
- **修复**：新增 `strikethroughRelevantToProposition` 靶向判定——仅当删除线片段与 marker 区间相交、或片段内包裹否定/禁止语义词（`~~不可以~~` 的否定效力存疑）、或孤立 `~~` 紧邻命题区间时转人工复核；修饰无关成分的成对删除线不抑制独立违规；未通过“全局删除 `~~`”规避问题，marker 被删除线打断时的复核路径（`evaluateOccurrences` isEmpty 分支）保持不变。
- **验证**：任务书 5 个必验场景全部通过（无关人名→检出违规；包裹否定词→复核；前置分句删除线→后置违规检出；包裹完整命题→复核不机械放行；无关前置分句→不污染后置）。

### P5 RISK-AUDIT2-006：历史限定与现行标准隔离（`P10AnswerQualityScorer.isLegacyOccurrenceQualified`）

- **根因**：无转折连词的顺承复合句中，前置历史限定从句（“根据原规定”）经 `priorHasLegacy` 无条件豁免后置“现行标准为四百元”主张（基线实测 `LEGACY_QUALIFIED`、0 告警）。
- **修复**：逐 occurrence 增加“现行主张阻断”——局部子句在旧标准数值紧邻前方（窗口 6 字符）出现现行/目前/当前/现在/如今/现今等口径词时，该 occurrence 视为现行违规主张，局部与前置历史限定均不得豁免；不做整句级历史词搜索。
- **验证**：`根据原规定，现行标准为四百元`（5A）与无标点变体（5C）判 `VERSION_VIOLATION`；真正历史背景豁免保持（5B，历史 10D 用例）；“现行”出现在非主张位置（“原规定为四百元，现行文件已废止该标准”）不误判（5E）；同一回答中历史背景与独立现行主张并存时分别裁决（5D）。

### P6 RISK-AUDIT2-007：业务范围排除与特批例外解耦（`P10ContextualAssertionJudge.EXCEPTION_MARKER`）

- **根因**：例外词表含泛化 `除.*外`，普通业务范围排除从句（“除固定资产盘点外，”）获得特批例外豁免权并长程传递（基线实测独立违规降级 `AMBIGUOUS_NEEDS_REVIEW`）。
- **修复**：移除 `除.*外`，特批词表保留 `除非|特批|例外|特殊情况|经.*批准后方可|经.*批准`；“除经分管副总裁特批外”等含真实审批语义的表达仍由特批词命中；`isSelfContainedExceptionClause` 自闭合禁止判定保持不变。修复过程中发现并规避了初稿词表 `经.*同意` 的过度匹配（“经**办人不**同**意**” 跨字误命中），该教训已固化到代码注释。
- **验证**：普通范围排除后的独立违规检出（6A）；G4 特批条件许可保持人工复核（6B，历史边界）；排除从句内含真实特批语义保持例外处理（6C）；特批禁止主干与后置无关命题顺序互换互不污染（6D，历史 risk007/G2 保持）；历史 G1–G4 全部通过。

### P7 RISK-AUDIT2-008：认识模态与并列连词作用域（`P10ContextualAssertionJudge.模式 E1`）

- **根因**：E1 依赖封闭主语白名单判定模态作用范围，未枚举主语（“负责人”）+“具体”指示词使同句认识模态跨域放行后置肯定动作（基线实测 `NEGATED_OR_EPISTEMIC_MENTION`、0 告警）。
- **修复**：E1 引入并列连词截断 `truncateAtLastCoordinatingConjunction`（且/同时/并且/而且/以及）——模态统领范围止于最后一个连词，连词后为独立命题；主语与未知指示词检查均基于截断后的有效作用域。**刻意不扩张封闭主语词表**：将“负责人”加入白名单会使“负责人并未明确可以先…”这类合法认识模态误判为违规（假阳性），任务书亦要求避免无止境词表扩张；连词截断已覆盖全部必验场景。
- **验证**：`资料并未明确具体事项且负责人可以先组织培训再补签合同` 检出违规（7A）；真正认识模态陈述保持合法未知（7B，`QUALIFIED_UNKNOWN`）；负责人/经办人/各部门/项目组 × 且/同时 变体矩阵全部检出（7C/7D）；主语与动词被普通修饰语隔开仍正确（7E）。

## 五、静态风险裁决

| 风险编号 | 初始状态 | 本轮验证 | 裁决 |
| --- | --- | --- | --- |
| `RISK-AUDIT2-005`（转折连词两处定义不一致） | STATIC_RISK | 基线确定性复现：`资料中未明确具体抽查比例而负责人可以先…` 因 Judge 词表缺“而”被模态放行（0 告警） | **RESOLVED_VERIFIED**（最小附带修复）：Judge 的 `ADVERSATIVE_CONJUNCTION` 补齐 `\|而` 与 Scorer 对齐，回归用例 `staticRisk005` 固化 |
| `RISK-AUDIT2-009`（汉字数字映射范围有限） | STATIC_RISK | 基线确定性复现：来源“20 份”、回答“二十份”被误报候选（假阳性方向） | **RESOLVED_VERIFIED**（最小附带修复）：`toArabicDigits`/`toSimpleChineseNumber` 补充整十组合（二十…九十）与单字兜底（十/五）；超出映射的汉字数字保持保守检出，不做无边界词表扩张 |

两项静态风险均与七项修复直接相关（005 与 P7 同属作用域切分；009 与 P1 同属数字等值比对）、均可确定性复现，符合任务书第六节的最小附带修复边界。

## 六、回归与全量验证结果

| 验证项 | 结果 |
| --- | --- |
| `P10ScorerRobustness06Test`（本轮新增专项） | 32/32 通过（7 项缺陷回归 22 项 + 组合场景 9 项 + 证据导出 1 项） |
| `P10ScorerRobustnessTest`（历史健壮性回归） | 33/33 通过，历史 7/7 反例检出率 100%，AQ06/AQ12 假阳性 0 反弹 |
| `P10AnswerQualityOfflineEvaluationTest` | 4/4 通过 |
| `P10NetworkProxyDiagnosticTest` | 1 项跳过（`P10_PROXY_DIAGNOSTIC` 环境变量门控的网络诊断测试，默认 CI 既定行为） |
| `mvnw verify`（本地全量，1897 测试） | 三次运行分别为 1896/1897、1896/1897、1896/1897：唯一 Error 均为 **不同的** Postgres 集成测试类的 `CannotGetJdbcConnectionException`（本地 Docker Desktop WSL2 内存上限约 1.86GB，Testcontainers 在 8 分钟全量套件中频繁启停 Postgres 容器产生的连接抖动；三次失败点互不相同且具随机性）。三个失败类均单独重跑全绿（`AgentResearchQualityPostgresTest` 7/7、`PersistedModelTurnRecoveryPostgresTest` 24/24）。该错误与本轮改动无关（本轮仅改 4 个 test-scope 评分器文件，零 JDBC/容器/生产代码接触），最终以 push 后 CI 的完整 `verify` 全绿作为权威门禁 |
| 前端 `lint` / `typecheck` / `test` / `build` | 全部通过（409/409 测试，52 个文件；本轮零前端改动，例行全量确认） |
| 外部真实模型调用（Chat / Embedding / Agent / 模型裁判） | **0** |

## 七、证据清单与完整性

本目录交付物（manifest 见 `manifest.sha256`，全部为 SHA-256 实测计算）：

| 文件 | 内容 |
| --- | --- |
| `README.md` | 本验收报告 |
| `baseline-probe.json` | 基线 `953fe97`（评分器 v5）上 7 项缺陷反例与配对正例的真实评分输出（于基线 git worktree 生成） |
| `before-after-regressions.json` | 七项缺陷修复前后的真实运行对比（before 取自 baseline-probe，after 为修复后实测；基线正式回归套件红灯运行中 15 项失败的实际断言输出即体现于此表 before 侧与 baseline-probe） |
| `numeric-support-regressions.json` | P1 数字支持判定专项回归 |
| `grouped-citation-regressions.json` | P2 分组引用屏蔽专项回归 |
| `rich-text-and-strikethrough-regressions.json` | P3/P4 富文本与删除线专项回归 |
| `legacy-and-exception-scope-regressions.json` | P5/P6 版本限定与例外作用域专项回归 |
| `epistemic-scope-regressions.json` | P7 认识模态作用域专项回归 |
| `cross-feature-regressions.json` | 9 组跨规则组合场景回归（真实输入、预期、实际输出、告警与通过标记） |
| `false-positive-regressions.json` | 假阳性回归（历史已知合法表达 0 误报） |
| `false-negative-regressions.json` | 假阴性回归（历史 7/7 反例 + 本轮 5 项缺陷反例检出） |
| `risk-remediation-matrix.json` | 九项风险（7 缺陷 + 2 静态）的根因、修复与裁决矩阵 |

配套文档：任务书归档 `docs/archive/prompts/p10-scorer-robustness-06-task-20261027.md`；研究记录 `docs/archive/research/p10-scorer-robustness-06-research-20261027.md`；交付报告 `docs/archive/reports/p10-scorer-robustness-06-delivery-20261027.md`。

**历史证据保护说明**：`P10ScorerRobustnessTest.generateComparisonEvidenceArtifacts` 在每次测试运行时会以 `Map.of()`（键序不确定）重写 `2026-10-26/p10-scorer-robustness-05/` 下的证据 JSON，经逐字节 diff 核实全部差异均为 JSON 键序重排、数据内容零变化。本轮已在验证后 `git checkout` 恢复该目录，提交内容保持历史原始证据与 manifest 不变；该重写行为属测试设计的历史遗留，建议后续专项将证据生成目标迁移至运行时目录。

## 八、最终裁决

**裁决：`P10_SCORER_ROBUSTNESS_06_ACCEPTED`**

依据：

1. 七项目标缺陷全部在基线 `953fe97` 上真实复现（`baseline-probe.json` 与正式回归套件基线红灯运行），并全部以最小修复解决（`risk-remediation-matrix.json` 与 `before-after-regressions.json` 真实运行对比支持）。
2. 修复后专项回归 32/32、历史回归 33/33 + 4/4 全绿；历史 7/7 反例检出率 100%，AQ06/AQ12 假阳性 0 反弹，分组引用不再进入数字编造判定，诚实拒答不掩盖独立断言，普通历史背景/否定陈述/未知表达 0 误判。
3. 假阳性与假阴性双向回归均无新增（`false-positive-regressions.json` / `false-negative-regressions.json` 全部通过）；人工复核边界保持既有设计（双重否定、复杂反差、特批条件许可、删除线相关命题仍进复核）。
4. 两项静态风险（005/009）确定性复现并完成最小附带修复，裁决 RESOLVED_VERIFIED。
5. 改动严格限定于 4 个 test-scope 评分器文件与 1 个新增测试文件，`src/main/`、生产 Prompt、RAG 检索、Token 预算、冻结题集与历史证据零改动；外部真实模型调用数为 0。

**裁决边界（如实声明）**：本裁决仅覆盖本轮真实执行的测试与证据——冻结题集 14 题、审计登记 9 项风险、组合回归 9 组场景及其变体。评分器为确定性启发式规则引擎，本裁决不宣称其已覆盖全部自然语言表达；超出验证范围的语料（如新增句式、新业务词表场景）仍可能触发保守的人工复核路径，这正是设计意图。

**尚存风险与建议**：

- 本地全量 `verify` 在约 1.86GB 内存的 Docker 环境下存在 Postgres Testcontainers 连接抖动（与本专项无关，建议本地调高 Docker 内存配额）；CI 全绿为最终门禁。
- `P10ScorerRobustnessTest` 的证据重写行为（Map.of 键序噪声）建议后续迁移。
- 汉字数字映射仍非通用解析器（如“十五”“百”级组合未覆盖），超出映射的数字走保守检出路径，如未来题集扩充可按回归用例驱动增量扩展。
