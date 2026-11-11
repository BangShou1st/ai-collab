# contract/：跨语言契约（schemaVersion=1）

本目录是 Python 与 Java 之间**唯一**的字段口径来源。修改任何模型都必须同批更新
`snapshots/v1/*.schema.json`（`uv run python -m ai_collab_worker.contract.schema --write`），
否则 `tests/contract` 直接红。

## 1. 文件

| 文件 | 内容 |
| --- | --- |
| `common.py` | `schemaVersion`、9 个错误码、错误信封、`deadlineMs`、幂等键规则 |
| `prompts.py` | PromptPack manifest 与文档模型、占位符与 sha256/chars 计算（正文 = 文件去掉末尾唯一一个换行，允许正文自带尾换行） |
| `schema.py` | JSON Schema 渲染、比对（`--check`）与写回（`--write`） |
| `snapshots/v1/` | 入库的 JSON Schema 快照（`common-request` / `error-envelope` / `prompt-manifest` / `prompt-document`） |

## 2. 错误码与 HTTP 状态

| code | HTTP | retryable | 语义 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 / 422 | false | 请求体、路径或长度不合法 |
| `DEADLINE_EXCEEDED` | 504 | true | 超过 `deadlineMs`，不得返回半成品 |
| `MODEL_UNAVAILABLE` | 503 | true | 上游模型不可用（P0 不产生） |
| `MODEL_RATE_LIMITED` | 429 | true | 上游限流（P0 不产生） |
| `PROMPT_UNAVAILABLE` | 503（未知 key/version 用 404） | false | Prompt 缺失/校验失败；Java 回退上次成功版本，不重试 |
| `BUDGET_EXCEEDED` | 429 | false | 本轮预算耗尽 |
| `SCHEMA_VIOLATION` | 422 | false | 模型输出不符合契约 |
| `CONTENT_BLOCKED` | 400 | false | 内容安全拦截 |
| `INTERNAL` | 500 | true | 未预期错误；响应与日志都不含内部细节 |

## 3. 演进纪律

- 只加字段、不改语义；破坏性变更必须新端点（新路径/新版本），不得原地改。
- 所有响应带 `schemaVersion`；P1 起所有请求带 `deadlineMs`，重试必须复用同一个
  `Idempotency-Key`（设计 §6.4 / §7.1）。
- 契约模型 `extra="forbid"`：**Java 侧多传字段会 422**，这是有意的 fail-closed。
- 快照只允许通过 `--write` 更新，并与模型定义同批提交、在报告中说明影响面。
