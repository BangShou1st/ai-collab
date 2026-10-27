# P10-REAL-GENERATION-03 交付报告

## 交付范围

完成 QA 与 Agent 的扩展真实生成测量、评分器离线回放、裁判来源输入审计和复核证据归档。新增内容限于评测测试、冻结夹具、评测辅助脚本、文档与证据文件，没有修改生产代码或已发布数据库迁移。

## 主要结果

- QA：14 题全量加 3 次重复，共 17 次真实 Gemini `gemini-3.5-flash-lite` 请求，17/17 收到响应；输入 14,383 tokens、输出 1,740 tokens。
- Agent：20 个冻结场景，17 成功、3 个因 Kilo 免费模型截断失败；共 53 次模型调用，49 次收到 HTTP 响应、4 次失败或结果未知。来源身份校验共 219 次；每个场景前后业务表行数均为 0。
- 评分器 v6 离线回放：34 份有效回答，高严重度信号 0 项，`needsHumanReview` 41 项。
- 原模型裁判各判读 34 条，但来源审计确认每路仅 12 条输入带齐来源；5 条 QA 缺来源、17 条成功 Agent 没有来源。受影响的 22 条旧结论不作为质量证据。修正后的 Qwen2.5 7B 本地批次得到 22/22 条结构化判读。Qwen3.5 4B 补充批次受截断影响，详见证据目录。
- 独立人类复核为 0。语义复核结论均标记为编码助手复核或模型辅助结果，不升格为独立人工验收。
- 实际计费回执与 Agent 完整 Token 总量不可得，费用均记为 `UNAVAILABLE`，没有估算。

## 验证

Windows Maven wrapper 完整后端 `verify` 通过：1906 tests，0 failures，0 errors，43 skipped；新增 6 项 Agent/QA 授权门禁单测通过。真实 P10 生成类按默认门禁跳过，默认验证期间外部模型调用数为 0。前端 lint、typecheck、409 项测试和 build 均通过；lint 报告 76 条 warning，build 有大 chunk/注释 warning。首轮 WSL 后端验证因 Docker Desktop WSL 集成不可用而失败的记录保存在验收证据的 `validation-results.md`。

## Git 与 CI

工作分支为 `dev`。现有 PR #18（`dev` → `main`）仍为开放草稿，不执行合并。当前任务的提交与推送及对应 CI 运行结果将在推送后补记。

## 证据

- [完整运行证据与文件索引](../../acceptance-evidence/2026-10-27/p10-real-generation-03/README.md)
- [模型调用账本](../../acceptance-evidence/2026-10-27/p10-real-generation-03/model-call-ledger.json)
- [裁判输入覆盖审计](../../acceptance-evidence/2026-10-27/p10-real-generation-03/judge/source-coverage-audit.json)
- [本地验证结果](../../acceptance-evidence/2026-10-27/p10-real-generation-03/validation-results.md)
