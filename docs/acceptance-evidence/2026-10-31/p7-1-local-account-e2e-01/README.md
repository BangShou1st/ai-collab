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
