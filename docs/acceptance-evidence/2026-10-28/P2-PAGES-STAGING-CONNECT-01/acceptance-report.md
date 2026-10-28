# P2-PAGES-STAGING-CONNECT-01 验收证据

- 日期：2026-10-28
- 执行：AI 自动化验收（真实浏览器 + API 层双重验证）
- 结论：**STAGING_WEB_ACCEPTED**

## 部署信息

| 项 | 值 |
| --- | --- |
| Cloudflare Pages 项目 | `ai-collab-web`（Git 集成，GitHub `BangShou1st/ai-collab`） |
| 正式部署 URL | https://ai-collab-web.pages.dev/ |
| Production Deployment ID | `4ef09aab-a54e-4190-8983-25d9ca52594b` |
| Production 源提交 | `11dfcfc`（PR #18 合并 `dev@6683cfb`，合并时间 2026-10-28T15:31:25Z） |
| Preview 验证部署 | `6a5bf9d8`（`dev@6683cfb`，验证通过后再合入 main） |
| 后端 | InstaBox `box-aae54f`，https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech |

## 本轮改动

`ai-collab-frontend/functions/api/[[path]].ts`（提交 `6683cfb`）：

1. Set-Cookie 逐条透传（`getSetCookie()`），避免多 Cookie 被合并丢失。
2. 缓存禁用从 `/api/v1/auth` 扩展到全部 `/api/*`（no-store）。

代理目标后端为部署时常量（`BACKEND_ORIGIN` 环境变量或硬编码默认值），不接受请求参数控制，无开放代理风险。

## 回归测试（本轮新跑）

- `wrangler pages functions build`：通过
- `pnpm typecheck` / `pnpm lint`（0 errors）：通过
- `pnpm test`：409/409 通过
- `pnpm build`：通过
- GitHub Actions CI（run `40714985451`，`dev@6683cfb`）：4/4 job 全绿

## 浏览器端到端测试（正式站，PASS/FAIL 清单）

| # | 项目 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 页面加载无静态资源错误 | PASS | network 日志无 JS/CSS/字体 4xx/5xx |
| 2 | `/api/v1/auth/registration-policy` 返回 JSON | PASS | HTTP 200 `application/json` `{"enabled":true}`（修复前为 index.html） |
| 3 | 新用户注册成功且无管理员权限 | PASS | `e2eweb1028b` 注册 200，`systemAdmin=false` |
| 4 | 登录成功 | PASS | UI 登录后进入工作台 |
| 5 | Refresh Cookie 属性 | PASS | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209599` |
| 6 | 刷新页面后登录态保持 | PASS | 全量重载后路由守卫将 `/login` 重定向回工作台 |
| 7 | Access Token 失效后静默刷新 | PASS | 浏览器 network 日志两次 `POST /api/v1/auth/refresh` → 200 |
| 8 | 退出登录且 Cookie 清理 | PASS | UI 退出后回登录页，`Set-Cookie: …; Max-Age=0`，登出后 refresh → 401 |
| 9 | 项目创建/读取 API | PASS | API 创建 201 + UI 创建"项目已创建"，列表可见 |
| 10 | 文档上传持久化 | PASS | 202 UPLOADED，MinIO 对象新增；解析状态 FAILED 见阻断 #1 |
| 11 | SSE 流式响应 | PASS（传输层） | 200 + `text/event-stream` + 标准事件帧（含结构化 error 事件）；真实 RAG 回答见阻断 #1 |
| 12 | 非法 Origin / 未授权 / 越权拦截 | PASS | 非 white-list Origin 注册 → 403；无 Token → 401；普通用户访问 `/api/v1/admin/users` → 403 `ADMIN_REQUIRED` |
| 13 | InstaBox 现有服务与数据完整 | PASS | 5 容器 healthy；Flyway 仍为 V68；用户 10→13 / 项目 2→4 均为本轮隔离测试账号与项目；MinIO 业务对象仅原有 1 份 + 本轮上传 1 份 |

测试隔离：使用独立测试账号 `e2eweb1028a/b/c`（普通用户）与独立项目，未触碰管理员账号与既有业务数据。

## 发现与修复

1. **修复**：Pages Function 多值 `Set-Cookie` 透传缺陷与 `/api` 缓存覆盖面不足（`6683cfb`）。
2. **发现**：后端 Origin 白名单仅含 `https://ai-collab-web.pages.dev`，Pages Preview 部署子域名（如 `6a5bf9d8.ai-collab-web.pages.dev`）会被 403 拦截。属预期安全设计，不影响正式站；Preview 环境只能做非认证类验证。
3. **发现**：合并 PR #18 时上一次 CI 失败为 Docker Hub 拉取 pgvector 镜像网络抖动（connection reset），重跑后全绿，非代码问题。

## 阻断项（不阻塞本次验收）

- `BLOCKED_EXTERNAL_PROVIDER`：staging 未配置系统嵌入模型（「文档索引暂不可用：系统尚未配置嵌入模型」）。导致文档索引与 RAG 问答无法给出真实 AI 回答；SSE 传输层与错误事件通路已验证。配置嵌入模型后，在文档详情重试（`POST /documents/{id}/reindex`）即可恢复。

## 后续更新与回滚

- 更新：提交到 `dev` → Cloudflare 自动构建 Preview → PR `dev`→`main` 合并后自动发布正式站。
- 回滚：Cloudflare Dashboard → Pages → `ai-collab-web` → Deployments → 选上一 Production 部署（如 `832bf2f4`）→ **Rollback to this deployment**；或 git revert 后按上述流程发布。
