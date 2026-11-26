# ai-collab-ai-worker

AI Collab 的 **Python 认知面**：PromptPack、跨语言契约、离线评测，以及两个认知端点
`POST /v1/rag/plan`（查询规划）与 `POST /v1/jobs`（Memory 提取“执行一次”），全部经 Java
内部模型网关调模型；另有 W6 P1/P2-A 默认关闭的内部 MCP Host 客户端与独立测试进程
（只调用 Java Gateway；默认主 app 不暴露 MCP 路由）。

- 设计依据：[`docs/design/python-ai-capability-plane-2026-11-11.md`](../docs/design/python-ai-capability-plane-2026-11-11.md)
  （边界、契约纪律、PromptPack、Query plan、Job、门禁）；
  [`docs/design/ai-service-split-and-redis-2026-11-25.md`](../docs/design/ai-service-split-and-redis-2026-11-25.md)
  §0/§4.1/§11：**只保留 Ed25519 短令牌**，删除 rerank / decide / planning 三个端点与静态令牌模式。
- 历史批次：P0（2026-11-11）、P1 rerank、P2 plan + decide、P3 planning draft + Memory Job、
  W6 MCP Host（2026-11-12～13）；rerank / decide / planning 已于 2026-11-25 删除，
  `docs/archive/handoffs/` 下的冻结文档只作历史记录。
- 运行时：CPython 3.13 · FastAPI · Pydantic v2 · httpx · uv；端口 `9100`

## 1. 边界（工程不变量，不可交易）

| 能力 | P0 状态 |
| --- | --- |
| 连 PostgreSQL / Redis / MinIO | **禁止**（依赖树里也没有任何客户端） |
| 持有 `JWT_SECRET` / `POSTGRES_*` / `*_API_KEY` | **禁止**：出现即启动失败（§10.1） |
| 写业务表、发预签名 URL、代理 SSE、读 Cookie | **禁止** |
| 出站调用（模型 / Java 网关） | P0 零外呼；P1 起只调用 Java 内部模型网关与 Job 内部端点，不直连 Provider（设计 §1.4） |
| 外部 MCP / 任意 URL / 重试 / 授权判定 | **禁止**：MCP Host 只调用配置的 Java Gateway `/internal/ai/v1/mcp`；不直连外部 MCP、不跟随重定向、不自行签发/持久化 Token、不重试、不判定授权 |
| 执行业务写操作 / 审批 / 租约 / 账本 | **禁止**：全部留在 Java 控制面 |
| 解释 `leaseEpoch`、返回 token 流、写业务表 | **禁止**：worker 只返回建议或候选，Java 负责重校验、审批与落库 |
| Job 调度 / 重试决策 / 候选发布 | **禁止**：`POST /v1/jobs` 只“执行一次”；状态机、租约、重试、`review_status`、候选→`agent_memory` 全在 Java |

P0 的 `/readyz` 只校验本地 PromptPack 资产（manifest 可读、每个模板的 `sha256` 与
`variables` 自洽），不依赖任何外部进程。query plan 与 Memory Job 共用同一个
Java 网关连接池（Job 的三个内部端点用同源的第二个连接池，同样随 app 关闭）。启动时必须配置
`AI_WORKER_ED25519_PUBLIC_KEYS`，缺失或非法直接启动失败（见 §4.1）。

## 2. 目录

```text
ai-collab-ai-worker/
  pyproject.toml  uv.lock  .python-version  Dockerfile  .dockerignore
  prompts/                       # PromptPack 资产（唯一来源）
    manifest.json                # key/version/sha256/chars/variables
    knowledge.system/2026-11-11.2.md
  src/ai_collab_worker/
    main.py                      # FastAPI 装配（Ed25519 公钥环 + PromptPack + 共享网关 + 两个认知服务）
    mcp_host_app.py              # P2-A 独立 Uvicorn MCP Host 测试进程（默认不装配主 app）
    config.py                    # pydantic-settings + env allowlist
    auth/                        # Ed25519 公钥环与短令牌验签（只持公钥）
    api/                         # health / prompts / query_plan / jobs / errors / auth
    clients/                     # java_gateway（模型调用）+ java_jobs（Job）+ mcp_host（MCP Host）
    contract/                    # Pydantic 模型 + JSON Schema 快照（schemaVersion=1，18 个模型）
    cognition/query/             # query plan Prompt 与解析（filters 白名单/子集过滤）
    cognition/memory/            # 候选 Prompt、解析、sourceRef 校验与 dedupeKey 计算
    prompts/                     # manifest 加载、sha256 校验、manifest 构建 CLI、模板渲染
    eval/                        # canonical JSON、`ai-eval` CLI（memory / memory-arms）
    observability/               # Prometheus 指标与结构化日志
  tests/{unit,contract,integration,e2e}
```

## 3. 快速开始

```bash
cd ai-collab-ai-worker
uv sync --frozen
# 公钥必填；本地联调可用 tests/fixtures/auth/ed25519-test-fixture.json 的
# kid=publicKeySpki（仅测试密钥，不得用于任何共享环境）
AI_WORKER_ED25519_PUBLIC_KEYS="k1=<SPKI DER base64>" \
  uv run uvicorn ai_collab_worker.main:app --host 127.0.0.1 --port 9100
```

```bash
curl -s http://127.0.0.1:9100/healthz
curl -s http://127.0.0.1:9100/readyz
curl -s http://127.0.0.1:9100/metrics | head
curl -s http://127.0.0.1:9100/v1/prompts/manifest
curl -s http://127.0.0.1:9100/v1/prompts/knowledge.system/2026-11-11.2
```

## 4. 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/healthz` | 存活探针：进程可服务即 200，不检查任何依赖 |
| GET | `/readyz` | 就绪探针：PromptPack manifest 与全部模板校验通过才 200，否则 503 |
| GET | `/metrics` | Prometheus 文本格式（`prometheus-client`） |
| GET | `/v1/prompts/manifest` | `{schemaVersion, prompts:[{key,version,sha256,chars}]}`，条目按 `(key,version)` 升序 |
| GET | `/v1/prompts/{key}/{version}` | `{schemaVersion,key,version,sha256,text,variables}`；取用时校验 `sha256` 与 `variables` |
| POST | `/v1/rag/plan` | `{schemaVersion,query,locale,historySummary,documentIds,deadlineMs}` → `rewrittenQuery/subQueries/filters/confidence`；经 Java 网关做查询规划 |
| POST | `/v1/jobs` | `{schemaVersion,jobId,type:"MEMORY_EXTRACTION",deadlineMs}` → `{schemaVersion,jobId,status,candidateCount,latencyMs}`；**执行一次**，不调度/不重试/不发布 |

### 4.1 鉴权（Ed25519 短令牌，唯一模式）

- 只接受 Java 签发的 Ed25519 JWS 短令牌：`alg=EdDSA`、`typ=JWT`、`kid` 必须在
  `AI_WORKER_ED25519_PUBLIC_KEYS` 内；`iss=ai-collab-backend`、`sub=backend`、`aud` 含
  `ai-worker`，`exp/iat` 允许 ±60s 时钟偏移。端点按 scope 校验：`POST /v1/rag/plan` 要 `plan`，
  `POST /v1/jobs` 要 `job:execute`（另需 job claims，见 §4.3）。
- 公钥环启动必填：`AI_WORKER_ED25519_PUBLIC_KEYS` 缺失或非法时 `create_app` 抛
  `AuthConfigurationError`，进程**不对外服务**（fail fast；错误信息只含 kid 与格式，不含公钥值）。
  因此 `/readyz` 不再有 `auth` 检查项。
- 缺令牌、不是三段 JWS（包括旧的静态服务令牌）、未知 kid、验签失败、过期、错 aud / 错 scope，一律 401
  `INTERNAL_AUTH_FAILED`（不可重试）；`details.reason` 只给固定原因名（如 `unknown_kid` /
  `bad_signature` / `expired` / `scope`），不回显令牌、签名或 claim 值；日志只记 `scope` 与
  `reason`，不打印令牌。
- 验签通过后，worker 把**入站 Bearer 原样**透传给 Java 内部模型网关 / Job 内部端点（不重新签发、
  不落盘、不进日志），由 Java 再验 `backend` / `model-call` 等 scope。
- 静态令牌 `AI_WORKER_SERVICE_TOKEN`、`AI_WORKER_TOKEN_MODE`、`AI_WORKER_ALLOW_INSECURE_INTERNAL`
  已删除：再出现即按未知 `AI_WORKER_*` 拒绝启动（见 §8）。
- prompts 与健康端点不加鉴权。

所有错误走统一信封 `{code, message, retryable, details?}`；10 个错误码见
[`src/ai_collab_worker/contract/README.md`](src/ai_collab_worker/contract/README.md) §2。

### 4.2 Query plan 语义（P2，冻结）

- 请求：`query` 1–4000（去空白非空）、`locale` 2–35（`^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$`）、
  `historySummary` 0–8000（可缺省）、`documentIds` 0–200（每项 1–128 且唯一）、
  `deadlineMs` 1–30000 默认 8000；`extra` 字段一律 422。
- 响应：`rewrittenQuery` 1–4000；`subQueries` 0–8、每项 1–500、不重复；
  `filters` **只允许** `documentIds/docTypes/dateRange` 三个键（`extra=forbid`）；
  `dateRange` 为 `{from,to}`（ISO-8601 `YYYY-MM-DD`，两端可空，`from<=to`）；`confidence ∈ [0,1]`。
- `filters.documentIds` 必须是请求 `documentIds` 的子集：模型编造的 id 被丢弃并记
  `ai_worker_query_plan_unknown_document_ids_total`（按去重后的 id 数），不报错。
- 解析：允许 Markdown 围栏包裹的单个 JSON object；字段缺失/类型错/越界 → `SCHEMA_VIOLATION`(422)。
- **`confidence < 0.5` 的语义由 Java 决定（Java 使用原查询），Python 只如实返回，不做降级。**
- 超时：`asyncio.timeout(min(请求 deadlineMs, AI_WORKER_QUERY_PLAN_DEADLINE_MS))` 到点即
  `DEADLINE_EXCEEDED`(504)。幂等：无（只读建议端点）。
- 鉴权见 §4.1（scope `plan`）；指标 `ai_worker_query_plan_duration_seconds{outcome}`、
  `ai_worker_query_plan_confidence`、`ai_worker_query_plan_unknown_document_ids_total`；
  日志只记 `call_id/outcome/elapsed_ms`，不打印查询与历史摘要。

### 4.3 Memory 提取 Job 语义（P3，冻结）

- `POST /v1/jobs`：请求 `{schemaVersion, jobId(UUID), type:"MEMORY_EXTRACTION", deadlineMs}`，
  **不含快照**；响应 `{schemaVersion, jobId, status, candidateCount, latencyMs}`，
  `status ∈ {SUCCEEDED, FAILED_RETRYABLE, FAILED}`，`latencyMs` 是 worker 自测端到端耗时
  （不是模型耗时）。
- 鉴权：`require_claims(scope="job:execute", audience="ai-worker")`，只接受 Java 签发的
  job-scoped Ed25519 令牌。claims 必须含
  `jobId`（与 body 一致）、`attempt`、`leaseEpoch`、`snapshotHash`、`projectRef`；
  缺失/形状非法/不一致 → **403 `INVALID_REQUEST`**（固定短语，不回显 claim 值）。
- 执行顺序（一次、同步、不重试）：`POST heartbeat(start)` → `GET snapshot`（校验快照的
  `jobId/attempt/leaseEpoch/snapshotHash/projectId` 与 claims 逐项一致）→ PromptPack
  `memory.extract.system/user` → 模型调用（**入站同一 token**，`purpose=AGENT`；Java
  `ModelPurpose` 无 MEMORY 档位，不发明新值）→ 解析候选 → `POST results`
  （`{schemaVersion,attempt,leaseEpoch,candidates}`）→ 返回状态。
- 候选契约：`{kind ∈ DECISION/PREFERENCE/CONSTRAINT/LESSON, title 1–200, content 1–2000,
  confidence ∈ [0,1], sourceRef, dedupeKey(64 hex)}`，每次 ≤50 条。
  - `sourceRef` 必须引用快照 `facts[].ref` 或 `memory:<existingMemoryId>`；
    不合法 → **丢弃该候选**并记 `ai_worker_memory_invalid_source_total`（不报错）；
  - `dedupeKey = sha256(kind|normalized(title)|normalized(content))` **由 Python 计算**；
    `normalized` = NFKC → casefold → 连续空白折叠为单空格 → 去首尾空白。模型可省略
    `dedupeKey`；若给了非空值又与重算不一致 → 丢弃该候选并记
    `ai_worker_memory_invalid_dedupe_total`（防编造幂等键）。
- 失败映射（**HTTP 200 + 契约状态**，仅请求/令牌/模型输出非法才用错误码）：

  | 情况 | 响应 |
  | --- | --- |
  | 成功回报 | `status=SUCCEEDED` |
  | 端到端超时、网关超时/不可用/429、快照或结果回报的 5xx | `status=FAILED_RETRYABLE` |
  | 快照 404（job 不存在/已取消）、409 `STALE_LEASE`、其它 4xx、心跳已是终态 | `status=FAILED` |
  | 模型输出不是合法候选 JSON / 字段越界 / 超过条数上限 | **422 `SCHEMA_VIOLATION`**（不回报结果） |

- **红线**：Python 不建 Job 表、不调度、不决定重试、不发布候选、不写任何业务表；
  `ai_job_result.review_status` 永远停在 `PENDING_REVIEW`（那是 Java 的写路径）。
- 指标：`ai_worker_memory_job_requests_total{outcome}`、
  `ai_worker_memory_job_duration_seconds{outcome}`、`ai_worker_memory_candidates_total`、
  `ai_worker_memory_invalid_source_total`、`ai_worker_memory_invalid_dedupe_total`；
  日志只记 `job_id/status/elapsed_ms/reason`，不打印快照正文、候选正文与令牌。

### 4.4 内部 MCP Host 客户端与 P2-A 测试进程（W6 P1/P2-A，默认关闭）

- `src/ai_collab_worker/clients/mcp_host.py` 只实现 `initialize`、`ping`、`tools/list`、
  `tools/call` 的内部 JSON-RPC 客户端；目标固定为配置的 `AI_WORKER_JAVA_GATEWAY_URL` +
  `/internal/ai/v1/mcp`。默认主 app 不新增 MCP 路由，生产路径是否启用由调用方决定。
- 身份只来自每次调用注入的 Bearer 原值；客户端不签发、不缓存、不落盘、不打印 Token，
  也不读取任何 Provider 密钥。请求失败不重试；读/写超时无法确认是否执行时返回
  `OUTCOME_UNKNOWN`，连接未建立返回 `NOT_EXECUTED`。
- Java JSON-RPC `error.data.kind` 原样映射为 `McpErrorKind`，不把远端 `error.message`
  拼进日志或异常。`tools/call` 结果保留
  `outcome/untrusted/truncated/data/latencyMs/warnings`，不把截断、未知或失败洗成空成功；
  `warnings` 缺失按空数组处理，存在时原样保留。
- `tools/list` 会重算逐工具 `toolSchemaHash` 与 `catalogVersion` 并与 Gateway 值比对；
  不一致返回 `SCHEMA_DRIFT`。Host 不自动确认新 Schema，也不自动修改工具参数或补默认值。
- 契约 fixture 为仓库根 `tests/fixtures/mcp/catalog-v1.json`；Python 测试对其暴露名、
  描述、Schema、逐工具 Hash 与目录 Hash 做逐字节断言。
- P2-A 真实进程验收使用独立的 `src/ai_collab_worker/mcp_host_app.py`（不是生产主路径）：

  ```bash
  MCP_HOST_GATEWAY_URL=http://127.0.0.1:18080 \
    uv run uvicorn ai_collab_worker.mcp_host_app:app \
    --host 127.0.0.1 --port 19100
  ```

  提供 `GET /healthz`、`GET /readyz`，以及测试端点 `POST /v1/mcp/initialize`、
  `/v1/mcp/ping`、`/v1/mcp/list`、`/v1/mcp/call`（同时保留 `/v1/mcp/tools/*` 别名）。
  `list/call` 的 Bearer 只从调用方 `Authorization` 逐请求透传；Gateway URL 只从
  `MCP_HOST_GATEWAY_URL` 读取，请求体不能改道。成功返回
  `{schemaVersion,requestId,operation,outcome:"SUCCEEDED",result}`；Java 失败返回 502
  并保留 `{kind,retryable}`，不返回远端消息、参数或 Token。

## 5. 契约纪律

- 契约版本 `schemaVersion=1`；**只加字段、不改语义**，破坏性变更必须新端点。
- 所有响应带 `schemaVersion`；所有请求带根级 `deadlineMs`：plan 1–30000 默认 8000，
  jobs 用根级 `deadlineMs`（1–120000 默认 60000）。超时返回 `DEADLINE_EXCEEDED`/对应失败状态，
  不得返回半成品。
- 重试只发生在 Java 侧（连接失败/502/503，指数退避 ≤2 次；**504 不重试**：worker deadline
  已耗尽，同 deadline 重试只会放大用户等待）；Python 侧不做幂等缓存
  （plan 是只读建议端点；jobs 的幂等键 `(job_id, dedupe_key)` 在 Java）。
- 契约模型 `extra="forbid"`：多传字段直接 422，这是有意的 fail-closed。
- JSON Schema 快照入库在 `src/ai_collab_worker/contract/snapshots/v1/`；**重生成必须等于入库**
  （`tests/contract/test_contract_snapshots.py`）。改契约后运行：

```bash
uv run python -m ai_collab_worker.contract.schema --write
```

## 6. PromptPack

- 文件布局：`prompts/<key>/<version>.md`，如 `prompts/knowledge.system/2026-11-11.2.md`。
- key 形如 `knowledge.system` / `scenario.project_research`；version 形如 `2026-11-11.1`。
- `prompts/manifest.json` 是唯一来源，条目字段：`key`、`version`、`sha256`、`chars`、`variables`；
  `manifest.prompts` 必须按 `(key, version)` 升序，不得重复。
- 模板正文口径：文件 UTF-8 无 BOM、LF 换行、以换行结尾；正文 = 文件内容去掉末尾**唯一一个**换行。
  正文自身允许以换行结尾（此时文件以两个换行结尾）——Java 文本块迁移来的六个 Scenario 正文就是这种形状；
  chars/sha256 都按这个正文计算。
- `chars` 是正文的 **Unicode 码点数量**（Python `len` / Java `codePointCount`），不是字节数，也不是
  UTF-16 code unit。
- `sha256` 是正文 UTF-8 字节（不含末尾换行）的 SHA-256，十六进制小写。
- 占位符语法 `{{ name }}`（花括号内允许空白）；`variables` 必须**恰好等于**模板实际占位符名字集合
  （排序去重）。多一个、少一个、顺序不对都是硬错误：启动/取用/测试三处都会失败。
- 端点消费的模板：`query_plan.system`（无变量）与 `query_plan.user`（`locale`/`query`/
  `historySummary`/`documentIds`）；`memory.extract.system`（`maxCandidates`）与
  `memory.extract.user`（`snapshotJson`/`allowedSourceRefsJson`）。端点只经 `PromptStore` 读取
  （每次重读并复算 `sha256`/`chars`/`variables`），版本取 manifest 中的最高版本。
- `decide.system` / `decide.user`（含 `decide.system@2026-11-12.1/.2/.3` 历史版本）与
  `planning.draft.system` / `planning.draft.user` 仍作为 PromptPack 资产保留：Java 侧 fallback
  文本与 `ai-prompts export-java --check` 依赖它们逐字节一致；worker 已**没有**消费它们的端点
  （2026-11-25 删除 decide / planning）。
- 不做在线编辑：改 Prompt 走代码评审 + 发布；Java 启动时拉 manifest 并预取所需 key，
  `sha256` 与文本不匹配即拒绝（设计 §7.2）。
- `GET /v1/prompts/manifest` 按设计 §7.2 只返回 `key/version/sha256/chars`；`variables` 只用于
  服务端校验与 `GET /v1/prompts/{key}/{version}` 响应，避免 Java 侧被迫多解析一个字段。
- manifest 与文件不一致时用 CLI 重建：

```bash
uv run ai-prompts check   # 校验（默认命令）
uv run ai-prompts write   # 按文件重建 manifest.json
```

## 7. `ai-eval` 与 canonical JSON

`ai-eval` 读冻结评测集，输出 canonical JSON 与 SHA-256，用于跨语言（Java 侧）对齐同一份
输入的指纹。同一输入重复运行必须逐字节一致。

```bash
uv run ai-eval canonicalize tests/fixtures/frozen_set/rag_baseline.v1.jsonl
uv run ai-eval fingerprint  tests/fixtures/frozen_set/rag_baseline.v1.jsonl --json
uv run ai-eval verify       tests/fixtures/frozen_set/rag_baseline.v1.jsonl --sha256 <hex>
# memory 离线评测（P3，只有 replay）：precision / recall / 一致率 / 重复率
uv run ai-eval memory tests/fixtures/memory_set/memory_baseline.v1.jsonl --json
# D7 semantic 匹配（诊断口径；真实模型 replay 集为 memory_real_gemini31.v1.jsonl）
uv run ai-eval memory tests/fixtures/memory_set/memory_real_gemini31.v1.jsonl \
  --matcher semantic --evidence "REPLAY (archived real model output)" \
  --quality-claim NOT_VERIFIED --model gemini-3.1-flash-lite --json
# D7-B 三组跨会话对照骨架：A/B 诊断，C NOT_VERIFIED，门禁 NOT_EVALUATED
uv run ai-eval memory-arms tests/fixtures/memory_arms/arms_cases.v1.json --json
```

`ai-eval rerank` / `decide` / `planning` 子命令已随对应端点于 2026-11-25 删除。
`tests/fixtures/rerank_set/rerank_baseline.v1.jsonl` 只作为 Java `RerankParityWithPythonTest`
的冻结输入保留（见该目录 README）。

- `memory SET [--json] [--output FILE] [--matcher exact|semantic]`（**只有 replay**）：输出
  `precision`、`recall`、`candidateAgreement`（precision/recall 的 F1）、`duplicateRate`、
  `schemaPassRate`、`cases`、`confidence`、`evidence`、`qualityClaim`；`duplicateRate` 用
  `sha256(kind|normalized(title)|normalized(content))` 与既有记忆比对。`--matcher exact`
  是默认旧行为；`--matcher semantic` 启用 D7 确定性词面代理（SequenceMatcher ≥0.82 且
  bigram 包含率 ≥0.80），报告写 `matcher/diagnosticOnly/matchAudit`。**precision/recall
  一律是诊断指标，不设门禁**；semantic 不会把无事实支撑的判定变成可召回。
- `memory-arms SET [--json] [--output FILE] [--measure-latency --runs N]`（D7-B 骨架）：
  A_LEGACY（治理前 3 参 list 语义的本地重建，不是当前 shipped `context(projectId)`）、
  B_GOVERNED_RECALL（当前 4 参 recall-safe + 相关性语义的本地镜像）、C_DREAMING
  （未实现 → `NOT_VERIFIED`）。三组对照门禁固定 `NOT_EVALUATED`；A/B 只给诊断数字，
  不得外推生产。fixture 与期望值见
  [`tests/fixtures/memory_arms/README.md`](tests/fixtures/memory_arms/README.md)。
- 冻结集与标注口径见 [`tests/fixtures/memory_set/README.md`](tests/fixtures/memory_set/README.md)。
- `canonicalize INPUT [-o FILE]`：输出 canonical JSON（无尾随换行）。给了 `-o` 写文件，否则写 stdout。
- `fingerprint INPUT [--json]`：打印 `sha256=<hex> cases=<n>`；`--json` 输出
  `{"bytes","cases","sha256"}`（不含机器相关路径，便于跨机器比对）。
- `verify INPUT --sha256 <hex>`：不匹配时退出码 3（CI 可直接用）。
- 退出码：`0` 成功；`2` 输入不合法（UTF-8/BOM/重复 id/非 JSON 对象等）；`3` 指纹不匹配。

### 7.1 输入格式

- `.jsonl`：UTF-8 无 BOM，每行一个 case 对象，空行忽略；
- `.json`：case 对象数组，或 `{"schemaVersion":1,"cases":[...]}`（便于把 canonical 输出再喂回来）。

每个 case 必须是 JSON 对象，且含非空字符串 `id`；`id` 全局唯一（否则退出码 2）。

### 7.2 canonical JSON 规则（Java 侧必须逐条对齐）

1. **形状**：单个 JSON 对象 `{"cases":[...],"schemaVersion":1}`；`cases` 按 `id` 的
   Unicode 码点升序；case 内部数组保持原顺序（不排序、不归一化）。
2. **键序**：所有对象键按 Unicode 码点升序（递归）。
3. **字符串**：UTF-8 原样输出（不转 ASCII、不转义非 ASCII）；只转义 `"`、`\` 与 `< 0x20`
   控制字符（`\b \f \n \r \t`，其余用 `\u00xx` 小写十六进制）。不做 NFC/NFD 归一化。
4. **整数**：十进制原样，无前导零；`-0` 归一为 `0`。
5. **小数**：按十进制精确值解析（不是 binary64），输出无指数、无正号、至少一位小数的规范
   十进制形式：`1.50 → 1.5`，`2.0 → 2.0`，`1e3 → 1000.0`，`-0.0 → 0.0`。
6. **字面量**：`true` / `false` / `null` 小写；拒绝 `NaN` / `Infinity`。
7. **空白**：分隔符 `,` 与 `:`，无缩进、无前后空白、**无尾随换行**。
8. **摘要**：`SHA-256(UTF-8 bytes(canonical JSON))`，十六进制小写；同一输入的摘要必须稳定。

Java 对齐要点：Jackson 需启用 `USE_BIG_DECIMAL_FOR_FLOATS` + `WRITE_BIGDECIMAL_AS_PLAIN`，
关闭 `ESCAPE_NON_ASCII`，键排序用 Unicode 码点比较（Java `String.compareTo` 是 UTF-16 code unit
比较，键为 ASCII 标识符时不产生差异）；字符数用 `codePointCount` 而不是 `length()`。

**评测报告的 canonical 形状**（`ai-eval memory|memory-arms --json` 共用）：对象本身即文档
（`canonicalize_document`，不强制 `{cases,schemaVersion}` 外壳、不重排数组）；报告自带
`sha256 = SHA-256(canonical(报告去掉 sha256 键))`，同一输入两次运行逐字节一致。

## 8. 配置与密钥

配置只读 `AI_WORKER_*`（pydantic-settings，`extra="forbid"`，未知的 `AI_WORKER_*` 直接启动失败）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_WORKER_HOST` | `0.0.0.0` | 监听地址 |
| `AI_WORKER_PORT` | `9100` | 监听端口 |
| `AI_WORKER_LOG_LEVEL` | `INFO` | 日志级别 |
| `AI_WORKER_ROLE` | `api` | `api` / `job`（同一镜像，P0 仅记录指标标签） |
| `AI_WORKER_PROMPTS_DIR` | 仓库内 `prompts/` | PromptPack 目录（容器里为 `/app/prompts`） |
| `AI_WORKER_ED25519_PUBLIC_KEYS` | 空（**必填**） | Ed25519 公钥集合 `kid=SPKI DER base64[,kid=...]`；支持多 kid 轮换；缺失或非法启动失败，值不打印 |
| `AI_WORKER_JAVA_GATEWAY_URL` | `http://app:8080` | Java 控制面地址；query plan 与 Memory Job 经此调用内部模型网关与 Job 内部端点 |
| `AI_WORKER_QUERY_PLAN_MODEL_ALIAS` | `query-plan` | query plan 的模型别名（本波响应不含 modelAlias，保留给后续网关选路） |
| `AI_WORKER_QUERY_PLAN_DEADLINE_MS` | `8000` | query plan 服务端 deadline 上限；实际取 min(请求 deadlineMs, 本值) |
| `AI_WORKER_MEMORY_JOB_DEADLINE_MS` | `60000` | Memory Job 服务端 deadline 上限（1–120000）；实际取 min(请求 deadlineMs, 本值) |
| `AI_WORKER_MEMORY_MAX_CANDIDATES` | `50` | 每次 Memory Job 提交给 Java 的候选上限；不得高于契约的 50 |

2026-11-25 删除的变量再出现即按未知 `AI_WORKER_*` 拒绝启动：`AI_WORKER_SERVICE_TOKEN`、
`AI_WORKER_TOKEN_MODE`、`AI_WORKER_ALLOW_INSECURE_INTERNAL`、`AI_WORKER_MODEL_ALIAS`、
`AI_WORKER_IDEMPOTENCY_TTL_SECONDS`、`AI_WORKER_MAX_CANDIDATES`、`AI_WORKER_DECIDE_*`、
`AI_WORKER_PLANNING_*`（`tests/unit/test_config.py` 逐个断言）。

`AI_WORKER_ED25519_PUBLIC_KEYS` 只从环境变量读取：不写进日志、不进错误信封、不落仓库。配置里
**没有**任何 `*_API_KEY` 字段；Provider 密钥只在 Java 控制面。

Ed25519 边界：

- **私钥只在 Java**（PKCS#8 DER base64，由 `INTERNAL_AI_ED25519_PRIVATE_KEY` 注入，Java 启动必填）；
  worker 只持公钥（SPKI DER base64）。公钥不属于密钥材料，但同样不打印。
- 短令牌 TTL 默认 300s、允许 ±60s 时钟偏移；Java 签发 `aud=["ai-worker","backend"]`，worker 按
  端点验 `ai-worker` + `plan` / `job:execute`，回传 Java 后由 Java 验 `backend` / `model-call`。
- **Job 端点只接受 job-scoped 令牌**：`POST /v1/jobs` 走 `require_claims(scope="job:execute")`，
  需要 claims 里的 `jobId/attempt/leaseEpoch/snapshotHash/projectRef`。
- 轮换顺序：① Python 配置先加入新 `kid` 公钥并重启 → ② Java 切新 `kid`+私钥并重启 →
  ③ 观察 ≥2×TTL 无鉴权失败 → ④ 从 Python 移除旧公钥。**禁止**先删旧公钥再切 Java。
- 不再有 `dual` 过渡模式，也不能降级回静态令牌：公钥配置错误只能修正配置后重启。

启动时还会扫描整个环境：一旦出现 `JWT_SECRET`（含 `JWT_SECRET_OLD` 这类变体）、
`POSTGRES_*`、`*_API_KEY`，或未知的 `AI_WORKER_*`，立即失败并拒绝启动，防止密钥被误挂载进
Python 进程。错误日志只写变量名，绝不写变量值。

## 9. 质量门禁

```bash
uv sync --frozen
uv run ruff check .
uv run ruff format --check
uv run mypy --strict src
uv run pytest
uv run python -m ai_collab_worker.contract.schema   # 18 个契约快照必须与模型一致
uv run ai-prompts check
uv run ai-prompts export-java --check
```

- 依赖树禁止出现设计 §6.2 的禁入包（LangChain/LlamaIndex/…、DB/对象存储客户端、
  任务队列、模型 SDK、torch/transformers）；`tests/unit/test_forbidden_dependencies.py` 同时检查
  **已安装分发**与 `uv.lock`，任一命中即红。
- 契约快照双向校验、PromptPack 的 `sha256/chars/variables` 自检、`ai-eval` 两次输出逐字节一致，
  都在 `pytest` 内。
- CI 的 `python-worker` job 用同一组命令；不依赖数据库、网络与任何密钥。

## 10. Docker

```bash
docker build -t ai-collab-ai-worker:dev .
docker run --rm -p 9100:9100 ai-collab-ai-worker:dev
```

镜像基于 `python:3.13-slim`，非 root 运行，`/app/prompts` 只读挂载即可；不装 dev 依赖、
不装任何 eval/local-ml extra。

## 11. 不在本批（P4+）

MCP 生产主路径、Python 直连外部 MCP、OTel 导出、Memory 候选审核 UI 与发布路径、Job 自动
dispatcher。W6 P1 只交付默认关闭的内部 Host 契约客户端。
`/v1/jobs` 只做 Memory 提取“执行一次”（Java 侧 `memory.job.enabled=false` 时没有自动调度，也没有
“候选 → `agent_memory`”的写路径），其余按设计 §6.3 逐批推进。
