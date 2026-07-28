# AI Collab — 项目上下文

> **每次 Claude 对话启动时读取此文件。**
> 本文档是 AI Collab 项目的权威上下文摘要。阅读后可直接开始工作，无需重新探索。

## 项目定位

AI Collab（高校竞赛 AI 项目协作平台）面向高校竞赛团队，同时兼容软件课程项目团队。系统提供项目协作 + RAG 知识问答 + AI 任务规划。

**目标用户**：蓝桥杯、计算机设计大赛、创新创业比赛等竞赛团队；软件工程课程设计、实训和小型团队项目。

**第一版定位**：仅供开发者本人及邀请的测试用户使用。

## 技术栈

- **后端**: Java 21, Spring Boot 4.1, MyBatis-Plus, PostgreSQL + pgvector, MinIO, Redis
- **前端**: Vue 3, TypeScript, Vite, Element Plus, Pinia, Axios
- **AI**: OpenAI-compatible Chat + Embedding API（供应商无关网关）
- **部署**: Docker Compose（PG/Redis/MinIO）+ 本地进程（后端/前端）

## 当前状态

- **Phase 08 完成**: 认证、项目成员、里程碑、任务依赖、文档知识库、RAG 问答、AI 任务规划全部闭环
- **未实现**: Dashboard 页面、审计日志页面、通知系统
- **待开发**: 见 `docs/FUTURE_ROADMAP.md`

---

## 第一版功能范围

### 一、用户与权限

**用户登录**
- 第一版不开放注册；使用预置或管理员创建的测试账号
- 登录后获取访问令牌（JWT Access Token 30 分钟）
- Refresh Token 通过 HttpOnly Cookie 传递，最长存活 14 天，会话绝对期限 30 天
- 查看当前用户信息；退出登录（支持当前设备退出和全部设备退出）

**项目成员管理**
- 创建项目（创建者自动成为 OWNER）
- 邀请已有用户加入项目（邀请码机制，SHA-256 摘要存储）
- 查看项目成员；修改成员角色；移除成员；成员退出项目
- 每个项目必须有且只有一个 OWNER（数据库局部唯一索引保证）

**三级权限**

| 角色 | 权限 |
|---|---|
| OWNER | 管理项目、成员和管理员；可删除项目；转移所有权 |
| ADMIN | 管理任务、里程碑、文档和 AI 任务规划；查看审计日志；邀请成员 |
| MEMBER | 查看项目；维护自己负责的任务；评论；使用知识库问答；查看规划 |

所有项目接口都检查用户是否属于该项目，AI 功能同样不能绕过权限。

### 二、项目管理

- 创建、修改和删除项目
- 设置项目名称、简介、项目类型（竞赛项目/课程设计/软件实训/其他）
- 设置项目开始时间、预计结束时间
- 查看自己参与的项目列表；查看项目详情；查看项目成员
- 设置项目当前状态（准备中/进行中/已完成/已归档）

### 三、项目概览

进入项目后展示整体情况：项目基本信息、成员数量、任务总数/已完成/进行中/逾期数、里程碑完成情况、最近更新的任务、最近上传的文档、最近项目操作。

第一版只做简单统计，不做复杂数据分析大屏。

### 四、里程碑管理

里程碑代表项目中的重要阶段（如"完成需求分析"、"提交比赛作品"）。

- 创建/修改/删除里程碑
- 设置标题、描述、开始日期、截止日期、状态
- 查看里程碑下的任务；统计完成进度

### 五、任务管理

**任务字段**：标题、详细描述、所属里程碑、负责人、创建人、优先级（LOW/MEDIUM/HIGH/URGENT）、任务状态、开始日期、截止日期、预计工作量、前置依赖任务、创建/更新时间。

**任务状态**：TODO → IN_PROGRESS → DONE，可取消为 CANCELED，可标记为 BLOCKED。

**具体功能**：创建/编辑/删除任务；分配负责人；修改状态和优先级；设置截止日期；关联里程碑；设置前置依赖；按状态/负责人/优先级筛选；查看自己负责的任务。

依赖关系必须是有向无环图（DAG），使用 Kahn 算法检测环。

### 六、任务看板

类似 Trello 的看板展示：待处理 → 进行中 → 已完成 → 已取消

- 按状态分列展示
- 查看任务负责人、优先级和截止日期
- 点击进入任务详情
- 修改任务状态（通过按钮或下拉框，第一版不做拖拽）
- 筛选负责人和里程碑
- 存在未完成前置任务时显示阻塞标记

### 七、任务评论

- 发表评论；查看评论列表
- 删除自己的评论；管理员删除不合适的评论
- 展示评论人和评论时间

第一版不做：富文本评论、回复嵌套、@成员通知、实时聊天。

### 八、操作日志

记录项目中的重要操作：创建项目、邀请成员、修改角色、创建/删除里程碑、创建/修改/删除任务、修改状态、上传/删除文档、确认 AI 规划。

日志包含：操作人、操作类型、操作对象、操作时间、变更摘要。所有文档事件使用 `PROJECT_DOCUMENT` entityType。

### 九、项目文档管理

支持上传：PDF、DOCX、Markdown、TXT（单文件最大 20MB，每个项目最多 100 个有效文档）。

- 上传文档（原文件保存到 MinIO，对象键由后端生成）
- 查看文档列表（文件名、类型、大小、上传人、时间、状态）
- 下载原始文档（5 分钟预签名 URL）
- 删除文档；查看文档处理状态；解析失败后重新处理

文档状态：UPLOADED → PARSING → INDEXING → READY / FAILED

### 十、文档解析与知识库构建

上传后自动完成：保存原文件 → 提取文本（Apache Tika） → 清理无效内容 → 按标题和段落分块（目标约 1200 字符/块，重叠约 150 字符） → 调用 Embedding 模型 → 将向量保存到 pgvector → 文档进入可检索状态。

删除文档时同时清理 MinIO 文件、文档记录、文本块和向量。

使用 CAS + processing_token 防 ABA 竞态，心跳超时 15 分钟自动标记失败。

### 十一、项目知识库问答

项目成员针对当前项目资料提问，流程：提问 → 校验成员权限 → 问题向量化 → 检索当前项目文档块（Top 8，相似度 ≥ 0.55，内容哈希去重，最多 5 个来源，总计不超过 8000 Unicode code point） → 调用 Chat Model 生成回答 → 返回回答和引用来源。

- 项目内知识问答；回答基于项目文档并提供出处
- Markdown 渲染（DOMPurify 清洗后才进入 v-html）
- 展示引用文档和引用片段
- 资料不足时明确拒答（insufficientEvidence=true）
- 保存问答历史；会话仅对创建者可见

System Prompt 约束模型只根据 SOURCES 回答，不可信数据 XML 转义。

### 十二、多模型切换

系统不绑定某一家大模型。支持配置：模型供应商、API 地址、API Key、聊天模型名称、Embedding 模型名称、超时时间等。

Chat Model 和 Embedding Model 独立配置，切换 Chat Model 不需要重建向量，切换 Embedding Model 需要重新索引。

AI 任务规划支持独立 `PLANNING_*` 配置，缺失时逐项回退到 `CHAT_*`。

### 十三、AI 任务规划

用户输入规划要求（如"将这个项目拆成六周开发任务"），可选择知识库文档作为参考。

两阶段生成：
1. **骨架阶段**：生成摘要、假设、风险、里程碑和任务身份（SkeletonModelOutput）
2. **细节阶段**：补全描述、工时、日期、负责人建议、依赖和来源（DetailModelOutput）

每阶段最多自动修复一次。JSON Schema + 业务规则严格校验（DAG 无环、日期范围、成员归属等）。

### 十四、AI 规划预览与人工确认

AI 不能直接修改项目数据。完整流程：用户提交 → AI 生成 JSON → 后端校验 → 前端预览 → 用户编辑/确认 → 后端再次检查 → 事务中批量创建里程碑和任务 → 写入操作日志。

预览页面支持：修改任务名称/描述/日期/优先级；设置负责人；删除不合理任务；调整依赖；取消或确认。

确认使用幂等键 + 数据库事务原子落地，失败整体回滚。

### 十五、AI 规划版本记录

每次生成保存：用户输入、使用文档、模型信息、原始输出、校验结果、生成时间、是否已确认、确认人、最终创建数量。不可变版本历史，支持恢复。

### 十六、基础系统功能

统一 API 返回格式（code/message/data）；全局异常处理；参数校验（Jakarta Validation）；JWT 身份认证；项目权限校验；数据库事务；分页查询；日志记录；OpenAPI 文档；健康检查（/actuator/health）；Docker Compose 本地启动；环境变量配置；Flyway 数据库迁移；API Key 隐私保护。

---

## 后端架构

模块化单体，包按功能划分：

```
com.shitulelv.aicollab
├── auth/          登录、注册、Refresh Token、Cookie
├── user/          用户资料、改密
├── project/       项目、成员、邀请、角色、审计
├── work/          里程碑、任务、依赖、评论
├── document/      上传、解析、分块、索引、删除
├── knowledge/     会话、检索、回答、引用
├── planning/      AI 草案、校验、确认落地
├── infrastructure/ AI 网关、存储、向量
└── common/        统一响应、错误码、安全、限流
```

完整业务模块四层：`api/` → `application/` → `domain/` → `infrastructure/`

Controller → Application Service → Domain Policy → Repository → Mapper → Entity

## 前端架构

```
ai-collab-frontend/src/
├── api/           Axios 客户端、类型、拦截器
├── stores/        authStore、projectStore
├── modules/       功能模块（按模块组织）
│   ├── auth/      登录、注册
│   ├── account/   个人资料、改密
│   ├── project/   项目列表、成员管理、邀请
│   ├── work/      里程碑、任务看板、评论
│   ├── document/  文档知识库
│   ├── knowledge/ 知识问答
│   └── planning/  AI 任务规划
├── router/
└── styles/
```

前端路由：
- `/login` 登录
- `/register` 注册
- `/invite/:code` 邀请接受
- `/account` 账号设置
- `/projects` 我的项目
- `/projects/:projectId/board` 任务看板
- `/projects/:projectId/milestones` 里程碑
- `/projects/:projectId/members` 成员管理
- `/projects/:projectId/documents` 文档知识库
- `/projects/:projectId/knowledge` 知识问答
- `/projects/:projectId/ai-planning` AI 任务规划

## 数据库

21 张表（V1 创建），V1–V11 Flyway 迁移。关键表：`app_user`, `project`, `project_member`, `project_task`, `milestone`, `task_dependency`, `project_document`, `document_chunk`, `knowledge_session/message/citation`, `ai_task_plan/version/attempt/confirmation`, `audit_log`, `ai_call_log`

## 关键设计约束

- 主键统一 UUID，业务时间 `timestamptz`
- 所有项目级 SQL 必须携带 `project_id`
- 子资源查询用 `(projectId, entityId)`，不先按全局 ID 查询
- 外部模型调用不放在数据库事务内
- `@Transactional` 只标注短事务；Cookie 在事务外设置
- Redis 不可用时核心功能仍运行（限流降级为进程内）
- 前端按角色隐藏按钮，但后端权限是唯一可信边界
- AI 输出视为不可信数据，Prompt 边界转义，模型不直接写业务表
- Flyway 迁移文件不可修改（V1–V11），新变更用新版本号
- 文档处理使用 CAS + processing_token 防 ABA 竞态
- 规划确认使用幂等键 + 数据库事务原子落地

## 启动命令

```bash
# 基础设施（PostgreSQL + pgvector + Redis + MinIO）
docker compose -f ai-collab-deploy/docker-compose.yml up -d

# 后端（local profile 自动加载 .env，端口 8080）
cd ai-collab-backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 前端（Vite 代理 /api → localhost:8080，端口 5173）
cd ai-collab-frontend && pnpm dev

# 健康检查
curl http://localhost:8080/actuator/health
```

停止：`docker compose -f ai-collab-deploy/docker-compose.yml down`
重置数据：`docker compose -f ai-collab-deploy/docker-compose.yml down -v`

## 本地默认用户

用户名 `demo_owner`，密码由 `DEMO_OWNER_PASSWORD` 环境变量提供。

## 验证流程

```powershell
# 后端编译打包
cd ai-collab-backend
.\mvnw.cmd clean package -DskipTests

# 后端测试
.\mvnw.cmd test

# 前端类型检查 + 生产构建
cd ai-collab-frontend
pnpm typecheck
pnpm build

# 前端测试
pnpm test

# 检查代码格式
git diff --check
```

## 重要文档

| 文档 | 用途 |
|---|---|
| `CLAUDE.md` | 本文件，每次对话启动时读取 |
| `docs/architecture.md` | 系统设计事实来源（模块、API、安全、部署） |
| `docs/database.md` | 数据库设计与约束（21 张表、索引、V1–V11） |
| `docs/api/openapi.yaml` | API 契约（所有端点、请求/响应、错误码） |
| `docs/FUTURE_ROADMAP.md` | 未来功能路线图 |
| `docs/learning/phase-*.md` | 各阶段学习笔记（Phase 01–08） |

## 代码规范

- 后端目录名、Java 包名、ZIP 文件名使用 ASCII，不得使用中文目录
- Controller 只接收 Request DTO，不直接接收 Entity
- Application Service 返回 View DTO，不返回 Entity
- Mapper 全部使用注解 SQL（`@Insert`/`@Select` 等），无 XML Mapper 文件
- 前端简体中文 UI，Element Plus `zh-cn`，日期中文年月日
- 所有 AI 输出视为不可信文本，渲染前清洗 HTML
- API Key 只从环境变量读取，不写数据库、不提交 Git
- `architecture.md` 是系统设计事实来源，新功能设计必须与其对齐

## Git 工作流

- 开发在本地 main 分支进行，测试通过后直接推送到远程 main
- 不使用 feature 分支结构
- 测试文件不提交到 GitHub

## 下一步开发方向

见 `docs/FUTURE_ROADMAP.md`，包含后续可开发功能：

1. **规划与正式任务双向同步** — 变更检测、反向同步、影响分析
2. **项目执行进度智能分析** — 完成度、延期风险、关键路径、成员负载、AI 周报
3. **更丰富的任务视图** — 甘特图、依赖图、里程碑进度、成员工作量、日历
4. **通知和协作提醒** — 站内通知、截止提醒、依赖完成、@成员
5. **更完整的文档知识能力** — 版本管理、重新索引、引用清理、来源定位
6. **AI 规划模板** — 预置模板（软件开发/比赛/科研等）+ 自定义模板
7. **规划导出和报告** — Markdown/PDF/Excel 导出、打印、分享链接
