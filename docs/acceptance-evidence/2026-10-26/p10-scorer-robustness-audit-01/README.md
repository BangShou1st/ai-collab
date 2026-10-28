# P10 离线回答质量评分器全规则组合与作用域风险审计报告

> **任务标识：** `P10-SCORER-ROBUSTNESS-AUDIT-01`  
> **审计基线 Commit：** `c8c39bf554548b0fe8ee2eb237677b6eff77da47`  
> **远端 main 基线：** `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`  
> **关联 PR：** `#18`（OPEN / Draft / MERGEABLE）  
> **前置 CI 记录：** Push CI `40332424795` (success) / PR CI `40332436328` (success)  
> **归档位置：** `docs/acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/README.md`

---

## 一、审计背景与目标

AI-Collab 离线回答质量评分器（`P10AnswerQualityScorer` 2.0-scoped-contextual 系列）在前三轮专项中完成了以下工作：
- `P10-SCORER-ROBUSTNESS-01`：统一 Markdown 格式归一化、前置否定动词修饰识别、拒答词扩展；
- `P10-SCORER-ROBUSTNESS-02`：认识模态作用域收敛、转折分句隔离、重复 marker 独立判定；
- `P10-SCORER-ROBUSTNESS-03`：例外条款（特批例外）局部子句绑定，解决转折后独立肯定违规被掩盖的漏洞。

本轮任务（`P10-SCORER-ROBUSTNESS-AUDIT-01`）为**系统性只读审计专项**，不预设必须修改代码，重点核查在多规则交叉、复合分句、嵌套修饰及边界语境下是否存在新的作用域泄漏、规则顺序冲突、错误放行（假阴性）或错误拦截（假阳性）。

---

## 二、开工基线与执行环境核实

开工前已对本地工作区及远端状态完成 100% 确定性检查：
1. **工作区状态**：本地工作树干净（`working tree clean`），无任何未提交修改；
2. **分支与提交**：`HEAD` 与 `origin/dev` 完全对齐于 `c8c39bf554548b0fe8ee2eb237677b6eff77da47`；`origin/main` 为 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；
3. **PR 状态**：PR #18 为 Draft / OPEN 状态，未合并，headRefOid 为 `c8c39bf554548b0fe8ee2eb237677b6eff77da47`；
4. **历史测试运行**：本地既有 28 个测试用例全部通过（0 failures, 0 errors），执行耗时 ~3.5 秒；
5. **历史证据保护**：核实 `p10-scorer-robustness-01`、`02`、`03` 历史交付物与 SHA-256 清单均完好无损。

---

## 三、最高优先级风险核实：整句双重否定检测

任务书重点要求审查 `P10ContextualAssertionJudge.evaluateOccurrences()` 中的全局双重否定检测机制。

### 1. 代码事实核查
当前代码（`P10ContextualAssertionJudge.java:161-171`）：
```java
// 1. 全局双重否定检测：若整句存在强双重否定结构，反转概率极高，必须人工复核
if (DOUBLE_NEGATION.matcher(cleanSentence).find()) {
    results.add(new Assessment(
            marker,
            AssertionVerdict.AMBIGUOUS_NEEDS_REVIEW,
            cleanSentence,
            "检测到双重否定结构，语义可能翻转，机械规则无法可靠保证，转人工复核",
            RULE_VERSION
    ));
    return results;
}
```
**证实**：代码确实在遍历每一个 occurrence 之前，对整个大句 `cleanSentence`（由 `。！？；\n` 切分的大句）执行了 `DOUBLE_NEGATION.matcher(cleanSentence).find()`，一旦命中即**直接提前 return**！

### 2. 验证场景与确定性证据

对任务书要求的五个关键场景进行了可复现的确定性验证（代码及输出已固化在 Scratch 审计验证套件中）：

| 场景 | 输入回答 | 预期行为 | 实际行为 | 审计判定 |
|---|---|---|---|---|
| **场景 1：分句双重否定 + 独立分句肯定违规** | “我们不得不承认前期沟通存在问题，但是实际操作中可以先组织培训再补签合同 [S1]。” | 检出后半句违规，触发 `highSeverity` 报警 | 返回 `AMBIGUOUS_NEEDS_REVIEW`，`highSeverity` 为空 | **CONFIRMED 漏报** |
| **场景 2：拒答题双重否定掩盖冲突编造** | “虽然我们不得不承认资料不足、无法确认，但是该系统目前确定使用 MySQL 存储凭证影像 [S1]。” | 拒答失败，判定为 `CONFLICTING_FABRICATION` | 判定为 `HONEST_ABSTENTION`，`abstentionCorrect=true`，0 告警放行 | **CONFIRMED 致命漏洞** |
| **场景 3：同一 marker 两次出现（先双重否定后转折肯定）** | “虽然并非不可以先组织培训再补签合同，但我们明确声称可以先组织培训再补签合同 [S1]。” | 记录 2 个 occurrence，后一个判定违规并报警 | 仅记录 1 个 occurrence，第二个被完全吞掉，0 告警 | **CONFIRMED 漏报** |
| **场景 4：双重否定跨 marker 污染** | “虽然我们不得不承认数据统计困难，但是华东中心盘点差异率超过百分之五时需要提交说明 [S1]。” | 华东 5% 判定为 `AFFIRMATIVE_VIOLATION` 报警 | 被前半句“不得不”污染，降级为复核，0 告警 | **CONFIRMED 漏报** |
| **场景 5：单子句双重否定规范修饰** | “根据资料，并非不可以先组织培训再补签合同 [S1]。” | 语义反转，转入人工复核 | 正确转入人工复核 | **COVERED 正常** |

### 3. 根因剖析与最小修复边界
- **根因**：双重否定检测重蹈了 P10-02/P10-03 之前旧版本“全句级扫描”的覆辙。由于大句可以由多个逗号分句组成，将双重否定置于全局短路位置，使得整句中的所有分句、所有 marker、所有后续 occurrence 全部丧失了独立判定的能力。
- **最小修复边界**：
  1. 彻底移除 `evaluateOccurrences()` 中的整句级 `DOUBLE_NEGATION.matcher(cleanSentence).find()` 短路；
  2. 将双重否定判断下沉至 `judgeSingleOccurrence()` 内部；
  3. 双重否定词必须限定在当前 occurrence 的局部子句（`localClause`）或紧邻前置修饰区，且必须受转折连词截断；
  4. 每个 occurrence 独立输出 Assessment，保持审计明细的完整性。

---

## 四、全规则组合风险登记表概览

本轮审计共登记 10 项风险（详见 `risk-register.json`），其中：
- **CRITICAL（严重漏报/失守）**：2 项（均为 `CONFIRMED` 已证实）
- **HIGH（独立违规降级/放行）**：2 项（均为 `CONFIRMED` 已证实）
- **MEDIUM（误报/假阳性/版本漏洞）**：3 项（均为 `CONFIRMED` 已证实）
- **LOW（正则与词表边界）**：3 项（均为 `STATIC_RISK` 静态风险）

| 风险 ID | 严重度 | 状态 | 核心问题简述 |
|---|---|---|---|
| **RISK-P10-001** | `CRITICAL` | `CONFIRMED` | 全局整句双重否定检测导致后半句独立肯定违规降级为复核，漏报 highSeverity |
| **RISK-P10-002** | `CRITICAL` | `CONFIRMED` | 双重否定短路使肯定违规为空，拒答检测器误将冲突编造判定为诚实拒答放行 |
| **RISK-P10-003** | `HIGH` | `CONFIRMED` | 复杂反差模式（RHETORICAL_OR_HYPOTHETICAL）整句污染导致独立违规降级 |
| **RISK-P10-004** | `HIGH` | `CONFIRMED` | 认识模态短前缀判定（length <= 6）错误放行肯定违规动作主谓句 |
| **RISK-P10-005** | `MEDIUM` | `CONFIRMED` | 后置否定词（如“是不允许的”）未被纳入判定导致合法否定被误报为肯定违规 |
| **RISK-P10-006** | `MEDIUM` | `CONFIRMED` | 版本限定检查大句级词汇包含导致同句转折后旧标准违规被放行 |
| **RISK-P10-007** | `MEDIUM` | `CONFIRMED` | 跨从句例外作用域受无关紧邻从句污染导致独立违规降级 |
| **RISK-P10-008** | `LOW` | `STATIC_RISK` | HAN_NUMBER 仅支持汉字数字，阿拉伯数字编造无法被数字候选检测捕获 |
| **RISK-P10-009** | `LOW` | `STATIC_RISK` | Markdown 与富文本归一化未覆盖删除线与 HTML 标签 |
| **RISK-P10-010** | `LOW` | `STATIC_RISK` | 转折连词正则未覆盖部分口语与书面转折引导词（如“只是”、“反倒”） |

---

## 五、关键结论与下一轮建议

1. **质量结论的强度与边界**：
   - 当前评分器在已有的 14 道冻结题目和典型单子句、局部转折模式上表现稳健，假阳性为 0；
   - **但是，不能宣称评分器在多分句组合场景下不存在漏报**。本轮审计确凿证实了整句级双重否定短路、后置否定缺失、短前缀放行过宽等 7 项已复现缺陷；
   - 现有的“CI 全绿”仅能证明对已知单一 pattern 的防御有效，无法保证多规则交织下的语义正确性。
2. **是否需要下一轮代码修复？**
   - **强烈建议开启下一轮针对性修复专项（`P10-SCORER-ROBUSTNESS-04`）**。
   - **最小修复范围**：
     - **优先修复项 1（CRITICAL）**：重构双重否定检测，彻底废除大句全局短路，将其下沉为局部子句级判定；修复拒答题中因双重否定导致的编造冲突漏报；
     - **优先修复项 2（HIGH）**：移除认识模态 E1 规则中过宽的 `length <= 6` 启发式，防止带主语的肯定动作被静默放行；
     - **优先修复项 3（MEDIUM）**：补充后置否定修饰语识别，消除合规否定被误判为违规的假阳性。
3. **本轮变更说明**：
   - 生产代码：**0 变更**；
   - 冻结题集：**0 变更**；
   - System Prompt 与 RAG 检索：**0 变更**；
   - 外部模型调用：**0 次**。

---

## 六、证据清单与完整性

本目录下的所有审计资产如下：
- `README.md`：本报告全文；
- `rule-interaction-matrix.json`：20 项规则交互矩阵及审计状态；
- `risk-register.json`：10 项审计发现的详细定义、复现证据与修复建议；
- `coverage-gaps.md`：现有测试覆盖盲区与覆盖率统计；
- `manifest.sha256`：本证据目录完整性校验散列清单。
