# 本地一键安装指南（Windows / Linux / macOS）

面向普通用户：只需要安装 Docker，不需要 Java、Maven、Node.js、pnpm 或任何数据库知识。
本地模式与服务器模式使用同一批应用镜像，差异仅在入口绑定（本机回环 HTTP）与安全基线。

## 前置条件

| 平台 | 要求 |
| --- | --- |
| Windows 11 | [Docker Desktop](https://www.docker.com/products/docker-desktop/)（WSL2 后端），x64 架构 |
| Linux | Docker Engine + Compose v2 插件 |
| macOS | Docker Desktop（Apple Silicon 上后端镜像经 Rosetta 模拟运行，性能下降，可用但非最优） |

磁盘空间建议预留 5GB 以上（镜像约 1GB + 数据）。

## Windows 本地安装

```powershell
# 1. 下载或克隆仓库后，进入仓库目录
cd ai-collab

# 2. 一键安装（自动检查 Docker、生成随机凭据、拉取/构建镜像、启动并等待就绪）
.\ai-collab-deploy\install.ps1 install

# 3. 创建首位管理员（仅显示一次密码，请立即记录）
.\ai-collab-deploy\install.ps1 bootstrap-admin

# 4. 浏览器访问
http://localhost:8080
```

说明：

- 默认入口端口为 8080，被占用时换端口：`.\install.ps1 install -Port 8090`。
- 安装脚本幂等：重复执行不会覆盖已有凭据与数据。
- 凭据保存在 `ai-collab-deploy/local.env`（权限受限，已被 `.gitignore` 排除，切勿提交或外传）。

## Linux / macOS 本地安装

```bash
cd ai-collab
./ai-collab-deploy/install.sh install
./ai-collab-deploy/install.sh bootstrap-admin
# 浏览器访问 http://localhost:8080
```

## 安装脚本做了什么

1. 检查 Docker 引擎与 Compose v2 可用性，检查 CPU 架构兼容性。
2. 检查端口冲突（冲突时明确报错，不会抢占端口）。
3. 生成 `local.env`：随机 PostgreSQL/Redis/MinIO 密码、JWT_SECRET、模型密钥主密钥（MODEL_CONFIG_MASTER_KEY）。
4. 解析应用镜像：优先拉取 GHCR 预构建镜像（`ghcr.io/bangshou1st/ai-collab/ai-collab-backend` / `…/ai-collab-frontend`，与 CI 发布路径一致），不可用时从本地源码用 Docker 多阶段构建（容器内完成 Java/前端编译，宿主机无需任何工具链）。
5. `docker compose up -d` 启动 5 个服务：PostgreSQL(pgvector)、Redis、MinIO、后端、Nginx+前端。
6. 轮询健康检查（首次启动含 Flyway 数据库迁移，1-3 分钟）。
7. 输出访问地址与初始化提示。

## 本地拓扑与安全基线

- 入口仅绑定 `127.0.0.1`，局域网/公网无法访问；PostgreSQL/Redis/MinIO 不发布任何宿主机端口。
- 数据库/Redis/MinIO 在 `internal` 隔离网络；与开发拓扑（`ai-collab-deploy`）和生产拓扑（`172.30.173.0/24`）使用不同网段，可共存。
- Refresh Cookie 使用显式的本地 HTTP 例外（`REFRESH_COOKIE_SECURE=false` + `REFRESH_COOKIE_ALLOW_INSECURE=true`，后端有启动守卫强制两项成对出现）；生产 compose 固定 `Secure=true`，两者不会串用。
- 数据持久化在命名 Docker 卷（`ai-collab-local_postgres_data` 等）；停止/重启/重装不丢数据。
- 无 AI API Key 时，项目协作、文档管理等基础功能全部可用；AI 能力在「管理中心 → 动态配置中心」与「AI Infrastructure」界面按需配置。

## 日常维护命令

`install.ps1` 与 `install.sh` 命令语义一致：

| 命令 | 作用 |
| --- | --- |
| `status` | 各服务健康状态与入口可达性 |
| `up` / `stop` / `restart` | 启动 / 停止（数据保留）/ 重启 |
| `logs [-Tail n] [-Follow]` | 查看日志 |
| `doctor` | 环境诊断：Docker、架构、端口、配置完整性、容器健康、磁盘 |
| `backup` | 备份 PostgreSQL（pg_dump+完整性校验+SHA256）与 MinIO（镜像同步），保留策略默认 14 天 |
| `restore -Dump <file> [-Minio <archive>]` | 从备份恢复到本部署（先停 app，完成后自动重启） |
| `upgrade -Tag <版本>` | 受控升级：备份 → 核验 → 更新镜像 → 健康检查 → 失败自动回滚 |
| `version` | 仓库基线、镜像 digest、构建 revision |
| `uninstall` | 移除容器与网络（数据卷保留） |

注意：

- **停止 ≠ 删除数据**：`stop`/`uninstall` 都不会删除 Docker 卷。
- 备份的数据库 dump 与 MinIO 归档是先后生成的，**不是原子快照**；跨系统一致性以恢复演练（restore + `verify-restore-consistency.sh`）为准。
- 升级若推进了数据库迁移（Flyway），旧镜像可能无法回退运行；升级脚本会自动备份并在失败时回滚镜像，同时明确提示迁移风险。

## 服务器部署

见 [server-install.md](server-install.md)；从旧部署方式迁移见 [migration.md](migration.md)。

## 故障排查

- `install.ps1 doctor` / `install.sh doctor` 是第一步。
- 应用启动失败：`logs -Tail 200` 查看 Flyway/配置错误。
- 端口冲突：换端口重装，或释放端口（脚本会显示占用进程名）。
- 本地 Ollama：容器内通过 `host.docker.internal:11434` 访问宿主机 Ollama（默认白名单已包含）。
