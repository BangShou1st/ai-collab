# Lane C · H-3 审批收件箱

## 基线

- 起始提交：`873fc6f06a4712aca32972f80a8d8a8fa83cbec5`
- Lane 代码提交：`8422bdad5b166dbff2df9df96208031f55550b76`
- 分支：`hardening/p1-approval-inbox`
- Worktree：`E:\project\ai-collab-wt\p1-approval-inbox`

## 实现状态

| 项 | 状态 | 证据 |
| --- | --- | --- |
| 项目级审批收件箱支持 `status` / `updatedSince` | IMPLEMENTED | `AgentApprovalController`、`AgentApprovalRepository#list(projectId,status,updatedSince)` |
| 收件箱返回 `{items,serverTime,maxUpdatedAt}` | IMPLEMENTED | `AgentApprovalInboxResponse`、`AgentApprovalInboxView` |
| ETag / `If-None-Match` / 304 | IMPLEMENTED | `AgentApprovalControllerTest`、`raw/polling-after-approval.json` |
| 审批状态跃迁推进 `updated_at` | IMPLEMENTED | `AgentApprovalRepository` 的 approve/reject/expire SQL；`AgentApprovalInboxPostgresIntegrationTest` |
| `useApprovalInbox` 5 秒增量轮询 | IMPLEMENTED | `ai-collab-frontend/src/modules/agent/use-approval-inbox.ts` |
| 终态 + PENDING 才启动轮询 | TESTED(UNIT) | `use-approval-inbox.test.ts` |
| 后台标签暂停 / focus 立即刷新 | TESTED(UNIT) | `use-approval-inbox.test.ts`；真实浏览器背景态见下方 `NOT_RUN` |
| 操作后立即刷新 | IMPLEMENTED | `use-agent-workspace.ts` 的 approve/reject 路径调用 `refreshNow(true)` |
| 卸载清理 timer 与 listener | TESTED(UNIT) | `use-approval-inbox.test.ts` |
| 304 不重复渲染 | TESTED(REAL_PROCESS) | 连续 304 后页面保持原状态，200 后才出现“已批准” |
| Run SSE 终态关闭行为不变 | IMPLEMENTED | 未修改 `AgentEventStreamService` |

## 门禁原始结果

| 门禁 | 结果 | 原始输出 |
| --- | --- | --- |
| 后端编译 | exit=0 | `raw/backend-compile.txt` |
| 后端定向单测 + Testcontainers | exit=0；16 tests，0 failures / 0 errors | `raw/surefire-*.txt` |
| 前端 lint | exit=0；0 errors，70 个既有 warning | `raw/frontend-lint.txt` |
| 前端 typecheck | exit=0 | `raw/frontend-typecheck.txt`、`raw/frontend-gates.txt` |
| 前端全量测试 | exit=0；66 files / 501 tests | `raw/frontend-test.txt` |
| 前端 build | exit=0；仅有既有 chunk >500 kB warning | `raw/frontend-build.txt` |

## 真实进程证据

隔离环境：

- PostgreSQL 独立库：`ai_collab_lh_c`（本机 `5433`）
- 后端：`18084`，管理健康检查 `18086`，健康状态 `UP`
- 前端：`15173`
- 浏览器：BSK / Edge，session `tkzz`、`huel`、`ytvg`

接口实测：

1. 登录成功（未记录凭据）。
2. `GET /api/v1/projects/{id}/agent/approvals?status=PENDING` 返回 200、1 条记录、ETag 与
   `maxUpdatedAt`。
3. 使用同一 ETag 再请求返回 304，响应体长度为 0。
4. A 标签页终态 Run 有 PENDING 时持续轮询；Resource Timing 间隔约 **5.027 秒**，
   未变化响应为 304。
5. B 标签页执行前端 `agentApi.approve`，返回 `status=200`、`itemStatus=APPROVED`。
6. A 标签页在下一次增量响应得到 200 后移出待审批，显示“已批准”；
   `document.visibilityState=visible`、`document.hasFocus=true`。
7. 独立计时轮：审批请求发出时间 `2026-11-13T17:21:11.3436817Z`，A 收到变化响应时间
   `2026-11-13T17:21:12.541Z`，间隔 **1.197 秒**。

截图：

- `screenshots/browser-a-pending.png`：A 显示“待审批（1）”。
- `screenshots/browser-a-approved.png`：A 显示“已处理提案（1）”“已批准”。

## 限制与未运行项

| 项 | 状态 | 说明 |
| --- | --- | --- |
| BSK 真实后台标签暂停 | NOT_RUN(REAL_PROCESS) | BSK Agent Window 中非活动标签仍报告 `document.visibilityState=visible`，不能构造标准后台标签条件；暂停/恢复分支由 `use-approval-inbox.test.ts` 覆盖 |
| BSK 真实 focus 立即刷新 | NOT_RUN(REAL_PROCESS) | 同上；focus/visibilitychange 分支由单元测试覆盖 |
| 双标签人工 UI 点击审批全程计时 | TESTED(REAL_PROCESS) | B 用前端 `agentApi.approve` 执行，A 页面真实渲染；首轮 UI 点击审批也已人工走通，但原始网络计时采用后续可重复的 `agentApi.approve` 轮 |

## 原始证据索引

- `raw/approval-resource-timings.json`：12 次收件箱请求的 Resource Timing。
- `raw/polling-before-approval.json`：轮询基线。
- `raw/polling-after-approval.json`：304 → 200 的变化响应。
- `raw/approve-t0-utc.txt`：独立计时轮 T0。
- `raw/browser-b-approve-utc.txt`：B 标签页审批 T0。
- `raw/frontend-*.txt`：前端四门禁输出。
- `raw/surefire-*.txt`：后端定向测试报告。
- `screenshots/*.png`：A 标签页待审批/已批准证据。

## 清理

- 浏览器验收完成后停止本次创建的 BSK session；不关闭用户窗口。
- 停止 lane C 临时后端/前端进程，保留原始证据。
- 删除临时 hardlink `.env`，不修改仓库根 `.env`。
