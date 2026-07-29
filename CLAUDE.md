# AI Collab 开发上下文

本仓库是“面向高校竞赛团队、兼容软件课程项目的 AI 项目协作平台”。后端为 Java 模块化单体，前端为 Vue 单页应用；当前 AI 能力包括项目文档 RAG 问答和受控任务规划。

## 开始任何任务前

按顺序执行：

1. 读取本文件。
2. 读取 `docs/feature-matrix.md`，确认能力是完整、部分还是未实现。
3. 运行 `git status --short`，保护当前工作区的用户修改。
4. 只读取当前任务直接相关的源码和文档。
5. 缺陷修复先建立可复现测试；功能开发先明确规格和验收。

不要从 `docs/learning/` 推断当前状态。它们是历史学习记录。

## 当前事实

- Phase 09 项目概览和操作日志已实现，前后端入口已联通。
- 项目 CRUD 已形成前端闭环；项目状态当前只有 `ACTIVE`、`ARCHIVED`。
- 公开注册默认关闭；测试用户来自启动初始化或邀请流程。
- 最新 Flyway 迁移为 V12，最终数据库结构为 23 张表。
- 前端用户界面使用简体中文；内部枚举必须经过中文映射。
- 当前 AI 是受控工作流，不是通用 Agent 运行时。
- 精确状态和缺口以 `docs/feature-matrix.md` 为准。

## 技术栈与目录

```text
ai-collab-backend/   Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/pgvector
ai-collab-frontend/  Vue 3、TypeScript、Vite、Element Plus、Pinia、Axios
ai-collab-deploy/    PostgreSQL、Redis、MinIO 的 Docker Compose
docs/                架构、数据库、OpenAPI、开发规范和历史记录
scripts/             可重复运行的验证脚本
```

后端 package-by-feature：

```text
auth  user  project  work  document  knowledge  planning  common  infrastructure
```

完整模块依赖方向：Controller/DTO → Application Service/View → Domain Policy/Repository → Mapper/Gateway。

## 不可违反的设计约束

- 所有项目级后端接口都校验成员身份；OWNER/ADMIN/MEMBER 权限以服务端为准。
- 子资源使用 `projectId + entityId` 查询，禁止先按全局 ID 读取再补权限。
- 项目级 SQL 显式包含 `project_id`。
- Controller 不访问 Mapper，不返回 Entity。
- 外部模型和 MinIO 网络调用不放进数据库长事务。
- 并发写使用 `version`、CAS、行锁、数据库约束或幂等键，不用“先查再写”假设安全。
- AI 输出和项目文档都是不可信输入；限制大小、结构化解析、业务校验并清洗渲染。
- AI 不直接写业务表；规划确认和未来 Agent 写工具必须经过权限、人工确认和事务。
- API Key、密码、Token、Cookie、邀请码原文、文档正文和 Prompt 不进入日志或审计 detail。
- 已提交的 V1–V12 迁移不可修改；数据库变更新建下一版本。
- 前端隐藏按钮只改善体验，不能替代后端授权。
- 不为当前任务提前实现功能矩阵中的其他未实现能力。

## 中文界面要求

- 标题、按钮、表单、空状态、确认、成功和错误文案使用简体中文。
- `AI`、`PDF` 等技术缩写可保留。
- 状态、角色、优先级、审计 action/entity 必须通过 `shared/display-labels.ts`。
- 未知内部值显示“未知状态/未知操作/未知对象”，不展示原始代码。
- 日期使用共享中文格式化函数。

## 启动

```powershell
docker compose -f ai-collab-deploy/docker-compose.yml up -d

Set-Location ai-collab-backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

Set-Location ..\ai-collab-frontend
pnpm dev
```

后端默认 `http://localhost:8080`，前端默认 `http://localhost:5173`。凭据只从根目录 `.env` 读取，不输出其值。

## 验证

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests

Set-Location ..\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build

Set-Location ..
.\scripts\smoke-existing.ps1
git diff --check
git status --short
```

必须报告实际执行结果。不能运行时说明具体命令、退出码和原因，不能声称通过。

当前仓库策略忽略测试源码；不要 `git add -f` 提交测试，除非用户明确改变该策略。测试仍必须在本地编写并运行。

## 文档索引

| 文档 | 用途 |
|---|---|
| `docs/feature-matrix.md` | 当前功能事实和明确缺口 |
| `docs/architecture.md` | 架构、模块、流程和安全边界 |
| `docs/database.md` | 当前表、迁移和约束 |
| `docs/api/openapi.yaml` | HTTP 契约 |
| `docs/development/README.md` | 后续开发阅读顺序 |
| `docs/development/backend-conventions.md` | 后端实现规范 |
| `docs/development/frontend-conventions.md` | 前端和中文界面规范 |
| `docs/development/agent-design.md` | 通用 Agent 的分阶段设计 |
| `docs/development/claude-mimo-task-template.md` | Claude/MiMo 单任务模板 |
| `docs/FUTURE_ROADMAP.md` | 后续优先级 |

## 工作方式

- 当前项目在本地 `main` 开发；不要自行创建分支或推送。
- 工作区可能有未提交文件，禁止清理、覆盖或提交无关修改。
- 使用小步提交；一个提交只表达一个稳定结果。
- 发现文档与代码冲突时，以代码、迁移和测试证据核实，再同步功能矩阵。
- 大任务使用 `docs/development/claude-mimo-task-template.md` 拆分；一次只做一个可验收闭环。
