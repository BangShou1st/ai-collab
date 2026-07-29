# Phase 05：账号与任务协作

> **历史记录**：本文只说明该阶段的设计过程，可能包含已演进的接口、测试和状态。当前事实以代码、Flyway、`docs/feature-matrix.md` 和 `docs/api/openapi.yaml` 为准。

本阶段把已有认证、项目成员和 V1 数据结构连接成可运行的账号设置与任务协作主链路。阅读时应以
Java 代码、Flyway 迁移、构建结果、实际启动和浏览器手工验收为当前事实来源。

## 1. 公开注册、邀请注册与现有账号接受

账号进入项目有三条边界清晰的链路：

- `POST /api/v1/auth/register` 是可配置公开注册。它只创建用户，不自动加入项目。新用户可以调用
  `POST /api/v1/projects` 创建自己的项目。
- `POST /api/v1/invitations/{code}/accept` 必须持有有效邀请码。它在创建用户后，把用户加入邀请指定
  的项目和角色，把邀请状态改为 `ACCEPTED`，并建立登录会话。
- `POST /api/v1/invitations/{code}/accept-current-user` 必须携带 Bearer Access Token。它读取数据库中的
  当前 `ACTIVE` 用户，不创建账号、不接收密码、不签发 Access/Refresh Token，也不设置 Cookie。已有成员
  幂等返回实际角色且不消费邀请；定向邀请要求当前账号邮箱匹配。

公开注册关闭时，`GET /api/v1/auth/registration-policy` 仍匿名返回 `{enabled:false}`，
`POST /register` 稳定返回 `REGISTRATION_DISABLED` 和 HTTP 403。

## 2. 配置绑定与注册调用链

`PublicRegistrationProperties` 使用 `@ConfigurationProperties(prefix =
"auth.public-registration")`。`JwtConfiguration` 的 `@EnableConfigurationProperties` 显式注册它。
环境变量 `AUTH_PUBLIC_REGISTRATION_ENABLED` 绑定到 `enabled`：

- `application.yml` 默认 `false`；
- `application-local.yml` 默认 `true`；
- 显式环境变量覆盖默认值。

调用链是：

```text
SecurityConfig RequestOriginSecurityFilter
  -> AuthController.register
  -> PublicRegistrationService.register (@Transactional)
  -> UserService / PasswordEncoder
  -> AuditService
  -> AccessTokenService + RefreshTokenService
  -> AuthController 设置 Refresh Cookie
```

来源校验、BCrypt、JWT、Refresh Token 和 Cookie 都复用已有组件，没有复制实现。数据库唯一约束是
并发安全的最终边界，服务层预检查用于返回稳定的 `USERNAME_ALREADY_EXISTS` 或
`EMAIL_ALREADY_EXISTS`。

## 3. 账号资料、改密与全部退出

`PATCH /api/v1/users/me` 只接受 `displayName` 和 `email`，不接受用户名。返回
`CurrentUserResponse`，因此 `passwordHash`、`tokenVersion` 和内部时间字段不会进入响应。

修改密码的事务步骤：

1. 读取当前用户；
2. 用现有 `PasswordEncoder.matches` 验证当前密码；
3. 拒绝与旧密码相同的新密码；
4. BCrypt 生成新哈希；
5. 更新 `password_hash` 并执行 `token_version = token_version + 1`；
6. 撤销该用户所有未撤销 Refresh Token；
7. 写 `USER_PASSWORD_CHANGED` 审计；
8. 事务成功后由 Controller 清除当前浏览器 Refresh Cookie。

`logout-all` 同样在一个事务中撤销全部 Refresh 会话并写审计，重复调用保持幂等。

重要限制：当前 Resource Server 只验证 JWT 签名、过期时间和 `sub`，没有在每次请求中在线比较
`token_version`。所以改密后旧 Access Token **不会保证立即失效**，它可能活到自身到期。前端会主动
清空内存 Token 并返回登录页，但这不等于服务端立即拒绝所有已签发 Access Token。

## 4. work 模块文件关系

```text
api/controller
  MilestoneController / TaskController / TaskCommentController
api/dto
  创建、修改、替换依赖和评论请求
application/service
  MilestoneApplicationService / TaskApplicationService / TaskCommentApplicationService
application/view
  MilestoneView / TaskView / TaskCommentView
domain/model
  MilestoneStatus / TaskStatus / TaskPriority / TaskDependencyEdge
domain/policy
  TaskStatusPolicy / TaskDependencyPolicy / WorkPermissionPolicy
infrastructure/entity
  MilestoneEntity / TaskEntity / TaskCommentEntity
infrastructure/mapper
  显式项目作用域 SQL
infrastructure/repository
  隔离应用服务与 MyBatis
```

Controller 只处理 HTTP；Application Service 负责用例与事务；Domain Policy 负责状态、依赖和权限规则；
Repository/Mapper 负责持久化；Entity 不作为接口响应；View 不包含内部敏感字段。

## 5. 表关系与项目作用域

`milestone.project_id` 和 `project_task.project_id` 指向项目；任务的 `milestone_id` 可空且删除里程碑时
由 V1 的 `ON DELETE SET NULL` 清除，不删除任务。`task_dependency` 用
`(task_id, depends_on_task_id)` 表示有向边。`task_comment` 同时保存 `project_id` 和 `task_id`，
作者外键不会因删除评论而删除用户。

所有子资源 Mapper 查询都显式携带项目 ID：

- 里程碑：`project_id + milestone_id`；
- 任务：`project_id + task_id`；
- 评论：`project_id + task_id + comment_id`；
- 依赖：通过两侧 `project_task` JOIN 验证同项目。

不能先按全局 taskId 查到资源，再在 Java 中补项目权限；那样既容易产生越权，也可能暴露资源是否存在。

## 6. ProjectAccessGuard 的跨模块复用

work 应用服务首先调用 `ProjectAccessGuard.requireMember(projectId, userId)`。非成员统一看到
`PROJECT_NOT_FOUND`，不泄露项目和任务是否存在。写操作再由 `WorkPermissionPolicy.requireAdmin`
要求 OWNER 或 ADMIN。这个接口来自 project 模块，但 work 不需要读取项目模块内部 Mapper。

## 7. MEMBER 受限更新与评论权限

MEMBER 可以查看全部项目任务，但 PATCH 时必须同时满足：

- 任务 `assignee_id` 是自己；
- 请求只包含 `status` 和 `version`；
- 状态转换符合状态机和依赖规则。

OWNER/ADMIN 可以创建、完整修改、删除任务和替换依赖。前端隐藏无权限按钮只是体验优化，后端策略才是
可信边界。

评论是单层结构。任意成员可查看和发表；只有作者能修改；作者可删除自己的评论；OWNER/ADMIN 还可删除
他人评论，但不能改写他人内容。

## 8. 任务状态机

允许转换：

| 当前状态 | 目标状态 |
|---|---|
| TODO | IN_PROGRESS、BLOCKED、CANCELED |
| IN_PROGRESS | TODO、BLOCKED、DONE、CANCELED |
| BLOCKED | TODO、IN_PROGRESS、CANCELED |
| DONE | IN_PROGRESS |
| CANCELED | TODO |

同状态更新幂等。进入 `IN_PROGRESS` 或 `DONE` 前，`TaskStatusPolicy` 检查
`unfinishedDependencyCount`；大于 0 返回 `TASK_BLOCKED_BY_DEPENDENCY`。查询计数时，依赖任务处于
`DONE` 或 `CANCELED` 都算“已结束”，因此不阻止推进；业务语义上只有 `DONE` 表示“已完成”。
`BLOCKED` 不会被服务自动写入，必须由客户端明确请求。

## 9. Kahn 环检测与 DFS 对比

替换依赖不是追加。`TaskApplicationService.replaceDependencies` 先读取项目全部节点和边，把目标任务的旧边
替换成请求边，然后调用 `TaskDependencyPolicy`：

1. 为每个任务建立入度；
2. 把入度为 0 的任务入队；
3. 出队节点并移除它指向的后继边；
4. 后继入度变成 0 时入队；
5. 已处理节点数少于节点总数就存在环，返回 `TASK_DEPENDENCY_CYCLE`。

DFS 也能通过白/灰/黑颜色发现回边，适合从单点解释递归路径；Kahn 对整张替换后图更直观，并能用“处理节点
数”直接判环。本实现选择 Kahn。

### 9.1 并发依赖修改的项目级串行化

仅有 `@Transactional` 不能阻止两个事务同时读取旧图：一个事务设置 A 依赖 B，另一个事务设置 B
依赖 A 时，两边都可能基于旧图通过单请求校验。现在 `replaceDependencies` 在读取依赖边之前先执行
`SELECT id FROM project_task WHERE project_id = ? ORDER BY id FOR UPDATE`。得到的任务 ID 是完整节点集，
不包含目标 `taskId` 时返回 `TASK_NOT_FOUND`。在持有锁期间才校验依赖 ID、重读完整依赖边、执行 Kahn
校验并写入。相同项目共享同一组任务行锁，不同项目不会互相阻塞。

## 10. 乐观锁

里程碑和任务更新 SQL 都带 `WHERE project_id = ? AND id = ? AND version = ?`，成功时
`version = version + 1`。更新行数为 0 表示客户端持有旧版本，返回 `VERSION_CONFLICT` 和 HTTP 409。
前端应重新加载数据，不应静默覆盖。

## 11. 事务与审计

公开注册、资料更新、改密、全部退出、里程碑写入、任务写入、依赖替换和评论写入都以应用服务的
`@Transactional` 为边界。审计 INSERT 与业务写入使用同一事务；任一步抛异常都会一起回滚。

依赖替换在删除旧边前完成全部 ID、同项目、重复、自依赖和环校验。删除与逐条插入仍在同一事务，因此写入
中途失败不会留下半更新。审计 detail 固定为空 JSON，不写密码、哈希、Token、Cookie、Authorization、
邀请码或密钥。

## 12. 本阶段实际使用的 Spring 与 MyBatis 注解

- `@RestController`：把 Controller 返回值序列化为 JSON。
- `@RequestMapping`：定义 `/api/v1/auth`、`/users` 或项目子资源公共路径。
- `@GetMapping`：注册策略、列表和详情读取。
- `@PostMapping`：注册、改密、退出全部、创建资源和发表评论。
- `@PutMapping`：任务依赖的完整替换语义。
- `@PatchMapping`：资料、里程碑、任务和评论的修改。
- `@DeleteMapping`：删除里程碑、任务和评论。
- `@RequestBody`：把 JSON 绑定为请求 record。
- `@PathVariable`：读取 projectId、taskId、milestoneId、commentId。
- `@RequestParam`：任务列表的 status、assigneeId、milestoneId 筛选。
- `@Valid`：在进入应用服务前触发 Bean Validation。
- `@NotBlank`、`@Size`、`@Email`：注册、资料、标题、名称和评论校验。
- `@Service`：注册账号、账号安全和 work 用例组件。
- `@Transactional`：声明读写事务；`readOnly=true` 用于只读查询。
- `@ConfigurationProperties`：把公开注册配置绑定为类型安全 record。
- `@EnableConfigurationProperties`：`JwtConfiguration` 实际采用的配置注册方式。
- `@Mapper`：声明 MyBatis Mapper。
- `@TableName`：把 Entity 映射到 `milestone`、`project_task`、`task_comment`。
- `@TableId`：标记 UUID 主键。
- `@TableField(exist=false)`：标记查询投影字段，不参与 BaseMapper INSERT。
- `@AuthenticationPrincipal`：从 SecurityContext 注入已验证 JWT。

## 13. 推荐源码阅读顺序与断点

阅读顺序：

1. `SecurityConfig`；
2. `AuthController`；
3. `PublicRegistrationService`、`AccountService`；
4. `TaskController`；
5. `TaskApplicationService`；
6. 三个 Domain Policy；
7. Task Mapper/Repository；
8. 前端 `auth-store`、`work-api` 和页面；
9. 浏览器 Network、服务端日志与数据库安全元数据。

推荐断点：来源过滤器的 `doFilterInternal`、注册事务入口、`changePassword` 的会话撤销、
`TaskApplicationService.update`、`TaskStatusPolicy.validateTransition`、
`TaskDependencyPolicy.validateAcyclic`、依赖删除前、Mapper 乐观锁返回处、评论删除权限分支。

## 14. Phase 05 前端业务闭环

账号设置页同时提供“退出当前设备”和“全部设备退出”。前者调用
`POST /api/v1/auth/logout`，只撤销当前 Refresh 会话；后者调用
`POST /api/v1/auth/logout-all`，撤销账号的全部 Refresh 会话。两种操作成功后都会清空仅存在于
Pinia 内存中的 Access Token 和当前用户，并跳转登录页。开发阶段的 `/auth-test` 调试路由已移除，
普通页面不展示 Token 状态、主动刷新或请求调试结果。

任务看板用一个集中转换表生成合法目标状态，避免在多个模板中散落状态判断。该转换表只改善交互，
后端 `TaskStatusPolicy` 仍负责最终校验。OWNER/ADMIN 修改状态时提交任务的完整可编辑快照；
MEMBER 只提交 `status` 和 `version`。依赖继续由独立的整体替换接口管理，因此普通任务更新不会清空依赖。
依赖保存失败时页面重新读取服务端状态；评论编辑和删除后也重新加载列表。

任务状态修改成功后不再只替换被修改任务的本地对象。页面统一重新查询当前筛选条件下的任务列表；
详情抽屉打开时，同时按原任务 ID 重查详情，因此抽屉不会因刷新闪退。任务列表中的
`unfinishedDependencyCount` 完全来自服务端重查结果，不按 `dependencyIds` 长度推断，也不在前端递增或
递减。依赖关系数量与未完成数量分别展示：前置任务完成后关系仍保留，但阻塞提示在计数变为 0 时消失。
这里的“实时更新”指当前页面操作成功后立即重读服务端，不引入 WebSocket 或 SSE。

Phase 05 前端采用统一应用外壳：顶部提供产品标识、全局页面导航、当前用户与退出入口；项目路由下增加
任务看板、里程碑、成员管理二级导航。页面统一使用标题、说明、上下文和主操作结构。视觉变量集中在
`styles.css`，覆盖浅灰蓝页面背景、白色内容面、蓝紫主色、文本层级、语义色、边框、圆角、阴影、页面
宽度、间距和动画时长，并映射到 Element Plus 主题变量。普通用户界面保持简体中文，Element Plus 使用
`zh-cn`，日期统一显示为中文年月日格式。

成员、邀请、里程碑、任务、依赖和评论入口均使用简体中文。角色、任务状态、优先级和业务错误使用
集中映射，不把英文枚举值或错误码直接展示给普通用户。邀请原文仅来自当前 URL 或一次性创建响应，
不会进入浏览器持久化存储。

邀请页先并行完成公开预览和认证初始化，再进入未登录、已登录可接受、已有成员、邮箱不匹配或失败等互斥
状态。未登录用户可选择登录现有账号并带安全站内回跳，也可展开注册表单；已登录用户接受后刷新项目列表并
进入任务看板。全局 401 登录回跳和登录页都复用同一站内路径校验，外部 URL、协议相对路径、反斜杠和控制
字符统一回退到项目列表。`GET` 邀请预览只读取信息，不产生成员、邀请状态或审计写入；页面不会自动接受，
用户必须明确点击接受。现有测试账号无需删除或重新注册。

## 15. 手工测试流程

1. 启动 Docker Compose、local 后端和 Vite；
2. 打开注册页确认策略开启，创建账号；
3. 创建项目，确认角色是 OWNER；
4. 创建里程碑和两个任务，把其中一个分配给 MEMBER；
5. 设置 A 依赖 B，再尝试设置 B 依赖 A，确认环被拒绝；
6. B 未完成时尝试把 A 改为 IN_PROGRESS/DONE，确认被拒绝；
7. MEMBER 登录，只能修改分配给自己的任务状态；
8. MEMBER 发表评论；管理员不能改写它，但可以删除；
9. 修改资料；
10. 修改密码，确认回登录页且旧 Refresh 会话不能刷新；
11. 再登录，分别验证“退出当前设备”和“全部设备退出”的会话范围。
12. 用已有账号打开邀请页，验证直接加入、重复提交幂等、已有成员不覆盖角色、不消耗邀请，以及定向邮箱
    不匹配返回 403；确认响应没有 `Set-Cookie`。

任何报告都不能记录真实密码、Token 或 Cookie。

## 16. 当前验证策略与风险

当前项目决定采用手工验收优先并移除自动化测试。后端以编译打包和实际启动为基础检查，前端以
`pnpm typecheck`、生产构建和实际启动为基础检查，再通过浏览器验收账号、项目、成员、里程碑、任务依赖、
状态与评论主流程。

这一策略存在明确风险：

- 页面操作无法稳定复现 Refresh 并发；
- 页面操作难以证明数据库事务中途失败后的回滚；
- 跨项目隔离只能抽样验证；
- 环检测只能验证选定案例；
- 后续改动可能造成未发现的回归。

## 17. 常见故障排查

- 注册策略一直关闭：确认 active profile 是 `local`，或检查 `AUTH_PUBLIC_REGISTRATION_ENABLED`。
- 注册返回 403 `AUTH_FORBIDDEN`：浏览器 Origin 不在 `AUTH_ALLOWED_ORIGINS`，或代理丢失来源头。
- 改密后旧 Access Token 仍可请求：这是当前明确限制，等待 Token 自身过期；Refresh 会话应已全部撤销。
- MEMBER 更新返回 403：确认任务负责人 ID 等于当前用户，且请求只有 status/version。
- 更新返回 409：重新读取最新 version 后再提交。
- 跨项目资源返回 404：确认 URL 的 projectId 与资源归属一致，且当前用户是项目成员。
- 依赖替换返回 `TASK_DEPENDENCY_CYCLE`：检查完整依赖图，不只检查本次新增的一条边。
- 基础设施无法启动：确认 Docker Desktop 可用，并检查 Compose 服务状态和日志。
- 前端 401 刷新失败：检查 Refresh Cookie 的 Path、SameSite、Secure 和 Origin；Access Token 不应出现在
  localStorage/sessionStorage。
