# 验证结果（P5-RELEASE-READINESS-02）

> 执行环境：Windows 11 + Docker Desktop（WSL2）+ JDK 21。所有"真实执行"条目均在本机完成，命令与结果可复现。

## R-01 备份恢复

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| PostgreSQL 备份 | `backup-postgres.sh` 对运行中源库（ai_collab）真实执行 | PASS：520,020 字节、54 个 TABLE DATA 条目、`pg_restore --list` 校验通过、SHA256 记录 |
| MinIO 备份 | `backup-minio.sh` 对运行中源 bucket（15 个真实文档对象）真实执行 | PASS：15 对象镜像、`mc diff` 零差异、清单与统计落盘 |
| 隔离恢复演练 | `run-restore-drill.sh`：临时 pgvector/pg17 + 临时 MinIO（全新匿名卷、无端口、无业务网络）真实恢复 | PASS：54 张表恢复、15 对象恢复，结束自动清理，源数据全程只读 |
| 一致性核验 | `verify-restore-consistency.sh` | PASS：10 张关键表行数逐一相等（app_user=19、project=17、project_member=22、project_task=46、milestone=7、task_comment=2、project_document=15、document_chunk=18、ai_task_plan=10、audit_log=169）；恢复库 15 条 `object_key` 与恢复 bucket 15 对象一一对应；与源引用集合 diff 为空 |
| 守卫负向测试 | 恢复命令显式指向业务容器/业务 bucket | PASS：两次均被拒绝，exit 14，未写入任何数据 |
| 脚本静态检查 | `bash -n` 全部 7 个脚本 | PASS |

**判定：FIXED_AND_VERIFIED（含隔离环境真实恢复演练）。** 边界：演练为同机 Docker 内隔离（独立容器/网络/卷），跨机异地恢复需部署环境补充。

## R-02 可观测性

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 健康探针 | 本地运行实例 curl | PASS：`/actuator/health` 200 UP、`/health/liveness` 200 UP、`/health/readiness` 200 UP |
| 敏感端点拒绝 | 匿名访问 env/configprops/beans/heapdump/threaddump/mappings/metrics/info/logfile | PASS：全部 401，无一 200 |
| 依赖故障分类 | 真实 stop Redis 容器 → 观察 → 恢复 | PASS：整体 health 503 DOWN、liveness 仍 200 UP、恢复后自愈 UP |
| MinIO 健康接入 | 真实 stop MinIO 容器 → 观察 → 恢复（新 MinioHealthIndicator） | PASS：同上分类行为 |
| 日志无凭据 | 用标记假口令登录后 grep 日志；grep 真实 POSTGRES_PASSWORD / JWT_SECRET | PASS：标记与真实密钥均未出现在日志（计数 0） |
| 请求关联 | 请求级日志行携带 `requestId=<16hex>`；响应头回传 `X-Request-Id` | PASS |
| 单元测试 | MinioHealthIndicatorTest 3 例、RequestIdMdcFilterTest 3 例 | PASS |

**判定：FIXED_AND_VERIFIED。**

## R-03 发布流水线与 Runbook

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| Compose 配置校验 | `docker compose --env-file .env.example config -q` | PASS |
| 镜像构建验证 | CI 新增 `image` job（**PR 与 main push 触发**，dev 日常 push 不触发以省成本）：构建 Dockerfile + revision label 断言 + 起临时 pg/redis/minio 做容器级冒烟（health UP、敏感端点拒绝、版本可追踪） | CI_PENDING（将在下一次 PR→main 或 main push 时真实执行；本地已通过 compose 校验与生产形态实例真实启动佐证容器配置路径） |
| 版本标识 | 镜像 OCI label `org.opencontainers.image.revision` == commit SHA；冒烟 job 中断言 | CI_PENDING |
| Runbook | release-runbook.md / rollback-runbook.md / backup-restore-runbook.md | 已交付，含前置检查、密钥、备份、迁移、启动、健康、冒烟、停止条件、回滚、一致性核对、日志检查；待真实部署环境回填项均已标注 |

**判定：IMPLEMENTED_NOT_FULLY_VERIFIED**（镜像构建与容器冒烟的真实执行以 CI 结果为准，见 CI 记录）。

## R-04 TLS 与 Refresh Cookie

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 生产模式 Secure Cookie | 默认 profile 真实启动实例（8081）→ 真实登录 | PASS：`Set-Cookie: ...; Secure; HttpOnly; SameSite=Strict` |
| 本地 HTTP 可用 | local profile 实例（8080）真实登录 | PASS：Cookie 属性正确（无 Secure），登录/刷新链路正常 |
| 不安全生产配置拒绝启动 | 默认 profile + `REFRESH_COOKIE_SECURE=false` 真实启动 | PASS：`APPLICATION FAILED TO START`，报错含修复指引，exit 1 |
| 客户端头不影响 Cookie | 携带 `X-Forwarded-Proto: https` 登录 | PASS：Cookie 属性不变 |
| 配置绑定回归 | RefreshTokenPropertiesTest 5 例 + RefreshTokenCookieServiceTest 5 例 | PASS |
| 敏感端点/日志 | 同 R-02（Cookie 端点为同一进程） | PASS |

**判定：FIXED_AND_VERIFIED。**

## 附带发现（本轮修复）

1. **默认 profile 缺 `security.jwt` / `security.cors` 配置块**：容器化部署启动即失败（JwtProperties 绑定错误）。此前 app 容器从未被真实启动过。已补齐并经生产形态实例真实启动验证（另见 cookie-tls-security.md §4）。
2. Compose `REFRESH_COOKIE_SECURE` 硬编码 `"false"`（R-04 本体）。
3. `run-restore-drill.sh` 修复过程：MSYS 路径转换、MinIO 匿名卷权限、目标库预建冲突、核验凭据透传——均已修复并记录在脚本注释。

## 回归

- 定向回归：auth + common + storage 相关 49 个测试全部通过（BUILD SUCCESS）。
- 全量后端 `mvnw verify`、前端 lint/typecheck/test/build 由 CI 执行（前端本轮零改动，预期无影响）。
