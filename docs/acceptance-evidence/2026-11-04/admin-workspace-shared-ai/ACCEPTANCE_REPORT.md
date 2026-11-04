# 管理员工作区重构与全站 AI 供应商共享专项验收报告

> **验收日期**：2026-11-04  
> **任务 ID**：`admin-workspace-shared-ai`  
> **执行环境**：Windows 11 · PowerShell · Edge (bsk) · PostgreSQL 17 (pgvector) · Spring Boot 4.1.0 · Vite 7 + Vue 3.5  
> **分支基线**：`dev` (`origin/dev`)

---

## 一、验收结论

本次专项经过前后端端到端实现、两轮真实浏览器（bsk）走查、全量测试套件执行及数据库并发幂等断言，所有 11 项核心业务目标与安全红线**全部通过验收**：

1. **管理员独立工作区**：管理员登录默认直接进入 `/admin`（使用 `AdminShell.vue` 布局），彻底剔除项目管理、任务中心、知识中心等业务入口与普通项目树；普通用户登录进入 `/home`（使用 `AppShell.vue` 布局）。
2. **扁平化 5 个一级入口**：平台概览 (`/admin`)、账号管理 (`/admin/users`)、AI 供应商 (`/admin/ai-providers`，内嵌 Embedding 选项卡)、通知中心 (`/admin/notifications`)、系统设置 (`/admin/settings`)；子路由精确映射且唯一定位高亮。
3. **全站共享发布与严格幂等**：管理员一键发布供应商至「全站共享」，重复发布 3 次均原子更新单条记录与版本号（数据库唯一约束 `uk_shared_ai_provider_source`），普通用户端绝无重复卡片。
4. **普通用户自主选择权**：用户在「全站共享」栏目自主选用共享模型或个人模型，管理员不干预具体用途分配；新发布不擅自覆盖用户既有选择。
5. **权限与安全隔离铁律**：普通用户端严禁提供共享供应商的测试连接、流式测试、探针、API Key 编辑与删除入口；后端对普通用户测试、修改、删除共享 Provider 予以 HTTP 403 严格拦截；普通用户只读共享脱敏视图（密钥不暴露）。
6. **管理员独立通知中心**：数据与普通用户项目通知物理隔离（分表 `admin_notification`），支持系统事件生成（发布/撤销/维护）、已读标记与非管理员访问拦截。

---

## 二、验收用例与场景断言矩阵

| 场景编号 | 测试场景 | 核心断言与执行动作 | 验收结果 |
| :--- | :--- | :--- | :--- |
| **场景 A** | 管理员独立工作区与 5 一级入口 | 管理员登录进入 `/admin`，无项目工作区；侧栏 5 个扁平入口子路由准确定位高亮；`/admin/embedding` 重定向并选中 Embedding 选项卡。多视口适配 (1440px / 1024px / 768px / 390px)。 | **PASS** |
| **场景 B** | 管理员添加、测试、发布与幂等更新 | 管理员在 `/admin/ai-providers` 添加「DeepSeek V3 官方」并测试连接；发布为全站共享；重复发布 3 次验证 `SELECT count(*) FROM shared_ai_provider WHERE source_provider_id = '...'` 精确为 1；更新配置重新发布版本递增为 1。 | **PASS** |
| **场景 C** | 普通用户查看、选用与安全红线 | 普通用户 (`fefinal_member`) 访问 `/settings/ai`，在「全站共享」专区看到共享模型；前端无测试/探测/编辑/删除按钮；直接调用后端测试接口被返回 HTTP 403 拦截；一键选用至全部用途落库生效。 | **PASS** |
| **场景 D** | 第二普通用户隔离与自主权 | 第二普通用户 (`p71e2e_a`) 访问 AI 设置，用途覆盖保持独立，未被管理员新发布的共享模型静默篡改。 | **PASS** |
| **场景 E** | 管理员撤销共享与用户端安全回退 | 管理员执行「撤销共享」，状态变为 `REVOKED`；普通用户端卡片展示为“已撤销”，用途覆盖标记为暂不可用，引导用户重新选择，绝不静默跳转其他模型；管理员恢复发布后重获可用。 | **PASS** |
| **场景 F** | 管理员独立通知中心 | 管理员访问 `/admin/notifications` 查看全站系统事件，完成全部已读；普通用户访问 `/admin/notifications` 被路由守卫重定向至 `/home`，后端接口返回 403。 | **PASS** |

---

## 三、真实验收截图清单

验收证据全部归档至受控目录 `docs/acceptance-evidence/2026-11-04/admin-workspace-shared-ai/`：

### 1. 终态业务视图（Desktop 1440px）
- `final/01-admin-overview-1440px.png`：管理员独立工作区与概览大盘（5 扁平一级入口、核心服务状态、系统运行指标）
- `final/02-admin-users-1440px.png`：账号管理（用户列表、角色变更、状态启停）
- `final/03-admin-ai-providers-1440px.png`：AI 供应商管理（供应商列表、全站共享状态、测试与发布入口）
- `final/04-admin-embedding-tab-1440px.png`：系统 Embedding 管理（平滑内嵌在 AI 供应商页面选项卡）
- `final/05-admin-notifications-1440px.png`：独立管理通知中心初始态
- `final/06-admin-settings-1440px.png`：系统参数与动态配置中心（精简说明，折叠高级项）
- `final/07-admin-ai-providers-published-1440px.png`：发布全站共享后的供应商卡片（展示“全站共享”标签与版本）
- `final/08-admin-notifications-populated-1440px.png`：发布/撤销事件触发后的系统管理通知流
- `final/09-user-home-workspace-1440px.png`：普通用户工作台（保留项目管理、协作任务与知识库导航）
- `final/10-user-ai-settings-shared-1440px.png`：普通用户 AI 设置（全站共享专区、已验证能力标签、一键选用、无任何测试/修改/删除按钮）
- `final/11-user-blocked-admin-redirect-1440px.png`：普通用户尝试访问 `/admin` 触发路由拦截安全重定向至 `/home`

### 2. 响应式与移动端视口（1024px / 768px / 390px）
- `responsive/01-admin-overview-1024px.png`：笔记本视口概览
- `responsive/01-admin-overview-768px.png`：平板竖屏视口概览（侧栏自动折叠为汉堡菜单抽屉）
- `responsive/01-admin-overview-390px.png`：移动端手机视口概览
- `responsive/10-user-ai-settings-shared-768px.png`：平板视口用户 AI 设置全站共享卡片
- `responsive/10-user-ai-settings-shared-390px.png`：手机视口用户 AI 设置全站共享卡片

---

## 四、前后端工程质量门禁

| 检查项 | 命令 | 检查结果 |
| :--- | :--- | :--- |
| 前端 TypeScript 类型检查 | `pnpm typecheck` | **0 errors (PASS)** |
| 前端 ESLint 语法规范 | `pnpm lint` | **0 errors (PASS)** |
| 前端自动化测试套件 | `pnpm test` | **63 文件全绿，489 个用例 PASS** |
| 前端生产构建打包 | `pnpm build` | **Vite 构建成功 (dist 就绪)** |
| 后端专项安全与生命周期测试 | `.\mvnw.cmd test -Dtest="SharedAiProviderLifecycleTest,AdminAiProviderSecurityTest"` | **5 个测试用例全 PASS** |
| 数据库迁移一致性 | PostgreSQL 容器 Flyway V77 执行校验 | **V77 迁移成功应用，外键解除与约束生效** |
