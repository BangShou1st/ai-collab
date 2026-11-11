# decide 冻结集（W4 / P2）

`decide_baseline.v1.jsonl`：`ai-eval decide` 的冻结评测集，口径见
`docs/archive/handoffs/w4-decide-freeze-20261112.md` §5（该冻结文档在 `dev` 主工作树，
本分支按纪律未复制）。

## 1. 诚实声明：stub 输出，不是真实模型采样

`modelOutput` 是**冻结的代表性模型输出（stub 标注）**，用于让 `--scorer replay` 在无网络、
无真实模型的条件下走一遍真实的解析/校验核心。它**不声称**来自任何真实 Provider，也**不能**
用来证明设计 §10.2 的一致率 ≥98% / JSON 合法率 ≥99.5%：

- replay 报告固定标 `evidence=TESTED (stub)` + `confidence=LOW_CONFIDENCE`（32 条 < 200）+
  `qualityClaim=NOT_VERIFIED`（无真实模型，明确不宣称目标）；
- 顶层 `decisionAgreement` / `jsonValidityRate` 的分母是**全体 32 条**，其中 12 条是刻意非法
  负样本；**目标评估看 `aggregate.bySubset.golden`**，不要直接拿顶层数值对照 98%/99.5%；
- 这些数值只证明**契约与评测管线正确**（合法样本全部通过、负样本全部被拒绝），
  **不证明真实模型质量**；真实模型结论只能在 staging 用 `--scorer worker` 产生，本波不可达
  → `NOT_VERIFIED`。

## 2. 每条 case 的字段

| 字段 | 含义 |
| --- | --- |
| `id` | 全局唯一；报告按 id 的 Unicode 码点升序输出 |
| `subset` | **必填**：`golden`（正常样本，20 条）或 `adversarial`（刻意非法负样本，12 条）；缺失/非法即加载失败 |
| `scenario` | `{code, category}`，与 `POST /v1/agent/decide` 的请求字段一致 |
| `goal` / `locale` | 同请求契约（goal 1–4000、locale 2–35） |
| `context` | `{facts, messages, observations}` 三段有界上下文 |
| `tools` | 本次下发工具（含 1 个 `WRITE` 风险工具，用于验证 Python 不据此扩权） |
| `policy` | `{allowWrite, allowExternal}` |
| `budget` | `{maxInputTokens, maxOutputTokens, deadlineMs}` |
| `expected` | **人工标注**的正确决策：`{decision, toolName?}`；CALL_TOOL 必须给 toolName |
| `modelOutput` | 冻结的模型输出字符串（stub） |

`runId/attempt/turnNo/snapshotHash` 不在 case 里：worker 打分时由 case id 合成稳定身份
（`eval-decide-<id>`、`attempt=1`、`turnNo=0`、`sha256:<32 hex>`），保证同一冻结集在任意机器上
请求体一致。

## 3. 覆盖矩阵（32 条 = 20 golden + 12 adversarial）

`subset` 按“输出是否应当被解析/校验核心接受”划分：`d-001`–`d-020` 是 golden（正常输出），
`d-021`–`d-032` 是 adversarial（越界工具名/非法 JSON/缺字段/参数非 object/决策违规）。

| 类别 | 覆盖 |
| --- | --- |
| 四决策 | CALL_TOOL / FINAL / ASK_USER / NO_OP 全部有合法样本 |
| 合法 CALL_TOOL | `search_project_knowledge`、`get_document_metadata`、`get_project_snapshot` |
| 越界工具名 | `delete_all_tasks`、`search_database`（adversarial，期望被拒绝 + invalidToolCalls） |
| 非法 JSON | 截断 JSON、自然语言解释（非 JSON） |
| 缺字段 | 缺 `decision`、CALL_TOOL 缺 `toolCall`、toolCall 缺 `name` |
| 参数非 object | `arguments` 为数组 / 字符串 |
| 决策违规 | 未知 decision `DELEGATE`、非 CALL_TOOL 决策携带 `toolCall` |
| 语言 | 中文、英文（en-US / en-GB） |
| 上下文 | 空上下文、20/20/20 最大条目数、约 22K 字符的长上下文（ASCII，避免 UTF-8 字节超 64 KiB） |
| 提示注入 | 观测摘要与事实里内嵌“忽略规则”文本（只作为数据） |
| 围栏与冗余键 | ```json 围栏、额外 `reasoning` 键（应被容忍） |
| 场景 | `PROJECT_RESEARCH/SCENARIO`、`WEEKLY_REPORT/WORKFLOW` |

## 4. 用法与基线

```bash
cd ai-collab-ai-worker
# 默认 replay：进程内跑真实解析/校验核心，零网络
uv run ai-eval decide tests/fixtures/decide_set/decide_baseline.v1.jsonl --json
# worker：打真实 worker（需要内部服务令牌环境变量，默认 AI_WORKER_SERVICE_TOKEN）
uv run ai-eval decide tests/fixtures/decide_set/decide_baseline.v1.jsonl \
  --scorer worker --worker-url http://localhost:9100 --json
```

本冻结集在 replay 下的期望值（同一次提交内固定；两次运行逐字节一致）：

**顶层（全体分母，含 12 条刻意负样本；不要直接对照 §10.2 目标）**

| 指标 | 值 | 说明 |
| --- | --- | --- |
| `cases` | 32 | 全体 |
| `decisionAgreement` | 0.625 | 20 条 golden 全部命中标注；12 条 adversarial 不产生合法决策 |
| `jsonValidityRate` | 0.625 | “可解析且契约合法”的 20/32 |
| `invalidToolCalls` | 2 | 两条越界工具名（adversarial 负样本，不是生产基线） |
| `schemaViolations` | 10 | 解析出 JSON 但契约违规；另 2 条是非法 JSON |
| `confidence` | `LOW_CONFIDENCE` | 32 < 200 样本阈值 |
| `qualityClaim` | `NOT_VERIFIED` | 无真实模型，不宣称目标 |

**`aggregate.bySubset`（目标评估看 golden）**

| 子集 | 指标 | 值 | 说明 |
| --- | --- | --- | --- |
| `golden` | `cases` | 20 | 正常样本 |
| `golden` | `decisionAgreement` | 1.0 | 20/20 决策与工具名全部一致 |
| `golden` | `jsonValidityRate` | 1.0 | 20/20 可解析且契约合法 |
| `golden` | `schemaViolations` | 0 | 无契约违规 |
| `adversarial` | `cases` | 12 | 刻意非法负样本 |
| `adversarial` | `rejectedRate` | 1.0 | 12/12 被解析/校验核心拒绝（含非法 JSON 与契约违规） |
| `adversarial` | `invalidToolCalls` | 2 | 2 条返回了不在 tools 内的工具名 |

`invalidToolCalls=2` **不是**“无效工具调用不增”目标的违背：这里统计的是冻结集里刻意构造的
越界工具名负样本。上述数值只证明**契约与评测管线正确**（golden 全过、adversarial 全拒），
**不证明真实模型质量**；真实模型的无效工具调用率/一致率只能在 staging 由
`--scorer worker` 观测。

## 5. 指纹与变更纪律

- 数据集 canonical 指纹（`ai-eval fingerprint --json`）：
  `sha256=1cd743bab6e48fb46f99776fcad558af62bcbfbe0a44cdb25be157c233694a58`，32 条。
- 报告自带 `sha256`（`canonical(报告去掉 sha256 键)` 的 SHA-256）；同一输入两次运行必须逐字节一致。
- 冻结集**只加不删**：需要新样本时新增 `decide_baseline.v2.jsonl` 并在报告中说明；不要原地改
  已冻结的条目，否则历史报告无法复算（本次补 `subset` 字段属于报告口径修正，已知会改变指纹）。
