# P4.1 Staging 部署方案（待单独批准后执行）

本方案覆盖远程下载修复、飞书授权与知识生命周期（V70）、OpenCode Zen v1.6.1 三组变更的 Staging 上线。**在获得单独的部署批准之前不执行任何一步**；不触碰生产，不清理任何真实数据，不撤销真实授权。

## 前置条件

1. 本地 `dev`（HEAD `664b209`，领先 `origin/dev` 9 个提交）已推送，PR `dev→main` 的远端 CI 全绿。CI 不绿则先修复，不进入部署。
2. 应用镜像基于本代码重建，**镜像内必须预装 Bun 并固定版本**（Zen v1.6.1 中继依赖；Linux 无自动下载兜底）。记录镜像 digest 与构建源码 commit。
3. 外层 instabox HTTPS 网关已把 `/storage/` 与 `/api/` 一并转发到仓库 Nginx（`/storage/` 不能落入 SPA 静态回退）。部署前用一次性探测确认。
4. 数据库部署前备份（现有 `backup-postgres.sh` 流程），记录还原步骤与校验点。

## 配置变更清单

| 变量 | 处理 | 说明 |
| --- | --- | --- |
| `MINIO_PUBLIC_ENDPOINT` | 新增 | 独立下载 Origin，建议 `https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`；必须能从浏览器访问且覆盖 `/storage/`。禁止从 `AUTH_ALLOWED_ORIGINS` 派生。 |
| `AUTH_ALLOWED_ORIGINS` | 保持/确认 | 仅前端认证 Origin（`https://ai-collab-web.pages.dev`），与下载 Origin 解耦。 |
| `MINIO_ENDPOINT` | 保持 | 容器内 `http://minio:9000`。 |
| `FEISHU_OAUTH_REDIRECT_URI` | 新增 | 精确回调地址（含 staging host），与飞书应用后台配置一致。 |
| `CONNECTOR_CLEANUP_ENABLED` | 新增 | 默认 `true`；回滚/恢复期可置 `false` 暂停清理，不阻断屏蔽。 |
| `OPENCODE_ZEN_RELAY_URL` | 新增 | 留空=auto（本地 Bun 中继，回环 18992）；确认镜像内 Bun 可执行。 |
| `MODEL_CONFIG_MASTER_KEY` | 保持 | 不得变更，否则已有模型凭据不可解密。 |
| `storage.minio.trusted-proxy-address` | 核对 | 必须等于实际容器网络中 Nginx 地址（当前模板 `172.30.173.2`），网段冲突需成对调整并重测。 |

所有值经受控 env 文件注入（属主/权限 0600），禁止写入仓库、日志或验收证据。`prod-config-check` 与 `minio-compose-contract` 两个脚本须用同一 env 文件先跑一遍。

## 部署步骤

1. 备份 PostgreSQL 与 MinIO（保留旧镜像 digest 与上一版 env 文件）。
2. 上传新镜像与 compose/nginx 配置；运行配置检查脚本。
3. `docker compose up -d` 拉起应用；Flyway 在启动时自动应用 **V70（纯增量：新建 connector_oauth_* / connector_deletion_journal / connector_cleanup_job / connector_hidden_* 表，`connector_source` 加可空列，`project_document` 加带默认值的 `access_blocked`）**。不手工执行 SQL，不改历史迁移。
4. 等待健康检查通过；确认日志无 Flyway 校验错误、无下载 Origin 校验失败。

## 验证清单（全部通过才申请验收）

1. 真实 HTTPS 下载：浏览器经 `MINIO_PUBLIC_ENDPOINT` 下载文件，SHA-256 与存储对象一致；响应带 `Content-Disposition: attachment`。
2. 权限负例：非项目成员取下载地址被拒；未登录被拒；知识被屏蔽后下载与问答同时不可用。
3. 签名负例：篡改路径/过期/坏签名请求被 Nginx+后端拒绝；浏览器全程不接触 `http://minio:9000`。
4. 飞书 OAuth：管理员配置回调→用户授权→Token 提前刷新→（飞书后台）撤销→知识即时屏蔽→清理任务完成→审计保留、标题清除。期间观察刷新互斥与事件防覆盖日志。
5. Zen：免费模型列表非空、Agent 可调用（Bun 中继生效，`OPENCODE_ZEN_RELAY_URL=none` 时应看到 429/403 以反证中继必要）。
6. 邀请流程与基础回归：登录、邀请接受、任务/文档主流程不回归。

## 回滚计划

1. 代码回滚=换回上一版镜像 digest；**V70 结构与数据保留，不回退不删除**。旧镜像（仅含 V69）启动会因 Flyway 校验遇到未知已应用迁移而失败——回滚时临时加 `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`（旧代码不读 V70 新表，新增列均有默认值/可空，向后兼容）。
2. 回滚前先把 `CONNECTOR_CLEANUP_ENABLED=false`，再隔离下载与知识入口（Nginx 停 `/storage/`、`/api/v1/knowledge` 限流或关闭入口），避免旧镜像绕过屏蔽逻辑。
3. 配置回滚：恢复上一版 env（保留 `MINIO_PUBLIC_ENDPOINT` 与 `AUTH_ALLOWED_ORIGINS` 解耦的写法即可回退下载问题）。
4. 任何回滚后重放检查：`connector_deletion_journal` 水位与清理任务状态，恢复镜像后再开启清理。

## NO_GO 条件

任一验证项失败即停：记录现象与日志（脱敏），回滚或修复后重新走验证清单；不跳过门禁，不把 Mock/容器启动成功当作端到端验收。
