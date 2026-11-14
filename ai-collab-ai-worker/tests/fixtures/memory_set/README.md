# memory 冻结集（W5 / P3）

`memory_baseline.v1.jsonl`：`ai-eval memory` 的冻结评测集，口径见
`docs/archive/handoffs/w5-planning-memory-freeze-20261112.md` §4.2（冻结文档在 `dev` 主工作树，
本分支按纪律未复制）。

## 1. 诚实声明：stub 输出，不是真实模型采样

`modelOutput` 是**冻结的代表性候选输出（stub 标注）**，用于让 `ai-eval memory` 在无网络、
无 job token、无真实模型的条件下走一遍真实的候选解析/来源校验/`dedupeKey` 计算核心。
它**不声称**来自任何真实 Provider，也**不能**证明"影子候选与人工判断一致率达标"：

- 报告固定标 `evidence=TESTED (stub)` + `confidence=LOW_CONFIDENCE`（14 条 < 200）+ `qualityClaim=NOT_VERIFIED`；
- 这些数值只证明**契约与评测管线正确**（来源非法/`dedupeKey` 不一致被丢弃、重复可识别），
  **不证明真实模型质量**；真实模型的一致率/重复率只能在 staging 由 Java shadow 链路观测；
- 报告里的 `limitations` 字段逐字写着这一点，不允许删。

## 2. 每条 case 的字段

| 字段 | 含义 |
| --- | --- |
| `id` | 全局唯一；报告按 id 的 Unicode 码点升序输出 |
| `snapshot` | 与 `GET /internal/ai/v1/jobs/{id}/snapshot` 同形状：`{schemaVersion, jobId, projectId, attempt, leaseEpoch, snapshotHash, facts[], existingMemories[]}` |
| `modelOutput` | 冻结的模型输出字符串（stub）；`{"candidates":[…]}`，允许围栏与顶层多余键 |
| `humanJudgement` | 人工判断"应当提取"的候选集合：`[{kind, title}]`，标题级匹配（NFKC + casefold + 空白折叠） |

候选的 `dedupeKey` 不在冻结集里：它由 `cognition.memory.parser.compute_dedupe_key` 计算
（`sha256(kind|normalized(title)|normalized(content))`），与在线行为同源。冻结集里只有
`m-005` 故意写了一个错误的 `dedupeKey`（`0`×64），用于验证"编造幂等键的候选被丢弃"。

## 3. 覆盖矩阵（14 条）

| 类别 | 覆盖 |
| --- | --- |
| 四种 kind | DECISION / PREFERENCE / CONSTRAINT / LESSON 全部出现 |
| 精确率下降 | `m-002` 多给一条正确但未被人工判断的候选（P=2/3） |
| 召回率下降 | `m-003`、`m-007` 漏提取 / 空候选 |
| 来源非法 | `m-004` 的 `fact-9` 不在快照内 → 丢弃并记 `invalidSource` |
| 幂等键不一致 | `m-005` 的 `dedupeKey` 与重算不一致 → 丢弃并记 `invalidDedupe` |
| 重复 | `m-006` 与既有记忆完全重复（`dedupeKey` 相同）→ `duplicateRate` |
| 归一化 | `m-008` 首尾空白、`m-009` 全角拉丁字母（NFKC） |
| 来源为既有记忆 | `m-010` 用 `memory:<id>` 作为 `sourceRef` |
| 置信度边界 | `m-011` 的 0 与 1 |
| 输出形状 | `m-012` 围栏、`m-013` 顶层多余键（容忍） |
| 同名不同内容 | `m-014` 与既有记忆同名但 `dedupeKey` 不同 → 不算重复 |

## 4. 用法与基线

```bash
cd ai-collab-ai-worker
uv run ai-eval memory tests/fixtures/memory_set/memory_baseline.v1.jsonl
uv run ai-eval memory tests/fixtures/memory_set/memory_baseline.v1.jsonl --json
```

本冻结集在 replay 下的期望值（同一次提交内固定；两次运行逐字节一致）：

| 指标 | 值 | 说明 |
| --- | --- | --- |
| `cases` | 14 | 全体（全部是契约合法输出，schemaPassRate=1.0） |
| `precision` | 0.9 | 18 个命中 / 20 个提交候选（micro） |
| `recall` | 0.9 | 18 个命中 / 20 条人工判断（micro） |
| `candidateAgreement` | 0.9 | precision 与 recall 的调和平均（F1） |
| `duplicateRate` | 0.05 | 1 个与既有记忆 `dedupeKey` 相同 / 20 个候选 |
| `schemaPassRate` | 1.0 | 14 条输出都可解析为候选契约 |
| `invalidSource` | 1 | 来源不在快照内被丢弃的候选数 |
| `invalidDedupe` | 1 | `dedupeKey` 与重算不一致被丢弃的候选数 |
| `confidence` | `LOW_CONFIDENCE` | 14 < 200 样本阈值 |
| `qualityClaim` | `NOT_VERIFIED` | 无真实模型，不宣称目标 |

## 5. 指纹与变更纪律

- 数据集 canonical 指纹（`ai-eval fingerprint --json`）：
  `sha256=079698767e5ebc4ad50f915bd7794bc21b6dd01a3b2e9a776633dc71d60f603a`，14 条，13810 字节。
- 报告自带 `sha256`：本次为
  `74177ebe90cdf98881474382c02d1ac08ebdef0deb970c4c6aaa81bd102fad33`；同一输入两次运行必须逐字节一致。
- 冻结集**只加不删**：需要新样本时新增 `memory_baseline.v2.jsonl` 并在报告中说明；不要原地改
  已冻结的条目。

## 6. v2 判定集（D7-A）：只加不删 + 显式 factRefs

`memory_baseline.v2.jsonl` 是 v1 的**可审计修复版**，不是替换：

- 14 条 case 全部保留，20 条 judgement 一条不删；
- 每条 judgement 增加显式 `factRefs`，指向同一个 case 的 `snapshot.facts` 中真实存在的 ref；
- `m-005` / `m-009` / `m-010` 原先的 judgement 在 facts 中没有支撑，v2 **只新增**
  `fact-4`（写审批 / API 契约 / Python 不写业务表），不删任何既有 fact；
- v1 仍原样保留，指纹
  `079698767e5ebc4ad50f915bd7794bc21b6dd01a3b2e9a776633dc71d60f603a`（13810 字节）不变。

v2 指纹：`73f3ef790c6d95623494bb0f84823b39e0196c579c42eade21a8c6d14c5c98a0`
（14 条，14537 字节）。v2 在 `--matcher exact` 下的报告与 v1 **逐字节相同**
（`sha256=74177ebe90cdf98881474382c02d1ac08ebdef0deb970c4c6aaa81bd102fad33`），
因为 factRefs/facts 不进入旧报告；精确率/召回率仍为 0.9 / 0.9。

`factRefs` 校验规则（loader 强制）：

- 可选：v1 不写 `factRefs` 仍可加载；
- 一旦出现必须是非空、去重的字符串数组；
- 每个 ref 必须存在（否则退出码 2，错误信息含“不存在的事实”）；
- 不引用 `memory:<id>`：判定支撑只认 `snapshot.facts`。

生成脚本：`tests/fixtures/memory_set/tools/build_fixtures.py`（只读 v1 + S3 归档，无时间戳/随机数）。

## 7. 真实模型 replay 集（D7-A）

`memory_real_gemini31.v1.jsonl`：snapshot / judgement 来自 v2，`modelOutput` 来自
S3 归档 `raw/memory-cases.jsonl` 的 14 条真实 `gemini-3.1-flash-lite` 输出。**这是
replay：输入是归档真实模型输出，本次没有重新调用模型**。

- 指纹：`5a317baa2b28efb127f4e11d3fe22c065e3708e5c17e3e60efb60431a0370151`
  （14 条，18608 字节）；
- `--matcher exact`：`precision=0.00`、`recall=0.00`、`duplicateRate=0.00`，
  报告 `sha256=d5e2abe21070fc219d1be4c0f1d74a7e011c62f31c414e21e4be0359575c3ac2`
  ——与 S3 归档 `aggregate-memory.json` 一致，证明 replay 输入/口径未漂移；
- `--matcher semantic`：`precision=5/33≈0.151515`、`recall=5/20=0.25`、
  `candidateAgreement≈0.188679`、`duplicateRate=0.00`；5 条审计命中为
  m-001 / m-003 / m-014（检索链路“走/至”改写）与 m-011 / m-013（“必须/必须经过”改写）；
- 运行时必须显式标注证据，例如：

  ```bash
  uv run ai-eval memory tests/fixtures/memory_set/memory_real_gemini31.v1.jsonl \
    --matcher semantic \
    --evidence "REPLAY (archived real model output)" \
    --quality-claim NOT_VERIFIED \
    --model gemini-3.1-flash-lite --json
  ```

- **precision / recall 一律是诊断指标**：不设门禁、不写成 PASS/FAIL；样本 14 条 < 200，
  仍为 `LOW_CONFIDENCE`。semantic 只改善同义改写的标题匹配，不会把无事实支撑的判定
  变成可召回；m-006 的既有记忆重复抑制仍是模型行为问题。

## 8. semantic 匹配口径（D7-A）

先精确匹配（NFKC + casefold + 空白折叠 + kind 相同）；未命中时要求**同时**满足：

1. `difflib.SequenceMatcher` 归一化相似度 `>= 0.82`；
2. judgement 标题的字符 bigram 在 candidate 标题中的包含率 `>= 0.80`；
3. kind 相同。

阈值来自可复核的间隔：真实同义改写 0.90–0.9333 通过；近似但含义不同/数字相反的标题
（“审批”vs“复核” 0.7778；“两周”vs“三周” 0.8889 但包含率 0.75；“需要”vs“无需”
0.8421 但包含率 0.75）被拒绝。命中审计写在 `matchAudit`（caseId / candidateTitle /
judgementTitle / method / score），只有 `--matcher semantic` 才输出。

## 9. 变更纪律（v2 之后）

- `memory_baseline.v1.jsonl` / `memory_baseline.v2.jsonl` 均为冻结文件，不得原地改；
- 新样本另建 `memory_baseline.v3.jsonl`，并在本 README 登记指纹与期望值；
- `memory_real_gemini31.v1.jsonl` 只由归档真实输出重建；不要手工改 `modelOutput`。
