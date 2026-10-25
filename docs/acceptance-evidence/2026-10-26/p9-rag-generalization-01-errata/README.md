# P9-RAG-GENERALIZATION-01 统计口径 Errata（独立补记）

> 补记日期：2026-10-26　被补记对象：`docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`（冻结，不改）
> 源 SHA：主测量基线 `3891171ecfc81a3929993f9f77989fed61ea5c85`；证据提交 `52bdb68` / `efafbc2`。
> 本文件为独立 errata，不覆盖任何原始报告、JSON、manifest 或失败记录。

## 1. 长文档规模表述修正

P9 设计与报告曾以"约 1,000 / 3,000 / 6,000 / 10,000 字"描述 G01 长文档压力分层。
冻结 `corpus-manifest.json` 的实际值为：

| 文档 | 实际长度（Unicode code points） |
| --- | --- |
| A1-设备巡检维护简则.md | 721 |
| A2-设备维护管理细则.md | 1,436 |
| A3-中央机组维护手册.md | 2,633 |
| A4-全厂设施维护总册.md | 3,353 |

**正确表述：P9 已覆盖不同长度与事实位置的合成文档，但最长文档约 3,353 code points；
6,000～10,000 量级的长文档压力验证尚未执行，不得据此宣称已完成。**

G01 维度结论（11 题位置稀释无 QA 失效、Agent 1 例名额失败）仅在 ≤3,353 cp 文档上成立。

## 2. 文档与分块计数口径澄清

P9 证据 `retrieval-results.json` 的 `indexing.documentCount=25 / chunkCount=175` 是**累计索引事件**，
不是同一时刻参与检索的唯一分块数。四种口径必须区分：

| 口径 | 数值 | 说明 |
| --- | --- | --- |
| 唯一语料 | 23 篇 / 161 chunks | 22 常驻（154 chunks）+ F4 注入样本（7 chunks） |
| 常驻索引规模 | 22 篇 / 154 chunks | 任意非注入题目运行时实际参与检索的分块 |
| 临时索引事件 | F4 × 3 次（每次 7 chunks） | H-G07-NONE / H-G07-SIMILAR / H-G07-INJECT 三道题按 Q13 协议临时索引、测后失效 |
| 累计索引工作量 | 25 次文档入库 / 175 次 chunk 入库 | 22 + 3 次文档事件；154 + 21 次 chunk 事件 |

原证据 README"语料 23 篇 / 175 chunks"及交付汇总中的"175 chunks"应按上表理解：
**同一时刻参与检索的唯一分块最多为 161（含注入期）或 154（常驻）。**

## 3. 泛化结论边界收敛

原研究报告中"旧冻结集显著低估 Agent 泛化水平"的表述偏强。准确结论：

> 新独立合成集上的 Agent 事实保留率（94.4%）明显高于旧冻结集（81.5%），
> 说明旧集结果不能直接代表其他语料的典型表现；
> 但单一新合成集无法估计真实用户文档总体的平均泛化水平，
> 也不能推断旧集"偏难"具有普适性。

支持证据不变（同 SHA、同配置、逐事实可复现），仅收敛结论的适用范围表述。

## 4. 更正后的关键数字速览

- QA 事实保留 52/54（96.3%）、Agent 51/54（94.4%）、agent12 对照 53/54、Recall@8 53/54、Recall@20 54/54。
- 失败 4 例（Agent 名额 ×2、QA 名额 ×1、QA 召回+名额 ×1）；BODY_TRUNCATION / 身份违规 / 越界均为 0。
- 冲突条款暴露：7 条干扰条款中 QA 可见 6、Agent 可见 7。
- P6 旧冻结集回归逐事实零差异（QA 26/27、Agent 22/27）。
- 上述结论均在"最长文档 3,353 cp、常驻 154 chunks"的语料规模下测得。

## 5. 可追溯性

- 语料长度与哈希：`tests/fixtures/documents/p9-rag-generalization-01/corpus-manifest.json`（冻结）。
- 索引事件计数：`docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/retrieval-results.json` 的
  `corpus[]`（25 条事件记录，F4 出现 3 次）与 `indexing` 块（累计口径）。
- 本 errata 未重新运行任何测量；全部数字来自冻结文件的直接读取核对（核对脚本输出见
  `errata.json.verificationNote`）。
- 受影响并已同步修正的活文档：`docs/design/rag-generalization-benchmark.md`（长度分层表述）。
  原研究报告/交付报告按冻结保留，以本 errata 为准。
