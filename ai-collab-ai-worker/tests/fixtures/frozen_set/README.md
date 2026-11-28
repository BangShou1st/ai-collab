# 冻结评测集：RAG 基线 v1

## 用途

`rag_baseline.v1.jsonl` 是 `ai-eval` 的**冻结输入基线**：Python 侧与 Java 侧读取同一份
文件，各自计算 canonical JSON 与 SHA-256，用于验证跨语言指纹逐字节一致（设计
[`python-ai-capability-plane-2026-11-11.md`](../../../../docs/design/python-ai-capability-plane-2026-11-11.md)
§6.3 P0、`ai-collab-ai-worker/README.md` §7）。

它只服务于"输入指纹可复算、可对齐"，不参与在线请求，也不包含任何真实用户数据、
密钥或未脱敏语料；所有问题均为按本仓库真实能力编写的合成样例。

## 格式

- 文件：UTF-8 **无 BOM**、LF 换行、以换行结尾；每行一个 case JSON 对象（`.jsonl`）。
- 空行（含纯空白行）忽略；`id` 全局唯一，按 Unicode 码点排序后进入 canonical 输出。
- 小数按十进制精确值解析（不是 binary64）；禁止 `NaN` / `Infinity`；禁止重复键。
- 字段（同一 case 内固定，供评测脚本消费）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | case 唯一标识，`rag-baseline-<序号>` |
| `question` | string | 用户中文问题 |
| `expectedCitations` | array | 期望引用：`{docId, section, score}`，`score` 为该 chunk 的召回分 |
| `retrievalScore` | number | 该 case 的检索总评（0–1） |
| `topK` | integer | 检索条数 |
| `filters` | object | `{projectId, includeArchived, locale}`，`locale` 可为 `null` |
| `tags` | array | 标签，保持写入顺序（canonical 不排序数组） |
| `needsHumanReview` | boolean | 是否需人工复核 |

## 冻结纪律

- 本文件**冻结后不得改动**：任何字段、排版或换行变化都会改变 canonical 字节与
  SHA-256，破坏与 Java 侧的指纹对齐。
- 需要新增/调整用例时，**新建版本文件**（如 `rag_baseline.v2.jsonl`）并在评审中说明
  变更理由与新指纹；旧版本保留不动，保证历史报告可复算。
- `README.md` 中的基线指纹随文件冻结；改文件必须同步改这里的指纹，否则视为漂移。

## 基线指纹（v1）

```text
sha256=90a2856d1bb512471f7b2243289b41230457e5f62a5d1adcb62acb0c18bb6146
bytes=2156  cases=5
```

复算方式（在 `ai-collab-ai-worker/` 下执行）：

```bash
uv run ai-eval fingerprint tests/fixtures/frozen_set/rag_baseline.v1.jsonl --json
uv run ai-eval verify tests/fixtures/frozen_set/rag_baseline.v1.jsonl \
  --sha256 90a2856d1bb512471f7b2243289b41230457e5f62a5d1adcb62acb0c18bb6146
```

canonical JSON 规则见 `ai-collab-ai-worker/README.md` §7.2；Java 侧对齐要点
（Jackson `USE_BIG_DECIMAL_FOR_FLOATS` / `WRITE_BIGDECIMAL_AS_PLAIN`、关闭
`ESCAPE_NON_ASCII`、码点排序）同节。

---

# P3 金标准夹具：查询理解 / 消歧 / 装配 / 输出安全（v1，2026-11-28）

## 用途

P3 契约（`docs/design/p3-qa-contract-2026-11-28.md`）§8.4/§8.5：查询理解与问答生成迁
Python 前，由 Java 侧导出的**等价性对拍金标准**。Python 侧实现（路由、多轮消歧、证据
装配、输出安全预检）必须对本组夹具逐字节/逐字段对拍通过，才允许切换。

生成器：`ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P3GoldenQueryUnderstandingExportTest.java`
（纯单元测试，不需要 Docker / Ollama / 数据库；路由与消歧为纯规则，输出确定）。

## 文件与指纹（v1）

| 文件 | 行数 | bytes | sha256 |
| --- | --- | --- | --- |
| `p3_routing_golden.v1.jsonl` | 56 | 27988 | `b599b403318f26943be060140d4b51b0e9b6e0d98e0aa180c7d2256772de22b2` |
| `p3_understanding_golden.v1.jsonl` | 15 | 84286 | `f7c30706ed25533b2a0d05d5c3d633ebc3193cdd9dd29169c8c2b9ce93ba0fdb` |
| `p3_safety_golden.v1.jsonl` | 35 | 27839 | `7ecec203884bd4e828290a9e5525c93cda72fc0a3bcd78b4250dc578310b3060` |
| `p3_context_golden.v1.jsonl` | 17 | 53137 | `418ae1899512f9737b01e4e0e910d639059e2c8d4620cdb63c68aa6b9ef29116` |

## 内容

- **routing**：39 题 manifest（dev 21 + holdout 18）逐题路由决策（complexity/strategy/
  reason/semanticQuery/subQueries/filters/documentIds）+ 16 条合成全分支样本
  （STRUCTURED、COMPLEX 比较/多问/连词、EXACT_IDENTIFIER、FAST、默认 dense-first、
  空查询、非法过滤字段回退）。注意 `syn-route-05`（全角"？使比较句退化为单子查询）
  与 `syn-route-17`（无问号三路分解）是一对刻意对照。
- **understanding**：15 条多轮消歧样本（STANDALONE/FOLLOW_UP/TOPIC_SWITCH、turn 分组、
  5-turn/3500 窗口、引用剥离、证据不足轮跳过、改写 2000 码点预算、孤儿消息、连续 USER）。
  `und-15` 用 emoji 代理对钉住"turn 长度按 UTF-16 单位计"的跨语言口径。
- **safety**：常量（K_GRAM=16、BLOCKED_ANSWER、INSUFFICIENT_ANSWER 逐字）、
  escapeXmlText（含限长边界）、knowledge.system 渲染文本的 k-gram 指纹摘要
  （count + sorted-join sha256）、inspect/contains 行为样本、时间行格式、引用校验
  （`[Sn]` 对照、混合编号、字符串 bodySnapshotId 无效身份）。
- **context**：TokenEstimator（CJK_WEIGHTED_V1）样本（含 `"ab"→1` 钉住 Math.round
  半值进位）、证据装配 `buildWithTokenBudget(candidates,5,2500)` 全丢弃原因
  （阈值/名额/同文去重/空正文/token 预算/句子抑制）、knowledge.user payload 装配
  （转义 + 上下文前缀；含 inputs 与模板正文供 Python 侧复渲染）。

## 复现

```bash
cd ai-collab-backend
P3_GOLDEN_FIXTURE_EXPORT=true ./mvnw -B -ntp test \
  -Dtest=P3GoldenQueryUnderstandingExportTest \
  -Dp3.golden.output.dir=../ai-collab-ai-worker/tests/fixtures/frozen_set
```

## 冻结纪律

与 `rag_baseline.v1.jsonl` 相同：冻结后不得改动；Python 等价测试只读不改。Java 行为
变更需要重导时**新建 v2 文件**并在评审中说明，旧版本保留不动。生成器断言了样本行为
（如 inspect/contains 的期望布尔值），行为漂移会在生成端先报错。
