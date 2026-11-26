# contract/：跨语言契约（schemaVersion=1）

本目录是 Python 与 Java 之间**唯一**的字段口径来源。修改任何模型都必须同批更新
`snapshots/v1/*.schema.json`（`uv run python -m ai_collab_worker.contract.schema --write`），
否则 `tests/contract` 直接红。

## 1. 文件

| 文件 | 内容 |
| --- | --- |
| `common.py` | `schemaVersion`、10 个错误码（设计 9 码 + `INTERNAL_AUTH_FAILED`）、错误信封、`deadlineMs`、幂等键规则 |
| `prompts.py` | PromptPack manifest 与文档模型、占位符与 sha256/chars 计算（正文 = 文件去掉末尾唯一一个换行，允许正文自带尾换行） |
| `query_plan.py` | `POST /v1/rag/plan` 请求/响应模型；`filters` 只允许 `documentIds/docTypes/dateRange`，`dateRange` 为 `{from,to}` |
| `memory_job.py` | `POST /v1/jobs` 请求/响应、Memory 候选（kind/title/content/confidence/sourceRef/dedupeKey）、以及 Java 内部快照/心跳/结果端点形状（后者为内部传输形状，**不登记快照**） |
| `mcp.py` | 内部 MCP Gateway JSON-RPC 请求/结果/错误模型、Unicode NFC/canonical JSON、逐工具 Hash 与 catalogVersion、Schema 限制与注入中和 |
| `schema.py` | JSON Schema 渲染、比对（`--check`）与写回（`--write`） |
| `snapshots/v1/` | 入库的 JSON Schema 快照（8 个业务/信封模型 + W6 MCP 的 10 个请求、结果与 JSON-RPC 信封模型，共 18 个；rerank / decide / planning 的 6 个快照已于 2026-11-25 删除） |

## 2. 错误码与 HTTP 状态

| code | HTTP | retryable | 语义 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 / 422 / 403 | false | 请求体、路径或长度不合法；prompt 超 64 KiB 用 422；**jobs 的 job token claims 缺失/不一致用 403** |
| `DEADLINE_EXCEEDED` | 504 | true | 超过 deadline（plan/jobs 根级 `deadlineMs`），不得返回半成品 |
| `INTERNAL_AUTH_FAILED` | 401 | false | Ed25519 短令牌缺失/格式错/未知 kid/验签失败/过期/错 aud/错 scope；`details.reason` 为固定原因名（`malformed`/`alg`/`unknown_kid`/`bad_signature`/`subject`/`audience`/`scope`/`expired`），不含令牌、签名或 claim 值 |
| `MODEL_UNAVAILABLE` | 503 | true | 上游模型/网关不可用 |
| `MODEL_RATE_LIMITED` | 429 | true | 上游限流（plan 会透传；jobs 会转成 `FAILED_RETRYABLE`） |
| `PROMPT_UNAVAILABLE` | 503（未知 key/version 用 404） | false | Prompt 缺失/校验失败；Java 回退上次成功版本，不重试 |
| `BUDGET_EXCEEDED` | 429 | false | 本轮预算耗尽 |
| `SCHEMA_VIOLATION` | 422 | false | 模型输出不符合契约（plan 字段/越界、memory 候选 JSON/字段/条数非法） |
| `CONTENT_BLOCKED` | 400 | false | 内容安全拦截 |
| `INTERNAL` | 500 | true | 未预期错误；响应与日志都不含内部细节 |

## 3. 演进纪律

- 只加字段、不改语义；破坏性变更必须新端点（新路径/新版本），不得原地改。
- 所有响应带 `schemaVersion`；所有请求带 `deadlineMs`（设计 §6.4 / §7.1）。
- plan 是只读建议端点，无幂等缓存；Python 侧不做任何已完成响应缓存（rerank/decide 的
  `Idempotency-Key` 缓存已随端点于 2026-11-25 删除，`common.py` 仍保留键格式常量）。
- jobs 的幂等键在 Java：`(job_id, dedupe_key)` 唯一，重复 results 只更新心跳并返回既有结果；
  Python 侧不缓存、不重试。`candidates[].dedupeKey` 由 Python 计算
  `sha256(kind|normalized(title)|normalized(content))`。
- 契约模型 `extra="forbid"`：**Java 侧多传字段会 422**，这是有意的 fail-closed。
- MCP Host 不新增公共错误码；Java Gateway 的 `error.data.kind` 按冻结的 12 个
  `McpErrorKind` 原样透传，Host 自身只做固定消息映射，不拼接远端正文。
- 快照只允许通过 `--write` 更新，并与模型定义同批提交、在报告中说明影响面。
