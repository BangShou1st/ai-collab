# Go / No-Go 裁决（go-no-go）

- 任务：`P5-DEPLOYMENT-READINESS-01`
- 日期：2026-10-24
- 裁决人：AI 发布负责人（本轮）；**正式发布批准人须为人**（待指定，open-questions Q13）

## 裁决

> **部署准备方案：CONDITIONAL GO（就绪）** —— 部署拓扑、密钥方案、14 步流程、回滚恢复边界、可观测基线已形成可交给其他工程师执行的完整方案。
>
> **实际执行生产部署：NOT GO** —— 7 项阻断门禁未关闭（G-1、G-2、G-TLS、G-SECRETS、G-FRONTEND-HOSTING、G-BACKUP-OPS、G-REGRESSION-ITEMS，见 deployment-gates.json），其中 5 项依赖用户环境决策，G-1/G-2 需最小代码/配置变更后经 CI 验证。

RC 验证成功（`RC_VALIDATION_ACCEPTED`）仅代表构建与发布前技术验证通过，**不构成生产环境已就绪的推断**。

## 一、14 项交付问题回答

**1. 当前 Git 与 PR 状态如何？**
`dev` @ `436d06e` 与 `origin/dev` 同步、工作区干净；`main`/`origin/main` @ `9b4f7c5`；PR #18 OPEN（Draft，dev→main，MERGEABLE），最新 CI 全绿（runs 40104720601/40104717941，Image Job 在 PR 触发 run 真实执行）。全部 `VERIFIED`。

**2. 现有 Compose 是否具备目标环境的部署基础？**
具备。4 服务（pg/redis/minio/app）+ healthcheck + 命名卷 + 默认 profile 密钥注入已补齐，CI 已真实拉起容器冒烟。两个缺口：G-1（Embedding/Planning 配置未透传，RAG 生产不可用）与端口硬化（基础设施端口现发布 `0.0.0.0`，生产建议改绑 `127.0.0.1`）。`VERIFIED`。

**3. 部署需要哪些服务器和网络条件？**
Linux 主机 + Docker Engine + Compose v2 + NTP；磁盘 ≥ 数据 ×2；应用主机**直连**出网白名单（opencode.ai、ai-gateway.kilo.ai、Gemini 等——代码不支持代理出网，见 environment-requirements.md §3）；内存无压测证据，不承诺容量（R-11）。服务器规格 `ENVIRONMENT_DEPENDENT`。

**4. TLS、域名、CORS 与 Cookie 如何配置？**
TLS 终止于反代，app 保持 HTTP 8080 且不暴露公网；域名/DNS/证书待用户（Q1/Q2）；`AUTH_ALLOWED_ORIGINS=https://<生产域名>`（精确 Origin，禁通配符）；Refresh Cookie 生产强制 `Secure; HttpOnly; SameSite=Strict; Path=/api/v1/auth`（三重保障 + 启动守卫，配置不安全直接拒启）。`DOCUMENTED` + 值 `ENVIRONMENT_DEPENDENT`。

**5. 需要哪些密钥，如何安全注入？**
7 组：POSTGRES/REDIS/MINIO 凭据、JWT_SECRET、MODEL_CONFIG_MASTER_KEY（**不可更换**，必须离线归档）、全局模型 API Key、TLS 私钥。经密管/离线渠道 → 主机 `.env`（chmod 600）→ compose 注入；禁止入库、禁明文传输；轮换与泄露应急逐项见 secrets-and-configuration.md。

**6. Gemini/Kilo 等 Provider 如何出网？**
代码层一律**直连、无代理**（`NO_PROXY` 显式写死 + 预设策略 directNoProxy）。白名单：opencode.ai / ai-gateway.kilo.ai（匿名，free-only 保持 true）/ generativelanguage.googleapis.com / 用户配置 base / 可选 Ollama / MCP 白名单主机。若生产必须代理出网 → 当前不支持，需提前决策（Q3b）。`VERIFIED`。

**7. PostgreSQL 与 MinIO 如何备份和恢复？**
每日 + 发布前手动：`backup-postgres.sh`（pg_dump -Fc + SHA256 + pg_restore --list 校验）与 `backup-minio.sh`（mc mirror + mc diff 零差异）。恢复只到显式指定的隔离目标，`verify-restore-consistency.sh` 核对行数与 object_key 一一对应；**两侧必须整体恢复**。同机演练已通过；异机/异地未演练（Q6）。`VERIFIED`（同机）/ `NOT_VERIFIED`（异机）。

**8. 告警和日志是否已经接通？**
**没有。** 已具备：健康探针体系、requestId 关联、日志无密钥基线、stdout 输出。未接通：日志采集器、指标平台、告警通道——全部 `[部署环境补充]`（Q12）。健康端点存在 ≠ 告警就绪（rc-review 原文边界）。`VERIFIED`（具备部分）/ `ENVIRONMENT_DEPENDENT`（缺口）。

**9. N-01 是否需要补充 GitHub 必需检查？**
需要，建议但非部署阻断。实测 main 保护：required checks 仅 `Backend (Maven verify)`、`Frontend (pnpm)`，**Image Job 不在其中**，且 Image Job 只在 PR→main/main push 触发——恰是进入 main 的路径，把它设为 required 完全可行。最小修改：Settings → Branches → main → Add `Image build & container smoke`（名称与 ci.yml job 显示名精确一致）。能防止：Image Job 失败的 PR 被合并。**未经授权本轮未改动**（Q14）。`VERIFIED`（现状）。

**10. N-02 应如何根据实际代理拓扑处理？**
已从"待确认"升级为"有实证影响"：反代下注册限流退化为全局 10 次/小时（`AuthRateLimiter.java:68` key 只含 RemoteAddr，strategy 未配置）。处理：确认单一受信反代且其总是设置/覆盖 X-Forwarded-* 头后，compose 增加 `SERVER_FORWARD_HEADERS_STRATEGY: framework`（最小变更，无需改代码），部署日按 tls-and-networking.md §4.4 三步验证；拓扑不满足前提则不配置并采用缓解（调高 register-per-hour 或补充按用户名限流——后者属代码变更）。不得盲目设置。`ENVIRONMENT_DEPENDENT`（决策）+ `VERIFIED`（影响面证据）。

**11. 哪些问题必须由用户提供环境信息？**
open-questions.md Q1–Q14：域名/TLS/镜像分发/出网代理/服务器、备份位置与异地与责任人/跨机演练/密钥归档保管、AUTH_ALLOWED_ORIGINS、注册策略、Provider 清单、反代转发头能力、日志告警通道、发布窗口与风险接受负责人、分支保护授权。

**12. 哪些问题必须在首次部署前解决？**
阻断门禁 7 项：①G-1 compose 补 EMBEDDING_*/PLANNING_* 透传（最小 diff，走 dev 提交）；②G-2/N-02 决策落定；③TLS+域名+反代+前端托管就绪；④生产 `.env` 与密钥下发（含 master key 归档）；⑤备份调度+生产备份位置（异地可后补但需明示）；⑥历史遗留风险知情接受（R-10/R-13/R-11/MCP 实验特性/可视化域）；⑦发布批准人与风险接受负责人指定。N-01 与端口硬化建议同批完成但非阻断。

**13. 当前是否具备正式部署准备条件？**
**具备**：方案、Runbook、脚本、门禁全部就绪并区分证据状态。**不具备**：直接开工部署的条件——等 Q1–Q14 回答与 G-1/G-2 变更经 CI 验证。

**14. 下一步需要用户作出哪些明确决策？**
1) 回答 open-questions Q1–Q14（至少 A/B/C 组）；2) 授权 G-1（+可选 G-2 载体、端口硬化）作为最小 compose 修改提交 dev；3) 授权 N-01 分支保护修改；4) 批准 PR #18 合并时机（合并须人工触发）；5) 指定发布批准人与风险接受负责人；6) 确定首次发布窗口。

## 二、本轮红线执行情况

- 未合并 PR #18、未 push main、未创建 Release、未部署生产、未执行生产迁移、未在真实业务库测试恢复、未修改生产凭据、未做破坏性清理、未放宽任何安全检查、未开展新功能开发。仅新增本目录文档并提交 `dev`。
