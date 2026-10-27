# P10-REAL-GENERATION-03 验收证据

本目录记录 2026-10-27 的扩展真实生成与 Agent 端到端评测。真实模型请求只使用用户事先授权的路由；实际费用回执不可得，因此费用记录为 `UNAVAILABLE`，不作推算。

## 结果摘要

- QA：冻结 14 题全量加 3 次重复，共 17 次真实 Chat，17 次均收到 HTTP 响应；prompt 14,383 tokens、completion 1,740 tokens；无重试。
- Agent：20 个场景，17 个成功、3 个因模型输出截断失败；共 53 次模型调用，49 次收到 HTTP 响应，4 次失败或结果未知；4 次响应缺 usage，费用与完整 Token 总量不可得。
- 评分器 v6 离线回放：34 份有效回答，高严重度信号 0 项，`needsHumanReview` 41 项。逐项语义复核由编码助手完成，独立人类复核为 0 项。
- 原模型辅助裁判批次的来源覆盖审计发现：每个裁判的 34 条已判读结果中，仅 12 条 QA 输入含有相应来源；5 条 QA 缺来源、17 条成功 Agent 输入完全没有来源。原批次中这 22 条的语义结论无效，详见 `judge/source-coverage-audit.json`。
- 修正后的本地 Qwen2.5 7B 批次对 5 条缺源 QA 与 17 条成功 Agent 共输出 22 条结构化判读。Qwen3.5 4B 的 2,048-token 批次 4/4 截断；提高输出预算后运行 13/22 条，其中 5 条有效、4 条 JSON 不完整、4 条空截断，按停止规则结束，余下 9 条未运行；无重试、无外部 API。模型判读仅作辅助证据，不替代独立复核。

## 文件导航

| 路径 | 内容 |
| --- | --- |
| `evaluation-plan.md` | 冻结的评测协议和批次边界 |
| `question-manifest.json` | QA 题目、Agent 场景及测量 ID |
| `generation-results.json`、`agent-execution-results.json` | QA 与 Agent 全量结果副本 |
| `model-call-ledger.json` | 外部与本地模型请求、失败、Token 与费用账本 |
| `scorer-results.json` | 34 份回答的权威机械回放评分 |
| `judge-results.json`、`judge/` | 裁判输入覆盖审计、原批次摘要和修正后的本地复核 |
| `manual-review.md` | 编码助手逐项复核；明确标注独立人类复核为 0 |
| `failure-analysis.md`、`comparison-report.md` | 失败归因、对比与结论边界 |
| `validation-results.md` | 本地后端/前端验证结果及 Docker/Testcontainers 限制 |
| `raw/` | QA、Agent 的原始运行结果、检索上下文和模型调用账本 |
| `frozen-inputs.sha256`、`scorer-and-corpus.sha256`、`manifest.sha256` | 冻结输入、评分器/语料和本目录文件的 SHA-256 |

## 可复核边界

QA 与 Agent 使用不同的生产模型路由，不能把两条路径的差异单独归因于产品机制。Agent 有 3 个截断失败场景，不纳入有效回答质量分母。人工复核文档中的结论属于编码助手复核；原始模型裁判批次存在输入缺陷，修正后的本地裁判输出也可能误判。实际费用、Agent 完整 Token 总量和独立人类验收均不可得。

裁判脚本不包含个人 key 文件路径或固定代理端口；Gemini key 文件路径和免费用量确认都必须显式通过环境提供，裁判模型使用固定 allowlist。

`frozen-inputs.sha256`、`scorer-and-corpus.sha256` 和 `manifest.sha256` 使用仓库根目录相对路径；总清单覆盖本目录内全部文件，但不对清单自身做递归哈希。
