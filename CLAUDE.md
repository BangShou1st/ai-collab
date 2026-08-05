# AI Collab — Claude 开发入口

本仓库是面向高校竞赛团队和软件课程项目的 AI 项目协作平台：

- 后端：Java 21、Spring Boot、MyBatis-Plus、PostgreSQL/pgvector。
- 前端：Vue 3、TypeScript、Vite、Element Plus、Pinia。
- 基础设施：Redis、MinIO、Docker Compose。
- 当前数据库迁移：V1–V30。

## 每次任务开始

1. 读取 `AGENTS.md`。
2. 读取 `docs/README.md`。
3. 读取 `docs/feature-matrix.md`，不要重复实现已有能力。
4. 运行 `git status --short --branch`，保护现有未提交修改。
5. 只读取任务涉及的功能指南、源码和接口片段。

本地开发只使用 `main`，不要创建分支或 worktree。

## 文档路由

- 系统事实和文档入口：`docs/README.md`
- 当前能力：`docs/feature-matrix.md`
- 架构：`docs/architecture.md`
- 数据库：`docs/database.md`
- HTTP 契约：`docs/api/openapi.yaml`
- Claude/MiMo 协议：`docs/development/claude-mimo-protocol.md`
- 单任务模板：`docs/development/task-template.md`
- API 同步检查：`docs/development/api-contract-checklist.md`
- 具体模块指南：`docs/development/guides/`

## 执行原则

- 缺陷先复现并写失败测试；功能先明确设计和验收。
- 一次只做一个完整闭环，禁止顺手扩展无关能力。
- 项目权限、项目隔离、事务、并发和敏感信息规则以 `AGENTS.md` 为准。
- MiMo 只处理 Claude 已限定的局部机械任务，不能负责架构或接口决策。
- 修改接口必须同步后端 DTO/View、OpenAPI、前端 type/API、中文错误和测试。

## 启动

```powershell
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d

Set-Location ai-collab-backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

Set-Location ..\ai-collab-frontend
pnpm dev
```

默认地址：后端 `http://localhost:8080`，前端 `http://localhost:5173`。凭据只从根目录 `.env` 读取，不输出其值。

## 完成前

执行 `AGENTS.md` 的基础验证，并检查：

- `git diff --check`
- `git diff --cached`
- `git status --short --branch`

只提交当前任务文件，并在交付中报告未运行或未通过的验证。
