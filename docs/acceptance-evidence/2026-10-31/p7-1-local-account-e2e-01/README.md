# P7.1 本地账号生命周期与邮件闭环真实 E2E 验收（2026-10-31）

## 环境与配置

| 项 | 值 |
| --- | --- |
| 前端 | Vite dev http://localhost:5173（dev.ps1 start，PID 管理） |
| 后端 | Spring Boot local profile http://localhost:8080（代码基线 dev@3e5f51d 之后的工作区） |
| 数据库 | Docker `ai-collab-postgres`（pgvector:pg17，Flyway V1–V72 自动迁移） |
| 邮件 | Mailpit（本地假 SMTP，SMTP :1025 / 收件箱 UI :8025），`.env` 配置 `MAIL_ENABLED=true`、`MAIL_HOST=localhost`、`MAIL_PORT=1025`、`APP_PUBLIC_URL=http://localhost:5173` |
| 浏览器 | Edge + browser-skill（bsk 0.4.0，会话 kqvy / ushn） |
| 测试账号 | `p71e2e_a` / `p71e2e_a@local.test`（本轮注册） |

## 场景结果

| # | 场景 | 结果 | 证据 |
| --- | --- | --- | --- |
| E01 | 注册（含邮箱）后自动发送验证邮件 | PASS | Mailpit 收到 1 封「AI Collab 邮箱验证」，发件人 `no-reply@local.test`，链接为 `http://localhost:5173/verify-email?token=<43位>`（基于可信 APP_PUBLIC_URL 生成） |
| E02 | 打开验证链接完成验证 | PASS | 页面展示「邮箱验证成功」 |
| E03 | 验证链接一次性（同链接重放） | PASS | 重放展示「验证链接无效或已过期」+ 前往登录入口 |
| E04 | 登录页忘记密码入口 | PASS | 登录页存在「忘记密码？」链接（observe 记录） |
| E05 | 忘记密码 → 重置邮件 → 重置密码 | PASS | Mailpit 收到「AI Collab 密码重置」；`/reset-password?token=` 直达（SPA 深链）；提交后展示「密码已重置，所有设备的登录会话已退出」 |
| E06 | 重置后旧会话失效 + 新密码登录 | PASS | 重置后刷新即回到登录页；新密码登录成功进入工作台 |
| E07 | 重置链接一次性（服务端拒绝重放） | PASS | 重放已用链接并提交，返回「重置链接无效或已过期，请重新申请密码重置」 |
| E08 | 邀请链接由后端可信 APP_PUBLIC_URL 生成 | PASS | 成员管理创建邀请，链接展示为 `http://localhost:5173/invite/1ie8E4fKGCXuMQmv-fU1VARaWRzUkLqrzp3P95I8QJQ`（43 位码，来自 `InvitationView.invitationUrl`） |
| E09 | 未登录邀请预览 API（防枚举边界） | PASS | 匿名 `GET /api/v1/invitations/{code}` 返回 200，仅含项目名/角色/过期时间，无敏感字段 |
| E10 | SPA 深链直达（不 404） | PASS | `/verify-email?token=`、`/reset-password?token=`、`/forgot-password`、`/invite/{code}` 均直接打开 |

原始 observe 文本与 API 响应保存在本目录 `evidence.jsonl`（关键行摘录）。

## 说明与边界

- 本轮验证的是 Local 环境矩阵；Staging/Production 的邮件真实投递、Pages `BACKEND_ORIGIN` 回填、飞书回调仍为 `BLOCKED_EXTERNAL`，未在本轮声明。
- bsk 0.3.3 → 0.4.0 升级由工具自身提示触发（协议 1.3 < 1.6 无法连接扩展）；第二轮会话因扩展接收端断开（`browser_disconnected`）中断，E01–E08 的关键断言均已在会话中断前以 observe 文本留存；E09/E10 为 API/SPA 路由级验证不依赖浏览器。
- 截图未能保留（会话中断），以 observe 文本与 Mailpit API JSON 作为主要证据。
- 补充会话（guor）中邀请页 UI 预览因扩展对 Agent 窗口内容脚本注入失败（`Could not establish connection. Receiving end does not exist.`，扩展主面板已显示 connected，`bsk status` 显示 protocol 1.7 无版本偏差）未能执行，标记 `BLOCKED_TOOLING`；邀请深链本身已由 E10 的 SPA 深链证据与 E09 的 API 预览覆盖。若需补截图：在浏览器扩展页重新连接后重跑本场景。

## 增补（2026-10-31 晚）：真实 QQ SMTP 投递 + Pages 生产复验 + 飞书白名单

| # | 场景 | 结果 | 证据 |
| --- | --- | --- | --- |
| E11 | 真实 QQ SMTP 投递（用户授权码，凭据仅存本地 `.env`） | PASS | `smtp.qq.com:465` SSL；注册 `qqsmtp_test01`（绑定 3296249574@qq.com）触发验证邮件、忘记密码触发重置邮件；后端日志零发送失败；Mailpit 收件箱保持 2 封（均为此前本地 E2E 旧邮件，证明未走本地假服务器）；**用户确认 QQ 邮箱收到** |
| E12 | SMTP AUTH 协议诊断 | PASS | curl smtps 通话：`AUTH LOGIN` → `235 Authentication successful`，`MAIL FROM`/`RCPT`/`DATA` 全部 250 OK（诊断邮件「AI Collab SMTP test」已接收） |
| E13 | Pages 生产 API 转发与 Origin 放行 | PASS | `https://ai-collab-web.pages.dev/api/v1/auth/registration-policy` 200（经 Pages Function 转发真实后端）；带 `Origin: https://ai-collab-web.pages.dev` 的登录请求得到业务响应而非来源拒绝，CORS 头反射正确 |
| E14 | Pages 生产注册 + Cookie 属性 | PASS | 经 Pages 域名注册返回 200；`Set-Cookie: ai_collab_refresh_token=***; Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict; Max-Age=1209599` |
| E15 | 飞书重定向 URL 白名单（用户登录，bsk 驱动） | PASS | 「AI-Collab 知识库连接器」安全设置添加两条：Staging `https://8443-...instabox.tech/api/v1/connector/oauth/callback` 与本地 `http://localhost:8080/api/v1/connector/oauth/callback` |

边界更新：

- 应用发出的邮件在应用侧零失败记录；未收到时的第一怀疑点（本地假服务器截留）已被 Mailpit 收件箱证据排除。
- Staging 后端 `AUTH_ALLOWED_ORIGINS` 已包含 Pages 域名（E13 的 CORS 反射即证），无需改 InstaBox 配置。
- 飞书 OAuth 的端到端真实点击授权（发起授权 → 回调 → 建立授权）待 `FEISHU_OAUTH_REDIRECT_URI` 与白名单一致性的运行时验证，另飞书「事件与回调 → 订阅方式」当前为未配置，撤权事件需在连接管理里补配。

## 增补（2026-10-31 深夜）：Staging 服务器部署与真库迁移

| # | 场景 | 结果 | 证据 |
| --- | --- | --- | --- |
| E16 | Staging 部署前备份（Runbook 纪律） | PASS | `backup-postgres.sh`：`pg_ai_collab_staging_20261031T011327Z.dump` 391455 字节含完整性校验与 SHA256；`backup-minio.sh`：15 对象镜像同步 0 差异 |
| E17 | Staging 部署最新代码（main=00093d6 + dev 透传） | PASS | 服务器仓库切 main 拉取 → 镜像 `ai-collab-backend:staging-cc3ebb1` 构建 → `compose -p ai-collab-staging up -d app`（env-file `/data/ai-collab-staging/config/.env`，`APP_PUBLIC_URL=https://ai-collab-web.pages.dev`）→ 容器 healthy |
| E18 | Staging 真库迁移 V72/V73 | PASS | `SELECT max(version::int) FROM flyway_schema_history WHERE success` = **73**；health UP |
| E19 | Staging 转发链路冒烟（Pages Origin → 公网网关） | PASS | 登录 200（Pages Origin 放行）；建项目 200；`GET /api/v1/projects/{id}/connector/available-connections` 返回系统连接「AI-Collab 知识库连接器」 |
| E20 | 项目级连接重复 App ID 防护 | PASS | 用 P4 同一 App ID 创建项目级连接 → 400 `VALIDATION_ERROR: 该 App ID 已存在连接，同一飞书应用不能重复创建`（部署前版本为 500，已在 cc3ebb1 修复） |

边界更新（部署矩阵联动）：

- Staging 已运行 V73；`BACKEND_ORIGIN` 已由用户在 Cloudflare 生产 Pages 回填且 PR #20 已合并（main=00093d6），Pages 生产部署从 main 自动发布。
- 剩余：飞书端到端真实点击授权（发起 → 回调 → 建立授权）；正向项目级连接需要一个未被接入的第二个飞书应用（当前 App ID 已有系统连接，唯一约束按设计拒绝重复）；飞书撤权事件订阅方式未配置。
