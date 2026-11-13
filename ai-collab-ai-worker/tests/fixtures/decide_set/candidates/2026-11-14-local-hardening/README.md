# decide 候选集：2026-11-14 本地加固

> 状态：**PENDING_REVIEW**
> 性质：中心复核候选，不是冻结集，不是默认质量门禁，不具备 `QUALITY_OK` 资格。

本目录为 F1 lane 的 80 条 decide 候选，目标是把 v1 的 32 条样本扩展到 50–100 条，
同时避免候选被误当成已审标签或直接覆盖 v1/v2/v3。

## 文件

| 文件 | 内容 |
| --- | --- |
| `golden.v1.jsonl` | 64 条正常决策候选；只有建议标签，没有 `expected`/`modelOutput` |
| `adversarial.v1.jsonl` | 16 条待审契约拒绝 probe；仍为 `PENDING_REVIEW` |
| `manifest.json` | 数量、决策/拒绝分布、来源文件哈希、review policy |
| `tools/build_candidates_20261114.py` | 确定性生成器；不调用模型 |
| `tools/validate_candidates_20261114.py` | 候选 schema、请求契约、标签与 probe 校验器 |

历史输入只读：

| 文件 | 当前 raw SHA-256 |
| --- | --- |
| `decide_baseline.v1.jsonl` | `144439dcb0998cceef549c13bb87720ebe0d5fa16072649f2738444a00a91413` |
| `decide_baseline.v2.jsonl` | `6b74c9325964d9a0c5a5f18e995da73483683fbb89b8ff1f683972909998fd2b` |
| `decide_baseline.v3.jsonl` | `68a66d8a42a2171e3ab8a88e0b93fd77cf621921167b3ef569419fee20b0dd5a` |
| `decide_real_gemini31.v1.jsonl` | `ec35ac978446b898b87c6e1566efe9799f4132c76126845e8292e6ed66fec0ba` |

## 候选 Schema

每条记录固定包含：

| 字段 | 含义 |
| --- | --- |
| `id` | `cand-20261114-NNN`，全局唯一 |
| `reviewStatus` | 固定 `PENDING_REVIEW` |
| `suggestedSubset` | `golden` 或 `adversarial`，仅为建议 |
| `suggestedLabel` | golden 为建议决策；adversarial 为建议拒绝原因 |
| `request` | 与 `/v1/agent/decide` 请求字段同构；已做 Pydantic 契约校验 |
| `source` | 来源类型、可解析引用与 rationale |
| `coverage` | 覆盖 tags 与审查说明 |
| `review` | `reviewer/reviewedAt/notes` 留空，等待人工复核 |

adversarial 额外含 `probe.candidateOutput` 与 `probe.expectedRejectReason`，用于复核
解析/校验核心是否按预期拒绝；这不是已冻结的 `modelOutput`。

候选没有顶层 `expected` 或 `modelOutput`；冻结必须另经人工复核、补全模型输出、生成
新版本文件并获批，禁止原地修改 v1/v2/v3。

## 数量与分布

- 总计 **80** 条：64 golden + 16 adversarial。
- 建议决策：`CALL_TOOL` 42、`FINAL` 8、`ASK_USER` 6、`NO_OP` 8。
- 建议工具：`search_project_knowledge` 16、`get_document_metadata` 12、
  `get_project_snapshot` 10、`create_task` 4；其余 22 条为非工具决策。
- locale 9 种：`zh-CN`、`en-US`、`en-GB`、`ja-JP`、`fr-FR`、`de-DE`、`es-ES`、
  `pt-BR`、`ko-KR`。
- 场景 15 种：包含 `PROJECT_RESEARCH`、`PROJECT_HEALTH`、`RISK_REVIEW`、
  `WEEKLY_REPORT`、`MEETING_TO_TASKS`、`ITERATION_PLANNING`、`RELEASE_REVIEW`、
  `DOCUMENT_METADATA_CHECK`、`DEPENDENCY_PLANNING`、`TASK_TRIAGE` 等。

## 来源结构

| 来源类型 | 数量 | 说明 |
| --- | ---: | --- |
| `FROZEN_V1_VARIANT` | 39 | 从 v1 已有 case 的建议决策/工具形态派生，改写场景或边界 |
| `LIVE_ROUND5_REFERENCE` | 1 | 引用 W4 R5 真实运行决策，但只作为新候选来源 |
| `SYNTHETIC_COVERAGE_GAP` | 30 | 明确补 v1 未覆盖的 locale、场景、工具子集或边界 |
| `DESIGN_REFERENCE` | 10 | 引用本地加固设计与 W4 冻结纪律，重点覆盖审批/注入边界 |

来源引用必须能解析到本仓库文件；具体 case id 以 `#` 后缀登记。

## 覆盖矩阵

| 维度 | 覆盖 |
| --- | --- |
| 四决策 | `CALL_TOOL` / `FINAL` / `ASK_USER` / `NO_OP` |
| 工具选择 | 三只只读工具 + `create_task` 写工具（仅 4 条 `allowWrite=true`） |
| 工具子集 | 三工具、两工具、单工具、仅写工具 |
| 上下文边界 | 空上下文；20 facts；20 messages；20 observations；引号与换行 |
| 安全 | 观测提示注入、JSON 样式数据、allowWrite=false 时写工具可见、审批前置 |
| 歧义/拒绝 | 缺项目范围、缺文档 ID、缺时间范围、缺任务 ID、冲突动作、多任务 |
| 请求级负样本 | 未知工具、非法 decision、缺 decision、缺 toolCall、非法 arguments、意外 toolCall、非法 JSON |
| 语言 | 中文、英文（US/UK）、日语、法语、德语、西班牙语、葡萄牙语、韩语 |
| 工作流 | 研究、周报、会议转任务、迭代规划、发布评审、交付、复盘、任务分诊 |

覆盖 tags 的具体条目见每条候选的 `coverage.tags`；校验器要求 21 个关键 tag 全部存在。

## 人工复核清单

- [ ] 复核 `request.goal` 与 `context` 是否足以支撑 `suggestedLabel`。
- [ ] 复核 `CALL_TOOL` 的工具在本次 `tools` 内；写工具仅在 `allowWrite=true` 时建议。
- [ ] 复核 `FINAL` 的依据确实在 facts/messages 中，不要求模型编造事实。
- [ ] 复核 `ASK_USER` 的歧义无法从上下文消解。
- [ ] 复核 `NO_OP` 不掩盖本应执行的审批或查询。
- [ ] 复核 adversarial probe 的预期拒绝原因与解析器实际原因一致。
- [ ] 复核来源引用真实存在；合成样本写明缺口，不冒充真实历史。
- [ ] 人工填写 `review.reviewer/reviewedAt/notes`，再决定是否进入冻结流程。
- [ ] 冻结另建版本文件并单独审批；不得改写 v1/v2/v3 或本候选状态冒充已冻结。
- [ ] 未经复核不得把本目录用于质量门禁、shadow 一致率或生产默认切换依据。

## 校验命令

```powershell
cd ai-collab-ai-worker
uv sync --frozen
uv run python tests/fixtures/decide_set/candidates/2026-11-14-local-hardening/tools/build_candidates_20261114.py --check
uv run python tests/fixtures/decide_set/candidates/2026-11-14-local-hardening/tools/validate_candidates_20261114.py --json
uv run pytest -q tests/unit/test_decide_candidates_20261114.py
```

本候选集由确定性脚本编写，不调用 Magpie 或其它模型；因此没有“真实模型生成”或
“模型质量”声明。
