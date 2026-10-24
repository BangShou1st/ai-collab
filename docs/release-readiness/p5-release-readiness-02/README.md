# P5-RELEASE-READINESS-02：发布运维阻断项整改

> 上游审计：[p5-release-readiness-01](../p5-release-readiness-01/)（裁决 `RELEASE_CONDITIONAL_GO`）。
> 本轮目标：把审计识别的四项发布运维 P1 转为有实现、有验证、有操作说明的可交付状态，不开发新业务功能。

## 风险状态总览

| 风险 | 状态 | 一句话结论 |
| --- | --- | --- |
| R-01 备份恢复缺演练 | **FIXED_AND_VERIFIED** | 脚本集 + 一键隔离演练，真实执行：恢复后关键表行数与 DB↔MinIO 对象引用全部核验一致 |
| R-02 生产可观测性 | **FIXED_AND_VERIFIED** | 管理端点基线 + MinIO 健康 + 请求关联 ID；敏感端点全 401；停依赖实测分类正确；未新增依赖 |
| R-03 发布流水线/Runbook | **IMPLEMENTED_NOT_FULLY_VERIFIED** | CI 新增镜像构建+容器冒烟 job（随本推送执行）；发布/回滚/备份三份 Runbook 就绪，环境相关项已标注 |
| R-04 Refresh Cookie TLS 基线 | **FIXED_AND_VERIFIED** | Compose 默认 Secure + 启动守卫（不安全配置拒绝启动）+ 本地 HTTP 不受影响，全部真实验证 |

详见 [risk-status.json](risk-status.json) 与 [verification-results.md](verification-results.md)。

## 文档目录

| 文件 | 内容 |
| --- | --- |
| [backup-restore-runbook.md](backup-restore-runbook.md) | 备份策略、恢复硬约束、标准恢复流程、演练结论（脚本详见 `ai-collab-deploy/backup/README.md`） |
| [observability-review.md](observability-review.md) | R-02 改造清单、安全边界、故障诊断方式 |
| [release-runbook.md](release-runbook.md) | 发布前检查 → 密钥 → 备份 → 构建 → 迁移 → 启动 → 健康 → 冒烟 → 判定条件 |
| [rollback-runbook.md](rollback-runbook.md) | 回滚停止条件、应用回滚、数据不一致处理、回滚后核对 |
| [cookie-tls-security.md](cookie-tls-security.md) | R-04 调用链、各环境行为、验证证据、附带发现的部署缺陷 |
| [verification-results.md](verification-results.md) | 四项风险的逐条验证方式与结果 |
| [risk-status.json](risk-status.json) | 机器可读状态 |

## 改动范围

- **后端**：`RefreshTokenProperties`（启动守卫）、`application.yml`（management/logging/security.jwt/security.cors）、`application-local.yml`、`MinioHealthIndicator`、`RequestIdMdcFilter`，均为最小必要改动，无新依赖。
- **部署**：`docker-compose.yml`（Cookie/CORS 环境变量基线）、`ai-collab-deploy/backup/`（新脚本集）、`.env.example`、`.github/workflows/ci.yml`（image job）。
- **前端**：零改动。
- **业务语义**：Refresh Token 生命周期、审批链路、认证流程均未改变。

## 遗留与边界

- 镜像构建/容器冒烟以 CI 实际运行为准（本轮推送触发）。
- 跨机异地恢复演练、真实生产部署、生产告警通道接入：需部署环境信息后补充（Runbook 已预留 `[部署环境补充]` 标注）。
