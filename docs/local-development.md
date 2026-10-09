# 本地开发维护

在仓库根目录使用 Windows PowerShell 5.1 或 PowerShell 7；需要 JDK 21、Node.js 22+、
pnpm 11+、Docker Desktop（Compose）。首次在 `ai-collab-frontend` 执行
`pnpm install --frozen-lockfile`。不要使用历史 Agent 验收宿主作为日常启动入口。

```powershell
.\dev.ps1 start
.\dev.ps1 status
.\dev.ps1 stop
.\dev.ps1 stop-infra  # 明确独立操作；不删除容器、镜像、卷
```

`start` 检查配置、依赖、两端端口，复用现有基础设施，等待健康，然后通过现有 Maven wrapper
编译源码/生成运行 classpath，以本机 JVM 的 `local` profile 启动后端，以本机 Node 启动 Vite。
首次解析依赖可能需要网络，进度在 `build.log`。不会启动 compose 的 `app`，不会构建后端容器。
重复执行保留健康进程的 PID；同仓库的既有健康进程可借用，并标为不受本入口管理。
并发入口操作通过文件锁串行化；启动失败保留已启动的进程供诊断，可执行 `stop` 收尾。

| 服务 | 地址 / 健康检查 |
| --- | --- |
| 前端 | http://localhost:5173（Vite HMR，端口严格固定） |
| 后端 | http://localhost:8080；`/actuator/health` 应为 `UP` |
| PostgreSQL / Redis | localhost:5432 / localhost:6379；容器 health 应为 healthy |
| MinIO | http://localhost:9000/minio/health/ready；控制台 http://localhost:9001 |

修改 Vue/TS 由 Vite 热更新。修改 Java 后执行 `stop` → `start` 重新编译；入口不监视 Java 源码。
需要 IDE 断点时，先停止入口管理的后端，再由 IDE 使用相同工作目录、`local` profile 和配置启动。
不要同时运行两个后端。入口的 JVM 直接运行 `target/classes`，不依赖陈旧的打包 JAR。

## 配置与数据归属

- 根目录 `.env` 是唯一后端/compose 配置源；后端工作目录为 `ai-collab-backend`，
  `application-local.yml` 通过 `file:../.env[.properties]` 加载。使用模板的 Java properties 格式，
  不写 shell `export` 或给值加 shell 引号。仅在文件不存在时复制 `.env.example`，手动填值。
  入口只检查，不创建、覆盖或更换任何密钥。加密主密钥必须沿用既有值。
- 前端沿用 `vite.config.ts` 与 `.env.development`，本机覆盖放前端 `.env.local`。
  代理目标为 `http://localhost:8080`，API 前缀为 `/api/v1`。
  来源校验沿用后端 `AUTH_ALLOWED_ORIGINS`，需允许 `http://localhost:5173`。
  本入口不提供改端口开关；手动改端口须同步代理和精确来源，禁止通配符放宽。
- `ai-collab-deploy/.env` 不参与本入口。会覆盖配置源的继承环境变量会被入口拒绝，
  提示变量名但不显示值；在干净终端运行或先自行核对。
- PostgreSQL、Redis、MinIO 的现有健康容器优先复用。停止的容器使用 `docker start`，
  不根据 compose 镜像变化重建。新环境创建时只选择 `postgres redis minio`，使用
  `--no-deps --no-recreate`。卷存在而容器缺失时拒绝自动恢复，避免新镜像接到旧数据。
- 旧 MinIO 数据升级须先用旧数据副本验证对象读写、重启留存、非 root 权限与回退。
  [恢复说明](deployment-recovery.md) 中的空卷验证不能替代旧数据验证。本流程不升级 MinIO。
- 用户 LLM 配置留在个人 AI 设置；Embedding 留在系统管理中心；入口不改业务或模型配置，
  不启动 Ollama、不安装模型，也不接入付费服务。

## 日志、停止与故障

私有运行目录为 `ai-collab-backend/target/local-dev/`（Git 忽略）：`processes.json` 记录
PID、创建时间、可执行文件、命令摘要、管理标记与日志位置；`backend/frontend.stdout.log`、
`backend/frontend.stderr.log` 为当前一次启动日志，`build.log` 为编译/依赖解析日志。
`infra-*.log` 在创建/启动失败或健康超时时保存诊断；正常复用不更新它们，旧内容不代表当前状态。
日志可能含业务诊断，禁止直接提交或归档；无需输出 `.env` 或模型密钥。

`stop` 只终止管理标记为 true 且 PID、创建时间、路径、命令摘要仍匹配的 JVM/Node；
不按端口或进程名杀进程，不停止借用进程。启动它们的隐藏命令壳随应用退出。
默认不停止基础设施；`stop-infra` 单独停止三个已核对 compose 身份的服务，保留全部卷。

| 故障 | 处理 |
| --- | --- |
| Docker engine 不可用 | 启动 Docker Desktop 主程序，等待 `docker info` 成功；不启动 Windows 辅助服务 |
| 缺配置 / 占位符 | 按提示变量手动配置；有业务数据时保留原加密主密钥 |
| 8080 / 5173 被占用 | 按提示的 PID、名称、路径核对；入口不会杀停或自动换端口 |
| 基础设施不健康 | 看 `status`、对应 `infra-*.log`，检查既有容器和凭据；不清卷 |
| 后端失败 / 超时 | 看后端 stdout/stderr 与 `build.log`；检查 DB/Redis 凭据、Flyway、JDK，修复后重试 |
| 前端失败 | 先用锁文件安装依赖，检查 Vite 日志和代理覆盖；不会自动跳到 5174 |
| 状态文件丢失 | 同仓库健康进程会被借用；入口不擅自重新认领其停止权限 |

本轮实际验收范围与限制见 [验收记录](local-development-acceptance.md)。
