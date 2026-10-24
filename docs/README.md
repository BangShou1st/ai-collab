# AI Collab 文档导航中心

欢迎查阅 AI Collab 项目技术文档库。本项目采用分级分域的文档管理体系，请根据工作场景查阅对应文档。

---

## 1. 核心长期文档（根目录）

| 文档 | 作用与涵盖内容 | 适用对象 |
| --- | --- | --- |
| [系统架构总览 (`architecture.md`)](architecture.md) | 系统总体分层、核心模块调用关系、技术栈与数据流设计 | 所有开发者 / 新人接入 |
| [能力矩阵 (`capability-matrix.md`)](capability-matrix.md) | 四大业务域（项目协作、知识库、AI规划、Agent）的能力交付与基线状态 | 需求梳理 / 验收对照 |
| [本地开发与维护 (`local-development.md`)](local-development.md) | Windows PowerShell 5.1 本地启停、环境配置、日常运维与故障排查 | 日常开发 / 本地调试 |
| [部署与故障恢复 (`deployment-recovery.md`)](deployment-recovery.md) | Docker Compose、Postgres (pgvector)、MinIO、Redis 部署与灾难恢复 | 运维部署 / 基建排查 |
| [后端 Agent 重构设计 (`backend-agent-redesign.md`)](backend-agent-redesign.md) | 以 Spring Boot 承载核心 AI 编排能力的架构设计与选型依据 | AI 协作域深度开发 |

---

## 2. 模块详细设计与进阶规范 (`docs/design/`)

长期有效的系统子模块方案与机制设计：

- [Agent 工具并发隔离与有界准入 (`design/agent-tool-concurrency.md`)](design/agent-tool-concurrency.md)：单 JVM 精确配额、两层轮转、取消安全与指标。
- [Agent 远端结果确认与安全恢复 (`design/agent-remote-outcome.md`)](design/agent-remote-outcome.md)：持久化出站证据、UNKNOWN、规划核对与 Lease fencing。
- [Agent 运行时设计 (`design/agent-runtime-design.md`)](design/agent-runtime-design.md)：工具契约、批次执行、中断决策与取消恢复机制。
- [Agent 运行时维护手册 (`design/agent-runtime-maintenance.md`)](design/agent-runtime-maintenance.md)：运行时状态机、核心类调用图与故障排查清单。
- [Agent 交互体验设计 (`design/agent-experience-design.md`)](design/agent-experience-design.md)：模型配置切换、执行过程卡片、人工审批与流式输出体验。
- [Agent 预算与暂停恢复设计 (`design/agent-budget-and-resume-design.md`)](design/agent-budget-and-resume-design.md)：Token 预算控制、用户主动暂停与恢复设计。
- [AI 能力下一阶段总设计 (`design/ai-next-stage-blueprint.md`)](design/ai-next-stage-blueprint.md)：长上下文压缩、多轮对话与知识库联动演进蓝图。

---

## 3. 标准与规范目录

- **接口定义**：[`docs/api/`](api/) —— 包含 OpenAPI 契约与接口文档。
- **开发规范**：[`docs/development/`](development/) —— 包含代码分支策略、提交约定等。
- **部署指南**：[`docs/deployment/`](deployment/) —— 包含网关部署、环境依赖配置等。
- **自动化测试语料**：[`tests/fixtures/documents/`](../tests/fixtures/documents/) —— 用于知识库 RAG 评测与端到端测试的标准原件。

### 3.1 发布就绪与运维 Runbook (`docs/release-readiness/`)

- [P5 发布准备审计一轮（`release-readiness/p5-release-readiness-01/`](release-readiness/p5-release-readiness-01/README.md)）：全系统发布准备审查，裁决 `RELEASE_CONDITIONAL_GO`，识别四项运维 P1。
- [P5 发布运维阻断项整改（`release-readiness/p5-release-readiness-02/`](release-readiness/p5-release-readiness-02/README.md)）：R-01 备份恢复演练（`ai-collab-deploy/backup/`）、R-02 可观测性基线、R-03 发布流水线与 Runbook、R-04 Refresh Cookie TLS 安全基线，逐项状态见其 `risk-status.json`。
- [P5 RC 构建验证（`release-readiness/p5-rc-validation-01/`](release-readiness/p5-rc-validation-01/README.md)）：Image Job 真实执行验证与 RC 配置复核，裁决 `RC_VALIDATION_ACCEPTED`。
- [P5 部署准备与生产运行方案（`release-readiness/p5-deployment-readiness-01/`](release-readiness/p5-deployment-readiness-01/README.md)）：生产拓扑、密钥与 TLS 方案、14 步部署流程、回滚恢复边界与部署门禁（`deployment-gates.json`），裁决：准备方案就绪、实际部署待环境参数回填。

---

## 4. 自动化验收证据与基准 (`docs/acceptance-evidence/`)

包含按日期严格组织的交付证据目录（测试结果、BSK 浏览器截图、回归基准与脱敏元数据）：

- 目录格式：`docs/acceptance-evidence/YYYY-MM-DD/<task-id>/`
- [P4-RAG-QUALITY-02：长文档召回参数成对复验](acceptance-evidence/2026-10-22/p4-rag-quality-02/README.md)：真实本地 Embedding 对照、生产准入判断与索引引用兼容性。
- 注意：该目录下的部分基准 JSON 由自动化回归测试（如 `RealRetrievalBaselineTest` 等）作为只读断言基准直接引用，**严禁随意改名或删除**。

---

## 5. 历史归档资产库 (`docs/archive/`)

所有已结项的历史迭代过程文档统一归档于此，避免污染顶层目录：

- **任务交接与过渡**：[`docs/archive/handoffs/`](archive/handoffs/)（历史阶段任务书与上下文交接文档）
- **代码审查与评审**：[`docs/archive/reviews/`](archive/reviews/)（阶段性代码审查、复杂度评估与复核记录）
- **阶段交付报告**：[`docs/archive/reports/`](archive/reports/)（历史任务验收交付报告与验证补充）
- **历史提示词与指令**：[`docs/archive/prompts/`](archive/prompts/)（过往大型任务使用的复合优化 Prompt）
- **专项研究与技术决策**：[`docs/archive/research/`](archive/research/)（长上下文压缩阈值、Java 框架选型等历史调研）
