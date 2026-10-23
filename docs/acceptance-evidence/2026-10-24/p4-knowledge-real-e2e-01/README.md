# P4-KNOWLEDGE-REAL-E2E-01：知识库真实浏览器端到端业务验收报告

**交付状态：`KNOWLEDGE_BROWSER_E2E_GAPS_FOUND`**

主链路（浏览器上传 → Ollama 真实 Embedding 索引 → UI SSE 提问 → Gemini 3.5 Flash Lite 真实生成 → 双来源引用核查 → 多轮追问 → 刷新持久化）全部真实通过；同时发现 3 个不影响主链路的真实产品缺陷/体验缺口，详见 F 节。

---

## A. Git

- 任务起始基线：`2f0a4b279d69391977cb379b85eeac71b9ab0c6c`（dev = origin/dev，工作区干净）
- `main` / `origin/main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（全程未触碰）
- 本轮交付 SHA：见提交历史（本文档随最后一笔提交固定）
- 生产代码修改：无（本轮零生产代码改动）
- 改动文件：`tests/fixtures/documents/README.md`、`tests/fixtures/documents/p4-rag-answer-quality-01/灰度发布与回滚规范.md`、`docs/acceptance-evidence/2026-10-24/p4-knowledge-real-e2e-01/**`

## B. 环境

详见 [environment.json](environment.json)。要点：

- 前端：Vite dev server（5173）；后端：local profile（8080，/actuator/health UP），本轮以持久会话携带 JVM 代理参数启动（本机无法直连 Google API，系统代理 127.0.0.1:7897；`dev.ps1` 的 WMI 分离启动无法继承环境变量）。
- PostgreSQL 17.11（pgvector）· Redis 7 · MinIO（docker，dev.ps1 拉起）。
- Embedding：OLLAMA `qwen3-embedding:0.6b`，1024 维（system_embedding_config 实读 + `vector_dims(embedding)=1024` 实测）。
- Chat：GEMINI `gemini-3.5-flash-lite`，官方 `generativelanguage.googleapis.com/v1beta` 端点，Key 走用户授权文件仅进入浏览器表单，加密落库，未出现在任何证据。
- 浏览器：Edge 155（bsk 0.3.3 驱动，会话 vdeb），账户：U04 合成管理员（历史验收专用合成账户）。
- 隔离：独立项目 `P4-KNOWLEDGE-REAL-E2E-01 隔离验收项目`（`4dde7a2e-f26e-43a0-ae1f-51caeaa29d76`），未触碰任何其他项目/用户数据。

## C. 浏览器场景

逐条见 [browser-scenarios.json](browser-scenarios.json)。7 个场景：6 PASS / 1 PASS_WITH_FINDINGS（S3 Provider 配置发现两处缺陷）。

## D. 上传与索引

详见 [document-indexing.json](document-indexing.json)。5 篇合成文档（含甲/乙两份同文异名合同）全部经真实浏览器对话框上传，自动异步索引，`READY` + 向量 1024 维全量落库；刷新后状态不变。SHA-256、documentId、分块数逐篇记录。不支持类型（.exe）被前端校验拒绝。

## E. 问答质量

详见 [knowledge-qa-results.json](knowledge-qa-results.json) 与 [gemini-calls.json](gemini-calls.json)。

- 真实 Gemini 生成 6 次（预算 10 次内，串行，含 1 次“连接成功”连通性验证），0 次 Provider 失败。
- Q-E2E-01 三个上线门禁完整、无编造，引用《上线验收规程.md》并可溯源。
- Q-E2E-02（Q09 修复的浏览器复验）：正确认定甲乙一致，甲=[S2]、乙=[S1] 两个独立 documentId/chunkId，正文摘录支持结论，数据库持久化与页面一致。
- Q-E2E-03：服务端判定证据不足，页面显示“当前项目资料不足以回答该问题”+ 警示条，无编造。
- Q-E2E-04：同一会话多轮，第一问明确“不可以”，追问未编造“批准后可加”的例外规则，保留来源。

## F. 缺陷与体验缺口（本轮发现，均未在本轮擅自修复）

1. **[P1] 自定义 Provider 选择 Gemini 后端点预设错误**：`UserAiSettingsView.vue` 选 GEMINI 时仍保留 `https://api.openai.com/v1/chat/completions`，首次连通性测试必然 SSL 握手失败；用户必须自己知道并手填官方端点。建议按 providerType 切换默认 baseUrl/apiPath。
2. **[P2] Gemini 原生 `{model}` 占位符无法通过保存校验**：后端 `validateEndpoint` 用 `URI.create` 校验 apiPath，`/models/{model}:generateContent` 因花括号被判“模型接口地址无效”，而 `GeminiModelAdapter.modelEndpoint` 又依赖该占位符；用户只能把模型名硬编码进 path（本轮做法）。两者需要对齐（放行占位符或适配器支持无占位符路由）。
3. **[P2] 自定义 Provider 能力固定为 CHAT，无 STREAMING 勾选入口**：前端表单无 capabilities 编辑，后端 `ModelCapabilityPolicy` 又要求流式问答必须 STREAMING，导致走 UI 创建的 Gemini 配置无法流式问答，且 SSE 失败时前端消息区无可见错误提示（只有会话标题更新）。本轮为继续验收，通过同一 PATCH API 补 STREAMING 后恢复。建议：表单暴露能力勾选 + 流式失败在前端展示错误。

以上均不阻断本轮主链路验收（缺陷 3 可经 API 配置绕过），列为后续任务建议。

## G. 引用交互

详见 [citation-interactions.json](citation-interactions.json)。引用以可交互按钮呈现（编号/文件名/标题/相似度/原文摘录），点击打开抽屉并支持“下载原文”；后端 citation→chunk→document 身份链与页面一致。**产品当前不支持页面内滚动定位/高亮引用分块**，按任务要求记录为能力边界（不判失败、不判通过），评估为非发布阻断。

## H. 多轮与持久化

详见 [persistence-checks.json](persistence-checks.json)。同一会话 5 轮问答（10 条消息）刷新后全部保留、顺序正确、无重复、完成态不再显示生成中；Q-E2E-02 双来源引用刷新后仍正确显示；数据库行与页面一致。

## I. 权限与异常

详见 [permissions-and-errors.json](permissions-and-errors.json)。已执行：不支持文件上传校验、无证据拒答、STREAMING 门控错误路径。未执行：跨项目越权（无第二合法测试账户，不做改库伪造）；Gemini 停机反馈（不主动制造 Provider 故障）。未观察到任何越权。整体按 PARTIAL 记录权限项。

## J. 测试与 CI

详见 [local-test-summary.json](local-test-summary.json)。前端 lint 0 错误 / typecheck / 373 tests / build 全绿；后端零改动未重跑全量 verify（上一轮 CI 全绿）；推送后 CI pending，不预写结果。

## K. 遗留问题（建议后续任务，最多两项）

1. **修复 AI 设置 Provider 体验三缺陷**（F 节 1-3，建议合并一个任务处理，含流式错误的前端可见性回归测试）。
2. **引用定位能力增强评估**（点击引用滚动定位/高亮原文分块；先做产品决策与工作量评估，不急于实现）。

## L. 证据文件与截图

- [environment.json](environment.json) / [browser-scenarios.json](browser-scenarios.json) / [document-indexing.json](document-indexing.json) / [gemini-calls.json](gemini-calls.json) / [knowledge-qa-results.json](knowledge-qa-results.json) / [citation-interactions.json](citation-interactions.json) / [persistence-checks.json](persistence-checks.json) / [permissions-and-errors.json](permissions-and-errors.json) / [local-test-summary.json](local-test-summary.json)
- 截图（原始未编辑）：`screenshots/` 目录 9 张，覆盖文档列表、Q-E2E-01 回答与引用、Q-E2E-02 双来源、引用抽屉、Q-E2E-03 拒答、Q-E2E-04 追问、刷新后持久化、不支持文件校验。
