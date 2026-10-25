# P9-RAG-GENERALIZATION-01 统计口径 Errata 交付报告

> 日期：2026-10-26　性质：独立补记，不修改任何 P9 冻结报告、原始 JSON、manifest 或失败记录。
> 证据入口：`docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01-errata/`（README + errata.json + manifest.sha256）。

## 修正内容

1. **长文档规模**：G01 分层实际为 721 / 1,436 / 2,633 / 3,353 Unicode code points（冻结 corpus-manifest 核对），
   未达到设计描述的 6,000～10,000 字量级。正确表述："已覆盖不同长度与事实位置的合成文档，
   最长约 3,353 cp；更大规模文档尚未验收。"G01 结论（位置稀释无 QA 失效）仅在 ≤3,353 cp 上成立。
2. **分块计数口径**：证据 `indexing.documentCount=25 / chunkCount=175` 是累计索引事件；
   唯一语料 23 篇 / 161 chunks，常驻索引 22 篇 / 154 chunks，F4 注入样本按 Q13 协议临时索引 3 次
   （每次 7 chunks）。同一时刻参与检索的唯一分块最多 161（注入期）或 154（常驻）。
3. **泛化结论边界**："旧集显著低估 Agent 泛化"收敛为"旧集结果不能直接代表其他语料的典型表现；
   单一新合成集无法估计真实用户文档总体的平均泛化水平"。支持数据不变（同 SHA 逐事实可复现）。

## 数字核对方式

全部数字由冻结文件直接读取核对（未重新运行测量）：corpus-manifest.json 逐篇 codePoints；
retrieval-results.json `corpus[]` 25 条事件记录（F4 出现 3 次）、chunkCount 求和 175（累计）与
按唯一文档求和 161；常驻（非 F4）求和 154。

## 已同步修正的活文档

- `docs/design/rag-generalization-benchmark.md`：长度分层表述、唯一/常驻/累计口径、状态头加 errata 引用。
- `docs/README.md`：导航新增 errata 条目。

## 保持冻结未动

- `docs/archive/research/p9-rag-generalization-01-research-20261026.md`
- `docs/archive/reports/p9-rag-generalization-01-delivery-20261026.md`
- `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`（全部原始产物）

## 不变的结论

QA 52/54、Agent 51/54、Recall@8 53/54、Recall@20 54/54、失败 4 例归因、冲突条款暴露 6/7 与 7/7、
P6 旧集回归逐事实零差异——以上均在"最长 3,353 cp、常驻 154 chunks"语料规模下测得并保持可复现。
