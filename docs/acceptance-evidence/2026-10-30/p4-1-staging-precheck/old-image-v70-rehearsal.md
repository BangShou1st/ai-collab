# P4.1 旧镜像 × V70 回滚演练证据（隔离环境，2026-10-30）

- 旧镜像：基线 `f7b528a` 构建的 `p41-old-app:baseline`（仅含 V69）；新镜像：`p41-new-app:head`（V70）。
- 数据库：全新 Postgres（pgvector:pg17），由新镜像经 Flyway 迁移至 V70（`flyway_schema_history` 70 行、max version 70），新镜像健康 UP 后停止。

## 结果

1. 旧镜像直接启动（无任何 Flyway 开关）：**启动成功**。Flyway 日志：`Successfully validated 70 migrations`；`Schema "public" has a version (70) that is newer than the latest available migration (69)!` 仅为 WARN；随后 `Schema "public" is up to date. No migration necessary.`
2. 旧镜像功能冒烟（同一 V70 库）：健康 UP；`/api/v1/auth/register` SUCCESS；`/api/v1/auth/login` SUCCESS；`POST /api/v1/projects` SUCCESS（创建项目成功）；`GET /api/v1/projects/{id}/knowledge/sessions` SUCCESS。V70 增量结构对旧代码无影响。
3. 代码事实：旧镜像不含任何屏蔽守卫（`KnowledgeVisibilityGuard`、`StorageAuthorizationService`、`AgentHistoryVisibilityService`、`/internal/storage-authorize` 均为本轮新增）——回滚期间被屏蔽内容的安全边界必须由代理层维持，相关入口默认关闭的要求见 `docs/deployment/staging-rollout-p4-1.md` 回滚计划。

结论：旧镜像回滚**不需要** `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`；回滚时按方案关闭知识相关入口即可维持安全边界。本演练全部使用一次性合成数据与独立容器，结束后已清理。
