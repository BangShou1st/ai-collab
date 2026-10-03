# space-bunny-free 真实验收补充报告

日期：2026-10-03（北京时间）。执行依据：[验收交接](space-bunny-acceptance-handoff.md)。本轮已完成工具数值类型修复，并在受限业务库副本中完成真实修订、审批、幂等和只读事实查询。创建提案沿用交接中已成功的真实记录；没有把历史失败覆写成成功。

## 修复与自动化证据

1. `ToolArgumentValidator` 原先用类型名称完全相等判断，把整数 `3` 拒绝在 `number` / `[number,null]` 之外。单类型和联合类型现采用统一的数值语义：number 包含整数；integer 判断是否存在非零小数部分，接受 `3.0`，拒绝 `3.5`；字符串、布尔、对象不能冒充数字。有限数值检查与 decimal 边界比较保留工时 0.5–80 的限制。依据：[JSON Schema 官方数值说明](https://json-schema.org/understanding-json-schema/reference/numeric)。没有模型名特判或要求模型输出浮点写法。
2. 首次真实复验通过数值校验后，暴露 `assigneeName` 展示元数据混入业务 DTO 的问题：存储提案经过 merge 后再次 normalize 会抛出 `工具参数格式无效`；审批重校验及执行也会遇到同一路径。任务创建和更新工具现在在 DTO 转换前仅移除这一展示字段，保留其余参数及业务校验；normalize 从项目成员重新生成显示名。没有关闭未知字段检查，没有修改负责人 ID 或审批边界。
3. 数值回归先得到 26 项中的 2 项预期失败；显示元数据回归先得到 12 项中的 2 项错误，均已保留日志。最终受影响测试 **80 项通过，0 失败、0 错误、0 跳过**，包含真实任务提案 Schema、嵌套联合类型、合法整数不消耗纠正预算、无效工时不执行工具/创建审批、创建与更新 DTO 展示字段处理，以及提案连续性和 Spring 审批集成测试。
4. 后端 `./mvnw.cmd package` 全量回归与打包成功：**900 项，895 通过、5 跳过，0 失败、0 错误**。日志和逐类统计已保存。前端源码和迁移均未修改，本轮未重跑前端测试或生产构建。

主要实现：`agent/domain/tool/ToolArgumentValidator.java`、`agent/infrastructure/tool/AbstractApprovalWriteAgentTool.java`、`CreateTaskApprovalAgentTool.java`、`UpdateTaskApprovalAgentTool.java`。保留现有 Java Runtime、模型网关和审批事务，没有新增 Agent 框架。

## 真实链路结果

固定模型 `space-bunny-free`，使用副本已有的加密配置。本轮没有调用 ling；恢复为原 mimo 配置仅为环境恢复，没有使用 mimo 生成验收结果。没有遇到 Zen 429、403 或认证错误，也没有据历史限流预判当前状态。

副本项目 `3292c82f-7878-40f4-a7f0-5b12d5c939cd`；会话 `34d96f0e-e482-4500-9621-13615e9bf8fa`；提案 `e6f2da98-d45e-49de-8a88-82499a7da7ee`。

| 验收项 | 结果与证据 |
| --- | --- |
| 真实创建提案 | 通过，沿用交接创建 Run `5f2de7bd-960e-4d6f-a6d0-a4239b5516f6`。验收前仍为 PENDING、revision 1、2 小时、HIGH、Local Owner，未过期，正式任务数 0。本轮没有重新创建提案。 |
| 历史修订失败保留 | 原 Run `2585c7be-01ea-4494-8cf6-d55c7a79b8bc` 的整数误拒绝和纠正预算耗尽记录保留。 |
| 第一次本轮修订 | Run `46dbc232-d53a-4aa8-ab5e-3e1953cb62b8` 失败于展示元数据 DTO 转换，错误 `AGENT_TOOL_EXECUTION_FAILED`。真实模型返回整数 3，未消耗参数纠正预算；失败后提案仍 revision 1，正式任务数 0。经本地修复后才再次复验。 |
| 修复后真实修订 | Run `13bd8e62-07c7-42ad-9256-ddd3b0321c34` 成功，原提案 ID 不变，revision 1→2；标题改为“太空兔真实提案验收-180212-修订”，工时 3，HIGH 和 Local Owner 保持，原描述、状态、日期、里程碑等其余字段保持。审批前正式任务数 0。 |
| 旧版本审批 | HTTP 409，`AGENT_APPROVAL_CONFLICT`，说明“提案已修订，请刷新并确认当前版本”。提案仍 PENDING revision 2，无正式任务。 |
| 当前版本审批 | APPROVED revision 2，正式任务 `71c14de2-d0fd-4365-92d7-a67e05069869`；指定标题、工时 3、HIGH、负责人 ID/显示名符合提案，状态按业务默认值为 TODO。 |
| 同键重复批准 | 返回相同业务结果和任务 ID；按验收标题前缀检索恰有 1 项任务，没有重复写入。 |
| 重新读取/刷新 | API 再次读取审批结果及任务列表，结果一致、任务仍为 1 项。此项是 API 刷新验证。 |
| 真实只读查询 | Run `51c27fb4-0d1e-4729-9635-9fb2371e677c` 成功，原生 `get_task` 返回实际任务，首轮最终回答正确说明标题、TODO、Local Owner；没有要求用户读取字段路径。无新提案、任务完整记录未变。 |

本轮修订使用正常业务语言，第二次明确“仅修改标题和工时，原描述、状态、日期、里程碑、负责人和优先级保持”；没有传入人工 JSON 补丁或直接修改提案库记录以制造成功。

## 收尾轮（同日）：浏览器链路与回答范围

沿用隔离副本与真实验收宿主（18080）+ Vite（15173），模型显式 `space-bunny-free`，完成后已恢复 `mimo-v2.5-free`。前端修复先行，再以双页面浏览器场景验收；证据见 [space-bunny-browser-summary.json](acceptance-evidence/2026-10-03/space-bunny-browser-summary.json)。

1. 前端修复：批准/拒绝失败后刷新审批列表到服务端最新状态——提案已修订但当前版本仍待审批时展示最新版本供重新确认，只有已批准/已拒绝/已过期等不再待审批的记录移出待审批列表；刷新失败不覆盖原始审批错误。防重入锁在打开确认弹窗前占用，取消、失败、成功统一释放；提交期间批准、拒绝按钮同时禁用，loading 只出现在实际提交的按钮上。针对性组件测试覆盖：冲突刷新展示最新版本、非待审批移出、弹窗未完成时重复触发拦截、提交期间重复点击只发一次请求、拒绝冲突刷新、`AGENT_APPROVAL_EXPIRED` 过期文案与移出、刷新失败不覆盖原始错误；前端测试 **135 项全部通过**，`vue-tsc` 类型检查与 `vite build` 生产构建通过（日志已存证）。
2. 浏览器双页面旧版本场景（页面 A 持旧卡片、页面 B 完成修订）：页面 A 用旧 revision 批准得到提示“Agent 提案批准失败：提案已修订，请刷新并确认当前版本”，随后待审批列表自动展示最新版本（修订 #2）卡片；确认当前版本后任务创建，快速双击确认仅一次请求、无重复任务；刷新页面后任务看板与详情（标题/负责人 Local Owner/待处理/高/3 小时）与 API 一致。关键截图与请求/运行记录已存证。
3. 回答范围遵循：系统提示词在原生（`AgentModelMessageComposer`）与 Legacy（`AgentPromptFactory`）两条路径加入约束——用户明确限定字段时最终回答只呈现这些字段；工具结果与事件记录保持完整；未限定范围时不强制固定 JSON、不截断。提示词测试两条路径均覆盖，受影响 Agent 运行时回归 **76 项全部通过**；本轮全部修改完成后的最终后端 `mvnw package` 全量回归与打包成功：**904 项，899 通过、5 跳过，0 失败、0 错误**（`ai-space-bunny-closeout-full.log`）。真实模型只读查询（Run `9f1dc36f-7424-452e-85bc-10f7a8c1607c`，原生 Tool Calling 路径）最终回答仅含标题、状态（TODO）、负责人（Local Owner），`get_task` 工具结果完整保留全部字段。一次真实成功只证明该用例通过，不证明所有模型都能稳定遵循。

本轮新增/修改：`use-agent-workspace.ts`、`AgentApprovalCard.vue`、`AgentView.vue`、`AgentView.approval-conflict.test.ts`、`AgentPromptFactory.java`、`AgentModelMessageComposer.java` 及对应测试。保留现有 Java 编排，无架构重写。

## 未通过或未验证

- **回答严格只包含指定字段：收尾轮通过（本用例）。** 主轮中模型补充了优先级、工时等字段；收尾轮在两条提示词路径加入回答范围约束后，真实只读查询的最终回答仅含标题、状态、负责人，工具事实完整保留。该结论限于本用例与该模型，不宣称所有模型稳定遵循。
- **本轮浏览器链路与事实表：收尾轮通过（批准路径）。** 主轮 headless Edge 超时未验证；收尾轮以真实浏览器双页面场景完成修订、旧版本冲突提示、最新版本重新确认、批准、双击防重复与刷新后事实表核对（见收尾轮第 2 条）。未覆盖：`AGENT_APPROVAL_EXPIRED` 的浏览器提示（副本审批 24 小时有效，无法在本轮真实制造过期场景；该文案与移出行为由组件测试 `AgentView.approval-conflict.test.ts` 覆盖）与拒绝路径的浏览器冲突场景（拒绝的失败刷新、错误提示由同一组件测试覆盖，浏览器只验证了批准路径）。服务端同键幂等沿用主轮 API 证据，浏览器双击验证的是前端防重复提交，两者分别记录。
- 没有追加大规模稳定性、并发或长时间运行评测。本次成功不证明模型在所有需求上可靠。
- 正式环境发布、迁移未执行。

## 证据与环境恢复

证据目录：[2026-10-03](acceptance-evidence/2026-10-03/)。

- [真实复验 JSON](acceptance-evidence/2026-10-03/space-bunny-retest.json)：请求文本、完整 Run/工具结果、前后提案、旧版本 409、同键批准结果、正式任务和只读回答。
- [原始真实证据快照](acceptance-evidence/2026-10-03/space-bunny-original.json)：原始创建与失败修订，原 backend/target 文件亦保留。
- 数值失败回归、展示元数据失败回归、80 项受影响测试日志：`ai-space-bunny-validator-red.log`、`ai-space-bunny-metadata-red.log`、`ai-space-bunny-targeted-final.log`。
- 两次真实宿主日志：`ai-space-bunny-retest-host.log`、`ai-space-bunny-retest-host-final.log`。初次 API 登录未携带合法 Origin，被本地安全过滤器拒绝；补上该应用要求的 Origin 后登录成功。这不是 Zen 403，也没有触发模型请求。
- [全量测试与打包日志](acceptance-evidence/2026-10-03/ai-space-bunny-full.log)、[逐类测试统计](acceptance-evidence/2026-10-03/space-bunny-backend-tests.json)。5 项默认 opt-in 外部验收测试跳过；本轮真实宿主通过显式 opt-in 单独启动，真实链路结果来自上述 JSON，不将默认跳过计作通过。
- 收尾轮浏览器证据：[浏览器链路汇总](acceptance-evidence/2026-10-03/space-bunny-browser-summary.json)、[查询运行完整记录](acceptance-evidence/2026-10-03/space-bunny-browser-query-run.json)、[最终任务表](acceptance-evidence/2026-10-03/browser-final-tasks.json)、宿主/前端日志（`ai-space-bunny-browser-host.log`、`ai-space-bunny-browser-vite.log`）及截图（`space-bunny-browser-B-revision2-pending.png`、`space-bunny-browser-A-stale-approve-conflict-refresh.png`、`space-bunny-browser-A-current-approved.png`、`space-bunny-browser-board-task-after-refresh.png`）。
- 收尾轮最终构建证据（早于本轮的旧全量日志以本轮为准）：后端全量 `mvnw package` 日志 `ai-space-bunny-closeout-full.log`；前端测试、类型检查、生产构建日志 `space-bunny-closeout-frontend-tests.log`、`space-bunny-closeout-typecheck.log`、`space-bunny-closeout-frontend-build.log`。

所有业务写入限制在 `127.0.0.1:55432` 的既有离线副本及其合成项目，测试集成用临时隔离容器。原 `ai-collab-postgres` 未启动、未迁移、未写入；没有复制其他项目凭据或输出密钥。副本模型恢复为原 `mimo-v2.5-free`；真实验收宿主、Vite 和专用副本 PostgreSQL/MinIO/Redis 已停止。合成数据与日志保留，既有未提交修改未清理或覆盖。

收尾轮沿用同一口径：`space-bunny-free` 验收后副本模型已恢复 `mimo-v2.5-free`；真实验收宿主（18080）、Vite（15173）与三个专用隔离容器已停止，原业务库未启动。临时验收脚本与中间凭据文件保留在 `ai-collab-backend/target`（`space_bunny_browser_round.py` 等），未提交入库。
