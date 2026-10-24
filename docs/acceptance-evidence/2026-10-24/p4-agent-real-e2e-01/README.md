# P4-AGENT-REAL-E2E-01：Agent 真实浏览器端到端业务验收

**交付状态：`AGENT_REAL_E2E_GAPS_FOUND`**

主链路（真实浏览器 → 真实后端 → 真实 PostgreSQL → 真实 Kilo 模型 → 真实工具执行 → 结果回流 → 最终回答 → 持久化 → 刷新恢复）全部真实验证通过；审批通过/拒绝门控真实验证通过。发现并最小修复 1 个真实可复现的缺陷（无效 UUID 参数穿透 Schema 校验导致运行失败）；真实模型（nemotron-3.5-lightning:free）存在持续传非法参数的模型质量局限，导致审批提案创建对提示词措辞敏感。暂停/恢复/取消/重试的真实浏览器场景因预算不足未执行（NOT_EXECUTED，离线契约测试补充）。

---

## A. Git

- 任务起始基线：`3b87d7646ddb1835f97a147a7e2512d01298700e`（dev = origin/dev，工作区干净）
- `main` / `origin/main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（全程未触碰）
- 本轮改动：ToolArgumentValidator 最小修复 + 回归测试 + 本轮证据文档（见 [regression-results.json](regression-results.json)）

## B. 环境与模型（详见 [environment.json](environment.json)）

- 前端 Vite dev 5173、后端 local profile 8080（中途因缺陷修复重启一次）、postgres/redis/minio（Docker，healthy）。
- 浏览器：browser-skill（bsk）+ Edge，复用既有登录态，账户 U04 合成管理员（p3u04_admin），未读取任何凭据。
- 隔离验收项目：`P4-AGENT-REAL-E2E-01 隔离验收项目`（`b4b55544-dab6-43f1-9b5b-c20b1d79ac59`），3 条合成任务（1 IN_PROGRESS + 2 TODO）全部经真实浏览器创建。
- 模型：**Kilo Gateway Free `nvidia/nemotron-3.5-lightning:free`**。AGENT 用途解析：U04 未设显式分配 → is_default → Kilo；落库探针 CONNECTIVITY/STREAMING/NATIVE_TOOLS 全部 PASS 且 fresh（非 STALE）。

## C. 真实模型调用预算（详见 [real-model-call-ledger.json](real-model-call-ledger.json)）

- 本轮独立授权：**12 次**（用户确认；上一轮 14 次已结束不转移）。
- 实际使用：**11 次**（5 个 Run，全部 Kilo 免费模型），剩余 1 次未使用。
- 计数口径：按真实到达 Provider 的生成请求（每轮 MODEL_TURN 计 1），失败与重试计入。
- 未自动切换收费模型、未更换用户身份、未绕过上游额度。

## D. Agent 执行链路审计结论（以源码为准，验收前完成）

- 链路：`POST /sessions` / `POST /sessions/{id}/messages` → `agent_run` QUEUED → `AgentRuntimeJob` 每秒 `FOR UPDATE SKIP LOCKED` 领取（6 分钟租约 + claim_version fencing + 2 分钟续租）→ `AgentRuntimeCoordinator` → `RoutingAgentModelExecutor`（用户+AGENT 用途每轮解析）→ `NativeToolCallingExecutor` → Kilo 网关真实出站 → 工具批次（只读并行/写提案串行）→ 收敛判定 → FINAL_ANSWER。
- 状态机 11 态；终态无出边。写工具全部走审批提案（任务/里程碑/记忆）；规划操作走受控写（仅 OWNER/ADMIN + depth=0）；永久禁止工具集 10 项（run_sql、delete_* 等）。
- 审批为**提案-解耦**模式：提案创建时 Run 即 SUCCEEDED，批准/拒绝由审批服务同事务完成写入与事件，无 Run 自动唤醒。
- SSE：`agent_run.last_event_sequence` 行内自增 + `UNIQUE(run_id, sequence_no)`；Last-Event-ID 续传；终态收口。

## E. 核心验收结论（详见 [agent-scenarios.json](agent-scenarios.json)）

### 真实只读 Agent 任务（必须，全部 PASS）

Run `82f16d75`（36.9s，2 轮模型调用，25,623/2,948 tokens）：

1. 真实浏览器提交任务 → Run 创建并领取。
2. 模型第 1 轮真实输出 4 个工具调用：`get_project_overview`、`check_project_progress`、`list_tasks`、`analyze_project_risks`（`agent_tool_invocation.source_mode=NATIVE_TOOLS`，全部 SUCCEEDED）。
3. **工具结果真实进入下一轮**：第 2 轮输入 14,294 tokens（+工具结果注入），最终回答包含 list_tasks 返回的真实任务 UUID/状态/负责人未分配（assigneeId null）等 DB 事实。
4. 回答与数据库逐条一致，无编造；Run 前后 project_task=3、approval=0、memory=0，无任何未经请求的写入。

### SSE 与持久化（必须，全部 PASS）

- 页面实时展示「Agent 正在处理」→ 执行过程折叠块 → 最终回答；事件 sequence 1–17 与 `agent_run_event` 一一对应。
- 真实刷新：会话/回答/时间线完整不重复、状态「Agent 已完成」、右栏权威快照 Steps 2/64 · Tools 1/64、「SSE 未连接」（终态不重连）。
- `agent_step` 7 行、`agent_message` 2 行、token 结算 actual=used。

### 审批机制（详见 [approvals.json](approvals.json)）

- **批准前无写入**：提案 PENDING 时 project_task 仍为 3 / agent_memory 仍为 0。
- **批准**：UI 确认对话框 → approval APPROVED → 任务真实写入（TODO/HIGH，创建者=审批人）→ `APPROVAL_APPROVED` 事件 → 执行次数=1。
- **拒绝**：UI 强制输入原因 → approval REJECTED + rejection_reason → 0 条记忆写入 → `APPROVAL_REJECTED` 事件 → 刷新后「已处理提案（1）·已拒绝」。
- 幂等/nonce/revision 冲突：以离线契约测试为准（AgentApprovalServiceTest 11 用例 + ApprovalToolRevalidationTest 12 用例全绿），未通过浏览器恶意点击制造污染。

## F. 缺陷发现与最小修复（详见 [regression-results.json](regression-results.json)）

**DEFECT-01（真实复现 → 已修复 → 真实复验）**：`ToolArgumentValidator` 不校验 schema 声明的 `format: "uuid"/"date"`。真实模型把 `assigneeId` 传为 `"None"` 时穿透 Schema 校验，深入到 Jackson 反序列化才失败，整个 Run 以 `AGENT_TOOL_EXECUTION_FAILED`（retryable=false）终止且模型无纠正机会——而同 Run 中无效枚举值却被正确拒绝并给了模型纠正轮（模型确实纠正成功）。同一类无效参数两种命运。

修复：`validateString` 增加 uuid/date 两条格式校验（约 25 行，与 DTO 的 UUID/LocalDate 字段类型一致）。修复后真实复验：`"None"` 在 Schema 阶段被拒（`.assigneeId必须是合法的 UUID`），模型获得纠正轮；新增 2 个回归用例；Agent 模块 762 用例全绿。

## G. 模型质量局限（如实记录，非后端缺陷）

nemotron-3.5-lightning:free 倾向把「负责人留空」实现为 `assigneeId: "None"`，且在纠正反馈后仍重复同一错误（run ea469167 两次相同非法参数 → `PARAMETER_CORRECTION_EXHAUSTED`，运行按设计终止）。后果：**审批提案创建的成功率对该免费模型的提示词措辞敏感**（明确「不要传 assigneeId 字段」后一次成功）。这是免费模型能力边界，后端防护行为正确。

## H. 未执行场景与遗留（诚实缺口）

| 场景 | 状态 | 补充证据 |
| --- | --- | --- |
| 真实浏览器暂停/恢复 | NOT_EXECUTED | AgentPauseResumePostgresTest 19 用例全绿 |
| 真实浏览器取消 | NOT_EXECUTED | AgentWorkerTest/SchedulerTest（含于 762 全绿） |
| 真实浏览器重试 | NOT_EXECUTED | AgentRunRetryPostgresIntegrationTest 6 + AgentRunServiceRetryTest 6 全绿 |
| 跨账户真实权限隔离 | NOT_EXECUTED | requireMember/requireAdmin 由 AgentRuntimeBehaviorTest 25 + AgentApprovalServiceTest 11 覆盖；无第二合法登录态（不伪造身份） |
| PLANNING_JSON 探针 | NOT_EXECUTED | 本轮 Agent 场景不依赖规划；套件无单步跳过，剩余预算不足 |

未执行原因统一为：剩余预算 1 次无法保证不超限（暂停/取消/重试真实场景均需启动可能消耗 ≥2 次的 Run），按任务书「无法保证剩余预算足够时应停止」执行。DB 中保留 2 个真实 FAILED Run（`24b71f55`、`ea469167`）可供后续预算轮做真实重试复验。

## I. 回归与 CI

- 后端 Agent 模块定向测试：762 用例全绿（3 跳过为历史即跳过的 RealAgentLoopPostgresTest）。
- 完整 `mvnw verify`：**BUILD SUCCESS**（含 Testcontainers 集成测试，11 分 57 秒，与 CI 同命令）。
- 前端（无代码改动）：lint 0 errors / typecheck 通过 / Vitest 393 全绿 / build 成功。
- CI：run [`40057436278`](https://github.com/BangShou1st/ai-collab/actions/runs/40057436278) · attempt 1 · head SHA `536ca3ada735fed5b1cf306e43dad37cad62836c` · **completed / success**（Backend Maven verify ✓ · Frontend pnpm ✓）。

## J. 观察项（非阻断）

1. 前端 `refreshApprovals` 仅加载 activeRun 的审批：非最新 Run 的已批准卡片刷新后不在页面展示（服务端记录完整，拒绝卡可正常展示）。轻微 UX 局限。
2. 历史 ASSISTANT 消息中的「当前审批状态：待审批」是提案创建时刻的确定性摘要文本，不随审批状态更新（实时状态以审批卡/DB 为准）。
3. 部分 Run 的 `agent_run.model_name/model_provider` 列与 `model_configuration_snapshot` 为空（页面模型芯片来自事件流，展示正常）；快照写入时机建议后续核对。
4. `AgentToolPolicy` 与 `AgentToolRegistry.allowed` 存在双份写权限判定（审计发现，未见漂移），建议后续收敛。

## K. 最终状态判定：`AGENT_REAL_E2E_GAPS_FOUND`

依据：主链路（真实模型工具调用、结果反馈、SSE、持久化、审批门控）真实验证全部通过且无阻断缺陷；但 (1) 免费模型对写提案场景的参数可靠性存在真实局限（G 节），(2) 暂停/恢复/取消/重试与跨账户隔离的真实浏览器场景未执行（H 节）。不宣称 Agent 达到完全发布就绪；扩展场景需在新的预算轮补齐。

## L. 证据文件

- [environment.json](environment.json) — Git/环境/模型解析/预算
- [real-model-call-ledger.json](real-model-call-ledger.json) — 11 次真实生成请求台账
- [agent-scenarios.json](agent-scenarios.json) — 18 个验收场景矩阵
- [tool-execution.json](tool-execution.json) — 真实工具执行与结果回流证明
- [approvals.json](approvals.json) — 审批通过/拒绝全量记录
- [sse-and-persistence.json](sse-and-persistence.json) — SSE 与刷新持久化
- [pause-resume-retry.json](pause-resume-retry.json) — 未执行场景与离线证据映射
- [budget-and-safety.json](budget-and-safety.json) — v1/v2 资源策略与循环保护
- [regression-results.json](regression-results.json) — 缺陷修复与回归
- screenshots/ — 7 张脱敏截图（01 只读回答 · 02 刷新持久化 · 03 审批卡 · 04 批准确认 · 05 批准后 · 06 拒绝原因 · 07 拒绝后）
