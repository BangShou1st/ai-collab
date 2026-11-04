# V2 Before/After Product QA（真机，2026-09-10）

- before：docs/archive/superpowers/audits/product-experience-v2/before/（15 图）
- after：docs/archive/superpowers/audits/product-experience-v2/after/（17 图）
- 后端 local/8080（Flyway V44），前端 5173，owner 登录，演示项目复用。

## 解决的主要问题
- 无 Home → 新增 /home：问候、近期项目、临期/进行中任务、未读通知、待审批、近期活动、AI 卡。
- 双层横向导航 → 侧边栏：全局（工作台/项目/通知/AI 设置/账号/管理）+ 项目区（概览/看板/文档/问答/规划/Agent/更多视图/项目设置/切换）。
- 登录注册纯表单 → 统一品牌 + 价值 + highlights；注册后进 Home。
- 项目卡片默认堆砌 + 大红删除 → 分层信息 + 删除收进更多。
- 概览纯统计 → 健康条（逾期/阻塞/里程碑风险）+ 接下来要做什么。
- Agent 单栏 + 审批另 tab → 三栏（会话/对话/检查器：状态/审批/时间线/上下文/已处理）。
- 项目级模型三下拉全未分配 → 个人 AI 设置（默认 + 按用途覆盖 + 连接测试）；旧页废弃横幅。
- 嵌入无处可管 → 管理中心 AI Infrastructure（配置/测试/重建）。
- MCP 藏在模型页 → 项目设置 → 集成与 MCP 独立页。
- 单文件 736 行 styles.css → tokens/base/utilities/element-overrides 四分 + 工作区新样式。

## 仍然存在的限制
- 无真实 AI Provider：流式/引用/审批执行链按空错态验证，未烧真实费用。
- 对比度量测、完整键盘走查、窄屏 360px 以下细节待补。
- 任务/知识/规划页本轮只做 IA 与视觉继承，未重写（结构已合理）。
- 后端 7 个 planning wiring 历史失败与本轮无关（基线 worktree 同败已证），未动。
- favicon：保留现状（简洁 AI 标，未引入新品牌资产）。
- favicon：已收编为正式资产（index.html 引用 + 已入库），简洁 AI 标与 Direction A 一致。
- Planning 空白面已解决：空列表 empty state + 右侧成熟 empty（草案→审核→确认 + 创建按钮 + 未配置 AI 提示与入口），已重拍 09-planning.png。
- Backend suite 已全绿：7 个 planning 历史失败根因为 fixture 写死 2026-08 日期（计划窗口相对今天），生产校验正确，已改相对日期，全量 0 failures 0 errors。
- 有数冒烟：任务看板 3 卡、概览非 0（总任务 3 + 下一步列表），规划/Agent 创建入口正常，Knowledge 保持未配置态。
- git status 真正 clean（public/ 已入库）。分支 READY TO PUSH，未 merge 未 push。
