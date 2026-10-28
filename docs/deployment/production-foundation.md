# P0 生产部署基础（待环境验收）

本文描述仓库交付的部署路径，不构成生产上线批准。生产域名、真实证书、权限、告警接收和异机恢复均须在目标环境实测；当前验收状态见 `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/`。

## 拓扑及信任边界

使用独立的 `ai-collab-deploy/compose.prod.yml`，不要与开发 `docker-compose.yml` 叠加。Nginx 是唯一公开入口，直接终止 HTTPS，静态目录来自构建后的 `ai-collab-frontend/dist`；`/api/v1/` 转发应用。PostgreSQL、Redis、MinIO、应用不发布宿主机端口；MinIO 控制台不可从公网直接访问。Docker 宿主机和具有 Docker 管理权限的主体仍可访问容器，主机防火墙、IPv4/IPv6 路由及外网扫描必须单独验证。

仅支持**客户端直接连接此 Nginx**：它覆盖客户端转发头，Tomcat 的 production profile 只信任 `172.30.173.2`。生产网段与地址在 Compose 和 `application-production.yml` 中成对固定；部署主机若冲突，必须同步调整并重跑两来源 IP、伪造头、直连和 SSE 测试。上游 CDN、负载均衡、代理或多台 Nginx 不属于此信任模型，不能简单叠加。`server.forward-headers-strategy=framework` 不能替代信任边界。

私有对象下载使用短期签名 URL：应用先对同一 HTTPS Origin 的原始桶路径签名，再在返回地址加 `/storage`；Nginx 去掉前缀后仅转发 GET/HEAD 到 MinIO，由 MinIO 校验签名和应用账号的桶权限。该路径关闭 Nginx 访问日志及错误日志；令牌仍可能出现在浏览器历史、外部网关或其他日志中，切勿将签名 URL 写入证据。应用账号不得具有其他桶权限。生产桶必须预建为 `us-east-1`；不同 region 需先修改签名配置并验证，不能静默部署。

## 生产配置与凭据

1. 在仓库外的受控目录（例如 `/etc/ai-collab/`）从 `ai-collab-deploy/prod.env.example` 创建环境文件，属主为部署管理员、权限 0600。该模板只含占位符，原样校验必然失败。
2. `APP_IMAGE` 与 `PROXY_IMAGE` 使用已审核的不可变 `@sha256:` digest；PostgreSQL、Redis、MinIO 在生产 Compose 中也按 digest 固定，Dockerfile 的构建及运行基础镜像同样固定。记录构建源码 commit、镜像 digest、前端 dist 校验和和配置版本，升级需审查并验证目标 CPU 架构支持。占位模板不指定可被误当成生产凭据的默认值；固定基础镜像并不保证依赖仓库及构建产物完全可复现。
3. PostgreSQL、Redis、MinIO 管理员及应用密码各自使用密码管理器生成的独立高强度随机值；JWT 和模型主密钥各使用至少 32 随机字节编码为不少于 43 个 base64url 字符。检查器只能识别格式、重复值和常见占位符，**不能证明随机熵**。
4. `AUTH_ALLOWED_ORIGINS` 是一个规范 HTTPS Origin；生产入口固定 443，使用其他端口须重新设计并验证转发端口和跳转。准备匹配该域名、未过期的证书和私钥，续签后重新检查并安全重载代理。按用户要求，生产默认开放公开注册（`AUTH_PUBLIC_REGISTRATION_ENABLED=true`），必要时可在受控环境中关闭；不初始化 local 演示管理员，Cookie 固定 Secure/HttpOnly/SameSite=Strict。
5. 模型凭据由管理员在系统中配置并使用 `MODEL_CONFIG_MASTER_KEY` 加密入库；保管并离线加密备份原主密钥。改变该环境变量不会自动迁移已有密文，遗失密钥将使原凭据不可解密。禁止把实际密码、API Key、备份或证书私钥写入仓库或验收证据。

运行 `node ai-collab-deploy/scripts/prod-config-check.mjs /etc/ai-collab/production.env`。它校验实际生效的进程环境覆盖、密钥格式、账号分离、精确 HTTPS Origin、证书域名/有效期与私钥匹配，**不验证公共 CA 证书链或自动续签**；随后用 `bash ai-collab-deploy/scripts/prod-compose.sh /etc/ai-collab/production.env config` 检查 Compose。不要将包含秘密的 `docker compose config` 完整输出写入终端日志或工单。

Docker 环境变量、容器 inspect、Docker 管理接口和宿主机特权进程仍能看到注入的机密；环境变量不是密钥隔离机制。备份脚本将密码通过环境传给 Docker CLI 而非放进 `-e NAME=value` 参数，但仍不能抵御宿主机或 Docker 管理员读取。限制账号、Docker socket 和证据访问；具备更安全的密钥注入方案时应按目标平台设计并重新验证。

## 首次部署顺序（由获授权运维执行）

1. 核对变更对应 CI、新旧镜像 digest、Flyway 兼容性、容量和回滚窗口；创建并核实部署前备份，保留已验证的旧镜像及配置。不要在数据库迁移前宣称可以无条件回退旧镜像。
2. 用锁文件部署脚本在干净构建环境执行 `pnpm install --frozen-lockfile` 与 `pnpm build`，为 dist 记录 SHA；生产目录对代理只读。
3. 执行上述配置检查。使用相同 env 文件及生产 Compose **仅启动 postgres、redis、minio**，等健康检查通过；应用使用的私有桶需由管理员预先创建，不允许匿名访问。建立独立应用账号，仅对该桶授予 `s3:ListBucket`、`s3:GetBucketLocation` 和对象级 `s3:GetObject`、`s3:PutObject`、`s3:DeleteObject`；MinIO 管理与恢复使用独立权限和受控流程。生产应用不会创建桶或修改桶策略。实际 MinIO 版本、权限策略和凭据下发须在预发布实测。
4. 再运行 `bash ai-collab-deploy/scripts/prod-compose.sh /etc/ai-collab/production.env up`。等待依赖、Flyway 与健康检查就绪；应用 liveness 与整体 health 的意义不同，模型 Provider 不进入 health。应用失败时查看脱敏日志，不要手动改 Flyway checksum。
5. 从外部测试入口、端口、TLS、认证、授权、下载、SSE、依赖故障与恢复；配置监控、备份和外部告警接收。检查匿名 `/actuator/env` 等不可访问、实际证书链及自动续签。通过前保持 `NO_GO` 或 `PARTIAL_ACCEPTANCE`。

**空库首位管理员尚无生产引导流程。** 公开注册创建的是普通用户，不会授予系统管理员权限；生产 profile 也不运行 local 演示初始化器。在已有管理员可安全迁入，或另行实现并验证受控引导方案之前，空库不能完成正式交付。不要通过临时启用演示账户、将明文口令写入仓库或绕过授权来规避该门禁。

公开注册保留现有每来源 IP 每小时 10 次限流与后端字段校验；该功能目前不要求邮箱验证，真实部署需确认账号滥用监测、限制策略和运营责任。代理来源 IP 处理必须按前述拓扑实测，避免所有注册者共用计数或伪造来源绕过限制。

## 备份与监控

`ai-collab-deploy/backup/systemd/` 提供 Linux systemd 计时器模板；先按环境修订 `/opt/ai-collab` 路径、运行账号与 `/etc/ai-collab/backup.env`。该环境文件须显式提供 `BACKUP_ROOT`、`POSTGRES_CONTAINER/DB/USER/PASSWORD`、`MINIO_CONTAINER/ROOT_USER/ROOT_PASSWORD/BUCKET` 及监控用 `MONITOR_APP_CONTAINER`；生产调度入口不回退到仓库 `.env`。需要 `flock`、Docker CLI、访问 Docker 的权限与专用备份目录。备份客户端以运行账号的 UID/GID 访问备份目录，目录应归该账号所有、权限 0700；Docker 组通常具有等同宿主机 root 的权限，不应误认为普通低权限账号。启动服务前先人工执行一次并检查返回码、日志、备份和目标空间。

`scheduled-backup.sh` 使用互斥锁、单次独立目录与 14 天默认保留；不完整目录留待人工调查。`BACKUP_COPY_HOOK` 是运维提供的绝对路径可执行文件，接收完成快照目录并负责安全复制、异机验证及独立保留；未配置时仅发出 `backup_copy_unconfigured`，不会伪装成异地备份成功。`ALERT_HOOK` 接收固定事件名，不应输出凭据。未配置真实接收人时，告警仅写本机日志，属于环境依赖。`monitor.sh` 周期检查应用 health、备份盘使用率及五分钟内 ERROR 数，阈值可调；它不能替代完整监控和容量规划。

Compose 容器日志限制为每个容器 5 个、每个 10 MiB；systemd/journald 日志需在目标主机设置并验证容量与保留时长，例如由管理员配置 `SystemMaxUse` 和 `MaxRetentionSec`。该仓库不会自动修改宿主机日志策略。

PostgreSQL dump 与 MinIO mirror 是两个时点，跨系统不构成原子快照。应安排静默窗口或额外一致性策略，再按 `backup/README.md` 在隔离环境恢复并核对对象引用；演练与备份间源侧变更会影响行数对账。RPO ≤24 小时、RTO ≤2 小时、至少保留 14 天是目标，只有目标环境实际测量才能确认。异机复制及恢复未实测前保持阻断。

## 回滚

触发条件包括启动/健康失败、授权回归、关键功能不可用、迁移失败或错误率持续超阈值。先停止发布并保留脱敏证据；数据库迁移向后兼容且已验证时，仅在目标环境使用记录的旧镜像 digest 回退应用并重新冒烟。迁移不兼容或数据损坏时，先在独立实例恢复并对账，由负责人决定前滚或经授权的数据切换；镜像回滚不等于数据库恢复。不要改历史迁移、手工改 checksum、覆盖源卷或执行 `docker compose down -v`。

GitHub 管理员需在仓库 Rules/Branch protection 中，将现有工作流 `CI` 的 `Image build & container smoke` 设为保护 `main` 的 required check，并保持 `Backend (Maven verify)` 和 `Frontend (pnpm)` 必需；该设置不由本次脚本修改，须在实际 PR 界面复核。
