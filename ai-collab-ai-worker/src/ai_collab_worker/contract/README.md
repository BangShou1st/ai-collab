# contract/：跨语言契约（schemaVersion=1）

本目录是 Python 与 Java 之间**唯一**的字段口径来源。修改任何模型都必须同批更新
`snapshots/v1/*.schema.json`（`uv run python -m ai_collab_worker.contract.schema --write`），
否则 `tests/contract` 直接红。

## 1. 文件

| 文件 | 内容 |
| --- | --- |
| `common.py` | `schemaVersion`、9 个错误码、错误信封、`deadlineMs`、幂等键规则 |
| `prompts.py` | PromptPack manifest 与文档模型、占位符与 sha256/chars 计算（正文 = 文件去掉末尾唯一一个换行，允许正文自带尾换行） |
| `rerank.py` | `POST /v1/rag/rerank` 请求/响应模型（候选 1–50、text 1–2000、deadline 1–30000、scores 覆盖全部 id） |
| `query_plan.py` | `POST /v1/rag/plan` 请求/响应模型；`filters` 只允许 `documentIds/docTypes/dateRange`，`dateRange` 为 `{from,to}` |
| `agent_decide.py` | `POST /v1/agent/decide` 请求/响应模型；四决策、context 三段总量、tools 1–32、usage 缺失 UNKNOWN；**无 token 流字段** |
| `planning.py` | `POST /v1/planning/draft` 请求/响应模型；template 三取值、constraints 边界、plan 1–8/1–40、文本与 warnings 上限、usage 缺失 UNKNOWN；响应**只有四个键** |
| `memory_job.py` | `POST /v1/jobs` 请求/响应、Memory 候选（kind/title/content/confidence/sourceRef/dedupeKey）、以及 Java 内部快照/心跳/结果端点形状（后者为内部传输形状，**不登记快照**） |
| `schema.py` | JSON Schema 渲染、比对（`--check`）与写回（`--write`） |
| `snapshots/v1/` | 入库的 JSON Schema 快照（`common-request` / `error-envelope` / `prompt-manifest` / `prompt-document` / `rerank-request` / `rerank-response` / `query-plan-request` / `query-plan-response` / `agent-decide-request` / `agent-decide-response` / `planning-request` / `planning-response` / `memory-job-request` / `memory-job-response`，共 14 个） |

## 2. 错误码与 HTTP 状态

| code | HTTP | retryable | 语义 |
| --- | --- | --- | --- |
| `INVALID_REQUEST` | 400 / 422 / 403 | false | 请求体、路径或长度不合法；rerank/plan 同 key 不同请求体用 400；decide 同键不同 body 用 400，prompt 超 64 KiB 用 422；planning 同上；**jobs 的 job token claims 缺失/不一致用 403** |
| `DEADLINE_EXCEEDED` | 504 | true | 超过 deadline（rerank/plan/planning/jobs 根级 `deadlineMs`，decide `budget.deadlineMs`），不得返回半成品 |
| `MODEL_UNAVAILABLE` | 503（鉴权失败用 401） | true | 上游模型/网关不可用；认知端点未配置服务令牌时 fail closed；`static` 模式下 `POST /v1/jobs` 固定 503 |
| `MODEL_RATE_LIMITED` | 429 | true | 上游限流（rerank/plan/decide/planning 会透传；jobs 会转成 `FAILED_RETRYABLE`） |
| `PROMPT_UNAVAILABLE` | 503（未知 key/version 用 404） | false | Prompt 缺失/校验失败；Java 回退上次成功版本，不重试 |
| `BUDGET_EXCEEDED` | 429 | false | 本轮预算耗尽 |
| `SCHEMA_VIOLATION` | 422 | false | 模型输出不符合契约（rerank 解析失败、plan 字段/越界、decide 四决策/工具名/arguments 非法且重试一次后仍非法、planning 数量/依赖/日期/成员/来源校验失败、memory 候选 JSON/字段/条数非法） |
| `CONTENT_BLOCKED` | 400 | false | 内容安全拦截 |
| `INTERNAL` | 500 | true | 未预期错误；响应与日志都不含内部细节 |

## 3. 演进纪律

- 只加字段、不改语义；破坏性变更必须新端点（新路径/新版本），不得原地改。
- 所有响应带 `schemaVersion`；P1 起所有请求带 `deadlineMs`，重试必须复用同一个
  `Idempotency-Key`（设计 §6.4 / §7.1）。
- rerank 的 `Idempotency-Key` 缓存 10 分钟（进程内、有界 ≤1024）；同 key + 同请求体返回
  逐字节相同的响应且不再调用模型，同 key + 不同请求体 → `INVALID_REQUEST`(400)。
- decide 的幂等键是 `(runId, attempt, turnNo, snapshotHash)`（不是请求头）：同键同 body 指纹
  逐字节重放，同键不同 body → 400；缓存同样 10 分钟、≤1024、只缓存成功。
  `budget.deadlineMs` 是预算不是身份，不参与指纹。
- plan 是只读建议端点，无幂等缓存。
- planning 是只读建议端点，无幂等缓存；`sourceRefs` 越界只丢弃不报错，`assigneeId` 越界置 null
  + warning。
- jobs 的幂等键在 Java：`(job_id, dedupe_key)` 唯一，重复 results 只更新心跳并返回既有结果；
  Python 侧不缓存、不重试。`candidates[].dedupeKey` 由 Python 计算
  `sha256(kind|normalized(title)|normalized(content))`。
- 契约模型 `extra="forbid"`：**Java 侧多传字段会 422**，这是有意的 fail-closed。
- 快照只允许通过 `--write` 更新，并与模型定义同批提交、在报告中说明影响面。
