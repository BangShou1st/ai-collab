# Product Experience V2 - Final Design
status: implementation-approved-one-pass
scope: full-implementation-to-v2-complete
branch: feat/product-experience-v2
date: 2026-09-10

> 设计已批，计划待二批。实施未批准，禁止业务重构。
> 本目录本地保存，不上传 GitHub。

## 0. 基线
- branch feat/product-experience-v2；工作树仅 ?? ai-collab-frontend/public/，favicon.svg 保留
- 禁 reset --hard/clean -fdx/restore/checkout --/force push；不 merge，不 push

## 1. Baseline Product Audit（真机 2026-09-10）
- 环境：postgres/redis/minio up；后端 local 在 8081，Flyway V1-V38 全过；前端 5173；owner 登录成功；演示项目 V2 Baseline Audit 已建；图全来自 localhost
- before/01-login.png：居中单卡，无品牌价值与 highlights
- before/02-register.png：字段全，无强度与开关说明，缺 onboarding
- before/03-projects-entry.png：未登录回登录，入口行为正确
- before/04-projects.png：单宽卡堆文字，三按钮删除大红最抢，无进度/里程碑/团队/活动
- before/05-overview.png：6 个零统计加表格，答不出健康/风险/下一步/谁忙
- before/06-board.png：5 空列，批量与状态语义弱
- before/07-documents.png：已拍，上传列表删除一致性与大小限制待实施验收
- before/08-knowledge.png：空会话双空态，无检索/引用/证据/流式/不足证据统一表达
- before/09-planning.png：已拍，提案与真实修改边界弱
- before/10-agent.png：单栏堆会话/对话/技能/输入框，无三栏 Inspector、无状态语言、无工具折叠、无 diff 审批
- before/11-members.png、13-account.png、14-admin.png、15-notifications.png：已拍，按 IA 收敛入口与权限表达
- before/12-model-settings.png：三用途全未分配加嵌入表加模型表，正是 V2 要拆掉的页
- AppShell：全局顶栏加项目横栏加三下拉，层级弱，关键能力被藏
- a11y 可见风险：焦点态弱、纯颜色状态、下拉藏关键操作、对比度待量测；不宣称 WCAG 合规
- 限制：未配真实 Provider，流式按空错态审计；响应式/键盘/量测实施后补

## 2. P0 已验证
- P0-1 ProjectEmbeddingController get/test/save 用可选 X-User-Id 加 if 非空才鉴权，不传头绕过；改为 AuthenticationPrincipal Jwt
- P0-2 update/assign/test/delete 先验项目管理员后裸 findById，无归属校验；新仓储用 findByIdAndUserId/findByIdAndProjectId
- P0-3 validateEndpoint 仅 http/https/host/userinfo；抽象 OutboundEndpointPolicy 复用 McpEndpointPolicy 的 DNS/私网/回环/链路/组播/CGNAT/userinfo/redirect 规则
- P0-4 application.yml 默认 fallback 公开 key；生产缺 key fail fast

## 3. Selected Architecture
- User-scoped LLM：Knowledge Answer、Planning、Agent；System-scoped Embedding；Project-scoped MCP/Integrations
- AiInvocationContext(userId, projectId, purpose) 显式透传；Agent 永远 run.requesterId，Retry/Continue/Approval 不变
- Runtime 最终 user model only；旧 Project Model 只做迁移来源，不长期 fallback
- 路由只留 /settings/ai；首页只放 AI 状态卡加入口；普通模式 Provider/Key/Model/测试/默认，高级折叠 Base URL/API Path/Temperature/MaxTokens/Capabilities 加 purpose override；单 Provider 默认全用途

## 4. Rejected Alternatives
- B 双写灰度：测试矩阵翻倍、心智两套，未选为主，仅迁移期临时兼容
- C 先安全后 IA：AI 归属延后返工大，仅保底

<!-- S1 -->
## 5. Migration Map（V1-V38 永不改，每版唯一 owner）
- V39__create_user_ai_configuration.sql：user_ai_provider（id PK，user_id FK app_user ON DELETE CASCADE，name，provider_type check，base_url，api_path，encrypted_api_key，model_name，enabled，temperature，max_output_tokens，capabilities，is_default BOOLEAN NOT NULL DEFAULT FALSE，created/updated；unique(user_id,name)；partial unique index 保每 user 最多一个 is_default=TRUE）。语义：purpose 有 assignment 用 assignment；无则用 is_default；皆无则 AI 未配置。首个 Provider 自动 default；Set Default 事务内切换；删 default 后为 none 不随机选，同时清理其 assignments。测试：first_provider_becomes_default、user_cannot_have_two_default_providers、setting_default_replaces_previous_default、purpose_override_wins_over_default、missing_default_and_override_returns_unconfigured、deleting_default_does_not_randomly_select_another。
- V40__create_user_model_purpose_assignment.sql：user_model_purpose_assignment（user_id，purpose check KNOWLEDGE_CHAT/AGENT/PLANNING，provider_id FK；PK(user_id,purpose)）；语义：无行则该 purpose 用用户默认 Provider，有行则 override
- V41__create_system_embedding_configuration.sql：system_embedding_config（id，provider，base_url，api_path，encrypted_api_key，model_name，dimensions，batch_size，fingerprint，enabled，created_at，updated_at；单 active 行约束）。preset 走服务端固定安全 endpoint，custom 走 OutboundEndpointPolicy。Key 只写不读，读只返 hasApiKey。Admin API（systemAdmin only）：GET /api/v1/admin/embedding-config、PUT、POST /test、POST /reindex；UI 在 Admin→AI Infrastructure。测试：system_admin_can_read/update/test、normal_user_cannot_read、project_admin_cannot_update_system_embedding、embedding_api_key_is_write_only。
- V42__migrate_project_models_to_users.sql：旧配置全迁给 owner，同名冲突加 project 短缀确定性归并；迁后唯一 Provider 则 is_default=TRUE，多则全 FALSE 不猜；每 purpose 收集该 owner 全部旧 assignment，全一致才建 assignment，冲突留空（禁 LIMIT1/首个/最新/demo/UUID 任选），用户后续在 /settings/ai 自选，冲突不致失败。测试：single_provider_becomes_default、multiple_providers_leave_default_unset、consistent_project_assignments_are_migrated、conflicting_project_assignments_are_left_unset、all_project_providers_are_preserved、migration_is_deterministic。
- V43__add_embedding_fingerprint.sql：project_document 加 embedding_fingerprint，document_chunk 加 embedding_fingerprint；存量按 provider|model|dimension 回填
- V44__switch_ai_configuration_constraints.sql：运行时切 user routing，旧 ProjectModel API 废弃标记；Runtime 100% user routing、0% legacy fallback；旧三表本轮保留为 deprecated 只读数据不物理删。每步空库与 V38 升级双测。破坏性清理留未来独立 V45+，本轮不预埋自动 DROP（Flyway 无人工确认窗口）。

## 6. Legacy Embedding Migration（Cases）
- 现状：project_embedding_config 每项目一套；project_document/document_chunk 存 provider/model/dimension；fingerprint=sha(provider|model|dimension)
- Case A 无旧配置：system=config 未配置；已有向量按指纹判（无则 legacy 待配）；管理员配置后重建
- Case B 全部旧项目语义一致：提升该语义为 system，不自动继承任一 API Key；Key 缺失则 system 先未激活，管理员填 Key 后重建；fingerprint 同值；规则确定
- Case C 多项目语义不一致：禁止 LIMIT 1/最新/首个/demo 静默当选；system 暂不激活；已有索引标 legacy/stale；管理员配好后显式 Reindex；不丢数据，不混空间，Flyway 不失败，管理员知道下一步

## 7. Embedding Reindex（本轮要做，保持简单）
- system admin 显式触发 Reindex all documents；复用 BatchReindexService.reindexAll 能力（resetForReindex 加 DocumentUploadedEvent），按 system 扩展到全平台或逐项目触发；用当前 system embedding 重进已有 pipeline；成功更新 fingerprint；不建分布式调度与多版本并发搜索

## 8. Semantic Guard 状态机
- 有 READY 且指纹一致：非语义参数可改
- 改 provider/model/dimension：拒默改；标记 stale/阻断该空间搜索/要求重建；admin 手动重建；全兼容后恢复；实现选最简一致机，worker 不临场定

## 9. SSRF（含 redirect）
- 官方 Provider 固定官方 HTTPS endpoint，不接受任意 host
- 自定义：HTTPS、无 userinfo、DNS 解析、拒 loopback/private/link-local/multicast/CGNAT/metadata；默认不自动跟随 redirect；若客户端关不掉，则每跳重过策略加 hop 上限；禁 public 经 302 到 127；测试覆盖

## 10. Auth Rate Limit（IP 来源）
- 默认 request.getRemoteAddr；生产受信代理按 Spring Forwarded Header 正式配置解析；不直接信 X-Forwarded-For；username 归一化后哈希拼 Redis key（复用 KnowledgeRateLimiter 的 INCR 加 EXPIRE 加本地 fallback 思路）；不记密码/token/邮箱明文；超限 429

<!-- S2 -->
## 11. Information Architecture
- GLOBAL：Home/Projects/Notifications/AI Settings/Account/Admin；PROJECT：Overview/Tasks(Board/Calendar/Gantt/Dependencies)/Knowledge(Documents/QA)/AI Workspace(Planning/Agent)/Team/Settings(MCP/Audit)
- MemberLoad/Milestones/WeeklyReport/RiskAnalysis/PlanComparison 不做一级；/home 登录默认进；旧模型配置页拆掉，项目设置只留 Members/Integrations-MCP/Audit/基础

## 12. Visual Direction A（已定）
- Modern Collaborative Workspace：Linear/Notion/Height 气质，克制精准清晰现代，高密度不拥挤，层级强；抽象 hierarchy/density/navigation/surface/interaction，不抄产品，保留 AI Collab 身份
- Agent/Planning 吸收 B 的 live status/timeline/inspector/tool/diff/metrics/context；全局字体/间距/颜色/侧栏/表面/圆角/按钮/表单/表格/图标唯一
- Element Plus 保留不换库；禁满屏渐变、廉价玻璃、处处卡片、大阴影、emoji 主图标、随机色、超大圆角、营销页
- favicon 本阶段不动，实施 Design System 再定保留/微调/重做

## 13. Design System 与页面顺序
- 拆 tokens/base/utilities/element-overrides；原语 AppShell/Sidebar/WorkspaceSwitcher/PageHeader/SectionHeader/MetricCard/EmptyState/StatusBadge/UserAvatar/AIStatus/LoadingState/ConfirmDialog；图标统一 Element Plus icons
- UI-1 Auth/AppShell/Home/Projects/Overview；UI-2 Task 全组；UI-3 Documents/Knowledge/Planning；UI-4 Agent 最高投入三栏；Dashboard 答健康/风险/下一步/谁忙，无数据不造图；删除收进 more

## 14. 测试与 QA 门
- TDD 全程；禁删测/skip/降断言；OpenAPI 同步且 $ref 无悬挂；README 改用户连 LLM、系统管 Embedding、项目管 MCP
- 最终：mvn test 零失败；pnpm test/typecheck/build 全过；空库与 V38 双验证；IDOR/SSRF/key/限流复测；15 流 after 对比；git 干净，用户明确才 push

<!-- SPEC-END -->
