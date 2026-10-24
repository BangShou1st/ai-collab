# AGENTS.md

> 本文件是 AI 编码助手的**仓库级工作约定**，每次新会话开始前应完整阅读。
> 它只记录"必须遵守的规则"，项目介绍与用法见 [README.md](README.md)，架构细节见 [docs/architecture.md](docs/architecture.md)。
> 若本文件与代码事实冲突，以代码为准，并顺手修正本文件。

---

## 1. 项目是什么

**AI Collab** —— 面向高校竞赛团队与软件课程项目的 AI 协作平台（单体仓库，前后端同仓）。

四个业务域：

| 域 | 能力 |
| --- | --- |
| 项目协作 | 项目/成员/邀请、任务看板、里程碑、依赖、甘特图、日历、成员负载、操作日志 |
| 文档知识库 | 文档上传 → Tika 解析 → 分块 → pgvector 向量化 → RAG 问答（流式 + 引用） |
| AI 任务规划 | 两阶段生成（骨架 → 细节）、Schema 校验、人工编辑、幂等确认落库 |
| 协作 Agent | 原生 Tool Calling、6 个内置 Skill、写操作人工审批、项目记忆、SSE 事件流、MCP 集成 |

---

## 2. 技术栈与仓库结构

| 层 | 栈 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 4.1.0 · Spring Security + OAuth2 Resource Server · MyBatis-Plus 3.5.17 · Flyway · Apache Tika 3.3.0 · pgvector |
| 前端 | Vue 3.5 · TypeScript 5.8 · Vite 7 · Pinia 3 · Element Plus 2.10 · vue-router 4 · Vitest |
| 基建 | PostgreSQL 17 (pgvector) · Redis 7 · MinIO · Docker Compose |
| AI 接入 | OpenAI 兼容 / Anthropic Claude / Google Gemini · OpenCode Zen & Kilo Gateway 共享库（公开 JitPack） |

```
ai-collab-backend/    Spring Boot 后端（548 主源文件 / 约 4.2 万行）
  src/main/java/com/shitulelv/aicollab/<module>/
  src/main/resources/db/migration/    Flyway V1–V67
  src/test/java/                      单元 + Testcontainers 集成测试（1600+ 测试用例）
ai-collab-frontend/   Vue 3 前端（161 源文件 / 约 2.2 万行）
  src/{api,auth,shared,stores,router.ts,styles}/
  src/modules/<module>/              页面与业务组件（370+ 测试用例）
ai-collab-deploy/     docker-compose.yml（postgres / redis / minio / app）
docs/                 架构、运维与历史评审文档
dev.ps1               Windows 本地开发启停入口（仓库根）
```

---

## 3. 常用命令

### 本地启停（Windows 推荐入口）

```powershell
.\dev.ps1 start        # 复用基础设施 + 本机跑后端(local profile)与 Vite
.\dev.ps1 status
.\dev.ps1 stop         # 只停本入口管理的进程
.\dev.ps1 stop-infra   # 只停 postgres/redis/minio，保留卷
```

首次需在 `ai-collab-frontend` 执行 `pnpm install --frozen-lockfile`。
配置源固定为仓库根 `.env`（从 `.env.example` 复制），不要另建或覆盖。

### 手动启动

```bash
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d --no-deps --no-recreate postgres redis minio
cd ai-collab-backend && bash ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
cd ai-collab-frontend && pnpm install --frozen-lockfile && pnpm dev --port 5173 --strictPort
```

### 校验（提交前必跑）

```bash
# 后端
cd ai-collab-backend && bash ./mvnw -B -ntp verify      # 含 Testcontainers，CI 同命令
# 前端
cd ai-collab-frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

| 服务 | 地址 |
| --- | --- |
| 前端 | http://localhost:5173 |
| 后端 | http://localhost:8080 （健康检查 `/actuator/health`） |
| PostgreSQL / Redis | localhost:5432 / localhost:6379 |
| MinIO | API http://localhost:9000 · 控制台 http://localhost:9001 |

---

## 4. 后端分层约定（硬规则）

调用链固定为：

```
Controller → Application Service → Domain Policy/Model → Repository 接口 → Mapper/Gateway
```

- **Controller 不允许注入 Mapper 或直接访问数据层**；权限与事务边界在 Application Service。
- **权限一律后端强制**：项目级资源用 `ProjectAccessGuard`（`requireMember` / `requireAdmin` / `requireOwner`，实现 `DatabaseProjectAccessGuard`）校验 `projectId + userId`；任务级另有 `WorkPermissionPolicy`。前端隐藏按钮不算权限。
- **领域层不依赖基础设施层**：`domain/**` 只允许依赖自身与 `common`。
- **异常统一出口**：`GlobalExceptionHandler` 把错误映射为 `ApiResponse`；业务错误抛 `BusinessException(ErrorCode)`，不要在 Controller 里手写 try/catch 或返回裸 Map。
- **响应统一包装** `ApiResponse<T>`；入参用 `api/dto` 下的 DTO + Bean Validation，不要直接接收 Entity。
- 仓储接口放 `infrastructure/repository`，MyBatis 实现与 Mapper 同域，SQL 用注解或 XML，**禁止字符串拼接 SQL**。
- 读接口返回 `application/view` 下的 View 对象，不要把 Entity 直接序列化给前端。

## 5. 数据库约定

- 迁移文件放 `ai-collab-backend/src/main/resources/db/migration/`，命名 `V<版本>__<说明>.sql`，**版本号连续递增**。
- **绝不修改已发布的历史迁移**（会触发 Flyway checksum 不一致）；一律新建迁移。
- 迁移与实体字段必须同步；状态字段用数据库 CHECK 约束兜底，应用层状态机也要校验非法跃迁。
- 涉及向量的表：维度必须与 Embedding 模型实际输出一致，不要硬编码 1536。

## 6. AI / Agent 红线

- **写操作必须走审批**：创建/修改任务、里程碑、记忆等写操作经 `ApprovalWriteAgentTool` / `ControlledWriteAgentTool`，由用户确认后才落库；不要新增绕过审批的写路径。
- **工具只读边界**：MCP 仅允许只读工具白名单；工具输出经 `AgentToolResultSanitizer` / `McpResultSanitizer` 清洗后再进入上下文，防提示注入。
- **密钥只写不读**：API Key 加密存储（`MODEL_CONFIG_MASTER_KEY`），接口不返回明文，日志与异常信息不得包含密钥。
- **循环与预算**：Agent 循环受 `AgentLoopGuard`、`AgentConvergencePolicy` 与上下文预算约束，新增执行路径不得绕开这些上限。
- **失败要可区分**：工具成功/失败/拒绝/取消/未执行/结果未知要分别表达，失败不得当作已验证事实。
- **流式与并发**：SSE 事件需保证顺序与断线续传（`Last-Event-ID`）；并发写入用运行租约（`AgentLeaseRenewer`）保护，重试必须幂等。
- Provider 差异（Claude/Gemini 的角色顺序、tool call id、SSE 事件格式）要显式处理，不要假设与 OpenAI 兼容。

## 7. 前端约定

- 组件放在 `src/modules/<module>/`，接口封装在 `src/api/`，跨模块共享状态放 `src/stores/`。
- 渲染 Markdown 必须经 `dompurify` 净化后再 `v-html`；禁止直接渲染未净化内容。
- 令牌与凭据处理跟随现有 auth store 方案，不要把长期凭据写进 `localStorage`。
- SSE/EventSource 在组件卸载时必须清理监听器，避免重复订阅与泄漏。
- 异步路径必须有 loading / error 收敛态；类型使用 TypeScript，避免新增 `any` 与 `@ts-ignore`。
- 新增依赖须同步 `pnpm-lock.yaml` 并用 `--frozen-lockfile` 验证。

## 8. 分支与提交

- 长期分支只有 **`main`**（稳定发布）与 **`dev`**（唯一开发分支）。日常开发、修复、自动化任务**全部直接提交 `dev`**。
- **升级到 `main` 的唯一路径是 PR 合并**：`dev` → PR → CI 全绿 → 合并。合并动作必须由人明确触发，Agent 不擅自合并 `main`。
- `main` 与 `dev` 的 push 都会跑 CI；PR 面向 `main` 也会跑。
- **禁止 force push、禁止改写历史、禁止删除 `main`**；删除临时分支前先确认提交已成为 `dev`/`main` 的祖先，否则先打 `archive/<日期>/<分支>` tag 归档。
- 提交信息沿用 Conventional Commits（`feat:` / `fix:` / `docs:` / `test:` / `chore:` / `refactor:`），正文用中文说明动机与影响面。

## 9. 文档与临时资产规范（硬规则）

- **技术文档分类存放（严禁 docs/ 根目录平铺过程文档）**：
  - 长期有效的系统级架构与核心指南保留在 `docs/` 顶层（`architecture.md`、`capability-matrix.md`、`local-development.md`、`deployment-recovery.md`、`backend-agent-redesign.md`）。文档总导航见 `docs/README.md`。
  - 模块设计与技术方案放 `docs/design/`；接口契约放 `docs/api/`；运维部署放 `docs/deployment/`；开发规范放 `docs/development/`。
  - 任务交接（handoff）、审查复核（review）、阶段交付报告（report）、专项研究（research）、临时 Prompt **必须存入 `docs/archive/<类型>/`**，严禁在 `docs/` 根目录直接平铺创建新文档。
- **验收证据统一受控**：
  - 自动化与端到端验收证据统一按日期归入 `docs/acceptance-evidence/YYYY-MM-DD/<task-id>/`（含 BSK 截图、脱敏数据、断言基准），严禁在项目根目录散落。
- **测试语料（Fixtures）统一收归**：
  - 用于 RAG 检索、文件解析、业务测试的静态文档语料统一存放于 `tests/fixtures/documents/`（统一使用 UTF-8 无 BOM 编码，并在其 `README.md` 中登记用途），禁止在根目录下随意新建 `test-*` 临时目录。
- **日志生成与临时产物红线**：
  - 测试命令与构建命令的输出日志**严禁直接生成在项目根目录或前后端源码目录下**。
  - 临时日志重定向输出至 `target/` 或指定受控目录；任务交付前必须清理本地临时生成的 `*.log` 文件。
- **凭据与敏感信息零容忍**：
  - 禁止在项目任何目录（包括受忽略的文本文件）存放明文 Token、API Key、密码等长期凭据。
- **文档维护与事实一致性**：
  - 改动功能时同步更新对应文档；改依赖版本、端口、命令、迁移数量时**必须**回改 README 与核心文档，避免文档漂移。
  - 涉及文档移动或重命名时，必须全局检查并修正 Markdown 链接与代码注释中的引用路径。

## 10. 交付前检查清单

- [ ] 改动范围符合任务；没有夹带无关重构
- [ ] 后端 `mvnw verify`、前端 `lint/typecheck/test/build` 全绿
- [ ] 新增数据库变更用新迁移文件，未改历史迁移
- [ ] 权限在服务端校验；Agent 写操作走审批；密钥不进日志与文本
- [ ] 文档与过程记录按规范存放于对应子目录，`docs/` 顶层未平铺新增过程文档
- [ ] 本地临时生成的 `*.log` 与调试产物已清理干净，未污染代码库
- [ ] README / 相关文档已同步，链接可用
- [ ] 提交落在 `dev`；合并到 `main` 走 PR 并等 CI 绿
