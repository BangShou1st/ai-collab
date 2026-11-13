# Agent 审批收件箱 API

## 列表与增量轮询

`GET /api/v1/projects/{projectId}/agent/approvals`

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 否 | `PENDING` / `APPROVED` / `REJECTED` / `EXPIRED` / `CONFLICTED` |
| `updatedSince` | 否 | ISO-8601 时间；只返回 `updated_at` 严格晚于该时间的记录 |
| `If-None-Match` | 否 | 上一次响应返回的 ETag |

成功响应：

```json
{
  "code": "SUCCESS",
  "message": "success",
  "data": {
    "items": [],
    "serverTime": "2026-11-14T01:00:05Z",
    "maxUpdatedAt": "2026-11-14T01:00:05Z"
  }
}
```

- `maxUpdatedAt` 只统计本次响应中实际返回且当前用户可见的记录。
- ETag 基于筛选条件、`maxUpdatedAt`、记录 ID/version/revision 计算；无变化时返回
  `304 Not Modified` 且无响应体。
- 审批的 `APPROVED` / `REJECTED` / `EXPIRED` 状态跃迁会同步推进 `updated_at`，
  因而可被增量轮询发现。
- 项目成员权限在应用服务层强制校验；无权限项目不可读取收件箱。

## 前端轮询

`useApprovalInbox` 仅在 Run 已进入终态且本地仍存在 `PENDING` 审批时启动 5 秒轮询。
轮询携带 `updatedSince` 与 ETag；后台标签（`document.visibilityState=hidden`）暂停，
`visibilitychange` / `focus` 恢复时立即刷新一次，组件作用域销毁时清理 timer 与监听器。
