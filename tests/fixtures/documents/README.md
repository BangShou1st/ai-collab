# 测试语料库（Test Fixtures: Documents）

本目录存放用于系统端到端测试、RAG 文档知识库解析、分块向量化评测及验收验证的标准测试语料。

---

## 1. 语料文件清单

| 文件名 | 涉及业务域 / 场景 | 主要内容概览 | 引用测试 / 验收记录 |
| --- | --- | --- | --- |
| `报名流程与容量规划.md` | 项目与团队管理、容量设计 | 报名批次、预估团队数、存储规划与限流策略 | RAG 知识检索基准验收 |
| `数据安全与脱敏规范.md` | 数据合规、脱敏规则 | 敏感字段脱敏算法、鉴权等级与审计日志要求 | RAG 检索质量评测、安全性验收 |
| `竞赛评分模块技术方案.md` | 架构方案、模块设计 | 评分流水线、评审权限、双盲打分与结果汇总架构 | 架构方案知识检索与问答验收 |
| `验收标准v2.1.md` | 验收交付、质量指标 | 功能点覆盖要求、P0/P1/P2 指标与测试回归标准 | 端到端验收标准对比 |
| `p4-rag-answer-quality-01/灰度发布与回滚规范.md` | 发布管理 | 灰度发布流程、核心指标观察、回滚触发条件 | P4-KNOWLEDGE-REAL-E2E-01 浏览器 E2E 引用与多文档验收 |
| `p9-rag-generalization-01/`（23 篇） | 设备维护 / 服务交接 / 资产盘点 / 审批与合同 / 技术变更 / 审计核对（全新业务域，独立于 P4 冻结集） | G01–G08 泛化维度合成语料：长文档信息稀释、跨文档组合、同文档互补、多版本冲突、条件与例外、相似文本不同来源、无答案与注入、自然语言改写 | `P9RagGeneralizationPostgresTest` 独立泛化基准（`AI_REAL_OLLAMA_RETRIEVAL=true` 门控） |

---

## 2. 规范与维护约定

`p4-rag-answer-quality-01/`：真实 Gemini 回答质量验收专用公开合成语料。13 篇历史文档由
`AgentRetrievalQualityPostgresTest.CORPUS` 完整重建，SHA-256 与 P4-RAG-QUALITY-02 一致；
另有一篇明确标注的提示注入检测资料。只允许上传此目录的必要摘录到获授权的 Gemini。
问题与人工事实基准见 `docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/questions.json`。

`p9-rag-generalization-01/`：P9-RAG-GENERALIZATION-01 独立 RAG 泛化评测基准，23 篇合成文档
（UTF-8 无 BOM）+ 39 道问题（dev 21 / holdout 18，共 54 条必答事实标注）。冻结清单为
`corpus-manifest.json`、`question-manifest.json`、`question-manifest-holdout.json` 与
`holdout-integrity.json`（Holdout 方法学保留集，冻结后不得依据其结果调整生产规则或评测参数）。
评测标注（facts/distractFacts marker）只允许用于离线评分，严禁参与检索、来源选择或查询构造。
生成脚本与逐字校验见 `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/generate-manifests.cjs`。
统计口径修正见 `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01-errata/`。
`p10-evaluation-question-set.json`：P10-RAG-ANSWER-QUALITY-01 冻结回答质量评测子集
（14 题，派生自上述 P9 冻结标注，未改动原标注；机械评分规则内嵌）。
`p10-real-generation-03-agent-scenarios.json`：P10-REAL-GENERATION-03 冻结 Agent 场景清单（20 个场景），供受门禁保护的 PostgreSQL 端到端评测读取；不含凭据。

1. **统一编码**：
   - 所有测试语料文件必须使用 **UTF-8（无 BOM）** 编码保存，避免在 Windows 与 Linux/CI 环境间产生跨平台字符集差异。
2. **只读保护与测试隔离**：
   - 测试用例对本目录下的语料仅允许进行**只读加载**（如上传到测试知识库、执行 Tika 解析与分块向量化），**严禁在测试运行时回写、修改或向本目录输出临时文件**。
   - 所有运行时生成的测试输出、分块 JSON、中间产物必须写入 `target/` 或临时目录。
3. **新增语料规则**：
   - 若新增专门用于回归测试或 RAG 评测的 Markdown、PDF 或文本文件，必须统一放入本目录或在 `tests/fixtures/` 下开辟相应子目录，并在本 `README.md` 中登记说明。
