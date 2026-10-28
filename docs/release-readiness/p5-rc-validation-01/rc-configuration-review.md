# RC 配置审查（rc-configuration-review.md）

> 审查对象：`ai-collab-deploy/docker-compose.yml`、`.env.example`、`ai-collab-backend/Dockerfile`、
> `application.yml`（默认 profile）、R-02/R-04 已交付文档。本轮只做配置与操作可行性检查，不实际部署。

## A. TLS 与 Cookie 安全

| 项 | 状态 | 依据 |
| --- | --- | --- |
| HTTPS / TLS 终止位置 | ✅ 方案明确 | release-runbook.md §0：反向代理终止 HTTPS → `app:8080`，禁止绕过代理直曝 8080 |
| Refresh Cookie Secure | ✅ 默认安全 | Compose `REFRESH_COOKIE_SECURE: "${REFRESH_COOKIE_SECURE:-true}"`；启动守卫：非 Secure 必须显式 `REFRESH_COOKIE_ALLOW_INSECURE=true` 否则拒绝启动（R-04 已真实验证） |
| HttpOnly / SameSite | ✅ | R-04 整改范围（RefreshTokenCookieService + 测试），Cookie 属性由后端统一设置 |
| CORS | ✅ | `AUTH_ALLOWED_ORIGINS` 显式变量，`.env.example` 注明生产必须改为真实前端 Origin |
| 反代转发头信任边界 | ⚠️ `[部署环境补充]` | 代理只允许设置 `X-Forwarded-*` 的可信跳数/来源需在真实环境配置（Spring `server.forward-headers-strategy` 未在 compose 显式配置，本地冒烟不受影响）；列入部署准备清单 |

**结论**：生产模式不安全配置会被启动守卫明确拒绝（R-04 真实验证），无阻断项。

## B. 密钥

Runbook（release-runbook.md §2）已覆盖：`POSTGRES_*`、`REDIS_PASSWORD`、`MINIO_ROOT_*`、`JWT_SECRET`（≥32 字节）、`MODEL_CONFIG_MASTER_KEY`（32 字节，不可更换警告）、`REFRESH_COOKIE_*`、`AUTH_ALLOWED_ORIGINS`、`CHAT_*`。仓库内无任何真实凭据（`.env` 已 gitignore，`/backups/` 已加入 .gitignore）。`[部署环境补充]`：密钥由运维密管系统下发的具体通道。

## C. 外部模型网络

Runbook 与 rollback-runbook 已明确：外部 AI Provider（Zen/Kilo/Gemini）异常 **不触发应用回滚**（liveness 不受影响，属上游抖动）；go-no-go-decision.md 已声明上游依赖（Zen Free 限流、Kilo 免费模型可能下线、换模型处置流程）。`[部署环境补充]`：生产出网白名单清单、本地 embedding（Ollama）在生产是否启用及 `EMBEDDING_LOCAL_ALLOWED_TARGETS` 取值。

## D. 生产监控

R-02 已交付：management 基线（health 含依赖聚合、probes、敏感端点 401）、MinioHealthIndicator、RequestIdMdcFilter（请求关联 ID）、日志无凭据。诚实边界：**生产告警通道未接通**——日志收集方式（docker logs 驱动的采集器选型）、资源告警、通知渠道均为 `[部署环境补充]`，不能因本地 Actuator 可用而宣称告警就绪。

## E. 备份

R-01 已交付脚本集 + 隔离演练（54 表 + 15 对象恢复核验一致）。Runbook 明确发布前必做备份、失败即中止。`[部署环境补充]`：备份保存位置与保留周期、异地备份、备份访问权限、恢复责任人、演练周期——真实部署参数无法虚构，保留占位。

## 汇总

| 类别 | 阻断项 | 部署环境补充项 |
| --- | --- | --- |
| TLS | 无 | 反代转发头信任配置、TLS 证书 |
| 密钥 | 无 | 密管系统下发通道 |
| 模型网络 | 无 | 出网白名单、embedding 部署决策 |
| 监控 | 无 | 日志采集选型、告警通道 |
| 备份 | 无 | 备份位置/周期/异地/责任人 |
