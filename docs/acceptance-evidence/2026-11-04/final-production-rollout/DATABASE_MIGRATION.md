# Flyway V77 迁移预检与执行记录（DATABASE_MIGRATION）

- 日期：2026-11-04
- 目标数据库：正式站后端 PostgreSQL 17.11（`box-aae54f`，库 `ai_collab_staging`，历史命名含 staging，实为正式站生产库，见 `PRE_DEPLOY_AUDIT.md`）
- 迁移文件：`ai-collab-backend/src/main/resources/db/migration/V77__shared_ai_provider_and_admin_system.sql`（dev 新增，未改历史迁移）

## 1. 迁移前状态（实测）

```
flyway_schema_history: 76 rows, all success=true
latest: 76 | external document revision object key | true
PostgreSQL: 17.11 (Debian 17.11-1.pgdg12+2)
```

关键既有对象（`pg_tables` 实测）：`user_ai_provider`、`user_model_purpose_assignment` 存在；`shared_ai_provider`、`admin_notification` 不存在。数据基线：35 用户 / 20 项目 / 24 任务 / 12 个人 Provider / 1 用途绑定 / 1 管理员。

## 2. V77 内容逐项兼容性分析

| 语句 | 语义 | 对生产数据的影响 | 风险 |
| --- | --- | --- | --- |
| `CREATE TABLE shared_ai_provider` | 全站共享供应商表，含类型/状态/输出上限/温度 CHECK、唯一 source 约束、状态索引 | 新表，不触碰既有数据 | 无 |
| `CREATE TABLE admin_notification` | 管理员通知表，级别 CHECK、未读/去重索引 | 新表，不触碰既有数据 | 无 |
| `ALTER TABLE user_model_purpose_assignment DROP CONSTRAINT IF EXISTS ..._provider_id_fkey` | 解除原先"只能绑定个人 provider"的 FK | 既有 1 条用途绑定行保留；约束删除只改元数据 | 低 |
| `ADD COLUMN IF NOT EXISTS is_shared BOOLEAN NOT NULL DEFAULT FALSE` | 既有行全部标记为个人绑定 | PostgreSQL 17 对带常量默认值的加列不重写全表；无长锁 | 低 |
| `CREATE OR REPLACE FUNCTION trg_cascade_user_ai_provider_delete()` + trigger | 删除个人供应商时清理 `is_shared=FALSE` 的用途绑定，接替原 FK 的 `ON DELETE CASCADE` 语义 | 只定义删除路径，升级期间不删除任何供应商 | 低 |
| `CREATE OR REPLACE FUNCTION trg_cascade_shared_ai_provider_delete()` + trigger | 删除共享供应商时清理 `is_shared=TRUE` 的用途绑定 | 同上；新表尚无数据 | 低 |
| `DROP TRIGGER IF EXISTS ...` × 2 | 幂等重放保护 | 对生产首次执行为 no-op | 无 |

**无** `DROP TABLE`、`TRUNCATE`、`UPDATE`、`INSERT`、删除列、改列类型、重建管理员账号、覆盖项目数据或写入任何 Provider 凭据。不会把 Staging/本地测试数据复制到生产。

### 特别检查结论

- **原有个人模型不丢失**：`user_ai_provider` 12 行、模型配置与 `model_configuration` 均不动。
- **原有用途分配不丢失**：`user_model_purpose_assignment` 1 行保留，新增列默认 `FALSE` 正确归类为个人绑定。
- **管理员账号没有被重建**：迁移中无任何 `app_user` 语句。
- **已有项目数据不受影响**：`project` / `project_member` / `project_task` / `project_document` 等均不在迁移范围内。
- **外键及触发器语义正确**：个人侧由 FK cascade 改为 AFTER DELETE 触发器（按 `is_shared=FALSE` 精确匹配），共享侧按 `is_shared=TRUE` 精确匹配；两类绑定不会互相误删。
- **锁表风险**：新表/索引创建与加默认列均为短时元数据锁；用途绑定表仅 1 行，即使触发重写也可忽略。
- **向后兼容**：V77 全是新增结构，旧镜像不会引用新表/列；Flyway 对"库版本新于镜像已知版本"只输出 WARN（仓库历史 V69 镜像 + V70 库演练已验证）。

## 3. 不可逆性与回滚原则

- Flyway 迁移不因镜像回退而自动撤销；**回滚旧镜像 ≠ 数据库回滚**。
- 若 V77 执行成功但应用不健康，优先按 `BACKUP_AND_ROLLBACK.md` 方案 A 回退应用（旧镜像兼容 V77 结构）与 Pages 前端。
- 若 V77 执行失败或出现数据异常，停止写入、保留全部卷，先在隔离实例用本次备份恢复并对账，由发布负责人独立决策；禁止手工改 checksum、禁止降级数据库、禁止用测试库覆盖生产库。

## 4. 执行方式

- Flyway 由 Spring Boot 应用启动时自动执行（`spring-boot-starter-flyway` + `flyway-database-postgresql`；compose 中无独立 flyway job）。
- 升级顺序：备份（已完成）→ 拉取新镜像 digest → 仅重建 `app` 容器 → 观察日志中 V77 应用记录 → 查询 `flyway_schema_history` → 健康检查 → 再发布 Pages 前端。
- 不手工执行 SQL、不改历史迁移文件。

## 5. 执行后核验（发布阶段填写）

| 检查 | 预期 | 实测 |
| --- | --- | --- |
| `flyway_schema_history` 行数 | 77 | 待执行 |
| V77 `success` | true | 待执行 |
| `shared_ai_provider` / `admin_notification` 表 | 存在 | 待执行 |
| `user_model_purpose_assignment.is_shared` | 存在且既有行 = FALSE | 待执行 |
| 用户/项目/任务/Provider/用途绑定/管理员数量 | 35/20/24/12/1/1（不减少） | 待执行 |
| 两个级联触发器 | 存在 | 待执行 |
| 应用 health | UP | 待执行 |
