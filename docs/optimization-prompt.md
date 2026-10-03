# AI Collab 全量优化任务提示词

> 使用方式：在 WorkBuddy 新会话中打开本项目（E:/project/ai-collab），把本文件全文粘贴为任务指令执行。

---

你是一名资深全栈工程师。请对当前工作区的 AI Collab 项目（Spring Boot 4.1 + Java 21 后端 / Vue 3.5 + TS + Vite 7 前端 / PostgreSQL 17 + pgvector + Redis + MinIO）执行一轮系统性优化。前期已完成一次只读体检，以下所有问题均已定位到具体文件，请按阶段逐项完成。

## 第 0 步：建立基线（必做，先于一切修改）

1. 通读 `README.md`、`docs/architecture.md`（如存在）、`ai-collab-backend/pom.xml`、`ai-collab-frontend/package.json`，建立项目全貌。
2. 运行现有测试确认基线全绿，记录失败的用例（如有）：
   - 后端：`cd ai-collab-backend && ./mvnw test`（Windows 用 `mvnw.cmd`）
   - 前端：`cd ai-collab-frontend && pnpm test && pnpm typecheck`
3. 若基线本身存在失败，先报告并修复基线，再继续后续任务。

## 全局约束（全程有效）

- **不得修改已发布的 Flyway 迁移文件**（`ai-collab-backend/src/main/resources/db/migration/V1__*.sql` 至 `V45__*.sql`）。任何 schema 变更一律新增 `V46__*.sql` 起编号的迁移。
- 保持现有架构风格：后端按业务域分包 + api/application/domain/infrastructure 四层；前端按 `src/modules/<domain>/` 组织。
- 不得引入硬编码密钥/地址；所有环境差异走环境变量。
- 每完成一个阶段，重新运行对应测试套件验证不回归，再进入下一阶段。
- 本机为 Windows + pnpm 11 + JDK 21 环境，前端包管理器统一用 pnpm，禁止混用 npm。
- 所有改动完成后输出一份变更总结（改了哪些文件、为什么、验证结果）。

---

## 阶段 1（P0）：工程化缺口

### 1.1 后端 Docker 化 + CI
- 在 `ai-collab-backend/` 新增多阶段构建 `Dockerfile`（Maven 构建 → JRE 21 运行镜像，非 root 用户运行，暴露 8080，支持通过环境变量注入全部配置，含 healthcheck 走 `/actuator/health`）。
- 在仓库根新增 `.dockerignore` 与后端 `.dockerignore`。
- 新增 GitHub Actions 工作流 `.github/workflows/ci.yml`：触发于 push/PR，包含两个 job——后端（JDK 21，`./mvnw -B verify`，Testcontainers 需可用）与前端（pnpm，`pnpm install --frozen-lockfile && pnpm typecheck && pnpm test && pnpm build`）。
- （可选）在 `ai-collab-deploy/docker-compose.yml` 中追加 app 服务定义，镜像指向新 Dockerfile，依赖 postgres/redis/minio 健康检查通过后启动。

### 1.2 前端环境变量管理
- 前端目前**没有任何 `.env` 文件、零 `import.meta.env` 引用**，后端地址硬编码在两处：
  - `ai-collab-frontend/vite.config.ts`（dev 代理 `/api → http://localhost:8080`）
  - `ai-collab-frontend/src/api/http-client.ts`（第 12 行附近 baseURL `/api/v1`）
- 引入 `VITE_API_BASE_URL`（默认 `/api/v1`）与 `VITE_BACKEND_ORIGIN`（默认 `http://localhost:8080`，供 vite 代理使用）；新增 `.env.development`、`.env.production`、`.env.example`，并在 `src/` 补充 `env.d.ts` 类型声明。vite.config.ts 改为读取环境变量（用 `loadEnv`）。

### 1.3 前端 ESLint + Prettier
- 当前项目**完全没有 lint 配置**。安装并配置 `eslint` + `eslint-plugin-vue` + `@vue/eslint-config-typescript` + Prettier（eslint-config-prettier 关冲突），规则从严但不对存量代码做大规模风格改写——先以 `warn` 级别接入，仅修复 `error` 级问题。
- `package.json` 增加 `lint` / `lint:fix` / `format` 脚本，并把 `pnpm lint` 接入 CI。

### 1.4 后端测试覆盖率
- `pom.xml` 接入 JaCoCo 插件，`verify` 阶段产出报告；在 CI 中上传/展示覆盖率（不必设硬性阈值门禁，先可视化现状）。

---

## 阶段 2（P1）：测试补强与隐患修复

### 2.1 补测试薄弱模块
后端测试分布严重不均，优先为以下模块补充单元/集成测试（复用现有 Testcontainers + PostgreSQL 模式，参考 `agent/infrastructure/AgentRepositoryIntegrationTest.java` 的写法）：
- `auth`（24 个主类仅 2 个测试）——**最高优先，涉及安全**：覆盖登录、JWT 签发/校验、注册限流、权限校验。
- `user`（13:1）、`work`（45:1）、`knowledge`（31:1）——先补核心 service 与 repository 的关键路径。

### 2.2 修复 4 处静默吞异常
以下位置存在 `catch (Exception ignored) {}` 式空 catch，改为至少 DEBUG/WARN 级日志（含上下文信息，不泄露敏感数据），并评估是否应向上抛出：
- `planning/application/TaskPlanOutputParser.java`
- `planning/application/TaskPlanCommandService.java`
- `planning/application/TaskPlanModelClient.java`
- `infrastructure/ai/user/UserAiProviderRepository.java`

### 2.3 排查测试稳定性遗留物
- `ai-collab-backend/target/surefire-reports/` 下有 75 个 `.dumpstream`（2026-08 至 2026-09），后端根目录有 JVM 崩溃日志 `hs_err_pid46752.log` 与 `replay_pid46752.log`。
- 先阅读 hs_err 日志头部判断崩溃原因（内存/原生库/ JIT），再检查 Surefire 的 fork/内存配置是否需要调整（如 `argLine` 加 `-Xmx`）；确认后删除两个本地日志文件（不入库，已被 gitignore）。

---

## 阶段 3（P2）：结构优化

### 3.1 后端大类拆分
- `agent/infrastructure/repository/AgentRepository.java`（893 行）：按聚合/查询职责拆分为多个 repository 或引入查询服务。
- `agent/application/runtime/AgentRuntimeCoordinator.java`（881 行）：按运行时职责（调度、事件处理、状态持久化）拆分。
- 拆分前后行为必须等价，相关测试全绿。

### 3.2 前端超大视图组件拆分
以下 SFC 超过 500 行，按"视图层只负责渲染与编排，逻辑下沉到 composable/纯 TS"的原则拆分（参考已有的 `agent/agent-run-store.ts`、`planning/planning-poller.ts` 模式）：
- `src/modules/knowledge/KnowledgeView.vue`（874 行）
- `src/modules/work/TaskBoardView.vue`（824 行）
- `src/modules/planning/PlanningView.vue`（793 行）
- `src/modules/agent/AgentView.vue`（696 行）
- `src/modules/project/ProjectModelSettings.vue`（572 行）

### 3.3 目录归位与逻辑收敛
- `src/views/LoginView.vue` 移入 `src/modules/auth/`，同步更新 `src/router.ts`，并删除空的 `src/views/` 目录。
- `src/api/http-client.ts`（axios 版）与 `src/api/authenticated-fetch.ts`（fetch 流式版）各自实现了一套 401 刷新重试逻辑，将刷新协调逻辑收敛到 `src/auth/auth-refresh-coordinator.ts`，两处复用。

---

## 阶段 4（P3）：杂项清理

1. `start-frontend.bat`：第 18 行 `call npm run dev` 改为 `pnpm dev`，与第 9 行的 `pnpm install --frozen-lockfile` 统一。
2. 核实 `package.json` 中 `marked ^18.0.7` 与 `jsdom ^29.1.1` 的版本号（官方主线约为 15.x / 26.x，明显异常）：对照 npm registry 实际可用版本，固定到正确的最新稳定版并重新安装验证。
3. 后端 `pom.xml`：确认 `jackson-datatype-jsr310`（127–130 行）无实际使用后移除（Boot 4 / Jackson 3 已内置 Java 时间支持）；检查 `spring-boot-starter-jdbc` 是否被直接使用，未使用则移除；`okhttp-jvm` 保留（pom 注释说明是 MinIO 9 元数据 workaround）。
4. 前端清理：`.wrangler/`、`dev.log` 加入 `.gitignore` 并删除本地残留；确认 `dist/` 未被 git 跟踪。
5. `pom.xml` 增加 `versions-maven-plugin` 或 OWASP dependency-check，便于后续常态化依赖巡检（仅接入，不强制作门禁）。

---

## 阶段 5：前端设计优化（UI/UX 打磨）

在不改变信息架构和路由的前提下，对前端做一轮设计质量提升。先通读 `src/styles/`、`src/shared/`（`AppShell.vue`、`PageHeader.vue`、`StatusBadge.vue`）与上述大视图组件，建立现状认知，然后：

1. **设计令牌统一**：将散落在各组件中的颜色、间距、圆角、字号收敛为一组 CSS 变量/SCSS token（主色、语义色 success/warning/danger/info、间距阶梯、字级阶梯、阴影），Element Plus 主题通过 CSS 变量覆盖与之对齐，消除硬编码色值。
2. **页面骨架一致性**：所有业务页面统一使用 `AppShell` + `PageHeader` 的结构（标题、副标题、操作区位置一致），消除各模块自行其是的页头样式。
3. **三态补全**：逐页检查并补齐 加载态（骨架屏或统一样式的 loading）、空状态（有引导文案和主操作按钮，而非裸文本"暂无数据"）、错误态（错误提示 + 重试入口）。
4. **数据可视化与状态语义**：`StatusBadge` 的状态-颜色映射全项目统一；任务看板、甘特图等密集信息区检查对比度与可读性。
5. **交互细节**：表单校验反馈一致；危险操作（删除/归档）统一二次确认样式；按钮层级（主/次/文字）使用一致；SSE 流式输出区域有明确的进行中/完成/失败视觉区分。
6. **响应式**：至少保证 1280px 与 1440px 桌面宽度下无横向滚动、无元素挤压溢出；侧边栏在小窗口下可折叠。
7. 每处视觉改动保持组件测试通过；如改动 `AppShell` 等共享组件，运行全部 `pnpm test` 验证。

---

## 验收标准

- [ ] 后端：`./mvnw verify` 全绿（含新增测试），JaCoCo 报告产出，auth/user/work/knowledge 测试数明显增加
- [ ] 前端：`pnpm lint && pnpm typecheck && pnpm test && pnpm build` 全绿
- [ ] 后端 Dockerfile 可构建（`docker build` 成功），CI 工作流语法有效
- [ ] 前端 `.env` 机制生效：改 `VITE_BACKEND_ORIGIN` 后 dev 代理随之变化
- [ ] 无新增硬编码地址/密钥；Flyway 存量迁移文件零改动
- [ ] 4 处吞异常已带日志；5 个超大 SFC 均拆至 500 行以内（或逻辑明显下沉）
- [ ] 输出最终变更总结报告

执行顺序：阶段 0 → 1 → 2 → 3 → 4 → 5。如遇基线测试失败、Flyway 校验失败、或某问题在代码中已不存在（已被修复），如实记录并说明，不要强行"完成任务"。
