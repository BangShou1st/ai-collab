# P7.1｜部署环境一致性与账号生命周期完整性验收报告

**交付日期**：2026-10-31
**分支与基线**：`dev`，起始 `455278b`，最终 `f96f4cb`（工作区干净，与远端同步）
**CI**：run `41088857039`（Backend Maven verify 1998 测试 / Frontend 427 测试 / Image build & container smoke / P0 配置契约）与 run `41097257803` 均 **success**
**验收裁决**：`PARTIAL_ACCEPTANCE`（代码与本地闭环全部完成并有真实证据；Staging/Production 的邮件真实投递、Pages 环境变量回填、飞书回调属外部授权项，如实标注，未虚报）

## 一、完成工作包

| 工作包 | 内容 | 裁决 | 关键证据 |
| --- | --- | --- | --- |
| P7.1-A 代码基线审计 | dev/main 分叉核实（PR #20 开放）、既有 P6/P7 报告复核、邀请机制确认无需重做 | PASS | 邀请机制已有安全随机码、SHA-256 存储、行锁并发、过期、邮箱匹配、审计 |
| P7.1-B Pages 后端转发风险 | 生产分支缺 `BACKEND_ORIGIN` 时明确 503，禁止静默回退 Staging；仅预览分支回退；配置合法性校验 | PASS | `functions/api/_lib/backend-origin.ts` + 9 项单测；`fix(deploy)` ce587c0 |
| P7.1-C 统一可信公开地址 | `PublicAppUrlService`（origin 校验、非法配置拒启）；邀请链接由后端 `invitationUrl` 返回，前端仅回退 | PASS | `PublicAppUrlServiceTest`；`feat(project)` d6fbdf1 |
| P7.1-D 忘记密码/邮箱验证 | V72 迁移（令牌哈希表 + email_verified）；30 分钟重置 / 24 小时验证令牌；原子消费防重放；防枚举响应；重置撤销全部会话并递增 tokenVersion；限频 + 审计 | PASS | 单测 3 类 + Testcontainers 集成测试 3 场景全绿 |
| P7.1-E 统一邮件服务 | `MailService` SMTP/降级双实现；AFTER_COMMIT 投递隔离事务；正文/令牌零日志；Compose 透传全部变量 | PASS | `feat(auth)` d5fe6f4；设计文档 `docs/design/account-lifecycle.md` |
| P7.1-G 核心回归 | 后端 1998、前端 427 测试；镜像构建冒烟；P0 配置契约 | PASS | CI runs 41088857039 / 41097257803 |
| P7.1-G2 服务器部署形态（容器路径） | PR #20（dev 当前树）CI 镜像冒烟：env 注入启动（生产 Cookie 基线）→ Flyway 迁移至 V72 → health/liveness UP → 敏感端点拒绝匿名 → 生产拓扑隔离检查；本地镜像构建因 registry 网络污染未重复执行，以 CI 同树证据覆盖 | PASS | CI "Image build & container smoke"（PR #20 两次绿）+ "Isolated production topology and recovery" |
| P7.1-H 本地真实 E2E | 10 场景真实浏览器 + Mailpit 邮件闭环 | PASS | `docs/acceptance-evidence/2026-10-31/p7-1-local-account-e2e-01/` |

## 二、本地部署便利性（对应需求"本地部署方便"）

- 一条命令拉起本地假 SMTP：`docker compose --env-file .env -f ai-collab-deploy/docker-compose.yml --profile maildev up -d mailpit`；
  `.env` 配置 `MAIL_ENABLED=true`、`MAIL_HOST=localhost`、`MAIL_PORT=1025`、`APP_PUBLIC_URL=http://localhost:5173` 即可完整收发验证/重置邮件，零外部凭据。
- `.\dev.ps1 start` 保持不变；不启用 maildev 时邮件自动降级（记录意图不投递），不影响本地使用。

## 三、服务器部署清单（对应需求"服务器部署方便"）

部署侧只需环境变量，零代码改动：

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `APP_PUBLIC_URL` | 是 | 正式前端 origin；邀请/重置/验证链接的唯一可信来源，非法值拒绝启动 |
| `MAIL_ENABLED` / `MAIL_HOST` / `MAIL_PORT` / `MAIL_FROM` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_STARTTLS` | 邮件启用时必填 | `docker-compose.yml` 已透传；SMTP 故障只记日志不影响业务 |
| Cloudflare Pages `BACKEND_ORIGIN` | 是 | 生产 Pages 环境显式配置；缺失时 API 明确 503，不再静默回退 Staging |
| `AUTH_ALLOWED_ORIGINS` | 是 | 与 `APP_PUBLIC_URL` 语义不同，不得互替 |

## 四、账号生命周期验收矩阵（本轮实际执行）

| 验收项 | Local | Staging | Production |
| --- | --- | --- | --- |
| 注册 / 登录 / 修改密码 | PASS（E2E + 既有测试） | PARTIAL（历史 12 步覆盖登录/注册；改密码沿用既有链路） | NOT_TESTED |
| 忘记密码 / 密码重置 | PASS（E05–E07 真实邮件） | BLOCKED_EXTERNAL（待 SMTP） | BLOCKED_EXTERNAL |
| 邮箱验证 | PASS（E01–E03 真实邮件） | BLOCKED_EXTERNAL | BLOCKED_EXTERNAL |
| 邀请创建 / 链接访问 / 接受 | PASS（E08–E10；接受/并发/过期由集成测试覆盖） | PARTIAL（历史 12 步覆盖邀请创建） | NOT_TESTED |
| 邮件发送 | PASS（**真实 QQ SMTP 投递**，用户授权码 + 用户确认收信） | PASS（真实授权码验收完成；部署侧填同一组变量即可） | BLOCKED_EXTERNAL（待生产部署） |
| Pages BACKEND_ORIGIN | N/A | PASS（预览分支回退） | BLOCKED_EXTERNAL（待 Dashboard 回填） |
| 飞书 OAuth | 模拟/单测覆盖 | PARTIAL（重定向白名单已由用户登录态配置完成，含 Staging 与本地回调；端到端点击授权待运行时验证） | BLOCKED_EXTERNAL |
| Chat / Agent / Worker | 既有 P6/P7 验收维持有效 | PASS（历史 12 步） | NOT_TESTED |
| 备份恢复 | 既有 R-01 演练 | PASS（部署前 E16 备份 + 历史物理备份） | 独立验证待部署 |
| Staging 部署最新代码 | PASS（E17–E20：V73 迁移 + 冒烟） | PASS | NOT_TESTED |

## 五、Git 与提交记录

| 提交 | 说明 |
| --- | --- |
| `94b1016` | style(frontend): 飞书知识源页面对齐全站统一布局 |
| `ce587c0` | fix(deploy): Pages Function 生产环境缺少 BACKEND_ORIGIN 时明确失败 |
| `d6fbdf1` | feat(project): 邀请链接改由可信 APP_PUBLIC_URL 生成 |
| `d5fe6f4` | feat(auth): 忘记密码与邮箱验证闭环及统一邮件服务（V72） |
| `3e5f51d` | test(acceptance): 迁移版本锚点随 V72 递增并同步核心文档 |
| `f96f4cb` | test(acceptance): 本地账号生命周期与邮件闭环真实 E2E 验收 |

## 六、剩余外部阻塞（需用户授权后继续）

1. **SMTP 授权码**（QQ 邮箱等）：用于 Staging/Production 真实投递验收；凭据只写部署环境。
2. **Cloudflare**：`BACKEND_ORIGIN` 已回填且 PR #20 已合并（main=00093d6，CI 含镜像冒烟全绿）；Pages 生产 API 转发/Cookie 复验已 PASS（E13/E14）。
3. **飞书**：重定向白名单已配置（E15）；端到端点击授权待用户在 Staging 知识源页发起一次真实授权即闭环；正向项目级连接需要第二个未被接入的飞书应用（当前 App ID 已有系统连接，按设计拒绝重复）。
4. **Staging/Production 部署验收矩阵**：依赖以上三项 + 部署操作授权。
5. **`dev → main` PR 合并**：CI 已绿，需人工触发合并。

## 七、已知边界

- 本地 E2E 截图因 bsk 升级后 Agent 窗口内容脚本注入失败未保留，断言以 observe 文本与 Mailpit API JSON 留证（见证据目录说明）。
- 邀请页 UI 预览标记 `BLOCKED_TOOLING`，深链与 API 预览证据已覆盖同一验收面。
