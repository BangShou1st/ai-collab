# V2 Implementation Plan (task-by-task)
status: pending-second-approval-do-not-implement
spec: docs/archive/superpowers/specs/product-experience-v2-spec.md
baseline: docs/archive/superpowers/audits/product-experience-v2/before/
branch: feat/product-experience-v2

> 本轮只写计划，不改业务、不迁移、不提交、不推送。TDD：先 failing，确认红，实现，转绿，commit。
> 后端命令均在 ai-collab-backend 下执行；前端均在 ai-collab-frontend 下执行。

## Phase 0 Temporary endpoint hardening（后续被替换，但中间提交必须安全）

### T0.1 Embedding authorization fix
- Goal：旧 ProjectEmbeddingController 三接口不再信任客户端 userId。
- Files：ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/embedding/ProjectEmbeddingController.java；测试 ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/embedding/ProjectEmbeddingAuthTest.java（新建）。
- Interfaces：GET/POST /test/PUT /api/v1/projects/{projectId}/embedding-config 签名改为 @AuthenticationPrincipal Jwt；Jwt.subject 为 userId；删除 X-User-Id 参数。
- Database：无。
- Failing tests：member_cannot_get_embedding_config；non_member_cannot_save_embedding_config；owner_can_save_embedding_config；forged_user_header_has_no_effect（后者发伪造头仍按 Jwt 判）。
- Command fail：.\mvnw.cmd -Dtest=ProjectEmbeddingAuthTest test（应红）。
- Steps：换 Jwt 取 subject；删 header；保持加密与连接测试逻辑；临时端点标记后续替换。
- Command pass：同上转绿；再跑 .\mvnw.cmd -Dtest='*Embedding*' test。
- Acceptance：不传头不再绕过；MEMBER/非成员 403；OWNER/ADMIN 200。
- Commit：fix: harden embedding authorization（temporary endpoint hardened first）。
- Dependencies：无。

### T0.2 Model configuration scoping fix
- Goal：旧服务 update/assign/test/delete 先验 scope 再按 scope 加载。
- Files：ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ProjectModelConfigurationService.java；ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelConfigurationRepository.java（加 findByIdAndProjectId、deleteByIdAndProjectId）；测试新建 ProjectModelScopingTest。
- Interfaces：Repository 加 Optional<ModelConfiguration> findByIdAndProjectId(UUID id, UUID projectId)；Service 内 require(id,projectId) 替代裸 require(id)。
- Database：无（查询收紧）。
- Failing tests：project_a_admin_cannot_update_project_b_config；project_a_admin_cannot_delete_project_b_config；project_a_admin_cannot_test_project_b_config。
- Command fail：.\mvnw.cmd -Dtest=ProjectModelScopingTest test。
- Steps：仓储加 scoped 查询；service 四处改用；保持校验与加密。
- Command pass：同上转绿。
- Acceptance：跨项目 UUID 一律 403/404，无越权。
- Commit：fix: scope model config access。
- Dependencies：无。

### T0.3 OutboundEndpointPolicy with redirect policy
- Goal：Model/Embedding/MCP 共用出站策略，堵 redirect 绕过。
- Files：新建 ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/security/OutboundEndpointPolicy.java；改造 McpEndpointPolicy 使其委托该类；改造 ProjectModelConfigurationService.validateEndpoint 调用；Embedding 与 MCP Http 客户端接入；测试新建 OutboundEndpointPolicyTest。
- Interfaces：OutboundEndpointPolicy.validate(URI)；validateWithRedirects(URI,maxHops,redirectFetcher) 每跳重验；官方 Provider 用固定 endpoint 常量，不接受任意 host。
- Database：无。
- Failing tests：rejects_loopback_endpoint；rejects_private_ipv4_endpoint；rejects_ipv6_loopback；rejects_public_host_resolving_private_address；rejects_redirect_to_private_address；allows_public_https_custom_endpoint。
- Command fail：.\mvnw.cmd -Dtest=OutboundEndpointPolicyTest test。
- Steps：抽取 McpEndpointPolicy 的 DNS/私网规则；默认不跟随 redirect；关不掉的客户端逐跳重验加 hop 上限；local 显式开关外一律禁 localhost。
- Command pass：同上转绿。
- Acceptance：public 经 302 到 127 被拒；公有自定义 HTTPS 放行。
- Commit：fix: unify outbound SSRF policy。
- Dependencies：无。

<!-- P0 -->
### T0.4 Master key fail fast
- Goal：生产缺 key 启动失败，测试 key 只限 test/local。
- Files：ai-collab-backend/src/main/resources/application.yml（删公开 fallback，改为无默认占位）；ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/ModelSecretCipher.java（空 key 抛错）；测试新建 ModelMasterKeyFailFastTest。
- Interfaces：ModelSecretCipher 构造器空值即 IllegalState；DTO/日志不输出明文。
- Database：无。
- Failing tests：missing_master_key_fails_fast；test_profile_accepts_test_key；api_key_never_logged。
- Command fail：.\mvnw.cmd -Dtest=ModelMasterKeyFailFastTest test。
- Steps：删 fallback；构造器校验长度与非空；排查日志/异常/DTO 回显。
- Command pass：同上转绿。
- Acceptance：无 key 生产启动失败；test/local 可跑。
- Commit：fix: fail fast on master key。
- Dependencies：无。

### T0.5 Auth login/register rate limiting
- Goal：开放注册后防暴力请求，不引入短信邮件验证码。
- Files：新建 ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/ratelimit/AuthRateLimiter.java（仿 KnowledgeRateLimiter 的 Redis INCR 加 EXPIRE 加本地 fallback）；改造 AuthController login/register；测试新建 AuthRateLimitTest。
- Interfaces：AuthRateLimiter.checkLogin(ip, usernameNorm)、checkRegister(ip)；IP 默认 request.getRemoteAddr，生产受信代理按 Spring Forwarded Header 配置解析，不直信 X-Forwarded-For；username 归一化哈希拼 key，不记密码/token/邮箱明文。
- Database：无（Redis key rate:auth:login:{ipHash}:{userHash}:{window}）。
- Failing tests：login_rate_limited_after_burst；register_rate_limited_by_ip；spoofed_forwarded_header_does_not_bypass。
- Command fail：.\mvnw.cmd -Dtest=AuthRateLimitTest test。
- Steps：限流器仿现有脚本；controller 入口先 check；超限 429。
- Command pass：同上转绿。
- Acceptance：突发被 429；伪造头不绕过。
- Commit：feat: auth rate limiting。
- Dependencies：T0.4 无冲突，可并行。

## Phase 1 User AI domain

### T1.1 V39 user_ai_provider schema
- Goal：建用户域 Provider 表。
- Files：ai-collab-backend/src/main/resources/db/migration/V39__create_user_ai_configuration.sql（新建，user_ai_provider 含 is_default BOOLEAN NOT NULL DEFAULT FALSE，加 partial unique index 每 user 最多一个 TRUE）；integration test 新建 UserAiMigrationTest（含 first_provider_becomes_default、user_cannot_have_two_default_providers）。
- Interfaces：表 user_ai_provider(id UUID PK，user_id UUID FK app_user ON DELETE CASCADE，name varchar80，provider_type check，base_url，api_path，encrypted_api_key text，model_name，enabled bool，temperature，max_output_tokens，capabilities，created/updated；unique(user_id,name)；index(user_id))。
- Database：V39 唯一 owner；空库与 V38 升级双验证。
- Failing tests：v39_creates_user_ai_table；v38_upgrades_to_v39。
- Command fail：.\mvnw.cmd -Dtest=UserAiMigrationTest test。
- Steps：写 SQL；跑空库与老库升级。
- Command pass：同上转绿。
- Acceptance：约束生效，升级不失败。
- Commit：feat: user AI provider schema V39。
- Dependencies：Phase 0 完成。

### T1.2 V40 purpose assignment schema
- Goal：建准确命名的 purpose assignment 表。
- Files：V40__create_user_model_purpose_assignment.sql；表 user_model_purpose_assignment(user_id，purpose check，provider_id FK；PK(user_id,purpose))；语义无行用默认 Provider，有行 override。
- Failing tests：v40_creates_assignment_table。
- Command fail：.\mvnw.cmd -Dtest=UserModelPurposeAssignmentMigrationTest test。
- Steps/验收/commit：同 T1.1；commit feat: user model purpose assignment V40。
- Dependencies：T1.1。

### T1.3 User AI CRUD API
- Goal：个人 Provider 增删改查加测试连接加默认。
- Files：新建 Repository ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/user/UserAiProviderRepository.java（含 findByIdAndUserId/findAllByUserId/findDefaultByUserId）；Service 含 setDefault 事务（清旧立新）、deleteDefault 清 assignments 不另选；Controller（GET/POST/PATCH/DELETE /api/v1/user/ai-providers，POST /{id}/test，PUT /defaults）；DTO 只给 hasApiKey；测试 UserAiProviderOwnershipTest 加 setting_default_replaces_previous_default、purpose_override_wins_over_default、missing_default_and_override_returns_unconfigured、deleting_default_does_not_randomly_select_another。
- Interfaces：见上；test 返回连通结果不回 key。
- Database：V39/V40。
- Failing tests：owner_cannot_read_other_users_provider；owner_cannot_update_other_users_provider；owner_cannot_delete_other_users_provider；api_key_write_only。
- Command fail：.\mvnw.cmd -Dtest=UserAiProviderOwnershipTest test。
- Steps：仓储 scoped；DTO 脱敏；连接测试走 T0.3 策略。
- Command pass：同上转绿。
- Acceptance：跨用户 403/404；key 只写不读。
- Commit：feat: account AI providers。
- Dependencies：T1.1/T1.2。

### T1.4 AiInvocationContext routing
- Goal：Knowledge/Planning/Agent 按调用者选模型。
- Files：新建 AiInvocationContext；改造 Knowledge/Planning/Agent 调用链；测试 AiInvocationRoutingTest。
- Interfaces：AiInvocationContext(userId,projectId,purpose)；Repository.findAssigned(userId,purpose)。
- Failing tests：knowledge_uses_caller_model；planning_uses_actor_model；agent_uses_requester_model。
- Command fail：.\mvnw.cmd -Dtest=AiInvocationRoutingTest test。
- Steps：调用方显式传参，不塞 Map/ThreadLocal。
- Command pass：同上转绿。
- Acceptance：三链路各归其主。
- Commit：feat: user model routing。
- Dependencies：T1.3。

<!-- P1 -->
## Phase 2 System embedding

### T2.1 V41 system embedding table
- Goal：建单 active 系统嵌入配置表。
- Files：V41__create_system_embedding_configuration.sql；表 system_embedding_config(id，provider，base_url，api_path，encrypted_api_key，model_name，dimensions，batch_size，fingerprint，enabled，created_at，updated_at；单 active 约束)；测试 SystemEmbeddingMigrationTest。
- Command fail/pass：.\mvnw.cmd -Dtest=SystemEmbeddingMigrationTest test 红转绿。
- Acceptance：空库与 V38 升级通过。
- Commit：feat: system embedding schema V41。
- Dependencies：T1.2。

### T2.2 V42 project data migration to users
- Goal：旧 project 模型迁给 owner。
- Files：V42__migrate_project_models_to_users.sql；旧配置全迁 owner，同名冲突加 project 短缀确定性归并；迁后唯一 Provider 则 is_default=TRUE，多则全 FALSE；每 purpose 全一致才建 assignment，冲突留空不失败；测试 ProjectModelToUserMigrationTest 加 single_provider_becomes_default、multiple_providers_leave_default_unset、consistent_project_assignments_are_migrated、conflicting_project_assignments_are_left_unset、all_project_providers_are_preserved、migration_is_deterministic。
- Command：.\mvnw.cmd -Dtest=ProjectModelToUserMigrationTest test。
- Acceptance：unique 不炸 Flyway；双库验证。
- Commit：feat: migrate project models to users V42。
- Dependencies：T1.3/T2.1。

### T2.3 V43 fingerprint columns plus legacy cases
- Goal：指纹列加三案确定性迁移。
- Files：V43__add_embedding_fingerprint.sql（project_document/document_chunk 加 embedding_fingerprint；存量回填 sha(provider|model|dimension)）；迁移逻辑 Case A 无旧配置则未配置，Case B 全一致提升语义不继承 Key，Case C 不一致则不激活标 legacy/stale；测试 LegacyEmbeddingMigrationTest 含一致/不一致/空三案。
- Command：.\mvnw.cmd -Dtest=LegacyEmbeddingMigrationTest test。
- Acceptance：不丢数据，不混空间，正常多项目不失败。
- Commit：feat: embedding fingerprint and legacy migration V43。
- Dependencies：T2.1/T2.2。

### T2.4 Semantic guard plus manual reindex
- Goal：语义变更闸加手动全量重建。
- Files：ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/embedding/SystemEmbeddingService.java（新建）；改造 BatchReindexService 加系统触发入口（复用 resetForReindex 加 DocumentUploadedEvent）；DocumentRepository/DocumentMapper 查询加指纹过滤；管理端点 GET /api/v1/admin/embedding-config、PUT、POST /test、POST /reindex（systemAdmin only；UI 在 Admin→AI Infrastructure）；测试 SystemEmbeddingGuardTest。
- Interfaces：改 provider/model/dimension 有 READY 指纹一致则拒并返中文业务错；非语义参数可改；重建成功更新指纹。
- Failing tests：member_cannot_modify_system_embedding；non_admin_cannot_modify_system_embedding；system_admin_can_read_embedding_config；system_admin_can_update_embedding_config；system_admin_can_test_embedding_config；normal_user_cannot_read_embedding_config；project_admin_cannot_update_system_embedding；embedding_api_key_is_write_only；semantic_change_requires_reindex；mixed_fingerprint_is_never_searched。
- Command fail：.\mvnw.cmd -Dtest=SystemEmbeddingGuardTest test。
- Steps：守卫状态机；搜索按 active 指纹过滤；重建复用现有 pipeline。
- Command pass：同上转绿。
- Acceptance：旧索引不静默混查；管理员知道下一步。
- Product Design evidence：管理页空/未配置/需重建三态截图。
- Commit：feat: system embedding guard and reindex。
- Dependencies：T2.3。

### T2.5 V44 deprecate legacy runtime (no physical drop this release)
- Goal：V44 运行时切 user 并废弃旧 ProjectModel API；旧三表本轮保留 deprecated 不物理删（Flyway 无确认窗口，不预埋自动 DROP；清理留未来独立 V45+）。
- Files：V44__switch_ai_configuration_constraints.sql；Controller 加废弃标记；旧 ProjectModel/Embedding 查询从 runtime 摘除；测试 LegacyRetireTest。
- Command：.\mvnw.cmd -Dtest=LegacyCleanupMigrationTest test。
- Commit：chore: retire legacy project model runtime V44。
- Dependencies：T1.4/T2.4。

## Phase 3 Registration hardening

### T3.1 Public registration defaults
- Goal：本地默认可注册，生产显式开关。
- Files：.env.example（AUTH_PUBLIC_REGISTRATION_ENABLED=true）；README 相应段；测试 RegistrationPolicyTest。
- Command：.\mvnw.cmd -Dtest=RegistrationPolicyTest test；前端 pnpm vitest run src/modules/auth。
- Acceptance：开/关两态正确。
- Commit：feat: public onboarding flow。
- Dependencies：T0.5。

## Phase 4 Home aggregate

### T4.1 GET /api/v1/home
- Goal：个人工作台单聚合接口。
- Files：新建 HomeController、HomeApplicationService、HomeView/Response；聚合 project/task/notification/approval/AI/activity 仓储；测试 HomeAggregateTest 覆盖 N+1（SQL 计数断言）。
- Interfaces：返回 recentProjects/myTasks/notificationsSummary/pendingApprovals/aiConfigSummary/recentActivity；按真实仓储裁剪。
- Command fail/pass：.\mvnw.cmd -Dtest=HomeAggregateTest test。
- Acceptance：首页只调一次；无 N+1。
- Commit：feat: home workspace API。
- Dependencies：T1.4。

<!-- P2 -->
## Phase 5 IA Router Shell — Gate UI-A 在此验收 AppShell 加 Design System

### T5.1 Router
- Goal：/home 默认进，唯一 /settings/ai，旧模型页废弃。
- Files：ai-collab-frontend/src/router.ts；ai-collab-frontend/src/App.vue；测试沿用路由守卫单测。
- Interfaces：路由 /home、/settings/ai；/projects/:id/model-settings 标 deprecated 跳转提示。
- Command：pnpm vitest run src/stores；pnpm typecheck。
- Acceptance：before 03/04 对比找得到回得去；systemAdmin 守卫正确。
- Evidence：Gate UI-A 截图 AppShell 加 tokens。
- Commit：refactor: IA router。
- Dependencies：T4.1。

### T5.2 AppShell sidebar
- Goal：双横栏改 Sidebar 加 WorkspaceSwitcher。
- Files：ai-collab-frontend/src/shared/AppShell.vue；新建 Sidebar/WorkspaceSwitcher（如 shared/Sidebar.vue、shared/WorkspaceSwitcher.vue）。
- Command：pnpm vitest run；pnpm typecheck。
- Acceptance：全局与项目层级清晰；下拉不再藏关键操作。
- Evidence：Gate UI-A 全壳截图。
- Commit：refactor: app shell。
- Dependencies：T5.1。

## Phase 6 Design System（拆开）

### T6.1 Tokens foundation
- Files：新建 styles/tokens.css、base.css、utilities.css、element-overrides.css，瘦身 styles.css；映射 Element Plus 变量。
- Command：pnpm build；pnpm vitest run。
- Acceptance：视觉无回归；对比度抽查。
- Commit：feat: design tokens foundation。

### T6.2 Shell primitives
- Files：shared/PageHeader.vue、shared/Sidebar.vue、shared/WorkspaceSwitcher.vue。
- Command：pnpm vitest run；pnpm typecheck。
- Commit：feat: shell primitives。

### T6.3 Status data primitives
- Files：StatusBadge/EmptyState/MetricCard/UserAvatar/AIStatus/LoadingState/ConfirmDialog。
- Command：pnpm vitest run。
- Commit：feat: status data primitives。

### T6.4 Element integration plus focus responsive motion
- Goal：图标统一 Element Plus icons，清 emoji；焦点可见；断点与动效收敛。
- Command：pnpm build；键盘走查。
- Commit：feat: element integration and focus motion。

## Phase 7 Auth Home Projects Overview — Gate UI-B

### T7.1 Auth experience
- Files：src/views/LoginView.vue、src/modules/auth/RegisterView.vue。
- Evidence：before 01/02 对比；品牌价值入口俱全，不做营销页。
- Command：pnpm vitest run src/modules/auth；pnpm typecheck。
- Commit：refactor: auth experience。

### T7.2 Home workspace
- Files：新建 modules/home/HomeView.vue、home-api.ts；AI 卡未配给入口，已配给模型与分配，不 show key。
- Evidence：空项目/空 AI/有数据三态截图。
- Command：pnpm vitest run src/modules/home；pnpm typecheck。
- Commit：feat: home workspace。
- Dependencies：T4.1。

### T7.3 Project list
- Files：src/modules/project/ProjectListView.vue、project-api.ts。
- Evidence：before 04 对比；分层卡加 summary；删除进 more。
- Command：pnpm vitest run src/modules/project/ProjectListView.test.ts。
- Commit：refactor: project list。

### T7.4 Project overview
- Files：src/modules/dashboard/DashboardView.vue、dashboard-api.ts。
- Evidence：before 05 对比；健康/风险/下一步/负载/活动/AI insights。
- Command：pnpm vitest run src/modules/dashboard。
- Commit：refactor: project overview。

## Phase 8 Task workspace（拆开）

### T8.1 Task board
- Files：src/modules/work/TaskBoardView.vue、TaskBoardCard.vue、work-api.ts、task-status-transitions.ts。
- Evidence：before 06 对比。
- Command：pnpm vitest run src/modules/work。
- Commit：refactor: task board。

### T8.2 Task detail
- Files：src/modules/work/TaskDetailDrawer.vue。
- Command：pnpm vitest run src/modules/work。
- Commit：refactor: task detail。

### T8.3 Schedule surfaces
- Files：CalendarView.vue、GanttView.vue。
- Command：pnpm vitest run src/modules/work。
- Commit：refactor: schedule surfaces。

### T8.4 Dependency graph
- Files：DependencyGraphView.vue。
- Command：pnpm vitest run src/modules/work。
- Commit：refactor: dependency graph。

### T8.5 Milestones
- Files：MilestoneView.vue。
- Command：pnpm vitest run src/modules/work。
- Commit：refactor: milestones。

### T8.6 Team member load
- Files：MemberLoadView.vue、ProjectMembersView.vue。
- Command：pnpm vitest run src/modules/work。
- Commit：refactor: team load。

<!-- P3 -->
## Phase 9 Knowledge Documents Planning — Gate UI-C

### T9.1 Documents
- Files：src/modules/document/DocumentView.vue、document-api.ts、document-upload.ts。
- Evidence：before 07 对比；上传/列表/删除一致性。
- Command：pnpm vitest run src/modules/document。
- Commit：refactor: documents。

### T9.2 Knowledge states
- Files：src/modules/knowledge/KnowledgeView.vue、knowledge-api.ts、knowledge-feedback.ts。
- Evidence：before 08 对比；session/retrieval/citation/evidence/stream/不足证据/未配置/错误八态。
- Command：pnpm vitest run src/modules/knowledge。
- Commit：refactor: knowledge workspace。
- Dependencies：T1.4（调用者模型）/T2.4（系统 embedding）。

### T9.3 Planning states
- Files：src/modules/planning/PlanningView.vue、planning-api.ts、planning-draft.ts、planning-edit.ts、planning-poller.ts。
- Evidence：before 09 对比；goal/generation/draft/version/repair/confirm/source/未配置八态，提案与真实修改边界显式。
- Command：pnpm vitest run src/modules/planning。
- Commit：refactor: planning workspace。
- Dependencies：T1.4。

## Phase 10 Agent workspace — Gate UI-D（复用状态逻辑，不为 UI 重写 store/stream）

### T10.1 Responsive workspace shell
- Files：src/modules/agent/AgentView.vue（desktop 三栏 Sessions/Conversation/Run Inspector；tablet 右栏折叠；mobile 可切）。
- Evidence：before 10 对比空态。
- Command：pnpm vitest run src/modules/agent/AgentView.test.ts。
- Commit：refactor: agent workspace shell。

### T10.2 Sessions navigation
- Files：AgentView.vue 会话段；agent-api.ts sessions/rename/delete。
- Command：pnpm vitest run src/modules/agent。
- Commit：refactor: agent sessions。

### T10.3 Conversation presentation
- Files：消息流与 composer；复用 agent-run-store.ts、agent-event-stream.ts（逻辑正确不重写）。
- Command：pnpm vitest run src/modules/agent/agent-run-store.test.ts src/modules/agent/agent-event-stream.test.ts。
- Commit：refactor: agent conversation。

### T10.4 Run status inspector
- Files：agent-run-state.ts、AgentRunTimeline.vue、Inspector 区；Running/Approval/Input/Succeeded/Failed/Canceled/Budget 语言。
- Command：pnpm vitest run src/modules/agent/agent-run-state.test.ts。
- Commit：refactor: agent run inspector。

### T10.5 Tool call disclosure
- Files：工具调用折叠（名/态/耗时/摘要，JSON 默认收起）。
- Command：pnpm vitest run src/modules/agent。
- Commit：refactor: agent tool disclosure。

### T10.6 Approval diff experience
- Files：AgentApprovalCard.vue、approval-presentation.ts；想做何事/资源/现值/建议值/原因/Approve/Reject，危险分级。
- Command：pnpm vitest run src/modules/agent/approval-presentation.test.ts。
- Commit：refactor: agent approval diff。

### T10.7 Waiting input retry cancel error states
- Files：waiting/重试/取消/错误/无配置态。
- Command：pnpm vitest run src/modules/agent。
- Commit：refactor: agent run states。

### T10.8 Agent responsive keyboard visual QA
- Command：pnpm typecheck；pnpm build；键盘与窄屏走查。
- Commit：chore: agent visual QA。

## Phase 11 MCP integrations

### T11.1 Project MCP settings
- Files：项目设置 Integrations/MCP（Name/Endpoint/Health/Schema/ToolCount/Enabled/LastChecked，永不返 credential）；后端 McpAdministrationService、McpClientFactory、HttpMcpClientFacade 走 T0.3。
- Command：.\mvnw.cmd -Dtest='*Mcp*' test；pnpm vitest run。
- Commit：feat: project MCP integrations。

## Phase 12 Docs OpenAPI

### T12.1 API docs plus README
- Files：docs/api/openapi.yaml（User AI、System Embedding、Home、限流错、旧模型废弃、MCP 移动；$ref 校验）；README（用户连 LLM、系统管 Embedding、项目管 MCP）；docs/architecture.md（AiInvocationContext/User Routing/System Embedding/Project MCP）。
- Command：OpenAPI $ref 校验脚本；pnpm build。
- Commit：docs: product experience v2。

## Phase 13 Final QA — Gate Final 15-flow before/after
- T13.1 后端全量：.\mvnw.cmd test 零失败零错误；IDOR/SSRF/key/限流复测。
- T13.2 前端：pnpm test；pnpm typecheck；pnpm build。
- T13.3 Flyway：空库 V1 到 latest；V38 到 latest。
- T13.4 Product Design：同 15 图 after 复拍加 before/after 报告（问题/改变/剩余限制）。
- T13.5 Git：status 仅 ?? public/；local docs 未跟踪；用户明确才 push，不 merge。

<!-- PLAN-END -->
