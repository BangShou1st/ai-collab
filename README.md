# AI Collab

AI Collab 是一个面向高校竞赛团队和软件课程项目的 AI 项目协作平台，把项目管理、文档知识库、AI 任务规划和受控 Agent 集成在同一个工作空间中。系统强调项目隔离、服务端权限校验、人工确认和可追溯执行，适合作为完整的全栈项目或 Agent/MCP 工程实践参考。

## 主要能力

| 模块 | 功能 |
|---|---|
| **项目管理** | 项目生命周期、三级角色权限（OWNER/ADMIN/MEMBER）、邀请码、里程碑、任务看板、依赖管理、评论和审计日志 |
| **协作视图** | 甘特图、依赖图、日历视图、成员负载、项目周报、风险分析和规划对比 |
| **文档知识库** | PDF/DOCX/Markdown/TXT 上传，Tika 解析，pgvector 向量检索，引用定位，流式问答和反馈机制 |
| **AI 任务规划** | 两阶段结构化生成、Schema/业务校验、版本记录、人工编辑、幂等确认后创建正式任务 |
| **Agent 2.0** | 原生 Tool Calling、6 个固定 Skill、持久化执行步骤与事件、SSE 断线续传、取消/重试、人工审批写入、项目记忆 |
| **受控 MCP** | 项目级连接管理、只读工具白名单、Schema 确认、HTTPS/SSRF 防护和不可信结果清洗 |
| **模型管理** | 项目级 AI 模型配置（OpenAI 兼容/Claude/Gemini）、加密 API Key、多用途路由（知识/规划/Agent） |

## 技术栈

| 层级 | 技术 | 说明 |
|---|---|---|
| **后端** | Java 21、Spring Boot、Spring Security | 模块化单体架构，JWT 认证 |
| **持久层** | MyBatis-Plus、Flyway | V1–V38 迁移，~41 张业务表 |
| **前端** | Vue 3、TypeScript、Vite、Pinia | Composition API，Element Plus UI |
| **数据存储** | PostgreSQL 17 + pgvector | 向量检索、项目隔离查询 |
| **缓存/状态** | Redis 7 | 限流、辅助状态（可降级） |
| **对象存储** | MinIO | 文档原文件存储 |
| **AI 集成** | OpenAI 兼容、Claude、Gemini | 原生 Tool Calling、多模型路由 |
| **协议** | MCP (Model Context Protocol) | 受控外部工具集成 |
| **部署** | Docker Compose | 一键启动基础设施 |

## 目录结构

```text
ai-collab/
├─ ai-collab-backend/           # Spring Boot 后端
│  ├─ src/main/java/            #   业务模块（auth/user/project/work/document/knowledge/planning/agent）
│  ├─ src/main/resources/db/    #   Flyway 迁移（V1–V38）
│  └─ src/test/                 #   集成测试、单元测试
├─ ai-collab-frontend/          # Vue 3 前端
│  └─ src/modules/              #   功能模块（agent/knowledge/planning/work）
├─ ai-collab-deploy/            # Docker Compose 配置
├─ docs/                        # 架构、数据库、OpenAPI、功能文档
│  ├─ api/openapi.yaml          #   HTTP 契约
│  ├─ agent/                    #   Agent 2.0 文档与历史规格
│  └─ development/              #   开发规范与功能指南
├─ scripts/                     # 校验脚本
├─ AGENTS.md                    # 自动化代理规则
└─ .env.example                 # 配置模板（不含真实凭据）
```

## 快速开始

### 环境要求

- JDK 21
- Docker Desktop（含 Docker Compose）
- Node.js 20+、pnpm 11+

### 启动步骤

1. **配置环境变量**

   ```powershell
   Copy-Item .env.example .env
   # 编辑 .env，替换所有 change-me/replace-with 占位符
   ```

2. **启动所有服务**（Windows）

   ```powershell
   .\start-all.bat          # 一键启动
   # 或分别启动
   .\start-docker.bat       # 基础设施
   .\start-backend.bat      # 后端
   .\start-frontend.bat     # 前端
   ```

   手动启动（跨平台）：

   ```bash
   docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d
   
   cd ai-collab-backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   
   cd ../ai-collab-frontend && pnpm dev
   ```

3. **访问服务**

   | 服务 | 地址 |
   |---|---|
   | 前端 | http://localhost:5173 |
   | 后端 API | http://localhost:8080 |
   | 健康检查 | http://localhost:8080/actuator/health |
   | MinIO 控制台 | http://localhost:9001 |

### 功能配置

- 默认关闭外部模型能力；配置 `CHAT_*` 环境变量后可启用知识问答和规划
- Agent 需设置 `AGENT_ENABLED=true`
- MCP 远程主机必须加入 `AGENT_MCP_ALLOWED_HOSTS` 白名单
- 详细步骤见 [MCP 部署指南](docs/agent/MCP_DEPLOYMENT_AND_BROWSER_TESTING.md)

## 测试与构建

### 后端

```powershell
cd ai-collab-backend
.\mvnw.cmd test                        # 单元测试 + 集成测试
.\mvnw.cmd clean package -DskipTests   # 打包
```

### 前端

```powershell
cd ai-collab-frontend
pnpm install --frozen-lockfile
pnpm test          # 单元测试
pnpm typecheck     # 类型检查
pnpm build         # 生产构建
```

### 契约验证

```powershell
.\scripts\validate-openapi.ps1         # OpenAPI 与代码一致性检查
```

### 速率限制配置

| 环境变量 | 说明 | 默认值 |
|---|---|---|
| `KNOWLEDGE_RATE_LIMIT_PER_USER_HOUR` | 知识问答每用户每小时次数 | 60 |
| `PLANNING_GENERATION_LIMIT_PER_USER_HOUR` | AI 规划生成限额 | 60 |

> Agent 不使用固定次数限制，而是通过步骤/模型轮次/工具调用/Token 预算收敛。

## 项目结构

| 文档 | 说明 |
|---|---|
| [文档导航](docs/README.md) | 所有文档的入口 |
| [功能矩阵](docs/feature-matrix.md) | 已实现功能与明确缺口 |
| [系统架构](docs/architecture.md) | 模块边界、数据流、安全原则 |
| [数据库设计](docs/database.md) | V1–V38 迁移，~41 张表结构 |
| [OpenAPI 契约](docs/api/openapi.yaml) | HTTP 接口定义 |
| [Agent 2.0](docs/agent/README.md) | Agent 系统文档 |
| [开发规范](docs/development/) | 后端/前端规范、功能指南 |
| [MCP 部署](docs/agent/MCP_DEPLOYMENT_AND_BROWSER_TESTING.md) | MCP 配置与验收 |

## 安全与隐私

- 真实 `.env`、模型密钥、MCP 凭据、构建产物和测试报告不应提交到仓库
- API Key 和 MCP 凭据仅以加密密文存储
- 所有项目资源后端校验项目成员身份
- 敏感信息（密码、Token、Prompt、模型输出）不进入日志或审计

## 许可证

本项目为私有仓库，仅限授权人员访问。
