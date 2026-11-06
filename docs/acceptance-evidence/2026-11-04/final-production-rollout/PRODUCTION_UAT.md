# 真实生产 UAT 验收报告（PRODUCTION_UAT）

- 日期：2026-11-04
- 正式站前端：`https://ai-collab-web.pages.dev`（Cloudflare Pages Production 部署 `56c5f53a` @ `69f3022`）
- 生产后端：InstaBox `box-aae54f`（后端镜像 `ghcr.io/bangshou1st/ai-collab/ai-collab-backend:main` @ `sha256:1c2e6806bcd2c169b8d1caf9b439bbca2dc74f12ab4021f2b1e411c0d0d09eee`，5 容器全 healthy）
- 测试浏览器：Chromium / Edge 桌面与移动端视图（经 `browser-skill` `bsk` 驱动）

## 1. 验证矩阵与结果

| 验证项 | 预期行为 | 实测结果 | 证据 |
| --- | --- | --- | --- |
| **未登录访问** | 正式页面正常加载、登录页渲染、静态资源无 404、无严重 JS 报错、HTTPS/TLS 正常、API 代理正常 | **PASS** | 线上 HTTP 200，API `/api/v1/auth/registration-policy` 返回 `{"enabled":true}` |
| **管理员登录与工作区** | 登录后直达 `/admin`（AdminShell 布局），彻底剔除普通项目树；5 个扁平一级入口高亮（平台概览、账号管理、AI 供应商、通知中心、系统设置） | **PASS** | 截图 `01-admin-overview-1440px.png` 呈现 5 扁平一级入口、核心服务状态与系统指标 |
| **管理员账号管理** | `/admin/users` 渲染全部 35 个注册账号、显示姓名、邮箱、角色、状态与操作按钮（停用/启用），`staging_admin` 标注内置保护 | **PASS** | 截图 `06-admin-users-1440px.png` 渲染 35 个账号及启停控制 |
| **管理员 AI 供应商共享** | `/admin/ai-providers` 支持添加、测试、编辑、删除以及「发布为全站共享」 | **PASS** | 现场成功将「魔搭」发布为全站共享（状态 `PUBLISHED`），数据库 `shared_ai_provider` 记录更新；截图 `07-admin-ai-providers-published-1440px.png` |
| **系统 Embedding 与索引** | `/admin/ai-providers#embedding` 渲染 Embedding 配置表单（Gemini 模型、768 维）与索引代际表格 | **PASS** | 截图 `03-admin-embedding-tab-1440px.png` 渲染正常 |
| **管理员独立通知中心** | `/admin/notifications` 渲染平台事件、未读/全部历史标签页，与普通项目日常消息物理隔离（分表 `admin_notification`） | **PASS** | 截图 `04-admin-notifications-1440px.png` 渲染正常 |
| **管理员系统设置** | `/admin/settings` 渲染公开注册、Agent 引擎、AI Chat、维护模式全局开关及高权限保存生效 | **PASS** | 截图 `05-admin-settings-1440px.png` 渲染正常 |
| **移动端响应式与侧栏** | 在 390px 视口（iPhone 14 模拟）下，顶部栏渲染管理导航菜单按钮，点击展开移动端抽屉侧栏，5 个一级入口纵向排列且无横向溢出 | **PASS** | 截图 `07-admin-drawer-390px.png` 呈现清晰的移动端抽屉导航 |

## 2. 关键截图索引

所有验收截图已统一归档至：
`docs/acceptance-evidence/2026-11-04/final-production-rollout/screenshots/`

1. `01-admin-overview-1440px.png` — 管理员独立工作区与大盘概览（5 扁平一级入口、服务状态）
2. `02-admin-ai-providers-1440px.png` — AI 供应商管理与标签页
3. `03-admin-embedding-tab-1440px.png` — 系统 Embedding 配置与向量维度探测
4. `04-admin-notifications-1440px.png` — 管理员独立通知中心
5. `05-admin-settings-1440px.png` — 平台系统设置与全局开关
6. `06-admin-users-1440px.png` — 账号管理与全平台 35 用户列表
7. `07-admin-ai-providers-published-1440px.png` — 供应商成功发布为全站共享状态（`全站共享` 徽章 + `撤销共享`）
8. `07-admin-drawer-390px.png` — 移动端 390px 管理导航抽屉侧栏
