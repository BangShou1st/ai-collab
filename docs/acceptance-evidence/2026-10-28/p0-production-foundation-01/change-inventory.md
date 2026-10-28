# 本轮逐文件变更

路径相对于仓库根；未修改历史 Flyway 迁移，未新增业务表。

## 应用与生产入口

| 文件 | 作用 |
| --- | --- |
| `ai-collab-backend/src/main/resources/application-production.yml` | 只信任生产 Nginx 固定地址的 Tomcat 转发设置。 |
| `ai-collab-backend/Dockerfile` | 将构建及运行基础镜像固定到 digest。 |
| `ai-collab-backend/src/main/resources/application.yml` | 增加 MinIO 公开签名 Origin 与桶管理开关。 |
| `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/ratelimit/AuthRateLimiter.java` | 更新代理信任的代码注释。 |
| `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/storage/MinioProperties.java` | 增加公开签名及桶管理配置。 |
| `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/infrastructure/storage/MinioDocumentStorageGateway.java` | 可关闭自动桶管理，并为同源下载生成签名。 |
| `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/storage/MinioHealthIndicatorTest.java` | 同步扩展后的配置构造。 |
| `ai-collab-backend/src/test/java/com/shitulelv/aicollab/document/infrastructure/storage/MinioDocumentStorageGatewayTest.java` | 验证桶权限和不外呼的公开 URL 签名。 |
| `ai-collab-deploy/compose.prod.yml` | 隔离网络、地址池、固定镜像、默认开放公开注册和最小权限凭据。 |
| `ai-collab-deploy/nginx/production.conf` | TLS、静态文件、转发头、SSE、只读签名对象代理。 |
| `ai-collab-deploy/prod.env.example` | 无真实秘密的生产配置模板。 |

## 备份与监控

| 文件 | 作用 |
| --- | --- |
| `ai-collab-deploy/backup/common.sh` | 数据库和 bucket 名校验。 |
| `ai-collab-deploy/backup/backup-postgres.sh` | UTC 时间戳与不在 CLI 参数中传密码。 |
| `ai-collab-deploy/backup/backup-minio.sh` | 校验失败不误判、清理临时输出、UID/GID 兼容和不在 CLI 参数中传密码。 |
| `ai-collab-deploy/backup/restore-postgres.sh` | 校验目标名、降低密码参数暴露。 |
| `ai-collab-deploy/backup/restore-minio.sh` | 校验目标名、UID/GID 兼容、清单失败不可误判、减少输出。 |
| `ai-collab-deploy/backup/verify-restore-consistency.sh` | 查询失败拒绝通过、只读 SQL、隔离临时文件、隐藏对象键。 |
| `ai-collab-deploy/backup/run-restore-drill.sh` | 显式 bash 调用、UTC 时间戳、默认复用源数据库镜像。 |
| `ai-collab-deploy/backup/scheduled-backup.sh` | 互斥、独立快照、受限保留及复制/告警 hook。 |
| `ai-collab-deploy/backup/emit-alert.sh` | 仅发送固定事件类别的可替换告警适配。 |
| `ai-collab-deploy/backup/monitor.sh` | 健康、磁盘与持续错误检查。 |
| `ai-collab-deploy/backup/systemd/ai-collab-backup.service` | Linux 备份服务模板。 |
| `ai-collab-deploy/backup/systemd/ai-collab-backup.timer` | 每日备份计划模板。 |
| `ai-collab-deploy/backup/systemd/ai-collab-monitor.service` | Linux 监控服务模板。 |
| `ai-collab-deploy/backup/systemd/ai-collab-monitor.timer` | 周期检查计划模板。 |
| `ai-collab-deploy/backup/README.md` | 更新执行方式、限制及调度指引。 |

## 契约、测试与 CI

| 文件 | 作用 |
| --- | --- |
| `ai-collab-deploy/scripts/prod-config-check.mjs` | 校验生产参数、证书和环境覆盖，不打印秘密。 |
| `ai-collab-deploy/scripts/prod-config-check.test.mjs` | 生产配置的正例、负例及合成证书测试。 |
| `ai-collab-deploy/scripts/prod-compose.sh` | 配置检查后的受限生产 Compose 入口。 |
| `ai-collab-deploy/scripts/prod-compose-contract.mjs` | 检查 Compose 实际展开的网络和安全配置。 |
| `ai-collab-deploy/scripts/backup-safety-contract.sh` | 模拟数据库、对象对账、互斥和告警失败。 |
| `ai-collab-deploy/scripts/p0-isolated-compose-smoke.sh` | 创建并清理随机命名的合成拓扑，串联真实隔离测试和恢复。 |
| `ai-collab-deploy/scripts/p0-https-test.mjs` | 注册、权限、Cookie、过期 Token、上传和签名下载。 |
| `ai-collab-deploy/scripts/p0-proxy-ip-test.mjs` | 两来源 IP、伪造头、登录和注册限流。 |
| `ai-collab-deploy/scripts/p0-sse-test.mjs` | SSE 首帧、中止和断线续传。 |
| `ai-collab-deploy/scripts/p0-dependency-outage.sh` | 仅限随机测试项目的依赖停机与恢复。 |
| `ai-collab-deploy/scripts/p0-port-scan.mjs` | 检查隔离容器的宿主机端口绑定。 |
| `ai-collab-deploy/scripts/p0-log-scan.mjs` | 不输出内容地扫描合成凭据和文档标记。 |
| `.github/workflows/ci.yml` | 增加生产契约 Job、镜像拓扑验收及手动触发镜像 Job。 |

## 文档与本轮证据

| 文件 | 作用 |
| --- | --- |
| `README.md` | 区分本地与生产配置并说明公开注册。 |
| `docs/README.md` | 增加生产指南入口。 |
| `docs/deployment/production-foundation.md` | 配置、部署、信任边界、运维和回滚指南。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/README.md` | 本轮裁决与索引。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/baseline-and-changes.md` | 基线、差距和失败修复。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/change-inventory.md` | 此文件。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/test-matrix.md` | 测试方法、结果与未执行事项。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/open-items-and-rollback.md` | 未闭环门禁及回滚边界。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/login-page-http-bridge.png` | 受控 HTTP 桥接的登录页面。 |
| `docs/acceptance-evidence/2026-10-28/p0-production-foundation-01/registration-open-http-bridge.png` | 受控 HTTP 桥接的开放注册页面。 |
