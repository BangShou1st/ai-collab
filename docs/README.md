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
- [账号生命周期：忘记密码、邮箱验证与统一邮件服务 (`design/account-lifecycle.md`)](design/account-lifecycle.md)：统一可信公开地址 `APP_PUBLIC_URL`、一次性令牌（哈希存储/原子消费）、防枚举响应、AFTER_COMMIT 邮件投递与部署环境矩阵（P7.1-D/E）。
- [飞书知识生命周期与恢复约束 (`design/feishu-knowledge-lifecycle.md`)](design/feishu-knowledge-lifecycle.md)：OAuth PKCE 用户授权流程、Token 单次使用与自动刷新轮换、文件夹差异扫描、Webhook 撤权事件验签与数据隔离安全。
- [Agent 工程与产品架构改造设计 2026-11-10 (`design/AI-Collab-2026-11-10.md`)](design/AI-Collab-2026-11-10.md)：六工作包（AC-01 观测评测基线、AC-02 上下文成本治理、AC-03 Agent/Tool/Workflow 边界、AC-04 自有 MCP Server、AC-05 关系增强检索、AC-06 业务中性化）的实施设计与分批交付顺序（改造方案，非完成声明）。
- [AI-CostOps Java 后端与可靠性改造设计 (`design/AI-CostOps-2026-11-10-Java后端与可靠性改造设计.md`)](design/AI-CostOps-2026-11-10-Java后端与可靠性改造设计.md)：与上篇关联的独立 CostOps 项目改造方案，业务概念可共用，不引入运行时依赖。
- [AC-07 Agent Memory V2 设计补充 (`design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md`)](design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md)：跨会话可召回、来源可验证、失效可治理的项目长期记忆系统设计——记忆候选审批（Dreaming 只提议）、来源撤销失效、按需召回与 Token 预算协作；Redis/pgvector/私人记忆为条件性后续阶段（P0～P4 分批交付）。
- [Python AI 能力平面（ADR，`design/python-ai-capability-plane-2026-11-11.md`）](design/python-ai-capability-plane-2026-11-11.md)：双平面架构——Java 控制面保留权限、审批、事务、租约、SSE、密钥与账本；Python 承担提示词、rerank、query 理解、单轮决策、规划草稿、记忆候选提取与离线评测等认知计算（不连 DB、不持密钥、不执行不可逆动作，V2.1 §1.4）。含原 AC 路线图映射、job token 契约、V90+ 表设计、Python 技术栈与分波次门禁。
- [Rerank 延迟预算与可观测设计 (`design/rerank-latency-budget-2026-11-12.md`)](design/rerank-latency-budget-2026-11-12.md)：明确 P1 的 800ms 为「不含模型调用的服务开销」，模型延迟单列观测/告警；定义 overhead/model/e2e 三口径、stub 基准与 staging 测量协议、W3.1 只做可观测不改行为的施工范围。
- [AI 模型配置与多能力 Provider 设计 (`design/model-provider-configuration-2026-11-12.md`)](design/model-provider-configuration-2026-11-12.md)：统一 CHAT / EMBEDDING / RERANK 的 Provider、模型档案、能力档案、测试/发布/回滚与运行时解析；Reranker 与 Embedding 共用生命周期但使用不同 capability schema，禁止硬编码模型与 endpoint。
- [Provider 发布最小延迟准入 (`design/provider-publish-admission-2026-11-13.md`)](design/provider-publish-admission-2026-11-13.md)：管理员多次测试记录 P50/P95、超时与失败，结果绑定 `capability_config_version`；配置变化或明显超时拒绝发布，编辑已共享 Provider 时自动撤销共享。
- [MCP Provider 只读准入 (`design/mcp-provider-admission-2026-11-13.md`)](design/mcp-provider-admission-2026-11-13.md)：V99 准入表、`DRAFT/APPROVED/REVOKED` 状态机、服务端 Provider 类型推导、Gateway/旧 Provider 双路径强制与真实进程证据。
- [Rerank 产品边界：inline 8 / 3s (`design/rerank-inline-policy-2026-11-13.md`)](design/rerank-inline-policy-2026-11-13.md)：把用户关键路径固定为 8 候选 / 3000ms；20/50 仅允许 shadow/offline，async 明确 `ASYNC_NOT_IMPLEMENTED`，超限回退 Java 启发式。
- [W6 MCP Gateway 内部契约 v1 (`api/mcp-gateway-internal-v1.md`)](api/mcp-gateway-internal-v1.md)：Java Gateway ↔ Python Host 的 JSON-RPC、规范化、逐工具/目录 SHA-256、错误码与审计边界；P1 default-off，仅用于内部只读切片。
- [AC07-P0 Memory 基线审计（`acceptance-evidence/2026-11-10/AC07-P0-memory-baseline/`）](acceptance-evidence/2026-11-10/AC07-P0-memory-baseline/README.md)：Memory 模块现状核对（file:line）、M-01～M-22 差距矩阵、sourceType/sourceId 校验缺口实锤（M-09，P1 第一波已修复）、25 个跨会话冻结评测场景与 LEGACY 基线方案。
- [AC07-P1 验收门禁补齐（`acceptance-evidence/2026-11-11/AC07-P1-gates/`）](acceptance-evidence/2026-11-11/AC07-P1-gates/README.md)：逐条核对 P0 报告 §8.5 的 5 项 P1 门禁，新增真实 PostgreSQL 集成测试覆盖成员移除（M-07）、`list()` 撤权三段过滤（M-05）、disable 后召回与工具可见性（M-04）、同版本并发 CAS（M-13）；零生产代码变更。
- [AC-P1 第一波：全局模型窗口档案（`acceptance-evidence/2026-11-11/AC-P1-window-profile/`）](acceptance-evidence/2026-11-11/AC-P1-window-profile/README.md)：V89 全局 `ai_model_profile` 表与管理 API（档案优先于 `window-overrides`，`UNVERIFIED` 不冒充已核实）；默认无档案时行为不变。J0-1 已统一 Token 计量，J0-2 已取消 v2 的 256K 触发顶；v1 旧运行按持久化策略保留兼容上限，详见 [`archive/report/j0-2-window-policy-20261111.md`](archive/report/j0-2-window-policy-20261111.md)。
- [J1 清理批次：Legacy / 文档范围 / composerV2 / 问答 Prompt (`archive/report/j1-cleanup-20261111.md`)](archive/report/j1-cleanup-20261111.md)：J1-1（Legacy 退出）与 J1-2（文档范围结构化）按停止条件**未执行删除**——前者缺生产 CHAT-only 用量证据且 `source_mode IS NULL` 是历史写调用的安全兜底，后者 Agent 侧无文档范围选择器、删正则会让“只根据《X》”静默退回全项目检索；J1-3 已删除 `composer-v2=false` 回退（所有 Run 固定 v2），J1-4 已把三份问答 Prompt 收敛到 `KnowledgePromptProvider` 临时单例。本批无迁移、无前端改动。
- [P0 Python 认知面骨架（`archive/report/p0-ai-worker-skeleton-20261111.md`）](archive/report/p0-ai-worker-skeleton-20261111.md)：新建独立子项目 `ai-collab-ai-worker/`（CPython 3.13 + uv + FastAPI + Pydantic v2），交付 `schemaVersion=1` 跨语言契约与 4 份 JSON Schema 快照、统一错误信封与 9 个错误码、PromptPack 资产与 manifest（`sha256`/`chars`/`variables` 硬校验）及两个查询端点、`ai-eval` canonical JSON + SHA-256 指纹 CLI、三层 184 项测试、禁入依赖断言、Dockerfile 与独立 `python-worker` CI job；零 DB、零密钥、零外呼，无迁移、无 Java/前端改动。
- [J2 PromptPack 接入（`archive/report/j2-prompt-pack-20261111.md`）](archive/report/j2-prompt-pack-20261111.md)：Prompt 正文从 Java 迁到 Python 版本化资产（14 条 key，含 `knowledge.system`/`knowledge.user` 与六个 Scenario 的 instruction/outputContract），Java 侧新增 `PromptCatalog`（启动预取 + 进程内缓存 + 二次 sha256 校验 + 刷新失败保留上次成功版本 + 快速失败 `AI_PROMPT_UNAVAILABLE`）；删除 `KnowledgePromptProvider` 与六个 Scenario 的 Prompt 正文；`promptVersion` 可在 Run 快照与 `knowledge_message`（V90）追溯；`prompt.registry.enabled=false` 是真实回退（内置只读 fallback 包，与 PromptPack 同一份字节）。施工契约与冻结映射见 [`archive/handoffs/j2-prompt-pack-freeze-20261111.md`](archive/handoffs/j2-prompt-pack-freeze-20261111.md)。
- [J3/P1 rerank 接入（`archive/report/j3-rerank-20261112.md`）](archive/report/j3-rerank-20261112.md)：Python 新增 `POST /v1/rag/rerank`（50 候选/2000 字符、deadline 3s、幂等 10 分钟、覆盖全部 id）与唯一的 Java 内部模型网关客户端；Java 新增 `rerank.mode=java|shadow|python`（默认 java 行为不变、shadow 非阻塞、python 失败回退 + `PARTIAL_SCORE`）、`RerankGatewayClient` 与 `/internal/ai/v1/model-calls`；compose 增加 `ai` internal 网与无宿主端口的 worker。staging 真实模型打通：shadow top-8 重合率 1.0、用户可见延迟不变；冻结集 NDCG@8 0.9791 vs 0.9614、支持率持平；**e2e P95≈5.05s 未达 800ms 门禁**，真实用户 canary `NOT_CLAIMED`。冻结与修正见 [`archive/handoffs/w3-rerank-freeze-20261112.md`](archive/handoffs/w3-rerank-freeze-20261112.md)。
- [AC06 端到端业务闭环验收（`acceptance-evidence/2026-11-10/AC06-E2E-business-loop-01/`）](acceptance-evidence/2026-11-10/AC06-E2E-business-loop-01/README.md)：产品研发项目九步真实链路（上传→索引→引用可验证检索→规划→确认落库→进度→Agent 分析→周报），7 步 PASS、2 步叙述层 NOT_VERIFIED（免费模型截断），含缺口与最小修复方案。
- [AC-P0 Trace 与评测基线审计（`acceptance-evidence/2026-11-10/AC-P0-trace-baseline/`）](acceptance-evidence/2026-11-10/AC-P0-trace-baseline/README.md)：Run/Step/模型调用/工具调用关联 ID 现状图谱、§3.1 字段差距表（已有 8/部分 7/缺失 3）、§3.2 故障可定位七问逐答、重复记账五层防护核查、40–49 条冻结评测集 100% 继承方案与 AC-01 实施前置清单。

---

## 3. 标准与规范目录

- [模型 HTTP 网络代理](development/model-network-proxy.md)：JDK 原生 OS 发现、JVM/Surefire/IDE 启动参数、客户端级环境变量回退、本地排除与无 Chat 诊断（P10-NETWORK-PROXY-AUTO-01）。
- [飞书 OAuth 故障排查](development/feishu-oauth-troubleshooting.md)：授权/令牌端点配对、常见错误码（含 20049）与排查步骤。

- **接口定义**：[`docs/api/`](api/) —— 包含 OpenAPI 契约与接口文档。
- **开发规范**：[`docs/development/`](development/) —— 包含代码分支策略、提交约定等。
- **部署指南**：[`docs/deployment/`](deployment/) —— 包含网关部署、环境依赖配置等。
- [P0 生产部署基础](deployment/production-foundation.md)：独立生产拓扑、代理信任边界、备份/告警及发布回滚要求；真实环境仍需验收。
- [本地一键安装指南](deployment/local-install.md)：Windows/Linux/macOS 普通用户一键部署与日常维护（仅需 Docker）。
- [服务器部署指南](deployment/server-install.md)：单台 Linux 服务器 HTTPS 一键部署、定时备份与受控升级。
- [旧部署方式迁移指南](deployment/migration.md)：开发环境、旧生产部署、Cloudflare Pages 与管理员初始化的兼容性说明。
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
- [W3 inline 产品边界（`acceptance-evidence/2026-11-13/w3-inline-policy/`）](acceptance-evidence/2026-11-13/w3-inline-policy/report.md)：Java 定向 28/28 绿（含 8 候选 510 样本 overhead P95 22.992ms），9/20/50 候选 inline 零外呼并回退；本地 Qwen3-Reranker 8 候选 smoke 15 次全部 <3s（P95 415.640ms）；20/50 不写 inline PASS，生产路径仍 `NOT_VERIFIED`。
- [W4 decide Prompt/Policy 专项（`acceptance-evidence/2026-11-13/w4-decide-policy/`）](acceptance-evidence/2026-11-13/w4-decide-policy/report.md)：17 个 mismatch 分为 5 个边界错、2 个输入事实不足、0 个标签错；v3 只补两个不可执行 goal，Prompt/契约 108/108 通过；后续 Magpie live 两轮为 17/20、19/20，均低于 0.98，W4 历史判定 `FAIL`。基线归属校正见该报告 §11（实际 worker 为 `2026-11-12.3`）。
- [W4 decide 连续调优（`acceptance-evidence/2026-11-13/w4-continuous-tuning/`）](acceptance-evidence/2026-11-13/w4-continuous-tuning/report.md)：固定 Magpie/Marvis，32 例 × 5 轮；R5 最终 Prompt `2026-11-13.5` 达到 golden 20/20、JSON 20/20、adversarial invalidToolCalls 0；每轮新 runId/snapshotHash，无缓存重放，`DECIDE_MODE=java` 未变。
- [P1 W6 default-off 垂直切片（`acceptance-evidence/2026-11-13/p1-default-off/`）](acceptance-evidence/2026-11-13/p1-default-off/report.md)：Java Gateway + Python Host 契约、本地只读测试 Server、最小只读调用、安全最小项与 D4 Provider 发布准入；`agent.mcp.gateway.enabled=false`，生产 W6 仍为 `NOT_READY / BLOCKED`。
- [P2-A W6 真实进程 E2E 与安全矩阵（`acceptance-evidence/2026-11-13/p2a-real-process/`）](acceptance-evidence/2026-11-13/p2a-real-process/report.md)：真实 Spring Boot Java Gateway + Uvicorn Python Host + 本地 TLS MCP Server 三进程；双端 `tools/list` 一致、真实只读调用；M-01～M-12 为 **CONDITIONAL PASS**（9 REAL_PROCESS / 1 PREFLIGHT / 1 JAVA_UNIT / 1 CONFIG），生产 W6 仍 `NOT_READY`。
- [P2-B1 真实进程证据补全（`acceptance-evidence/2026-11-13/p2b1-real-process-evidence/`）](acceptance-evidence/2026-11-13/p2b1-real-process-evidence/report.md)：M-01 external-negative（socket 尝试 0 + sentinel accept 0）、M-02 DNS 重绑定（合法 TLS 成功、重绑定监听器 0）、M-12 Gateway-off（真实 Spring Boot 404/Bean 缺席、旧 Provider 实际执行）；三项均为 `REAL_PROCESS PASS`，生产 W6 仍 `NOT_READY`。
- [P2-B2 Lane A：W6-B 功能/安全补强（`acceptance-evidence/2026-11-13/p2b2-release-readiness/lane-a/`）](acceptance-evidence/2026-11-13/p2b2-release-readiness/lane-a/report.md)：V99 Provider 准入、外部只读 + 本地 fixture 双 Server、租户隔离、Hash fence、在途撤权与审计脱敏；12/12 真实进程 case PASS。
- [W3.5 修复轮：staging 重建、Gemini 3.5 重测与评测口径修复（`acceptance-evidence/2026-11-12/w3-5-remediation/`）](acceptance-evidence/2026-11-12/w3-5-remediation/report.md)：D1/D2/D3/D5 修复后的 staging 重建（D2 healthy、D5 默认 200/60000→422）；Gemini 3.5 + thinking `budget=1` tier 8 门禁 13/15（13.33%，429=0）**FAIL**、overhead P95 27.6ms PASS、1 次用户请求=1 次 provider 调用 PASS；30s 诊断 15/15（尾部 ~2.98s）；D6/D7/D10 归档真实输出 replay（memory semantic 5/33、planning mutation 105/105、decide golden 19 例 agreement 0.7368/JSON 1.0）。
- [RAG V2 第三方公开 Benchmark 独立质量评测（`benchmark/2026-11-07-rag-v2-public-benchmark/`）](benchmark/2026-11-07-rag-v2-public-benchmark/benchmark-report.md)：C-MTEB/DuRetrieval 官方全量 10 万语料 + 300 冻结查询六变体对照（Dense Only 全面占优，混合 RRF 实测负收益）与 RGB 中文 B1 固定证据生成评测（噪声/拒答/整合/反事实四能力 270 次真实生成）；裁决 `PUBLIC_BENCHMARK_COMPLETED`，含数据许可核验、冻结抽样清单、逐题原始结果与失败分析，与内部 P9/P10 评测独立解释。
- [FINAL-DEPLOY-ACCEPTANCE-01：最终部署验收与 GHCR 首次发布闭环（ACCEPTED）](acceptance-evidence/2026-11-01/FINAL-DEPLOY-ACCEPTANCE-01/acceptance-summary.md)：GitHub Actions 干净 ubuntu Runner 真实 Docker Engine 全生命周期验收 21/21 PASS（安装、防重复引导、业务冒烟、数据保留、备份恢复、升级回滚、server-check 正负向）；GHCR 首次正式发布与 Digest↔源码 SHA 全链一致；覆盖式恢复部署缺陷修复；InstaBox 环境结论与过程事故披露。
- [one-click-deployment：本地与服务器双场景一键部署验收](acceptance-evidence/2026-11-01/one-click-deployment/acceptance-summary.md)：Windows 本地安装与生产拓扑隔离冒烟全量证据、资源占用实测、升级演练与三轮复审收口。
- [P10-REAL-GENERATION-03：扩展真实生成质量与端到端验收](acceptance-evidence/2026-10-27/p10-real-generation-03/README.md)：14 题全量真实 QA（17 次 Chat 含 3 次重复测量，0 失败）+ 20 个冻结 Agent 逻辑场景经真实生产链路（17 成功、3 个 Kilo 免费模型截断如实标 FAILED）；回放评分 34 条回答高严重度 0、41 项保守转复核。裁判来源审计发现旧批次每路 34 条结果中仅 12 条 QA 带齐来源，修正后本地 Qwen2.5 7B 输出 22 条结构化复核；编码助手逐项复核，独立人类复核 0。评分器 v6 全程冻结；[研究](archive/research/p10-real-generation-03-research-20261027.md)与[交付](archive/reports/p10-real-generation-03-delivery-20261027.md)。
- [P10-SCORER-ROBUSTNESS-06：审计确证七项缺陷修复与回归验收](acceptance-evidence/2026-10-27/p10-scorer-robustness-06/README.md)：基于 Audit-02 的 7 项确证缺陷（数字裸包含误判、分组引用泄漏、嵌套删除线失效、无关删除线抑制违规、历史限定豁免现行主张、范围排除污染独立命题、认识模态跨域放行）开展 TDD 修复并升版评分器 2.1-scoped-contextual-v6；静态风险 005/009 确定性复现后完成最小附带修复；专项 32 项与历史回归 33+4 项全绿，历史 7/7 反例 100% 检出，修复前证据于基线 commit 的 git worktree 真实重建；零外部模型调用，零生产代码变更；[研究](archive/research/p10-scorer-robustness-06-research-20261027.md)与[交付](archive/reports/p10-scorer-robustness-06-delivery-20261027.md)。
- [P10-SCORER-ROBUSTNESS-AUDIT-02：离线评分器全链路交互审计与变异测试](acceptance-evidence/2026-10-27/p10-scorer-robustness-audit-02/README.md)：纯只读全链路审计，建立 10 阶段流水线交互矩阵与十大不变量变异测试；确证数字裸包含误判、分组引用泄漏、嵌套删除线失效、无关删除线抑制违规、历史限定豁免现行主张、范围排除污染、认识模态跨域放行等 7 项可复现缺陷与 2 项静态风险；零生产代码变更，零外部模型调用。
- [P10-SCORER-ROBUSTNESS-05：数字编造检测、富文本归一化与弱转折作用域专项](acceptance-evidence/2026-10-26/p10-scorer-robustness-05/README.md)：针对 Audit-01 与 P10-04 遗留的 3 项静态风险开展 TDD 真实验证与定向加固；证实阿拉伯数字编造漏报（008）、HTML/删除线格式干扰（009）与弱转折分句泄漏（010）均真实存在并完成定向修复；33 项测试全绿，三组风险交互回归全过；零外部模型调用，零生产代码变更；[研究](archive/research/p10-scorer-robustness-05-research-20261026.md)与[交付](archive/reports/p10-scorer-robustness-05-delivery-20261026.md)。
- [P10-SCORER-ROBUSTNESS-04：已确证缺陷修复与逐项回归](acceptance-evidence/2026-10-26/p10-scorer-robustness-04/README.md)：针对 Audit-01 确证的 7 项缺陷（双重否定全局短路、拒答级联失守、反差转折跨句污染、认识模态短前缀放行、后置否定谓语误报、版本限定跨分句泄漏、例外条款污染无关命题）实施测试驱动修复与逐项回归；29 项稳健性测试全绿，历史反例 100% 保持，静态风险受控界定；零外部模型调用，零生产代码变更；[研究](archive/research/p10-scorer-robustness-04-research-20261026.md)与[交付](archive/reports/p10-scorer-robustness-04-delivery-20261026.md)。
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
  - [JMeter 本地性能基线（2026-11-12）](archive/report/perf-baseline-2026-11-12.md)：8 个后端/HTTP 场景、522,806 样本；错误率/5xx/AI stub overhead PASS，per-label P95 25% 一项 FAIL；本地共置基线，不是生产容量。
  - [W3.5 收口报告（2026-11-12）](archive/report/w3-5-blockers-20261112.md)：W3–W5 真实模型门禁汇总（S0/S1/S2/S1.5/S3）、W3/W4/W5 最终判定、D1–D10 缺陷登记与 W6 前置条件；生产默认开关未变。
  - [P1 W6 default-off 垂直切片（2026-11-13）](archive/report/p1-default-off-2026-11-13.md)：Java Gateway/Python Host 契约、本地只读测试 Server、安全最小项与 D4 发布准入；默认关闭，生产 W6 仍为 BLOCKED。
  - [P2-A W6 真实进程 E2E 与安全矩阵（2026-11-13）](archive/report/p2a-real-process-2026-11-13.md)：真实三进程、双端目录一致、21/21 已执行 case 与 M-01～M-12 分层证据；P2-A CONDITIONAL PASS，生产 W6 仍 NOT_READY。
- **技术笔记与调研备忘**：[`docs/archive/notes/`](archive/notes/)（历史技术笔记、数据库设计事实与 Cloudflare 部署可行性审计备忘）
- **历史提示词与指令**：[`docs/archive/prompts/`](archive/prompts/)（过往大型任务使用的复合优化 Prompt）
- **专项研究与技术决策**：[`docs/archive/research/`](archive/research/)（长上下文压缩阈值、Java 框架选型等历史调研）
  - [本地 Reranker Spike：Qwen3-Reranker-0.6B readiness（2026-11-12）](archive/research/local-reranker-spike-2026-11-12.md)：Qwen3-Reranker-0.6B 模型哈希、llama.cpp Vulkan `/rerank` 冒烟与 8/20/50 测量夹具；全量延迟/NDCG 为 `NOT_RUN`，未改默认开关。
  - [Python AI 旁路评估（2026-11-11）](archive/research/python-ai-sidecar-evaluation-20261111.md)：结论为条件性 GO——只允许离线评测消费者与 Java 编排的无状态 Dreaming 候选提取器；禁止 Python 直连数据层、持有密钥、执行在线 Agent Loop 或任何写操作。
- **历史审计截图资产**：[`docs/archive/audits/shots/`](archive/audits/shots/)（各轮次 UI 与多端视觉审计快照、审计报告及对比资产）
- **产品体验重构方案**：[`docs/archive/superpowers/`](archive/superpowers/)（早期的产品交互体验重构提案、规格说明与真实验收对比）
