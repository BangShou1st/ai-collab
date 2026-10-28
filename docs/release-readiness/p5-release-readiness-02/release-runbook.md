# 发布 Runbook（ai-collab）

> 适用部署形态：`ai-collab-deploy/docker-compose.yml`（app / postgres / redis / minio）+ TLS 终止代理。
> 本 Runbook 是通用可执行流程；标注 `[部署环境补充]` 的条目需要在真实生产环境确定后回填。
> 配套文档：[backup-restore-runbook.md](backup-restore-runbook.md) · [rollback-runbook.md](rollback-runbook.md)

## 0. 前置条件（一次性）

| 项 | 要求 |
| --- | --- |
| 服务器 | 已安装 Docker Engine + Compose v2；系统时间同步（NTP） |
| TLS | 反向代理（如 Nginx/Caddy）终止 HTTPS，转发到 `app:8080`；**不允许绕过代理直接暴露 8080** |
| 镜像 | 本项目无公共镜像仓库；生产主机从 Git 检出后本地构建（CI 的 image job 已验证构建路径），或自建私有 registry 后改用 `image:` 引用 `[部署环境补充]` |
| 密钥 | 按 §2 准备 `.env`；所有密钥由运维密管系统下发，禁止入 Git |
| 通知 | 发布窗口通知团队 `[部署环境补充]` |

## 1. 发布前检查（全部通过才继续）

```bash
# 1.1 CI 状态：目标 commit 的 Backend / Frontend / Image 三个 job 必须全绿
gh run list --branch main --limit 1

# 1.2 版本标识：记录本次发布的 commit SHA（镜像 label 与之一致）
git rev-parse HEAD

# 1.3 迁移预检：比对本版与现网的迁移文件差集，人工确认每条新增迁移的可回滚性
git diff --stat <现网SHA> HEAD -- ai-collab-backend/src/main/resources/db/migration/

# 1.4 磁盘余量：数据卷所在分区剩余空间 > 现网数据体积 × 2
docker system df -v
```

停止条件：CI 不全绿、存在不可理解的迁移、磁盘不足——中止发布。

## 2. 环境变量与密钥准备

以 `.env.example` 为基线逐项核对生产 `.env`，重点：

| 变量 | 生产要求 |
| --- | --- |
| `POSTGRES_*` / `REDIS_PASSWORD` / `MINIO_ROOT_*` | 全部为强随机值，不得保留模板占位符 |
| `JWT_SECRET` | ≥32 字节强随机 |
| `MODEL_CONFIG_MASTER_KEY` | 32 字节 AES 主密钥，**一旦使用不可更换**（已加密数据将不可解） |
| `REFRESH_COOKIE_SECURE` | `true`（默认；Compose 透传） |
| `REFRESH_COOKIE_ALLOW_INSECURE` | `false`（默认；生产不得改 true） |
| `AUTH_PUBLIC_REGISTRATION_ENABLED` | 按运营策略 |
| `AUTH_ALLOWED_ORIGINS` | 生产必须显式设为前端站点 Origin（逗号分隔），默认值仅适用于本机 |
| `DEMO_OWNER_*` | 仅 local profile 生效，生产 `.env` 无需配置 |
| `CHAT_*` / `EMBEDDING_*` | 若启用，API Key 由密管系统注入 |

校验配置合法性：

```bash
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml config -q
```

## 3. 数据备份（发布前必做）

```bash
ai-collab-deploy/backup/backup-postgres.sh
ai-collab-deploy/backup/backup-minio.sh
```

两条命令均须退出码 0，并记录产物文件名（回滚 Runbook 需要用）。**备份失败即中止发布。**

## 4. 构建镜像与标识

```bash
git checkout <发布SHA>
SHA_SHORT=$(git rev-parse --short=12 HEAD)
docker build -f ai-collab-backend/Dockerfile \
  -t ai-collab-backend:rc-$SHA_SHORT \
  --label org.opencontainers.image.revision=$(git rev-parse HEAD) \
  .
# 版本追踪：docker inspect 的 revision label == 发布 commit
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ai-collab-backend:rc-$SHA_SHORT
```

前端为静态产物：`cd ai-collab-frontend && pnpm install --frozen-lockfile && pnpm build`，由部署环境的静态服务器/代理托管 `[部署环境补充：托管路径]`。

## 5. 数据库迁移

- 迁移由 Flyway 在应用启动时自动执行（容器启动即迁移，幂等、带 checksum 校验）。
- **手动预演（推荐）**：把新镜像指向生产库的副本（或演练恢复实例）启动一次，观察 Flyway 日志无错误后再正式发布。
- 失败处理：容器启动失败且日志含 `FlywayException` 时，**不要反复重启**（避免半完成迁移叠加）；按 rollback-runbook.md 处理，必要时修复迁移后用新 commit 重走发布。

## 6. 应用启动 / 更新

```bash
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d --no-deps --no-recreate app
docker compose -f ai-collab-deploy/docker-compose.yml ps
```

## 7. 健康检查（发布成功的必要条件）

```bash
# 整体健康（含 postgres/redis/minio 依赖聚合）
curl -fsS http://127.0.0.1:8080/actuator/health          # 期待 {"status":"UP"}（HTTP 200）
# 存活探针（仅进程存活）
curl -fsS http://127.0.0.1:8080/actuator/health/liveness # 期待 UP
# 敏感端点拒绝
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/env   # 期待 404/401，绝不 200
```

## 8. 冒烟测试（代表性路径）

1. **认证**：浏览器打开前端 → 登录 → 刷新页面保持登录（Refresh Cookie 生效；HTTPS 下 Cookie 应带 Secure）。
2. **项目协作**：打开项目列表 → 进入任一项目 → 任务看板创建/移动一个任务。
3. **知识库**：上传一个测试文档 → 解析分块完成 → RAG 提问得到带引用回答。
4. **Agent**：发起一次只读 Agent Run，确认 SSE 事件正常收尾。
5. **权限**：用普通成员账号确认无权限操作返回 403。

停止条件：任一步骤失败且回滚成本低于修复成本 → 执行 rollback-runbook.md。

## 9. 发布成功的判定条件

- [ ] §7 三个健康检查全部符合预期
- [ ] §8 五项冒烟全部通过
- [ ] `/actuator/health/liveness` 持续 UP ≥ 15 分钟
- [ ] 日志无 ERROR 级刷屏、无密钥/凭据输出（`docker logs app --since 15m | grep -iE 'ERROR|password|apikey|secret'` 复核）
- [ ] 镜像 revision label == 发布 commit

## 10. 发布后观察

- T+30min：`docker compose ps` 全部 healthy；`docker logs app --since 30m` 抽查。
- T+24h：复核 `ai_call_log` 无异常错误率；备份脚本照常执行并抽查产物。
