# P4.1 备份恢复演练证据（隔离环境，2026-10-30）

- 工具：`ai-collab-deploy/backup/backup-postgres.sh`、`backup-minio.sh`、`run-restore-drill.sh --backup-dir`（备份→全新匿名卷临时实例恢复→一致性核验→强制清理，源侧只读）。
- 隔离性：合成源容器（`p41-src-pg` / `p41-src-minio`）与本机业务容器、Staging/生产完全无关；演练实例无端口、无业务网络；结束后强制删除，源容器只读不写。演练凭据均为一次性合成值。
- 源数据：真实 V1–V70 迁移 SQL 顺序应用后的完整 schema + 最小合成行（app_user/project/project_member/project_document 各 1 行）+ MinIO `ai-collab/drill/marker.txt`。

## 结果

- PostgreSQL 备份：`pg_ai_collab_20261029T171930Z.dump`，234758 字节，69 个数据条目，`pg_restore --list` 完整性校验 + SHA256 通过。
- MinIO 备份：`minio/ai-collab/`，1 个对象，`mc diff` 源/副本零差异 + 对象清单。
- 恢复：临时实例恢复 69 张表、1 个对象。
- 一致性核验：关键表行数逐一比对 OK（app_user=1、project=1、project_member=1、project_document=1，其余 0）；DB↔MinIO 对象引用 1 条且全部在恢复 bucket 中找到。
- 退出码 0：`备份 -> 隔离恢复 -> 一致性核验 全部成功（源业务数据全程只读）`。

完整控制台输出：本机 `target/p41-precheck/drill-a-output.log`（不入库）。Staging 实际部署时须在同一流程下对真实实例重跑一次并归档输出。
