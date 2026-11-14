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

## 测试数据边界与清理启用时机

撤权全生命周期验证只使用专用测试数据，真实用户授权与真实业务文档一律不动：

1. **专用租户**：创建仅用于本次验收的测试账号（命名前缀 `p41-acceptance-`）、独立测试项目、专用连接（绑定专用飞书测试应用与测试用户，不是任何真实成员的飞书身份），以及若干合成测试文档（内容含标记字符串，便于事后核对）。真实业务项目不挂测试连接，测试项目不挂真实连接。
2. **清理范围由代码约束**：清理任务只删除"删除日志中记录、且 `project_document.access_blocked=true` 且对象键与日志逐字段匹配"的对象（见 `ConnectorCleanupRepository`：删除前校验对象键前缀 `projects/{projectId}/documents/{documentId}/` 与库内行一致）。未进入测试连接删除日志的真实文档不在可达范围内。
3. **分两步启用清理**：首次部署 `CONNECTOR_CLEANUP_ENABLED=false`——先完成屏蔽验证（知识/下载/历史全部拒绝）并核对 `connector_cleanup_job` 与删除日志**只包含测试连接的文档**；确认范围无误后，单独一步置 `true` 仅对测试连接执行清理验证，结束后保持 `false` 或恢复由运维决定。
4. 撤权动作作用于测试连接的授权（飞书测试应用后台撤销该测试授权），不涉及任何真实用户；验证清单第 4 项据此执行。

## 验证清单（全部通过才申请验收）

1. 真实 HTTPS 下载：浏览器经 `MINIO_PUBLIC_ENDPOINT` 下载文件，SHA-256 与存储对象一致；响应带 `Content-Disposition: attachment`。
2. 权限负例：非项目成员取下载地址被拒；未登录被拒；知识被屏蔽后下载与问答同时不可用。
3. 签名负例：篡改路径/过期/坏签名请求被 Nginx+后端拒绝；浏览器全程不接触 `http://minio:9000`。
4. 飞书 OAuth（仅测试连接）：管理员配置回调→测试用户授权→Token 提前刷新→在飞书测试应用后台撤销**测试授权**→知识即时屏蔽→按上文时机启用清理→测试文档对象删除、审计保留、标题清除。期间观察刷新互斥与事件防覆盖日志。真实用户授权不参与任何一步。
5. Zen：免费模型列表非空、Agent 可调用（Bun 中继生效，`OPENCODE_ZEN_RELAY_URL=none` 时应看到 429/403 以反证中继必要）。
6. 邀请流程与基础回归：登录、邀请接受、任务/文档主流程不回归。

## 回滚计划

**结论先行：旧镜像没有屏蔽逻辑，回滚期间被屏蔽内容的安全边界只能由代理层维持；无法证明安全的入口一律默认关闭。**

受知识授权状态影响的入口（新镜像全部执行屏蔽；括号内为旧镜像基线就存在的对应路由）：

| 入口 | 屏蔽方式 |
| --- | --- |
| 知识问答（流式与非流式）、会话、引用、消息：`/api/v1/projects/{p}/knowledge/**` | 服务端可见性守卫；旧镜像无守卫 |
| 文档正文与下载：`/api/v1/projects/{p}/documents/{d}/content`、`.../download`、代理 `/storage/`（旧镜像仅有 content） | 内容服务守卫 + 下载授权子请求；旧镜像无守卫 |
| Agent 事件 SSE：`/api/v1/projects/{p}/agent/runs/{r}/events`；历史/记忆/审批/规划：`/api/v1/projects/{p}/agent/**` | 运行与会话可见性守卫；旧镜像无守卫 |
| 连接与来源视图：`/api/v1/connector/connections`、`/api/v1/projects/{p}/connector/sources` | 来源视图过滤；旧镜像无过滤 |
| 元数据外泄面：审计日志、通知、项目概览、仪表盘中的文档标题与引用 | 服务端元数据屏蔽；旧镜像输出原值 |

回滚步骤与约束：

1. 先把 `CONNECTOR_CLEANUP_ENABLED=false`，随后在 Nginx **默认关闭**上表全部入口（对旧镜像而言包括旧基线就存在的 knowledge、documents/content、agent 全域、connector 路由，以及元数据外泄面所在路由）；仅保留登录、注册、任务/里程碑等工作协作入口。关闭手段为 Nginx 对应 location 返回 404/403，不做"限流放行"。
2. 代码回滚=换回上一版镜像 digest；**V70 结构与数据保留，不回退不删除**。隔离演练实测：旧镜像（仅含 V69）对 V70 库启动**不需要任何 Flyway 开关**——旧版 Flyway 对"新于已知版本的已应用迁移"只输出 WARN（"Schema has a version (70) that is newer..."）后正常启动。因此不需要 `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`；若现场诊断确需该开关，仅允许在回滚镜像启动时临时使用，新镜像恢复时必须移除并确认 Flyway 正常执行，不得作为长期配置。
3. 回滚后立即执行权限负例：以被屏蔽项目成员身份访问上表入口，全部必须被代理拒绝；任何入口返回内容即视为回滚失败，保持入口关闭或回退到新镜像。
4. 配置回滚：恢复上一版 env（保留 `MINIO_PUBLIC_ENDPOINT` 与 `AUTH_ALLOWED_ORIGINS` 解耦的写法即可回退下载问题）。
5. 恢复新镜像后重放检查：`connector_deletion_journal` 水位与清理任务状态，确认后再开启清理。

## 隔离演练与证据（部署前完成，结果见验收证据目录）

1. **备份可恢复性**：使用仓库 `backup/run-restore-drill.sh`（备份→全新匿名卷临时实例恢复→一致性核验→强制清理，源侧只读）执行完整演练；备份自带 `.sha256` 完整性校验。记录演练时间、备份体积、核验输出。证据归档 `docs/acceptance-evidence/2026-10-30/p4-1-staging-precheck/`。
2. **旧镜像 + V70 兼容演练（已完成，2026-10-30）**：基线 `f7b528a` 旧镜像对含 V70 的隔离库：直接启动成功（Flyway 校验 70 个已应用迁移仅输出 newer-version WARN，无错误）；健康检查 UP；注册、登录、创建项目、知识会话接口全部正常（V70 全部增量：新表不被旧代码触碰，`connector_source.user_authorization_id` 可空，`project_document.access_blocked` 带默认值）。同时以代码事实确认旧镜像不含任何屏蔽守卫（`KnowledgeVisibilityGuard`、`StorageAuthorizationService`、`AgentHistoryVisibilityService` 均为新增类）——支撑上表"默认关闭"的结论。
3. 恢复对应关系：PostgreSQL 备份 ↔ MinIO 对象镜像 ↔ 旧镜像 digest 三者记录于同一演练档案；Staging 实际部署时的备份按同一流程在真实实例上重跑一次。

## NO_GO 条件

任一验证项失败即停：记录现象与日志（脱敏），回滚或修复后重新走验证清单；不跳过门禁，不把 Mock/容器启动成功当作端到端验收。
