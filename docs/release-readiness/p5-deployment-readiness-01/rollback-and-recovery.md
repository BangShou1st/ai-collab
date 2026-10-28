# 回滚与恢复（rollback-and-recovery）

> 权威文档：`docs/release-readiness/p5-release-readiness-02/rollback-runbook.md` 与 `backup-restore-runbook.md`（脚本 `ai-collab-deploy/backup/`）。本文只做部署视角的整合与边界声明，不重复步骤。

## 1. 两类处置必须严格区分

| 场景 | 处置 | 数据影响 |
| --- | --- | --- |
| **应用版本回滚**（新版本故障、迁移兼容） | 换回上一 SHA 镜像：`docker compose ... stop app` → `docker run -d --name ai-collab-app --restart unless-stopped --network ai-collab-deploy_default -p 8080:8080 --env-file .env ai-collab-backend:rc-<上一版本SHA>`（rollback-runbook §2） | 不动数据。前提：新增迁移对旧代码兼容（Flyway 只增不改历史）；若新迁移删除/重命名旧代码依赖的列，**必须**走数据恢复路径 |
| **数据库数据恢复**（数据损坏、迁移不可前滚） | 隔离恢复流程（backup-restore-runbook §3-4）：停 app 止血 → 临时 pgvector/MinIO 容器（`--network none`，不发布端口）→ `restore-postgres.sh` / `restore-minio.sh` → `verify-restore-consistency.sh` → 决策"前滚修复"（首选）或"恢复快照切换"（接受备份点后增量丢失） | 接受增量丢失；`[部署环境补充：快照切换机制]` |

## 2. 硬性红线（`DOCUMENTED`，脚本内置守卫）

- **PostgreSQL 与 MinIO 必须作为整体恢复**：`project_document.object_key` 与 MinIO 对象一一对应，不允许"只恢复库"或"只恢复对象"。
- 禁止在生产库手工回退 Flyway checksum / 删除迁移记录 / drop / truncate（脚本对业务源容器默认拒绝恢复，exit 14；`ALLOW_SOURCE_OVERWRITE=1` 为显式高危解锁）。
- Redis 不需要恢复：仅承载限流/锁，重启容器即可（rollback-runbook §4）。

## 3. 回滚触发条件（rollback-runbook §1）

- 应用无法启动（反复重启 / Flyway 迁移失败）。
- `/actuator/health` 持续 DOWN 且原因在本次变更范围。
- 核心冒烟失败且 15 分钟内无法定位修复。
- **例外**：仅外部 AI Provider（Zen/Kilo/Gemini）异常不回滚——liveness 与整体健康不受影响，等待恢复或切换 Provider。

## 4. 回滚后必做

1. 复跑五项冒烟（deployment-procedure 步骤 10）。
2. `docker logs app --since 30m` 无 ERROR。
3. 一致性核对：关键表行数、`object_key` ↔ MinIO 对象一一对应（`verify-restore-consistency.sh`）。
4. 事件记录：根因、时间线、数据影响、后续修复；**修复以新 commit 走正常发布流程，禁止在回滚版本上热改**。

## 5. 验证状态与未验证项

| 项 | 状态 |
| --- | --- |
| 备份 + 隔离恢复演练（同机 Docker，2026-10-24 实测：54 表 + 15 对象、10 关键表行数相等、15 条 object_key 一一对应、负向 exit 14 通过） | `VERIFIED`（backup-restore-runbook） |
| **异机/异地恢复** | `NOT_VERIFIED`——演练与源同机；跨机恢复需部署环境提供目标主机后补充演练（open-questions Q4） |
| 生产真实回滚演练（含快照切换） | `NOT_VERIFIED`——首次执行属部署日动作 |
| 镜像保留策略（`rc-<sha>` 镜像留存 N 版） | `ENVIRONMENT_DEPENDENT`——回滚依赖上一版本镜像存在，主机磁盘需保留最近 2–3 个版本镜像 |
| MODEL_CONFIG_MASTER_KEY / JWT_SECRET 离线备份归档 | `NOT_VERIFIED`——计划提及，脚本集未实现；**master key 丢失 = 加密数据永久不可解**，首次部署前必须落实 |
