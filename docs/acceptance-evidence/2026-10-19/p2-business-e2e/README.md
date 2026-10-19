# P2-BUSINESS-E2E-01 验收记录

## Git 与环境

- 验收基线：`df330bace37d41a59798e39c42e5f7cf81c9d130`，分支 `dev`。
- 环境：Windows；前端 `http://localhost:5173`，后端 `http://localhost:8080`；PostgreSQL 17/pgvector、Redis、MinIO 使用现有 Docker 容器与数据卷。
- 浏览器验收：BrowserSkill CLI/扩展 `0.3.3`，Microsoft Edge `155`。真实浏览器操作分别使用 BSK session `woiv`、`yjqw`；Ollama 配置及复测 run 为 `de4e739a44050`。
- 最终提交 SHA 与 GitHub Actions URL 在推送后由提交记录及 CI 结果补充到最终报告。
- 所有新建项目、文档、任务、账户均为合成验收数据。文档正文和网络请求正文不提交。

## Ollama 与文档分块

验收开始时，本机 Ollama 可访问，已安装 `qwen3-embedding:0.6b` 和 `nomic-embed-text:latest`；`qwen3-embedding:0.6b` 的本地向量探测返回 1024 维。但 AI Collab 的管理员页面 `AI Infrastructure` 中没有系统 Embedding 配置。Ollama 运行状态不会自动写入应用配置，因此上传只会失败并显示 0 分块。

在本机管理员 UI 中完成了连接测试并保存：

- Provider：`OLLAMA`
- Base URL：`http://127.0.0.1:11434`
- API Path：`/v1/embeddings`（应用使用 Ollama 的 OpenAI 兼容端点）
- 模型：`qwen3-embedding:0.6b`
- 探测维度：`1024`；批量大小：`16`；本地直连未配置 API Key

保存配置后，在合成项目“P2-E2E-20261019｜Ollama 文档分块回归项目”上传 `ollama-chunking-proof.md`。UI 显示“可检索”、3 个文本分块；详情显示 Provider `OLLAMA`、模型 `qwen3-embedding:0.6b`、维度 `1024`。点击“重新索引文档”后，版本由 v0 到 v1；刷新项目文档页后仍为“可检索”、3 个分块。截图 `08`、`17` 与脱敏网络记录 `network/bsk-ollama-config-document-retry-sanitized.json` 记录了这次修复后验证。

先前 Owner 项目中的 6 份合成文档在配置缺失期间失败；系统配置保存不会自动重排这些失败文档。它们仍需各自项目所有者在项目文档页执行重新索引。系统管理员没有这些项目的成员权限，所以本轮没有越权替其他所有者重试。新建文档和管理员自有项目中的重新索引已通过真实 UI 验证。

## 浏览器场景结果

| 场景 | 结果 | 实际操作与证据 |
|---|---|---|
| E2E-01 登录与工作区 | PASS | Owner 登录、进入项目并刷新；管理员/成员视图亦通过实际浏览器登录。截图 `02-owner-workspace.png`。 |
| E2E-02 文档上传与索引 | PASS（修复后） | 配置 Ollama 后上传合成 Markdown，实际生成 3 个分块并可检索；重新索引至 v1，刷新后状态保留。修复前失败截图 `03`、`04`、`07`、`14`；修复后截图 `08`、`17`。旧 Owner 项目中的失败文档需所有者另行重试。 |
| E2E-03 知识检索与引用 | FAIL | 实际提交过 1 个问题，浏览器请求 HTTP 200，但业务结果校验失败；其余 5 个问题因文档无 READY 向量而未提交。配置 Ollama 后没有在管理员账号上重新运行聊天模型问答，因为该账号没有已配置的聊天模型。未取得有效 RAG 答案和来源引用，不能记为通过。 |
| E2E-04 Agent 完整流程 | FAIL（引用链） | 真实免费 Kilo Gateway 模型完成初次及多轮 Agent 对话并产生回答/步骤；文档未索引时没有来源引用。一次 SSE 流在约 34–36 秒出现 `ERR_ABORTED`，后续运行记录显示已完成。未对已索引文档重跑 Agent。截图 `05-free-model-connection.png`。 |
| E2E-05 规划建议与人工确认 | FAIL | 真实模型生成 3 个里程碑骨架；细节生成返回 `PLANNING_MODEL_OUTPUT_TRUNCATED`（任务细节内容过长），未形成可审批任务，也没有确认写入。 |
| E2E-06 任务与项目协作 | PASS | 创建里程碑及 3 项任务，验证负责人、状态变更、截止日期与刷新持久化。截止日期辅助项目截图 `15-due-date-task-persisted-after-refresh.png`；初始空看板截图 `06-task-board-empty.png`。 |
| E2E-07 权限隔离 | PASS | Owner 邀请 Member；成员可查看项目/文档但没有创建、批量操作、编辑或删除任务的控件。成员访问 Owner 私有项目时 API 返回 404，页面显示项目不存在。截图 `11`、`12`、`13`、`16`；网络证据 `bsk-member-retest-sanitized.json`。 |
| E2E-08 异常与恢复 | PASS（文档重试范围） | 配置 Ollama 后对管理员自有合成文档执行重新索引，状态及 3 个分块在页面刷新后保持。429、Ollama 停机、Agent 中途刷新/续跑未测试。 |

这组验收资料包含 14 张 BSK 实际截图。Ollama 复测 run 共记录 600 个请求、36 个操作，0 个请求/操作/控制台记录丢失，run 级错误数为 0；关键上传、重试 API 使用 202，后续文档查询为 200，业务状态由页面显示验证。记录中已省略请求/响应正文、头、Cookie、查询串及项目/文档 UUID。

## 模型调用分类

| 能力 | 分类 | 结果 |
|---|---|---|
| Ollama Embedding | `REAL_MODEL_PASS` | 本机模型真实连接测试成功，应用实际处理并重试文档，产出 3 个 1024 维向量分块。 |
| 知识问答 | `REAL_MODEL_FAIL` | 一次真实浏览器问题未通过业务校验；无有效来源引用。配置后未追加聊天模型调用。 |
| Agent | `REAL_MODEL_FAIL`（完整引用流程） | 免费 Kilo Gateway 返回真实回答，但未完成引用验证。 |
| AI 规划 | `REAL_MODEL_FAIL` | 真实模型返回的细节被截断，审批写入未完成。 |
| Mock | `MOCK_ONLY` | 本轮没有用 Mock 结果替代任何业务验收结论。 |

未配置付费模型或新增付费凭证；没有绕过限流，也没有进行多账号/IP 轮换。额外聊天模型调用保持在验收所需范围内。

## 缺陷与修复

### 缺陷数量

- P0：1 项本地验收证据处理事件（详见下方）。
- P1：3 项阻断项：系统 Embedding 配置缺失曾阻断索引；知识问答业务结果校验失败；规划细节生成截断。
- P2：2 项代码/提示问题，均已修复：页面头部组件缺失导入；Embedding 未配置时提示不够明确。
- P3：1 项非阻断控制台问题：私有项目按预期被后端 404 拒绝后，前端 watcher 仍打印未处理错误警告。

### 具体问题

| 级别 | 问题与复现 | 根因/处理 | 证据 |
|---|---|---|---|
| P0（本地证据处理） | 一条合成项目邀请链接曾在较早的本地 BSK 页面输出中显示。 | 邀请已在验收中使用；令牌没有写入 Git。此前原始临时证据位于忽略的 `target`，后续 `mvn clean` 已清除；提交的网络记录只保留脱敏元数据。报告不复述链接或令牌。 | 成员验收截图与脱敏网络记录不含令牌。 |
| P1 | 上传文档时系统 Embedding 配置为空，索引失败并阻断 RAG/Agent 引用。复现：Ollama 正常运行但管理员页面未配置系统 Embedding。 | 属于本地环境配置缺失，不是 Ollama 进程未启动。已在本机管理员 UI 配置 Ollama 并验证新文档与重试成功；之前失败的 Owner 文档仍需其所有者重试。部署环境也必须设置系统 Embedding。 | 截图 `03`、`04`、`08`、`14`、`17`。 |
| P1 | 知识问答请求 HTTP 200，但前端业务结果校验失败，未能得到可引用答案。 | 本轮没有完成请求字段到业务错误的根因定位，也没有在修复后重跑聊天模型。应单独修复并补充真实引用验收。 | E2E-03；`bsk-baseline-sanitized.json`。 |
| P1 | 三里程碑规划细节生成返回 `PLANNING_MODEL_OUTPUT_TRUNCATED`，无法编辑并确认完整计划。 | 真实模型输出超出系统规划细节处理限制；本轮未修改规划架构。 | E2E-05 浏览器记录。 |
| P2（已修复） | 系统 Embedding 未配置时，文档索引失败提示不够可操作。 | `DocumentProcessingService` 对该特定验证错误返回“系统尚未配置嵌入模型，请联系管理员在管理中心配置后重试”；新增服务层测试。 | 截图 `14-doc-actionable-index-failure.png`；后端 `DocumentProcessingServiceMessageTest`。 |
| P2（已修复） | `TaskBoardView`、`KnowledgeView` 使用了未导入的 `PageHeader`。 | 增加显式组件导入；补充 TaskBoard Owner/Member 操作权限回归测试。 | TaskBoard 页面截图 `06`、`11`、`12`；前端测试 `TaskBoardView.test.ts`。 |
| P3 | 成员直接访问私有项目时后端正确返回 404，页面 watcher 同时产生未处理错误警告。 | 权限隔离有效；建议前端把预期的 404 收敛为“项目不存在”页面状态并吞掉 watcher 异常。 | 截图 `16-member-private-project-denied.png`；`bsk-member-retest-sanitized.json`。 |

## 代码变更

- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/service/DocumentProcessingService.java`：为“系统未配置 Embedding”给出可操作错误文本，其他验证错误保持原行为。
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/document/application/service/DocumentProcessingServiceMessageTest.java`：覆盖新错误映射。
- `ai-collab-frontend/src/modules/work/TaskBoardView.vue`、`ai-collab-frontend/src/modules/knowledge/KnowledgeView.vue`：补 `PageHeader` 导入。
- `ai-collab-frontend/src/modules/work/TaskBoardView.test.ts`：检查 Owner/Member 任务操作权限差异。
- `docs/acceptance-evidence/2026-10-19/p2-business-e2e/`：验收报告、14 张 BSK 截图、合成 fixture、脱敏网络元数据。

## 自动化回归

- 后端：`.\mvnw.cmd clean verify`，`BUILD SUCCESS`；1,516 项，0 失败，0 错误，18 跳过。Postgres/pgvector Testcontainers 集成用例运行通过；18 项均要求显式设置 `AI_*` opt-in 开关（含 `AI_OLLAMA_SMOKE`、真实 Agent/检索和可靠性验收），本轮未启用这些专用烟测。
- 前端：`pnpm lint` 通过，0 errors、79 warnings；`pnpm typecheck` 通过；`pnpm test` 通过，41 个测试文件、239 项；`pnpm build` 通过。构建仅有现存的 PURE 注释及大 chunk 提示。
- `git diff --check`：通过。
- GitHub Actions：推送 `dev` 后等待实际 CI 结果，并以最终报告中的 Actions 链接为准。

## 结论

Ollama 确实已经启动；失败原因是 AI Collab 尚未配置系统级 Embedding，并且它使用 `/v1/embeddings` 兼容路径。当前本机已配置并实测文档能被解析为 3 个可检索分块，重新索引后刷新仍保持成功。

**暂不建议进入 P3。** 知识问答有效引用和规划确认仍有 P1 失败；原 Owner 项目的失败文档尚待对应所有者重试；私有项目 404 的前端 watcher 警告待修。完成这些事项后再做 P3 判断。
