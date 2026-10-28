# P10-SCORER-ROBUSTNESS-04｜已确证缺陷修复与逐项回归（原始任务书）

## 一、任务目标

基于 `P10-SCORER-ROBUSTNESS-AUDIT-01` 的审计结果，修复评分器中已经通过实际运行确证的漏报、误报和错误放行问题。

本轮属于**测试驱动的离线评分器修复专项**，不是生产 RAG 改造，也不是重新设计整个评分器。

主要目标：

1. 消除全局双重否定对独立分句及重复 marker 的污染。
2. 防止包含编造事实的回答因存在拒答词而被判定为诚实拒答。
3. 修复认识模态、反差转折、例外条件和版本限定的作用域泄漏。
4. 支持常见后置否定谓语，减少合法回答的错误高严重度告警。
5. 保留已有肯定违规检出能力、人工复核边界和历史证据完整性。

## 二、基线与 Git 保护

上轮已核实的远端基线：

- `dev`：`45d084d420e09767880225cd1ed2474118a3041e`
- `main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`
- PR #18：OPEN / Draft / 未合并
- Push CI：40338619259，success
- PR CI：40338634365，success

继续使用现有 `dev` 分支和 PR #18，不创建新分支，不合并 PR，不部署。

## 三、严格限定修改范围

重点检查以下现有文件，先确认真实路径及调用关系：

- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ContextualAssertionJudge.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AnswerQualityScorer.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10AbstentionDetector.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P10ScorerRobustnessTest.java`

**本轮禁止修改：**

- `src/main/` 生产代码。
- 生产 System Prompt、RAG 检索算法、来源选择策略及 Token 预算。
- 冻结题集 JSON 和历史评测基准。
- P10-01、P10-02、P10-02 Errata、P10-03 及 Audit-01 的既有证据文件和哈希清单。

不得通过放宽断言、删除反例或将预期错误结果改成实际错误结果来使测试通过。

## 四、按风险逐项修复

- RISK-P10-001 / 002：双重否定全局短路与拒答级联失守（CRITICAL）
- RISK-P10-003：反差转折规则的全局污染（HIGH）
- RISK-P10-004：认识模态短前缀错误放行（HIGH）
- RISK-P10-005：后置否定谓语误报（MEDIUM）
- RISK-P10-006：版本限定词跨分句泄漏（MEDIUM）
- RISK-P10-007：例外条款污染无关命题（MEDIUM）

## 五、静态风险的处理边界

以下风险暂不纳入本轮代码修改：

- `RISK-P10-008`：阿拉伯数字编造候选检测。
- `RISK-P10-009`：HTML 标签、删除线等富文本归一化。
- `RISK-P10-010`：口语化转折词覆盖不足。

## 六、证据与交付

新增独立证据目录：

`docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-04/`
