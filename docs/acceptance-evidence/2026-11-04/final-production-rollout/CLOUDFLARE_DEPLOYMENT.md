# Cloudflare Pages 正式前端发布记录（CLOUDFLARE_DEPLOYMENT）

- 项目：`ai-collab-web`（Git 集成，GitHub `BangShou1st/ai-collab`）
- 正式域名：`https://ai-collab-web.pages.dev`
- 生产分支：`main`（`dev` 及各功能分支自动生成 Preview）
- 发布机制：合并 PR 到 main 触发自动 Production 构建；本次不手工 `wrangler pages deploy`，以 Git 集成实际部署为准。

## 1. 发布前基线（已实测）

| 项 | 值 |
| --- | --- |
| Production 部署 ID | `4c0644db-1bce-4dda-9b7d-a75e55399a6c` |
| Production commit | `66a704d` |
| 前一 Production 部署 | `86f7d1ca-1d40-487a-a90b-34005875c188` @ `c863ac5` |
| 最新 dev Preview | `ce3198f4-e2cc-4c29-a5ec-de7d9f4c6580` @ `56b0a06` |
| 正式站 index 资产 | `index-U8TCdIOk.js` / `index-CPT9jqmZ.css` |
| dev Preview 资产 | `index-C0FM3JfT.js` |
| HTTPS / 缓存 | TLS 验证通过；`Cache-Control: public, max-age=0, must-revalidate`；HSTS 生效 |
| API Functions | `/api/v1/auth/registration-policy` → 200 `{"code":"SUCCESS","data":{"enabled":true}}`，`cache-control: no-store` |
| 未登录 API | `/api/v1/actuator/health` → 401 `AUTH_UNAUTHORIZED`（鉴权按设计拦截，非代理故障） |
| 构建配置 | Git 集成由 Cloudflare 托管；仓库前端构建产物为 `ai-collab-frontend/dist`，Functions 位于 `ai-collab-frontend/functions/`；生产构建使用 `.env.production` 的 `VITE_API_BASE_URL=/api/v1` 同源代理 |
| BACKEND_ORIGIN | 部署级变量，生产分支必须显式配置；代码 `functions/api/_lib/backend-origin.ts` 在生产分支缺失时返回 503，禁止静默回退 InstaBox Staging |
| 代理目标 | `https://8443-agqyc3kteqehqgazwefpmhwulu.instabox.tech`（真实正式站后端网关，见 `PRE_DEPLOY_AUDIT.md`） |

## 2. 发布顺序（与后端协调）

按"后端先升级、前端后发布"执行，避免新前端长期连接不兼容旧后端：

1. main 合并 → CI 发布新 GHCR 镜像（main push）。
2. 拉取并部署新后端镜像 → Flyway V77 成功 → 后端健康。
3. main 合并会自动触发 Pages Production 构建；核对新部署 ID、commit SHA 与静态资产哈希变化。
4. 若 Pages 自动构建未触发或失败，再考虑受控重试（不绕过 CI、不强制推送）。
5. 新前端生效后清理浏览器缓存上下文做 UAT，确认不是缓存旧版。

> 注：main push 同时触发 GHCR 镜像发布与 Pages 构建，两者互相独立。后端升级必须在 Pages 新部署被用户访问前完成；由于 CI 镜像构建/发布通常慢于 Pages 构建，发布协调以"后端确认健康后再做浏览器验收"为门禁，若必要可用 Pages 回滚/重试控制窗口。

## 3. 发布后核对（执行阶段填写）

| 检查 | 预期 | 实测 |
| --- | --- | --- |
| Production 新部署 ID | 新 UUID | 待执行 |
| 部署 commit | main 合并后的 SHA | 待执行 |
| 部署分支 / 环境 | main / Production | 待执行 |
| 部署状态 | success | 待执行 |
| 正式站 index 资产哈希 | 与 dev Preview `index-C0FM3JfT.js` 一致或更新 | 待执行 |
| 旧资产 `index-U8TCdIOk.js` | 不再被 index 引用 | 待执行 |
| `/api/v1/auth/registration-policy` | 200 且 no-store | 待执行 |
| HTTPS / 静态资源 | 无 404、无严重 JS 错误 | 待执行 |

## 4. 回滚

Cloudflare Dashboard → Pages → `ai-collab-web` → Deployments → 选 `4c0644db…`（或 `86f7d1ca…`）→ **Rollback to this deployment**；前端回滚与后端镜像回滚配套执行，数据库不自动回退。
