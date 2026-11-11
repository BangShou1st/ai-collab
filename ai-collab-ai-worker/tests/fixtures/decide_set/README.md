# decide 冻结集（W4 / P2）

`decide_baseline.v1.jsonl`：`ai-eval decide` 的冻结评测集，口径见
`docs/archive/handoffs/w4-decide-freeze-20261112.md` §5（该冻结文档在 `dev` 主工作树，
本分支按纪律未复制）。

## 1. 诚实声明：stub 输出，不是真实模型采样

`modelOutput` 是**冻结的代表性模型输出（stub 标注）**，用于让 `--scorer replay` 在无网络、
无真实模型的条件下走一遍真实的解析/校验核心。它**不声称**来自任何真实 Provider，也**不能**
用来证明设计 §10.2 的一致率 ≥98% / JSON 合法率 ≥99.5%：

- replay 报告固定标 `evidence=TESTED (stub)` + `confidence=LOW_CONFIDENCE`（32 条 < 200）；
- 真实模型结论只能在 staging 用 `--scorer worker` 产生，本波不可达 → `NOT_VERIFIED`。

## 2. 每条 case 的字段

| 字段 | 含义 |
| --- | --- |
| `id` | 全局唯一；报告按 id 的 Unicode 码点升序输出 |
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

## 3. 覆盖矩阵（32 条）

| 类别 | 覆盖 |
| --- | --- |
| 四决策 | CALL_TOOL / FINAL / ASK_USER / NO_OP 全部有合法样本 |
| 合法 CALL_TOOL | `search_project_knowledge`、`get_document_metadata`、`get_project_snapshot` |
| 越界工具名 | `delete_all_tasks`、`search_database`（期望 422 + invalidToolCalls） |
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

| 指标 | 值 | 说明 |
| --- | --- | --- |
| `cases` | 32 | |
| `decisionAgreement` | 0.625 | 20 条合法输出全部命中标注；12 条故意非法 |
| `jsonValidityRate` | 0.625 | “可解析且契约合法”的 20/32 |
| `invalidToolCalls` | 2 | 两条越界工具名（负样本，用于验证检测能力，不是生产基线） |
| `schemaViolations` | 10 | 解析出 JSON 但契约违规；另 2 条是非法 JSON |
| `confidence` | `LOW_CONFIDENCE` | 32 < 200 样本阈值 |

`invalidToolCalls=2` **不是**“无效工具调用不增”目标的违背：这里统计的是冻结集里刻意构造的
越界工具名负样本；真实模型的无效工具调用率只能在 staging 由 `--scorer worker` 观测。

## 5. 指纹与变更纪律

- 数据集 canonical 指纹（`ai-eval fingerprint --json`）：
  `sha256=6f3595f9abcef3c4ea0b36109d540d94ef2a0e8aabfc788b7c364fa5c774c4c2`，32 条。
- 报告自带 `sha256`（`canonical(报告去掉 sha256 键)` 的 SHA-256）；同一输入两次运行必须逐字节一致。
- 冻结集**只加不删**：需要新样本时新增 `decide_baseline.v2.jsonl` 并在报告中说明；不要原地改
  已冻结的条目，否则历史报告无法复算。
