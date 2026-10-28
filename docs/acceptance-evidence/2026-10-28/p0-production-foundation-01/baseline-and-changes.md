# 基线、差距与变更

## 开工事实

- `git fetch --no-tags origin dev main` 后，本地及远端 dev 均为 `87817b54245be98bc3a3bd2b1781a6a3ecd098dc`；`main` 为 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`。远端有现存 Draft PR #18（`dev → main`），本轮未改动。
- 原 Compose 已有数据卷、健康检查、Dockerfile 和备份/恢复；但开发网络发布 5432/6379/9000/9001/8080，不能直接作为生产配置。应用运行用户已有非 root 设置；`production` 配置只信任固定 Nginx 地址。
- 现有认证包括 JWT、Origin、Secure Cookie 与后端权限控制；演示账号初始化只属于 `local` profile。Embedding 由管理端加密配置，历史 G-1 已关闭，未重复改造。
- 用户在实施中要求开放公开注册，已覆盖原设计中默认关闭的决定。注册用户是普通用户；实际代码没有生产首位系统管理员引导流程，不能将测试中直接插入的合成管理员当作生产方案。
- P5/P7 的反代真实 IP、TLS、前端托管、生产秘密、调度/异机恢复、真实告警、Image required check 均未因历史验收而自动闭环。

## 文件级变更

| 路径 | 原因 |
| --- | --- |
| `ai-collab-deploy/compose.prod.yml`、`nginx/production.conf`、`prod.env.example` | 独立生产网络、只暴露 HTTPS、固定信任锚、反向代理及带签名的私有对象下载；动态地址池避让代理固定地址。 |
| `ai-collab-backend/Dockerfile`、`src/main/resources/application-production.yml`、`application.yml` | 固定构建/运行基础镜像，production profile 的 Tomcat 原生受信代理及公开下载 Origin / 桶管理开关。 |
| `MinioProperties.java`、`MinioDocumentStorageGateway.java` 与对应测试 | 桶由管理员预建、应用使用独立最小权限账号；对公开 Origin 签名并使用同源代理路径，保持内部端口不公开。 |
| `AuthRateLimiter.java` | 修正文档注释，明确只信任生产拓扑代理，不改变限流算法。 |
| `backup/{common.sh,backup-*,restore-*,verify-restore-consistency.sh,run-restore-drill.sh}` | 查询和对账失败不再误判通过；限制对象查询为只读单表单列 SELECT、校验数据库及 bucket 名；临时文件隔离清理、不打印对象键；凭据不出现在 Docker CLI 参数；UTC 时间戳；Git 100644 脚本显式由 bash 调用；客户端以备份用户 UID/GID 访问目录。 |
| `backup/{scheduled-backup.sh,monitor.sh,emit-alert.sh,systemd/}` | 快照、互斥、保留、告警/异机复制适配点及 Linux 调度模板；真实通道由运维配置。 |
| `ai-collab-deploy/scripts/` 下 `prod-*`、`p0-*`、`backup-safety-contract.sh` | 配置负例、合成证书、隔离 HTTPS/代理/SSE、端口、日志、故障及恢复的可重复检查。 |
| `.github/workflows/ci.yml` | 新增所有 CI 事件运行的生产配置/备份契约 Job；镜像 Job 增加隔离生产拓扑和恢复测试。 |
| `docs/deployment/production-foundation.md`、`backup/README.md`、`docs/README.md` | 网络与密钥边界、生产部署顺序、运维、恢复与回滚；保留外部阻断。 |
| 本目录 | 本轮执行记录和脱敏页面截图；未复制真实证书、Token、数据库或文档。 |

## 缺陷与处理

1. 原恢复对账把两边同为 `QUERY_FAILED` 视为相等；原 MinIO `mc diff` 静默失败可能被视为成功。均改为失败退出，并以模拟 Docker 失败验证。
2. 首次隔离拓扑中应用动态地址抢占了代理固定 IP，Docker 返回 `Address already in use`。为动态容器指定 `.128/25` 地址池后，真实拓扑重跑通过；按用户后续要求开放注册，并验证新用户不获得系统管理员权限。
3. 宿主备份目录可能属于不同于 MinIO 镜像默认 UID 65532 的运行账号。备份/恢复临时客户端改用当前 UID/GID 与可写临时配置目录；Windows Docker Desktop 上已复测，Linux 目标环境仍须实测其目录权限。
4. 历史脚本时间戳用本地时间却附带 `Z`，改为 UTC；演练脚本原直接执行 Git 中的 100644 shell 文件，改为显式 `bash`。
5. 初次合成恢复演练运行时修改了正在执行的脚本，产生 shell 解析错误；停止编辑后重跑通过。没有将该失败算成通过。
