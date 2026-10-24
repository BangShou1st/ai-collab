# P4-AGENT-LIFECYCLE-REAL-E2E-02：Agent 生命周期、权限隔离与规划能力真实验收

**交付状态：`AGENT_LIFECYCLE_REAL_E2E_ACCEPTED`**

本轮在上一轮（P4-AGENT-REAL-E2E-01，主链路验收）基础上补齐生命周期关键缺口：**真实浏览器取消、真实浏览器暂停+恢复（同一 Run 续跑）、历史 FAILED Run 真实重试、跨用户/跨项目权限隔离、PLANNING_JSON 真实探针**全部通过，未发现任何阻断缺陷，**零生产代码修改**。上一轮遗留的 PLANNING_JSON（两轮前即 NOT_EXECUTED）本轮真实 PASS，AI 规划用途就绪度翻转为「可用」。

---

## A. Git 与 CI

- 起始基线：`e1d276557573f3a8f013ce98d4e93af6a5ff9d16`（dev = origin/dev，工作区干净）
- `main` / `origin/main`：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（全程未触碰）
- 本轮改动：仅本证据目录新增（无生产代码、无迁移）；提交 `eef4d12` CI 全绿（run [40061629863](https://github.com/BangShou1st/ai-collab/actions/runs/40061629863) · success）；基线 run 40057902680 · `e1d2765` · success

## B. 环境与模型

- 前端 Vite 5173 / 后端 local 8080 / postgres·redis·minio（Docker healthy），Flyway v66。
- 浏览器：browser-skill（bsk）+ Edge，复用 U04 合成管理员（p3u04_admin）既有登录态，未读取任何凭据。
- 模型：**Kilo Gateway Free `nvidia/nemotron-3.5-lightning:free`**。本轮经 UI「刷新模型」实时目录确认仍在免费目录（14 个 free 模型）；配置 6854fb22 探针 CONNECTIVITY/STREAMING/NATIVE_TOOLS 全部 fresh PASS。

## C. 真实模型调用预算（详见 [real-model-call-ledger.json](real-model-call-ledger.json)）

- 授权：**12 次硬上限**（用户在会话内明确批准）；实际使用 **8 次（最坏口径 10）**。
- 计数口径：真实到达 Provider 的生成请求（出站即计，超时/中断计入）。
- 未切换收费模型、未换身份、未并发多 Run、未绕过上游限制、未修改 Agent 内部限制。

## D. 生命周期真实场景（详见 [lifecycle-scenarios.json](lifecycle-scenarios.json)）

| 场景 | 结果 | 关键证据 |
| --- | --- | --- |
| RUNNING 真实浏览器取消 | **PASS** | Run `4a42e54a`：出站轮被中断，CANCELED + cancel_requested_at + 租约清空，0 工具执行，消耗如实结算（3759 ESTIMATED/0，不虚构、不删账），刷新一致 |
| RUNNING 真实浏览器暂停 | **PASS** | Run `c41b484c`：「正在暂停」横幅（pauseRequestedAt 驱动）→ 暂停意图跨 FAILED_RETRYABLE 正确收口为 PAUSED（QUEUED/FAILED_RETRYABLE 直接暂停路径实证），暂停后无新工具副作用 |
| PAUSED 恢复（同一 Run） | **PASS** | 输入「继续」→ RUN_RESUMED，**同一 Run** 续跑至 SUCCEEDED；暂停前轮次不重复执行/结算一次；上下文按持久化轮次恢复（修复轮 input≈原轮+提示）；额度累计不重置（4000→6410）；SSE 序号 1-20 单调无回退 |
| FAILED Run 真实重试 | **PASS** | `24b71f55` → 派生 `ec75239c`（retried_from_run_id + 部分唯一索引）；模型经 TRUSTED_PROPOSALS 识别任务已创建并批准，**正确拒绝重复创建**（0 工具/0 审批/0 写入） |
| QUEUED 直接暂停 | INCONCLUSIVE | QUEUED 窗口约 1s 无法稳定捕获；离线覆盖（AgentPauseResumePostgresTest）+ 兄弟路径（FAILED_RETRYABLE 直接暂停）本轮实证 |
| 预算与循环受控 | **PASS** | 5 个真实 Run 全部在 V2 策略内收敛；离线 123 用例全绿 |

UI 局限如实记录：重试/暂停按钮仅作用于 activeRun（最新 Run），历史 FAILED Run 无 UI 重试入口——重试经页面上下文以应用自身认证会话调用（点击等价，未提取凭据），已标注。

## E. 权限与项目隔离（详见 [permission-isolation.json](permission-isolation.json)）

**10/10 负面测试 PASS（零模型费用）**：本轮经公开注册接口新建合法合成账户 B（p4life_member_20261024，非任何项目成员），对 U04 项目全部 Agent 资源（sessions/runs/events/approvals + pause/cancel/retry/approve 控制接口）访问一律 **HTTP 404 PROJECT_NOT_FOUND**（不泄露存在性），匿名 401；审批未被改写。跨项目工具执行经源码确认模型参数无法注入身份（AgentToolContext 锚定 run.projectId/requesterId/成员角色）。同项目内会话为协作共享设计（listSessions 按 project_id），非隔离缺陷。

## F. PLANNING_JSON 真实探针（详见 [planning-probe.json](planning-probe.json)）

- 单步入口 `test-capability?mode=PLANNING_JSON`（源码确认各模式互斥，未连带重跑连接/流式）。
- 第 1 次：骨架阶段 **504 AI_MODEL_TIMEOUT → INCONCLUSIVE**（上游瞬态超时，如实记录）。
- 第 2 次：**PASS**（骨架 + 细节全链路通过生产 PlanningJsonContract，latency 20088ms，verified=true，无 Schema 错误）。
- AI 设置页「AI 规划」就绪度由「未验证」翻转为「**可用**」（截图 06）。

## G. Agent 模型快照审计（详见 [model-snapshot-audit.md](model-snapshot-audit.md)）

**定性：设计行为 + 遗留 schema，非快照缺失缺陷。** `agent_run.model_name/model_provider` 仅 v1 遗留记账路径写入；现行 v2 路径（所有新 Run 固定 `context_policy_version=2`）把模型身份落在三处权威来源：`model_configuration_snapshot`（21/21 覆盖）、`agent_step.MODEL_TURN` 逐轮记录（实测 68/68 覆盖）、`MODEL_COMPLETED` 事件（前端芯片来源）。逐轮记录比 Run 级单值列更准确（支持每轮换配置）。未回填、未伪造、未改代码；建议后续版本清理死列（独立小任务，不阻塞发布）。

## H. 审批历史与权限判定审查（详见 [approval-history.json](approval-history.json)）

- 真实浏览器刷新复核：最新 Run 的已拒绝审批卡正确展示、与 DB 一致；无 PENDING 卡片；已批准的旧 Run 审批不在当前视图（Run 级拉取设计，服务端记录完整）。
- 历史 ASSISTANT 消息「当前审批状态：待审批」为创建时刻确定性快照文案（实测仍存在），但旁边不渲染可操作审批卡（仅 PENDING 渲染按钮），权威状态以审批卡 tag 为准——维持不重写历史消息的设计，非缺陷。
- `AgentToolPolicy` 与 `AgentToolRegistry.allowed`：生产执行链单一判据在 Registry（Policy 仅测试对照物，且 Registry 对规划写更严格）；无规则漂移导致的权限放宽。按 P3 约定仅记录，不重构。

## I. 缺陷修复

**无。** 本轮未发现稳定可复现的生产缺陷，零生产代码修改。上轮 uuid/date format 修复保留并经 ToolArgumentValidatorTest 28 用例回归。

## J. 回归与测试（详见 [regression-results.json](regression-results.json)）

- 生命周期相关离线契约定向测试 **123 用例全绿**（9 个测试类：PauseResume 19 / RunRetryPostgres 6 / RunServiceRetry 6 / CrashRecovery 5 / ApprovalService 11 / RuntimeBehavior 25 / ResourcePolicy 10 / LoopGuard 13 / ToolArgumentValidator 28）。
- 完整 `mvnw verify` 未重复执行：零生产代码改动，基线 `e1d2765` CI 全绿（与上轮 verify 同命令）。
- 前端无改动，未执行。

## K. 观察项与发布前缺口（非阻断）

1. 历史FAILED Run 无 UI 重试入口（重试按钮仅 activeRun）；cancelActiveRun 无防重 loading；`agentApi.resume` 死代码。
2. `agent_run.model_name/model_provider` 为仅 v1 写入的死列，建议后续清理。
3. `AgentToolPolicy`（MEMBER/SUPERVISOR 可写规划）与 Registry（仅 OWNER/ADMIN）语义差异保留在测试对照物中，建议加注释防误用。
4. 免费模型存在瞬态超时（PLANNING_JSON 第 1 次 504）与偶发工具失败（list_tasks FAILED 如实标记后运行仍收敛），生产建议保留重试入口。
5. 跨账户隔离负面测试为 API 级真实请求 + 源码审计（浏览器级双登录态切换未执行，不伪造身份）；同项目成员间会话互相可见属协作设计。

## L. 最终状态判定：`AGENT_LIFECYCLE_REAL_E2E_ACCEPTED`

依据：本轮任务书要求的生命周期（取消/暂停/恢复/重试）、权限隔离、PLANNING_JSON 全部真实验证通过且无阻断缺陷；QUEUED 暂停窗口 INCONCLUSIVE 已由离线测试 + 兄弟路径实证补强并如实标注；所有未执行/受限项诚实列出（K 节）。**Agent 可进入发布准备评估阶段。**

## M. 证据文件

- [architecture-audit.md](architecture-audit.md) — 生命周期机制审计（状态机/租约/重试/预算/SSE/权限）
- [real-model-call-ledger.json](real-model-call-ledger.json) — 8 次真实生成台账（授权 12）
- [lifecycle-scenarios.json](lifecycle-scenarios.json) — 8 个生命周期场景矩阵
- [permission-isolation.json](permission-isolation.json) — 10 项负面权限测试
- [planning-probe.json](planning-probe.json) — PLANNING_JSON 探针明细
- [model-snapshot-audit.md](model-snapshot-audit.md) — 模型快照空值定论
- [approval-history.json](approval-history.json) — 审批历史 UI 复核
- [regression-results.json](regression-results.json) — 离线契约 123 绿 + CI 基线
- screenshots/ — 6 张脱敏截图（01 审批历史刷新 · 02 取消后刷新 · 03 暂停态 · 04 恢复完成 · 05 重试幂等回答 ×2 · 06 规划就绪）

## N. 最终报告问答（任务书第十九节）

1. Git 最终 SHA：见提交（本轮仅新增证据文档，基线 `e1d2765`）。
2. main 不变：`9b4f7c5` 全程未触碰。
3. 实际模型调用：8 次（最坏口径 10）/ 授权 12。
4. Kilo 当前模型 ID：`nvidia/nemotron-3.5-lightning:free`（实时目录确认）。
5. FAILED Run 重试：成功（派生 `ec75239c`，幂等无重复写入）。
6. 暂停真实生效：是（RUN_PAUSED 落库，跨 FAILED_RETRYABLE 正确收口）。
7. 恢复同一 Run：是（`c41b484c`，非派生）。
8. 取消后停止新工具：是（0 工具执行、0 写入）。
9. 事件与数据库一致：是（3 个 Run 序号一一对应、单调）。
10. 跨用户/跨项目权限安全：是（10/10 负面测试 404/401）。
11. PLANNING_JSON 真实通过：是（第 2 次尝试 PASS，生产契约）。
12. 快照空值原因：v1/v2 记账路径并存，v2 不写 Run 级列，权威来源完整（step 逐轮 + snapshot jsonb + 事件）。
13. 历史审批正确展示：是（权威 tag 与 DB 一致；静态快照文案为已知设计）。
14. 本轮修复代码：无（零生产代码修改）。
15. 自动化测试：定向 123 用例全绿。
16. CI：本轮证据提交 run [40061629863](https://github.com/BangShou1st/ai-collab/actions/runs/40061629863) / attempt 1 / head `eef4d12` / **completed success**；基线 run 40057902680 / `e1d2765` / success。
17. 发布前缺口：K 节 5 项观察项，均非阻断。
18. **Agent 可进入发布准备评估：是。**
