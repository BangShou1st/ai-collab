# P8-RAG-QUALITY-FIX-01

任务日期 2026-10-25；实际执行跨北京时间 10 月25–26日。开工基线 dev@f11939e，远端 main@9b4f7c5。原 P8 未提交的测试、证据和导航已逐文件保护，原始 manifest 的290项哈希核对一致。提交归属独立记录，不覆盖、不重写上轮证据。

本轮仅准入 Agent 子来源最终持久化的身份/摘录校验。**生产检索、来源选择、分块、Prompt、模型与默认 limit 均未修改。未执行真实 Chat 生成、正式部署或生产数据修改。**

| 路径 | 结果与边界 |
| --- | --- |
| F0 | 真实 Ollama + Testcontainers：Q02 排名23 / 0.3643；QA26/27、Agent8 22/27、Agent12 25/27 |
| Q02 | `Q02_EXPERIMENT_ONLY`：标题补读+名额保留+连续尾部摘录能救回目标，但 QA 同时丢 Q03 门禁；不准入 |
| Agent | `AGENT_EVIDENCE_EXPERIMENT_ONLY`：固定8的 S1/S2 到26/27；文档覆盖退化且独立必要文档反例失败；保留 S0 |
| 引用 | `CITATION_CONTRACT_FIXED`：子来源重新匹配成功读取资料的持久化移交与当前身份，保留原摘录与失败研究中的合法部分资料；不改变正文，不把卡片当语义支持 |
| 生成质量 | `NOT_EXECUTED`：Chat 全为既有跨层测试脚本，外部 Chat 与付费 Embedding 均0次；实际 token 不可得 |

总体最终裁决及 F01–F16 见 [decision.md](decision.md)；验收只覆盖本轮获准机制与实验结论，不构成真实生成质量通过。

## 审计文件

- [开工 Git 与原工作区归属](baseline.json)、[真实基线](retrieval-baseline.json)、[历史文件哈希](source-manifest.json)、[历史完整性复核](historical-integrity.json)。
- [Q02 对照及全题 gained/lost](q02-experiments.json)、[Agent 同候选对照](agent-selection-experiments.json)、[完整原始测量的脱敏投影](paired-experiments.json)。
- [引用契约矩阵](citation-contract-matrix.json)、[成本上界与测量局限](cost-comparison.json)、[逐次回归与失败尝试](regression-results.json)、[前端验证](frontend-regression.json)。
- [新增引用SQL实际计划](citation-sql-plans.json)、[交付审计](audit-results.json)、[实际验证源码的本地字节及LF哈希](tested-source-hashes.json)、[P8原始字节入Git核验](inherited-git-bytes.json)。
- 既有机制本轮重跑明细：[SOURCE_CAP E1–E11](sourcecap-regression.json)、[QA选择回归](qa-selection-regression.json)、[真实检索回归](real-retrieval-regression.json)、[父子研究回归](agent-research-regression.json)。这些保留原测试的统计命名，尤其父子脚本指标不被提升为独立语义支持证明。
- [研究报告](../../../archive/research/p8-rag-quality-fix-01-research-20261025.md)、[交付报告](../../../archive/reports/p8-rag-quality-fix-01-delivery-20261025.md)、[长期来源契约](../../../design/agent-source-citation-contract.md)。

## 复现

从仓库根目录运行，先确保 Docker Desktop 和既有本地 qwen3-embedding:0.6b 可用；不读取 `.env`，不下载模型。runner 清除外部模型 opt-in，Maven 使用离线缓存。`protect` 拒绝覆盖已有 baseline，冻结后必须为复测设置新的证据和target目录，不能覆盖历史 docs。

```powershell
$env:P8_FIX_EVIDENCE_DIR = 'docs/acceptance-evidence/2026-10-26/p8-rag-quality-fix-01-replay'
$env:P8_FIX_TARGET_DIR = 'ai-collab-backend/target/p8-rag-quality-fix-01-replay'
node ai-collab-backend/src/test/p8-quality-fix-run.cjs protect
node ai-collab-backend/src/test/p8-quality-fix-run.cjs baseline
node ai-collab-backend/src/test/p8-quality-fix-run.cjs experiment
node ai-collab-backend/src/test/p8-quality-fix-run.cjs citation
node ai-collab-backend/src/test/p8-quality-fix-run.cjs regression
node ai-collab-backend/src/test/p8-quality-fix-run.cjs integration
node ai-collab-backend/src/test/p8-quality-fix-run.cjs verify
node ai-collab-backend/src/test/p8-quality-fix-evidence.cjs
node ai-collab-backend/src/test/p8-quality-fix-audit.cjs
node ai-collab-backend/src/test/p8-quality-fix-run.cjs restore
node ai-collab-backend/src/test/p8-quality-fix-run.cjs integrity
```

现有 target 输出每次覆盖前归档在 `target/p8-rag-quality-fix-01/prior-runs/`；历史检索 target 与跨层输出在收尾恢复。普通 CI 不启用真实 Ollama，但独立引用 PostgreSQL 用例必须实际执行，已加入 CI 容器执行守卫。

实验补读只访问已经合法命中的两份文档，最多一条 SQL/四个新增块；标题信号不是全项目全文扫描。SQL计划来自49块的小语料，不代表大型项目性能。局部摘录只是连续原文窗口，未通过通用语义安全性或 Runtime 集成准入。S1/S2 的关键词保留改善不能掩盖文档覆盖损失。
