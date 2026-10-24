# ai-collab 备份与恢复工具集

面向 `ai-collab-deploy/docker-compose.yml` 部署形态的 PostgreSQL（pgvector）与 MinIO 备份、恢复和一致性核验脚本。全部为幂等、只读源的安全操作：**备份永不写业务数据；恢复默认拒绝指向业务容器。**

## 脚本清单

| 脚本 | 作用 | 退出码 |
| --- | --- | --- |
| `backup-postgres.sh` | `pg_dump -Fc` 自定义格式备份 + `pg_restore --list` 完整性校验 + SHA256 + 保留策略 | 0 成功 / 3 容器不可用 / 10 dump 失败 / 11 完整性失败 / 20 校验和失败 |
| `backup-minio.sh` | `mc mirror` 对象备份 + `mc diff` 完整性校验 + 对象清单 | 0 成功 / 3 容器不可用 / 12 mirror 失败 / 13 diff 有差异 |
| `restore-postgres.sh` | 恢复 dump 到**显式指定**的目标容器/新库；拒绝目标库已存在、拒绝业务源容器 | 0 成功 / 3 参数不可用 / 14 守卫拒绝 / 15 恢复失败 / 16 自检失败 |
| `restore-minio.sh` | 恢复备份目录到**显式指定**的目标实例/bucket；拒绝业务源实例同名 bucket | 0 成功 / 3 参数不可用 / 14 守卫拒绝 / 17 恢复失败 |
| `verify-restore-consistency.sh` | 关键表行数比对 + 恢复库 `project_document.object_key` 与恢复 bucket 对象一一对应核验 | 0 一致 / 3 参数不可用 / 18 行数不一致 / 19 引用不一致 |
| `run-restore-drill.sh` | 一键演练：全新备份 → 隔离临时实例恢复 → 一致性核验 → 清理 | 0 通过 / 见子脚本 |

## 配置（环境变量，均可回退到仓库根 `.env`）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `POSTGRES_CONTAINER` / `MINIO_CONTAINER` | `ai-collab-postgres` / `ai-collab-minio` | 业务源容器 |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | 取 `.env` | 业务源连接信息 |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_BUCKET` | 取 `.env` | 业务源 MinIO 信息 |
| `MINIO_IMAGE` | 源容器镜像 | 一次性 mc 容器所用镜像（与运行版本一致） |
| `BACKUP_ROOT` | `<仓库根>/backups` | 备份输出根目录（已在 `.gitignore`） |
| `BACKUP_RETENTION_DAYS` | `14` | 超期备份文件自动删除，`0` 关闭 |
| `POSTGRES_RESTORE_USER` / `POSTGRES_PASSWORD_RESTORE` | 回退源配置 | 恢复目标容器凭据 |
| `MINIO_RESTORE_USER` / `MINIO_RESTORE_PASSWORD` | 回退 `MINIO_ROOT_*` | 恢复目标实例凭据 |
| `ALLOW_SOURCE_OVERWRITE` | `0` | **高危**：显式解除"禁止恢复进业务容器"守卫 |
| `VERIFY_KEY_TABLES` / `VERIFY_OBJECT_KEY_SQL` | 核心业务表 / `project_document.object_key` | 核验范围可调 |

## 日常备份（建议 cron / 计划任务）

```bash
ai-collab-deploy/backup/backup-postgres.sh && ai-collab-deploy/backup/backup-minio.sh
```

产物结构：

```
backups/
  postgres/pg_<db>_<时间戳>.dump{,.sha256,.manifest.txt,.summary}
  minio/<bucket>/{对象目录树}
  minio/<bucket>.manifest.txt   # 对象清单（恢复后核对）
  minio/<bucket>.summary        # 对象数/字节数统计
```

## 恢复演练（一条命令）

```bash
ai-collab-deploy/backup/run-restore-drill.sh
```

隔离性保证：临时 PostgreSQL / MinIO 容器使用全新匿名卷、不发布端口、不接业务网络；源侧只读导出；结束强制清理临时容器。演练通过标准：关键表行数一致，且恢复库中每条 `object_key` 都能在恢复 bucket 中找到对应对象。

## 恢复到指定目标（手动场景）

```bash
# 数据库：目标必须是一个隔离容器（如临时演练容器），目标库必须不存在
ai-collab-deploy/backup/restore-postgres.sh \
  --file backups/postgres/pg_ai_collab_<时间戳>.dump \
  --container <目标容器> --db <新库名>

# 对象：目标必须是隔离实例或独立 bucket
ai-collab-deploy/backup/restore-minio.sh \
  --dir backups/minio/<bucket> \
  --container <目标MinIO容器> --bucket <目标bucket>

# 一致性核验
ai-collab-deploy/backup/verify-restore-consistency.sh \
  --pg-container <目标容器> --pg-db <新库名> \
  --minio-container <目标容器> --bucket <目标bucket>
```

## 安全说明

- 凭据仅经环境变量 / `.env` 传入，只进一次性容器进程环境，不写日志、不落终端输出。
- 恢复类脚本三重守卫：目标容器/实例必须显式指定；目标库必须不存在；目标为业务源容器时直接退出（exit 14）。
- 备份恢复均不发布宿主机端口；mc 操作经目标容器自身网络命名空间（127.0.0.1）执行。
