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
