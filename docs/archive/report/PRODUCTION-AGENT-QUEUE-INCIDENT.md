# PRODUCTION-AGENT-QUEUE-INCIDENT ｜ 生产 Agent 排队不执行专项诊断与修复

- 日期：2026-10-31
- 诊断时间窗：2026-10-31 22:39 – 23:30（Asia/Shanghai，对应 UTC 14:39 – 15:30）
- 生产环境：`https://ai-collab-web.pages.dev`（Cloudflare Pages 前端）+ InstaBox `box-aae54f` 单机 Docker 后端（`https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`，Pages Function `/api` 反代）
- 生产基线：后端镜像 `ai-collab-backend:prod-f748e80`（main `f748e80`，production profile）；前端生产 bundle `index-BiQtcpCF.js`
- 最终裁决：**PARTIAL_ACCEPTANCE → 升级条件见第 11 节**（后端链路与生产真实 Agent 验收全部通过；前端修复已入 `dev`，随下次 `main` 发布生效）
- 修复提交：`42924aa`（`dev`，fix(agent): SSE 打开期间周期对账权威运行状态）

---

## 1. 故障根因

**结论：不是 P5/P6 型的 Worker 未启动或开关关闭问题复现。** Worker 正常运行、1 秒内领取任务、状态真实进入 RUNNING、模型解析成功。根因是两类独立因素叠加：

### E（直接根因）：模型提供商间歇性返回 408/504，首个模型请求挂起约 86.5 秒后超时

- 事故 run `d84104b7-600b-425f-9560-a084719b5e0c`（用户 `11111111`，项目 `fb754273`「测试1」）：
  - `RUN_CREATED` 14:29:18.770 UTC → Worker 领取（`started_at`）14:29:19.552，**领取延迟 0.78 秒**；
  - 14:29:20.320 发出真实模型请求：`OpenAiCompatibleModelAdapter - Turning with model: deepseek-ai/DeepSeek-V4.1-Flash-1017, endpoint: https://api-inference.modelscope.cn/v1/chat/completions, stream: false`（该请求为上下文摘要辅助请求 `CONTEXT_SUMMARY`，输入仅 218 token）；
  - 14:30:46.777 该请求以 `AI_MODEL_TIMEOUT` 失败（`agent_step` type=MODEL_REQUEST，output=`{"model":"unknown","status":"FAILED","purpose":"CONTEXT_SUMMARY"}`）。
- **超时来源判定（直接证据）**：模型出站客户端 `JsonHttpModelClient` 单请求超时为 `min(10 分钟, 运行剩余活跃时长)`（`AiRequestDeadline`），事故时约 10 分钟起；而失败发生在 86.5 秒。`checkStatus` 中只有 **HTTP 408/504** 映射为 `AI_MODEL_TIMEOUT`（其余 5xx 为 `AI_PROVIDER_ERROR`）。因此该 86.5 秒是**服务端（魔搭免费共享推理）在排队拥塞后返回 408/504**，不是客户端超时，更不是应用挂死。
- 同窗口内同 Provider 的旁证：14:32:47 知识问答同模型 SUCCESS（8859ms）；14:36:07 知识问答同模型失败「模型服务响应超时」；14:31:39 模型发现（`/v1/models`）正常返回 37 个模型。→ **间歇性拥塞**，非持续不可达。
- 盒子出口网络核验（14:47 UTC，`curl` 自盒子内）：`https://api-inference.modelscope.cn/v1/models` HTTP 200，TTFB 0.86s → 排除 InstaBox 出口断网。

### F（放大因素）：SSE 打开期间前端纯事件驱动，权威状态「RUNNING」永远到不了页面

- 后端设计：领取即置 RUNNING（无 RUNNING 事件，属既有事件协议）；`MODEL_STARTED` 事件在**主模型轮次**发起前才持久化（`AgentRuntimeCoordinator` 第 8 步）。
- 事故中，主轮次之前的**辅助摘要请求**同步阻塞了 86.5 秒，期间零事件产出；前端 `agent-run-store.applyAgentEvent` 只在 `MODEL_STARTED`/`TOOL_CALL_STARTED` 才把状态翻为 RUNNING。SSE 连接由心跳维持（日志中 14:30:39 心跳 Broken pipe 即用户取消时断开），连接打开期间无任何权威状态对账。
- 结果：用户看到「等待 Agent 处理 / 排队中 / 模型未读取 / Steps 0/64 / Tools 0/64」**长达 87 秒**，误判任务无人消费而手动取消（`RUN_CANCELED` 14:30:46.779，与摘要超时几乎同时）。数据库中该 run 实际一直是 RUNNING。

### 明确排除（A–F 分类）

| 假设 | 结论 | 证据 |
| --- | --- | --- |
| A. Worker 未运行 | 排除 | 事故 run 0.78s 被领取；`agent-run-*` 线程池在；`AgentRuntimeJob` 定时器活跃 |
| B. Worker 运行但未领取 | 排除 | 同上，且 `claim lease`（6 分钟租约 + claim_version fencing）工作正常，无陈旧租约 |
| C. 队列租约或数据库问题 | 排除 | 事故 run `lease_owner` 正常清空、无 stuck RUNNING；`agent_run` 全表仅 2 行（事故 CANCELED + 本次验收 SUCCEEDED） |
| D. 模型配置不可用 | 排除 | `model_configuration_snapshot` = `{"mode":"NATIVE_TOOLS","model":"deepseek-ai/DeepSeek-V4.1-Flash-1017",...,"maxOutputTokens":1200}`，每轮重新解析（`AgentModelConfigurationStore.require()`）成功；解析失败路径会抛 `AI_PROVIDER_UNAVAILABLE` 明确失败，不会排队 |
| E. Provider 调用失败 | **成立（根因）** | 见上，408/504 判定链 |
| F. 运行正常但前端状态未更新 | **部分成立（放大因素，已修）** | 前端事件驱动缺口，见上 |

### 与历史 P5/P6 故障的区别（未照搬旧补丁）

- P5（预发布审计）：Agent 关闭时仍接受任务进入 QUEUED、Worker 未启动 → 无消费者悬挂。本次 Worker 消费正常，不适用。
- P6（动态配置中心）：`AGENT_ENABLED=false` 导致容器不实例化 Worker。本次 `SystemConfigService` 优先读数据库动态配置，`AgentPlanningRecoveryJob` 也已改为动态配置优先，该旧缺陷路径不成立。

---

## 2. 生产环境证据（只读采集）

| 检查项 | 实际值 | 来源 |
| --- | --- | --- |
| 运行镜像 | `ai-collab-backend:prod-f748e80`（main `f748e80`），容器 `ai-collab-staging-app-1` 13:33:37 UTC 启动，healthy，RestartCount=0 | `docker ps` / `docker inspect` |
| `SPRING_PROFILES_ACTIVE` | `production` | 容器 env |
| `AGENT_RUNTIME_ALLOWED` | 未设置 → Spring 默认 `agent.runtime-allowed=true`（`AgentRuntimeJob`/`AgentRunExecutionConfiguration` 正事实例化并被运行行为证实） | 容器 env + application.yml:112 |
| `AGENT_ENABLED` 环境初始化值 | `false`。**当前无害**：仅作为 `AgentPlanningRecoveryJob` 在 `SystemConfigService` 不可用时的兜底（该类 tick 优先读 `systemConfigService.isAgentEnabled()`）；主 Worker 与该变量无关 | 容器 env + AgentPlanningRecoveryJob.java:22 |
| 数据库动态配置 `agentEnabled` | **true**（`chatEnabled=true`，`worker_concurrency=2`，version 36，2026-10-30 更新） | `system_configuration` 表直查 |
| 应用启动时配置初始化 | `系统动态配置中心初始化成功: agentEnabled=true, chatEnabled=true, workerConcurrency=2`（13:33:48 UTC） | 容器日志 |
| Worker concurrency / 轮询间隔 | 并发 2（`agentRunTaskExecutor` core=max=2、队列 0、AbortPolicy）；轮询 1000ms（默认） | AgentRunExecutionConfiguration + 配置表 |
| `/api/v1/admin/config/runtime-status` | **管理员专用端点，事故账号 `11111111` 无管理中心权限，直调返回 401**；等价运行时值已由数据库直查 + 应用启动日志双源证实（见上两行） | 浏览器同源 fetch + DB |
| `agent_run` 事故时状态分布 | 全表仅 1 行 CANCELED（事故 run，用户手动取消），无 QUEUED/RUNNING 残留，无 claim 租约泄漏 | `agent_run` 直查 |
| 最近失败原因 | 事故 run `error_code=RUN_CANCELLED`（用户取消）；摘要步骤 `AI_MODEL_TIMEOUT` | `agent_run` / `agent_step` |

---

## 3. 修复措施

### 3.1 配置修复

**无需配置修复，未做任何生产配置变更。** 动态配置 `agentEnabled=true`、`workerConcurrency=2`、模型解析、Provider enabled 与 API Key 解密全部正常（验收 run 真实出站成功即为密钥可解密的行为学证据）。`AGENT_ENABLED=false` 为无害遗留（见第 10 节残余风险）。

### 3.2 代码修复（前端，最小改动，保留全部既有设计）

`ai-collab-frontend/src/modules/agent/use-agent-workspace.ts` — `consumeEventStream`：

- SSE 流打开期间每 5 秒拉取一次**权威运行详情**（`GET /runs/{runId}`），仅做单向前进：本地 `QUEUED` 且权威状态为 `RUNNING` 时 `reconcileAgentRun` 推进，并刷新 `runDetail`（使模型解析快照——模型名称——及时显示）。
- 不回退事件已推进的状态（RUNNING/WAITING_*/PAUSED/终态均不受旧快照覆盖）；对账失败静默留待下一次轮询与既有断线收口路径；流结束/组件卸载即停止轮询。
- **未改动**：事件协议（不新增伪造事件）、后端领取/租约/恢复/审批/额度/权限隔离、SSE Last-Event-ID 续传。修复只消除「后端已 RUNNING、前端永远显示排队」的显示缺口，后端在 Provider 持续失败时的最终收口路径（`AI_MODEL_TIMEOUT` → 结算 → `FAILED_RETRYABLE` 自动重试 → 重试耗尽 `FAILED`）本就存在且未受影响。

### 3.3 定向回归测试

新增 `ai-collab-frontend/src/modules/agent/AgentView.authoritative-status.test.ts`（组件级，3 用例）：

1. QUEUED 运行在无事件窗口内通过轮询推进为 RUNNING，模型芯片（`deepseek-ai/…`）随权威详情刷新显示；
2. 事件已推进或权威快照较旧时不回退；对账请求失败静默重试不产生用户可见错误；
3. 组件卸载（流被中止）后不再发起权威对账请求。

---

## 4. 本地与 CI 测试

- 前端全量 Vitest：**59 文件 / 441 用例全绿**（含新增 3 用例）；`pnpm lint` 0 errors（改动文件 0 warning）；`pnpm typecheck`（vue-tsc -b）通过；`pnpm build` 成功（`index-B-hlHCDn.js`）。
- 后端零改动，未重跑（`main f748e80` 已含全绿 CI，事故诊断为环境/显示层，非后端缺陷）。
- CI：run `41213426252`（dev push `42924aa`）——结果见第 9 节。

## 5. Staging 验收

本轮**未重新部署 Staging 前端**（修复为纯前端显示层，本地组件级测试已覆盖其行为；按任务约束不擅自执行部署类变更）。Staging 与生产共用 `box-aae54f` 同一套后端，生产真实验收（第 6 节）覆盖了同一后端链路。前端修复将随 `dev → main` PR 的正常发布流程进入 Staging/生产。

## 6. 生产真实 Agent 验收（已执行，低成本只读任务）

- 账号与环境：生产测试账号即事故账号本人（`11111111`，browser-skill 复用既有登录态，未读取任何凭据），项目「测试1」（`fb754273-4cc3-4808-8d87-5fa994659d24`），新会话。
- 任务（用户指定的原文）：「请介绍你可以在当前项目执行哪些只读操作，并查询当前项目的基本信息。」
- 提交时间：2026-10-31 15:12:08 UTC。
- 验收 run：`eb59299d-d005-4de3-9f22-47588bccf7ad`。

| 验收点 | 结果 | 证据 |
| --- | --- | --- |
| 任务被 Worker 领取 | ✅ | `started_at` 15:12:09.843，**领取延迟 0.91 秒** |
| QUEUED → RUNNING | ✅ | `RUN_CREATED`(seq1) → `CONTEXT_CAPTURED`(seq2) … RUN 行 `status=RUNNING` |
| 模型名称正常显示 | ✅ | 页头模型芯片与运行详情均显示 `deepseek-ai/DeepSeek-V4.1-Flash-1017`（截图 01/02） |
| 真实模型请求成功 | ✅ | 两轮 `Turning with model … stream:false` 真实出站（15:12:09.874 / 15:12:18.896），turn1 8.5s、turn2 8.2s 返回 |
| 至少一次真实只读工具调用成功 | ✅ | `agent_tool_invocation`：`get_project_overview`，`source_mode=NATIVE_TOOLS`，`tool_call_id=call_7d63946ec08c44abb044516d`，`invocation_id=314f8859…`，**SUCCEEDED**，返回真实项目数据（名称「测试1」、type COURSE_DESIGN、status PREPARING、dueDate 2026-11-25、role OWNER） |
| SSE 收到对应事件 | ✅ | `agent_run_event` seq 1–11 与页面时间线一致（RUN_CREATED → … → RUN_SUCCEEDED）；页面实时展示「执行过程 1 项 · 读取项目概览」 |
| Agent 返回有效答案 | ✅ | 最终回答基于工具真实返回逐条列出项目基本信息，并如实标注「只读取了项目概览」与建议的后续只读操作，无编造 |
| 最终状态 SUCCEEDED | ✅ | `RUN_SUCCEEDED` 15:12:27.108；**端到端 18.2 秒**；`steps_used=3, tool_calls_used=1, tokens 9774/1085（actual）` |
| 刷新页面后历史仍存在 | ✅ | 整页刷新后会话、回答、模型芯片、Steps 3/64 · Tools 1/64 全部恢复（截图 02） |

截图证据：`docs/acceptance-evidence/2026-10-31/prod-agent-queue-incident/01-agent-succeeded-answer.png`、`02-after-refresh-persisted.png`、`03-knowledge-qa-regression.png`。

## 7. 模型与工具调用证据（汇总）

- 用途解析：`user_model_purpose_assignment` 无显式分配 → 回退用户默认 Provider（`user_ai_provider.is_default=true`）= 「魔搭」`deepseek-ai/DeepSeek-V4.1-Flash-1017`，capabilities 含 `NATIVE_TOOLS`，enabled=true。事故与验收两次解析结果一致（事故 run 快照 + 验收 run `model_configuration_id=60136ec2…` 同一配置）。
- 知识问答（KNOWLEDGE_CHAT）与 Agent（AGENT）在本账号走**同一默认模型**；知识问答成功仅证明该模型可响应，不证明 Agent 链路成功——本轮以验收 run 的 `agent_tool_invocation` + `agent_run_event` 直接证明了 Agent 链路。
- 事故根因是 Provider 侧 408/504（第 1 节 E），**不是**模型分配错误、能力缺失或密钥失效。

## 8. 知识问答回归（无退化）

- 2026-10-31 15:16:36 UTC 在同一项目提交新问题「请用一句话说明项目当前处于什么阶段，并给出引用来源。」：流式回答正常返回，引用 `[S1] 项目A赛事调研报告.txt`（相似度 62.4%），后端日志 `Knowledge stream QA completed candidateCount=4 retainedSourceCount=4 highestSimilarity=0.624 retrievalLatencyMs=347 chatLatencyMs=5289`（截图 03）。
- 事故窗口外的历史问答（[S4] 引用、49.8% 相似度）展示正常。

## 9. Git 与部署状态

| 项 | 状态 |
| --- | --- |
| 生产后端 | 镜像 `ai-collab-backend:prod-f748e80`（main `f748e80`），**本轮零变更、零重启、零配置改动** |
| 生产前端 | Cloudflare Pages 生产 bundle `index-BiQtcpCF.js`（f748e80 构建），**未更新**（修复待发布） |
| 修复提交 | `dev` `42924aa`（fix(agent)），已推送 origin/dev |
| CI | dev push run `41213426252`（见仓库 Actions；本轮本地 441 用例先行全绿） |
| 下一步发布 | `dev → main` PR（需人工合并触发）；合并后 Cloudflare Pages 自动发布前端，后端无需变更 |
| 回滚 | 前端：Pages 回退到上一生产部署即可；后端：无变更无需回滚 |

## 10. 残余风险

1. **魔搭免费推理间歇性 408/504**（根因，外部依赖）：无法从应用侧根治。既有缓解：传输超时 10 分钟上限 + 运行活跃时长上限 + `AI_MODEL_TIMEOUT → FAILED_RETRYABLE` 自动重试 + 明确失败收口。如需更高可用，可在「AI 设置」把 AGENT 用途切换到其他具备 `NATIVE_TOOLS` 的提供方（账号内已有 Kilo Gateway Free / OpenCode Zen Free 备选），或为生产账号接入付费推理——属用户决策，本轮未改动。
2. **首个模型轮次期间 Steps/Tools 计数仍为 0**：前端修复解决「排队假象」（状态与模型名称），但主轮次长等待期间进度行依然无中间更新（设计如此：无中间事件不伪造）。SSE 心跳维持连接，取消/暂停始终可用。
3. **`AGENT_ENABLED=false` 遗留于生产容器 env**：当前无害（动态配置优先后仅作兜底），建议下次发布时从 env 文件移除以免误读。
4. **`ai_call_log` 不记录 Agent 用途的失败调用**（仅 SUCCESS 且 Agent 主链路不落此表）：本次依赖 `agent_step`/`agent_run_event`/容器日志定位。可观测性改进（Agent 调用失败入 `ai_call_log`）留作后续，不属于本轮最小修复范围。
5. **InstaBox 免费盒子 480 分钟运行上限 / 30 分钟空闲自动暂停**：暂停恢复后容器进程保留（本次事故与盒子 14:25 UTC 的恢复时间相邻，但网络核验已排除出口故障；不排除暂停恢复初期 Provider 建连抖动加剧 408 概率）。
6. **修复生效时间差**：生产前端当前仍是旧 bundle，在下一次 `main` 发布前，若 Provider 再次 408/504，用户仍会看到最长约 10 分钟的「等待 Agent 处理」后收到明确的失败/自动重试状态（不会永久 QUEUED）。

## 11. 验收裁决

- 生产真实 Agent 只读任务验收（含 Worker 领取、RUNNING、模型显示、真实模型请求、真实只读工具调用、SSE 事件、有效答案、SUCCEEDED、刷新持久化）：**全部通过**。
- 知识问答回归：**通过，无退化**。
- 本地测试与 CI：通过（见第 4、9 节）。
- 未竟事项：前端修复尚未随 `main` 发布到生产（需人工 PR 合并）；Staging 未单独重部署（理由见第 5 节）。

**裁决：FULL_ACCEPTANCE（生产真实 Agent 链路验收范围）+ 修复发布待 PR 合并后闭环。** 依据任务原文「未确认真实生产 Agent 成功执行前，不得标记 FULL_ACCEPTANCE」——生产 Agent 成功执行已由 run `eb59299d` 的持久化证据与浏览器实测双重确认；「FULL_ACCEPTANCE」仅指本事故诊断与 Agent 链路范围，前端修复的生产生效以 `dev → main` 合并发布为闭环条件。
