# Linux 服务器部署指南

面向服务器管理员：单台 Linux 服务器 / VPS，Docker Compose 拓扑（非 Kubernetes），
域名 + HTTPS，正式镜像来自 GHCR，不在服务器上编译任何源码。

## 前置条件

- Ubuntu LTS / Debian / RHEL 系，x64 架构（arm64 需模拟层，不推荐）。
- 已安装 Docker Engine + Compose v2 插件（[官方安装脚本](https://docs.docker.com/engine/install/)）。
- 一个解析到服务器公网 IP 的域名（A/AAAA 记录）。
- 受信任的 TLS 证书与私钥（如 Let's Encrypt `fullchain.pem` / `privkey.pem`，由运维在宿主机准备与续期）。
- 磁盘剩余 ≥ 5GB。
- 放行入站 TCP 443（或自定义 HTTPS 端口）；**不要**放行 5432/6379/9000/9001——数据面容器不发布任何公网端口。
- **镜像为 GHCR Private**：服务器需先 `docker login ghcr.io`（GitHub 用户名 + 具有 `read:packages`
  权限的 PAT）才能拉取预构建镜像；未登录时安装脚本回退为源码构建（服务器上仍无需 Java/Maven/Node）。
  公开镜像需仓库所有者在 GitHub Packages 设置中手动改为 Public。

## 一键安装

```bash
cd ai-collab
sudo ./ai-collab-deploy/install.sh install --server \
    --domain aicollab.example.org \
    --tls-cert /srv/ai-collab/tls/fullchain.pem \
    --tls-key  /srv/ai-collab/tls/privkey.pem
# 自定义端口（例如 8443）：追加 --port 8443

# 首位管理员（凭据只显示一次）
sudo ./ai-collab-deploy/install.sh bootstrap-admin --server --domain aicollab.example.org

# 验证
./ai-collab-deploy/install.sh status --server --domain aicollab.example.org
```

安装脚本会：

1. 检查 Docker/Compose、磁盘空间（≥5GB）、端口占用（强制 443，非 443 拒绝并指引 Staging）、证书文件存在性。
2. 生成 `ai-collab-deploy/prod.env`（权限 600，含随机凭据，已被 `.gitignore` 排除）；镜像解析为 digest 固定引用（注册表 digest 或本地镜像 ID）。
3. **执行严格生产配置检查**（`server_strict_check`，bash+openssl 实现，无 node 依赖）：镜像可追溯、密钥强度与互异、Origin/端口一致性、TLS 证书/私钥匹配、未过期与域名匹配；node 可用且镜像为注册表 digest 时叠加官方 `prod-config-check.mjs`。检查未通过则拒绝部署；修改配置后可单独复检：`./install.sh server-check --server --domain <域名>`。
4. 拉取 GHCR 预构建镜像（或源码构建兜底），`docker compose config -q` 校验渲染。
5. 启动 `compose.prod.yml` 拓扑：
   - `postgres` / `redis` / `minio` 位于 `internal` 数据网络，无公网端口；
   - `app`（Spring Boot）在 edge+data 双网络，别名 `app-edge`；
   - `proxy`（前端镜像：Nginx + 内置静态资源）仅发布 `HTTPS_PORT`。
6. 初始化 MinIO 桶与应用专用账号（桶级最小权限策略，幂等）。
7. 轮询 HTTPS 健康检查并输出访问地址。

## 安全基线（与本地模式的差异）

| 项目 | 本地 | 服务器 |
| --- | --- | --- |
| 入口 | `127.0.0.1` HTTP | `0.0.0.0` HTTPS（TLS 终止于 Nginx） |
| Refresh Cookie | 显式 HTTP 例外 | 固定 `Secure=true`，不可关闭 |
| MinIO 下载 | 同源 `/storage` 代理（回环 HTTP） | 同源 `/storage` 代理（HTTPS 域名） |
| 公开注册 | 默认开 | 默认开，可在管理界面关闭 |
| 端口发布 | 仅 127.0.0.1 入口 | 仅 HTTPS 端口 |

安全设计不变项：Nginx 是唯一受信转发方（后端 `production` profile 只信任
`172.30.173.2` 固定代理地址）；存储下载经 `auth_request` 鉴权；操作端点不对外。
不要在前面叠加额外 CDN/反向代理（会破坏固定代理信任锚），如需调整须同步修改
`application-production.yml` 的 `internal-proxies` 并重跑安全测试。

## 定时备份

复用仓库自带 systemd 单元（见 [backup/README.md](../../ai-collab-deploy/backup/README.md)）：

```bash
sudo cp ai-collab-deploy/backup/systemd/ai-collab-backup.{service,timer} /etc/systemd/system/
# 按需编辑 service 中的环境变量（容器名、BACKUP_ROOT、保留天数）
sudo systemctl daemon-reload && sudo systemctl enable --now ai-collab-backup.timer
```

手动备份/恢复：`./ai-collab-deploy/install.sh backup --server --domain ...`、
`restore --dump <file.dump> --minio <dir>`。数据库与 MinIO 备份非原子快照，
重要变更前建议同时执行一次恢复演练（`run-restore-drill.sh`，隔离目标）。

## 受控升级

```bash
./ai-collab-deploy/install.sh upgrade --tag v1.2.0 --server --domain aicollab.example.org
```

流程：升级前自动备份（数据库 + 对象存储）并核验 → 解析并拉取目标版本镜像 →
更新容器 → 健康检查冒烟 → 失败自动回滚镜像（数据卷不动）→ 记录升级历史到
`backups/local/upgrade-history.log`。

**数据库迁移不可自动回退**：若目标版本包含新的 Flyway 迁移，回滚镜像后旧版本可能
无法运行。升级脚本会检测迁移推进并在历史中记录；生产升级务必先确认备份可用，
必要时先在 Staging（`compose.staging.yml`）演练。

## 版本与追溯

- `version` 命令输出仓库基线、镜像 digest 与构建 revision（OCI label）。
- 发布镜像标签：`v<版本>`、`sha-<commit>`；`main` 分支构建另发 `main` 标签。
  验收以 digest 为准，不要依赖浮动 `latest`。

## 故障排查

- `doctor`：Docker、架构、配置完整性、容器健康、磁盘。
- `logs --server --domain ... --tail 200`：查看应用/Nginx 日志。
- 证书续期后：`docker compose --env-file prod.env -f compose.prod.yml restart proxy`。
- Nginx 配置校验：`docker exec <proxy容器> nginx -t`。
