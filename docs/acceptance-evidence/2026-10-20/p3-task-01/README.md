# P3-TASK-01：工作台待办直达任务详情

日期：2026-10-20（Asia/Shanghai）。批准范围：仅 P3-SCOPE-01 U-01。分支：`dev`。

## 实现与基线

开工 `HEAD`、`origin/dev` 均为 `9348c60e7dc4b9c07c856024627b2db8a3129c3b`，工作区干净；执行 `git fetch origin` 后无新增提交需要合并。复用当前 checkout、运行中的 dev.ps1 前后端与三个基础设施容器，没有重置历史、覆盖用户改动、操作历史文档或删除数据卷。

- HomeView 将“临期任务”改为“我的待办”，每项含标题、项目、真实状态、截止日期/未设置提示；原生 RouterLink 指向 `/projects/:projectId/board?taskId=:taskId`。最多展示五项，文案不声称总数；保留空态，复用 Home API。
- URL 是详情选择的来源。普通卡片打开通过 router.push 更新 taskId；关闭立即清详情并 push 清除 taskId，保留其他 query。路由 watcher 读取已有详情和评论 API，不依赖看板筛选列表。
- 项目 ID 改为响应式；详情、列表、看板读取分别有请求代次/项目/卸载检查。切换目标立即清旧标题、评论和评论草稿；详情与评论都成功后才展示内容。相关编辑、状态刷新和评论读取同样检查上下文。非法/重复/空 taskId 不请求后端；失败显示中性提示，不自动重试。
- HomeView 局部修正窄屏 grid 最小宽度与换行；详情抽屉宽度受视口限制。其余设计、任务权限和 AppShell 项目门控复用。

未修改后端、数据库、API 契约、Agent、SSE、模型配置、规划确认或审批；U-02/U-03/U-04/U-05 未实施。无真实 AI 模型调用。

## 自动化验证（不是浏览器证据）

新增 `use-task-board-view.test.ts` 17 项真实内存 Router + composable 测试，以及 HomeView 2 项组件测试；保留所有既有测试。

| 检查 | 实际结果 |
| --- | --- |
| `pnpm lint` | PASS：0 errors、78 warnings（与基线相同） |
| `pnpm typecheck` | PASS |
| `pnpm test` | PASS：45 文件、290 项（基线 43 文件、271 项） |
| `pnpm build` | PASS；既有 PURE 注释/大 chunk 提示保留 |
| `git diff --check` | PASS |
| impeccable detect（入口/抽屉） | 空结果；辅助检查，不替代真实 UI |
| 本地 Maven | 未重复执行；无后端改动，最终后端 CI 仍为交付门槛 |

日志：[lint](frontend-lint.log)、[typecheck](frontend-typecheck.log)、[test](frontend-test.log)、[build](frontend-build.log)。首次类型检查因新增测试夹具缺少 Task 字段失败，已补全并重跑成功；一次测试因卸载 Router 重置路径失败，改为卸载前保存 URL，保留刷新断言。

17 项路由用例覆盖：深链初始化、列表外目标、无 taskId、普通卡片、query 切换、返回/前进、关闭保留 sourcePlanId/其他 query、重挂载恢复、非法/空/重复 ID、403/404、评论拒绝不展示标题、任务/评论迟到、关闭/卸载/项目切换、旧列表迟到、无关 query/筛选不重读详情、撤权读取失败清内容、迟到状态写入不覆盖新详情。Home 测试覆盖原生链接 href、真实状态、项目/日期信息、五项预览和空态。

## 真实 Edge / BSK 验收

环境：本机前端 `localhost:5173`、后端 `8080`，真实 Edge 155、BrowserSkill CLI/扩展 0.3.3。后台 session `epyn`，验收完成后才 stop。使用已有 Local Owner 与两个既有合成项目：P2-HARDEN-01 Member access fixture、P2-E2E-02 synthetic RAG and planning acceptance。新增合成任务 A（无截止日期）、B（2026-10-30 截止），均分配给 Owner；未修改历史规划任务内容。

已有 Member 的登录凭据不可用，故通过合法 UI 注册本轮合成账号 P3 Task Member，经 Owner 的 MEMBER 邀请加入第一个合成项目。完成查看和退出项目测试后，该账号已退出项目、退出登录；恢复 Local Owner 登录。没有修改已有成员角色或凭据。邀请已被使用；凭据/邀请代码不进入证据文件。

| 场景 | 真实浏览器结果 | 证据 / 限制 |
| --- | --- | --- |
| 工作台 → 正确项目/任务 | PASS：A 用键盘 Enter 打开；B 用鼠标点击打开第二项目正确详情，显示 10-30 截止日期 | [桌面工作台](01-home-desktop.png)、[A 详情](02-detail-refreshed.png)；真实 UI 与 DOM 观察 |
| 直接链接与刷新 | PASS：Owner A 深链刷新、Member 直接导航 A、Member 窄屏刷新均保持正确任务 | [刷新后评论](02-detail-refreshed.png)、[Member 窄屏](04-member-detail-narrow.png) |
| 普通看板入口与关闭 | PASS：无 taskId 的看板不弹窗；从普通 Document acceptance criteria 卡片用 Enter 打开，URL 更新到该任务；Escape/关闭按钮清 taskId | 实际 BSK 交互与 location.search 检查；普通历史任务仅只读 |
| 关闭保留 query、返回/前进 | PASS：带 sourcePlanId=p3-fixture、extra=keep 的 A 深链关闭后仅保留两项；Back 重开 A，Forward 关闭 | BSK history + selector/URL 检查；BSK 等待 SPA lifecycle 报 timeout，但最终 URL、可见选择器均正确，未将 timeout 当成功依据 |
| MEMBER 正常权限 | PASS：可读合法 A 与 Owner 评论；管理任务和依赖按钮隐藏，不能编辑 Owner 评论 | [Member 窄屏](04-member-detail-narrow.png)；未尝试越权写入 |
| 未加入项目 | PASS：Member 打开第二项目看板深链，AppShell 显示“项目不存在或无权访问”，无任务/评论 | 真实 observe；该场景截图 RPC 超时，没有冒称已保存截图 |
| 成员资格失效 | PASS（有限范围）：本轮 Member 主动退出合成项目后，再导航原 A 深链同样中性拒绝，未呈现旧详情 | 真实退出确认与 observe；不是 Owner 在另一会话移除成员的实时注入 |
| 非法任务 | PASS：合法项目内 taskId=not-a-task 显示中性反馈，抽屉无旧标题/评论 | [非法任务](06-invalid-task.png)；删除/不存在 UUID/跨项目任务的详情接口拒绝分支以自动化覆盖 |
| 桌面 / 窄屏 | PASS：桌面 1440×900；真实 Edge iPhone-14 仿真 390×844，Home scrollWidth=390，详情抽屉宽 374，无横向溢出；标题/项目/日期可读 | [桌面](01-home-desktop.png)、[窄屏工作台](03-home-narrow.png)、[窄屏详情](04-member-detail-narrow.png)；不是物理手机测试 |
| 键盘 | PASS：原生链接 focus + Enter，普通任务卡 Enter，Escape 关闭详情 | 真实 BSK 键盘操作；未进行屏幕阅读器测试 |
| 合法写入持久化 | PASS：Owner 在 A 新增合成评论“P3-TASK-01 合成验收：待办深链接评论刷新持久化。”，刷新后仍存在，Member 也能读取 | [刷新评论](02-detail-refreshed.png)；选择评论作为本轮写入验收，未另改任务状态 |
| 空态 | PASS：新增夹具前 Owner 工作台显示“暂无待办任务”；新增空态组件断言也通过 | 真实 observe + 自动化；没有单独空态截图 |
| 快速 A/B、迟到、同组件跨项目 | 自动化 PASS | 确定性 deferred Promise 覆盖；真实浏览器未人为延迟网络制造乱序，不以普通导航替代竞态证据 |
| 列表外详情 | 自动化 PASS | 列表返回空仍打开详情；真实 UI 未额外组合全部筛选条件 |

浏览器备注：静态页面截图数次发生 BSK RPC timeout；成功文件均通过真实 BSK screenshot 获取并逐张查看。窄屏初检发现工作台撑宽到 510px，已在 HomeView 局部修正并复验为 390px。一次移动仿真鼠标点击未导航，改用直接深链完成窄屏详情验证；鼠标点击入口另在桌面成功。未把这些操作器异常记成产品通过。

截图仅含合成项目/任务/测试账号，未含凭据、请求头、浏览器地址栏或真实业务 ID；没有保存原始网络正文/完整控制台（其中可能含 Vite 临时连接参数）。

## 可复现步骤

1. 用 `dev.ps1 status` 确认本机服务，在真实 Edge 中登录合法测试 Owner。打开工作台，通过标题辨认 A/B（没有夹具时在两个合成项目中新建并分配给自己）。
2. 点击任一待办，核对项目、标题与日期，复制站内链接直接访问并刷新。
3. 在 URL 添加 sourcePlanId 与额外 query，关闭详情核对只移除 taskId；浏览器 Back/Forward 核对详情同步。普通卡片打开后再刷新。
4. 合法角色添加合成评论并刷新。使用 MEMBER 访问已加入项目与未加入的私有项目；需复验失效时仅退出/移除本轮合成账号，勿改历史成员。
5. BSK 以 390×844 仿真检查工作台与详情，使用原生链接 Enter、卡片 Enter 和 Escape。单测运行 `pnpm exec vitest run src/modules/work/use-task-board-view.test.ts src/modules/home/HomeView.test.ts` 复验乱序边界。

## 未验项、风险与回退

- 无权限/404 的真实页面由 AppShell 与既有后端鉴权处理。本轮未新增权限策略。已经读到的数据不支持服务端主动撤回；失权在下一次导航/刷新/受保护读取时识别，未新增权限轮询或推送。
- 未做另一会话 Owner 移除 Member、真实网络乱序、屏幕阅读器、物理移动设备、每种筛选组合的浏览器测试；相关路由/请求竞态由自动化覆盖。
- R-3 维持 OBS-02 受控真实通过；R-1 历史文档 BLOCKED、R-2 旧 ERR_ABORTED UNKNOWN、R-4 未验外部异常、R-5 opt-in 跳过按已有台账追踪。本轮不关闭这些风险。
- 回退：普通 `git revert` 本轮功能提交，再正常推送 dev；无需数据库回滚。不删除两个合成任务、评论、账号或 Docker 数据卷。必要时仅移除首页链接和 taskId 同步相关变更，但保留原权限代码和测试。

## Git 与 CI

实现、19 项新增测试、截图与本报告将共同提交并普通推送 `dev`；不合并 main。最终提交 SHA 及对应 GitHub Actions Frontend / Backend 结果在交付消息核对，不能引用基线绿色 CI 代替。若另有报告元数据补记，最终消息同时记录该最终 SHA 的 CI。
