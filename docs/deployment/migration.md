# 从旧部署方式迁移指南

本指南覆盖从以下旧方式迁移到统一部署体系（`install.ps1` / `install.sh`）的兼容性说明。
**迁移不改变业务功能、数据库结构与既有账号；所有方式使用同一批应用镜像。**

## 1. 开发环境（dev.ps1 / 手动源码启动）——无需迁移

`dev.ps1 start` 的工作流完全保留：本机 Java + Vite + `ai-collab-deploy/docker-compose.yml`
基础设施容器。一键部署是**面向普通用户的独立拓扑**（compose 项目
`ai-collab-local`，网段 `172.30.174.0/24`，独立数据卷），与开发拓扑互不影响：

- 开发基础设施容器名 `ai-collab-postgres` 等、卷前缀 `ai-collab-deploy_` 不变；
- 一键本地部署容器名 `ai-collab-local-*`、卷前缀 `ai-collab-local_`；
- 唯一潜在冲突是宿主机端口：一键部署默认入口 8080 与开发后端端口相同。
  两者需要共存时，一键部署换端口：`install -Port 8090`。

## 2. 旧生产部署（宿主机 pnpm build + Nginx 挂载 dist）

旧 `compose.prod.yml` 把 `../ai-collab-frontend/dist` 挂载进 proxy 容器，要求
部署前在宿主机执行前端构建。新方案改为**前端镜像内置静态资源**：

- `compose.prod.yml` 已移除 `../ai-collab-frontend/dist` 挂载；
- `PROXY_IMAGE` 从裸 Nginx 镜像改为与后端同版本的前端镜像
  （`ghcr.io/bangshou1st/ai-collab-frontend:v<版本>`，内置静态资源与 production.conf）。

迁移步骤（由运维在受控窗口执行）：

1. 确认目标版本镜像已在 GHCR 发布（或已在服务器本地构建）。
2. 修改 `prod.env`：`PROXY_IMAGE=<前端镜像引用>`。
3. `docker compose --env-file prod.env -f ai-collab-deploy/compose.prod.yml up -d proxy`
   重建 proxy 容器；随后 `docker exec <proxy容器> nginx -t` 校验。
4. 回退路径：恢复旧 `PROXY_IMAGE=nginx:...` 并重新挂载 dist 的旧 compose 文件即可，
   数据卷不受影响。

不迁移也可以：旧部署继续用旧 compose 文件照常运行，不受本仓库新文件影响；
但无法享受镜像化前端与受控升级流程。

## 3. 旧管理员初始化方式

- `bootstrap_staging_admin.mjs`（硬编码 staging URL）与 `set_admin_password.mjs`
  （硬编码密码）属于一次性演练脚本，**已被统一引导命令取代**，不再建议使用：
  - 本地：`install.ps1 bootstrap-admin` / `install.sh bootstrap-admin`
  - 服务器：`install.sh bootstrap-admin --server --domain <域名>`
- 统一引导的安全属性：仅当库内 `system_admin` 计数为 0 时执行；密码随机生成、
  仅终端显示一次；提权经 PostgreSQL 事务级咨询锁防并发抢占；引导完成后重复执行
  自动拒绝。
- 已有生产/Staging 管理员的系统不受影响：引导命令检测到管理员即直接退出。

## 4. 备份脚本

`ai-collab-deploy/backup/` 全部脚本保持原样、原参数、原安全守卫。
`install.sh backup` 只是把容器名/凭据/路径以环境变量注入后调用它们；
`install.ps1 backup`（Windows 无 bash 环境）实现了等价的 PowerShell 版本，
输出格式与保留策略一致。

## 5. Cloudflare Pages 前端

前端仓库的 `functions/`（Pages Functions 代理）与 Cloudflare Pages 构建流程
**未做任何修改**：部署配置改动仅涉及 `ai-collab-deploy/`、Dockerfile 与 CI，
前端源码与构建配置不变。使用 Cloudflare Pages 的站点照常工作；
Docker 拓扑与 Pages 是两条独立的入口路径。

## 6. CI 变化

- `ci.yml` 新增 `release` job：仅在 `main` push 或 `v*` 标签时发布镜像到 GHCR；
  PR 永不发布。
- `image` job 的隔离冒烟改为构建真实前端镜像并纳入生产拓扑验证
  （不再使用合成 dist 占位文件）。
- 既有 `production-contract` / `backend` / `frontend` job 逻辑不变。
