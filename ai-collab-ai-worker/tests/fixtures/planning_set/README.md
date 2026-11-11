# planning 冻结集（W5 / P3）

`planning_baseline.v1.jsonl`：`ai-eval planning` 的冻结评测集，口径见
`docs/archive/handoffs/w5-planning-memory-freeze-20261112.md` §4.1（冻结文档在 `dev` 主工作树，
本分支按纪律未复制）。

## 1. 诚实声明：stub 输出，不是真实模型采样

`modelOutput` 是**冻结的代表性模型输出（stub 标注）**，用于让 `ai-eval planning` 在无网络、
无真实模型的条件下走一遍真实的解析 + 自校验核心。它**不声称**来自任何真实 Provider，也
**不能**用来证明设计 §6.3 P3 的"Schema 校验一次通过率 ≥95%"：

- 报告固定标 `evidence=TESTED (stub)` + `confidence=LOW_CONFIDENCE`（20 条 < 200）+ `qualityClaim=NOT_VERIFIED`；
- 顶层 `schemaPassRate` 的分母是**全体 20 条**，其中 5 条是刻意非法负样本；
  **目标评估看 `aggregate.bySubset.golden.schemaPassRate`**（≥0.95）；
- 这些数值只证明**契约与评测管线正确**（golden 全过、adversarial 全拒），**不证明真实模型质量**；
  真实模型结论只能在 staging 由 Java shadow 链路产生；
- 报告里的 `limitations` 字段逐字写着这一点，不允许删。

## 2. 每条 case 的字段

| 字段 | 含义 |
| --- | --- |
| `id` | 全局唯一；报告按 id 的 Unicode 码点升序输出 |
| `subset` | **必填**：`golden`（正常样本，15 条）或 `adversarial`（刻意非法负样本，5 条） |
| `goal` / `locale` | 同 `POST /v1/planning/draft` 请求契约（goal 1–4000、locale 2–35） |
| `projectFacts` | `{projectName, description, members, existingMilestoneTitles, existingTaskTitles}` |
| `template` | `ITERATION` / `DELIVERY` / `CUSTOM` |
| `constraints` | `{maxTasks(1–40), maxMilestones(1–8), startDate?, dueDate?}` |
| `expected` | **人工标注**：`{shouldPass}`；`note` 可选（只作说明，不参与判定） |
| `modelOutput` | 冻结的模型输出字符串（stub）；允许是围栏包裹的单个 JSON object |

`deadlineMs` 不在 case 里：评测不需要真实调用，请求模型用契约默认值（60000）。

## 3. 覆盖矩阵（20 条 = 15 golden + 5 adversarial）

| 类别 | 覆盖 |
| --- | --- |
| 模板 | ITERATION / DELIVERY / CUSTOM 三个取值都有样本 |
| 数量上界 | `maxTasks=3` 恰好 3 个任务；`maxMilestones=1` 恰好 1 个里程碑 |
| 文本上界 | title 100 字符、objective 500、description 2000 |
| 工时边界 | `estimatedHours` 0.5 与 200（另有多条 `null`） |
| 日期边界 | 起止日期正好等于 `constraints.startDate`/`dueDate` |
| 依赖 | 链式依赖（t1→t2→t3）；adversarial 里有两节点环 |
| 成员 | 越界 `assigneeId` → 置 null + warning；空成员列表 |
| 来源 | 伪造 `sourceRefs`（`fact:unknown`/`doc:42`/`milestone:9`）→ 丢弃并计数 |
| 重名 | 里程碑标题与既有标题重名 → `duplicateTitles` |
| 输出形状 | 无围栏、```json 围栏、字段最小化（只有必填字段） |
| 非法样本 | 悬空 `milestoneTempKey`、依赖环、任务超额、日期越界、`tempKey` 重复 |

## 4. 用法与基线

```bash
cd ai-collab-ai-worker
# replay：进程内跑真实解析 + 自校验核心，零网络、零令牌
uv run ai-eval planning tests/fixtures/planning_set/planning_baseline.v1.jsonl
uv run ai-eval planning tests/fixtures/planning_set/planning_baseline.v1.jsonl --json
```

本冻结集在 replay 下的期望值（同一次提交内固定；两次运行逐字节一致）：

**顶层（全体分母，含 5 条刻意负样本；不要直接对照 ≥95%）**

| 指标 | 值 | 说明 |
| --- | --- | --- |
| `cases` | 20 | 全体 |
| `schemaPassRate` | 0.75 | 15 条 golden 全部通过；5 条 adversarial 全部被拒绝 |
| `sourceViolations` | 3 | 被丢弃的伪造 `sourceRefs` 条数 |
| `assigneeViolations` | 1 | 被置 null 的越界 `assigneeId` 条数 |
| `duplicateTitles` | 1 | 与既有标题/同稿前序条目重名的条数 |
| `expectationMatched` | 20 | `actual.schemaPass == expected.shouldPass` 的 case 数 |
| `confidence` | `LOW_CONFIDENCE` | 20 < 200 样本阈值 |
| `qualityClaim` | `NOT_VERIFIED` | 无真实模型，不宣称目标 |

**`aggregate.bySubset`（目标评估看 golden）**

| 子集 | 指标 | 值 |
| --- | --- | --- |
| `golden` | `cases` | 15 |
| `golden` | `schemaPassRate` | 1.0（≥0.95，但**仅 stub**） |
| `adversarial` | `cases` | 5 |
| `adversarial` | `rejectedRate` | 1.0 |

## 5. 指纹与变更纪律

- 数据集 canonical 指纹（`ai-eval fingerprint --json`）：
  `sha256=b931e4579de4c9f0c1cf2b28430f36263117065c2a53260bfd4ec742789a361a`，20 条，54893 字节。
- 报告自带 `sha256`（`canonical(报告去掉 sha256 键)` 的 SHA-256）：本次为
  `83694b38f34ab1c86411a2859e0d3af60b3cc74a1c21ef520a938aa2541f9b4a`；同一输入两次运行必须逐字节一致。
- 冻结集**只加不删**：需要新样本时新增 `planning_baseline.v2.jsonl` 并在报告中说明；不要原地改
  已冻结的条目，否则历史报告无法复算。
