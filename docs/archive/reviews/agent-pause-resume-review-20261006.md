# Agent 暂停与输入续跑限定审查

日期：2026-10-06。审查基线为 `codex/context-foundation` / `d5f09ab` 加现场全部未提交修改。本次只审查与复现，不修改生产实现、不提交、不启动业务服务、不调用真实模型、不操作业务数据库。

用户最新要求：逻辑成立、行为无矛盾即可，不将最后一轮浏览器复扫作为收口门槛。本次没有启动浏览器。

**最终复核（2026-10-06，收到 C1/C2 补修报告后）：F1–F5 及 C1/C2 全部收口。已核对 C1 总兜底、C2 全链恢复代次检查和正式回归；后端受影响 138 项 XML 为零失败/错误/跳过，本审查会话实际复跑前端暂停续跑组件 15 项全部通过。下文缺陷、失败探针及“下一轮”要求仅保留为历史记录，不再作为待修任务；不追加浏览器验收或重复开发。**

## 结论与范围

当前方向成立：动作边界暂停、在途响应保存、同 run 恢复、已受理规划独立完成、后续请求按当前模型配置。RUNNING 暂停意图不递增 version 是合理的控制语义；其暂停/恢复命令使用行锁，确认 PAUSED 时释放租约并保留 PENDING/未消费轮次，也符合既有 R1/R2 恢复契约。

首次审查确认 **5 个局部缺陷**，均有实际复现；后续又复现 F1/F2 的两处原契约遗漏 C1/C2。实施方已完成全部补修与正式回归，最终限定复核通过，详见各项记录和交付报告第 10 节。现已收口，不重做编排、不引入框架/意图模型/checkpoint 平台、不重新扩展 R1/R2 或精确计费，不追加全仓审查。

## 1. [P1] 带原 runId 的迟到续跑输入在终态退化为新任务

位置：`ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java:139`，相关分支在 133–169 行，落入普通 `createRun` 在 197 行。

`pausedRunId` 当前只检查是否等于 latest.id；续跑分流整体又受 `!latest.status().terminal()` 限制。原任务恢复后快速完成，或者暂停与文本完成竞争时，迟到/重复的“继续”仍绑定原 runId，但终态使它跳过控制分流，创建一个目标为“继续”的新运行和 USER 消息。

真实 PG 复现：原运行暂停→恢复→完成，再提交携带原 `pausedRunId` 的“继续”，运行数量从 1 变 2，返回的是新 runId。不是终态复活，是控制输入错误地变成普通新任务。

最小修法：**显式绑定暂停运行的输入始终留在控制作用域**。原运行完成/取消后返回真实状态或明确业务提示，不能落入普通 createRun。检查和控制操作仍依赖权威状态；首次成功、重复发送、终态、run 已切换均有一致结果。普通终态下真正的新任务发送保持原用途。

> **补修状态（已修复）**：绑定输入始终走控制作用域；终态明确续跑幂等返回真实状态，非续跑绑定内容抛 AGENT_RUN_NOT_RESUMABLE；绑定失效/已切换按既有 NOT_FOUND 拒绝；未绑定新任务仍普通提交。回归：`AgentPauseResumePostgresTest.staleBoundResumeInputOnTerminalRunStaysInControlScope`（真实 PG，断言 run 数/runId/USER 消息/goalRevision）。

## 2. [P1] 输入续跑的迟到响应缺少页面作用域保护

位置：`ai-collab-frontend/src/modules/agent/use-agent-workspace.ts:275`，尤其 278–284 行。

`pauseActiveRun` 有 projectId/sessionId/runId/restoreSeq 检查，但输入续跑经过 `send`，其 await 后没有这些检查。A 会话发送“继续”后切到 B，会清空 B 新输入的草稿、重启当前页面事件订阅，并触发未绑定请求作用域的 loadMessages。单个 `timeline.run.id === run.id` 条件只保护状态赋值，保护不了其余副作用。

组件复现：A 的 submit 挂起→切到 B→输入“B会话的新问题”→返回 A 的续跑响应，B 草稿由原文变成空字符串。

最小修法：复用现有控制请求的作用域处理思路，发送时捕获作用域，过期成功/失败响应都不能修改新页面、清草稿、换订阅或应用旧消息。后续 loadMessages 等异步结果也需绑定作用域。卸载与恢复代次变化一并覆盖；不要为此建立通用事件总线。

> **补修状态（已修复）**：send 捕获项目/会话/恢复代次/绑定 runId 与卸载标志，成功与失败响应均核对作用域；loadMessages 返回时核对调用时作用域；发送后新增草稿不被同页旧响应误清。回归：`AgentView.pause-resume.test.ts` 输入续跑作用域 4 项（切会话保草稿保订阅、切回后代次变化不生效、失败不显示错误、延迟消息加载不覆盖新会话）。

## 3. [P2] Ctrl+Enter 普通发送/续跑分支没有调用 send

位置：`ai-collab-frontend/src/modules/agent/AgentView.vue:225`。

当前表达式为 `WAITING_FOR_USER_INPUT ? continueRunHandler() : send`。非澄清状态下仅返回函数引用，未执行 `send()`。点击发送正常，按 Ctrl+Enter 无发送请求。

组件使用真实 Vue 编译与键盘事件复现：暂停态输入“继续”并触发 Ctrl+Enter，submit 次数为 0。报告中将按键不生效全部归因于 bsk 合成事件限制，需纠正；这是一处实际绑定缺陷。

最小修法：调用正确的发送分支或使用一个明确事件处理函数。补普通发送、暂停续跑和等待澄清三条键盘路由的行为断言即可，无需专门重开浏览器验证此行。

> **补修状态（已修复）**：非澄清分支改为 `send()`。回归：`AgentView.pause-resume.test.ts` Ctrl+Enter 三条路由（普通提交、暂停续跑带 pausedRunId、等待澄清走 continueRun）各恰一次且调用正确 API。首轮报告的 bsk 归因已在交付报告第 4/8.2 节就地纠正。

## 4. [P2] 同一运行的旧暂停回包会覆盖较新的完成事件

位置：`ai-collab-frontend/src/modules/agent/use-agent-workspace.ts:516`。

请求的项目/会话/run 都未变化时，`pauseActiveRun` 无条件用响应快照替换 timeline.run，未核对其 `lastEventSequence` 是否落后于当前已应用事件。暂停意图已受理后，允许在途最终文本正常完成；若 RUN_SUCCEEDED 比旧 RUNNING 暂停回包先到达，页面会从“已完成”退回“正在暂停”。若事件流已经结束，状态可能一直不再纠正。

组件复现：暂停请求挂起→应用 RUN_PAUSE_REQUESTED / RUN_SUCCEEDED→页面已完成→返回旧 RUNNING + pauseRequestedAt 快照，页面变回运行中并出现正在暂停横幅。

最小修法：区分作用域新旧和同一运行事实新旧，已有事件比响应快照更新时不覆盖状态；使用现有事件序号/权威刷新方式处理即可，不以”终态永不变化”的硬编码代替正确顺序。补同 run 延迟回包回归；输入续跑的响应也复核同类顺序问题。

> **补修状态（已修复）**：pauseActiveRun 在作用域核对通过后比较 `detail.lastEventSequence` 与 `timeline.lastSequence`，落后即整体跳过（含 PAUSED 确认分支的停流与加载）；续跑同 run 响应以”当前视图仍为 PAUSED”为应用前提。回归：`AgentView.pause-resume.test.ts` 旧回包顺序 3 项（完成事件后旧暂停回包、PAUSED 后旧 RUNNING 快照、RESUMED 后旧 PAUSED 快照——状态不回退且不误停事件流）。

## 5. [P2] 摘要请求及重压缩没有持久化暂停准入

位置：`ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java:224`，以及 Recorder 的 `beginSummaryAttempt:60` / `beginSummaryRecompressAttempt:73`。

主请求的 beginModelCall 确实在运行行锁内检查暂停，但摘要通过独立的 beginSummaryAttempt/重压缩身份路径调用模型，没有使用这个入口。协调器 7a 只在整个摘要流程之前检查一次。首次摘要在途时落库暂停意图，若返回超长摘要，代码只复查取消/时间/额度，随后会新启动重压缩请求。

真实 PG + 替身模型复现：首次摘要返回之前，真实 requestPause 事务已提交；第二次调用观察到 pauseRequested=true，仍然实际发出。请求计数为 2，期望为 1。

最小修法：摘要首次调用和重压缩的现有身份创建事务也使用相同运行控制/租约准入规则。暂停拒绝不是摘要失败或新的重试消耗；首次已发生结果照常保存/处理，未获准入的第二次请求不发出。复用小的准入校验，不合并原有独立结算身份，不把数据库锁持有到网络请求完成。

> **补修状态（已修复）**：`beginSummaryAttempt`/`beginSummaryRecompressAttempt` 身份创建事务内先 `AgentLeaseScope.verify` 再 FOR UPDATE 检查暂停意图与 RUNNING 状态（与 requestPause 串行化，短事务）；首次暂停拒绝按控制结果返回，重压缩拒绝结算首次用量（outcome=PAUSED/CANCELED）并沿用上一份有效摘要。回归：`AgentPauseResumePostgresTest` 摘要准入 4 项（意图先落库零请求、在途暂停无重压缩身份且用量结算、真实协调器收口 PAUSED 且主请求零出站、过期 claim 拒绝准入）。

## 证据与边界

证据目录：`E:\project\ai-collab\docs\acceptance-evidence\2026-10-06\pause-resume-review`。

- `PauseResumeReviewProbe.java` / `backend-probe.log`：本次独立 Testcontainers PG，真实迁移到 V61，两项异常行为均确认，`REVIEW_BACKEND_PROBES_CONFIRMED=2`。仅使用该容器与替身模型，容器已停止。
- `AgentPauseResume.review-probe.test.ts` / `frontend-probe.log`：从已有组件测试装配派生，4 项既有测试通过，3 项新断言失败，分别锁定键位、会话草稿与同 run 状态回退。探针已移出生产测试目录，避免把故意失败的复现当成交付测试。
- 已核对归档全量日志：1134 项、0 失败/错误、11 skipped、BUILD SUCCESS。本次未重跑全量。现场 surefire XML 后来被浏览器 host 运行更新过一项，当前目录汇总 skipped=10，不把它当作该次全量的原始统计。
- 已受理规划不受 Agent 暂停控制，当前规划生成调度未读取 Agent 暂停状态，逻辑上保持独立。PG 用例 7 证明受理边界和 operation 复用，不是实际后台规划完成的端到端证据；本次不要求为这一证据边界额外启动浏览器。

修复时将上述复现转换为正式断言回归，跑受影响测试、必要编译/类型检查。保持已有恢复/来源模式/权限/幂等规则。没有新事实证明需要时，不补浏览器全景复扫、24 轮或真实模型调用；五项修复验证完成后即收口。

## 实施方补修报告摘要（2026-10-06 第二轮，后续复核见下文）

五项缺陷全部修复，复现探针已转为正式回归并通过；探针文件与日志按原样保留在本目录作为失败证据，未进入测试目录。回归与验证规模：后端受影响套件 222 项 0 失败（含真实 PG 的 `AgentPauseResumePostgresTest` 12→17 项）、前端 vitest 全量 38 文件 182 项 0 失败、`vue-tsc --noEmit` 通过。本轮未启动浏览器、未调用真实模型、未触业务数据库；未重跑 1134 项全量，历史全量数字见交付报告第 6 节。细节见 `docs/agent-pause-resume-report.md` 第 10 节。

## C1/C2 历史复核与补修记录（现已解决）

本次核对源码和正式测试：后端受影响 222 项 XML 合计零失败/错误/跳过；前端现有 14 项暂停/续跑组件测试实际复跑通过。F3 键盘调用、F4 暂停回包序号判断、F5 摘要准入与控制收口实现符合原修法。以下两项仍需限定补全，不代表要重新开发主体。

### C1 / F1：[P1] 非终态的绑定控制输入仍会落入 createRun

位置：`E:\project\ai-collab\ai-collab-backend\src\main\java\com\shitulelv\aicollab\agent\application\AgentRunService.java:175`。

新补丁只在 `boundControlInput && terminal()` 时阻止普通提交；QUEUED/RUNNING 的明确 RESUME 有幂等返回，但 WAITING_FOR_USER_INPUT、WAITING_FOR_APPROVAL、FAILED_RETRYABLE 以及活跃态的非续跑绑定文本仍可能绕过全部控制分支，落入 210 行 createRun。这与“显式绑定的输入始终留在控制作用域”矛盾。

确定性 PG 复现：同一运行暂停→恢复→进入 FAILED_RETRYABLE，迟到“继续”仍携带原 pausedRunId，产生第二个 run；将状态换成 WAITING_FOR_USER_INPUT 同样产生第二个 run。两种情况都从 1 个运行变为 2 个。澄清应走原 `/continue`，重试应走既有 retry 或调度；不能把旧控制输入变成新任务。

最小补全：在已处理的合法控制分支之后，**所有仍未处理的 boundControlInput 都返回权威状态或明确业务拒绝，绝不进入 createRun**。保留未绑定的新任务与澄清/重试原入口。不要新增意图类型或状态框架。

正式回归补上述两个状态，兼顾 bound 非续跑文本及已有终态/正常续跑/未绑定新任务。断言 run 数、USER 消息、目标与工作状态不被控制输入推进。无需回退已有终态修复。

> **补修状态（已修复）**：submit 兜底改为"所有未获合法控制分支处理的绑定输入一律不进入 createRun"——按权威状态给出明确业务提示（等待澄清→回复入口、等待审批→审批卡片、重试等待→重试入口、排队/运行中非续跑文本→引导先结束本次运行或新建会话），输入保留；终态绑定"继续"幂等返回真实状态的既有修复不变。回归：`AgentPauseResumePostgresTest.boundInputOnActiveRunNeverFallsIntoCreateRun`（真实 PG：QUEUED 绑定非续跑文本、FAILED_RETRYABLE 迟到"继续"、WAITING_FOR_USER_INPUT 迟到"继续"、RUNNING 绑定非续跑文本，断言 run 数/USER 消息/goalRevision 不被控制输入推进）。

### C2 / F2：[P2] 后续消息加载缺少恢复代次，A→B→A 时仍可覆盖新消息

位置：`E:\project\ai-collab\ai-collab-frontend\src\modules\agent\use-agent-workspace.ts:141`，相关发送后的二次检查在 324 行。

send 主请求检查了 restoreSeq，但 loadMessages 返回只比较项目/会话 ID。续跑成功后的 A 消息加载挂起→切到 B→再切回 A，新的 restoreSession 已展示 A 的最新消息；旧加载返回时 ID 又相同，被接受并用旧消息覆盖新视图。

组件复现已先断言“重新恢复后的 A 最新消息”存在，再返回旧消息加载，最终新消息消失、页面只显示旧请求内容。新增探针一项失败，现有 14 项全部通过：已有“submit 回包前切回”和“消息加载时停留 B”的测试不能覆盖这个窗口。

最小补全：loadMessages 捕获调用时的 restoreSeq，返回时同时核对代次与 disposed；续跑后异步副作用遵守同一份作用域。在普通发送分支自己递增 restoreSeq 后，记录该次重建后的预期代次，不能用 rebuiltByThisResponse 永久豁免代次；消息加载后启动订阅之前也核对，避免相同 ID 下重新恢复的页面被旧操作重建。只需局部令牌判断，不建立新请求平台。

正式回归补 A 消息加载挂起→B→A 的窗口，保留现有首次切会话、切回时 submit 仍挂起、键位和旧控制回包回归。

> **补修状态（已修复）**：loadMessages 捕获调用时的恢复代次并在返回时连同 disposed 一起核对；send 的作用域闭包改用"预期代次"——新任务分支自己递增 restoreSeq 后同步更新预期值，不再永久豁免代次核对，消息加载后启动订阅之前也经同一核对。回归：`AgentView.pause-resume.test.ts` 新增"A→B→A 切回后挂起的旧消息加载不得覆盖重新恢复的最新消息"（先断言重新恢复的 A 最新消息存在，再返回旧加载并断言不覆盖）。

### 新复核证据与收口范围

- `PauseResumeClosureProbe.java` / `backend-closure-probe.log`：本次独立 Testcontainers PG + 真实 V61 迁移，C1 两种非终态均确认，`F1_NONTERMINAL_SCOPE_PROBE_CONFIRMED=2`；只用安全替身和隔离数据，容器已回收。
- `AgentPauseResume.closure-probe.test.ts` / `frontend-closure-probe.log`：现有 14 项通过，C2 新探针 1 项失败。已归档移出正式测试目录。
- 本次未改生产代码、未启动浏览器/业务服务、未调用真实模型，未重跑全量。C1/C2 是已要求的 F1/F2 控制作用域与后续异步保护，不追加独立功能。

以上为当时的限定补修要求，C1/C2 现已补齐并转换为正式回归；F3/F4/F5 和 R1/R2 保持已验收，不重复复现或开发。

> **收口结论（2026-10-06 补修轮）**：C1/C2 已修复并转为正式回归。后端 `AgentPauseResumePostgresTest` 17→18 项、受影响核心套件共 138 项 0 失败（真实 PG）；前端 vitest 全量 38 文件 183 项 0 失败（15 项暂停续跑组件回归）、`vue-tsc --noEmit` 通过。本轮探针（`PauseResumeClosureProbe.java`、`AgentPauseResume.closure-probe.test.ts` 及日志）原样归档保留，未进入测试目录；未启动浏览器、未触业务数据库。

> **独立复核确认**：本审查会话再次核对 C1/C2 生产实现和正式用例、138 项后端 XML，并实际复跑正式 `AgentView.pause-resume.test.ts` 15 项，全部通过（`frontend-closure-confirmed.log`）。未重跑后端全量/前端全量或浏览器，未修改生产代码。限定审查无剩余必修项。
