# 容器级真实冒烟结果（container-smoke-results.md）

> 依据：run 40103355487 的 `Image build & container smoke` Job（ID 120899851810）日志原文，
> 已归档至 [docs/acceptance-evidence/2026-10-24/p5-rc-validation-01/](../../acceptance-evidence/2026-10-24/p5-rc-validation-01/pr-ci-40103355487-image-job.log)。

## 一、冒烟环境

- 独立 Docker 网络 `smoke`，四个临时容器：`smoke-pg`（pgvector/pgvector:pg17）、`smoke-redis`（redis:7-alpine + requirepass）、`smoke-minio`（chainguard/minio 按 digest 固定）、`smoke-app`（本次构建镜像）。
- 全部使用合成测试密码（`smoke-only` / `smoke-only-minio` / 假 JWT secret / 假 master key）；无业务数据卷、无生产数据库、无真实用户凭据、不依赖任何真实模型 API Key。

## 二、任务书十项验证逐条结果

| # | 验证项 | 结果 | 证据（CI 日志） |
| --- | --- | --- | --- |
| 1 | 应用容器启动成功 | ✅ | `smoke-app` 从本次构建镜像启动；步骤整体退出码 0 |
| 2 | Flyway 迁移执行正确 | ✅ | 迁移在应用启动时执行；就绪等待循环内 `/actuator/health` 返回 `UP`（健康聚合含 DataSource，迁移失败将导致启动失败而非 UP） |
| 3 | 数据库连接正常 | ✅ | 同上——health UP 蕴含 DataSource 建链成功 |
| 4 | Redis 连接正常 | ✅ | 同上——health 聚合含 Redis 检查 |
| 5 | MinIO 健康检查正常 | ✅ | 同上——R-02 交付的 `MinioHealthIndicator` 已纳入 health 聚合 |
| 6 | `/actuator/health` 返回 UP | ✅ | `curl -fsS .../actuator/health \| grep -q '"UP"'` 通过 |
| 7 | `/actuator/health/liveness` 返回 UP | ✅ | `curl -fsS .../actuator/health/liveness \| grep -q '"UP"'` 通过 |
| 8 | 敏感 Actuator 端点拒绝匿名读取 | ✅ | 日志原文输出：`actuator/env=401 actuator/metrics=401 actuator/info=401`，断言 `[ "$code_env" != "200" ] && ...` 通过 |
| 9 | 镜像 revision label 正确 | ✅ | `docker inspect` 取回 revision = `GITHUB_SHA`（merge commit `281fdbee…`），断言通过 |
| 10 | 应用异常时 CI 能明确失败 | ✅（设计验证） | 就绪等待 60×2s 上限后 `docker logs --tail 80` 并 `exit 1`；健康/安全/版本三组断言任一失败即 fail 整个步骤 |

时间线佐证：冒烟步骤 14:39:51 启动（真实拉取 pgvector 镜像层）→ 14:40:16 打出 actuator 断言结果并正常结束——依赖容器启动、应用启动、Flyway 迁移、四项断言在 25 秒内真实完成，与 GitHub runner 的高带宽及空库迁移规模相符。

## 三、"单元测试 ≠ 真实镜像启动" 声明

本 Job 验证的是**真实 Dockerfile 构建出的镜像在隔离容器中的启动与依赖联通**，与 Backend Job 的 1688 个 JVM 测试相互独立、互不替代；两者在本 run 中同时通过。

## 四、Flyway 全量初始化口径

- CI 冒烟为全新空库 → V1–V66 一次性全量执行成功（health UP 为必要非充分证据，但结合 Backend Job 中 `AgentMigrationIntegrationTest` / `NextStageMigrationPostgresTest` 等真实 PostgreSQL 迁移断言（container test guard 确认未跳过），迁移正确性有双重证据）。
- 未发现任何迁移问题，无需用临时库复现。
