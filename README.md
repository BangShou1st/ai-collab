# AI Collab

AI Collab 是一个面向项目团队的 AI 协作平台，把项目管理、文档知识库、AI 任务规划和受控 Agent 放在同一个工作空间中。系统强调项目隔离、服务端权限校验、人工确认和可追溯执行，适合作为完整的全栈项目或 Agent/MCP 工程实践参考。

## 主要能力

- 项目与成员：项目生命周期、角色权限、邀请码、里程碑、任务、依赖、评论和审计日志。
- 协作视图：甘特图、依赖图、日历、成员负载、项目周报、风险分析和规划对比。
- 文档知识库：PDF、DOCX、Markdown、TXT 上传，Tika 解析、pgvector 检索、引用定位、流式问答和反馈。
- AI 任务规划：两阶段结构化生成、校验与有限修复、版本记录、人工编辑、幂等确认后创建正式任务。
- Agent 2.0：原生 Tool Calling、固定 Skill、持久化步骤与事件、SSE 续传、取消、审批写入、项目记忆和定时运行。
- 受控 MCP：系统级连接管理、项目级授权、只读工具白名单、Schema 确认、HTTPS/SSRF 防护和不可信结果清洗。

## 技术栈

| 层级 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 4、Spring Security、JDBC / MyBatis-Plus、Flyway |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Element Plus、Vitest |
| 数据与基础设施 | PostgreSQL 17、pgvector、Redis 7、MinIO、Docker Compose |
| AI | OpenAI 兼容、Claude、Gemini，多用途模型路由，原生 Tool Calling 与 MCP |

## 目录结构

```text
ai-collab/
├─ ai-collab-backend/   # Spring Boot 后端、迁移和后端测试
├─ ai-collab-frontend/  # Vue 前端和前端测试
├─ ai-collab-deploy/    # PostgreSQL、Redis、MinIO 的 Compose 配置
├─ docs/                # 架构、数据库、OpenAPI、功能与开发文档
├─ scripts/             # OpenAPI 等工程校验脚本
├─ AGENTS.md            # 自动化开发代理规则
└─ .env.example         # 本地配置示例，不包含真实凭据
```

## 本地启动

环境要求：JDK 21、Docker Desktop、Node.js 20+、pnpm 11。

1. 复制配置示例并替换所有 `change-me`/`replace-with` 值：

   ```powershell
   Copy-Item .env.example .env
   ```

2. Windows 下可一键启动基础设施、后端和前端：

   ```powershell
   .\start-all.bat
   ```

   也可以分别执行 `start-docker.bat`、`start-backend.bat` 和 `start-frontend.bat`。

3. 打开以下地址：

   - 前端：http://localhost:5173
   - 后端：http://localhost:8080
   - 健康检查：http://localhost:8080/actuator/health
   - MinIO 控制台：http://localhost:9001

首次启动时 Flyway 会自动执行 V1–V30 迁移。默认关闭外部模型能力；配置 `CHAT_*` 后可启用知识问答和规划，Agent 还需设置 `AGENT_ENABLED=true`。MCP 远程主机必须加入 `AGENT_MCP_ALLOWED_HOSTS`，详细步骤见 [MCP 部署与浏览器验收](docs/agent/MCP_DEPLOYMENT_AND_BROWSER_TESTING.md)。

## 测试与构建

```powershell
Set-Location ai-collab-backend
.\mvnw.cmd test
.\mvnw.cmd clean package -DskipTests

Set-Location ..\ai-collab-frontend
pnpm install --frozen-lockfile
pnpm test
pnpm typecheck
pnpm build

Set-Location ..
.\scripts\validate-openapi.ps1
```

默认测试额度可通过环境变量调整：

- `KNOWLEDGE_RATE_LIMIT_PER_USER_HOUR`：知识问答每用户每小时次数，默认 60。
- `PLANNING_GENERATION_LIMIT_PER_USER_HOUR`：AI 规划每用户每小时成功/进行中生成数，默认 60。

Agent 不使用每小时次数限制，而是通过单次运行的步骤、模型轮次、工具调用和 Token 预算收敛；已有工具证据且接近预算边界时，会切换为不暴露工具的最终回答轮次。

## 文档

- [文档导航](docs/README.md)
- [功能完成度](docs/feature-matrix.md)
- [系统架构](docs/architecture.md)
- [数据库结构](docs/database.md)
- [OpenAPI 契约](docs/api/openapi.yaml)
- [Agent 2.0 文档](docs/agent/README.md)

真实 `.env`、模型密钥、MCP 凭据、机器码、构建产物、测试报告和 ZIP 文件均不应提交到仓库。
