# 生产部署流程（deployment-procedure）

> 复用既有 Runbook（`p5-release-readiness-02/release-runbook.md`、`backup-restore-runbook.md`、`rollback-runbook.md`），本文按任务要求展开为 14 步，标注前置条件、命令、成功判据与失败处置。未确认环境参数一律 `[部署环境补充]`。
> 全程约束：不合并 PR #18 之外擅自操作 main；镜像与发布 SHA 一一对应；备份失败即中止。

## 步骤总览

| # | 步骤 | 阻断性 |
| --- | --- | --- |
| 1 | 确认目标 SHA 与发布批准 | 阻断 |
| 2 | 备份 PostgreSQL 与 MinIO | 阻断 |
| 3 | 验证备份文件与对象清单 | 阻断 |
| 4 | 准备生产环境变量与密钥 | 阻断 |
| 5 | 构建或取得对应 SHA 镜像 | 阻断 |
| 6 | 准备 TLS 与反向代理 | 阻断 |
| 7 | 数据库迁移兼容性检查 | 阻断 |
| 8 | 启动基础设施与应用 | — |
| 9 | 验证健康端点 | 阻断 |
| 10 | 业务冒烟（登录/项目/知识库/Agent） | 阻断 |
| 11 | 引用、审批与权限边界检查 | 阻断 |
| 12 | 日志与告警检查 | — |
| 13 | 确认备份任务运行 | — |
| 14 | 发布结论 | — |

---

### 1. 确认目标 SHA 与发布批准
- 前置：PR #18 已由用户批准并人工合并（本轮禁止代合并）；目标 SHA = 合并后 `main` HEAD。
- 命令：`git fetch && git rev-parse origin/main`；`gh pr view 18`。
- 判据：记录 SHA；PR 合并前三 Job 全绿（Backend / Frontend / Image）——Image Job 暂非 required check（N-01），**人工核对为强制项**。
- 失败处置：CI 任一红 → 中止，修复后重走。

### 2. 备份（首次部署为空库则记录"无现网数据"并跳过恢复基线）
- 命令：`bash ai-collab-deploy/backup/backup-postgres.sh`、`bash ai-collab-deploy/backup/backup-minio.sh`（配置经环境变量或仓库根 `.env`）。
- 判据：两脚本退出码均 0，产物在 `[部署环境补充：BACKUP_ROOT]`，记录文件名。
- 失败处置：**备份失败即中止发布**（release-runbook §3）。

### 3. 验证备份
- 判据：`*.sha256` 校验通过；`pg_restore --list` 可解析；MinIO `mc diff` 零差异 + 对象清单（脚本内置）。
- 失败处置：重跑备份；连续失败 → 停止发布并排查（退出码语义见 `ai-collab-deploy/backup/README.md`）。

### 4. 准备环境变量与密钥
- 前置：G-1 修复已合并（compose 补 `EMBEDDING_*`/`PLANNING_*` 透传）；N-02 决策已落定。
- 操作：按 secrets-and-configuration.md §2 制作生产 `.env`（chmod 600）；`docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml config -q` 静态校验。
- 判据：`config -q` 无报错；必改清单逐项勾选。
- 失败处置：缺变量/占位符值 → 补齐后再继续；绝不放宽 `REFRESH_COOKIE_SECURE`。

### 5. 构建镜像
- 命令（主机本地构建形态）：
  ```bash
  git checkout <SHA>
  docker build -f ai-collab-backend/Dockerfile -t ai-collab-backend:rc-<SHA前12位> \
    --label org.opencontainers.image.revision=$(git rev-parse HEAD) .
  docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ai-collab-backend:rc-<SHA前12位>
  ```
- 判据：label == 目标 SHA。离线导入形态：`docker load` 后同样核对 label。
- 失败处置：构建失败 → 中止（不在生产主机调试构建）。

### 6. TLS 与反向代理
- 操作：部署证书至反代 `[部署环境补充]`；前端 `pnpm build` 产物托管 `[部署环境补充：托管路径]`；`/api/v1` → `127.0.0.1:8080`；按 tls-and-networking.md §4.3 落实 X-Forwarded 头与 N-02 决策。
- 判据：`https://<域名>` 返回前端页面；`.well-known` 续期可用（若 Let's Encrypt）。
- 失败处置：证书/反代问题 → 中止（不裸 HTTP 上线：`REFRESH_COOKIE_SECURE=true` 下登录 Cookie 不生效本身就是阻断）。

### 7. 迁移兼容性检查
- 操作：`git diff --stat <现网SHA> <目标SHA> -- ai-collab-backend/src/main/resources/db/migration/`；新迁移逐个人工过目（首署从空库起无此步）；推荐先对备份副本预演一次 Flyway（release-runbook §5）。
- 判据：无不可理解的迁移。
- 失败处置：存在风险迁移 → 中止，找作者确认后再评估。

### 8. 启动
- 命令：`docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml up -d --no-deps --no-recreate app`（基础设施若未起：先 up postgres redis minio）；`docker compose -f ai-collab-deploy/docker-compose.yml ps`。
- 判据：四容器运行、依赖服务 healthy；app 容器内 Flyway 完成（日志无 `FlywayException`）。
- 失败处置：迁移失败**不要反复重启**（防半完成迁移叠加），按 rollback-runbook §3 处理。

### 9. 健康端点（发布成功必要条件）
```bash
curl -fsS http://127.0.0.1:8080/actuator/health          # {"status":"UP"} 200
curl -fsS http://127.0.0.1:8080/actuator/health/liveness # UP
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/env   # 404/401，绝不 200
```
- 失败处置：DOWN → `docker logs app --since 10m` 定位（MinioHealthIndicator 会暴露 bucket/连接问题）；修不动 → 回滚。

### 10. 业务冒烟（release-runbook §8 五项）
1. 登录 → 刷新保持登录（浏览器确认 Cookie 带 `Secure; HttpOnly; SameSite=Strict`）。
2. 项目协作：建项目 → 看板建/移任务。
3. 知识库：上传 → 解析分块 → RAG 带引用回答（**依赖 G-1 已修复**，否则此步必失败）。
4. Agent 只读 Run：SSE 正常收尾。
5. 权限：普通成员无权限操作 → 403。
- 失败处置：任一失败且回滚成本 < 修复成本 → rollback-runbook。

### 11. 引用/审批/权限边界
- RAG 回答引用与 `project_document.object_key` 对应；Agent 写操作弹出审批流才落库；MCP 仅白名单只读工具。

### 12. 日志与告警
- 判据：`docker logs app --since 15m | grep -iE 'ERROR|password|apikey|secret'` 无刷屏、无密钥输出；日志被采集器收到 `[部署环境补充：采集通道]`；告警测试通知送达 `[部署环境补充：告警通道]`。
- 现实边界：**告警通道未接通前，此步只能完成日志侧**（observability-and-alerting.md）。

### 13. 备份任务
- 判据：cron/timer 已配置 `[部署环境补充]` 且首跑产物出现；保留期生效。

### 14. 发布结论
- 发布成功判据（release-runbook §9 全集）：步骤 9 三项全过 + 步骤 10 五项全过 + liveness 持续 UP ≥15 分钟 + 日志检查干净 + 镜像 label == 发布 SHA。
- 任一阻断步未过 → 输出"停止发布"，执行回滚评估（rollback-and-recovery.md）。
- T+30min / T+24h 复核：容器 healthy、`ai_call_log` 错误率、备份照常（release-runbook §10）。
