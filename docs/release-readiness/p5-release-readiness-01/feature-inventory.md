# feature-inventory.md — 全系统功能盘点

> 状态定义：IMPLEMENTED_AND_VERIFIED（真实验收通过）/ IMPLEMENTED_OFFLINE_ONLY（仅自动化测试）/ IMPLEMENTED_UNVERIFIED（有实现无证据）/ PARTIALLY_IMPLEMENTED / FEATURE_MISSING / NOT_APPLICABLE。
> 证据来源：docs/acceptance-evidence/ 下各轮验收报告 + 本轮源码审计（2026-10-24，基线 15f4905）。

## 一、功能矩阵

### A. 用户与身份

| 功能 | 状态 | 证据 |
| --- | --- | --- |
| 注册/登录/Token 刷新/退出/登出全部设备 | IMPLEMENTED_AND_VERIFIED | 真实验收以「登录基线复用」形式覆盖全部 P2–P4 验收轮（如 2026-10-19 p2-business-e2e-02）；register/refresh/logout-all 有自动化测试覆盖 |
| 用户资料与偏好（PATCH /users/me） | IMPLEMENTED_OFFLINE_ONLY | UserController；无独立真实验收 |
| 角色（systemAdmin）与管理员端点 | IMPLEMENTED_OFFLINE_ONLY | AdminController；admin 页面路由有守卫，无专项验收 |
| 多用户隔离 | IMPLEMENTED_AND_VERIFIED | 2026-10-24 p4-agent-lifecycle-real-e2e-02：10/10 跨用户/跨项目负面测试 404/401，不泄露存在性 |

### B. 项目与协作

| 功能 | 状态 | 证据 |
| --- | --- | --- |
| 项目/成员/邀请/角色管理 | IMPLEMENTED_AND_VERIFIED | 2026-10-03 space-bunny-browser-evidence、2026-10-19 p2-business-e2e-02 |
| 任务/看板/状态流转/依赖/里程碑 | IMPLEMENTED_AND_VERIFIED | 同上 + 2026-10-04 browser/（依赖、看板、SQL 断言） |
| 甘特图/依赖图/日历/成员负载/周报/风险分析/规划对比 | IMPLEMENTED_UNVERIFIED | WorkVisualizationController、WorkReportController、7 个前端 View 存在；**未发现任何真实验收或专项测试证据**（风险 R-06） |
| 通知中心 | IMPLEMENTED_UNVERIFIED | NotificationController 存在；无验收证据（R-06） |
| 操作日志/项目仪表盘 | IMPLEMENTED_UNVERIFIED | AuditLogController、ProjectDashboardController；无专项验收 |
| 项目删除/归档 | IMPLEMENTED_AND_VERIFIED | 项目删除有真实浏览器确认流程（ProjectListView.vue:120 ElMessageBox + 历史验收） |

### C. 文档与知识库

| 功能 | 状态 | 证据 |
| --- | --- | --- |
| 上传/Tika 解析/分块/索引/状态刷新 | IMPLEMENTED_AND_VERIFIED | 2026-10-24 p4-knowledge-real-e2e-01：5 篇文档真实上传索引 READY，1024 维向量落库，SHA-256 逐篇记录 |
| 向量检索/RAG 问答/SSE 流式 | IMPLEMENTED_AND_VERIFIED | 同上：6 次真实 Gemini 生成，0 Provider 失败 |
| 引用来源/引用抽屉/下载原文 | IMPLEMENTED_AND_VERIFIED | 同上 + 2026-10-23 p4-model-quick-connect-real-acceptance-01 复验；引用→chunk→document 身份链与页面一致 |
| 拒答（证据不足不编造） | IMPLEMENTED_AND_VERIFIED | Q-E2E-03：服务端判定证据不足 + 页面警示条 |
| 多轮问答/历史持久化/刷新恢复 | IMPLEMENTED_AND_VERIFIED | 5 轮 10 条消息刷新全保留 |
| 无匹配/错误反馈 | IMPLEMENTED_AND_VERIFIED | SSE 失败渲染错误气泡（p4-model-quick-connect-02 E2E-03/07 修复后） |
| 文档下载/删除/重新索引 | IMPLEMENTED_OFFLINE_ONLY | DocumentController 端点齐备 + 自动化测试；下载/重索引无独立真实验收记录 |
| 跨项目数据隔离 | IMPLEMENTED_OFFLINE_ONLY | requireMember 源码确认 + 通用负面测试；知识库专项跨项目越权未执行（p4-knowledge README §I 如实记录，无第二合法账户） |

### D. AI 模型接入

| 功能 | 状态 | 证据 |
| --- | --- | --- |
| Gemini 真实生成/流式/浏览器问答 | IMPLEMENTED_AND_VERIFIED | p4-model-quick-connect-real-acceptance-01（CONNECTIVITY/STREAMING PASS + 知识问答 PASS） |
| Kilo Gateway Free 真实生成/流式/原生工具 | IMPLEMENTED_AND_VERIFIED | 同上（套件两次全 PASS）+ p4-agent 两轮真实 Agent 使用 |
| OpenCode Zen Free | IMPLEMENTED_OFFLINE_ONLY | 上游限流，`UPSTREAM_RATE_LIMITED_NOT_RETESTED`，0 次真实请求（R-10） |
| Anthropic / OpenAI-compatible（自定义 Provider） | IMPLEMENTED_OFFLINE_ONLY | ProviderProfileCatalog 三协议档案 + 统一探针套件经真实浏览器验证（对假端点完整走通）；Anthropic 无真实上游生成验收 |
| 模型发现/能力探针/结果落库/STALE | IMPLEMENTED_AND_VERIFIED | V66 探针落库 + STALE 验证（假端点→探针 INCONCLUSIVE→改模型→STALE 展示→删除级联清理） |
| 用途分配/就绪度展示 | IMPLEMENTED_AND_VERIFIED | ai-purpose-status.ts 状态机（可用/不可用/未验证/未声明，CONNECTIVITY 不外推）经浏览器验收 |
| 凭据保护（加密/只写不读） | IMPLEMENTED_AND_VERIFIED | AES-256-GCM + 全库 View 无明文字段（见 security-review.md） |
| 出站 SSRF 防护 | IMPLEMENTED_AND_VERIFIED | 假端点/不可解析域名被拒并给分类文案（p4-model-quick-connect-02 E2E-04/05） |

### E. AI 规划

| 功能 | 状态 | 证据 |
| --- | --- | --- |
| PLANNING_JSON 两阶段生成（骨架→细节） | IMPLEMENTED_AND_VERIFIED | p4-agent-lifecycle-real-e2e-02 planning-probe.json：第 2 次真实 PASS（20s，verified=true，生产契约） |
| 规划确认落库（任务/里程碑写入） | IMPLEMENTED_AND_VERIFIED | 2026-10-03 real-planning-confirmed.png + 2026-10-19 p2-business-e2e-02（8 任务规划确认真实浏览器截图） |
| 规划编辑/版本保存恢复/部分重生成/取消/重试 | IMPLEMENTED_OFFLINE_ONLY | TaskPlanController 16 端点 + 自动化测试；无独立真实验收 |
| 失败重试/超时反馈 | IMPLEMENTED_AND_VERIFIED | 504 AI_MODEL_TIMEOUT 分类展示真实出现（planning-probe 第 1 次尝试） |
| 权限门控 | IMPLEMENTED_OFFLINE_ONLY | confirm_ai_plan 永久禁止 + ControlledWriteAgentTool 仅 OWNER/ADMIN + depth=0（源码审计） |

### F. Agent

| 功能 | 状态 | 证据 |
| --- | --- | --- |
| 会话/消息/原生工具调用/结果回流 | IMPLEMENTED_AND_VERIFIED | p4-agent-real-e2e-01 Run 82f16d75：4 工具真实调用、14,294 token 回流、回答与 DB 逐条一致 |
| 审批（批准/拒绝/批准前零写入/幂等） | IMPLEMENTED_AND_VERIFIED | 真实浏览器批准+拒绝全记录；幂等/nonce/revision 离线契约（AgentApprovalServiceTest 11 + ApprovalToolRevalidationTest 12） |
| 暂停/恢复（同一 Run 续跑） | IMPLEMENTED_AND_VERIFIED | p4-agent-lifecycle-real-e2e-02 Run c41b484c 真实恢复至 SUCCEEDED |
| 取消/重试（派生 Run + 幂等） | IMPLEMENTED_AND_VERIFIED | Run 4a42e54a 真实取消（0 工具 0 写入）；24b71f55→ec75239c 重试无重复写入 |
| SSE 顺序/断线续传/持久化 | IMPLEMENTED_AND_VERIFIED | 3 个 Run 事件序号与 DB 一一对应单调；Last-Event-ID 设计 + 刷新复核 |
| 记忆（审批门控写入） | IMPLEMENTED_AND_VERIFIED | 拒绝审批 0 条记忆写入真实验证 |
| 运行预算/循环收敛 | IMPLEMENTED_AND_VERIFIED | 5 个真实 Run 全部在 V2 策略内收敛 + 离线 123 用例 |
| 跨用户/跨项目权限隔离 | IMPLEMENTED_AND_VERIFIED | 10/10 负面测试 404/401；工具身份锚定源码确认 |
| 崩溃恢复/租约 | IMPLEMENTED_OFFLINE_ONLY | CrashRecoveryPostgresTest 5 用例 + 租约机制源码审计；真实进程崩溃场景未演练 |
| 历史模型追溯 | IMPLEMENTED_AND_VERIFIED | model_configuration_snapshot 21/21 + agent_step.MODEL_TURN 68/68 覆盖（实测） |
| QUEUED 直接暂停 | PARTIALLY_IMPLEMENTED | 窗口约 1s 无法稳定捕获，INCONCLUSIVE；离线测试 + 兄弟路径（FAILED_RETRYABLE）实证 |

### G. 集成与 MCP

**专项结论：IMPLEMENTED_OFFLINE_ONLY。**

- 后端完整（15 类）：ProjectMcpController（7 端点）、McpAdministrationService（接入 ProjectAccessGuard、ModelSecretCipher、McpEndpointPolicy、AuditService）、McpAgentToolProvider（tool/resource 双白名单 + McpResultSanitizer）、HttpMcpClientFacade/McpConnectionManager。
- 数据库：V29 agent_mcp_connection（凭据密文、白名单 JSON、schema_hash 确认、默认 enabled=false）。
- 测试：7 个测试类（HttpMcpClientFacadeTest、McpAdministrationPostgresIntegrationTest、McpAgentToolProviderTest、McpEndpointPolicyTest、McpResultSanitizerTest 等）。
- UI 入口存在：ProjectIntegrationsView.vue（路由 /projects/:projectId/integrations）。
- **关键缺口：全量检索 docs/acceptance-evidence/ 2026-10-03 至 10-24，无任何真实 MCP 服务器连接、工具发现或经 Agent 调用 MCP 工具的验收记录。**「代码存在」≠「已验收」→ 风险 R-05。发布范围需决策：验收或标注实验特性。

## 二、数据库迁移

66 个迁移（V1–V66），无跳号，命名规范。主题：V1 基础 / V2–V21 协作与知识库 / V22–V33 Agent v1 / V31–V48 模型与 Embedding / V47–V64 Agent v2 / V65–V66 Provider 接入。本地 flyway_schema_history 实测 66 全部 success。详见 database-and-migration-review.md。

## 三、前端模块与路由

16 个模块目录（account/admin/agent/ai/audit/auth/dashboard/document/home/knowledge/notification/planning/project/work）+ AppShell；31 条路由（公开 3 / 全局 5 / 项目级 18 + admin 2），全局登录守卫，项目级越权有专门收敛页（AppShell.vue:214-223）。

## 四、盘点总评

- 核心四域功能完整且真实验收充分，无 FEATURE_MISSING。
- IMPLEMENTED_UNVERIFIED 集中在 work 可视化域与通知中心（R-06）——这是本轮盘点最重要的发现，需要发布范围决策或补冒烟。
- MCP 是唯一「完整实现但零真实验收」的集成域（R-05）。
