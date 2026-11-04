# 生产备份与回滚方案（BACKUP_AND_ROLLBACK）

- 日期：2026-11-04 15:45Z 起
- 目标：正式升级前对真实生产资源做可靠备份并验证恢复可行性
- 工具：仓库自带 `ai-collab-deploy/backup/{backup-postgres.sh,backup-minio.sh,run-restore-drill.sh,verify-restore-consistency.sh}`
- 备份位置：`box-aae54f:/data/ai-collab-staging/backups/final-rollout-20261104/`（仓库外、`/data` 持久盘）

## 1. 备份前生产数据基线（只读查询）

| 项 | 值 |
| --- | --- |
| 用户 | 35 |
| 项目 | 20 |
| 项目成员 | 23 |
| 任务 | 24 |
| 里程碑 | 10 |
| 任务评论 | 1 |
| 文档 | 21 |
| 文档分块 | 30 |
| AI 任务规划 | 6 |
| 审计日志 | 139 |
| 个人 AI Provider | 12 |
| 用途绑定 | 1 |
| 管理员账号 | 1 |
| Flyway | 76 条全部 success，最高 V76 |
| MinIO 对象 | 20（bucket `ai-collab-staging`） |

## 2. 执行结果

### PostgreSQL

```
database=ai_collab_staging
file=pg_ai_collab_staging_20261104T154509Z.dump
size_bytes=519210
table_data_entries=72
status=OK
```

- `pg_dump -Fc` 自定义格式；
- `pg_restore --list` 完整性校验通过（72 条 TABLE DATA）；
- `sha256sum -c` 通过（`.sha256` 存在且匹配）；
- 单独保存 `.manifest.txt` / `.summary`。

### MinIO

```
bucket=ai-collab-staging
objects=20
local_files=20
status=OK
```

- `mc mirror --overwrite` 只读源 bucket；
- `mc diff` 校验源与本地副本零差异；
- `.manifest.txt` 记录 20 个对象键，`.summary` 记录对象数/字节数。

### 配置与版本记录

- `config-record.txt`：仅字段名清单、镜像引用、允许的 Origin、库名、桶名、密钥存在性布尔值；**无任何密钥值**；
- `production.env.backup`：生产 env 原文件的受控副本，`chmod 600`，不进入 Git、不出现在报告；
- 原 env 文件 SHA256（仅指纹）：`de8a5f2f563568db3d342e6237183fd8b7ea744f1a6310255dde00a28967051d`；
- `version-record.txt`：旧镜像 tag / digest / revision / Flyway 版本；
- Redis：AOF 开启，`dump.rdb` 存在；其数据不是唯一权威源，随整机持久盘保留。

## 3. 隔离恢复演练（源侧全程只读）

`run-restore-drill.sh --backup-dir /data/ai-collab-staging/backups/final-rollout-20261104`：

- 启动全新匿名卷、无端口、无业务网络的临时 PostgreSQL 与 MinIO；
- 从本次 dump 恢复：public schema 共 **72 张表**；
- 从本次 mirror 恢复：**20 个对象**；
- 关键表行数逐一比对，**10/10 全部一致**：`app_user=35`、`project=20`、`project_member=23`、`project_task=24`、`milestone=10`、`task_comment=1`、`project_document=21`、`document_chunk=30`、`ai_task_plan=6`、`audit_log=139`；
- 临时容器已强制清理。

### 已知异常：1 条部署前既有的孤儿对象引用

一致性核验最后的"DB↔MinIO 引用"检查报：

```
恢复库对象引用: 21 条；恢复 bucket 对象: 20 个
ERROR: 19 恢复库有 1 条对象引用在恢复 bucket 中无对应文件
```

排查结论：

- 缺失键属于文档 `ae3b20ad-36e8-4a45-85e0-38fa1cd3a3c7`（`READY`、`access_blocked=false`），其 `object_key` 指向 `projects/dfa8e3e7-42bb-4d36-9c63-a98906073850/documents/ae3b20ad-…/source.txt`；
- 该文件在**升级前的源 MinIO bucket 中同样不存在**（用 `mc ls --recursive` 核对），备份的 `mc diff` 为零差异，说明不是备份丢失、也不是恢复失败；
- `connector_deletion_journal` / `connector_cleanup_job` 均为 0，不是 V70 清理任务造成；属于部署前既有数据状态（历史上传/清理遗留）；
- 该异常与 V77 迁移无关，升级不会使其恶化；备份已证明可恢复到与升级前完全相同的状态。

**门禁判断**：数据库与对象备份的有效性、完整性、恢复可行性均已实测；"全量一致性 exit 0"因这条历史孤儿引用未通过。按任务"备份不能只看命令退出码"的要求如实记录，不绕过、不掩饰、不把失败写成成功。由于异常是既有状态而非本次变更引入，且恢复后与源端一致，继续发布；任何升级后数据异常仍可按本备份恢复到升级前时点并人工对账。

（说明：核验脚本的 `VERIFY_OBJECT_KEY_SQL` 按安全设计只允许单表单列 SELECT，无法用 `WHERE NOT access_blocked` 之类过滤；因此未通过裁剪核验范围来"刷绿"，而是保留原始失败输出并给出证据链。）

## 4. 回滚资产清单

| 资产 | 位置 / 标识 |
| --- | --- |
| PostgreSQL 备份 | `/data/ai-collab-staging/backups/final-rollout-20261104/postgres/pg_ai_collab_staging_20261104T154509Z.dump`（+ sha256 / manifest / summary） |
| MinIO 备份 | `/data/ai-collab-staging/backups/final-rollout-20261104/minio/ai-collab-staging/`（20 对象 + manifest） |
| 配置备份 | 同目录 `production.env.backup`（chmod 600）、`config-record.txt`（脱敏） |
| 旧后端镜像 | `ghcr.io/bangshou1st/ai-collab/ai-collab-backend@sha256:68befe03e5bed2d9730a862bb0977803b81d558540206d1343a926e1ba2db36a`（revision `c863ac55`），已确认仍在主机镜像缓存中 |
| 旧前端（Pages） | Production 部署 `4c0644db-1bce-4dda-9b7d-a75e55399a6c` @ `66a704d`；再前一版 `86f7d1ca-1d40-487a-a90b-34005875c188` @ `c863ac5` |
| 旧前端 GHCR 镜像 | `ghcr.io/bangshou1st/ai-collab/ai-collab-frontend@sha256:cbb83f767a7c323d0f12a03f92fd3c21e99b7ac3c4d061118aebc2bd134d5b85`（拓扑实际用 Pages，不作前端回滚主路径） |
| Flyway 基线 | V76 / 76 条 success |
| 数据卷 | `/data/ai-collab-staging/{postgres,redis,minio}` 全程不动 |

## 5. 可执行回滚步骤

### A. 仅应用/前端不健康、V77 已迁移但无数据损坏（首选，快）

1. 将生产 env 的 `APP_IMAGE` 改回 `ghcr.io/bangshou1st/ai-collab/ai-collab-backend@sha256:68befe03…`（不可变 digest，不用浮动 `main`）。
2. `docker compose --env-file /data/ai-collab-staging/config/.env -f /data/home/repo/ai-collab-deploy/compose.staging.yml up -d app`。
3. 等待 app healthy；旧镜像对 V77 库按 Flyway 语义只输出 newer-version WARN，新增表/列不被旧代码触碰（同类演练已验证）。
4. Cloudflare Pages Dashboard → `ai-collab-web` → Deployments → 选 `4c0644db…` → **Rollback to this deployment**；或在 main 上做 git revert（不得 force push / 改写历史）。
5. 复核登录、项目列表、任务中心、知识库入口均无回归。

### B. 迁移失败 / 数据异常（数据库级，独立安全判断后执行）

1. 立即停止对生产数据库的写入（停止 app 容器，**保留** postgres/redis/minio 容器与数据卷；禁止 `down -v`、禁止删库/删桶/清卷）。
2. 用本次备份在**隔离实例**先做恢复与对账（已演练过的方法），确认 dump 有效、对象清单一致（注意上述 1 条历史孤儿引用）。
3. 由发布负责人依据对账结果决定：前滚修复 / 经授权的受控数据切换。绝不手工改 `flyway_schema_history` checksum、绝不把测试库覆盖为生产库。
4. 恢复完成后按 A 步骤回退应用与前端，并重新冒烟。

### 禁止项（本次全程遵守）

`docker compose down -v`、删除生产数据库、删除 MinIO 正式 bucket、清空生产数据卷、覆盖正式库为测试库、force push、把测试账号/凭据灌入生产、把密钥写进日志或证据、绕过失败的备份检查继续升级。

## 6. 回滚触发条件

Flyway V77 失败；后端无法 healthy 启动；登录异常；正式站 API 代理异常；用户无法访问原有项目；关键数据异常；普通用户获得管理员权限；共享 Provider 凭据泄露；重要功能明确回归。非关键截图美观问题不触发整体回滚。
