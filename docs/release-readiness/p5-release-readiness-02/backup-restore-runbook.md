# 备份与恢复 Runbook（R-01）

> 脚本与参数详见 [ai-collab-deploy/backup/README.md](../../../ai-collab-deploy/backup/README.md)，本文记录运维决策与演练结论。

## 1. 备份策略

| 项 | 约定 |
| --- | --- |
| 频率 | 每日一次（建议业务低峰），发布前追加一次手动备份 |
| PostgreSQL | `pg_dump -Fc` 自定义格式（含 pgvector 表结构与数据），`pg_restore --list` 校验 + SHA256 |
| MinIO | `mc mirror` 到宿主机目录，`mc diff` 校验零差异 + 对象清单 |
| 保留 | `BACKUP_RETENTION_DAYS=14`（过期自动删除） |
| 产物 | `<仓库根>/backups/`（gitignore，不含任何代码目录） |
| 监控 | 脚本退出码非 0 即告警 `[部署环境补充：接入现有告警通道]` |

**数据库与对象的关联是整体备份的**：`project_document.object_key` 唯一关联 MinIO 对象，两侧必须取自同一次演练/发布窗口的备份，不允许"只恢复库"或"只恢复对象"。

## 2. 恢复的硬性安全约束

1. 恢复目标必须显式指定（容器 + 库名 / 实例 + bucket）。
2. 业务源容器/实例/同名 bucket 默认拒绝恢复（exit 14）；`ALLOW_SOURCE_OVERWRITE=1` 是高危显式解锁。
3. 目标库必须不存在（脚本自检），恢复不覆盖既有数据。
4. 凭据仅经环境变量/.env 传递，不落日志与终端。
5. 严禁在生产库直接执行手工回退（drop/truncate/Flyway 篡改）。

## 3. 标准恢复流程（隔离环境）

```bash
# 3.1 恢复数据库到隔离容器（建议全新临时容器，无端口、无业务网络）
docker run -d --name restore-target-pg --network none \
  -e POSTGRES_DB=seed -e POSTGRES_USER=restore -e POSTGRES_PASSWORD=<临时口令> \
  pgvector/pgvector:pg17
POSTGRES_RESTORE_USER=restore POSTGRES_PASSWORD_RESTORE=<临时口令> \
  ai-collab-deploy/backup/restore-postgres.sh \
  --file backups/postgres/pg_ai_collab_<时间戳>.dump \
  --container restore-target-pg --db ai_collab

# 3.2 恢复对象到隔离 MinIO（独立实例或独立 bucket）
docker run -d --name restore-target-minio --network none \
  -e MINIO_ROOT_USER=restore -e MINIO_ROOT_PASSWORD=<临时口令> -v /data \
  <与源一致的 minio 镜像> server /data
MINIO_RESTORE_USER=restore MINIO_RESTORE_PASSWORD=<临时口令> \
  ai-collab-deploy/backup/restore-minio.sh \
  --dir backups/minio/<bucket> --container restore-target-minio --bucket <bucket>

# 3.3 一致性核验（关键表行数 + object_key ↔ 对象文件对应）
POSTGRES_RESTORE_USER=restore POSTGRES_PASSWORD_RESTORE=<临时口令> \
MINIO_RESTORE_USER=restore MINIO_RESTORE_PASSWORD=<临时口令> \
  ai-collab-deploy/backup/verify-restore-consistency.sh \
  --pg-container restore-target-pg --pg-db ai_collab \
  --minio-container restore-target-minio --bucket <bucket>
```

## 4. 演练结论（2026-10-24 实测）

`run-restore-drill.sh` 一键演练在本机真实执行通过：

- 备份：`pg_dump` 520,020 字节、54 个 TABLE DATA 条目、SHA256 记录在案；MinIO 15 个对象镜像 + `mc diff` 零差异。
- 隔离恢复：临时 pgvector/pg17 容器恢复 54 张表；临时 MinIO 恢复 15 个对象。
- 一致性：10 张关键业务表行数逐一相等（app_user=19、project=17、project_member=22、project_task=46、milestone=7、task_comment=2、project_document=15、document_chunk=18、ai_task_plan=10、audit_log=169）；恢复库 15 条 `object_key` 与恢复 bucket 15 个对象一一对应。
- 负向验证：恢复脚本指向业务容器/业务 bucket 时被守卫拒绝（exit 14）。
- 源业务数据全程只读。

**真实恢复演练：已执行（EXECUTED）。** 边界：演练在与源同机的 Docker 内进行（隔离靠独立容器/网络/卷保证），未覆盖"异机异地恢复"；跨机恢复需要部署环境提供目标主机后补充演练。
