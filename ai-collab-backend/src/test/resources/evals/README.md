# Agent 评估集资源目录（Test Resources: Evals）

本目录存放 Agent/评测相关 JSON 资产。语料位置的仓库级登记见 `tests/fixtures/evals/README.md`。

## 1. 资产清单

| 资产 | 格式 | 用途 | 引用测试 |
| --- | --- | --- | --- |
| `golden-cases.json`（20 case，`version: 1`，UTF-8 无 BOM） | v1 schema：场景输入（用户请求 + 项目上下文 seed）+ 脚本化模型响应 + 可机械化判定断言（事件流 + invocation + 受限 DB probe） | Agent 回归防线与 CI 守门评估，20 条全部 dev | `AgentEvalSuiteTest`（Testcontainers 全链路）、`GoldenCaseLoaderTest`（schema 契约） |
| `frozen-eval-set-v2.json`（46 case，`version: 2`，UTF-8 无 BOM） | v2 schema：v1 骨架 + §3.3 元数据（split/reviewStatus/factSources/expectedTools/forbiddenTools/citationTarget/allowedResultRange/frozenEnvironment 等） | AC-01 跨 Agent/RAG 冻结评测集：dev 调试 + holdout 终报一次性解锁 | `FrozenEvalSetV2ValidationTest`（只读校验，无 Docker） |

## 2. frozen-eval-set-v2.json：来源与划分

**只做结构化包装与元数据补全，不含任何新题目；既有题目语义零改动**（golden 来源 case 的
`payload` 为 v1 case 全文逐字嵌入，可被 `GoldenCaseLoader` 原样加载）。

### 2.1 继承映射表（46 条）

| 桶 | 数量（dev/holdout） | 来源 | 继承内容 |
| --- | --- | --- | --- |
| `RAG_QA` | 14（8/6） | `tests/fixtures/documents/p9-rag-generalization-01/p10-evaluation-question-set.json` | 14 题全量（AQ01–AQ14，12 维度：版本现行/废止/变化、区域冲突×2、跨文档、条件复合/否定/超时、拒答×2、未定诚实性、提示注入） |
| `TOOL_CALLING` | 12（12/0） | `golden-cases.json` | TASK_WRITE_APPROVAL×4 + TASK_QUERY×4 + DELEGATION_RESEARCH×4 |
| `LONG_CONVERSATION` | 11（7/4） | `golden-cases.json` + `docs/acceptance-evidence/business-evaluation-cases.json` | LOOP_GUARD×2 + BUDGET_CONVERGENCE×3；business 素材：long-constraints、summary-resume、target-switch、memory-selection、summary-budget、large-result |
| `PLANNING_APPROVAL` | 9（6/3） | `golden-cases.json` + `business-evaluation-cases.json` | MEMORY_WRITE_APPROVAL×3；business 素材：planning-product-chain、dispatch-restart、cancel-permission、repair-restart、model-capabilities、configuration-reload |

business-eval 17 条素材中 **5 条明确排除**（原因记录在文件 `excludedSources` 字段）：
`object-identity`（绑定整个测试类，无法固化单条断言面）、`insufficient-sources` /
`multi-sources`（引用校验器单测素材，非端到端评测场景）、`task-pages-document-continuation`
（分页续读基建测试）、`upgrade`（数据库迁移升级验证，与评测语义无关）。

### 2.2 dev/holdout 划分依据

- 总体约 70/30：dev 33 / holdout 13（28.3%），按桶分层抽样。
- golden 来源 20 条**全部 dev**：其中 4 条【回归防线】case 须常跑 CI、防护被删时必须失败，不能冻结进 holdout；holdout 权重因此落在 P10 题集（6/14）与 business-eval 继承 case（7/12）。
- `holdoutPolicy`（文件字段）：**终报解锁一次之前不得用 holdout 反复调参**，不得依据其结果调整生产检索规则、评测参数或标注；holdout 结果只允许在最终报告解锁时读取一次。
- 语料 dev/holdout 沿用 P9 `corpus-manifest.json` 的 per-doc `set` 标记，本文件不复制文档清单以免双源漂移。

### 2.3 固化环境与 NOT_VERIFIED 项

`frozenEnvironment`（文件级）：commitSha `9bcf78ae…`、语料指纹（corpus-manifest.json sha256，
逐文档 sha256 在该 manifest 内）。

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 模型配置 / 温度 / 成本费率版本 | **NOT_VERIFIED（NOT_BOUND）** | 本批 46 条执行载体为脚本化模型响应（golden）或离线确定性评分（P10/business），不出站真实模型；真实出站运行时按 `agent_run.model_configuration_snapshot` 逐 run 固化，回填前评测结果不得对外引用 |
| 索引指纹 | 已固化 | corpus-manifest.json sha256（含逐文档 sha256，冻结于 2026-10-26） |
| business 来源 12 条的 reviewStatus | **PENDING_REVIEW** | 仅完成结构化包装，断言面仍绑定集成测试类，尚未独立复核为可机读断言 |
| RAG / golden 来源 34 条的 reviewStatus | HUMAN_REVIEWED | 继承来源资产冻结时的人工校订 |

### 2.4 schema v2 要点

- 每条 case 固定键序：`case_id / bucket / split / reviewStatus / sourceAsset / sourceRef /
  question / factSources / expectedTools / forbiddenTools / citationTarget /
  allowedResultRange / modelProfile / commitSha / notes / payload`。
- `factSources`、`expectedTools`、`forbiddenTools`、`citationTarget`、`notes` 允许 null
  （存量继承值如实标注，null 语义写在各条 `notes`）；`case_id / bucket / split /
  reviewStatus / sourceAsset / sourceRef / question / allowedResultRange / commitSha` 必填。
- `payload` 为来源资产原条目逐字副本：golden 来源 = v1 case 全文；RAG 来源 = P10 原题对象
  （含 rules/marker/abstain 协议）；business 来源 = `scenario/testClass/testMethod/gate`。
- v1 读取不受影响：`golden-cases.json` 未改动，v1 加载路径（`GoldenCaseLoader.RESOURCE`）不变。

## 3. 维护约定

- 冻结集禁止生成新题目、禁止改动既有题目语义；扩充只能走"来源资产继承 + 元数据补全"并在
  本 README 登记继承映射。
- 修改 `golden-cases.json` 时同步 `GoldenCaseLoaderTest` 与 `AgentEvalSuiteTest` 的数量守门
  （当前 20）；修改 `frozen-eval-set-v2.json` 时同步 `FrozenEvalSetV2ValidationTest` 的桶
  覆盖统计断言（当前 14/12/11/9）。
- holdout 子集一旦被任何调参过程消费，须在文件与报告中标记"保留集已消费"并另建新集。
