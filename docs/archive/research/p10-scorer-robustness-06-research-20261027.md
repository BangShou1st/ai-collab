# P10-SCORER-ROBUSTNESS-06 研究记录（归档）

> **日期：** 2026-10-27 · **基线：** `953fe978e471a8ab1a36494cac8b94756aa929f2`（评分器 2.0-scoped-contextual-v5）

本文记录七项缺陷修复过程中的关键技术决策与发现，供后续轮次参考。

## 1. RISK-AUDIT2-001 的真实触发路径核实

审计报告将 P1 反例的触发来源记为“来源含 2025-10-01 与十个工作日”。经对 AQ12 冻结来源（F3-审计待定事项备忘、F1-审计核对工作指引、C3-资产盘点操作规范-总部本部）grep 核实：三份语料中均不存在阿拉伯数字子串 `10`（`grep -c "10"` 为 0）。真实触发路径是**汉字等值链**：`isNumberSupported("10 份")` 提取 digits=`10` 后，`toSimpleChineseNumber("10")="十"`，而 F1 语料含“核对报告在项目结束后**十个工作日**内出具”——`allSourceText.contains("十")` 命中。审计探针当时可能使用了自定义来源（含 2025-10-01），但“十个工作日”路径在真实题集上即可稳定复现，缺陷成立性不受影响。修复因此必须同时约束阿拉伯词边界匹配与汉字等值变体的**单位绑定**（“十份”而非裸“十”）。

## 2. RISK-AUDIT2-003 的下游表现与审计记录差异

审计记载 `<del><strong>不可以</strong></del>` 场景误报 `AFFIRMATIVE_VIOLATION`（假阳性）。本轮基线探针实测（同一 commit、同一题集）为：normalizedAnswer 残留 `<del>不可以</del>`，`</del>` 打断 marker “可以先组织培训再补签” 的连续性 → `containsFact` 三级匹配（直接/紧凑/删除线分支）均不命中（文本无 `~~`，删除线分支要求 `~~` 存在）→ `forbiddenAffirmationDetails` 为空、0 告警（漏报方向）。两种表现同根因（删除线语义丢失），修复方向一致（多轮规整），已在交付报告如实记录差异。教训：审计探针的中间态记录（normalizedAnswer）应与最终裁决一起复核，防止下游表现推断偏差。

## 3. P7 为什么选择连词截断而非主语词表扩张

将“负责人”加入 `SUBJECT_OR_AGENT` 词表可以修复 `资料并未明确具体事项且负责人可以…`，但会引入新的假阳性：“负责人并未明确可以先组织培训再补签”（合法认识模态陈述）中 `hasSubjectOrAgent=true` 会跳过 E1 的放行分支，落入 AFFIRMATIVE_VIOLATION。任务书同时要求“避免无止境扩张词表”。并列连词截断（且/同时/并且/而且/以及）从命题结构上解决跨域统领问题：连词后必为独立命题，前置模态无论接什么主语都不再跨域。全部 6 个主语/连词变体用例验证通过，且 AQ12 场景 C（无主语合法未知）零反弹。

## 4. P6 修复中的词表陷阱

初稿将特批词表从 `除.*外` 替换为 `…|经.*批准|经.*同意`，随即在 4E 场景（`经办人~~不同意加班~~说无可厚非，可以先…`）触发回归失败：`经.*同意` 中 `.*` 跨任意字符，把“经**办人不**同**意**加班”误匹配为例外引导词，使后置违规被降级复核。`经.*同意` 被移除（“除经…特批外” 场景已由 `特批` 命中覆盖）；`经.*批准` 保留但存在类似理论风险（“经办人不批准”），因其后置分支（无局部否定 → AMBIGUOUS 复核）是安全路径而可接受。教训：中文例外词表的 `.*` 模式必须用含否定前缀的日常词组做反向回归。

## 5. before 证据的工程保障

第一版基线探针在迭代调试中被误重跑，`baseline-probe.json` 被修复后输出覆盖（before 侧出现修复后行为）。处置：`git worktree add` 建立基线 commit 的独立工作树，在其中重建探针并运行，将输出复制回主工作区，确保 before 数据严格来自未修复实现（scorerVersion=2.0-scoped-contextual-v5、baselineCommit=953fe978 双重校验）。教训：基线证据类工具应在首次运行后立即归档为只读资产，或以系统属性锁定输出文件。

## 6. 本地 verify 的 Testcontainers 抖动定性

三次全量 `mvnw verify`（1897 测试）分别在不同 Postgres 集成测试类上以 `CannotGetJdbcConnectionException` 失败（AgentResearchQualityPostgresTest / PersistedModelTurnRecoveryPostgresTest×2，失败点互不相同），三个类单独重跑均全绿。`docker stats` 显示 Docker Desktop WSL2 内存上限仅 1.859GiB，全量套件 8 分钟内 Testcontainers 频繁启停 Postgres 容器导致连接抖动。该失败与本轮改动（4 个 test-scope 评分器文件，零 JDBC/容器接触）无关，历史 CI 全绿佐证代码在 CI 资源下稳定。最终以 push 后 CI 的完整 verify 为权威门禁。

## 7. Map.of 键序噪声与历史证据保护

`P10ScorerRobustnessTest.generateComparisonEvidenceArtifacts` 在每次运行时重写 `2026-10-26/p10-scorer-robustness-05/` 证据 JSON，且记录由 `Map.of()` 构建（Java 规范不保证键序），导致每次运行产生全文件键序 diff（数据内容零变化）。本轮以逐字节 diff 核实后 `git checkout` 恢复，提交内容保持历史证据原始状态。建议后续专项将该测试的证据输出迁移至 `target/` 运行时目录或改用 LinkedHashMap 固定键序。
