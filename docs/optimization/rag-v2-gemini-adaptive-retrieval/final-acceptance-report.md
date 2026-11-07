# 最终验收报告｜AI Collab RAG V2 Gemini 双模型接入与智能检索优化专项

> 任务：RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01 ｜ 日期：2026-11-07 ~ 11-08
> 分支：dev ｜ 提交链：`40c0bde4`（基线推送）→ `a1a3ec3f`（阶段 1）→ `589b7fb8`（CI 修复+阶段 2 代码）→ `ab9e73d9`（阶段 3）→ `1015eba0`（阶段 4）
> 合并 main：未执行 ｜ 部署正式站：未执行 ｜ 生产数据库/密钥/路由：零改动

---

## 最终裁决：`OPTIMIZATION_PARTIAL`

已执行阶段（1、3、4）质量与工程验收通过、有可验证收益；阶段 2 的检索质量 A/B 因
Gemini 免费层配额耗尽（外部条件）标记 NOT_VERIFIED；阶段 5 依据任务规则不修改（有据不改动）。
所有已宣称的结果均有真实测量支撑；无法验证的部分如实标注，未伪造任何数据。

---

## 各阶段汇总

| 阶段 | 裁决 | 起始→结束 SHA | 核心结果 |
| --- | --- | --- | --- |
| 0 基线审查 | PASS | `39d1603d`(远端)→`40c0bde4` 推送 | 2 个未推送提交安全审查后推送；现状审查见 00 报告 |
| 1 Gemini Chat | **PASS** | `40c0bde4`→`a1a3ec3f`（+CI 修复 `589b7fb8`） | thinkingConfig 能力位契约、400 API_KEY_INVALID 错误语义；8 场景真实冒烟 9/9（TTFT 882ms、JSON/工具调用/拒答/截断分类全过）；契约测试 42/42 |
| 2 Gemini Embedding | **接入 PASS / A/B NOT_VERIFIED** | `589b7fb8` | 原生 batchEmbedContents 协议（taskType 配对/降维/严格校验）、指纹扩展、查询链路接入；契约 10/10；A/B 因免费层 RPD 耗尽未完成（如实记录，harness 可续跑） |
| 3 策略化 Router | **PASS** | `ab9e73d9` | 复杂度×策略分离；FAST 走 Dense-only+有界回退（公开 Benchmark：7 倍提速）；P9 检索侧复测 52/54、53/54、空上下文 0——与封版一致；路由诊断出口（查询掩码） |
| 4 Lexical SQL | **PASS** | `1015eba0` | 根因 EXPLAIN 定证（similarity>0.03 不可索引→全表扫 3.0-3.7s）；阈值 0.03 + 索引化 → 602ms（6 倍）；**300 冻结查询语义等价 diff=0**；朴素方案丢失 2566 候选被拒绝 |
| 5 RRF 融合 | **NOT_EXECUTED** | — | 阶段 3/4 已消除无收益词法调用与成本动机；无独立验证集不做融合调参（防过拟合自证），有据不改动 |

## P9/P10 历史质量基线

| 保护目标 | 结果 |
| --- | --- |
| QA 必要事实保留 ≥52/54 | **52/54 ✓**（两次复测一致） |
| Agent 必要事实保留 ≥53/54 | **53/54 ✓** |
| 空上下文系统性回归 | **0/39 ✓** |
| P10 新增高严重度违规 | **0**（生产生成路由未改动；Gemini 实验路由的 1 项版本口径违规属模型能力，未进入任何默认配置） |
| 引用/权限/文档身份 | 零违规（RetrievalQualityHardGatePostgresTest 7/7） |

## 公开 Benchmark 关系

阶段 3/4 的策略与 SQL 改动以公开 Benchmark 冻结查询做等价性/收益验证（未据其调参生产检索阈值；
阈值 0.03 源自旧 SQL 自身语义，非刷分）；300 题仅用于等价性对账，未用于融合权重调优。

## CI

| 提交 | CI run | 结果 |
| --- | --- | --- |
| `40c0bde4` | 42371367155 | success |
| `a1a3ec3f` | 42377323111 | **failure**（冒烟套件门控泄漏——TestAbortedException 被计为失败；已修复于 `589b7fb8`） |
| `ab9e73d9` | 42393980554 | **failure**（同批嵌入代码导致 5 个检索测试类 3 参 mock 缺 4 参打桩；已修复于 `1015eba0`） |
| `1015eba0` | 42400944310 | 运行中（结果见 GitHub Actions；本地完整 verify 已 BUILD SUCCESS 2314/0/0） |

每个失败均已归因并修复后推送，无未解释失败带入下一阶段。

## 修改前 / 修改后核心指标（同条件实测）

| 指标 | 前 | 后 | 来源 |
| --- | --- | --- | --- |
| FAST 查询检索路径 | 完整管线 P50 3434ms | Dense-only P50 465ms（约 7 倍） | 公开 Benchmark 同语料同查询 |
| Lexical SQL P50 | 3032-3655ms | 602ms（6 倍） | EXPLAIN ANALYZE BUFFERS + 300 查询 |
| Lexical 召回语义 | 基线 | diff=0（Top20/Top1 全一致） | 300 冻结查询对账 |
| P9 QA / Agent / 空上下文 | 52/54, 53/54, 0 | 52/54, 53/54, 0 | P9 冻结协议复测（真实 Ollama） |
| Gemini chat 冒烟 | 未接入验证 | 9/9 场景、TTFT 882ms、错误分类正确 | 真实免费 API |

## 已知问题（不隐藏）

1. **Gemini 免费层配额极低**：flash 系 RPM 实测连续请求即 429；embedding RPD 耗尽（约 +08:00 16:00 重置）。
   生产化建议：Chat 用 flash-lite（快、稳、但需 RAG 证据纪律约束——实测一次事实波动）或付费层；
   Embedding A/B 待配额恢复续跑（`AI_GEMINI_EMBED_AB=true ... -Dtest=GeminiEmbeddingABBenchmarkPostgresTest`）。
2. **生成侧 P9 门禁未达成 PASS**：三组实验路由（DeepSeek 余额 / flash-lite 模型 / flash 配额）分别
   NOT_VERIFIED×2、REGRESSION×1，全部归因于外部路由模型，非本轮代码回归；生产路由未改动。
3. **DENSE_WITH_BOUNDED_FALLBACK 回退判据**当前为"候选为空"，更细的证据覆盖判据留待后续实测。
4. **流式链路无 thinkingConfig 400 回退**（非思考模型配置正确能力位即可规避）。
5. 本地完整 verify 曾两次因多容器并行资源竞争出现环境性 JDBC 抖动（无关模块单跑 19/19 通过）；
   第三次清场后 BUILD SUCCESS（2314/0/0/59），CI 为权威门禁。
6. 框架引入（Spring AI / LangChain4j）评估结论：本轮不引入（收益/风险比不足，thought signature 等
   新协议特性框架同样滞后）；未来可做"适配器的适配器"单路径试点，见 01 报告附录讨论。

## 复现

所有阶段命令、环境变量、证据文件见 `README.md` 与各阶段报告；评测原始数据在 `evidence/` 目录。
