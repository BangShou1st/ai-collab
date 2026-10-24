# operations-readiness.md — 运行环境与可运维性审计

> 审计方式：源码/compose/脚本审读 + 本地非破坏性实测（docker ps、/actuator/health）。未重启任何服务、未触发新 CI。

## 1. 部署成熟度：生产可部署（尚未生产就绪）

| 项 | 现状 |
| --- | --- |
| Dockerfile | ai-collab-backend/Dockerfile：多阶段构建、非 root、HEALTHCHECK（/actuator/health）、依赖层缓存、-XX:MaxRAMPercentage=75 —— 结构合格 |
| compose | 4 服务（postgres pgvector / redis 7-alpine requirepass+AOF / minio Chainguard digest 锁定 / app），前三者 healthcheck，持久命名卷，restart: unless-stopped，depends_on service_healthy |
| 缺口 | app 服务无 mem/cpu limit；CI 不构建/推送镜像（无 registry 环节）；无生产 profile（application-prod.yml 不存在）；无 TLS/反向代理设计 |

三档判定：**开发可用性极佳**（dev.ps1 校验/防误杀/日志落 target 设计工程质量高）→ **生产可部署**（Dockerfile+compose 完整）→ **未达生产就绪**（差可观测性、发布流水线、TLS 安全基线三件事）。

## 2. 环境变量清单（.env.example，脱敏）

已覆盖：POSTGRES_DB/USER/PASSWORD · REDIS_PASSWORD · MINIO_ROOT_USER/PASSWORD/BUCKET · JWT_SECRET · AUTH_PUBLIC_REGISTRATION_ENABLED · DEMO_OWNER_USERNAME/PASSWORD · MODEL_CONFIG_MASTER_KEY · REFRESH_COOKIE_SECURE · CHAT_*/EMBEDDING_*（PROVIDER/BASE_URL/API_KEY/MODEL/DIMENSIONS 等）· PLANNING_GENERATION_LIMIT_PER_USER_HOUR · AGENT_ENABLED/AGENT_MCP_ALLOWED_HOSTS/AGENT_WORKER_CONCURRENCY · KNOWLEDGE_RATE_LIMIT_PER_USER_HOUR。

缺口（R-12，P2）：代码还消费 AGENT_WORKER_DELAY_MS、AUTH_ALLOWED_ORIGINS、OPENCODE_ZEN_UPSTREAM、KILO 相关等未列入模板的变量；无 application-prod.yml。

## 3. 可观测性 — 薄弱（R-02，P1 阻断条件）

- management 配置**仅存在于 application-local.yml:68**（health,info）；默认 profile（容器即此）回落 Spring Boot 默认仅 health。
- pom 无 micrometer/prometheus 依赖；无告警方案。
- 无 logback-spring.xml：日志仅控制台，无文件滚动/保留策略。local profile 日志级别有安全设计（mapper 层禁 DEBUG 防敏感参数泄漏）。
- 本轮实测：/actuator/health 返回 UP（含 liveness/readiness 分组）。

## 4. Agent Worker 调度 — 合理

| 键 | 默认值 | 备注 |
| --- | --- | --- |
| agent.worker-delay-ms | 1000 | @Scheduled fixedDelay（AgentRuntimeJob.java:47），FOR UPDATE SKIP LOCKED 领取 |
| agent.schedule-delay-ms | 30000 | |
| agent.worker-concurrency | 2 | 1–4 越界启动失败 |
| 租约 | 续租 2min / 窗口 6min | AgentLeaseRenewer.java:35-37 代码常量不可配（R-24，P3） |
| stale 边界 | 10min | AgentRecoveryJob.java:23 上限校验 |

compose 已透传 AGENT_WORKER_CONCURRENCY；崩溃恢复由 AgentRecoveryJob + 租约过期接管（离线测试 CrashRecovery 5 用例 + 源码确认；真实进程崩溃演练未做，属可延期项）。

## 5. 外部依赖韧性

- **Provider 失败分类完整**（JsonHttpModelClient.checkStatus）：408/504→AI_MODEL_TIMEOUT、429→AI_PROVIDER_QUOTA_EXCEEDED、403→FreeTier 识别、401→凭据无效；不回显上游响应体。前端 planning-failure.ts / agent-run-state.ts 有对应失败态；知识库 SSE 失败有错误气泡。
- **Kilo 模型下线处理已实测**：p4-model-quick-connect-real-acceptance-01 中 minimax 模型被上游下线 → 「刷新模型」获取当前目录 → 校验拒绝旧模型并提示重选 → 真实换模型成功。故障表见 docs/deployment/kilo-gateway.md §6-7。
- **Zen 限流**：失败正确分类展示；持续限流是上游事实（R-10）。
- **Embedding**：SystemEmbeddingConfig 管理员动态配置 + embedding.local-allowed-targets 白名单（SSRF 防护）；p4-knowledge 实测 Ollama qwen3-embedding 1024 维成功。注意：不能因 Ollama 进程在跑就假定已配置——就绪度以 system_embedding_config 实读 + 探针为准（已实测）。
- **网络约束（重要环境事实）**：本机 Gemini 直连不可达，历史验收均以后端 JVM 代理参数启动。**生产环境无代理配置项**——若部署环境需代理出网，Gemini 将硬阻断。部署文档必须明确出网白名单：opencode.ai、ai-gateway.kilo.ai、generativelanguage.googleapis.com、JitPack（R-24）。

## 6. 性能与容量

- **无负载测试证据**（R-11，P2）：按要求不给未经验证的生产并发容量数字。Agent Run 真实耗时样本：只读 Run 36.9s、规划生成 20s（单用户量级）。
- Hikari 仅 local profile 声明（pool 10/idle 2）；生产默认未显式。
- Redis 用途：限流（Auth/Knowledge/Planning）+ 分布式锁，AOF 已开；无会话存储，Redis 故障时降级路径未文档化（P3）。
- SSE 连接：Last-Event-ID 续传 + 终态收口不重连；连接数上限无显式配置（依赖容器默认），随 R-11 基线监控再评估。

## 7. CI 审计（详见 test-and-ci-coverage.md）

- 仅 ci.yml：push(main,dev)+PR→main+手动；backend job（JDK21 + mvnw verify + **Python 断言 10 个关键 Testcontainers 类真实执行**，防静默跳过）+ frontend job（pnpm frozen-lockfile + lint/typecheck/test/build）；permissions: contents: read；PR 并发取消。
- **gh 实测：最新 HEAD 15f4905 有 CI 记录 run 40062189574 = success**（另有 eef4d12 run 40061629863 success）。CI 覆盖目标提交。
- 无镜像构建/发布 job（R-03）。

## 8. 运维风险汇总

| # | 风险 | 级别 |
| --- | --- | --- |
| 1 | 生产可观测性缺失（R-02） | P1 阻断条件 |
| 2 | 无发布流水线/runbook（R-03） | P1 阻断条件 |
| 3 | REFRESH_COOKIE_SECURE=false + 无 TLS（R-04，详见 security-review.md） | P1 阻断条件 |
| 4 | 生产变量文档不齐 / 无 prod profile（R-12） | P2 |
| 5 | 无负载测试（R-11） | P2 |
| 6 | MinIO 卷升级兼容未验证 + DEMO_OWNER 初始账号（R-13） | P2 |
| 7 | 租约常量不可配 / 无资源 limit / 无代理出网方案（R-24） | P3 |
| 8 | Redis 单点降级路径未文档化 | P3 |
