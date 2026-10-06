# 四项审查修复——衔接提示词

日期：2026-10-05。基线：d5f09ab，分支 codex/context-foundation。**工作区含两轮未提交改动（上一轮功能轮 + 本轮修复轮的中断状态），全部保留，禁止覆盖、回退、reset。**

## 1. 任务背景与边界

上一轮已交付并验收通过两个功能：Agent 按当前配置中途换模型 + 过程可视化（轮次正文、工具活动、最终回答）。本轮任务是只修四项审查发现，不扩大功能范围：

1. 单次模型请求配置一致性（准备时解析一次，同一份配置贯穿窗口预算/能力/工具协议/出站/该响应的工具校验；下次请求重读最新配置）
2. 恢复工具调用的原始执行权限（按原模型轮次持久化的来源模式校验，去掉"恢复批次一律跳过 Legacy 检查"的假设）
3. 修正分析状态（"正在分析"按最新一次未完成的模型请求显示）
4. 稳定活动块身份（新增事件不得重新挂载整段执行过程，保住展开/焦点/滚动）

本轮不开发：暂停、断点续跑、逐字流式、token 计费、自动择模、新框架。四项验证通过即收口。

## 2. 本轮已完成的改动（全部在工作区，勿回退）

### 后端主代码（已完成，编译通过）

- **V59 迁移**（新增文件 `V59__agent_invocation_source_mode.sql`）：`agent_tool_invocation` 增加 `source_mode VARCHAR(24)`（可空，历史行为 NULL）。
- **AgentRunEventRecorder**：`recordModelTurn(run, turn, sourceMode)` 重载——调用清单 INSERT 写入 source_mode；`recordModelTurnWithSettlement` 增加 6 参重载透传。旧签名保留为委托（sourceMode=null）。
- **AgentRepository**：`recordModelTurn`/`recordModelTurnWithSettlement` 对应重载；新增 `invocationSourceMode(run, call)`（按 run_id+tool_call_id+最新 MODEL_TURN 轮次查 source_mode，无记录返回 null）。
- **RoutingAgentModelExecutor**：新增 `record ResolvedRequest(provider, config, providerType, modelName, legacyMode)` 与 `resolveRequest(run)`（解析一次：enabled 检查、native/legacy 模式判定）；新增 5 参 `callModel(..., ResolvedRequest)` 使用传入配置出站（不再重新 require）；4 参旧签名委托。`pinnedProviderIdentity` 保留（仅作 NULL source_mode 的恢复兜底路径，协调器已不再使用）。
- **AgentToolCallExecutor**：`executeCalls` 8 参版本 `(run, ctx, skill, turn, exposed, steps, recoveryBatch, requestLegacyMode)`（6/7 参旧签名委托兼容）。`validateToolCall` 写工具校验改为按来源：恢复批次查 `repository.invocationSourceMode`（NATIVE_TOOLS 放行 / LEGACY_READ_ONLY 拒绝 / NULL 回退 `modelExecutor.isLegacyModeForRun(run)` 保守检查）；新轮次用 `requestLegacyMode`。权限、Skill 白名单、审批检查对所有来源不变。
- **AgentRuntimeCoordinator**：预算计算处改为 `var resolved = modelExecutor.resolveRequest(run)`（替换 pinnedProviderIdentity）；`MODEL_STARTED` payload 的 provider/model 改用 resolved（不再判空）；`callModel(..., resolved)` 5 参；`recordModelTurnWithSettlement(..., resolved.legacyMode() ? "LEGACY_READ_ONLY" : "NATIVE_TOOLS")`；新轮次 `executeCalls(..., false, resolved.legacyMode())`；两个恢复批次调用点走 7 参委托 `(true, false)`。

### 测试（部分完成）

- **测试桩已批量更新**：`AgentRuntimeBehaviorTest`/`AgentRuntimeCoordinatorTest`/`CrossTickToolCallTest` 的 `callModel(eq(run), any(), any(), eq(false))` → 追加 `, any()`（5 参）；`recordModelTurnWithSettlement` 桩追加第 6 个 `any()`。
- **新增 `RoutingAgentModelExecutorRequestConsistencyTest`**（单测，2 项已通过）：覆盖"准备后切换配置，本次请求及出站仍按 A，下次 resolveRequest 才用 B"、"原生 A 在途切 Legacy B，本次走原生、下次走只读执行器"。
- **`AgentModelConfigurationSwitchPostgresIntegrationTest`**（PG 集成，上一轮 5 项全通过；本轮新增 4 项，中断时状态见下节）。

### 前端（本轮两项未开始，无任何改动）

第 3、4 项完全未动工。相关文件：`src/modules/agent/agent-activity.ts`（归约器）、`AgentView.vue`（活动块 key）。

## 3. 中断点：集成测试最后一个用例

`AgentModelConfigurationSwitchPostgresIntegrationTest.recoveryStillEnforcesSkillWhitelistAndRoleChecks` 刚把"角色/深度场景"简化为仅白名单场景（Edit 已写入文件），**被取消的测试命令尚未重跑**。下一步第一件事：

```bash
cd ai-collab-backend && ./mvnw -q test -Dtest=AgentModelConfigurationSwitchPostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

预期 9 项全过（原 5 项 + 新 4 项）。若 whitelist 用例仍有问题，注意两个已确认的落库约定：拒绝结果以 `status='REJECTED'` 落库（createError 自带 status），非致命拒绝后批次 requeue（outcome=QUEUED，errorCode=null）。

新 4 项用例（文件内已有，若需微调请保持意图）：

1. `legacyTurnWriteCallIsStillRejectedOnRecovery`：LEGACY_READ_ONLY 轮次的写调用，切到原生配置后恢复仍拒绝（outcome FAILED/LEGACY_WRITE_TOOL_FORBIDDEN，invocation REJECTED，提案服务未调用）。
2. `nativePendingWriteCallStillRecoversAfterSwitchToLegacy`：NATIVE_TOOLS 轮次的写调用，切到 Legacy 配置后恢复仍进入提案流程（approvals.proposeOrRevise 被 mock 调用，invocation SUCCEEDED，outcome SUCCEEDED）。
3. `completedInvocationIsRecoveredWithoutReexecution`：已完成（SUCCEEDED 结果）的调用恢复时直接复用，不重新执行（proposeOrRevise never，outcome SUCCEEDED/QUEUED）。
4. `recoveryStillEnforcesSkillWhitelistAndRoleChecks`：白名单外写工具（draft_weekly_report + IterationPlanningSkill）恢复时拒绝（REJECTED + never）。注意：原想加"角色/深度"场景，但 `AgentToolRegistry.allowed()` 的 depth 取自 agent_run.depth（根运行被 CHECK 约束锁定为 0），构造子运行复杂且该检查代码本轮未改动，已删除该场景；角色检查在用例 2/3 中作为放行门被真实执行（OWNER 通过 checkPolicy）。若审查坚持要角色拒绝用例，可用 Testcontainers 里手工 UPDATE parent_run_id+depth 构造子运行，或评估后放弃。

测试 harness 的坑（已解决，勿踩回去）：

- `AgentToolResultSanitizer` mock 必须打桩 `when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0))`，否则 createError 返回 null 引发连锁 NPE（这个 NPE 暴露的是测试桩问题，不是生产缺陷）。
- `AgentApprovalView` 是 record（domain.model.builtin.IterationPlanningSkill 允许 create_task_after_approval 且 allowWriteTools=true）；`AgentProposalOutcome` 也是 record，可直接构造。
- 两次 executeCalls 之间必须重新 findRun + pendingModelTurn（版本与 PENDING 状态都会变）。
- Docker Desktop 需先启动（`E:\DevTools\DockerDesktop\Docker Desktop.exe`），PG 用 Testcontainers 自管，无需手工容器。

## 4. 剩余工作（按序）

### A. 后端收尾

1. 重跑上面那个集成测试到 9 项全过。
2. 后端全量 `./mvnw test`（上一轮基线 1067 项全过；本轮改了协调器/执行器/记录器，重点看 AgentRuntime* 测试与 AgentActualTokenUsageIntegrationTest）。跳过项应为 11（opt-in 门控）。
3. 检查 `git diff` 中 `isLegacyModeForRun` 只剩恢复兜底一个调用点。

### B. 前端第 3 项：修正分析状态

文件 `ai-collab-frontend/src/modules/agent/agent-activity.ts` 的 `reduceAgentActivities`。当前缺陷：`analyzing` 数组里只要出现过任何 MODEL_COMPLETED，"正在分析"行就消失——第二轮、第三轮请求在途时不再显示。修法：分别追踪最新 MODEL_STARTED 与最新 MODEL_COMPLETED 的 sequence，仅当"最新模型事件是 STARTED（无 COMPLETED 或 STARTED.sequence > COMPLETED.sequence）"时显示一行"正在分析"，detail 取该 STARTED payload 的 model（现有逻辑已带"使用 X"）。终态（terminal 事件存在）时仍然不显示。运行终态后未完成工具强制标 failed 的现有逻辑保持。

测试（`agent-activity.test.ts` / `agent-activity-coverage.test.ts`）：第二轮在途（STARTED(3) 在 COMPLETED(2) 后）→ 1 行"正在分析"；第三轮同理；重试场景（RUN_RETRY_SCHEDULED 不产生模型事件，新一轮 STARTED 后恢复显示）；RUN_SUCCEEDED 终态 → 0 行；事件重放顺序无关（sort 后结果一致）。现有断言"`STARTED+COMPLETED`（无 payload）→ 0 行"应保持成立。

### C. 前端第 4 项：稳定活动块身份

文件 `AgentView.vue` 对话区活动块的 `:key="block.items.map((a) => a.key).join('|')"`——新事件到达时 key 变化导致整块重挂载，`<details>` 展开状态、键盘焦点、滚动位置全部丢失。修法：改为稳定 key，如 `'activities-' + (activeRun?.id ?? 'none')`（活动块每渲染树最多一个，绑定当前运行即可）。行级 v-for 已按 act.key 稳定，分组 key（group:首元素key）也稳定，无需再动。注意 `groupAgentActivities` 排序按 minSequence，行序插入中部时 Vue 只移动受影响节点。

测试：`AgentView.restore.test.ts` 或新建用例——mount 后通过 mock 的 SSE 注入事件流，找到工具行的 `<details>`（技术详情），设为 open 并聚焦 summary；再注入下一批事件（新工具行/正文行）；flushPromises 后断言：details 仍 open、`document.activeElement` 不变、`.messages` scrollTop 未回弹。可参考 `AgentView.restore.test.ts` 现成的 `g.__testFetch = () => sseResponse([...])` 事件注入模式。

### D. 回归与验收

1. 前端 `npx vitest run`（上一轮 155 项全过）+ `npx vue-tsc -b`。
2. 后端全量 mvn test（如 A.2 未做）。
3. 浏览器验收（bsk，沿用上一轮隔离环境方案，不调真实模型、不触业务库）：
   - 启动隔离 PG（docker，参考上一轮 `ai-collab-agentexp-pg-20261005` 的 run 参数，名字换新日期）→ `AI_BROWSER_ACCEPTANCE=true ./mvnw test -Dtest=BrowserAcceptanceHostTest`（日志直接落文件，别用管道 grep，会缓冲）→ peer 端口从 `netstat` 按后端 java PID 的 127.0.0.1 监听端口找（READY 日志有缓冲延迟）→ `curl http://127.0.0.1:<peer>/control?NARRATE` → 前端 `VITE_BACKEND_ORIGIN=http://localhost:18080 npx vite --port 15173`。
   - 验收点：(a) 运行全程"正在分析"在第二、三轮在途时出现且随完成消失；(b) 运行中展开工具行"技术详情"并保持焦点，新事件到达后展开态/焦点/滚动位置不变（这是第 4 项的真实交互验证）；(c) 刷新后说明/工具/回答顺序与去重不回归（上一轮已验过的点抽查即可）。
   - 已知设施问题（上轮已修，勿回退）：ScriptedAcceptanceModel 的工作状态块解析已兼容中文文本格式（非 JSON）；NARRATE 模式分阶段返回"正文+工具调用"；SLOW_NEXT 已支持 resumeMode（`/control?SLOW_NEXT` 睡 20s 后恢复之前模式，可与 NARRATE 叠加）；BrowserAcceptanceHostTest 会自动补种 owner/12345678、验收项目、3 个任务和 acceptance-a/b/auth-failure 三个模型配置。
   - 登录请求必须带 `Origin: http://localhost:15173` 头（AuthRequestOriginValidator 校验）。
4. impeccable detect 跑一次改动的 vue/ts 文件（上轮仅报 blockquote 左边框，属 Markdown 标准样式，可保留）。

### E. 收口报告

报告四项各自的实际验证范围（确定性测试 + 交互验证），明确：单次请求一致性由 RoutingAgentModelExecutorRequestConsistencyTest（纯单测、无竞态）+ 集成测试共同覆盖；恢复权限由 PG 集成测试的确定性用例覆盖；不声称已完成暂停/续跑/流式。清理：Testcontainers 自清理、bsk 会话 `bsk session stop --all`、停 dev server、隔离 PG 容器 `docker rm -f`、确认 18080/15173/55432 端口释放。改动保持未提交（与基线一致），不合并 main。

## 5. 关键文件索引

- 后端：`AgentRuntimeCoordinator.java`（advance 主循环，预算/事件/结算在 196-320 行附近）、`RoutingAgentModelExecutor.java`（resolveRequest/ResolvedRequest）、`AgentToolCallExecutor.java`（executeCalls 78-100、validateToolCall 写工具校验 355-375 附近、executeCalls 批次分支 160-300）、`AgentRunEventRecorder.java`（recordModelTurn ~575、事件 payload ~620）、`AgentRepository.java`（knownInvocationResult/invocationSourceMode ~296）。
- 前端：`agent-activity.ts`（reduceAgentActivities/groupAgentActivities/currentModelFromEvents）、`AgentView.vue`（对话区 conversationBlocks 循环、messagesEl 滚动逻辑）、`conversation-blocks.ts`、`use-agent-workspace.ts`。
- 设计文档（优先级最高，冲突时以此为准）：`docs/agent-experience-design.md`；上轮验收截图存于 `docs/acceptance-20261005/`。
