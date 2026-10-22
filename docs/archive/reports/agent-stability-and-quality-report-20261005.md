# 交付报告：四项中断修复收尾 + 资料可读完整性与回答质量验收

日期：2026-10-05。分支 `codex/context-foundation`，基线 `d5f09ab`（含交接时全部未提交改动）。本轮全部改动保持未提交，未合并 main、未部署。

## 1. 本轮完成的行为

### 第一组：四项中断修复

**1. 单次请求配置一致性（全链）**
- `AgentModelMessageComposer` 不再读取模型配置：`composeV2(..., legacyMode)` 与 `buildMessageHistory(..., legacyMode)` 接收请求准备阶段的模式快照，`buildSystemPrompt` 用它渲染只读/原生契约。构造函数去掉了 `RoutingAgentModelExecutor` 依赖（编译期保证不再有第二次配置读取）。
- 协调器把 `resolved.legacyMode()` 贯穿三次组装（首次、0.6 降级重组、收尾重组）、写入工具暴露判断（按能力）、`AgentToolCallExecutor` 的新轮次校验来源；窗口预算与出站调用继续使用同一 `ResolvedRequest`。
- 新增 `ResolvedRequest.of(provider, config)` 统一派生模式；删除误导性的 4 参 `callModel` 兼容重载，使"少传快照"在编译期不可表达。
- `isLegacyModeForRun` 现在只剩 `AgentToolCallExecutor.validateToolCall` 一处（历史 NULL 来源的恢复兜底），与交接要求一致。

**2. 恢复工具调用按原来源模式校验**
- V59 `source_mode`、落库透传、恢复读取与执行器分支保持原实现，未另造执行身份。项目权限、Skill 白名单、审批、已完成结果复用（`knownInvocationResult`）一律保留。
- 把误导的测试名 `recoveryStillEnforcesSkillWhitelistAndRoleChecks` 改为 `recoveryStillEnforcesSkillWhitelist`：该用例只覆盖白名单拒绝，角色/深度检查在放行路径被执行，不含角色拒绝场景。

**3. 后续轮次的"正在分析"**
- `agent-activity.ts` 改为按最新模型事件判定：仅当"最新 MODEL_STARTED 晚于最新 MODEL_COMPLETED"且运行未终态时渲染一行"正在分析"，detail 取该 STARTED 的 model。第二轮、第三轮、重试后的新请求都会重新显示；终态不显示；事件乱序/重复重放结果一致。

**4. 活动块稳定身份**
- `AgentView.vue` 活动块 key 由"全部活动 key 拼接"改为 `activities-<runId>`；行级 key 仍按活动身份，分组 key 仍按首元素。同一运行内追加事件不再重挂载整块；切换运行仍会换块（不串旧展开状态）。

### 第二组：资料可读完整性

**分页契约统一为"第一条未返回记录"**
- 新增 `ListPageContract`：`items` 只含本页记录，`returned` = 本页条数，`total` = 当前过滤条件下尚未续读的记录数，`hasMore`，`nextCursor` = **第一条未返回记录**的 id；续读用 `id >= cursor`，因此既不跳过未返回记录，也不重复已返回记录。工具按 `PAGE_BUDGET_CHARS=3600` 自控单页体积，明显低于模型可见阈值 6000——"服务端本页记录 = 模型可见记录"，两层缩减不再互相割裂。
- `list_tasks`：描述截断到 300 字符（完整描述用 `get_task`），`taskFacts` 覆盖范围与本页一致并声明 `factsScope=SAME_AS_ITEMS_PAGE`；schema/描述/fieldGuide 同步更新游标语义。
- `list_milestones`：补齐 `definition()`（原为开放 schema）、`cursor`/`limit`/`status` 参数、`total`/`hasMore`/`nextCursor`/`sort` 与 `fieldGuide`；此前只能读前 50 条、无法续页的问题消除。
- 组装器 `projectToolOutput` 对列表页走专用投影：可见记录、`returned`、`pageReturned`、`total`、`hasMore`、`nextCursor`、`taskFacts` 全部按同一可见范围重算；仍超限时继续缩可见范围并重算游标（游标严格晚于最后可见记录，必然前进）；极端阈值下只保留契约核心字段。非列表结果的通用投影与规划/正文投影行为不变。
- 系统提示补充列表续读与覆盖声明要求（"未续读完不得宣称已列全"），并区分 `returned` 与 `pageReturned`。

**指令矛盾收敛**
- `ProjectHealthSkill`：去掉"第一轮必须同时调用所有需要的工具"，改为"最少必要 + 独立只读查询可并行 + 按 nextCursor 续读 + 未续读完不得宣称查全"。
- `DeliveryReadinessSkill`：不再要求"文档完整性"（白名单无文档工具），明确写"文档未核查（当前工具范围不包含文档读取）"；去掉无依据的 0–100 评分，改为"达到/未达到/证据不足"判断；不给出无依据的分数。

## 2. 实际验证

| 范围 | 命令/方式 | 结果 |
| --- | --- | --- |
| 后端全量 | `mvnw test` | **1096 项，0 失败 0 错误，跳过 11**（BUILD SUCCESS） |
| 前端全量 | `npx vitest run` | 36 个文件，**163 项全过** |
| 类型检查 | `npx vue-tsc -b` | 无错误 |

11 项跳过全部是显式 opt-in 门控：`BrowserAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、`RealAcceptanceHostTest`、`RealRetrievalBaselineTest`、`ReliabilityAcceptanceHostTest`、`ReliabilityOperationDatabaseTest`(4)、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest`——需要环境变量或外部服务，不是"Docker 不可用"。

新增/改造的确定性覆盖：
- `AgentListPaginationChainTest`（9 项）：真实串联"工具分页 → 清洗器 → 模型可见视图 → 续页"；63 条里程碑与 130/60 条任务在多页后 ID 集合无遗漏、无重复；投影后 `returned` = 可见条数、`nextCursor` = 首条未展示记录、`unshownInPage` 显式可见；`limit>50` 明确失败。
- `ListFactsQuestionCoverageTest`（5 项）：三类问题的资料覆盖——项目概览（总数/状态分布/未读范围）、列全部（130 条全覆盖且不重复）、本周任务是否影响交付（已逾期 vs 缺日期不可判定分开，逾期结论带负责人与依赖数）；模型可见阈值被压低时未展示条数显式可见。
- `AgentModelMessageComposerRequestConsistencyTest`（6 项）：Legacy/原生与降级重组下提示词模式由传入快照决定，组装器不再读配置。
- `AgentRuntimeRequestSnapshotTest`（3 项）：协调器端到端——准备后配置切到 Legacy/原生，本次提示词、暴露的写工具、出站执行器仍按原快照；解析只发生一次；下一次请求才用新配置。
- `AgentModelConfigurationSwitchPostgresIntegrationTest`（9 项，真实 PostgreSQL）：原 5 项 + 恢复来源模式 4 项全部通过。
- 前端 `agent-activity.test.ts`（18 项，含第二轮/第三轮/重试/终态/重复乱序）、`AgentView.activities.test.ts`（2 项，MutationObserver 证明追加事件不替换原 DOM 节点，`<details>` 保持展开、焦点仍在原 summary）。
- 迁移历史断言随 V59 更新（`Phase08MigrationSafetyIntegrationTest`、`NextStageMigrationPostgresTest`、`LegacyApprovalCitationUpgradeTest`、`TaskPlanSpringBeanPostgresIntegrationTest`），`DocumentRepositoryIntegrationTest` 的列表断言改为按游标读完整覆盖（110 条任务全部读到、第 90 条只出现一次）。

## 3. 浏览器验收（bsk，隔离环境）

环境：隔离 PostgreSQL 容器 `ai-collab-agentexp-pg-20261005b`（127.0.0.1:55432，来自 `.env`，非业务库）→ `AI_BROWSER_ACCEPTANCE=true mvnw -o test -Dtest=BrowserAcceptanceHostTest`（后端 18080 + 合成模型 peer）→ 前端 `VITE_BACKEND_ORIGIN=http://localhost:18080 npx vite --port 15173`。模型全部为合成响应，未触真实模型、未触业务库。

用 `NARRATE`（真实工具活动多轮：查任务 → 查里程碑 → 最终回答）叠加 `SLOW_EACH`（每轮 8s，给交互留出观察窗口），并在页面内用 MutationObserver 观测原 DOM 节点是否被移除：

```json
{"log":[
 {"t":0,"rows":1,"analyzing":false,"removals":0},
 {"t":0,"event":"expand","focused":true,"scrollTop":40},
 {"t":1,"rows":2,"analyzing":true,"removals":0,"markerOpen":true,"focused":true},
 ... 第二轮在途期间 t=1..11 连续 11 次观测：rows=2、analyzing=true、removals=0、markerOpen=true、focused=true ...
 {"t":12,"rows":1,"analyzing":false,"removals":0,"markerOpen":true,"focused":true},
 {"t":12,"event":"terminal","rows":1,"removals":0,"markerOpen":true,"focused":true,"markerConnected":true,"scrollTop":40}
]}
```

验收点：
- (a) **第二轮在途仍显示"正在分析"**：以上 t=1..11 期间 `analyzing=true`（首轮在途同样显示）。
- (b) **展开态/焦点稳定**：运行中展开工具行"技术详情"并聚焦 summary，随后新事件到达（rows 1→2→1、analyzing 翻转、终态），观测期间 `removals=0`、`markerConnected=true`、`markerOpen=true`、`focused=true`——活动块没有整块重挂载，原 DOM 节点、展开状态与键盘焦点都保留。
- (c) **刷新后过程与最终回答不重复**：重新进入会话后，每个问题只有一条最终回答（`核对完成：两项任务延期…` 计数 3 = 3 个问题各一次），无重复答案、模型标签为 `acceptance-a`。
- (d) **列表与回答内容可读**：过程按"说明 → 工具活动 → 说明 → 工具活动 → 最终回答"顺序渲染，工具行可展开技术详情。

限制：jsdom 没有布局（`scrollHeight/clientHeight` 恒为 0），前端单测无法断言滚动回弹，因此滚动跟随只在浏览器侧观察（观测期间 `scrollTop` 未被强制归零）；本机数据库中没有 >50 条里程碑的页面数据，里程碑/任务分页完整性由后端集成测试（63/110/130 条）覆盖，未在浏览器里重复造大项目。

## 4. 回答质量验收

- 已完成：三类问题（项目概览 / 按指定范围列全部 / 检查本周任务是否影响交付）的资料覆盖到"模型可见事实"的确定性验收——覆盖完整、无遗漏无重复、缺依据范围显式可见、"延期"只由日期证据支持。
- **未完成**：**真实模型质量验收未执行**。本轮只使用合成脚本模型（`ScriptedAcceptanceModel` 固定文本，含 `acceptance-b` 的短回答），按交接要求它不能作为分析质量证据；隔离环境里也没有可用的真实已授权配置（验收宿主把出站端点限制在本地合成 peer）。因此"用当前真实配置对三类问题各跑一次"这一项按原样保留未验收，不用假模型结果替代。

## 5. 仍待办事项（非本轮门槛）

- 真实模型的三类问题质量验收（需要一份可用的已授权真实配置）。
- 跨运行"继续剩余工作"续接、同运行暂停/继续、崩溃后有效执行时长。
- 逐字流式、精确 token 计费、自动择模；框架替换收益验证（见 `docs/agent-framework-and-quality-research-20261005.md`）。
- 列表页 `total` 目前是"当前过滤条件下尚未续读的量"而非全局总量；若产品需要全局总量，需在工具层另加字段（本轮按最小可验证方案未扩展）。

## 6. 工作区状态

- 分支 `codex/context-foundation`，未提交、未合并 main、未部署。
- 本轮新增文件：`ListPageContract.java`、`AgentListPaginationChainTest`、`AgentModelMessageComposerRequestConsistencyTest`、`AgentRuntimeRequestSnapshotTest`、`ListFactsQuestionCoverageTest`、`AgentView.activities.test.ts`；测试侧新增 `SLOW_EACH` 验收模式（`ScriptedAcceptanceModel`）。
- 原有未提交改动（精简、动态模型配置、过程可视化、V59、上一轮修复）全部保留。
- 清理仅限本会话：bsk 会话已 `session stop rcvm`，前端 dev server 与验收宿主进程已停止，隔离容器 `ai-collab-agentexp-pg-20261005b` 已 `docker rm -f`；55432/18080/15173 已无监听。
- 证据与日志：`docs/acceptance-20261005/`（`backend-full-final.log`、`frontend-vitest-final.log`、`host-d.log`，以及上一轮截图）。
