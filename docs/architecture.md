# AI Collab 系统架构

## 1. 架构概览

AI Collab 采用模块化单体架构，后端为 Spring Boot 模块化单体，前端为 Vue 3 SPA，通过 REST API 和 SSE 通信。

```
┌─────────────────────────────────────────────────────────────┐
│                      Vue 3 Frontend                         │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Backend                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                     API Layer                        │   │
│  │   Controller → DTO → Validation → Response           │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                 Application Layer                    │   │
│  │   Application Service → View / Command               │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                   Domain Layer                       │   │
│  │   Domain Model → Policy → Repository Interface       │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                Infrastructure Layer                  │   │
│  │   Mapper → Entity → External Service Adapter         │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────┬───────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
   │ PostgreSQL  │ │    Redis    │ │    MinIO    │
   └─────────────┘ └─────────────┘ └─────────────┘
```

## 2. 后端模块

| 模块 | 职责 | 主要 Controller |
|---|---|---|
| `auth` | 认证、JWT、Refresh Token | `AuthController` |
| `user` | 用户管理、系统管理员 | `UserController`, `AdminController` |
| `project` | 项目、成员、邀请、审计 | `ProjectController`, `InvitationController`, `AuditLogController` |
| `work` | 任务、里程碑、依赖、可视化 | `TaskController`, `MilestoneController`, `WorkVisualizationController` |
| `document` | 文档上传、解析、向量化 | `DocumentController` |
| `knowledge` | RAG 问答、SSE 流式输出 | `KnowledgeController` |
| `planning` | AI 任务规划生成、确认 | `TaskPlanController` |
| `agent` | 协作 Agent、Tool Calling、MCP | `AgentSessionController`, `ProjectMcpController` |
| `notification` | 站内通知 | `NotificationController` |
| `common` | 公共组件、异常处理 | - |
| `infrastructure` | AI 网关、存储适配 | `ProjectModelController`, `ProjectEmbeddingController` |

### 调用链路

```
Controller (HTTP)
    ↓
Application Service (业务逻辑、权限、事务)
    ↓
Domain Policy (纯业务规则)
    ↓
Repository Interface
    ↓
Mapper / Gateway (数据访问、外部服务)
    ↓
PostgreSQL / Redis / MinIO / AI Model
```

Controller 不直接访问 Mapper。Application Service 是事务和权限边界。

## 3. 认证与授权

### JWT 认证

- Access Token：短期有效，JWT 格式
- Refresh Token：HttpOnly Cookie，轮换式
- 登录/注册强制校验 Origin/Referer

### 项目角色

| 角色 | 权限 |
|---|---|
| OWNER | 完全控制：修改、删除项目，管理成员，管理 AI |
| ADMIN | 管理成员、任务、文档、AI 规划 |
| MEMBER | 查看、使用知识问答、管理自己任务 |

所有项目资源后端校验 `projectId + userId`。

## 4. 任务与依赖

### 任务状态

```
TODO → IN_PROGRESS → DONE
        ↓
      BLOCKED
        ↓
      CANCELED
```

### 依赖管理

- 支持任务间依赖关系
- Kahn 拓扑排序环检测
- 跨项目依赖校验
- 依赖替换原子操作

## 5. 文档处理流水线

```
Upload (校验类型、大小)
    ↓
MinIO 存储原文件
    ↓
Tika 提取文本
    ↓
清洗、分块 (按标题、段落)
    ↓
Embedding (向量化)
    ↓
pgvector 存储
    ↓
状态: READY
```

状态流转：`UPLOADED → PARSING → INDEXING → READY / FAILED`

## 6. RAG 问答流程

```
用户问题
    ↓
项目成员权限校验
    ↓
问题向量化 (Embedding)
    ↓
pgvector 相似度检索 (Top K)
    ↓
阈值过滤、去重、来源预算
    ↓
LLM 生成回答 (流式输出)
    ↓
引用验证、不足证据处理
    ↓
保存消息和引用 (短事务)
    ↓
SSE 推送 token/引用/完成事件
```

### 检索配置

- 仅检索当前项目 READY 文档
- 按 provider/model/dimension 过滤
- 默认 Top K 由配置控制

## 7. AI 任务规划

### 两阶段生成

1. **骨架生成**：基于需求生成任务列表结构
2. **细节填充**：为每个任务补充描述、依赖、工时

### 流程

```
用户需求 + 参考文档
    ↓
LLM 生成结构化输出 (JSON)
    ↓
Schema 校验
    ↓
业务校验 (日期、依赖、工时)
    ↓
保存不可变版本
    ↓
前端预览、人工编辑
    ↓
用户确认 (幂等键)
    ↓
事务创建里程碑、任务、依赖
```

## 8. Agent 系统

### 架构

```
AgentSessionController
    ↓
AgentRuntimeCoordinator
    ↓
AgentPromptFactory (系统提示、Skill 选择)
    ↓
Model Turn Gateway (原生 Tool Calling)
    ↓
Tool Registry / Approval Service
    ↓
AgentRepository (持久化步骤、事件)
    ↓
AgentEventStreamService (SSE 推送)
```

### 核心组件

| 组件 | 职责 |
|---|---|
| AgentRuntimeCoordinator | 运行时协调、循环控制 |
| NativeToolCallingExecutor | 原生 Tool Calling 执行 |
| AgentApprovalService | 写操作审批 |
| AgentMemoryService | 项目记忆管理 |
| McpAgentToolProvider | MCP 工具提供 |

### 6 个固定 Skill

| Skill | 功能 |
|---|---|
| ProjectHealth | 项目健康分析 |
| ProjectResearch | 项目研究 |
| WeeklyReport | 周报生成 |
| DeliveryReadiness | 交付就绪检查 |
| IterationPlanning | 迭代规划 |
| MeetingToTasks | 会议转任务 |

### MCP 集成

- 仅支持只读工具
- 双层白名单：连接级 + 项目级
- HTTPS + DNS 私网阻断
- Schema Hash 校验
- 不可信结果清洗

## 9. 存储设计

### PostgreSQL

- 主业务数据库
- pgvector 扩展用于向量存储
- Flyway 管理迁移 (V1-V38)

### Redis

- 限流计数器
- 辅助状态缓存
- 可降级为进程内实现

### MinIO

- 文档原文件存储
- 预签名下载
- 兼容 S3 API

## 10. AI 模型网关

### 统一抽象

```
RoutingChatModelGateway
    ↓
├── OpenAiCompatibleModelAdapter
├── AnthropicTurnContract
└── GeminiTurnContract
```

### 多用途路由

| 用途 | 配置前缀 |
|---|---|
| 知识问答 | `chat.*` |
| 任务规划 | `planning.*` |
| Agent | `agent.*` |

每个项目可独立配置模型和 API Key。

## 11. 前端架构

### 路由

| 路径 | 页面 |
|---|---|
| `/login` | 登录 |
| `/projects` | 项目列表 |
| `/projects/:id/dashboard` | 项目概览 |
| `/projects/:id/board` | 任务看板 |
| `/projects/:id/gantt` | 甘特图 |
| `/projects/:id/documents` | 文档管理 |
| `/projects/:id/knowledge` | 知识问答 |
| `/projects/:id/ai-planning` | AI 规划 |
| `/projects/:id/agent` | 协作 Agent |
| `/admin` | 系统管理 |

### 状态管理

- `auth-store`：认证状态
- `project-context-store`：当前项目和角色

### API 客户端

- Axios 封装
- 统一 `ApiResult<T>` 响应
- 错误映射

## 12. API 设计

### 统一响应

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {}
}
```

### 错误响应

```json
{
  "code": "ERROR_CODE",
  "message": "中文错误描述"
}
```

### 路径规范

- 项目资源：`/api/v1/projects/{projectId}/...`
- 全局资源：`/api/v1/...`

详细 API 契约见 [api/openapi.yaml](api/openapi.yaml)。

## 13. 安全设计

- 密码、Token、API Key 不进入日志
- MCP 凭据加密存储
- 文档内容不注入系统权限
- 项目资源强制隔离
- SQL 参数化查询
