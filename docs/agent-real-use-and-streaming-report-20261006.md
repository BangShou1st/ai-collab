# Agent 真实使用检查与正文流式输出交付报告（20261006）

任务书：`docs/agent-real-use-and-streaming-handoff-20261006.md`。本报告为最终收口记录，统计均为本轮实测，未复制历史数字。分支 `codex/context-foundation`（接手 HEAD `758fcf0` + 任务书文档），全部改动保留未提交至本轮收口提交；未推送、未合并 main、未部署；业务库 `ai-collab-postgres` 全程未启动。

## 1. 交付概览

| 任务 | 结论 |
| --- | --- |
| A：真实文档检索成功链 + 正常使用检查 | **完成**。三类场景全部通过，`search_project_knowledge` 真实成功链补齐，无需代码修复（上轮失败根因确认为本机 Ollama 未启动） |
| B：Agent 普通正文实时流式展示 | **完成**。临时正文帧经既有 SSE 通道下发，含一次真实缺陷修复（读流线程错配，见 5.2）；真机全运行 51 帧、最终回答轮 48 帧真增量确认 |

## 2. A：真实文档检索与正常使用检查

### 2.1 环境与配置（全部为既有资产，未安装新模型、未创建账号）

- 隔离副本 PG `ai-collab-acceptance-postgres-20261003`（127.0.0.1:55432）+ 验收 Redis（16379）/MinIO（18090），宿主 `RealAcceptanceHostTest`（18080，`AI_REAL_ACCEPTANCE_KEEP_CONFIG=true` 保留副本配置）。
- 副本库既有启用配置：`system_embedding_config` → OLLAMA `http://127.0.0.1:11434/v1/embeddings`，模型 `qwen3-embedding:0.6b`，维度 1024，与本机 Ollama 服务实测匹配（真实调用返回向量）。
- 真实模型：`OPENCODE_ZEN_FREE` preset → `space-bunny-free`，AGENT 用途分配指向该配置（`config-purposes.json`），生产解析链路未改动。

### 2.2 三类场景实测（脚本 `run-real-use-check.mjs`，生产 API，无模型 mock）

隔离场景项目 `文档检索与流式验收 20261006`（projectId `e4aed8a8-a2c0-4092-a5c8-c96f8650aeac`）：2 里程碑 + 4 任务（DONE/TODO 逾期/IN_PROGRESS 缺日期/BLOCKED）+ 测试文档《星桥发布值班手册(验收专用 20261006)》（含独特事实：周三 02:00–02:30 发布窗口、回滚 15 分钟时限、三级审批）。

| 场景 | 运行 | 结论 |
| --- | --- | --- |
| Q1 常规进度分析 | `7ec2e759`，SUCCEEDED，8 步 | **通过**。正确使用 `list_tasks` 分页续读（limit=3 → nextCursor → total=3 → 投影 total=4），明确"还有 1 条未读到完整字段"；区分"证据充分/不足"，未把缺日期任务说成延期 |
| Q2 文档中存在的具体事实 | `3abab717`，SUCCEEDED，7 步 | **通过（成功链）**。见 2.3 |
| Q3 文档无法支持的结论 | （同会话第 3 问），SUCCEEDED，10 步 | **通过**。正确声明"当前项目数据里没有移动端 App 的排期记录"；检索/列目录均未命中后明确"值班手册的审批约定适用对象是服务本身，不是 App"，未编造 |

### 2.3 文档检索成功链（任务书要求的完整链证据）

**文档入库/索引 → 真实检索 → 模型可见工具输出 → Agent 回答与来源**，逐环实测：

1. **入库/索引**：上传后异步解析（Tika）→ 分块 → 真实 Ollama embedding → pgvector 落库。实测 `document_chunk` 5 条、`vector_dims=1024`，文档状态 READY（`document-final.json`）。
2. **真实检索**：Q2 中模型实际调用 `search_project_knowledge`（TOOL_CALL_STARTED seq 7），返回片段 similarity 0.72（"每周三 02:00–02:30(UTC+8)"）与 0.70（"回滚操作必须在 15 分钟内完成"），带完整 sourceIdentity（`q2-search-output.json`，来自 `agent_step.output_json`）。
3. **模型可见输出**：模型回答准确给出"发布窗口每周三 02:00–02:30（UTC+8）"与"回滚时限 15 分钟内"，依据来源标注为《星桥发布值班手册-20261006.md》及 documentId（`q2-messages.json`）。
4. **模型身份**：`q2-model-identity.json` 记录本轮 `OPENCODE_ZEN_FREE` / `space-bunny-free`。

### 2.4 结论

- `search_project_knowledge` 链路本身无缺陷：恢复 Ollama 服务后一次成功。上轮 `DOCUMENT_EMBEDDING_FAILED` 根因即本机 embedding 服务未启动，与代码无关。
- 历史文档部分 FAILED 为该时段服务未启动的历史索引失败，非本轮缺陷；未做批量重索引（不在本轮范围）。

## 3. B：正文实时流式展示

### 3.1 实现（沿用完整模型轮次链路，未引入第二套执行机制）

**后端**：

- `infrastructure/ai/turn/ModelContentPreview`：ThreadLocal + 显式捕获的正文观察通道（`activate`/`capture`/`push`/`finish`/`clear`）。观察者异常被吞掉，展示失败不影响模型调用与结算。
- `OpenAiCompatibleModelAdapter.turnStreamingSyncWithSession`（Zen 生产流式路径）：SSE 聚合循环内把**累计正文快照**推给捕获的观察者，流结束发 final 帧；聚合结果 `ModelTurnResult` 原样返回，工具调用仍在流结束后按既有协议校验执行。
- `AgentContentPreviewPublisher`：请求作用域发布器，按 80 字符/300ms 节流（final 帧总发），载荷 `{modelCallId, revision, text, final}`——自包含累计文本，按 revision 幂等替换。
- `AgentRuntimeCoordinator`：`beginModelCall` 拿到 `modelCallId` 后、`callModel` 前后激活/清理观察者（同 worker 线程 finally 清理）；`MODEL_STARTED` payload 增加兼容字段 `modelCallId`。
- `AgentRunEventRecorder.recordModelTurn(…, modelCallId)`：`recordModelTurnWithSettlement` 透传调用身份，`MODEL_COMPLETED` payload 增加 `modelCallId`（历史事件缺该字段按旧规则）。
- `AgentEventStreamService.publishContentDelta`：临时帧经既有订阅连接下发（SSE 事件名 `MODEL_CONTENT`），**不落 `agent_event`、不占序号、不进 replay、不更新 lastSequence/Last-Event-ID**；发送失败仅关闭该订阅。`AgentEventService.append` 仍是持久事件唯一入口。该服务在后续收口中把持久事件、正文预览、heartbeat 与初始 replay 统一到同一订阅发送线程，使所有 SSE 网络写退出模型读流线程、事务提交回调与调度线程（见 `docs/agent-real-use-and-streaming-review-20261006.md` 第 10 节）。

**前端**：

- `agent-event-stream.ts`：按 SSE 事件名分流——`MODEL_CONTENT` 帧走 `onContent`，不进入事件时间线；持久事件解析不变。
- `use-agent-workspace.ts`：`contentPreview` 状态机——`MODEL_STARTED(modelCallId)` 开启 → 帧按 revision 幂等替换（展示上限 8000 字符，超限仅停止追加并提示）→ 请求结束事件（完成带工具/失败/暂停/恢复/等待输入/取消）收口 → 纯文本轮的预览在最终 ASSISTANT 消息落库后由 `messages` watch 清除。切会话/切运行/`startEventStream`/`restoreSession` 一律丢弃预览。
- `AgentView.vue`：`.streaming-preview` 块安全文本插值（含生成光标与超限提示），沿用既有滚动跟随；最终回答仍用 `marked + DOMPurify` 渲染一次。

### 3.2 本轮发现并修复的真实缺陷：读流线程错配

初版实现把观察者放在 worker 线程 ThreadLocal 中，但 `JsonHttpModelClient.stream` 的读流回调在 `model-stream` 池线程执行——ThreadLocal 不可见，**逐 token 推送全部被静默丢弃**，只有流结束后 `finish()` 在 worker 线程发出的一帧到达。该缺陷曾被误判为"provider 整块交付"（真机初测仅 1 帧 final）；用 Java HttpClient 探针 + `JsonHttpModelClient` 回调时序探针定位后修复：发起线程 `capture()`，回调线程用捕获值显式 `push(observer, …)`。回归 `ModelContentPreviewObservationTest.pushesFromHttpClientReaderThreadStillReachTheCapturedObserver`（模拟另一线程回调）锁定该行为。

修复后真机重测（run `309ca900`，`sse-frame-real-model-final.json`）：全运行共 **51 个 content 帧**，其中较早一轮 3 帧；最终回答轮 **48 帧 = 47 个非 final 增量 + 1 个 final**，字符数 14 → 3159 递增，final 帧（+54.2s）先于 `MODEL_COMPLETED`（+54.25s）——真实 provider 实为逐块流式，"单块交付"结论系修复前的假象，已在报告第 5 节更正。

### 3.3 支持范围（如实声明）

- **支持**：Zen 传输的 OpenAI 兼容流式路径（`turnWithSession`，生产 OPENCODE_ZEN_FREE 实际路径）。
- **不支持（保持一次性完成展示，不冒充实时生成）**：非 Zen 的 Custom provider（生产适配器 `adapter.turn` 为非流式调用）、Anthropic/Gemini 适配器、Legacy CHAT-only 模式。
- 临时帧只在单实例进程内 fanout（与既有事件订阅一致）；断线重连不补发预览帧（已提交内容经持久事件恢复，在途请求从后续帧继续或回到"正在分析"提示，不因此重发模型请求）。

## 4. 验证矩阵实测结果

### 4.1 正式回归（脚本测试）

| 套件 | 结果 |
| --- | --- |
| 新增 `ModelContentPreviewObservationTest`（6：累计推送/final/观察者失败容错/无观察者 no-op/纯工具轮零推送/跨线程回调可达） | 全过 |
| 新增 `AgentContentPreviewPublisherTest`（4：载荷身份/节流合并/revision 单调/空文本） | 全过 |
| 新增前端 `agent-event-stream.test.ts` +2（帧分流/坏帧跳过）、`AgentView.streaming.test.ts` +5（两段增量→过渡说明让位/纯文本轮最终回答只出现一次/失败重试不混旧文本+过期帧忽略/暂停清空/超限停止追加） | 全过 |
| 受影响后端回归 10 套件（协调器 32、仓库 39、恢复 23、崩溃计时 5、暂停续跑 19、配置切换 9、行为 25、快照 3、跨 tick 7、流式 10） | **172 项全过**（线程修复前）；修复后新增 2 套件 10 项复跑全过 |
| 后端全量 `mvnw test`（线程修复**前**） | **1160 项：0 失败 0 错误，11 显式 opt-in 跳过**，BUILD SUCCESS（6m33s），日志 `backend-full-streaming-20261006.log` |
| 后端全量复跑（线程修复**后**，收口前最后一轮） | **1161 项：0 失败 0 错误，11 显式 opt-in 跳过**，BUILD SUCCESS（4m55s），日志 `backend-full-streaming-final-20261006.log` |
| 前端 `vue-tsc -b` | 零错误 |
| 前端 `vitest run` 全量 | **39 文件 212 项全过**（205 → 212，新增 7 项） |

### 4.2 真实模型验证（`OPENCODE_ZEN_FREE` / `space-bunny-free`，生产 API）

- 真增量：run `309ca900` 全运行 51 帧、最终回答轮 48 帧，字符数单调递增、final 先于 MODEL_COMPLETED（3.2 节）。**不是**结束时单块。
- 完整链与回答质量：2.2/2.3 节三类场景。
- 脚本化 SSE 与 UI 观察证据：`sse-frame-fresh.json`（订阅延迟 18–87ms 的完整流捕获）、`sse-frame-real-model-final.json`（脱敏）。

### 4.3 合成 peer 验证（隔离，生产 Zen 流式路径 + 同一前端）

为验证 UI 在途多状态，给测试设施 `ScriptedAcceptanceModel` 增加"分段流式验收"模式（每 1.5s 推一段，仅测试设施）；`BrowserAcceptanceHostTest` 用 `@MockitoBean` 把 Zen preset 指向合成 peer（离线宿主本就仅允许合成 peer 出站），并把真实 Zen 行"暂借"给 peer（密钥原值备份至 `target/zen-row-backup-20261006.json`，finally 块恢复原值与 AGENT 用途分配——退出后已实测恢复）。生产代码与配置解析规则未改动。

实测（bsk 浏览器 + observe）：

- **两段不同在途正文状态**：t+2.2s 显示第一段+"风险一"；t+4.4s 追加到"以上判断…"（`obs-state1-2.2s.txt`/`obs-state2-4.4s.txt`），"正在分析"行同时在途。
- **收口**：完成后最终回答渲染一次，预览块消失，无"正在输出"残留（截图 10、14）。
- **刷新**：Agent 页刷新后已提交回答正常恢复、无预览残留、无新增模型请求（RUN_SUCCEEDED 后无新 MODEL_STARTED）。
- **切会话**：切走→切回，旧会话内容不串页，回答不重复。
- 截图 15 张：`docs/acceptance-evidence/2026-10-06/real-use-streaming/shots/01–14`（01/02 为真机首轮在途与完成，08/10 为合成首轮，05–07 为非流式 Custom 路径一次性展示对照）。

### 4.4 provider 行为记录

- 真实 `space-bunny-free`：逐块流式（3.2 节最终回答轮 48 帧实测）；工具轮通常无正文，最终回答轮增量明显。
- 一次性大块到达的场景（修复前误判、以及部分短回答）在 UI 表现为"预览一次性出现后定格收口"，功能正确但无渐进效果——已按任务书要求区分实现回归与真实体验结论。

## 5. 边界与未验收项（如实）

1. **未验收**：Anthropic/Gemini/Custom provider 的流式适配（明确不支持，见 3.3）；多实例部署的帧路由；断线后未提交正文的恢复（设计即不承诺）；逐 token 持久化；Q1 类"模型未主动续读"的单次行为差异（沿用上轮结论，不改提示词）。
2. 真机 UI 在途双状态抓拍未命中窗口（回答轮在 +40s 后，observe/截图 RPC 在流式期间多次超时）；该证明由合成 peer UI 证据（4.3）+ 真机 SSE 最终轮 48 帧证据（4.2）共同覆盖，不声称"真机 UI 双状态截图已验收"。
3. 合成 peer 的分段模式与 Zen 暂借仅存在于测试设施（`ScriptedAcceptanceModel`/`BrowserAcceptanceHostTest`），退出恢复逻辑经实测验证；`target/zen-row-backup-20261006.json` 为副本库行备份（不入 Git）。
4. 临时帧的存量会话兼容：历史事件无 `modelCallId`，前端按旧规则渲染（无预览），恢复路径不受影响（`PersistedModelTurnRecoveryPostgresTest` 23 项通过）。

## 6. 资源与现场

- 已停止：真机宿主、浏览器宿主（finally 已恢复副本库 Zen 行与用途分配，实测核对 `space-bunny-free|https://opencode.ai/zen/v1`、AGENT 分配正确）、vite(15173)、bsk 会话。
- 保留：隔离副本 PG/验收 Redis/MinIO 容器（接手时即运行，数据卷保留供复核）；本机 Ollama 服务（用户已有服务，未动）。
- 业务库 `ai-collab-postgres` 全程未启动、未写入；本轮创建的隔离数据（项目 `文档检索与流式验收 20261006` 及会话/运行）保留在副本库供复核。

## 7. 收口提交

- 实现 + 测试 + 文档按可独立审查组织为本地提交（hash 见 `git log`）；后端全量复跑 **1161 项 0 失败**（4.1 节）。
- 未推送、未合并 main、未部署。

## 8. 证据索引

```
docs/acceptance-evidence/2026-10-06/real-use-streaming/
├── run-real-use-check.mjs          # A 阶段验收脚本(setup/waitdoc/session/q1/q2/q3)
├── scenario-doc.md                 # 隔离测试文档(独特事实源)
├── config-purposes.json            # AGENT 用途分配(keep-config 实测)
├── q{1,2,3}-{submit,run,events,messages}.json
├── q2-search-output.json           # search_project_knowledge 完整输出(agent_step)
├── q2-model-identity.json          # 本轮 provider/model
├── document-final.json             # 文档 READY 状态
├── sse-frame-fresh.json            # 订阅延迟 <100ms 的完整流捕获(合成 peer 分段)
├── sse-frame-real-model-final.json # 真机全运行 51 帧（最终轮 48）时序(脱敏)
├── obs-state1-2.2s.txt / obs-state2-4.4s.txt  # UI 在途双状态
├── state.json                      # 会话/项目/文档 ID(含本地 token,不入库于报告)
└── shots/01–14.png                 # 浏览器截图
```
