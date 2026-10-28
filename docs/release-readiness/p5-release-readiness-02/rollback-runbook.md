# 回滚 Runbook（ai-collab）

> 原则：**先止血、再归因**。回滚优先恢复服务可用性，数据一致性核对在后。
> 配套：[release-runbook.md](release-runbook.md) · [backup-restore-runbook.md](backup-restore-runbook.md)

## 1. 判断是否需要回滚（停止条件）

出现以下任一情况即启动回滚，不要在现场反复试修：

- 应用无法启动（容器反复重启 / Flyway 迁移失败）。
- `/actuator/health` 持续 DOWN 且原因在本次变更范围内（DB/Redis/MinIO 连接异常、启动校验失败）。
- 核心冒烟（登录 / 项目操作 / 知识库问答）失败，且 15 分钟内无法定位修复。

不回滚的例外：仅外部 AI Provider（Zen/Kilo/Gemini）异常——liveness 与整体健康不受影响，属上游抖动，等待恢复或切换 Provider，不回滚应用。

## 2. 应用回滚（不动数据）

适用于：代码缺陷、配置错误、**且本次新增迁移对旧版本代码兼容**（Flyway 只增不改历史，旧代码读新表结构通常安全；若新迁移删除/重命名了旧代码依赖的列，则必须走 §3）。

```bash
# 2.1 停止当前版本 app 容器
docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml stop app

# 2.2 用上一个已知良好版本的镜像启动（镜像按 §4 发布时以 SHA 标记保留）
docker run -d --name ai-collab-app --restart unless-stopped --network ai-collab-deploy_default \
  -p 8080:8080 --env-file .env \
  ai-collab-backend:rc-<上一版本SHA短标>
# （或临时把 compose 中 image/tag 指回上一版本后 up -d）

# 2.3 健康检查
curl -fsS http://127.0.0.1:8080/actuator/health
```

## 3. 数据库不一致时的处理（含新迁移不兼容）

**禁止在生产库上手工回退 Flyway checksum 或删除迁移记录。**

1. 停 app 容器（止血）。
2. 用隔离恢复流程核对数据：`backup-restore-runbook.md` §3-4 把最新备份恢复到临时实例。
3. 评估两条路（按影响面决策）：
   - **前滚修复**：修复缺陷以新 commit 发布，让新迁移被正确执行（首选，Flyway 只增不减）。
   - **恢复快照**：数据损坏不可修复时，才把生产切换到"恢复实例 + 上一版本应用"，并接受备份点之后的增量数据丢失 `[部署环境补充：切换机制]`。
4. 恢复操作全程使用 `restore-postgres.sh` / `restore-minio.sh` 的守卫模式（目标显式指定），**不得**在业务库上直接执行破坏性 SQL。

## 4. 回滚后数据一致性核对

```bash
# 用核对脚本对比"生产现网"与"回滚所依赖的备份"之间的差异是否可接受
ai-collab-deploy/backup/verify-restore-consistency.sh --help
```

人工确认项：

- [ ] 用户/项目/任务行数与业务预期一致（发布窗口内的增量丢失是否已获得业务方确认）
- [ ] `project_document.object_key` 与 MinIO 对象一一对应（无悬空引用）
- [ ] Redis 仅承载限流/分布式锁，无需恢复；异常时重启容器即可

## 5. 回滚后动作

- [ ] 复跑冒烟测试（release-runbook.md §8）。
- [ ] `docker logs app --since 30m` 确认无 ERROR。
- [ ] 在事件记录中写明：根因、回滚时间线、数据影响、后续修复项。
- [ ] 修复以新 commit 走正常发布流程，禁止在回滚版本上热改。
