# 下一轮完整开发提示词：P3-TASK-01

以下整段可直接发送给新的本地开发会话：

---

你现在接手 AI Collab 的 P3-TASK-01：工作台待办直达任务详情。本次明确批准实施 P3-SCOPE-01 的候选 U-01；其他候选暂不实施。

项目目录 `E:\project\ai-collab`；使用 `dev`，不新建长期远端分支。规划起点 `09d80639185fbb89e74b71d37d3af272bf43ad5f`；本轮应从已集成 P3-SCOPE-01 的最新 dev 开始，先记录实际 HEAD、工作区和远端差异，检查祖先关系，不 reset 到旧 SHA。保留用户已有改动。

先读 AGENTS 指令、README、docs/architecture.md、docs/capability-matrix.md、docs/p3-scope-01.md、docs/acceptance-evidence/2026-10-20/p3-scope-01/README.md，以及 P2-BUSINESS-E2E-02、P2-HARDEN-01、P3-ENTRY-01、P3-OBS-02。RAG、规划真实核心闭环和 SSE 受控真实恢复已经通过，不重复开发或无理由重验。

目标：成员在个人工作台看到自己的待办后，能够直接打开正确项目中的任务详情；直接访问链接、刷新、返回/前进均行为明确。现状：HomeData.myTasks 已含 id/projectId/projectName/dueDate；HomeView 任务行未提供链接；看板仅识别 sourcePlanId，不处理 taskId；现有 openTask/详情读取/评论/权限应复用。HomeMapper 查询的是未完成任务，按截止日期排序而无“临期”日期条件，因此先用“我的待办”等准确标题，不新增临期计算需求。首页最多展示五条，是预览而非全量计数；不要把长度冒充总任务数。

范围：

1. 首页任务条目显示项目名、截止日期或“未设置截止日期”、状态，提供可键盘访问的内部链接 `/projects/:projectId/board?taskId=:taskId`。保持有界预览和空态，不新建全量“我的任务”中心。
2. 看板通过 taskId 调用已有详情接口，不要求当前筛选列表含该任务。详情、评论、权限和错误文案复用既有规则，不绕过服务端鉴权。
3. 初始化/刷新/同页 query 改变/返回前进可恢复正确详情；关闭详情清除 taskId，保留 sourcePlanId 和其他既有 query。无 taskId 时保持原看板行为。手动点击普通看板任务与 URL 行为一致，避免刷新回到旧任务。
4. 防止快速 A→B、跨项目切换和迟到响应显示旧详情；必要时在看板局部使用请求序号/失效检查。不以本任务扩展为全站状态管理重构。核对 route projectId 在当前 composable 中的生命周期，不能只改 query 而忽略同组件复用。
5. 非法/缺失/删除/跨项目任务与撤权有中性反馈，清除旧详情；项目无权限仍由 AppShell 中性 404 屏蔽。普通成员现有查看/自己任务操作边界不变。不根据 URL 授权，不泄漏任务名或旧内容。重复 query/非法 ID 不应触发无界请求。

不做：通知深链/审批聚合、模型健康仪表盘、文档重试改造、自动分配、Agent/SSE 策略变化、数据库迁移或新增后端聚合 API。若发现已有等价功能，先记录证据，直接复用；若契约实在不足，说明具体最小缺口再决定局部调整，不扩充候选集合。

实现前阅读 `HomeView.vue`、`src/api/home-api.ts`、`TaskBoardView.vue`、`use-task-board-view.ts`、`TaskDetailDrawer.vue`、`work-api.ts`、AppShell/项目上下文和路由；后端 HomeMapper 只读核对。UI 相关技能按本会话可用目录选用并遵守。所有浏览器操作只能 browser-skill/bsk，先读 SKILL.md；后台启动 `bsk session start --no-focus --name ai-collab-p3-task-01 --json`，已有页面状态需要 borrow；动作前 observe，验收结束前不 stop。启动应用用现有 dev.ps1，Docker 只能用 Docker Desktop 主程序启动，禁止清卷。

验收矩阵（每项标注自动化/真实浏览器/未验证，不互相冒充）：

| 场景 | 必须结果 |
| --- | --- |
| 工作台→详情 | 合法成员两项目合成任务，点击目标仅打开正确项目/任务，显示日期和状态 |
| 直接链接和刷新 | 新开同登录态标签访问 taskId 链接与刷新均打开同一任务 |
| 导航与并发 | A/B 快速切换、跨项目、返回/前进、迟到响应不会串内容（竞态至少自动化确定性覆盖） |
| 关闭与普通入口 | 关闭清 taskId，其他 query 保留；看板手动点任务不留下旧 taskId；无 query 不自动弹详情 |
| 筛选 | 目标不在当前列表筛选结果中仍可合法查看，列表筛选/来源高亮保留 |
| 错误与权限 | 删除、非法、跨项目目标不泄漏旧内容；真实 Member 私有项目页面保持中性拒绝 |
| 既有操作 | 合法角色的状态修改/评论后刷新持久化；不扩大 Member 权限；已有看板测试保持 |
| 空态和窄屏 | 无待办可理解；桌面与窄屏任务文本、链接、详情无溢出或遮挡，键盘可访问 |

自动化使用有意义的组件/路由交互与竞态测试；测试至少覆盖深链接初始化、query 变化、关闭保留其他 query、目标不在列表、错误清旧内容和迟到响应。前端跑 pnpm lint、pnpm typecheck、pnpm test、pnpm build；后端不改则不为本地任务重复全量 AI 验收，但不得跳过现有 CI。仅合成数据，真实模型调用不是本任务验收前提。

交付：实现和测试；`docs/acceptance-evidence/<实际日期>/p3-task-01/README.md`、脱敏截图/结果；更新能力矩阵和 P3 候选 U-01 实际状态。按仓库分支策略在 dev 提交、普通 push，核对该 SHA 的前后端 CI 成功并返回链接；不合并 main。报告实际未验项，不虚构通过。回退为仅撤回本任务入口/路由改动，不删数据。

最终给出变化、验收矩阵结论、Git SHA/CI、剩余风险。保留 R-1、R-2、R-4、R-5，R-3 为受控真实验收通过；不以重复稳定性工作代替本轮产品交付。
