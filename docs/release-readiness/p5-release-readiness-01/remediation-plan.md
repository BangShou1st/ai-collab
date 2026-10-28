# remediation-plan.md — 分阶段整改计划

> 依据 risk-register.json（P0×0 / P1×4 / P2×9 / P3×12）。原则：先解除 RC 阻断，再补证据缺口，最后清技术债。每项含验证方式。

## S1 — 发布候选（RC）前必须完成（P1×4，全部为部署/运维面）

| 顺序 | 风险 | 任务 | 交付物 | 验证完成条件 |
| --- | --- | --- | --- | --- |
| 1 | R-01 备份恢复 | 编写备份脚本（pg_dump 自定义格式 + MinIO mc mirror + MODEL_CONFIG_MASTER_KEY/JWT_SECRET 加密归档说明）；在隔离环境（隔离容器/独立卷，非生产库）完成一次真实恢复演练 | 脚本 + 演练报告归档 acceptance-evidence | 恢复后 flyway_schema_history 完整、关键表行数核对、MinIO 对象数核对、加密 API Key 可解密使用 |
| 2 | R-04 TLS/Cookie | compose 的 `REFRESH_COOKIE_SECURE` 改为 `${REFRESH_COOKIE_SECURE:-true}` 引用；部署文档强制生产置 true + TLS 终结（反向代理）或仅内网可信网络暴露 | compose 改动 + 部署文档章节 | compose config 校验通过；文档含验证步骤（浏览器确认 Set-Cookie Secure 属性） |
| 3 | R-02 可观测性 | 新增生产 profile：management 暴露 health+metrics；logback-spring.xml 滚动文件策略（保留天数/大小）；日志级别显式声明 | 配置文件 | 本地以该 profile 启动 compose，/actuator/health 与 /actuator/metrics 可达，日志文件按策略滚动 |
| 4 | R-03 发布流水线 | 二选一：CI 增加镜像构建 job（tag = git SHA）+ 推送 registry；或脚本化发布 runbook（构建→传输→启动→冒烟→回滚），明确发布负责人与回滚条件 | workflow 改动或 runbook 文档 | 一次从流水线/脚本产出的可追溯镜像（digest 记录） |

S1 完成后，release-gates.json 的 E/G 门禁翻绿 → 可执行 RC 发布。

## S2 — RC 期间补齐（P2 高优先：证据缺口与范围决策）

| # | 风险 | 任务 | 验证完成条件 |
| --- | --- | --- | --- |
| 1 | R-05 MCP | 发布范围决策：a) 授权轮做一次真实只读 MCP 服务器连接+工具发现+Agent 调用验收；或 b) 发布说明标注 MCP 为实验特性 | 验收报告或明确的实验特性标注 |
| 2 | R-06 可视化域 | work 7 个可视化页面 + 通知中心低成本浏览器冒烟（加载/空态/错误/权限） | 冒烟记录归档 |
| 3 | 闭环 3 GAP | 规划页面编辑 + 版本保存 + 确认落库的浏览器复核（可用既有规划快照，无需外部模型） | 复核记录归档 |
| 4 | R-07/R-08 Agent UX | 会话内 Run 历史列表 + 非 latest FAILED Run 重试按钮；审批列表按会话聚合 PENDING | UI 改动 + 浏览器复验 |
| 5 | R-12 配置文档 | .env.example 补齐遗漏变量（AGENT_WORKER_DELAY_MS、AUTH_ALLOWED_ORIGINS、OPENCODE_ZEN_UPSTREAM 等）+ 部署文档出网白名单（opencode.ai / ai-gateway.kilo.ai / generativelanguage.googleapis.com / JitPack） | 文档评审 |

## S3 — 发布后第一批（P2 低优先 + P3 顺手修）

| # | 风险 | 任务 |
| --- | --- | --- |
| 1 | R-09 | 评估数据量后新增 ivfflat/hnsw 迁移（配合 embedding_fingerprint 与维度策略） |
| 2 | R-11 | 建立基线监控（依赖 R-02）后再决定压测；发布说明不承诺并发容量 |
| 3 | R-10 | Zen Free 上游恢复后经授权复验 |
| 4 | R-13 | MinIO 旧卷升级隔离演练；DEMO_OWNER 首登强制改密 |
| 5 | R-14/R-15/R-16/R-17/R-21/R-22/R-23/R-25 | 技术债批次：影子列随下次相关迁移删列、AgentToolPolicy 注释、删 agentApi.resume、cancelBusy、ProjectEmbedding 分层重构、planning ElMessageBox、双 HTTP 封装互标注释（R-22 本轮已修） |
| 6 | R-18/R-19/R-20 | 安全 P3：KDF 升级（需先决策密文兼容策略）、邀请预览裁剪邮箱、DNS TOCTOU 风险接受记录或 IP 直连改造 |

## 明确不做

- 不拆分 AgentRuntimeCoordinator/AgentRunEventRecorder 等大类的抽象层重构（无实际收益）。
- 不删除 agent_run 影子列的独立迁移（避免热点表单独迁移风险）。
- 不在本轮开展任何视觉重构或新增功能。
