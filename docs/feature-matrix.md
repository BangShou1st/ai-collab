# 现有功能矩阵

更新日期：2026-07-29。本文档只描述当前代码事实，是判断“已完成、部分完成、未实现”的首要入口；产品目标和后续设计不能替代本矩阵中的实现状态。

状态定义：

- **完整**：后端业务、权限校验和前端入口均已存在，并能通过自动化测试或本地冒烟检查。
- **部分**：已有可用闭环，但字段、交互或目标能力仍有明确缺口。
- **仅后端/仅配置**：代码已经具备一侧能力，尚未形成完整用户闭环。
- **未实现**：本轮不补建，进入后续路线图。

## 用户、权限与项目

| 能力 | 状态 | 当前实现与边界 | 主要代码证据 |
|---|---|---|---|
| 登录、JWT、刷新、当前用户、退出 | 完整 | Access Token + HttpOnly Refresh Cookie；支持当前设备和全部设备退出 | `auth/AuthController`、`auth-store.ts`、`LoginView.vue` |
| 公开注册关闭 | 完整 | 默认配置关闭；注册接口仍由策略开关保护，便于未来启用 | `application.yml`、`application-local.yml`、`PublicRegistrationService` |
| 管理员创建测试账号 | 未实现 | 当前测试账号由启动初始化或邀请流程产生 | — |
| 邀请成员 | 部分 | OWNER/ADMIN 可创建邀请码；支持当前用户接受，也支持受邀者按链接创建账号；不是“按用户名直接加入” | `InvitationApplicationService`、`InvitationAcceptView.vue` |
| 成员列表、角色修改、移除 | 完整 | OWNER 管理角色和移除；不能移除 OWNER | `ProjectMemberApplicationService`、`ProjectMembersView.vue` |
| 成员主动退出项目 | 未实现 | 没有 self-leave 接口和前端入口 | — |
| 所有权转移 | 未实现 | 数据库保证单 OWNER，但没有转移业务接口 | — |
| 三级项目权限 | 完整 | 所有项目子资源从后端校验成员身份；管理操作按 OWNER/ADMIN/负责人限制 | `DatabaseProjectAccessGuard`、`WorkPermissionPolicy` 及各 Application Service |
| 项目创建、查看、修改、删除 | 完整 | OWNER 可在项目列表编辑和删除；更新携带乐观锁版本 | `ProjectController`、`ProjectApplicationService`、`ProjectListView.vue` |
| 项目状态 | 部分 | 当前只有 `ACTIVE`、`ARCHIVED`，中文显示为“进行中、已归档” | `ProjectStatus`、`ProjectListView.vue` |
| 项目类型 | 未实现 | 没有类型字段、迁移、接口或界面 | — |

## 协作功能

| 能力 | 状态 | 当前实现与边界 | 主要代码证据 |
|---|---|---|---|
| 项目概览 | 完整 | 项目和成员数、各状态任务数、完成率、逾期数、里程碑进度、最近任务/文档/动态；成员可访问且按项目隔离 | `ProjectDashboardQueryService`、`DashboardView.vue` |
| 里程碑管理 | 部分 | 创建、编辑、删除、状态、描述、目标日期、排序和任务完成进度已实现；没有独立开始日期与截止日期，只有目标日期 | `MilestoneApplicationService`、`MilestoneView.vue` |
| 任务管理 | 完整 | 标题、描述、里程碑、负责人、状态、优先级、工时、起止日期、依赖、乐观锁、增删改查 | `TaskApplicationService`、`TaskBoardView.vue` |
| 任务权限 | 完整 | OWNER/ADMIN 管理任务；MEMBER 只能变更自己负责任务的状态 | `WorkPermissionPolicy`、`TaskBoardView.vue` |
| 任务筛选与“我的任务” | 完整 | 负责人、里程碑由后端筛选，优先级由当前结果集前端筛选；支持一键查看自己任务 | `TaskController`、`TaskMapper`、`TaskBoardView.vue` |
| 任务看板 | 完整 | 五列状态展示、负责人/优先级/截止日期、详情、状态变更；第一版没有拖拽排序 | `TaskBoardView.vue`、`TaskBoardCard.vue` |
| 前置依赖 | 完整 | 支持替换依赖、跨项目校验、环检测和阻塞状态提示 | `TaskDependencyPolicy`、`TaskApplicationService`、`TaskDetailDrawer.vue` |
| 任务评论 | 完整 | 新增、查看、作者编辑/删除、管理员删除；单层纯文本 | `TaskCommentApplicationService`、`TaskDetailDrawer.vue` |
| 操作日志 | 完整 | OWNER/ADMIN 分页查看；中文摘要；高价值操作保存脱敏结构化 detail；成员不可访问 | `AuditService`、`AuditLogQueryService`、`AuditLogView.vue` |

## 文档与 AI

| 能力 | 状态 | 当前实现与边界 | 主要代码证据 |
|---|---|---|---|
| 文档上传、列表、下载、删除、重试 | 完整 | PDF、DOCX、Markdown、TXT；20MB；MinIO 原文件；预签名下载；失败可重试 | `DocumentController`、`DocumentView.vue` |
| 文档解析与向量化 | 完整 | Tika 提取、清洗分块、Embedding、pgvector、处理状态和失败信息；删除时清理文件/记录/分块/向量 | `DocumentProcessingService`、V6/V7/V9 迁移 |
| 项目知识问答 | 完整 | 仅检索当前项目 READY 文档；保存会话、消息和引用；证据不足明确拒答 | `KnowledgeQuestionApplicationService`、`KnowledgeView.vue` |
| 问答流式输出 | 未实现 | 当前等待完整回答后一次返回 | — |
| 回答有用/无用反馈 | 未实现 | 没有反馈表、接口和界面 | — |
| 多模型切换 | 仅配置 | Chat、Embedding、Planning 使用独立 OpenAI-compatible 配置；通过环境变量手动切换，没有管理界面和额度自动检测 | `application.yml`、各 AI Gateway |
| AI 任务规划生成 | 完整 | 两阶段结构化生成、Schema/业务校验、失败修复、来源追踪和权限校验 | `planning` 模块、`PlanningView.vue` |
| 规划预览与人工确认 | 完整 | 人工编辑、删除/补充、负责人/日期/优先级/依赖调整、事务确认、幂等和审计 | `TaskPlanCommandService`、`TaskPlanConfirmationService` |
| 规划版本记录 | 完整 | 保存不可变版本、模型 attempt、原始输出/指标、恢复、确认结果和事件 | `ai_task_plan*` 表、规划查询与版本接口 |
| 通用 Agent 运行时 | 未实现 | 当前 AI 是受控 RAG 与规划工作流，不具备自主循环、工具注册/选择、状态机、暂停恢复或通用记忆 | 见 `docs/development/agent-design.md`（后续设计） |

## 工程能力

| 能力 | 状态 | 当前实现与边界 | 主要证据 |
|---|---|---|---|
| 统一响应、异常、参数校验 | 完整 | JSON API 使用 `code/message/data`；204 和文件响应按 HTTP 语义返回 | `ApiResponse`、`GlobalExceptionHandler` |
| 事务、幂等、乐观锁 | 完整 | 关键写操作使用短事务；规划确认有幂等键；项目/任务/里程碑使用版本冲突控制 | 应用服务与 Mapper |
| 分页 | 部分 | 审计和 AI 规划使用分页；普通列表按第一版规模返回数组 | 对应 Controller/Query Service |
| 日志与隐私 | 完整 | 敏感 Mapper 降低日志级别；API Key 仅从环境变量读取；前端错误数据脱敏 | `application-local.yml`、`api-result.ts` |
| 健康检查与本地基础设施 | 完整 | Actuator health；Docker Compose 启动 PostgreSQL/pgvector、Redis、MinIO | `/actuator/health`、`ai-collab-deploy/docker-compose.yml` |
| 数据库迁移 | 完整 | Flyway 当前最新版本为 V12 | `src/main/resources/db/migration` |
| OpenAPI | 部分 | 已覆盖现有主要接口并校正 Phase 09 契约；仍为手工维护，尚无契约自动差异检查 | `docs/api/openapi.yaml` |
| 自动化验证 | 完整 | 后端集成/单元测试、前端测试/类型检查/构建、真实只读冒烟脚本 | `mvnw test`、`pnpm test`、`scripts/smoke-existing.ps1` |

## 明确留待后续的能力

以下能力当前没有完整实现，本轮稳定化不新增：项目类型、四态项目生命周期、成员主动退出、所有权转移、管理员账号管理、流式问答、回答反馈、通知、AI 周报、甘特图、自动模型额度切换和通用 Agent 运行时。开发顺序以 `docs/FUTURE_ROADMAP.md` 为准。
