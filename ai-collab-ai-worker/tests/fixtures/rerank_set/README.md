# 冻结评测集：Rerank 基线 v1

## 用途

`rerank_baseline.v1.jsonl` 是 `ai-eval rerank` 的**冻结输入**，用于度量重排（rerank）的
排序质量与延迟，口径唯一来源是
[`w3-rerank-freeze-20261112.md`](../../../../docs/archive/handoffs/w3-rerank-freeze-20261112.md) §4：

- Python 侧 `--scorer heuristic` 用 Python 逐条复刻 Java `SemanticHeuristicReranker`
  （生产实际使用的 `LEXICAL_HEURISTIC`），作为 Java 基线的代理；
- `--scorer worker` 以冻结 §1 的请求打真实 `/v1/rag/rerank`，端到端计入延迟；
- 同一份输入也供 Java 侧一致性测试读取，保证两种实现可比。

**语料全部为合成**：虚构的项目管理/运维文档片段，不包含任何生产数据、真实人名、客户名或
密钥；与仓库真实文档的相似只是术语层面的，不是复制。

## 格式

- UTF-8 **无 BOM**、LF 换行、以换行结尾；每行一个 case（`.jsonl`），空行忽略。
- 固定 `topK=8`；每条 case 6–10 个候选（`min(8, 候选数)` 决定支持率分母）。
- 字段（每条 case 恰好这 5 个键，多/少都判为不合法）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | case 唯一标识，`r01`…`r15` |
| `query` | string | 用户中文问题；非空、去空白后非空、≤4000 字符 |
| `candidates` | array | 6–10 条 `{id,title,text}`；`id` 请求内唯一，`title` 可空 ≤500，`text` 1–2000 字符 |
| `labels` | object | 候选 id → 相关度 `0/1/2`，必须覆盖全部候选 |
| `supports` | object | 候选 id → 是否能作为引用来源（布尔），必须覆盖全部候选 |

`candidates` 的**文件内顺序不参与评测**：两种打分器都会重新排序，评测只看排序结果。

## 标注口径

相关度三档（`labels`）：

| 值 | 含义 | `supports` |
| --- | --- | --- |
| `2` | 直接回答 query 的核心问题，可单独作为引用来源 | 必须 `true` |
| `1` | 主题相关，提供背景/约束/相邻步骤，但**不能单独**回答问题 | 视是否含可引用的具体事实；本集真/假混用 |
| `0` | 无关，或只共享查询词但不回答问题 | 必须 `false` |

- 标注只看"文档片段能否回答 query"，**不看打分器输出**；`2` 档数量不按位置分布凑数。
- `labels` 与 `supports` 的一致性由加载器 fail-closed 校验：`2→true`、`0→false`，
  `1` 由标注者按"能否被引用"决定。
- 每条 case 的 `labels` 必须同时覆盖 0/1/2 三档（有测试断言）。

query 类型覆盖（设计 §6.3 P1 要求的五类）：

| 类型 | case |
| --- | --- |
| 精确术语 | `r01` 里程碑/依赖重排、`r06` HNSW/ef_search、`r11` 分块/重叠窗口、`r14` RAG 引用 |
| 口语化 | `r02` 任务拖到最后、`r07` 大半夜告警、`r12` 新人看文档 |
| 长句（无标点长中文） | `r03` 解析失败排查、`r08` 成员离职交接、`r13` 灰度放量判断 |
| 英文缩写 | `r04` SLA/SLO、`r06` HNSW、`r09` MCP、`r14` RAG |
| 跨文档对比 | `r05` 备份恢复 vs 数据库迁移、`r10` 发布锁 vs 变更审批、`r15` 在制品限制 vs 迭代容量 |

## 指标口径（冻结 §4）

- **NDCG@8**：graded gain `2^rel-1`、discount `log2(rank+1)`，IDCG 取理想排序；
  逐 case 平均。无正例（全部 `rel=0`）的 case 记 `1.0`，并在报告里用
  `"noPositiveLabels": true` 标注（本集 15 条都有正例，该分支由单测覆盖）。
- **引用支持率**：top-8 中 `supports[id]=true` 的条数 / `min(8, 候选数)`；逐 case 平均。
- **P95**：最近秩法（升序后取 `ceil(95*n/100)` 位），不是线性插值；样本是每条 case
  连续执行 20 次（前 2 次 warmup 不计）的端到端毫秒耗时：每条 18 个有效样本，
  15 条共 **270** 个样本，取全体样本的 P95。

## 复算冻结集指纹

```bash
cd ai-collab-ai-worker
uv run ai-eval fingerprint tests/fixtures/rerank_set/rerank_baseline.v1.jsonl --json
# {"bytes":19704,"cases":15,"sha256":"f5baa744542204b13a0750f0550928644b8b75de92cc8384b23c24cc21aa43e3"}

uv run ai-eval verify tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
  --sha256 f5baa744542204b13a0750f0550928644b8b75de92cc8384b23c24cc21aa43e3
```

`bytes` 是 canonical JSON 的字节数（不是文件字节数），规则见
`ai-collab-ai-worker/README.md` §7.2：键按 Unicode 码点排序、无空白、无尾随换行，
以外层 `{"cases":[...],"schemaVersion":1}` 形状编码。

## 复算评测报告

```bash
# Java 基线代理；默认不测耗时，因此两次运行逐字节一致
uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl --scorer heuristic --json

# 冻结口径的延迟采样（每条 case 20 次，前 2 次 warmup 不计；实测耗时会破坏逐字节一致）
uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
  --scorer heuristic --measure-latency --json

# 缩短采样（CI 冒烟用）：每条 case 4 次、前 1 次不计
uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
  --scorer heuristic --measure-latency --runs 4 --warmup 1 --json

# 真实 worker（令牌只从环境变量读，绝不写进文件/命令历史）
export AI_WORKER_SERVICE_TOKEN=...   # Windows: $env:AI_WORKER_SERVICE_TOKEN = ...
uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
  --scorer worker --worker-url http://127.0.0.1:9100 --json
```

冻结时的 heuristic 基线（与本文件同版本，用于回归对比）：

```text
ndcgAt8=0.9614184192429411  supportRate=0.5361111111111111  cases=15
report sha256=703dff1b6457766d929670c0a4e59f22463663be213f73317ca1ed169c78f40c
stdout sha256=2b4cfcab24468659f51c39558e94e87993f9e06f9bf62bf464e3cf63a0cf5d72
```

本机（Windows / CPython 3.13）实测 heuristic 的 P95 在 **约 0.1–0.15 ms** 量级（270 个样本），
每次运行都会浮动。这正是默认不测耗时、只有 `--measure-latency` 才采样的原因：一旦把实测
耗时写进报告，就不再满足冻结要求的"同输入两次运行逐字节一致"。

两条 SHA-256 的含义不同，复算时不要混：

1. 报告里的 `sha256` 字段 = `SHA-256(canonical(报告对象去掉 sha256 键))`，**不含**它自己；
2. `--json` 写到 stdout 的**整段字节**的 SHA-256（含 `sha256` 字段）。上表的 stdout 摘要
   只对"默认不测延迟"的输出成立；`--measure-latency` 的耗时每次都不同，不承诺字节一致。

## 冻结纪律

- 本文件**冻结后不得改动**：任何字段、文案、排版或换行变化都会改变 canonical 字节与
  SHA-256，破坏与 Java 侧的指纹对齐。
- 需要新增/调整用例时**新建版本文件**（如 `rerank_baseline.v2.jsonl`），在评审中说明
  变更理由与新指纹；旧版本保留不动，保证历史报告可复算。
- `README.md` 中的指纹随文件冻结；改文件必须同步改这里的指纹，否则视为漂移。

## 已知局限

- `topK=8` 且候选只有 6–10 条时，正例几乎都会进入 top-8，NDCG 接近上界，主要区分
  "正例是否排在负例之前"；跨数据集不可比，只用于同集回归与 shadow 对比。
- `heuristic` 是 Java 生产实现（词面启发式）的代理，不代表神经重排质量；真实模型未接入
  时 `worker` 结果只验证链路，按要求视为 `NOT_VERIFIED`，不冒充线上 NDCG。
- 15 条样本只够做门禁回归，不做显著性判断；延迟 P95 受机器负载影响，需在同一环境对比。
