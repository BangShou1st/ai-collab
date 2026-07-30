# 现有功能矩阵

更新日期：2026-07-30。本文档只描述当前代码事实，是判断“已完成、部分完成、未实现”的首要入口；产品目标和后续设计不能替代本矩阵中的实现状态。

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
| 系统管理中心 | 完整 | 仅系统管理员显示和访问；支持账号创建/启停、模型配置增删改测，以及知识问答、协作 Agent、任务规划的模型用途分配 | `AdminView.vue`、`AdminController`、`AdminModelController`、V20/V24 迁移 |
| 邀请成员 | 部分 | OWNER/ADMIN 可创建邀请码；支持当前用户接受，也支持受邀者按链接创建账号；不是“按用户名直接加入” | `InvitationApplicationService`、`InvitationAcceptView.vue` |
| 成员列表、角色修改、移除 | 完整 | OWNER 管理角色和移除；不能移除 OWNER | `ProjectMemberApplicationService`、`ProjectMembersView.vue` |
| 成员主动退出项目 | 完整 | 非 OWNER 成员可主动退出项目；OWNER 需先转移所有权 | `ProjectMemberApplicationService.leave`、`ProjectController`、`ProjectMembersView.vue` |
| 所有权转移 | 完整 | 使用事务和行锁确保原子转移；旧 OWNER 降级为 MEMBER，新 OWNER 升级 | `ProjectMemberApplicationService.transferOwnership`、`ProjectController`、`ProjectMembersView.vue` |
| 三级项目权限 | 完整 | 所有项目子资源从后端校验成员身份；管理操作按 OWNER/ADMIN/负责人限制 | `DatabaseProjectAccessGuard`、`WorkPermissionPolicy` 及各 Application Service |
| 项目创建、查看、修改、删除 | 完整 | OWNER 可在项目列表编辑和删除；更新携带乐观锁版本 | `ProjectController`、`ProjectApplicationService`、`ProjectListView.vue` |
| 项目状态 | 完整 | 四态状态（准备中/进行中/已完成/已归档）及合法转换规则；准备中和进行中可修改文档，已完成和已归档只读 | `ProjectStatus`、`ProjectStatusTransitionPolicy`、`ProjectWritePolicy`、`ProjectListView.vue`、V14 迁移 |
| 项目类型 | 完整 | 支持竞赛项目、课程设计、软件实训、其他；历史数据回填为"其他" | `ProjectType`、`ProjectListView.vue`、V13 迁移 |

## 协作功能

| 能力 | 状态 | 当前实现与边界 | 主要代码证据 |
|---|---|---|---|
| 项目概览 | 完整 | 项目和成员数、各状态任务数、完成率、逾期数、里程碑进度、最近任务/文档/动态；成员可访问且按项目隔离 | `ProjectDashboardQueryService`、`DashboardView.vue` |
| 里程碑管理 | 完整 | 创建、编辑、删除、状态、描述、开始日期、截止日期、目标日期、排序和任务完成进度 | `MilestoneApplicationService`、`MilestoneView.vue`、V15 迁移 |
| 任务管理 | 完整 | 标题、描述、里程碑、负责人、状态、优先级、工时、起止日期、依赖、乐观锁、增删改查 | `TaskApplicationService`、`TaskBoardView.vue` |
| 任务权限 | 完整 | OWNER/ADMIN 管理任务；MEMBER 只能变更自己负责任务的状态 | `WorkPermissionPolicy`、`TaskBoardView.vue` |
| 任务筛选与“我的任务” | 完整 | 负责人、里程碑由后端筛选，优先级由当前结果集前端筛选；支持一键查看自己任务 | `TaskController`、`TaskMapper`、`TaskBoardView.vue` |
| 任务看板与批量操作 | 完整 | 五列展示和跨列拖拽；管理员可批量变更状态、优先级和负责人，写入使用逐任务乐观锁并在一个事务中提交 | `TaskApplicationService.batchUpdate`、`TaskBoardView.vue` |
| 前置依赖 | 完整 | 支持替换依赖、跨项目校验、环检测和阻塞状态提示 | `TaskDependencyPolicy`、`TaskApplicationService`、`TaskDetailDrawer.vue` |
| 任务评论 | 完整 | 新增、查看、作者编辑/删除、管理员删除；单层纯文本 | `TaskCommentApplicationService`、`TaskDetailDrawer.vue` |
| 操作日志 | 完整 | OWNER/ADMIN 分页查看；中文摘要；高价值操作保存脱敏结构化 detail；成员不可访问 | `AuditService`、`AuditLogQueryService`、`AuditLogView.vue` |
| 站内通知 | 完整 | 任务分配/状态/依赖、截止与逾期、文档处理、规划确认通知；只读自己的通知，截止提醒按日幂等 | `NotificationApplicationService`、`NotificationReminderJob`、`NotificationView.vue` |
| 甘特图、依赖图、日历、成员负载 | 完整 | 成员可从项目导航进入；所有查询先校验项目成员并显式限定 `project_id` | `WorkVisualizationService`、`modules/work/*View.vue` |
| 项目周报与风险分析 | 完整 | 基于数据库事实计算完成率、逾期、阻塞、未分配和里程碑风险，不让模型计算业务指标 | `WorkReportService`、`WeeklyReportView.vue`、`RiskAnalysisView.vue` |
| 规划与正式任务对比 | 完整 | 页面直接选择规划；按最新版本 `tasks_json` 和正式任务 `source_plan_task_key` 对齐，返回缺失、新增和字段差异 | `WorkReportService.getPlanComparison`、`PlanComparisonView.vue` |

## 文档与 AI

| 能力 | 状态 | 当前实现与边界 | 主要代码证据 |
|---|---|---|---|
| 文档上传、列表、下载、删除、重试 | 完整 | PDF、DOCX、Markdown、TXT；20MB；MinIO 原文件；预签名下载；失败可重试；支持版本跟踪和重新索引；新建 PREPARING 项目可直接上传，COMPLETED/ARCHIVED 项目写操作返回 `PROJECT_READ_ONLY` | `DocumentController`、`DocumentView.vue`、`ProjectWriteGuard` |
| 文档解析与向量化 | 完整 | Tika 提取、清洗分块、Embedding、pgvector、处理状态和失败信息；删除时清理文件/记录/分块/向量 | `DocumentProcessingService`、V6/V7/V9 迁移 |
| 项目知识问答 | 完整 | 仅检索当前项目 READY 文档；保存会话、消息和引用；证据不足明确拒答；引用包含 PDF 页码定位；支持中文表单配置检索评测 | `KnowledgeQuestionApplicationService`、`KnowledgeView.vue`、`KnowledgeEvalView.vue` |
| 问答流式输出 | 完整 | SSE 流式推送 token/引用/完成信号；前端兼容分片、CRLF 与末尾缓冲并实时渲染；支持 AbortController 取消 | `KnowledgeController.askStream`、`KnowledgeStreamQuestionService`、`knowledge-api.ts` |
| 回答有用/无用反馈 | 完整 | knowledge_feedback 表；提交/撤销/查询端点；前端助手消息下方 👍/👎 按钮和统计 | `KnowledgeFeedbackService`、`KnowledgeController`、`KnowledgeView.vue` |
| 多模型配置与用途分配 | 完整 | 管理中心支持 OpenAI 兼容、Claude、Gemini 配置、连接测试和用途分配；API Key 加密保存；Embedding 仍由独立环境配置控制 | `AdminView.vue`、`ModelConfigurationService`、`RoutingChatModelGateway` |
| AI 任务规划生成 | 完整 | 两阶段结构化生成、Schema/业务校验、失败修复、来源追踪和权限校验 | `planning` 模块、`PlanningView.vue` |
| 规划预览与人工确认 | 完整 | 人工编辑、删除/补充、负责人/日期/优先级/依赖调整、事务确认、幂等和审计 | `TaskPlanCommandService`、`TaskPlanConfirmationService` |
| 规划版本记录 | 完整 | 保存不可变版本、模型 attempt、原始输出/指标、恢复、确认结果和事件 | `ai_task_plan*` 表、规划查询与版本接口 |
| 项目协作 Agent | 完整 | 持久化会话/运行/步骤，支持重命名删除、只读分析工具、人工审批写工具、预算、重试、定时运行和中文审批摘要；不提供无实际业务作用的固定样例评估页 | `AgentRunService`、`AgentWorker`、`AgentApprovalService`、`AgentView.vue`、V22/V23/V25 |

## 工程能力

| 能力 | 状态 | 当前实现与边界 | 主要证据 |
|---|---|---|---|
| 统一响应、异常、参数校验 | 完整 | JSON API 使用 `code/message/data`；204 和文件响应按 HTTP 语义返回 | `ApiResponse`、`GlobalExceptionHandler` |
| 事务、幂等、乐观锁 | 完整 | 关键写操作使用短事务；规划确认有幂等键；项目/任务/里程碑使用版本冲突控制 | 应用服务与 Mapper |
| 分页 | 部分 | 审计和 AI 规划使用分页；普通列表按第一版规模返回数组 | 对应 Controller/Query Service |
| 日志与隐私 | 完整 | 敏感 Mapper 降低日志级别；API Key 仅从环境变量读取；前端错误数据脱敏 | `application-local.yml`、`api-result.ts` |
| 健康检查与本地基础设施 | 完整 | Actuator health；Docker Compose 启动 PostgreSQL/pgvector、Redis、MinIO | `/actuator/health`、`ai-collab-deploy/docker-compose.yml` |
| 数据库迁移 | 完整 | Flyway 当前最新版本为 V26，当前业务结构为 36 张表 | `src/main/resources/db/migration` |
| OpenAPI | 完整 | 已覆盖所有主要接口；提供校验脚本和契约测试确保同步 | `docs/api/openapi.yaml`、`scripts/validate-openapi.ps1`、`OpenApiContractTest` |
| 自动化验证 | 完整 | 后端集成/单元测试、前端测试/类型检查/构建、真实只读冒烟脚本 | `mvnw test`、`pnpm test`、`scripts/smoke-existing.ps1` |

## 明确留待后续的能力

以下能力当前没有完整实现：自动模型额度检测/切换、跨项目 Agent、多 Agent 编排和通用长期记忆。
