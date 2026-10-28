# ux-readiness.md — 前端 UX 发布门禁

> 审计方式：源码级审计（组件 + composable 逐项定位）+ 五份 P4 验收的浏览器证据复核。本轮未开展视觉重构，未做全面浏览器复核（决策依据见文末）。

## 1. 已知 Agent 观察项核实（5/5 全部源码确认成立）

| # | 观察项 | 定位 | 判定 |
| --- | --- | --- | --- |
| a | 历史 FAILED Run 无 UI 重试入口 | AgentView.vue:363（重试按钮 `v-if="activeRunState?.canRetry"` 仅渲染于 activeRun inspector）；use-agent-workspace.ts:633-659 retryActiveRun 硬绑定 activeRun.id；:277 restoreSession 只拉 latestRun，UI 无 Run 历史列表 | **P2（重要但可延期）**。失败 Run 一旦被新消息顶掉即永久失去重试入口；兜底路径（重新发消息开新 Run）可用，主流程不断。但丢失「派生重试保留上下文」能力，且免费模型瞬态超时（planning-probe 第 1 次 504 实证）会被放大。**回答任务书问题 13：不需要在发布前修复——有可用兜底且后端 API 完备，列入 RC 期间 UX 批次** |
| b | 历史审批卡仅加载 activeRun | use-agent-workspace.ts:572-587 refreshApprovals 默认取 activeRun.id；AgentView.vue:345/382 审批区 v-if="activeRun" | **P2**。非最新 Run 的 PENDING 审批刷新后从 UI 消失；服务端审批闸门不受影响（不会漏执行），但形成「隐形待办」 |
| c | 历史 Assistant 消息「待审批」快照文案 | 生成点在后端 AgentToolCallExecutor.java:792（持久化快照），前端经 marked+DOMPurify 渲染 | **有意设计（非缺陷）**。权威状态以审批卡 tag 为准；维持不重写历史消息 |
| d | cancelActiveRun 无防重复 loading | use-agent-workspace.ts:660-666 无 busy ref；AgentView.vue:335 未绑 :loading（对比 :334 pauseBusy、:370 retry loading） | **P3**。后端幂等兜底，仅体验不一致 |
| e | agentApi.resume 死代码 | agent-api.ts:53-55；生产零调用；AgentView.pause-resume.test.ts:181 显式断言不调用 | **P3**。删除即可 |

## 2. 高频路径三态与防护覆盖 — 覆盖率高

| 页面 | 加载态 | 空状态 | 错误收敛 | 危险操作确认 | 防重复提交 |
| --- | --- | --- | --- | --- | --- |
| 登录/注册 | ✓ | — | showApiError | — | ✓（注册有密码 8-72 位双校验） |
| 项目列表 | ✓ | ✓ | ✓ | 删除 ElMessageBox | saving loading |
| 任务看板 | ✓ | 每列空态 | ✓ | 删除确认+deletingTask | opening/creating/saving/batch 全覆盖 |
| 知识库问答 | ✓ | ✓ | 全部 catch→showApiError；流式失败有可见 streamError 块（:358） | 会话删除确认 | submitting/creating/deletingId |
| 文档上传 | ✓ | ✓ | ✓ | 重索引确认 | uploading |
| AI 设置 | ✓ | ✓ | ✓ | 删除配置确认 | 各入口 saving loading |
| 规划 | ✓ | ✓ | ✓ 全覆盖 | ⚠️ 删除/离开用原生 window.confirm（R-23，P3 风格不一致） | ✓ |
| Agent | busy | ✓ | fail()→showApiError 全覆盖 + 作用域核对防迟到响应 | 删除会话/批准/拒绝均确认 | 审批/暂停/重试有，仅 cancel 缺（观察项 d） |

权限不足提示：统一错误码映射（403→「没有权限执行此操作」）；项目级越权有专门收敛页（AppShell.vue:214-223「该项目不存在，或当前账号没有访问权限」）；401 统一静默刷新重试。

## 3. 安全相关前端检查 — 全部合规

- **v-html（全仓 4 处）全部经 DOMPurify**：AgentView.vue:222/:300（markdown() 管线，AgentView.vue:44,64-70）；KnowledgeView.vue:141/:207（knowledge-render.ts:5-12，FORBID img/a/iframe/form/style）。
- **Token 存储**：access token 仅 Pinia 内存态（auth-store.ts:9），无 localStorage 持久化；refresh 走 withCredentials cookie；sessionStorage 仅存规划幂等 UUID（非凭据）。
- **SSE/流清理**：Agent 流 onUnmounted abort + disposed 标记（use-agent-workspace.ts:740-744，卸载后响应不得触碰状态）；知识库流 onUnmounted abort（use-knowledge-view.ts:482-486）；全局监听 onBeforeUnmount 移除。

## 4. 类型质量 — 发布级

生产代码 `@ts-ignore` 0 处、显式 `any` 0 处（初筛 9 处命中均为 CSS `overflow-wrap: anywhere` 误报）；仅 1 个测试文件使用 any。

## 5. 本轮是否需要新一轮真实浏览器验收？

**发布决策层面不需要**：核心闭环均有 2026-10-24 内的真实浏览器证据，且其后仅 docs-only 提交。建议（非必须）RC 期间补两个低成本项：规划页面编辑+确认复核（闭环 3 的 GAP 部分）、work 可视化域冒烟（R-06）。本轮 Agent 相关 5 个观察项均为源码确认 + 历史浏览器证据交叉验证，无需重跑。

## 6. 结论

无 UX 阻断缺陷。R-07/R-08 两项 P2 均有兜底路径，判定为「重要但可延期」，列入 remediation-plan.md S2；R-16/R-17/R-23 为 P3 顺手修批次。
