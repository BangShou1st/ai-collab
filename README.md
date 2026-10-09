# AI Collab

AI Collab 是一个面向高校竞赛团队和软件课程项目的 AI 项目协作平台，集成项目管理、文档知识库、AI 任务规划和受控 Agent，为团队提供智能化协作体验。

## 项目简介

AI Collab 解决的核心问题：

- **项目管理碎片化**：传统工具分散在多个平台，缺乏统一视图
- **知识沉淀困难**：项目文档散落各处，难以快速检索和复用
- **任务规划低效**：人工拆解任务耗时且容易遗漏依赖关系
- **AI 工具分散**：各类 AI 能力缺乏统一管理和安全控制

平台通过整合项目协作、文档管理、RAG 问答、AI 规划和 Agent 能力，让团队在同一个工作空间中高效协作。

## 核心功能

### 项目协作

| 功能 | 说明 |
|---|---|
| 项目生命周期 | 创建、编辑、归档、删除，四态状态管理 |
| 三级权限 | OWNER / ADMIN / MEMBER，后端强制校验 |
| 任务看板 | 拖拽交互、批量操作、依赖管理、环检测 |
| 可视化视图 | 甘特图、依赖图、日历、成员负载 |
| 里程碑 | 目标管理、进度追踪、截止提醒 |
| 操作日志 | 审计追踪、中文摘要、脱敏详情 |

### 文档知识库

| 功能 | 说明 |
|---|---|
| 文档管理 | PDF / DOCX / Markdown / TXT，20MB 限制 |
| 智能解析 | Apache Tika 提取，自动清洗分块 |
| 向量检索 | pgvector 存储，余弦相似度搜索 |
| RAG 问答 | 流式输出、引用定位、反馈机制 |

### AI 任务规划

| 功能 | 说明 |
|---|---|
| 两阶段生成 | 骨架生成 → 细节填充 |
| Schema 校验 | 结构化输出验证 |
| 人工编辑 | 预览、修改、补充 |
| 幂等确认 | 事务创建正式任务 |

### 项目协作 Agent

| 功能 | 说明 |
|---|---|
| 原生 Tool Calling | OpenAI / Claude / Gemini 统一协议 |
| 6 个固定 Skill | 项目健康、研究、周报、交付就绪、迭代规划、会议转任务 |
| 人工审批 | 写操作需确认，安全执行 |
| 项目记忆 | 决策、偏好、约束、经验 |
| SSE 事件流 | 实时进度、断线续传 |
| MCP 集成 | 只读工具白名单，HTTPS 安全 |

## 技术栈

### Backend

| 技术 | 版本 | 用途 |
|---|---|---|
| Java | 21 | 运行时 |
| Spring Boot | 4.1.0 | 框架 |
| Spring Security + OAuth2 | - | JWT 认证 |
| MyBatis-Plus | 3.5.17 | 持久层 |
| Flyway | - | 数据库迁移 |
| Apache Tika | 3.3.0 | 文档解析 |

### Frontend

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5.18 | 框架 |
| TypeScript | 5.8.3 | 类型安全 |
| Vite | 7.1.3 | 构建工具 |
| Pinia | 3.0.3 | 状态管理 |
| Element Plus | 2.10.7 | UI 组件库 |

### Infrastructure

| 技术 | 版本 | 用途 |
|---|---|---|
| PostgreSQL | 17 + pgvector | 业务数据 + 向量存储 |
| Redis | 7 | 限流、缓存 |
| MinIO | latest | 对象存储 |

### AI Integration

| Provider | 支持 |
|---|---|
| OpenAI 兼容 | Tool Calling |
| Anthropic Claude | Tool Calling |
| Google Gemini | Tool Calling |

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Vue 3 Frontend                         │
│  ┌──────────┬──────────┬──────────┬──────────┬────────────┐ │
│  │ Project  │   Work   │ Document │Knowledge │   Agent    │ │
│  │ Module   │  Module  │  Module  │  Module  │   Module   │ │
│  └──────────┴──────────┴──────────┴──────────┴────────────┘ │
└─────────────────────────┬───────────────────────────────────┘
                          │ REST API + SSE
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Backend                       │
│  ┌──────────┬──────────┬──────────┬──────────┬────────────┐ │
│  │   Auth   │  Project │   Work   │Document  │   Agent    │ │
│  │ Module   │  Module  │  Module  │ Module   │   Module   │ │
│  └──────────┴──────────┴──────────┴──────────┴────────────┘ │
│  ┌──────────┬──────────┬──────────┬──────────┬────────────┐ │
│  │Knowledge │Planning  │Notification│ Common  │Infra (AI) │ │
│  │ Module   │  Module  │  Module   │ Module  │   Module   │ │
│  └──────────┴──────────┴──────────┴──────────┴────────────┘ │
└─────────────────────────┬───────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
   │ PostgreSQL  │ │    Redis    │ │    MinIO    │
   │ + pgvector  │ │             │ │             │
   └─────────────┘ └─────────────┘ └─────────────┘
          │
          ▼
   ┌─────────────────────────────────────┐
   │        AI Model Gateway             │
   │  OpenAI / Claude / Gemini / MCP    │
   └─────────────────────────────────────┘
```

详细架构说明见 [docs/architecture.md](docs/architecture.md)。

## 环境要求

- JDK 21
- Node.js 22+
- pnpm 11+
- Docker Desktop (含 Docker Compose)

Docker Compose 提供 PostgreSQL、Redis、MinIO，无需手动安装。

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/BangShou1st/ai-collab.git
cd ai-collab
```

### 2. 配置环境变量

Linux / macOS:

```bash
[ -e .env ] || cp .env.example .env
```

Windows PowerShell:

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
```

编辑 `.env`，替换所有 `change-me` 占位符。

### 3. Windows 本地开发入口（推荐）

在仓库根目录执行，Docker 只运行 PostgreSQL、Redis、MinIO，本机运行后端源码与 Vite：

```powershell
cd ai-collab-frontend
pnpm install --frozen-lockfile
cd ..
.\dev.ps1 start
.\dev.ps1 status
.\dev.ps1 stop        # 仅本入口管理的前后端
.\dev.ps1 stop-infra  # 独立操作：停止基础设施，保留容器与数据卷
```

后台运行不弹额外窗口；重复启动复用健康进程，端口冲突时报告 PID 并退出。
已有容器优先复用，不自动重建或升级 MinIO。日志与进程身份在
`ai-collab-backend/target/local-dev/`，详细配置归属、修改源码后的重启与故障处理见
[本地开发维护说明](docs/local-development.md)。入口使用现有 `.env`，不会生成或覆盖配置。

### 4. 手动启动（其他系统或需要前台调试时）

只选择三个基础设施服务，**不要**同时启动容器 `app` 和本机后端。
以下 compose 命令适用于新环境；已有数据的环境优先使用 Windows 入口复用原容器，
镜像升级须遵循 [恢复说明](docs/deployment-recovery.md) 的副本验证边界。

```bash
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d --no-deps --no-recreate postgres redis minio
```

#### 启动后端

```bash
cd ai-collab-backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows:

```powershell
cd ai-collab-backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

#### 启动前端

```bash
cd ai-collab-frontend
pnpm install --frozen-lockfile
pnpm dev --port 5173 --strictPort
```

### 5. 访问应用

| 服务 | 地址 |
|---|---|
| 前端 | http://localhost:5173 |
| 后端 API | http://localhost:8080 |
| 健康检查 | http://localhost:8080/actuator/health |
| MinIO 控制台 | http://localhost:9001 |

默认登录账号见 `.env` 中的 `DEMO_OWNER_USERNAME` 和 `DEMO_OWNER_PASSWORD`。

本地 clone 默认开放公开注册（`.env.example` 中 `AUTH_PUBLIC_REGISTRATION_ENABLED=true`），
可直接注册体验；生产环境可将其设为 `false` 仅保留管理员建号。
登录与注册均有 IP/用户名双维度限流，无需短信或邮件验证码。

## AI 模型配置

### 环境变量

```bash
# 启用 AI 能力
CHAT_ENABLED=true
CHAT_PROVIDER=openai-compatible
CHAT_BASE_URL=https://api.openai.com
CHAT_API_KEY=your-api-key
CHAT_MODEL=gpt-4

# Embedding 配置
EMBEDDING_ENABLED=true
EMBEDDING_BASE_URL=https://api.openai.com
EMBEDDING_API_KEY=your-api-key
EMBEDDING_MODEL=text-embedding-3-small

# Agent 配置
AGENT_ENABLED=true
```

### 配置归属

登录后在个人 AI 设置（`/settings/ai`）中连接自己的 LLM，
可分别覆盖知识问答、AI 规划与 Agent，也可只配一个默认模型全用；
API Key 只写不读。Embedding 由系统管理员在管理中心 AI Infrastructure 维护；
MCP 集成由项目管理员在项目设置中维护。旧项目级模型配置页已废弃。

### 支持的 Provider

| Provider | Chat | Embedding | Tool Calling |
|---|---|---|---|
| OpenAI 兼容 | ✓ | ✓ | ✓ |
| Anthropic Claude | ✓ | - | ✓ |
| Google Gemini | ✓ | - | ✓ |

## API 文档

OpenAPI 规范：[docs/api/openapi.yaml](docs/api/openapi.yaml)

## 项目结构

```
ai-collab/
├── ai-collab-backend/           # Spring Boot 后端
│   ├── src/main/java/           # 业务模块
│   │   └── com.shitulelv.aicollab/
│   │       ├── auth/            # 认证
│   │       ├── user/            # 用户
│   │       ├── project/         # 项目
│   │       ├── work/            # 任务、里程碑
│   │       ├── document/        # 文档
│   │       ├── knowledge/       # 知识问答
│   │       ├── planning/        # AI 规划
│   │       ├── agent/           # 协作 Agent
│   │       ├── notification/    # 通知
│   │       ├── common/          # 公共组件
│   │       └── infrastructure/  # AI 网关、存储
│   ├── src/main/resources/db/   # Flyway 迁移 (V1-V38)
│   └── src/test/                # 测试
├── ai-collab-frontend/          # Vue 3 前端
│   └── src/modules/             # 功能模块
├── ai-collab-deploy/            # Docker Compose
├── docs/
│   ├── architecture.md          # 架构文档
│   └── api/openapi.yaml         # API 契约
├── .env.example                 # 环境变量模板
└── README.md                    # 本文件
```

## 开发

### 后端测试

```bash
cd ai-collab-backend
./mvnw test
```

### 前端测试

```bash
cd ai-collab-frontend
pnpm test
pnpm typecheck
```

### 构建

```bash
# 后端
cd ai-collab-backend
./mvnw clean package -DskipTests

# 前端
cd ai-collab-frontend
pnpm build
```
