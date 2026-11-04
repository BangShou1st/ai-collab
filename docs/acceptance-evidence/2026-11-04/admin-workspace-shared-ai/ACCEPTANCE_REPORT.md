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
| 后端专项安全与生命周期测试 | `.\mvnw.cmd test -Dtest="SharedAiProviderLifecycleTest,AdminAiProviderSecurityTest,UserAiProviderPresetGuardTest,KiloGatewayPresetIsolationIntegrationTest,ZenDisconnectIntegrationTest"` | **16 个测试用例全 PASS (Testcontainers + Flyway V1~V77)** |
| 数据库迁移一致性 | PostgreSQL 容器 Flyway V77 执行校验 | **V77 迁移成功应用，用途绑定级联删除触发器生效** |

---

## 五、全站共享供应商真实模型出站推理实测证据（RAG 知识库问答真实闭环）

针对「普通用户使用全站共享供应商真实推理」能力，组织了挂载专属事实文档、命中真实向量索引、路由网关出站推理的全链路实测：

### 1. 测试拓扑与执行主体
- **全站共享模型**：`魔搭 ModelScope` (`deepseek-ai/DeepSeek-V4.1-Flash-1017`)，由系统管理员发布为全站共享（状态 `PUBLISHED`）
- **普通用户主体**：`fefinal_member`（普通项目成员，无任何管理员特权）
- **用途绑定**：用户将 `KNOWLEDGE_CHAT` 绑定至该全站共享供应商，系统自动记录 `is_shared=true`

### 2. 知识库专属事实文档与索引就绪
- **测试项目**：`RAG-Verify-1793796367128` (ID: `52c93200-af16-4c32-8423-e953697cb6a4`)
- **上传文档**：`polestar_spec.md` (ID: `9c3e5dbd-dfe6-4268-851b-2fb43e0270ff`)
- **注入特定事实**：
  - 核心代号：`POLESTAR-ALPHA-99`
  - 首席总工程师：`诺兰·瓦特博士 (Dr. Nolan Watt)`
  - 核心引力波探测频率：`1420.40575 MHz`
  - 首次深空精准对接日期：`2027年11月28日`
- **索引状态**：Tika 文本提取 + 本机 Ollama (`qwen3-embedding:0.6b`) 向量计算 + pgvector 写入完成，状态跃迁至 `READY`，分块数 `chunkCount=1`。

### 3. 普通用户提问与真实模型交互事实
- **问答会话 ID**：`7f367d16-7354-4be3-8722-24a5a5717f07`
- **提问内容**：`"请根据资料说明：极星深空探索计划的首席总工程师是谁？核心引力波探测频率是多少？首次深空对接日期是哪一天？"`
- **HTTP 状态与总耗时**：`HTTP 200 OK`，往返延迟 `2242ms`
- **真实模型响应内容**：
  > 根据资料 [S1]（《极星深空探索计划技术规范》，polestar_spec.md）：  
  > 1. **首席总工程师**：诺兰·瓦特博士（Dr. Nolan Watt）  
  > 2. **核心引力波探测频率**：1420.40575 MHz  
  > 3. **首次深空精准对接日期**：2027年11月28日  
  > 此外，资料还显示该计划的核心代号为 POLESTAR-ALPHA-99 [S1]。  
  > 来源：[S1]
- **来源引用列表**：包含 `documentId: 9c3e5dbd-dfe6-4268-851b-2fb43e0270ff`，相似度 `0.70702467`，分块 ID `75591a98-e903-4d51-bc31-989e22ee4638`
- **证据不足标记**：`insufficientEvidence: false`（确证未走入拒答逻辑，真实调用 ModelScope 产生推理结果）

### 4. 服务端调用与数据库脱敏日志单一事实源
- **后端执行日志 (`backend.stdout.log`)**：
  ```text
  2026-11-04T20:46:10.704+08:00 DEBUG [requestId=cddc05596ddd43f8] c.s.a.i.ai.model.JsonHttpModelClient - Model API request: model=deepseek-ai/DeepSeek-V4.1-Flash-1017, stream=false
  2026-11-04T20:46:12.744+08:00 DEBUG [requestId=cddc05596ddd43f8] c.s.a.i.ai.model.JsonHttpModelClient - Model API response status: 200
  2026-11-04T20:46:12.796+08:00 INFO  [requestId=cddc05596ddd43f8] c.s.a.k.a.s.KnowledgeQuestionApplicationService - Knowledge QA completed candidateCount=1 retainedSourceCount=1 highestSimilarity=0.7070246724181128 retrievalLatencyMs=89 chatLatencyMs=2040 totalLatencyMs=2212 requestId=547cf3c4-f29d-4005-9d31-1259e4abe9ae
  ```
- **数据库审计记录 (`ai_call_log`)**：
  - 调用 ID：`da38942b-5424-435f-8e29-fc0e2fccfa53`
  - 用户 ID：`366c7bd7-d9b2-4c42-af70-63ed573e6d71` (`fefinal_member`)
  - 项目 ID：`52c93200-af16-4c32-8423-e953697cb6a4`
  - 功能标识：`KNOWLEDGE_QA`
  - 提供商类型：`OPENAI_COMPATIBLE`
  - 模型：`deepseek-ai/DeepSeek-V4.1-Flash-1017`
  - 状态：`SUCCESS`
  - 耗时：`2040 ms`
  - Prompt Tokens：`483`
  - Completion Tokens：`204`
  - 时间：`2026-11-04 12:46:12.77954+00`

确证：普通用户在无持有 ModelScope API Key 的前提下，经由系统服务端全站共享安全路由，完成真实大模型 RAG 推理与事实生成闭环，能力状态正式达成 **PASS**。
