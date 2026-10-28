# P5-DEPLOYMENT-READINESS-01 — 正式部署环境准备与生产运行方案设计

- 任务编号：`P5-DEPLOYMENT-READINESS-01`
- 日期：2026-10-24
- 上一阶段：`p5-rc-validation-01`，裁决 `RC_VALIDATION_ACCEPTED`
- 本轮性质：部署前静态审计与方案设计。**未合并 PR、未部署生产、未执行生产迁移、未修改分支保护。**

## 一、基线核实结果（2026-10-24 实测）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 当前分支 | `dev`，工作区干净（无未提交改动） | `git status` |
| dev HEAD | `436d06eada58b34f0328b60f07957f8c1e48ba3c`（与 `origin/dev` 一致） | `git rev-parse dev origin/dev` |
| main HEAD | `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（与 `origin/main` 一致，领先于 RC 轮记录，含 RC 轮 docs 提交之外的历史） | `git rev-parse main origin/main` |
| PR #18 | OPEN（Draft），head=dev@`436d06e`，base=main，`MERGEABLE`，正文保持 DO NOT MERGE 约定 | `gh pr view 18` |
| PR #18 最新 CI | 全绿：Backend (Maven verify)、Frontend (pnpm)、Image build & container smoke 均 SUCCESS（PR 触发的 run 中三 Job 真实执行；dev push 触发的 run 中 Image Job 按设计 SKIPPED，与 R-03 修正结论一致） | `gh pr view 18 --json statusCheckRollup`，runs 40104720601 / 40104717941 |
| 分支保护（main） | 存在；required checks 仅 `Backend (Maven verify)`、`Frontend (pnpm)`；enforce_admins=true；禁 force push / 禁删除 | `gh api repos/.../branches/main/protection` |

注意：`main` 当前 HEAD 为 `9b4f7c5`，与 RC 轮记录一致；PR #18 合并后 `main` 将前进至 `dev` 的合并结果。

## 二、文档索引

| 文件 | 内容 |
| --- | --- |
| [environment-requirements.md](environment-requirements.md) | 服务器、OS、Docker、端口、出网白名单、域名与证书要求 |
| [production-architecture.md](production-architecture.md) | 生产拓扑、组件职责、端口矩阵、前端托管方案 |
| [secrets-and-configuration.md](secrets-and-configuration.md) | 密钥清单（用途/生成/注入/轮换/泄露应急）、生产 `.env` 基线、compose 透传缺口 |
| [tls-and-networking.md](tls-and-networking.md) | TLS 终止、CORS、Cookie、N-02 转发头深入分析与决策流程 |
| [deployment-procedure.md](deployment-procedure.md) | 14 步生产部署流程（前置条件/命令/成功判据/失败处置） |
| [rollback-and-recovery.md](rollback-and-recovery.md) | 应用回滚与数据恢复的边界、既有脚本复用、未验证项 |
| [observability-and-alerting.md](observability-and-alerting.md) | 可观测性现状、缺口与最小可用方案 |
| [open-questions.md](open-questions.md) | 必须由用户提供的环境决策清单 |
| [deployment-gates.json](deployment-gates.json) | 部署门禁（结构化，含证据状态） |
| [go-no-go.md](go-no-go.md) | 最终裁决与 14 项交付问题回答 |

## 三、证据状态图例

- `VERIFIED`：本轮有真实命令/源码证据。
- `DOCUMENTED`：文档已有说明，但未在本轮实际执行。
- `ENVIRONMENT_DEPENDENT`：依赖尚未确认的部署环境，无法在仓库内定案。
- `NOT_VERIFIED`：尚无充分证据，不得当作已通过。

## 四、核心结论（摘要）

1. **现有 Compose + 备份/恢复/回滚 Runbook 已构成可行的部署基础**（`DOCUMENTED` + 关键路径 `VERIFIED`），推荐形态：单台 Linux 主机 + Docker Compose + 反向代理（TLS 终止、托管前端静态文件、转发 `/api/v1`）。
2. **发现两个部署阻断级缺口**（此前评审未登记为独立风险）：
   - **G-1 compose 未透传 Embedding 配置**：`docker-compose.yml` app 服务 `environment` 缺 `EMBEDDING_ENABLED/BASE_URL/API_KEY/MODEL/DIMENSIONS/BATCH_SIZE` 等条目，仅靠 `.env` 无法在生产启用 RAG 知识库（核心业务域之一）。修复为最小改动（补 environment 条目）。
   - **G-2 forward-headers 未配置且有实际功能影响**：`AuthRateLimiter` 注册限流 key 只含 `request.getRemoteAddr()`（`AuthRateLimiter.java:68`），反代后所有用户共享代理 IP，注册限流将退化为全局 10 次/小时；代码注释预期"由 Spring ForwardedHeaderFilter 解析"（`AuthRateLimiter.java:29`）但 `server.forward-headers-strategy` 未配置（Spring Boot 默认不解析转发头）。必须在确认代理拓扑后显式决策，不得盲目设置。
3. **环境参数全部待用户提供**：域名、服务器、TLS 证书、日志/告警通道、备份位置与异地副本、Provider 出网方式、`AUTH_ALLOWED_ORIGINS` 生产值等，详见 [open-questions.md](open-questions.md)。
4. **裁决**：部署**准备方案**已就绪（CONDITIONAL GO for readiness）；**实际部署在环境参数回填且 G-1/G-2 处置完成前为 NOT GO**。详见 [go-no-go.md](go-no-go.md)。
