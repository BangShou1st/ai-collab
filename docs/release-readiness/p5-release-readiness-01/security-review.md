# security-review.md — 安全发布门禁审计

> 审计方式：源码审计 + 既有测试证据 + 本地非破坏性检查。未对生产或外部服务执行任何扫描/攻击/压测。每项发现标注攻击前提与影响，不凭猜测升级等级。

## 1. 身份与授权 — PASS

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 路由鉴权 | PASS | SecurityConfig.java:57-69：默认 `anyRequest().authenticated()`；公开项仅 OPTIONS、auth 四端点、邀请 preview/accept、/actuator/health；JWT Resource Server 校验 + STATELESS |
| 项目级 Guard 覆盖 | PASS | 29 个 Controller 全部委托到含 ProjectAccessGuard 的 Service（37 文件引用）；DatabaseProjectAccessGuard.java:20-37 未命中抛 404 PROJECT_NOT_FOUND 掩藏存在性，无 TOCTOU |
| 任务级权限 | PASS | WorkPermissionPolicy.java:22-38：非 admin/owner 仅被指派人且仅限 status 字段 |
| 审批授权与幂等 | PASS | AgentApprovalService.java:317-425：requireAdmin + 行锁 + expectedRevision 乐观校验 + nonce SHA-256 比对 + Idempotency-Key 重放返回原结果 + 执行前 revalidate |
| 错误码泄露 | PASS（1 项 P3） | 项目级统一 404 不泄露；公开邀请预览返回被邀请人邮箱（R-19：码为 SecureRandom + 库存 hash，枚举不可行，设计内取舍） |

## 2. 模型凭据 — PASS（1 项 P3 GAP）

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 加密存储 | PASS | ModelSecretCipher.java:27-58：AES-256-GCM、12B 随机 IV、启动即拒绝占位 master key；master key 无默认值缺失即启动失败（application.yml:107） |
| API 回显 | PASS | ModelConfigurationView/SystemEmbeddingConfigView/UserAiProviderView 均只含 `boolean hasApiKey`；全库 View/DTO 无明文 apiKey 字段 |
| 日志泄露 | PASS | 全库 grep log+apiKey/password/secret/token 零命中；403 响应体不回显（JsonHttpModelClient.java:360-375）；local profile mapper 层 DEBUG 关闭防敏感参数 |
| 前端存储 | PASS | 前端全库无 localStorage 凭据；access token 仅 Pinia 内存态 |
| 主密钥派生 | GAP(P3) | 裸 SHA-256 无盐无 KDF（R-18）：需「短密钥 + 密文库泄露」双前提，已有 ≥16 字符下限缓解 |
| 修改配置不误删旧 Key | PASS | apiKey 仅在 Request 提供时更新；UI 保存空值语义经 p4-model-quick-connect-02 浏览器验收 |

## 3. 出站请求安全（SSRF）— PASS（1 项 P3 残余）

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 通用出站策略 | PASS | OutboundEndpointPolicy.java:44-108：强制 HTTPS、拒 userinfo/fragment、DNS 全量解析任一命中 loopback/site-local/link-local/CGNAT/保留段即拒、IPv6 ULA 拒绝 |
| 模型客户端 | PASS | JsonHttpModelClient.java:41-46：followRedirects(NEVER)；出站前 requirePublicHttps；SSE 8MB 总量/2MB 单帧上限 |
| {model} 占位符 | PASS | UserAiProviderService.java:172-210：至多一个占位符、替换安全 token 后整体 URI 校验、非法花括号组合拒绝、SSRF 策略零放松（浏览器实测 E2E-04/05） |
| MCP 出站 | PASS | McpEndpointPolicy.java:44-67：复用 OutboundEndpointPolicy + HTTPS 强制 + allowed-hosts 白名单（默认空=全拒） |
| DNS TOCTOU | GAP(P3) | 校验与连接二次解析间的 rebinding 窗口（R-20）：需攻击者控制目标 DNS，低概率残余风险 |

## 4. Agent 安全 — PASS

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| 永久禁止工具 | PASS | AgentToolPolicy.java:11-19：run_sql/http_request/delete_*/manage_members/configure_key/confirm_ai_plan/auto_approve 等 10 项无条件拒绝 |
| 写工具审批门控 | PASS | AgentToolRegistry.java:177-188 + AgentToolCallExecutor.java:488,587：ControlledWriteAgentTool 仅 depth=0、非 scheduled、OWNER/ADMIN；执行端每次调用前 checkPolicy 复核；写落库统一走 5 个 AbstractApprovalWriteAgentTool 实现，无旁路 |
| 批准前零副作用 | PASS | 真实验收（approvals.json）：PENDING 时 task=3/memory=0 |
| 幂等 | PASS | UNIQUE(project_id, idempotency_key) + nonce + revision（DB 约束见 database review） |
| 取消/重试防重复写入 | PASS | 真实验收：取消 0 工具执行；重试派生 Run 经 TRUSTED_PROPOSALS 识别已创建并拒绝重复创建 |
| 参数 Schema | PASS | ToolArgumentValidator uuid/date 校验修复（536ca3a）+ 28 用例回归 |
| 结果限幅与净化 | PASS | AgentToolResultSanitizer：256KB/100 项/深度 10/8000 字符梯度降级；McpResultSanitizer maxBytes 截断 |
| 身份锚定 | PASS | AgentToolContext 由服务端构造（run.projectId/requesterId/角色），工具无法注入身份 |
| 循环与预算 | PASS | AgentLoopGuard（滑动窗口 6 步/签名重复阈值 3）+ AgentConvergencePolicy（v1/v2 双策略）+ AgentContextBudget（单请求输入 50k 上限）+ AgentLeaseRenewer 行租约 |

## 5. 常规 Web 安全

| 检查项 | 结论 | 证据 |
| --- | --- | --- |
| CORS | PASS | CorsProperties.java:22-66：精确 Origin 白名单、禁通配符、启动期 @AssertTrue 校验 |
| CSRF | PASS | 无状态 Bearer + Refresh Cookie 模式下，Cookie 端点强制单值 Origin/Referer 白名单校验补偿（SecurityConfig.java:38-53,97-143 + AuthRequestOriginValidator.java:24-45，不回退 Referer、多值拒绝） |
| Token/Cookie | PASS（代码）/ FINDING（部署） | refresh cookie HttpOnly + SameSite=Strict + 限定 path；代码默认 secure=true（application.yml:67）；**compose 硬编码 REFRESH_COOKIE_SECURE="false"**（R-04，P1 部署条件，攻击前提=生产直接复用该 compose 且明文 HTTP 对外） |
| 文件上传 | PASS | DocumentFilePolicy.java:12-64：扩展名白名单 + 声明 MIME + Tika 嗅探三重校验、20MB 上限、路径遍历拒绝 |
| 文件访问授权 | PASS | 下载/正文读取经 requireMember（DocumentContentService.java:49） |
| 堆栈泄露 | PASS | GlobalExceptionHandler.java:83-87：未知异常仅返回 INTERNAL_ERROR，堆栈只进日志 |
| 调试端点 | PASS | management 仅 local profile 暴露 health+info；生产走 Boot 默认仅 health |
| 默认凭据 | PASS | compose 全部凭据来自 ${...} 环境变量；占位 master key 启动即拒；JWT_SECRET/POSTGRES_PASSWORD 无默认值 |
| 前端 XSS | PASS | v-html 全仓 4 处均经 DOMPurify（见 ux-readiness.md） |
| 依赖漏洞 | PASS（凭知识） | Spring Boot 4.1.0 / Tika 3.3.0 / MinIO SDK 9.0.2 / okhttp 5.3.2 均为新版本线；建议发布前跑一次 OWASP dependency-check（本报告未联网验证） |

## 结论

- **无 P0。无产品类 P1。** 门禁设计成熟：默认拒绝路由、全量 Guard、审批三因子、SSRF 全拒策略、密钥零回显。
- 唯一 P1 是部署配置问题（R-04：compose REFRESH_COOKIE_SECURE 硬编码 false + 无 TLS 设计），属发布操作条件而非代码缺陷。
- P3 三项（R-18 KDF、R-19 邀请邮箱回显、R-20 DNS TOCTOU）攻击前提苛刻，可延期并记录残余风险。
