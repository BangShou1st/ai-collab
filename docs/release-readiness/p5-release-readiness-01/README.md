# P5-RELEASE-READINESS-01：全系统发布准备审查与 Go/No-Go 评估

**任务编号**：P5-RELEASE-READINESS-01
**审计基线**：dev `15f4905`（= origin/dev，工作区干净）· main `9b4f7c5`（全程未触碰）
**CI**：run [40062189574](https://github.com/BangShou1st/ai-collab/actions/runs/40062189574) · head `15f4905` · **completed / success**
**审计日期**：2026-10-24
**本轮性质**：纯审计（零生产代码修改；仅一处文档漂移修正 AGENTS.md V65→V66，属 AGENTS.md 自身授权的顺手修正）

---

## 最终裁决：`RELEASE_CONDITIONAL_GO`

**核心结论一句话**：AI Collab 的核心产品功能（项目协作、知识库、模型接入、AI 规划、Agent）、安全门禁、数据库迁移纪律、CI 质量门禁全部达到发布候选标准，无 P0、无产品类 P1；**唯一不达标的是发布操作面**（备份恢复未演练、可观测性缺失、无发布流水线、TLS/Cookie 生产安全基线），这四项 P1 全部是有限工作量内可补齐的外部条件，而非产品缺陷。满足条件后即可进入发布候选（RC）阶段。

### 发布状态速览

| 维度 | 结论 | 说明 |
| --- | --- | --- |
| A 功能完成度 | PASS | 核心四域经真实端到端验收；可视化域/MCP 为验收缺口非功能缺失 |
| B 测试成熟度 | PASS | 当前 HEAD CI 全绿；五份 P4 真实验收证据有效 |
| C 数据安全 | PASS | 无 P0/P1 产品缺陷；1 项部署配置问题（R-04） |
| D 运行可靠性 | PASS（带条件） | 服务健康、迁移全绿、Agent 失败恢复有双重证据 |
| E 可运维性 | **FAIL（阻断 RC）** | R-01 备份恢复 / R-02 可观测性 / R-03 发布流水线 |
| F 用户体验 | PASS（带备注） | 2 项 P2 体验缺口均有兜底路径 |
| G 发布操作准备 | **FAIL（阻断 RC）** | 无 runbook/镜像流水线（R-03/R-12/R-13） |

### 必须修复（发布候选前，P1，全部为部署/运维面）

1. **R-01 备份与恢复**：编写 PostgreSQL/MinIO/密钥备份脚本，并在隔离环境完成一次真实恢复演练（当前 `DOCUMENTED_NOT_DRILLED`）。
2. **R-02 可观测性**：生产 profile 补 health/metrics 暴露与日志文件滚动策略（当前 management 配置仅存在于 local profile）。
3. **R-03 发布流水线**：产出可追溯镜像（CI 构建或脚本化）+ 发布 runbook（负责人/步骤/回滚条件）。
4. **R-04 TLS/Cookie 安全基线**：compose 的 `REFRESH_COOKIE_SECURE` 改为环境变量注入，部署文档强制生产置 true + TLS 终结。

### 可以延期（P2/P3，发布后第一批次）

- P2：MCP 真实连接验收（或标注实验特性）、work 可视化域冒烟、历史 FAILED Run 重试入口、跨 Run 审批可见性、向量索引、Zen 复验、负载测试基线、生产变量文档。
- P3 共 12 项（死列清理、双份权限判定注释、死代码删除、防连点、KDF 升级等），见 [risk-register.json](risk-register.json)。

### 下一步

按 [remediation-plan.md](remediation-plan.md) 的三阶段整改：S1（RC 前必须，P1×4）→ S2（RC 期间，P2 高优先）→ S3（发布后，P2 低优先 + P3）。完成 S1 后按 [release-checklist.md](release-checklist.md) 执行发布前检查，即可发布 RC。

---

## 本文档集

| 文件 | 内容 |
| --- | --- |
| [feature-inventory.md](feature-inventory.md) | 全系统功能盘点（含 MCP 专项） |
| [business-e2e-matrix.md](business-e2e-matrix.md) | 七大跨模块业务闭环 × 历史证据映射 |
| [security-review.md](security-review.md) | 身份/凭据/SSRF/Agent/Web 安全门禁 |
| [database-and-migration-review.md](database-and-migration-review.md) | 迁移、约束、备份恢复、升级兼容 |
| [operations-readiness.md](operations-readiness.md) | 部署、可观测性、Worker、外部依赖韧性 |
| [ux-readiness.md](ux-readiness.md) | 前端 UX 门禁与 5 项已知观察项核实 |
| [code-quality-review.md](code-quality-review.md) | 架构分层、死代码、重复逻辑审计 |
| [test-and-ci-coverage.md](test-and-ci-coverage.md) | 测试与 CI 有效覆盖、证据有效性复核 |
| [risk-register.json](risk-register.json) | 25 项结构化风险（P0×0 / P1×4 / P2×9 / P3×12） |
| [release-gates.json](release-gates.json) | A–G 七项发布门禁结果 |
| [remediation-plan.md](remediation-plan.md) | 分阶段整改计划 |
| [release-checklist.md](release-checklist.md) | 正式发布前检查清单 |
| [go-no-go-decision.md](go-no-go-decision.md) | 最终裁决与 20 问回答 |

审计过程证据（基线核实、环境实测、CI 查询、子代理审计范围）：[../../../../acceptance-evidence/2026-10-24/p5-release-readiness-01/evidence-index.md](../../acceptance-evidence/2026-10-24/p5-release-readiness-01/evidence-index.md)
