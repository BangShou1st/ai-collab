# P9-RAG-GENERALIZATION-01 交付报告

> 日期：2026-10-26　任务：独立 RAG 泛化评测基准建设与真实本地基线测量
> 基线：`dev@3891171ecfc81a3929993f9f77989fed61ea5c85`　最终 SHA：见本报告"Git 与 CI"节
> 裁决：**`P9_GENERALIZATION_BASELINE_ACCEPTED`**；真实 Chat 生成质量：**`REAL_GENERATION_NOT_EXECUTED`**

## 1. 交付内容

| 项 | 位置 |
| --- | --- |
| 长期设计 | `docs/design/rag-generalization-benchmark.md`（`docs/README.md` 导航已更新） |
| 合成语料与冻结标注 | `tests/fixtures/documents/p9-rag-generalization-01/`（23 篇 + 4 份冻结清单；`tests/fixtures/documents/README.md` 已登记） |
| 评测实现 | `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P9RagGeneralizationPostgresTest.java`（显式 opt-in 门控，复用 P6/P8 CI 隔离模式，未新增 CI 基础设施） |
| 研究报告 | `docs/archive/research/p9-rag-generalization-01-research-20261026.md` |
| 验收证据 | `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`（16 个文件，含 `manifest.sha256`） |
| P4～P8 历史证据 | 未修改（旧冻结集回归为只读重跑，输出写 `target/`） |

生产代码（`src/main`）零改动；未修改 chunk size/QA TopK/来源数/预算/截断上限等任何生产参数；
不涉及数据库迁移；前端无改动。

## 2. 规模与结果（真实测量，非脚本推演）

- 语料：23 篇（22 常驻 + 注入样本按 Q13 协议临时索引），175 chunks，索引输入 21,500 code points。
- 题目：39 道（dev 21 / holdout 18），54 条必答事实，7 条干扰条款标注，2 道拒答，1 道注入。
- QA：52/54（96.3%），单题完整覆盖 35/37；legacy 对照 53/54。
- Agent（真实 `KnowledgeSearchAgentTool`）：51/54（94.4%），完整覆盖 34/37；limit=12 对照 53/54。
- Recall@8 53/54、Recall@20 54/54；BODY_TRUNCATION 0；来源身份违规 0；范围越界 0。
- 失败归因：Agent 名额饱和（2 条，Top20 排名 4/2）、QA 5 来源名额竞争（1 条，排名 4，legacy 可保留）、
  QA 召回 + Agent 名额双重失效（1 条，排名 15，Q02 同族）。详见 `failure-analysis.md`。
- 回归保护：P6 旧冻结集同 SHA 重跑与 2026-10-25 冻结基线**逐事实零差异**（QA 26/27、Agent 22/27），
  `REGRESSION_CLEAN`。

## 3. 与历史结论的关系

- 旧集 QA 26/27 的相对水平在独立集上保持（96.3%），无泛化崩塌。
- 旧集 Agent 22/27（81.5%）低估 Agent 泛化（独立集 94.4%）：旧集是偏难集合，不是偏乐观集合。
- P8 的"固定 8 来源牺牲覆盖"在独立集上复现为更普遍的"代表饱和"机制，且名额↑能救互补事实
  （+2 条）——名额/覆盖/互补三者的结构性权衡得到双向证据。
- 名额均衡（S2）在旧集无回归，但在新集出现明确反例（同文档互补事实被挤，legacy 优于 balanced）。

## 4. 方法学与防泄漏声明

- Holdout（D/E/F 族）文档与问题文件 SHA-256 先于测量冻结于 `holdout-integrity.json`；
  本轮为纯基线测量，未依据 holdout 结果调整任何生产规则或评测参数，保留集未被消费。
- 评测标注（marker）只用于离线评分：测量代码中 marker 仅出现在评分侧定位 SQL，
  检索 query、工具参数、来源选择不接触任何标注；不存在"用标注生成正确回答"的路径（无 Chat 模型）。
- 题目不通过 ID/文件名特判；对全部题目使用同一生产参数。
- 测量口径为事实级（重复段落语料的必要修正，排查过程见 `failure-analysis.md` §6）。

## 5. CI 与测试隔离

- `P9RagGeneralizationPostgresTest` 显式 `@EnabledIfEnvironmentVariable(AI_REAL_OLLAMA_RETRIEVAL=true)`，
  默认 `verify` 中跳过（本地实测：Tests run: 1, Skipped: 1），不依赖 JUnit 条件继承（P8 教训落实）。
- 本地显式 opt-in 完整执行 39 题真实链路（3 次运行，全部通过硬断言与机械不变量）。
- 未全局跳过任何质量测试；真实 PostgreSQL 的非模型测试不受影响。

## 6. Git 与 CI

- 提交在 `dev`；`main` 未动；PR #18 保持 OPEN/Draft/未合并；无 force push；未部署。
- 最终提交 SHA 与 Push/PR CI 回执在推送后补记于 `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/README.md`（同 SHA 核实）。

## 7. 遗留与边界

- 真实 Chat 生成质量未验收（未获授权，不调用外部模型）；"保留"结论止于实际进入模型上下文的证据。
- 三个修复候选（代表名额上限、互补配额、受控补读）仅提出思路与实验协议，本轮未实施。
- 拒答题的干扰文档进入工具摘录属工具既有语义，不判缺陷；拒答行为属生成层，未测。
