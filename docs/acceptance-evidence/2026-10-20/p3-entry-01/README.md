# P3-ENTRY-01 阶段准入与安全交付

日期：2026-10-20

仓库：`BangShou1st/ai-collab` · 分支：`dev`

验收基线（开工基线）：`9d9d2f246311365a71f19bf6c464eff7c4dc6aa4`（开工时本地 `dev` 与 `origin/dev` 一致，工作区无预存修改）。

环境：Windows；本机 Docker 提供 PostgreSQL 17 / pgvector、Redis、MinIO（沿用现有数据卷，未删除数据）。浏览器验收使用现有 BrowserSkill 与 Microsoft Edge，标准桌面视口。

> 本报告不复述任何文档正文、Owner 身份、邀请链接、Token、Cookie、API Key 或请求/响应正文。所有涉及的业务对象以脱敏标签（`D-01`…）或类别描述出现。

---

## 0. 本报告要回答的问题（对应任务）

1. P2 遗留风险是否已进一步收敛？
2. P3 哪些工作可以正式启动？
3. 本轮完成的首项 P3 工作是什么？
4. 是否引入了新的阻断问题？
5. 最终 Git SHA、CI 和真实验收结果。

---

## 1. P2 阶段基线（正式准入基线）

### 1.1 已交付并在真实环境验证的核心能力

依据三份 P2 验收记录（`p2-business-e2e`、`p2-business-e2e-02`、`p2-harden-01`），P2 阶段收敛后的基线如下：

| 能力域 | 基线结论 | 证据来源 |
|---|---|---|
| 登录 / 工作区 / 权限隔离 | 已通过；Owner/Member 视图、私有项目 404 后端拒绝与前端中性页面均已验证 | p2-business-e2e、p2-harden-01 |
| 文档上传 → 解析 → 分块 → 向量化 → 重索引 | 已通过（系统级 Ollama Embedding 配置下）；新文档生成 READY 分块并刷新持久化 | p2-business-e2e |
| 知识问答 RAG 与引用 | 已通过（真实模型、真实 READY 文档、6 类问题，含拒答与多来源） | p2-business-e2e-02 |
| Agent 完整引用链 | 已通过（真实模型、2 次检索工具调用、6 个来源、刷新恢复） | p2-business-e2e-02 |
| AI 规划生成与人工确认 | 已通过（真实 8 任务闭环：截断 → 有界分批重试 → 人工编辑 → 确认 → 刷新持久化） | p2-business-e2e-02 |
| 任务 / 里程碑 / 依赖协作 | 已通过（负责人、状态、截止日期、刷新持久化、依赖） | p2-business-e2e |

### 1.2 真实模型验收口径

| 能力 | 分类 | 说明 |
|---|---|---|
| Ollama Embedding | `REAL_MODEL_PASS` | 本机真实 `/v1/embeddings`，1024 维分块，重索引持久化 |
| RAG 知识问答 | `REAL_MODEL_PASS` | 真实模型 + 真实 READY 文档，6 类问题 |
| Agent 正常链路 | `REAL_MODEL_PASS` | 真实模型 + 真实工具调用 + 刷新恢复 |
| AI 规划 | `REAL_MODEL_PASS`（重试后） | 首次细节截断被正确报告，重试后完成确认 |
| Agent 历史 `ERR_ABORTED` 归因 | `NOT_TESTED` | 未重现、未归因 |
| 真实 Provider 429 / SSE 故障注入 | `NOT_TESTED` | 未在真实服务注入 |

### 1.3 未完成事项与风险接受条件

P2 **不能正式关闭**。以下为需显式接受或继续跟踪的遗留项：

| 编号 | 遗留项 | 性质 | 风险接受条件 |
|---|---|---|---|
| R-1 | 6 份历史失败文档 | 数据待办，需各自 Owner 合法重试 | 接受为**长期待办**；本轮不越权代操作，建立匿名跟踪记录（见 §3.1） |
| R-2 | Agent 历史 `ERR_ABORTED` 未归因 | 未重现的旧异常 | 接受为**未归因**；先补可观测性（见 §4），不在无证据时改 Agent/SSE 架构 |
| R-3 | Agent SSE 断线恢复仅有自动化验证 | 真实故障场景缺口 | 接受为**待真实验证**；制定有限验证计划（见 §3.3） |
| R-4 | 真实 Provider 429 未注入 | 外部服务缺口 | 接受为**NOT_TESTED**；不以 Mock 冒充通过；不通过耗尽额度制造错误 |
| R-5 | 18 项 opt-in 集成/真实服务测试被跳过 | 已知限制 | 接受为**已知限制**；不在无授权的真实环境开启 |
| R-6 | Member 私有项目 watcher 浏览器复测 | 已在 p2-harden-01 完成真实 Edge 复测 | 已收敛 |

---

## 2. P3 准入矩阵

**是否存在正式 P3 需求或实施计划：否。** 仓库内没有独立的 P3 需求/实施计划文档；能力矩阵（`docs/capability-matrix.md`）中的 P1–P4 为**上一阶段已交付**的编号，不构成新的 P3 范围。

因此 P3 范围严格取自 `p2-harden-01` 的「P2 与 P3 决策」：
- **可开展**：与遗留风险无依赖的 UI、文档、可回滚的非模型功能；监控/可观测性、测试夹具、Owner 自助重试流程；不改变既有模型重试/权限标准的独立改进。
- **受限**：扩大 Agent 长运行/自动重试、依赖 SSE 中断恢复的关键流程、批量重试 6 份文档、提升并发写入规模、依赖真实 Provider 限流预算或产生外部费用的功能。

准入矩阵（`READY` 可立即启动 / `CONDITIONAL` 有前提启动 / `BLOCKED` 被遗留项阻断）：

| # | P3 工作流 | 准入 | 依据与前提 |
|---|---|---|---|
| 1 | Agent SSE / AI Provider / 规划生成**可观测性**（脱敏诊断、运行关联、恢复结果） | **READY** | 属于"监控/可观测性"，与遗留风险无依赖；纯前端附加、可回滚、可单测独立验收 |
| 2 | 前端连接状态 **监控展示**（连接/重连/恢复可见） | **READY** | 同上；非模型、无后端契约变更 |
| 3 | 文档 / 路线图 / 验收报告等**交付物**补齐 | **READY** | 文档类，零运行时风险 |
| 4 | **测试夹具 / 测试基础设施**（脱敏工具、SSE 断线模拟夹具） | **READY** | 不触碰产品运行路径 |
| 5 | Owner **自助重试**流程（重试入口与提示的可操作性） | **CONDITIONAL** | 现有重试入口已存在；任何自动批量重试须先满足 R-1 |
| 6 | 非模型 UI 改进（布局、空态、文案、可访问性） | **READY** | 与遗留风险无依赖、可回滚 |
| 7 | Agent 自动重试扩展 / SSE 中断恢复作为依赖的关键流程 | **BLOCKED** | 直接触及受限清单，且依赖 R-2/R-3 证据 |
| 8 | 批量历史数据修改（批量重试 6 份文档） | **BLOCKED** | 依赖 R-1、R-5 |
| 9 | 高并发写入 / 规模扩展 | **BLOCKED** | 依赖 R-5 真实并发证据 |
| 10 | 依赖真实 Provider 限流预算 / 产生外部费用的功能 | **BLOCKED** | 依赖 R-4 |

**本轮不启动任何 P3 阶段性的新业务功能**——只落地矩阵中 #1、#2（合并为首项 P3 交付，见 §5），并输出准入与遗留跟踪文档。

---

## 3. 遗留问题跟踪

### 3.1 六份历史失败文档 · 恢复跟踪记录（脱敏）

背景：6 份合成文档在历史 Embedding 服务不可用期间索引失败。系统 Embedding 配置保存**不会**自动重排这些文档；它们需要**各自项目所有者**在项目文档页执行重新索引。

**原则：不代替 Owner 操作。** 本机管理员账号对这些项目没有成员权限，本轮未通过数据库或管理员权限绕过项目权限；未记录任何文档标题、正文或 Owner 身份。

| 跟踪 ID | Owner（脱敏） | 所属项目（脱敏） | 已知状态 | 恢复方式 | 本轮结果 | 完成条件 |
|---|---|---|---|---|---|---|
| D-01 | 自述者 Owner（不出示身份） | 历史合成项目（不列名） | BLOCKED | 该 Owner 合法登录 → 项目文档页 → 重新索引 | 未执行（无授权会话） | 页面显示"可检索"且分块数 > 0，刷新后保持 |
| D-02 | 同上 | 同上 | BLOCKED | 同上 | 未执行 | 同上 |
| D-03 | 同上 | 同上 | BLOCKED | 同上 | 未执行 | 同上 |
| D-04 | 同上 | 同上 | BLOCKED | 同上 | 未执行 | 同上 |
| D-05 | 同上 | 同上 | BLOCKED | 同上 | 未执行 | 同上 |
| D-06 | 同上 | 同上 | BLOCKED | 同上 | 未执行 | 同上 |

**结论：本轮重试 `0/6`；实际可恢复数未验证。** 不得把先前失败推定成当前仍失败，也不得提前标记为恢复成功。恢复结果须由各自合法 Owner 在对应项目完成后回填，并附脱敏页面证据。

### 3.2 Agent 历史 `ERR_ABORTED` · 现有证据与无法归因的限制

**现存证据（复核三份 P2 记录）：**

- `p2-business-e2e` E2E-04：一次 SSE 流在约 34–36 秒出现 `ERR_ABORTED`，后续运行记录显示已完成。
- `p2-business-e2e-02`：同现象**未重现**；已保存的后续 capture 从**已完成运行**开始，只能证明历史恢复请求成功（13 个业务 API GET 均 200），**不含**原始模型生成与工具调用请求。
- `p2-harden-01`：复核既有证据仍未归因。

**为什么现在无法归因（限制）：**

1. 原始异常的**逐请求/逐事件记录缺失**：旧 capture 未覆盖中断时刻的流事件顺序。
2. 前端当时**未记录**断线原因、断线前事件序号、重连结果与错误类别——只有浏览器网络层的 `ERR_ABORTED` 标签。
3. `ERR_ABORTED` 在浏览器网络层**既可能**由客户端主动取消（切会话/切项目/重开运行/暂停/卸载）产生，**也可能**由意外传输中断产生；没有服务端/客户端侧记录时两者不可区分。
4. 因此**不得**推断为主动取消、页面切换、网络断线、SSE 超时或服务端失败的任一具体成因。

**归因路径（本轮起）**：见 §4 的脱敏诊断。补上"有记录＝意外断线 / 无记录＋网络 ERR_ABORTED＝客户端主动取消或卸载"的可判定规则后，未来同类事件可在首次发生时被归类。

### 3.3 有限验证计划（Provider 429 / Agent SSE 断线恢复 / opt-in 测试）

**约束：不通过大量真实模型请求或人为耗尽额度制造错误。**

| 验证项 | 方法 | 完成条件 | 本轮状态 |
|---|---|---|---|
| Provider 429 | 已有自动化：HTTP 状态映射与 Kilo Gateway 429 分类（`ModelProtocolHttpTest`、`KiloGatewayModelTest`） | 类别映射为 `RATE_LIMITED` 且不重试超预算 | 自动化通过；**真实 Provider 注入 = NOT_TESTED**（不耗额度） |
| Agent SSE 断线恢复 | 真实会话中制造**安全**中断（不破坏数据）：运行中刷新/离开页面 → 回来观察续接 | 持久化事件按序号续接、无重复终态消息、最终消息不重复 | 见 §6.3 |
| opt-in 集成/真实服务测试 | 需显式 `AI_*` 开关与环境 | 在授权环境开启并记录结果 | **未开启 = NOT_TESTED**（无授权真实环境） |

**明确完成条件**：任何"真实故障注入"只有在真实服务上可安全执行且可复现时才可记为 PASS；否则一律 `NOT_TESTED`，**不得用 Mock 结果冒充真实通过**。

---

## 4. 可观测性审查与最小改动

### 4.1 审查结论：存在真实观测缺口

| 诊断面 | 现状 | 缺口 |
|---|---|---|
| 后端 SSE 写出失败 | `AgentEventStreamService` 在写出失败时以 `debug` 记 `runId`，保留订阅等待重连 | 服务端无"客户端断线/重连"的结构化记录 |
| 后端 Provider 失败 | `ExternalProviderFailure.Kind`（`RATE_LIMITED`/`UPSTREAM_TIMEOUT`/`UPSTREAM_5XX`/`UPSTREAM_TRANSPORT`）已分类 | 分类已存在，无需改动 |
| 前端 SSE 客户端 | [agent-event-stream.ts](file:///e:/project/ai-collab/ai-collab-frontend/src/modules/agent/agent-event-stream.ts) 断线后静默进入指数退避重连；无任何记录 | **断线原因、断线前序号、运行关联、重连结果、错误类别均无记录** |
| 前端重连循环 | [use-agent-workspace.ts](file:///e:/project/ai-collab/ai-collab-frontend/src/modules/agent/use-agent-workspace.ts) `consumeEventStream` 重连 | 同上；且 `connected` 在发起请求前即被乐观置真 |

**缺口是真实存在的**：这正是 §3.2 中"无法归因"的直接原因。因此实施以下最小改动——不新增后端契约、不改变重连/退避策略、不触碰 Agent Runtime 与业务语义。

### 4.2 最小改动清单（本轮首项 P3 工作）

**新增** `ai-collab-frontend/src/modules/agent/agent-sse-diagnostics.ts`（纯函数，独立可测）：
- `classifySseDisconnect(reason, aborted)`：把断线归类为 `CLIENT_ABORT` / `HTTP_ERROR`(+状态码) / `NETWORK` / `UNKNOWN`。
- `AgentSseDiagnosticTracker`：按"断线区间"收敛——一次连续断线只产出一条 `disconnect`（避免后端不可用时刷屏），恢复后产出一条 `recovered` 并复位。
- `formatAgentSseDiagnostic` / `emitAgentSseDiagnostic`：序列化**契约限定字段**（`scope, phase, category, runId, lastSequence, attempt[, httpStatus]`）。
- `describeAgentSseDisconnect`：脱敏中文说明。

**修改** `agent-event-stream.ts`：新增可选 `onOpen` 回调，只在 HTTP 2xx 且拿到正文后触发——把"已连接/已恢复"作为事实而非乐观假设。

**修改** `use-agent-workspace.ts`：
- 接入 tracker；`connected` 改为在 `onOpen` 中置真（不再乐观置真）。
- **只有意外断线**（非本地取消）产出诊断记录；本地主动取消不记录。
- 切换项目/会话/运行、恢复会话时复位 tracking 区间，避免跨运行串用。

**修改** `AgentView.vue`：在既有「诊断与资源」区与运行状态行展示脱敏断线诊断（类别 · 断线前 seq · 第 N 次尝试 · 已恢复/重连中）。

**脱敏保证**：记录仅含类别、序号、运行关联与状态码；**不含**正文、Header、Cookie、Token、API Key、文档内容或用户身份。运行 ID 仅用于把记录挂到对应运行。

**可回滚性**：改动为前端附加式，无后端/数据库/契约变更；删除新增文件并回退三处接线即可完全复原。

---

## 5. 首项 P3 工作与独立验收

**本轮首项 P3 工作 = 上图 §4.2：Agent SSE 断线恢复可观测性（脱敏诊断 + 监控展示）。**

- 对应准入矩阵 #1 + #2（`READY`），位于 `p2-harden-01` 明确允许的"补充监控/可观测性"范围。
- 与遗留风险 R-1/R-4/R-5 无依赖；**不依赖** R-2/R-3 的结论——它是为归因 R-2/R-3 提供证据，而非依赖它们。
- 独立验收证据：纯函数单测（`agent-sse-diagnostics.test.ts`）+ 流建立回调单测（`agent-event-stream.test.ts` 新增用例）+ 真实浏览器展示（§6.3）。

---

## 6. 受控真实验证

### 6.1 后端自动回归

命令：Windows Maven Wrapper `mvnw.cmd clean verify`（工作目录 `ai-collab-backend`；运行前先停止占用 `target/` 的历史 dev 进程）。

结果：**`BUILD SUCCESS`**；`Tests run: 1533, Failures: 0, Errors: 0, Skipped: 18`；Testcontainers/PostgreSQL 集成用例实际执行（每例独立容器 + Flyway 迁移到 v65）；总耗时约 8 分 40 秒。18 项跳过均为显式 `AI_*` opt-in 真实服务测试（`AI_REAL_ACCEPTANCE`、`AI_REAL_RETRIEVAL`、`AI_RELIABILITY_ACCEPTANCE`、`AI_RELIABILITY_ASSERTIONS`、`AI_BROWSER_ACCEPTANCE`、`AI_UPGRADE_REHEARSAL`、`AI_OLLAMA_SMOKE` 等），本轮未开启。

### 6.2 前端自动回归

| 检查 | 命令 | 结果 |
|---|---|---|
| Lint | `pnpm lint` | 通过；**0 errors、78 warnings**（均为既有 warning，本轮新增文件未引入新 warning） |
| 类型 | `pnpm typecheck`（vue-tsc -b） | 通过 |
| 单测 | `pnpm test` | **43 个文件、264 项通过**（较 P2 基线 42/249 增加：`agent-sse-diagnostics.test.ts` 13 项 + `agent-event-stream.test.ts` 2 项） |
| 构建 | `pnpm build` | 通过；仅既有 PURE 注释与大 chunk 提示 |
| 差异空白 | `git diff --check` | 通过 |

### 6.3 真实浏览器验收（BrowserSkill + Edge）

环境：Microsoft Edge `155.0.4283.62`，BrowserSkill CLI/扩展 `0.3.3`，标准桌面视口 `1544×847`，真实前端 `localhost:5173` / 后端 `localhost:8080`，真实免费模型 `cohere/north-mini-code:free`（Kilo Gateway，匿名、无付费凭据）。测试使用浏览器内已存在的合成验收账号与合成项目（P2-E2E-02 synthetic RAG and planning acceptance），**未新建付费凭证、未消耗付费额度**。

| 场景 | 结果 | 证据 |
|---|---|---|
| 真实 Agent 运行 + SSE 已连接 | PASS | 截图 [02-agent-running-sses-connected.png](screenshots/02-agent-running-sses-connected.png) |
| 真实 `net::ERR_ABORTED` 再现与归因 | PASS（归因可判定） | 网络记录 [agent-sse-break-sanitized.json](network/agent-sse-break-sanitized.json) §S1；运行中真实免费模型 SSE 流返回 `200 text/event-stream`，页面重载触发 `net::ERR_ABORTED` |
| 受控 SSE 传输中断 → 脱敏断线诊断 | PASS | 截图 [03-agent-sse-reconnecting-network.png](screenshots/03-agent-sse-reconnecting-network.png)；UI 显示 `SSE 重连中（传输中断 · 断线前 seq 12 · 第 5 次尝试）`；控制台 `[agent-sse] {"scope":"agent.sse","phase":"disconnect","category":"NETWORK","runId":"<redacted>","lastSequence":12,"attempt":1}` |
| 断线记录区间收敛（不刷屏） | PASS | 传输中断期间浏览器出现多次 `ERR_CONNECTION_RESET` / `ERR_CONNECTION_REFUSED`（多次重连失败），`[agent-sse]` 只输出 **1 条** `disconnect` |
| 恢复后功能正确（真实回答 + 无重复终态消息） | PASS | 截图 [04-agent-recovered-answer-with-sources.png](screenshots/04-agent-recovered-answer-with-sources.png)；回答含 `project-release-standards.md`、`rollback-rules.md` 真实来源标记，该运行最终回答仅出现一次 |
| 同页 `phase=recovered` 记录 | **NOT_TESTED（环境限制）** | 见下方说明 |
| 真实 Provider 429 注入 | **NOT_TESTED** | 不以耗尽额度制造错误；仅自动化类别映射通过 |

**关于 `recovered` 记录未能复现（诚实记录）**：本环境唯一能造成客户端可见 SSE 断开的受控手段是停止承载页面的前端开发服务器（Vite 代理）。已用页面内标记验证：重启该服务器会**重载页面**（标记在重启后丢失），因此同一页面内的断线区间被重置，`phase=recovered` 记录无法在该环境复现。该路径（`opened()` → recovered 记录、区间复位）由单元测试覆盖。另一手段（停止后端进程）经 Vite 代理**不会**使客户端 SSE 断开（代理保持下游连接），同样无法产生客户端可见断线。**不将单元测试结果冒充为浏览器证据。**

截图与脱敏网络记录未保存 Token、Cookie、API Key、请求/响应正文或完整业务 UUID。

---

## 7. 结论

**1. P2 遗留风险是否已进一步收敛？**
部分收敛，未掩盖：
- R-2（Agent 历史 `ERR_ABORTED` 未归因）：**收敛一步**。新增可判定规则并在真实浏览器再现同类事件，判定其为**客户端主动取消（页面重载）**，同时用传输中断场景证明"意外断线会有记录"。但历史那次事件因原始逐事件证据缺失，**仍为未归因**。
- R-3（Agent SSE 断线恢复）：**收敛一步**。真实传输中断下断线被正确分类、记录并在 UI 展示；恢复后功能正确（真实来源回答、无重复终态消息）。同页 `recovered` 记录的浏览器复现受环境限制，标记 NOT_TESTED。
- R-1（6 份文档）、R-4（真实 429）、R-5（18 项 opt-in）：**未变**，仍 BLOCKED / NOT_TESTED，不因本轮绿色全量测试而视为通过。

**2. P3 哪些工作可以正式启动？**
- **READY（可立即启动）**：可观测性（矩阵 #1，本轮已启动）、连接状态监控展示（#2，本轮已交付）、文档/交付物（#3）、测试夹具（#4）、非模型 UI（#6）。
- **CONDITIONAL**：Owner 自助重试流程（#5，须先满足 R-1）。
- **BLOCKED**：Agent 自动重试扩展 / 以 SSE 恢复为依赖的关键流程（#7）、批量历史数据修改（#8）、高并发写入（#9）、依赖真实限流预算的功能（#10）。

**3. 本轮完成的首项 P3 工作？**
**Agent SSE 断线恢复可观测性**：新增脱敏断线诊断（类别 / 断线前序号 / 运行关联 / 重连尝试 / 恢复结果）+ Agent 运行详情内的连接状态监控展示；纯前端附加、无后端契约变更、可回滚（删除新增文件并回退三处接线即复原）。

**4. 是否引入了新的阻断问题？**
**否。** 后端全量 `clean verify`（1533 项全绿）与前端 lint / typecheck / test / build 全部通过，无新增失败用例；改动为附加式，未改变 Agent Runtime、SSE 重连策略、模型重试与业务语义。

**5. 最终 Git SHA、CI 和真实验收结果？**
见 §8。

---

## 8. Git 与 CI

- 开工基线：`9d9d2f246311365a71f19bf6c464eff7c4dc6aa4`（本地 `dev` 与 `origin/dev` 一致，工作区无预存修改）。
- 本轮代码与验收资料提交：`__CODE_SHA__`，普通快进推送至 `dev`，**未 force push、未硬重置、未清理数据卷**。
- 工作区保留说明：仅在本机新增/修改前端 agent 模块文件与验收证据目录；未改动后端源码、迁移或既有数据。
- 配套：为通过 `dev.ps1` 的依赖检查，在本机用户级 PATH 增加了一个 `pnpm` 转发脚本（`%USERPROFILE%\.local\bin\pnpm.cmd → corepack pnpm`），**不属于仓库内容**，不影响代码与 CI。

### GitHub Actions

- 代码提交对应 CI：**待推送后回填**（`__CI_PLACEHOLDER__`）。
