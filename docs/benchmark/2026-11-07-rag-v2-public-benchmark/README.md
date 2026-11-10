# RAG V2 第三方公开 Benchmark 独立评测（RAG_V2_PUBLIC_BENCHMARK-01）

> 评测日期：2026-11-07 ｜ 封版基线：`75fad312`（dev）｜ 内部质量封版裁决：`RAG_V2_FINAL_QUALITY_ACCEPTED`（本轮不改动）
>
> **性质声明**：本目录是 AI Collab RAG V2 首次面向**第三方公开数据集**的独立外部评测。内部 P9/P10 冻结基准成绩与
> 本轮外部成绩相互独立、不可互相替代或解读。

## 一、评测对象与数据集

| Benchmark | 数据集 | 用途 | 协议 |
| --- | --- | --- | --- |
| A 检索 | C-MTEB/DuRetrieval + DuRetrieval-qrels（HF，经 hf-mirror.com 获取） | 陌生中文语料上的真实检索能力 | 官方**全量 corpus**（100,001 条，`OFFICIAL_CORPUS_FULL`）+ 自定义分层随机抽样 300 查询（seed=20261107） |
| B 生成 | chen700564/RGB 中文版（@65ec39e4，CC BY-NC-SA 4.0） | 噪声鲁棒/拒答/整合/反事实 | 模式 **B1 官方固定证据**（不测检索）；自定义抽样 150 场景；证据选择复刻官方 `processdata`（每题 seed 2333） |

许可要点：RGB 为 **CC BY-NC-SA 4.0 非商业许可**，本项目为内部研究性评测、非商业使用，符合许可；**不重新分发完整第三方语料**，
仓库内仅保留查询/标准答案元数据、文档计数与 SHA256 指纹。语料全文与逐题证据文档只存放在仓库外工作缓存目录
`E:/project/ai-collab-benchmark-cache/`，该目录不入库、不属于长期证据（2026-11-09 清理本地缓存时已删除），
可按下述脚本从官方源确定性重建；评测结论以本目录入库的结果文件为准。

## 二、目录内容

| 文件 | 说明 |
| --- | --- |
| `README.md` | 本文件 |
| `dataset-manifest.json` | 数据来源、版本、SHA256、许可核验 |
| `sample-manifest.json` | 冻结抽样清单（DuRetrieval 300 查询分层明细；RGB 150 场景含官方答案与文档指纹） |
| `environment.json` | Git / 模型 / 运行配置指纹 |
| `retrieval-results.json` | DuRetrieval 逐题原始数据（6 变体 × 300 题：排名、命中、延迟、失败原因、逐题差异） |
| `retrieval-summary.csv` | DuRetrieval 各变体指标汇总 |
| `rgb-generation-results.jsonl` | RGB 逐题模型回答（含预测、官方 label、factlabel、延迟、token 用量） |
| `rgb-summary.csv` / `rgb-summary.json` | RGB 四类指标（官方口径与自定义诊断分开） |
| `failure-analysis.md` | 失败与负收益逐题分析 |
| `benchmark-report.md` | 最终报告《AI Collab RAG V2｜第三方公开 Benchmark 独立质量评测报告》 |
| `scripts/` | 冻结抽样脚本等评测工具 |

## 三、复现指南

### 0. 前置

- 仓库根 `.env` 含 `MODEL_CONFIG_MASTER_KEY`（生成模型密钥解密用，不外泄）。
- 本地 Ollama 运行 `qwen3-embedding:0.6b`（1024 维）；Docker 运行；隔离库容器：
  ```bash
  docker run -d --name ai-collab-bench-pg -p 5433:5432 \
    -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench -e POSTGRES_DB=bench pgvector/pgvector:pg17
  ```
- 第三方数据下载（不入仓库）：
  - DuRetrieval：`https://hf-mirror.com/datasets/C-MTEB/DuRetrieval` 与 `C-MTEB/DuRetrieval-qrels` 的 parquet，
    SHA256 见 `dataset-manifest.json`；corpus 另转 JSONL（`corpus.jsonl`）。
  - RGB：`https://github.com/chen700564/RGB` @ `65ec39e4` 的 `zh_refine.json / zh_int.json / zh_fact.json` 与官方脚本。
- Python 依赖（仅抽样/统计用）：`pip install pyarrow`。

### 1. 冻结抽样

```bash
python docs/benchmark/2026-11-07-rag-v2-public-benchmark/scripts/01_freeze_sample.py
# 产出 docs/benchmark/.../sample-manifest.json 与缓存目录 rgb-frozen-inputs.json（后者不入仓库）
```

### 2. 索引官方全量语料（约 1.5–2 小时，含断点续跑）

```bash
cd ai-collab-backend
AI_DU_BENCHMARK=true \
AI_DU_BENCH_DB_URL='jdbc:postgresql://127.0.0.1:5433/bench' \
AI_DU_BENCH_DB_USER=bench AI_DU_BENCH_DB_PASSWORD=bench \
./mvnw test -Dtest='DuRetrievalBenchmarkPostgresTest#indexOfficialCorpus'
```

### 3. 六变体对照评测（A1 Dense / A2 Lexical / A3 RRF / A4+Window / A5+Parent / A6 Full Default）

```bash
AI_DU_BENCHMARK=true AI_DU_BENCH_DB_URL=... ./mvnw test -Dtest='DuRetrievalBenchmarkPostgresTest#evaluateVariants'
# 产出 target/du-benchmark/{retrieval-results.json,retrieval-summary.csv,index-summary.json}
```

### 4. RGB B1 生成评测（约 270 次生成调用）

```bash
AI_RGB_BENCHMARK=true AI_RGB_BENCH_DB_URL=... ./mvnw test -Dtest=RgbGenerationBenchmarkPostgresTest
# 产出 target/rgb-benchmark/{rgb-generation-results.jsonl,rgb-summary.json,rgb-summary.csv}
```

## 四、公平性与边界声明

1. **不修改生产 RAG 代码**：harness 为独立测试类，全部检索组件生产原样复用；Benchmark 专用适配仅限语料装载 SQL、
   文档身份映射（`original_filename = "duretrieval/<pid>"`）与指标计算。
2. **文档身份映射**：候选 chunk 先还原公开 passage id、去重（同一 passage 多 chunk 只计一次）后再与 qrels 对账；
   不将项目内部 UUID 与官方 pid 直接比较。
3. **答案零泄漏**：qrels/标准答案只用于离线评分，从不进入 Prompt 或检索元数据。
4. **同条件对照**：六变体共用同一冻结语料、同一 300 查询、同一 Embedding 模型与指纹、同一隔离库。
5. **协议诚实标注**：300 查询抽样与 RGB 150 场景抽样均为**自定义协议**（官方 dev 全集为 2000 查询 / RGB 全量 300+100+100），
   成绩不得标记为官方排行榜分数；B1 模式不测量检索能力，不得宣称端到端 RAG 成绩。
6. **评分偏差**：RGB 官方 `reject_evalue.py`/`fact_evalue.py` 需 GPT-3.5 判分器（付费 API，未授权），本轮以官方
   `evalue.py` 的确定性字符串判定为准，差异在报告中标注；生成 temperature=0（官方示例 0.2）。
7. DuRetrieval corpus 官方数据存在少量重复 id 行，harness 已跨批次去重，实际索引数见 `index-summary.json`。

## 五、最终裁决

见 `benchmark-report.md` 末节（`PUBLIC_BENCHMARK_COMPLETED / PARTIAL / BLOCKED / INVALID`）。
