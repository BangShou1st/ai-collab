# P3-TASK-02：任务通知直达任务详情

日期：2026-10-20（Asia/Shanghai）。批准范围：P3-SCOPE-01 **仅 U-02**。分支：`dev`；不合并 `main`。

## 基线、数据契约与权限结论

开工 HEAD 与 origin/dev 均为 `c41ddca3490921d836bd040c451788d5c34372c4`，工作区干净。`git fetch origin` 后仍一致，`git merge-base --is-ancestor c41ddca3490921d836bd040c451788d5c34372c4 HEAD` 返回 0；无覆盖用户改动、强推、硬重置、数据库清理或 Docker 卷操作。仓库内未找到额外 AGENTS.md，遵循用户提供的全局指令。已读 README、architecture、capability-matrix、p3-scope-01 和 TASK-01 证据，并核对指定源码。

- `NotificationApplicationService.create` 接受服务端业务传入的 projectId/entityType/entityId；通知不是前端任意 URL。任务创建/状态变化/依赖完成及 WorkVisualizationService 的提醒使用 `TASK` 与对应任务 ID。`NotificationReminderJob` 从 project_task 取项目/任务/负责人，并 JOIN 当前 project_member 后生成截止通知。文档和规划通知另用其实体，不能根据通知标题或 type 标签推测目标；TaskCommentApplicationService 当前不生成评论通知。
- V19 的项目/实体 ID 为 UUID，项目 ID 非空且关联项目；entityId 可空，没有任务外键，故任务删除后个人通知可继续存在。项目删除则随项目外键级联删除通知。本轮没有迁移。
- `NotificationMapper.listByUser` 和未读/已读操作以收件人 user_id 隔离；个人收件箱既有语义是本人历史记录。项目内通知列表另外经过 `requireMember`。因此保留退出/移除后的本人历史通知与已读能力，不扩大到他人通知，不将通知视作任务访问凭证。
- AppShell 在渲染项目内容前加载项目上下文；DatabaseProjectAccessGuard 以当前 project_member 决定权限，非成员中性 PROJECT_NOT_FOUND。任务详情、评论 API 各自检查成员与 projectId/taskId 关联。失权页面没有旧标题、旧评论或被拒绝项目的详情；个人收件箱仍显示本人历史通知文案，这与目标访问权限分开。

## 实现与最小相关修复

1. `notification-task-route.ts` 只接受精确 `entityType === 'TASK'` 及两个规范 UUID 字符串，返回固定 `/projects/:projectId/board?taskId=:taskId`。不读 content/title 作为 URL，不拼接任意实体，不支持外部跳转。未知实体、缺失/非法 ID 不显示任务入口，仍可已读。
2. 通知卡片移除点击 div，新增原生 button 语义的“标为已读并打开任务”/“打开任务”和独立“标为已读”。未读请求成功后才更新本地 read 并导航；失败报错且不导航、不报成功，允许重试。已读通知可反复打开且不重复写入。
3. 页面 busy 锁串行化单条已读、打开、全部已读和加载更多。避免重复点击和旧分页响应覆盖全部已读。离页/路由变化/卸载使请求上下文失效，迟到结果不修改当前列表、不弹过期提示、不拉回旧任务。下一页继续采用服务端返回的 read 状态，不把全部已读之后的新通知强制改为已读。
4. **真实验收发现的既有 Mapper 缺陷**：自定义 SELECT n.* 未把 is_read 映射到实体 read，导致数据库更新成功、未读计数减少但刷新后 DTO 仍 read=false。新增真实 PostgreSQL 测试先红（3 tests / 3 failures），仅在列表与按实体查询中添加 `n.is_read AS read`；修复后 3/3 通过，未改写入范围和历史保留策略。
5. **真实返回链路发现的相关 U-01 离页缺陷**：看板同步 watcher 在卸载前观察到清空的 projectId，发出 `/projects//members`、`/tasks`、`/milestones` 等请求，401 干扰认证导航。新增测试先红（离页时任务列表被调用两次），给 `load`/`loadTasks` 加空项目与 disposed 守卫。真实返回/前进复验通过。深链协议、详情、评论和权限系统均复用原实现。

未实施 U-03～U-05，也未触及 Agent、RAG、SSE、审批、模型调用、通用通知深链或新状态平台。

## 自动化验证（独立于浏览器）

新增前端 31 项：NotificationView 27 项（含参数化白名单），看板 4 项（通知目标成功/403/404 衔接及返回通知时不发空项目请求）。原有测试保留。

| 检查 | 结果 |
| --- | --- |
| `pnpm lint` | PASS：0 errors，78 个既有 warnings |
| `pnpm typecheck` | PASS |
| `pnpm test` | PASS：46 文件、321 项（基线 45 文件、290 项） |
| `pnpm build` | PASS；既有 PURE 注释和大 chunk 提示保留 |
| `mvnw.cmd -B -ntp -Dtest=NotificationNavigationPostgresTest test` | PASS：3 tests，0 failures/errors，0 skipped；真实 pgvector PostgreSQL 容器与 Flyway |
| `git diff --check` | PASS |

通知测试覆盖：固定内部目标、11 类非法/未知输入、未读先写后跳转、已读重复打开、单条失败/重试、独立已读、重复提交、分页/全部已读互斥及失败重试、离页后的读请求成功/失败、分页/全部已读迟到、同组件 query 变化、全部已读之后的新通知保持未读。看板沿用既有删除/拒绝/评论失败/任务与评论迟到/项目切换/关闭和历史测试，并新增通知路由衔接与离页回归。

PostgreSQL 测试覆盖：已读后新 session 的个人/项目/实体查询正确；分页 20+2 与全部已读持久化；另一收件人不能读取或修改该通知；移除成员后个人历史仍可读/已读，项目范围列表拒绝。CI 的容器实际执行守卫已加入新测试类，不能整类跳过冒充通过。

检查摘录见 [checks.txt](checks.txt)。本地完整日志只放在 Git 忽略的 target/local-dev，不提交认证信息、原始网络请求或运行日志。只运行相关本地 Maven 测试，后端全量 verify 由最终 GitHub Actions 执行。

## 真实 Edge / BSK

环境：真实 Edge 155、BSK CLI/扩展 0.3.3；后台 session `ybei`，全部验收完成、合成 Member 退出登录后才 stop。桌面 CSS viewport 1542×847（滚动条可用宽度 1526），窄屏为同一真实 Edge 的 iPhone-14 仿真 390×844，不是物理手机。

夹具通过现有业务 API 的公开注册、邀请接受、项目创建及任务创建产生：独立合成 Owner/Member、两个合成项目，第一项目创建 24 个分配给 Member 的任务；第二项目另建一个用于三个入口对照的任务。初始 22 条真实 TASK_ASSIGNED 通知用于分页，随后补 2 条验证单独已读/撤权，再补第二项目任务验证入口一致。不操作历史用户通知；无直接 SQL 修改应用数据库。仅通过合法 Owner 删除本轮 task 21、移除本轮 Member 的第一项目资格。账号、项目、其余任务和评论作为可复验夹具保留；Member 仍属于第二个合成项目，浏览器最终已退出该合成账号。

| 场景 | 真实结果 | 证据与边界 |
| --- | --- | --- |
| 未读 TASK → 正确任务 | PASS：task 22 用 Space 打开正确项目与负责人；task 21 在窄屏用 Enter 打开 | [桌面通知](01-notifications-desktop.png)、[详情及评论](02-task-comment-refreshed.png)；核对 pathname/query 和详情标题 |
| 未读转已读并持久化 | PASS：返回通知页并刷新，task 22/21 均“打开任务 / 已读”，原高亮消失 | [窄屏已读/未读对照](04-notifications-narrow.png)；修 Mapper 后再验，未把修复前错误记为通过 |
| 已读重复打开 | PASS：task 22 多次打开仍为原任务，合法 Member 可读评论 | 桌面点击与真实 Back/Forward |
| 单独已读 | PASS：task 23 的“标为已读”用 Space，留在通知页；刷新后已读，task 24 仍未读 | 真实 observe；不是全部已读代替单条验收 |
| 合法 Member 继续协作 | PASS：从通知进入 task 22 添加合成评论，刷新后仍在；无任务管理按钮 | [刷新后评论](02-task-comment-refreshed.png)、[窄屏详情](03-member-detail-narrow.png) |
| 删除目标 | PASS：Owner API 删除本轮 task 21，再从其已读通知 Enter 打开，中性“任务不存在或无权访问”，无旧详情 | [删除目标](05-deleted-task-neutral.png) |
| 不存在的 UUID | PASS：已加入第二项目中访问不存在 UUID，只有中性任务反馈 | 真实深链 observe；未伪造一条非法业务通知 |
| 任务 ID 属于其他项目 | PASS：Member 同时有两个项目权限时，将第一项目 task 22 放入第二项目深链，拒绝且无旧标题/评论 | [跨项目目标](07-cross-project-neutral.png)；验证目标 API 关联检查，不伪造通知记录 |
| Owner 移除 Member | PASS：Owner API 移除第一项目资格后，Member 从未读 task 24 通知操作，已读成功但 AppShell 中性拒绝；刷新仍拒绝 | [移除后拒绝](08-member-removed-neutral.png)；这是另一合法 Owner 会话执行移除，非 Mock |
| 个人历史与任务访问分离 | PASS：移除后返回通知页仍能看到本人历史，task 24 已读持久化；无任务详情绕过 | [本人历史通知](09-personal-history-after-removal.png) |
| 返回/前进、刷新、关闭 | PASS：Back 回通知页，Forward 重开 task 22；刷新保留 taskId/详情/评论；Escape 关闭清 taskId | 修复空项目请求后重复验，URL 与 DOM 一致。BSK SPA lifecycle 等待报告 timeout，实际 URL/选择器/详情均另行核实 |
| 工作台/普通看板/通知同一目标 | PASS：第二项目 same task three entries 从 Home 链接、普通看板卡 Enter、通知按钮进入，三次 pathname/query/标题/负责人完全相同 | 真实 BSK 操作与只读 DOM/URL 结果；没有第二套路由 |
| 分页与全部已读 | PASS：20 → 22 条，加载更多消失；全部已读 22 条；刷新重新加载两页仍 22 条/0 未读 | [分页全部已读](06-pagination-read-all.png)；截图为视口，22 行数量由真实 DOM 核对 |
| 桌面/390px | PASS：文字和操作可读、按钮换行；通知页 scrollWidth=390；详情宽374、页面 scrollWidth=390 | [窄屏通知](04-notifications-narrow.png)、[窄屏详情](03-member-detail-narrow.png) |
| 键盘 | PASS：通知按钮 Enter/Space；独立已读 Space；普通卡 Enter；Escape 关闭 | 原生 button 行为；未做屏幕阅读器验收 |
| 非 TASK 正常展示 | PASS（只读）：开工 Local Owner 的既有规划/文档通知有独立已读按钮、无任务入口，未改这些历史通知 | 真实 observe；没有截图归档历史内容 |

所有截图均由 BSK screenshot 生成并逐张查看，只含合成项目/账号/任务/评论；不含密码、token、Cookie、请求头、地址栏或真实用户数据，无需像素级遮挡。BSK 诊断仅在复现已读/历史问题时采集，不提交原始网络导出。

## NOT_TESTED、安全边界与回退

- **真实浏览器 NOT_TESTED**：已读 API 人为失败、重复请求网络竞态/迟到注入、未知 entityType/非法 UUID 通知样本、物理手机、屏幕阅读器。相应失败/迟到/非法字段分支由确定性自动化覆盖，不将 Mock 声称为真实服务通过。UUID 数据库存储与现有业务操作不能合法产生 malformed ID 通知，故不篡改数据库补样本。
- 本轮真实通知来源为任务分配；截止提醒、依赖与状态变更通知的来源契约已核对源码，未逐类等待定时作业生成真实样本。
- 导航只做形状校验，不证明任务存在、属于指定项目或当前用户仍是成员；最终必须经过既有 AppShell 与后端成员/任务关联检查。本人历史通知保留是收件箱语义，不开放项目读取权限。没有新增撤权实时推送或轮询，已经交付到客户端的数据不会被服务端追溯抹除；下一次受保护读取/导航/刷新执行拒绝。
- R-1～R-5 维持既有专项状态，不因本轮通知验收升级为全部通过。没有真实模型调用。
- 回退使用普通 `git revert` 本轮功能提交并正常推送 dev；不重置分支、不删除数据、不回滚数据库迁移（本轮无迁移）。功能回退可与报告补记分开；保留权限检查和真实数据。

## Git 与 CI

功能提交、最终交付提交及对应 CI 在推送后补记。只有最终交付 SHA 的 Frontend / Backend 两项成功才作为交付通过；不使用基线绿色 CI 替代。最终交付消息另行给出经核对的完整 SHA、CI 链接与工作区状态。
