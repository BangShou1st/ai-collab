# 验收证据归档 ｜ P4-FEISHU-KNOWLEDGE-CONNECTOR-01

- 任务编号: `P4-FEISHU-KNOWLEDGE-CONNECTOR-01`
- 交付分支: `dev`
- 交付 SHA: `3a4fa7e`（含 P4 全部 6 个提交：迁移/后端、前端、测试、2 个安全修复、证据恢复）
- 目标环境: InstaBox Staging (`box-aae54f`)
- Staging 后端镜像: `ai-collab-backend:staging-3a4fa7e`
- Staging 前端: nginx 挂载 dev 分支构建的 dist（含新路由 `/projects/:id/knowledge-source`）
- 远端地址: `https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`
- 日期: 2026-10-29
- 裁决: `FEISHU_CONNECTOR_ACCEPTED`

## 真实飞书环境

| 项目 | 值 |
| --- | --- |
| 应用 | AI-Collab 知识库连接器（企业自建应用，`cli_aa688d12b778dd0e`） |
| 权限 | `drive:drive:readonly`、`drive:drive.metadata:readonly`、`docx:document:readonly`（均为应用身份只读） |
| 版本发布 | v1.0.0（管理员审核通过）、v1.0.1（机器人能力，免审通过） |
| 测试文件夹 | 云空间「AI-Collab 同步测试」，folder_token `CrAifrkMelnqBVdOB6vcDzx5nZe` |
| 授权方式 | 文件夹协作者（应用，可阅读）+ 项目管理员授权声明（审计留痕 `authorization_declared_by/at`） |
| 测试文档 | 《项目A赛事调研报告》《项目B经费管理办法》（Docx，非敏感内容） |
| 凭据交接 | App Secret 由本机剪贴板直写 gitignored `.env`，随后经 SSH 写入服务器受控文件（600 权限），全程未进 Git/日志/聊天记录 |

## 截图证据

1. `feishu-share-panel.png`：文件夹「管理协作者」面板，应用以「可阅读」协作者身份存在（撤权前）。
2. `feishu-remove-collaborator.png`：从文件夹协作者移除应用成功（撤权测试操作现场）。
3. `staging-knowledge-source-page.png`：Staging 前端「飞书知识源」页面——知识源状态正常、已同步文档 1、最近同步结果「同步成功 · 新增/更新 0 · 跳过 1 · 失败 0 · 移除 0」，系统管理员可见飞书应用连接（App ID、ACTIVE、验证按钮），项目导航含「飞书知识源」入口。

## 真实 E2E 结果（全部通过真实飞书 API + Staging 真库）

| # | 场景 | 结果 | 关键证据 |
| --- | --- | --- | --- |
| 1 | 连接鉴权（checkConnection） | PASS | 创建连接 201，`status=ACTIVE`，后端真实调用 `tenant_access_token/internal` |
| 2 | 文件夹可读验证 | PASS | 添加知识源时后端真实 `GET drive/v1/files` 成功 |
| 3 | 首次同步 | PASS | job SUCCESS，`scanned=2 updated=2`；两篇文档 `READY`，Gemini 768 维索引完成 |
| 4 | 无变化幂等跳过 | PASS | 第二次同步 `scanned=2 updated=0 skipped=2` |
| 5 | 单文档修改增量更新 | PASS | 飞书内修改文档A后同步 `updated=1 skipped=1`；问答返回新增内容（学校体育馆一层）且引用 quote 含新段落 |
| 6 | 删除文档 | PASS | 飞书删除文档B后同步 `removed=1` |
| 7 | 撤权（移除应用协作者） | PASS | 同步失败 `errorSummary="文件夹不可访问：forbidden."`，知识源转 `BLOCKED`，检索与正文即刻不可用 |
| 8 | 重新授权恢复 | PASS | 重新添加协作者后同步 `ACTIVE`、`docCount=1 updated=1`；问答恢复正常并引用新注册文档 |
| 9 | RAG 问答与来源引用 | PASS | 「报名截止日期？」→ 正确回答 2026-11-30 + 引用《项目A赛事调研报告》原文（similarity 0.83，模型 Qwen 魔搭） |
| 10 | 浏览器端页面验收 | PASS | 见截图 3 |

## 自动化测试与质量门禁

| 门禁 | 结果 |
| --- | --- |
| 后端 `mvnw verify`（1890 测试） | PASS（3 个 Testcontainers 用例因本地 Docker 未启动初跑失败，启动后单独重跑通过；1 个迁移版本断言同步至 69） |
| ConnectorSyncServiceTest（同步安全核心） | 10/10 绿（幂等跳过、增量、删除、撤权阻断、扫描不完整不删除、并发互斥、BLOCKED 恢复） |
| 前端 `typecheck / lint / test / build` | 全绿（409 测试通过，0 lint error） |
| Flyway 迁移 | V69 前向执行成功（真实 Staging 库），未修改任何历史迁移 |

## 部署与回滚

- 部署前备份：`/data/ai-collab-staging/backups/postgres-pre-p4-connector-202610281852.sql`（283KB）
- 仅重建 app 容器（`--no-deps app`），postgres/redis/minio 未动，卷未动，Gemini/魔搭配置未动
- 回滚方案：`.env` 的 `APP_IMAGE` 切回 `ai-collab-backend:staging-5c8e31b` 并 `up -d --no-deps app`；V69 表为新增表，旧镜像不感知，无需数据回滚
- 运行时健康：app 容器 healthy，`auth/me`、项目、知识源 API 外部访问均 200

## 未完成事项与说明

1. Cloudflare Pages Production 前端（ai-collab-web.pages.dev）尚未包含新页面路由——按任务约束不自动更新 Production；合并 `main` 后 Pages 随发布更新。本验收使用 Staging 自带前端（同一 dev dist）。
2. 定时轮询默认 30 分钟（`connector.sync.poll-interval-ms`），本轮验收以手动同步为准；调度器代码已上线。
3. 飞书协作者列表 API（权限成员核验）作为 best-effort 证据读取，失败不阻断主流程；主授权证据 = 文件夹真实可读（listing 成功）+ 管理员授权声明审计记录。
4. 一次 `git add -A` 曾误夹带 docs/acceptance-evidence 下 P10 历史 JSON 的意外改写，已在 `9a3c354` revert 恢复原状（净 diff 为零），特此留痕。

## 功能使用指南（用户）

1. 系统管理员：进入任意项目 → 飞书知识源 → 「飞书应用连接（系统管理员）」→ 新增连接（App ID + App Secret，创建时即真实验证，密钥加密入库不再回显）。
2. 文件夹所有者：在飞书把目标文件夹「添加协作者」给应用（可阅读）。
3. 项目管理员：飞书知识源 → 添加飞书文件夹 → 选择连接、粘贴 folder_token、勾选授权声明 → 验证并添加。
4. 手动同步：列表「同步」按钮；之后每 30 分钟自动扫描变更。
5. 检索：知识问答正常提问，回答引用飞书文档标题，文档抽屉里可点「在飞书中打开」回溯源文档。
