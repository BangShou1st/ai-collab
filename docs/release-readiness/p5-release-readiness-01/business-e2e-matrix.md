# business-e2e-matrix.md — 跨模块业务闭环 × 历史证据映射

> 证据分级：**REAL**（真实浏览器 + 真实后端 + 真实 DB，部分含真实模型）/ OFFLINE（自动化测试）/ STATIC（源码静态证据）/ GAP（无证据）。
> 原则：不因历史报告写 PASS 就视为永久 PASS——每条闭环都复核了其后代码是否变更（五份 P4 验收的基线均 ≤ 当前 HEAD 前数笔，其后仅 docs-only 提交，证据有效）。

## 闭环矩阵

| # | 闭环 | 用户路径 | 证据等级 | 证据来源 | 断裂点 |
| --- | --- | --- | --- | --- | --- |
| 1 | 项目管理 | 注册/登录 → 创建项目 → 添加成员 → 创建任务 → 修改状态 → 查看结果 | REAL | 2026-10-19 p2-business-e2e-02（真实浏览器全链路）；2026-10-03/10-04 早期浏览器证据（看板/依赖/SQL 断言）；此后协作域代码无破坏性变更 | 无。刷新恢复、权限提示、防重复提交均有覆盖 |
| 2 | 知识库 | 创建项目 → 上传 → 索引 → 问答 → 引用 → 刷新恢复 | REAL | 2026-10-24 p4-knowledge-real-e2e-01（Ollama 真实 Embedding + Gemini 真实生成 + 双来源引用 + 刷新复核）；2026-10-22/23 RAG 质量系列 | 无主链路断裂。Provider 配置体验三缺陷已在 p4-model-quick-connect-02 修复并复验 |
| 3 | AI 规划 | 项目上下文 → 选模型 → 生成骨架→细节 → 审阅 → 确认落库 | REAL（能力链路+历史落库）/ OFFLINE（编辑/版本 UI 细节） | PLANNING_JSON 真实 PASS（p4-agent-lifecycle-real-e2e-02，生产契约 verified=true）；确认落库真实证据为 2026-10-03 real-planning-confirmed.png + p2-business-e2e-02 8 任务确认 | 规划**页面**的编辑/版本保存/部分重生成仅离线测试（feature-inventory §E）。能力链路与落库已 REAL，UI 编辑链路为 GAP——建议 RC 期间补一次真实浏览器规划确认（低成本，无外部模型也可用既有 PLANNING_JSON 快照验证编辑+确认） |
| 4 | Agent 只读协作 | 进项目 → Agent 提问 → 工具查询 → 模型总结 → 持久化 | REAL | p4-agent-real-e2e-01 Run 82f16d75：4 个真实工具调用、结果回流（14,294 token）、回答与 DB 逐条一致、SSE 1–17 对应、刷新恢复 | 无 |
| 5 | Agent 受控写入 | 提案 → 审批 → 确认 → 写入 → 留痕 | REAL | p4-agent-real-e2e-01：批准前零写入（task=3/memory=0）、批准后真实写入 TODO/HIGH、拒绝强制原因 + 0 写入、事件留痕 | UI 侧缺口：非最新 Run 的 PENDING 审批刷新后不可见（R-08，服务端闸门不受影响） |
| 6 | 失败与恢复 | 模型失败 → 错误可见 → 重试/恢复 → 无重复副作用 | REAL（Agent 侧）/ REAL（知识库错误气泡）/ OFFLINE（规划 UI 细节） | Agent：真实取消（0 工具 0 写入）、暂停跨 FAILED_RETRYABLE 收口、同一 Run 恢复不重复结算、FAILED 重试幂等（p4-agent-lifecycle-real-e2e-02）；知识库：SSE 失败错误气泡（p4-model-quick-connect-02 E2E-07）；规划：504 分类展示（planning-probe 第 1 次） | QUEUED 直接暂停 INCONCLUSIVE（离线补强）；历史 FAILED Run 无 UI 重试入口（R-07，兜底=重新发消息） |
| 7 | 模型接入 | 添加 Provider → 测试生成/流式 → 分配用途 → 实际使用 | REAL（Gemini/Kilo）/ GAP（Zen） | p4-model-quick-connect-real-acceptance-01：Gemini 探针+问答 PASS、Kilo 双轮 PASS、用途就绪度聚合真实展示；KNOWLEDGE_CHAT→Gemini、Agent→Kilo 真实分工验证 | Zen Free 上游限流未复验（R-10，有清晰失败反馈兜底） |

## 前后端语义一致性抽查

- 错误码映射：后端 BusinessException(ErrorCode) → 前端 api-result.ts 统一映射（403→「没有权限执行此操作」、PROJECT_ADMIN_REQUIRED、404 PROJECT_NOT_FOUND 不泄露存在性）。
- Agent 状态机 11 态在前后端一致（agent-run-state.ts 与 ck_status CHECK 对齐）；失败/取消/暂停均有区分表达。
- 模型能力门控：ModelCapabilityPolicy 生产门控与探针 NOT_SUPPORTED 分类一致，前端就绪度不外推。

## 刷新后状态一致性

知识库（5 轮 10 条消息 + 双来源引用）、Agent（3 个 Run 会话/回答/时间线/权威快照）、审批历史（权威 tag 与 DB 一致）均经真实刷新复核。已知例外：历史 Assistant 消息「待审批」为创建时刻快照文案（设计如此，见 ux-readiness.md 观察项 c）。

## 结论

- **6.5/7 闭环有真实端到端证据**：1、2、4、5、7 完全 REAL；6 主体 REAL（一处 INCONCLUSIVE 有离线补强）；3 能力与落库 REAL、页面编辑链路 OFFLINE。
- 无闭环存在前后端语义断裂或业务状态不一致。
- 本轮判定：**无需为发布决策重跑真实模型验收**；建议 RC 期间补两个低成本项——规划页面编辑+确认的浏览器复核（闭环 3 GAP 部分）、work 可视化域冒烟（R-06）。
