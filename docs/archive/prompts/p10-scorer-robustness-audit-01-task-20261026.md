# P10-SCORER-ROBUSTNESS-AUDIT-01 任务书归档

> **任务标识：** `P10-SCORER-ROBUSTNESS-AUDIT-01`  
> **任务名称：** 评分器全规则组合与作用域风险审计  
> **归档日期：** 2026-10-26  
> **归档位置：** `docs/archive/prompts/p10-scorer-robustness-audit-01-task-20261026.md`

---

## 原始任务书内容

### 一、任务目标
对 AI-Collab 项目 P10 离线回答质量评分器进行一次系统性审计。
前置专项：
- `P10-SCORER-ROBUSTNESS-01`：否定语境、Markdown 归一化与拒答识别。
- `P10-SCORER-ROBUSTNESS-02`：认识模态、转折分句与重复 marker 作用域。
- `P10-SCORER-ROBUSTNESS-03`：例外条款作用域与独立肯定违规漏报。
当前目标不是继续堆叠局部正则，而是确认不同判定规则组合时是否存在新的作用域泄漏、规则顺序冲突、错误放行或错误告警。
本轮以只读审计为主，不预设一定需要修改代码。先形成完整风险矩阵和证据，再决定是否开启下一轮针对性修复。

### 二、开工基线与保护要求
仓库：`BangShou1st/ai-collab`
最近已核实的远端基线：
- `dev`：`c8c39bf554548b0fe8ee2eb237677b6eff77da47`
- `main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`
- PR #18：OPEN / Draft / 未合并
- Push CI：40332424795，success
- PR CI：40332436328，success
如果工作区不干净、SHA 不一致或发现未提交修改，先停止并报告，不得覆盖、清理、重置或替用户处理改动。
继续使用现有 `dev` 分支和 PR #18，不新建分支。

### 三、审计范围
优先检查以下文件及它们之间的调用关系：
1. `P10TextNormalizer.java`
2. `P10ContextualAssertionJudge.java`
3. `P10AbstentionDetector.java`
4. `P10AnswerQualityScorer.java`
5. `P10ScorerRobustnessTest.java`
6. `P10AnswerQualityOfflineEvaluationTest.java`
7. `P10RealGenerationGateTest.java`
8. 冻结题集、现有回归证据和评分器设计文档。

### 四、最高优先级风险：整句双重否定检测
重点检查：`P10ContextualAssertionJudge.evaluateOccurrences()`
核实当前实现是否仍在逐 occurrence 判断之前执行类似：
`DOUBLE_NEGATION.matcher(cleanSentence).find()`
并在命中后直接返回 `AMBIGUOUS_NEEDS_REVIEW`。
需要验证的风险场景：
- 一个分句出现双重否定，另一个独立分句明确肯定了禁断命题。
- 同一 marker 第一次处于双重否定语境，第二次处于明确肯定违规语境。
- 双重否定只作用于某一个 marker，而同句其他 marker 的语义明确。
- 双重否定与例外条款、认识模态、转折词组合。
- 复杂语境确实无法判定时，人工复核路径是否仍然可靠。

### 五、建立完整规则组合矩阵
至少覆盖以下维度：
否定、认识模态、例外、转折、重复 marker、标点边界、格式归一化、拒答、版本与地区、注入、引用、规则汇总。

### 六、验证方法与证据标准
按 CONFIRMED、STATIC_RISK、COVERED、NOT_APPLICABLE、UNKNOWN 分类。

### 七、本轮修改权限
本轮原则上只读。允许读取和分析源码、测试、历史证据与文档；执行不修改仓库内容的既有测试；生成独立审计报告与风险矩阵；对已有测试覆盖范围进行静态核查。未经确认，不得修改评分器逻辑、生产源码、System Prompt、检索算法或冻结题集。
禁止真实 Chat、Embedding、Agent 和模型裁判调用。

### 八、交付物
新增独立目录：`docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/`
包含 README.md、rule-interaction-matrix.json、risk-register.json、coverage-gaps.md、manifest.sha256。
同步归档任务书、研究记录和交付报告，并在文档导航中添加索引。
不得覆盖 P10-01、P10-02、P10-03 及 Errata 的历史证据或哈希清单。
