# P10-REAL-GENERATION-02 文档与证据一致性勘误（Errata）

> **归档位置：** `docs/acceptance-evidence/2026-10-26/p10-real-generation-02-errata/README.md`  
> **关联任务：** `P10-SCORER-ROBUSTNESS-01`  
> **勘误对象：** `docs/acceptance-evidence/2026-10-26/p10-real-generation-02/`（保持只读与冻结，不破坏原 SHA-256 清单）

---

## 1. 勘误背景与核查原则

在开展 `P10-SCORER-ROBUSTNESS-01` 评分器稳健性改造前，对上一轮真实模型验收产物（`P10-REAL-GENERATION-02`）进行了只读交叉检查。
核查遵循以下不可逾越的边界原则：
1. **冻结原始证据**：`docs/acceptance-evidence/2026-10-26/p10-real-generation-02/` 目录下的所有原始输出、账本、回执与 `manifest.sha256` 均已归档并已作为前置提交合入历史，**绝不直接修改、不重算、不破坏原 manifest**。
2. **实事求是**：查明不一致的源头，区分阶段草稿遗留与阶段裁决演进；对无法确定的属性明确标记为 `UNRESOLVED`，绝不凭空猜测。

---

## 2. 发现的现象与核查结论

### 现象一：README 批次对比表与正文记录不一致

- **现象描述**：
  在 `docs/acceptance-evidence/2026-10-26/p10-real-generation-02/README.md` 中：
  - 文档开头（第 3 行）及后续正文（第 45–78 行）均明确记录：“已获用户明确授权并执行真实 QA 生成 8 次，8 题全部取得模型回答”，“执行裁决 `MEASUREMENT_EXECUTED`；模型质量裁决 `PASS_WITH_SCORER_LIMITATIONS`”。
  - 但在第 10–20 行的“本轮与上一批次的差异”对比表中，`P10-REAL-GENERATION-02（本批次）` 列却仍记录为：“真实 Chat 尝试：0 次（等待授权）”、“取得模型回答：0 份（等待授权）”、“执行裁决：未裁决”、“模型质量裁决：未裁决”。
- **证据核查与根因**：
  1. 查验执行流程历史与提交记录：P10-02 任务严格分为“阶段一：零 Chat 预检”与“阶段二：授权后真实执行”。
  2. 该表格系阶段一预检文档编写时的草稿模板。在阶段二真实调用执行完成、正文写入真实生成结果时，更新了 README 头部摘要与下文详细结果表格，但**遗漏了第 10–20 行阶段一草稿对比表中的本批次列**。
  3. 实际事实依据：`model-call-ledger.json`（8 次真实记录）、`raw/real/generation-results.json`（8 份逐题真实回答）、`checks/real-run-receipt.txt`（Surefire 控制台 8 题完成回执）及 `checks/git-ci-receipt.json` 完全一致证明真实生成已完整执行。
- **勘误认定**：
  该对比表中的 `0 次（等待授权）` 属于**阶段一预检阶段的未更新遗留草稿内容**，事实状态以正文“真实生成结果”及账本事实为准。

---

### 现象二：`generation-results.json` 的 `qualityVerdict` 与后续回执/报告不一致

- **现象描述**：
  - `generation-results.json`（以及 `model-call-ledger.json`）中记录 `"qualityVerdict": "REQUIRES_PER_QUESTION_REVIEW"`。
  - 而 README、`checks/git-ci-receipt.json`、交付报告 `docs/archive/reports/p10-real-generation-02-delivery-20261026.md` 中记录的模型质量裁决为 `PASS_WITH_SCORER_LIMITATIONS`。
- **证据核查与根因**：
  1. 查阅独立构建脚本 `build-real-evidence.cjs` 第 183–195 行：
     ```javascript
     const completed = results.filter(r => r.status === 'EXECUTED').length;
     const verdicts = {
       generationVerdict: transportAttempts ? 'REAL_GENERATION_EXECUTED' : 'REAL_GENERATION_NOT_EXECUTED',
       executionVerdict: failures ? 'STOPPED_ON_REQUEST_FAILURE' : completed === 8 ? 'MEASUREMENT_EXECUTED' : 'LIMITED_EXECUTION',
       qualityVerdict: completed ? 'REQUIRES_PER_QUESTION_REVIEW' : 'NOT_ASSESSABLE_NO_MODEL_RESPONSE',
     };
     ```
  2. 脚本在完成 8 题测试数据采集组装时，其 `qualityVerdict` 被硬编码为 `completed ? 'REQUIRES_PER_QUESTION_REVIEW' : ...`。这是因为自动化脚本无法自行判定模型语义，且此时 `independentHumanReviewed: 0`，因此**自动化采集阶段的原始机械测量状态必须是 `REQUIRES_PER_QUESTION_REVIEW`**（需要逐题复核）。
  3. 随后，在阶段二人工/语义复核环节中（详见 `manual-review.md`），编码助手对机械评分器报出的 3 项高严重度信号逐条审查，证实全部为评分器字面包含缺陷（AQ06 粗体否定、AQ12 否定从句与冻结拒答词库未收录），模型本身无事实错误。
  4. 随后在汇总提交文档（README、交付报告、`checks/git-ci-receipt.json`）时，给出了**经语义复核后的最终综合裁决：`PASS_WITH_SCORER_LIMITATIONS`**。
- **勘误认定**：
  两者并非数据冲突，而是代表了**不同评审阶段的结论状态**：
  - `REQUIRES_PER_QUESTION_REVIEW`：**自动化工具链机械测量阶段状态**（反映评分器存在未决信号，需进入人工审查）；
  - `PASS_WITH_SCORER_LIMITATIONS`：**人工/语义复核完成后的最终质量裁决**（确认模型回答保真，信号归因为评分器局限）。
  两者分别在原始 JSON 账本与汇总报告中保留，记录了完整的审计演进链。

---

## 3. 字段审计与未决声明（UNRESOLVED 清单）

| 字段 / 属性 | P10-02 原始记录值 | 审计认定状态 | 说明依据 |
| --- | --- | --- | --- |
| 真实调用次数与响应数 | 8 次请求 / 8 份回答 | 已核实事实 | 账本、Surefire 回执、落库记录逐字节印证 |
| 执行裁决 | `MEASUREMENT_EXECUTED` | 已核实事实 | 8 题全部完成，无中断与错误 |
| 机械测量阶段质量状态 | `REQUIRES_PER_QUESTION_REVIEW` | 已核实事实（阶段状态） | `build-real-evidence.cjs` 原始构建固定产物 |
| 复核后最终质量裁决 | `PASS_WITH_SCORER_LIMITATIONS` | 已核实事实（阶段状态） | 经 `manual-review.md` 逐题语义复核后的裁决 |
| 独立第三方人工复核 | 0 题 / `NOT_STARTED` | 已核实事实 | 语义分析由编码助手完成，不作为独立第三方专家验证 |
| Provider 账号计费状态 | `UNAVAILABLE` | **UNRESOLVED** | 环境无 Web 访问，无外部账单查询接口，无法独立核实计费情况 |
| 真实请求实际费用 | `UNAVAILABLE` | **UNRESOLVED** | Provider 未返回计费账单，不使用公式推算作为实测值 |

---

## 4. 结论与指引

本 Errata 明确了 P10-02 证据链的真实演进过程。由于 P10-02 证据已冻结封存，本 Errata 作为独立补充文档存放于 `docs/acceptance-evidence/2026-10-26/p10-real-generation-02-errata/`，供后续评审与审计查阅。
