# REST API 规范

机器可读版本见 `api/openapi.yaml`。

## 1. 通用约定

- Base URL：`http://localhost:8080/api/v1`
- JSON：`application/json; charset=utf-8`
- 文件上传：`multipart/form-data`
- Bearer Token：`Authorization: Bearer <access-token>`
- UUID：标准字符串格式
- 分页参数：`page` 从 1 开始，`size` 默认 20、最大 100

成功响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "requestId": "e13ad8a0-3789-4ef8-a725-cc0f987cb748",
  "timestamp": "2026-07-18T08:30:00Z"
}
```

失败响应：

```json
{
  "code": "TASK_DEPENDENCY_CYCLE",
  "message": "任务依赖形成环",
  "data": null,
  "requestId": "e13ad8a0-3789-4ef8-a725-cc0f987cb748",
  "timestamp": "2026-07-18T08:30:00Z"
}
```

## 2. 认证与邀请

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| POST | `/auth/login` | 登录 | 公开 |
| POST | `/auth/refresh` | 刷新 Token | 公开 |
| POST | `/auth/logout` | 撤销 Refresh Token | 登录 |
| GET | `/auth/me` | 当前用户 | 登录 |
| GET | `/invitations/{code}` | 邀请预览 | 公开 |
| POST | `/invitations/{code}/accept` | 接受邀请并创建账号 | 公开 |

登录请求：

```json
{
  "username": "demo_owner",
  "password": "DemoPass123!"
}
```

## 3. 项目与成员

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| POST | `/projects` | 创建项目 | 登录 |
| GET | `/projects` | 我的项目 | 登录 |
| GET | `/projects/{projectId}` | 项目详情 | MEMBER+ |
| PATCH | `/projects/{projectId}` | 修改项目 | ADMIN+ |
| DELETE | `/projects/{projectId}` | 删除项目 | OWNER |
| GET | `/projects/{projectId}/members` | 成员列表 | MEMBER+ |
| POST | `/projects/{projectId}/invitations` | 创建邀请 | ADMIN+ |
| PATCH | `/projects/{projectId}/members/{userId}/role` | 修改角色 | OWNER |
| DELETE | `/projects/{projectId}/members/{userId}` | 移除成员 | OWNER |

创建项目：

```json
{
  "name": "2026 AI Competition",
  "description": "高校 AI 应用比赛项目",
  "startDate": "2026-07-20",
  "dueDate": "2026-09-15"
}
```

## 4. 里程碑与任务

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| GET | `/projects/{projectId}/milestones` | 列表 | MEMBER+ |
| POST | `/projects/{projectId}/milestones` | 创建 | ADMIN+ |
| PATCH | `/projects/{projectId}/milestones/{milestoneId}` | 修改 | ADMIN+ |
| DELETE | `/projects/{projectId}/milestones/{milestoneId}` | 删除 | ADMIN+ |
| GET | `/projects/{projectId}/tasks` | 任务列表/看板 | MEMBER+ |
| POST | `/projects/{projectId}/tasks` | 创建任务 | ADMIN+ |
| GET | `/projects/{projectId}/tasks/{taskId}` | 任务详情 | MEMBER+ |
| PATCH | `/projects/{projectId}/tasks/{taskId}` | 修改任务 | ADMIN+；负责人可改状态 |
| DELETE | `/projects/{projectId}/tasks/{taskId}` | 删除任务 | ADMIN+ |
| PUT | `/projects/{projectId}/tasks/{taskId}/dependencies` | 替换依赖 | ADMIN+ |
| GET | `/projects/{projectId}/tasks/{taskId}/comments` | 评论列表 | MEMBER+ |
| POST | `/projects/{projectId}/tasks/{taskId}/comments` | 新增评论 | MEMBER+ |
| PATCH | `/projects/{projectId}/tasks/{taskId}/comments/{commentId}` | 编辑自己的评论 | 作者 |
| DELETE | `/projects/{projectId}/tasks/{taskId}/comments/{commentId}` | 删除评论 | 作者或 ADMIN+ |

创建任务：

```json
{
  "title": "完成知识库上传流程",
  "description": "实现 PDF、DOCX、MD、TXT 上传和状态展示",
  "milestoneId": "3cffc249-ce49-4949-935b-e20a4cbd9271",
  "assigneeId": "14fbfbbf-42a4-460f-a4c3-79e275519ebd",
  "priority": "HIGH",
  "estimateHours": 12,
  "startDate": "2026-07-25",
  "dueDate": "2026-07-28",
  "dependencyIds": []
}
```

## 5. 文档

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| GET | `/projects/{projectId}/documents` | 文档列表 | MEMBER+ |
| POST | `/projects/{projectId}/documents` | 上传 | ADMIN+ |
| GET | `/projects/{projectId}/documents/{documentId}` | 详情 | MEMBER+ |
| GET | `/projects/{projectId}/documents/{documentId}/download-url` | 预签名下载 | MEMBER+ |
| POST | `/projects/{projectId}/documents/{documentId}/retry` | 重试解析 | ADMIN+ |
| DELETE | `/projects/{projectId}/documents/{documentId}` | 删除 | ADMIN+ |

上传响应使用 HTTP 202：

```json
{
  "code": "OK",
  "message": "accepted",
  "data": {
    "documentId": "ece47e47-1504-4aa3-a0fd-b212f414be38",
    "status": "UPLOADED"
  }
}
```

## 6. 知识库问答

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| POST | `/projects/{projectId}/knowledge/sessions` | 创建会话 | MEMBER+ |
| GET | `/projects/{projectId}/knowledge/sessions` | 会话列表 | 本人 |
| GET | `/projects/{projectId}/knowledge/sessions/{sessionId}` | 会话详情 | 本人 |
| POST | `/projects/{projectId}/knowledge/sessions/{sessionId}/questions` | 提问 | 本人 |
| DELETE | `/projects/{projectId}/knowledge/sessions/{sessionId}` | 删除会话 | 本人 |

提问请求：

```json
{
  "question": "比赛作品必须支持哪些终端？",
  "documentIds": [
    "ece47e47-1504-4aa3-a0fd-b212f414be38"
  ]
}
```

回答：

```json
{
  "answer": "根据赛题说明，作品需要运行在……",
  "insufficientEvidence": false,
  "model": "free-provider/model-name",
  "citations": [
    {
      "documentId": "ece47e47-1504-4aa3-a0fd-b212f414be38",
      "filename": "competition-rule.pdf",
      "chunkId": "7c24f0aa-a686-4bc5-a059-d38f6524bf71",
      "quote": "参赛作品应……",
      "similarity": 0.81
    }
  ]
}
```

## 7. AI 任务规划

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| POST | `/projects/{projectId}/ai/task-plans` | 发起生成，返回 202 | ADMIN+ |
| GET | `/projects/{projectId}/ai/task-plans` | 历史列表 | ADMIN+ |
| GET | `/projects/{projectId}/ai/task-plans/{planId}` | 草案详情 | ADMIN+ |
| PUT | `/projects/{projectId}/ai/task-plans/{planId}/draft` | 替换可编辑草案 | ADMIN+ |
| POST | `/projects/{projectId}/ai/task-plans/{planId}/confirm` | 确认并批量创建 | ADMIN+ |
| DELETE | `/projects/{projectId}/ai/task-plans/{planId}` | 删除未确认草案 | 创建者或 OWNER |

发起生成：

```json
{
  "goal": "在 14 天内完成可演示的第一版",
  "startDate": "2026-07-20",
  "dueDate": "2026-08-02",
  "maxTasks": 30,
  "documentIds": [
    "ece47e47-1504-4aa3-a0fd-b212f414be38"
  ],
  "constraints": [
    "由 3 名成员完成",
    "第 7 天必须有可运行版本"
  ]
}
```

确认请求必须携带：

```http
Idempotency-Key: 60ebea5f-93df-440f-9551-c606a7ce36ac
```

## 8. 概览与日志

| Method | Path | 说明 | 权限 |
|---|---|---|---|
| GET | `/projects/{projectId}/dashboard` | 概览 | MEMBER+ |
| GET | `/projects/{projectId}/audit-logs` | 审计日志 | ADMIN+ |

## 9. HTTP 状态码

| 状态码 | 使用场景 |
|---:|---|
| 200 | 查询、修改成功 |
| 201 | 同步创建成功 |
| 202 | 文档解析或 AI 生成已接受 |
| 204 | 删除成功且无响应体 |
| 400 | 参数或业务校验失败 |
| 401 | 未登录或 Token 失效 |
| 403 | 项目角色不足 |
| 404 | 资源不存在或不属于当前项目 |
| 409 | 幂等冲突、版本冲突、依赖环 |
| 413 | 文件过大 |
| 415 | 不支持的文件类型 |
| 429 | AI 调用频率超限 |
| 502 | 模型供应商不可用 |
| 504 | 模型调用超时 |
