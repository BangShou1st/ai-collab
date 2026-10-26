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

- [Agent 可观测性与可靠性诊断 (`design/agent-observability.md`)](design/agent-observability.md)：持久化状态快照、UNKNOWN 年龄、提交一致指标与安全日志关联。
- [Agent 工具并发隔离与有界准入 (`design/agent-tool-concurrency.md`)](design/agent-tool-concurrency.md)：单 JVM 精确配额、两层轮转、取消安全与指标。
- [Agent 远端结果确认与安全恢复 (`design/agent-remote-outcome.md`)](design/agent-remote-outcome.md)：持久化出站证据、UNKNOWN、规划核对与 Lease fencing。
- [Agent 运行时设计 (`design/agent-runtime-design.md`)](design/agent-runtime-design.md)：工具契约、批次执行、中断决策与取消恢复机制。
- [Agent 运行时维护手册 (`design/agent-runtime-maintenance.md`)](design/agent-runtime-maintenance.md)：运行时状态机、核心类调用图与故障排查清单。
- [Agent 交互体验设计 (`design/agent-experience-design.md`)](design/agent-experience-design.md)：模型配置切换、执行过程卡片、人工审批与流式输出体验。
- [Agent 预算与暂停恢复设计 (`design/agent-budget-and-resume-design.md`)](design/agent-budget-and-resume-design.md)：Token 预算控制、用户主动暂停与恢复设计。
- [AI 能力下一阶段总设计 (`design/ai-next-stage-blueprint.md`)](design/ai-next-stage-blueprint.md)：长上下文压缩、多轮对话与知识库联动演进蓝图。
- [RAG 检索质量、证据与成本优化 (`design/rag-quality-and-evidence.md`)](design/rag-quality-and-evidence.md)：QA 与 Agent 双路径检索基线、候选召回/上下文选择受控实验、引用契约与生产准入门槛（P6-RAG-QUALITY-01）。
- [独立 RAG 泛化评测基准 (`design/rag-generalization-benchmark.md`)](design/rag-generalization-benchmark.md)：独立于 P4～P8 冻结集的 23 篇合成语料与 39 道评测题（dev/holdout 划分与冻结）、八个泛化失效维度、QA/Agent 双路径测量协议与指标（P9-RAG-GENERALIZATION-01）。
- [真实回答质量与引用可信度评测 (`design/rag-answer-quality-evaluation.md`)](design/rag-answer-quality-evaluation.md)：两层评测架构（离线契约/真实生成）、版本冲突与拒答判定规则、QA 与 Agent 引用协议差异、机械可判与人工审查边界（P10-RAG-ANSWER-QUALITY-01）。
- [Agent 最终来源与引用契约 (`design/agent-source-citation-contract.md`)](design/agent-source-citation-contract.md)：已读来源、显式引用与语义支持的边界，子来源收口时的身份与原摘录校验。
- [Provider 接入自动化与能力发现 (`design/provider-onboarding-and-capability-detection.md`)](design/provider-onboarding-and-capability-detection.md)：服务商档案、URL/Path 组合规则、探针专用执行边界、检测并配置与 CAS 写回（P7-PROVIDER-ONBOARDING-01）。

---

## 3. 标准与规范目录

- [模型 HTTP 网络代理](development/model-network-proxy.md)：JDK 原生 OS 发现、JVM/Surefire/IDE 启动参数、客户端级环境变量回退、本地排除与无 Chat 诊断（P10-NETWORK-PROXY-AUTO-01）。

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
- [P10-SCORER-ROBUSTNESS-AUDIT-01：评分器全规则组合与作用域风险审计](acceptance-evidence/2026-10-26/p10-scorer-robustness-audit-01/README.md)：系统性只读审计，建立 20 项规则交互矩阵与 10 项风险登记；确证全局双重否定检测导致独立违规漏报与拒答冲突检测失守等 7 项已复现缺陷；零生产代码变更，零外部模型调用；[研究](archive/research/p10-scorer-robustness-audit-01-research-20261026.md)与[交付](archive/reports/p10-scorer-robustness-audit-01-delivery-20261026.md)。
- [P10-SCORER-ROBUSTNESS-03：例外条款作用域隔离与漏报防护](acceptance-evidence/2026-10-26/p10-scorer-robustness-03/README.md)：彻底修复例外条款分支全句级扫描缺陷，建立局部子句绑定与紧邻从句修饰范围界定，禁止词限定局部子句，阻断转折渗透与逆向覆盖，特批条件许可安全转人工复核；零外部模型调用，零生产代码变更。
- [P10-SCORER-ROBUSTNESS-02：认识模态作用域与转折分句漏报修复](acceptance-evidence/2026-10-26/p10-scorer-robustness-02/README.md)：修复评分器认识模态判断作用域过宽缺陷，隔离转折连词前后的独立分句主张，支持同句多 occurrence 判定，复杂反差安全转人工复核；零外部模型调用，零生产代码变更。
- [P10-SCORER-ROBUSTNESS-01：真实回答评分器稳健性专项](acceptance-evidence/2026-10-26/p10-scorer-robustness-01/README.md)：评分器 2.0-robust 重构，解决 Markdown 强调、否定语境（AQ06 粗体否定）与认识模态（AQ12 否定从句）导致的机械假阳性误报；7/7 肯定违规反例 100% 检出率，双重否定/复杂例外转人工复核；零外部模型调用，零生产代码变更；[研究](archive/research/p10-scorer-robustness-01-research-20261026.md)与[交付](archive/reports/p10-scorer-robustness-01-delivery-20261026.md)。
- [P10-REAL-GENERATION-02 文档与证据一致性勘误（Errata）](acceptance-evidence/2026-10-26/p10-real-generation-02-errata/README.md)：澄清 P10-02 README 批次对比表预检阶段遗留草稿原因，界定自动化工具链阶段裁决（`REQUIRES_PER_QUESTION_REVIEW`）与人工复核阶段裁决（`PASS_WITH_SCORER_LIMITATIONS`）的演进关系，未破坏原冻结证据与 manifest。
- [P10-REAL-GENERATION-01：真实 QA 生成受控执行](acceptance-evidence/2026-10-26/p10-real-generation-01/README.md)：已授权，首题一次真实客户端传输超时，后续 7 题按规则停止，模型质量不可评估；7897 代理排查、默认关闭门禁与独立回归回执；[研究](archive/research/p10-real-generation-01-research-20261026.md)与[交付](archive/reports/p10-real-generation-01-delivery-20261026.md)。
- [P10-REAL-GENERATION-02：真实模型端到端验收](acceptance-evidence/2026-10-26/p10-real-generation-02/README.md)：独立新批次，已获授权执行 8 次真实 Chat 并全部取得回答——执行 `MEASUREMENT_EXECUTED`、质量 `PASS_WITH_SCORER_LIMITATIONS`（3 项机械高严重度复核为评分器字面匹配假阳性，无模型事实错误）；首次取得可复核的真实回答质量基线，独立人工复核 0 题；[研究](archive/research/p10-real-generation-02-research-20261026.md)与[交付](archive/reports/p10-real-generation-02-delivery-20261026.md)。
- [P8-RAG-QUALITY-FIX-01：有界补读、互补事实与引用契约实验](acceptance-evidence/2026-10-25/p8-rag-quality-fix-01/README.md)：Q02与固定8来源候选的收益/退化、生产策略保留、子来源失效与摘录投影最小修复；[研究](archive/research/p8-rag-quality-fix-01-research-20261025.md)与[交付](archive/reports/p8-rag-quality-fix-01-delivery-20261025.md)。
- [P8-RAG-QUALITY-FIX-01 CI门控补充验收](acceptance-evidence/2026-10-26/p8-rag-quality-fix-01-ci-gate/README.md)：首次正式CI失败的原始回执、本地模型测试显式门控、默认禁用/显式启用双路径与源码版本映射；[补充报告](archive/reports/p8-rag-quality-fix-01-ci-gate-20261026.md)。
- [P8-AGENT-RAG-E2E-QUALITY-01：Agent 与 RAG 跨层质量评测](acceptance-evidence/2026-10-25/p8-agent-rag-e2e-quality-01/README.md)：冻结题集真实 Ollama/pgvector 复测、脚本模型驱动的实际工具与持久化链路、失败分层及独立修复证据；真实生成层另列授权限制。
- [P9-RAG-GENERALIZATION-01 统计口径 Errata](acceptance-evidence/2026-10-26/p9-rag-generalization-01-errata/README.md)：长文档实际 721–3,353 code points（6,000+ 未覆盖）、唯一 23 篇/161 chunks（175 为累计入库事件）、泛化结论边界收敛；冻结原始证据未改动；[errata 报告](archive/reports/p9-rag-generalization-01-errata-20261026.md)。
- [P10-RAG-ANSWER-QUALITY-01：回答质量离线契约评测](acceptance-evidence/2026-10-26/p10-rag-answer-quality-01/README.md)：14 题冻结评测子集、机械评分器与 Rubric、8 类高严重度失败模式确定性检出、QA/Agent 引用协议差异与未定义行为；真实生成层未授权未执行；[交付报告](archive/reports/p10-rag-answer-quality-01-delivery-20261026.md)。
- [P7-BUSINESS-E2E-01：真实业务链路与发布前验收](acceptance-evidence/2026-10-25/p7-business-e2e-01/README.md)：E01–E12 真实浏览器验收矩阵、SSE 断线恢复复现、审批/权限后端强制证据、魔搭 ModelScope 自定义 Provider 与模型发现实测、404 错误映射与 Provider 卡片布局修复。
- [P7-PROVIDER-ONBOARDING-01：AI 模型接入自动化与能力发现](acceptance-evidence/2026-10-25/p7-provider-onboarding-01/README.md)：探针专用执行边界（未声明能力真实可测）、检测并配置与 CAS 写回、URL/Path 重复前缀自动修正、ModelScope/Gemini 真实接入验收与 T01–T10 矩阵。
- [P6-C：Agent 可观测性与可靠性诊断](acceptance-evidence/2026-10-25/p6-agent-observability-01/README.md)：状态快照、UNKNOWN索引、提交一致指标、MDC与Actuator安全验收。
- [P6-RAG-QUALITY-01：RAG 检索质量、证据与引用契约](acceptance-evidence/2026-10-25/p6-rag-quality-01/README.md)：QA/Agent 双路径真实基线复现（Q02/Q03/Q09）、受控候选补充与选择实验、QA 每文档名额均衡准入与分组引用契约修复。
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
