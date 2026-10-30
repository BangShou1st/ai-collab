# 账号生命周期设计：忘记密码、邮箱验证与统一邮件服务（P7.1-D/E）

> 交付范围：忘记密码闭环、邮箱验证闭环、统一 `MailService`、统一可信公开地址（`APP_PUBLIC_URL`）。
> 设计原则：令牌只存哈希、防账号枚举、限频、审计可查、SMTP 故障不破坏业务事务、部署侧零代码即可接入邮件。

## 1. 统一可信公开地址

| 配置 | 语义 | 约束 |
| --- | --- | --- |
| `APP_PUBLIC_URL` | 正式前端公开访问源 | 只允许 origin（无路径/查询参数），非法配置拒绝启动（`PublicAppUrlService`） |
| `BACKEND_ORIGIN` | Cloudflare Pages Function 转发目标 | 生产分支缺失时明确 503，禁止静默回退 Staging（仅预览分支允许回退） |
| `AUTH_ALLOWED_ORIGINS` | CORS / Origin 校验白名单 | 与公开地址语义不同，不得互相替代 |

敏感链接（邀请 `/invite/:code`、重置 `/reset-password?token=`、验证 `/verify-email?token=`）只基于 `APP_PUBLIC_URL` 生成，
绝不从 `Host` / `X-Forwarded-Host` 等请求头推断。未配置 `APP_PUBLIC_URL` 时：
邀请链接由前端按当前页面 Origin 回退拼接；邮件类链接跳过发送并记录警告（不阻断注册/改邮箱主流程）。

## 2. 一次性账号操作令牌

- 表 `account_action_token`（迁移 `V72__account_lifecycle_tokens.sql`）：只存 SHA-256 哈希，`token_type`
  CHECK 约束限定 `PASSWORD_RESET` / `EMAIL_VERIFICATION`。
- 生成：`SecureRandom` 32 字节 → URL-safe Base64（43 字符）；同用户同类型旧令牌先失效再创建新令牌。
- 消费：`UPDATE ... WHERE consumed_at IS NULL AND expires_at > now()` 原子判定，天然防并发重放；
  无效/已用/过期统一返回 `ACCOUNT_TOKEN_INVALID`（410），不做状态区分。
- 有效期：密码重置 30 分钟；邮箱验证 24 小时。
- 日志红线：令牌明文只出现在邮件正文与 HTTP 请求体，不进任何日志；相关 Mapper 日志级别保持 INFO。

## 3. 忘记密码闭环

1. `POST /api/v1/auth/forgot-password`（公开 + IP/邮箱双维限频）：已知与未知邮箱响应完全一致，防枚举。
2. 生成重置令牌并发送邮件（链接指向前端 `/reset-password?token=`，前端再 POST，令牌不进后端日志）。
3. `POST /api/v1/auth/reset-password`（公开）：原子消费令牌 → 更新密码 → 撤销全部 Refresh Token
   → `token_version` 递增（全端 Access Token 失效）→ 审计 `USER_PASSWORD_RESET_COMPLETED`。

## 4. 邮箱验证闭环

- 注册填写邮箱 / 账号设置修改邮箱（邮箱变更会重置 `email_verified=false`）后自动发送验证邮件；
  `POST /api/v1/auth/verify-email/request`（登录态）支持手动重发。
- `POST /api/v1/auth/verify-email`（公开 + IP 限频）验证成功后置 `email_verified=true` 并审计。
- `CurrentUserResponse` 新增 `emailVerified` 字段，前端「账号设置」展示验证状态与重发入口。

## 5. 统一邮件服务

- `MailService` 接口 + 两个实现：`SmtpMailService`（`MAIL_ENABLED=true`，缺 `MAIL_HOST`/`MAIL_FROM` 拒绝启动）
  与 `LoggingMailService`（默认降级：只记录收件人与主题，正文/链接绝不写日志）。
- 事务解耦：事务内只发布 `MailMessage` 事件，`AFTER_COMMIT` 才真正投递；SMTP 故障只记错误日志，
  不回滚、不中断注册/重置等业务事务。
- 部署接入：`.env` 配置 `MAIL_ENABLED=true` + SMTP 参数即可，无需改代码；`docker-compose.yml` 已透传
  `APP_PUBLIC_URL` 与全部 `MAIL_*` 变量。

## 6. 环境矩阵

| 环境 | APP_PUBLIC_URL | MAIL_ENABLED | 行为 |
| --- | --- | --- | --- |
| 本地 Demo | 留空 | false | 邀请链接前端拼接；邮件跳过发送（日志记录意图） |
| Staging / Production | 正式前端 origin | true + SMTP | 全链接由可信配置生成；邮件真实投递 |

真实 SMTP 投递与 Cloudflare Pages 生产环境变量回填属外部验收项，需运维在部署侧配置后复验。
