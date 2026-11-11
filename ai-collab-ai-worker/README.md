# ai-collab-ai-worker

AI Collab 的 **Python 认知面**：PromptPack、跨语言契约、离线评测，以及 P1 的
`POST /v1/rag/rerank`（经 Java 内部模型网关调模型；超时/降级/幂等）。

- 设计依据：[`docs/design/python-ai-capability-plane-2026-11-11.md`](../docs/design/python-ai-capability-plane-2026-11-11.md)
  §1.4（边界）、§6.1–§6.4（目录/技术栈/批次/契约纪律）、§7.1–§7.3（通用约定/PromptPack/Rerank）、§10（门禁）
- 批次：**P0**（2026-11-11）、**P1 rerank**（2026-11-12）
- 冻结口径：[`docs/archive/handoffs/w3-rerank-freeze-20261112.md`](../docs/archive/handoffs/w3-rerank-freeze-20261112.md)
- 运行时：CPython 3.13 · FastAPI · Pydantic v2 · httpx · uv；端口 `9100`

## 1. 边界（工程不变量，不可交易）

| 能力 | P0 状态 |
| --- | --- |
| 连 PostgreSQL / Redis / MinIO | **禁止**（依赖树里也没有任何客户端） |
| 持有 `JWT_SECRET` / `POSTGRES_*` / `*_API_KEY` | **禁止**：出现即启动失败（§10.1） |
| 写业务表、发预签名 URL、代理 SSE、读 Cookie | **禁止** |
| 出站调用（模型 / Java 网关） | P0 零外呼；P1 rerank 只调用 Java 内部模型网关，不直连 Provider（设计 §1.4） |
| 执行业务写操作 / 审批 / 租约 / 账本 | **禁止**：全部留在 Java 控制面 |

P0 的 `/readyz` 只校验本地 PromptPack 资产（manifest 可读、每个模板的 `sha256` 与
`variables` 自洽），不依赖任何外部进程。

## 2. 目录

```text
ai-collab-ai-worker/
  pyproject.toml  uv.lock  .python-version  Dockerfile  .dockerignore
  prompts/                       # PromptPack 资产（唯一来源）
    manifest.json                # key/version/sha256/chars/variables
    knowledge.system/2026-11-11.2.md
  src/ai_collab_worker/
    main.py                      # FastAPI 装配（PromptPack + rerank 服务）
    config.py                    # pydantic-settings + env allowlist
    api/                         # health / prompts / rerank / errors
    clients/                     # java_gateway（唯一的出站模型调用）
    contract/                    # Pydantic 模型 + JSON Schema 快照（schemaVersion=1）
    cognition/rerank/            # 打分 Prompt、模型输出解析、缺失补齐
    prompts/                     # manifest 加载、sha256 校验、manifest 构建 CLI
    eval/                        # canonical JSON、`ai-eval` CLI
    observability/               # Prometheus 指标与结构化日志
  tests/{unit,contract,integration}
```

## 3. 快速开始

```bash
cd ai-collab-ai-worker
uv sync --frozen
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
| POST | `/v1/rag/rerank` | `{schemaVersion,query,topK,candidates,deadlineMs}` → 全量 `scores` + `modelAlias` + `usage`；经 Java 内部模型网关打分 |

### 4.1 Rerank 语义（P1，冻结）

- 请求：`query` 1–4000（去空白非空）、`candidates` 1–50（`id` 1–128 且唯一、`title` ≤500、
  `text` 1–2000）、`topK` 1–50 默认 8、`deadlineMs` 1–30000 默认 3000；`extra` 字段一律 422。
- 响应：`scores` **覆盖全部输入 id、按输入顺序**，`score ∈ [0,1]`；模型漏掉的 id 补 `0.0`，
  同时记 `partial` 指标与 `missing_ids` 日志（不伪造为模型真值）。
- 超时：`asyncio.timeout(deadlineMs)` 到点即 `DEADLINE_EXCEEDED`(504)，**不返回半成品**。
- 幂等：可选 `Idempotency-Key`（8–128 位 `[A-Za-z0-9._:-]`）；同 key + 同请求体 10 分钟内
  逐字节重放且不再调用网关；同 key + 不同请求体 → `INVALID_REQUEST`(400)。缓存有界（≤1024），
  TTL 用单调时钟；只缓存成功响应（瞬时失败允许重试）。
- 鉴权（W3.2 起支持 Ed25519 短令牌，默认仍是静态令牌）：
  - `static`（默认）：`Authorization: Bearer <AI_WORKER_SERVICE_TOKEN>`，常量时间比较；
    令牌未配置时 rerank 一律 503 `MODEL_UNAVAILABLE`（fail closed），除非显式
    `AI_WORKER_ALLOW_INSECURE_INTERNAL=true`（仅测试）；令牌不匹配返回 401
    （`MODEL_UNAVAILABLE`，不可重试）。
  - `ed25519`：只接受 Java 签发的 Ed25519 JWS 短令牌（`alg=EdDSA`、`typ=JWT`、`kid` 在
    `AI_WORKER_ED25519_PUBLIC_KEYS` 内、`iss=ai-collab-backend`、`sub=backend`、
    `aud` 含 `ai-worker`、`scope` 含 `rerank`、`exp/iat` 允许 ±60s）；公钥缺失/非法时进程
    仍可启动，但 rerank 503 且 `/readyz` 503。
  - `dual`：仅用于轮换过渡，先验 JWT，失败再按静态令牌常量时间比较；收敛后应切回
    `ed25519`（详见 runbook）。
  - 验签通过后，worker 把**入站 Bearer 原样**透传给 Java 内部模型网关（不重新签发、不落盘、
    不进日志）；静态模式下透传的也是同一个静态令牌。
  - **只作用于 rerank**，prompts 端点不加鉴权。
- 指标：`ai_worker_rerank_requests_total{outcome}`（`ok/deadline/model_error/invalid/partial`）、
  `ai_worker_rerank_duration_seconds`、`ai_worker_rerank_missing_scores_total`。

所有错误走统一信封 `{code, message, retryable, details?}`；9 个错误码见
[`src/ai_collab_worker/contract/README.md`](src/ai_collab_worker/contract/README.md) §2。

## 5. 契约纪律

- 契约版本 `schemaVersion=1`；**只加字段、不改语义**，破坏性变更必须新端点。
- 所有响应带 `schemaVersion`；P1 起所有请求带 `deadlineMs`（rerank 专用 1–30000ms，默认 3000；
  通用契约上限 600000ms），超时返回 `DEADLINE_EXCEEDED`，不得返回半成品。
- 重试只发生在 Java 侧（连接失败/502/503，指数退避 ≤2 次；**504 不重试**：worker deadline
  已耗尽，同 deadline 重试只会放大用户等待）；同一请求重试必须复用同一个 `Idempotency-Key`
  （8–128 位 `[A-Za-z0-9._:-]`）。
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
  ars/chars/sha256 都按这个正文计算。
- `chars` 是正文的 **Unicode 码点数量**（Python `len` / Java `codePointCount`），不是字节数，也不是
  UTF-16 code unit。
- `sha256` 是正文 UTF-8 字节（不含末尾换行）的 SHA-256，十六进制小写。
- 占位符语法 `{{ name }}`（花括号内允许空白）；`variables` 必须**恰好等于**模板实际占位符名字集合
  （排序去重）。多一个、少一个、顺序不对都是硬错误：启动/取用/测试三处都会失败。
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
# rerank 离线评测（P1）：heuristic = Java 基线代理；worker = 打真实 worker/模型
uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
  --scorer heuristic --json
uv run ai-eval rerank tests/fixtures/rerank_set/rerank_baseline.v1.jsonl \
  --scorer worker --worker-url http://localhost:9100 --json
```

- `rerank SET --scorer heuristic|worker [--json] [--output FILE]`：输出 NDCG@8、引用支持率、
  逐 case ranking；`--measure-latency [--runs N --warmup M]` 才做延迟采样（默认不测，保证
  同输入逐字节一致）。worker 失败退出码 4，stderr 带错误码与状态。
- 冻结集与标注口径见 [`tests/fixtures/rerank_set/README.md`](tests/fixtures/rerank_set/README.md)。
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

## 8. 配置与密钥

配置只读 `AI_WORKER_*`（pydantic-settings，`extra="forbid"`，未知的 `AI_WORKER_*` 直接启动失败）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `AI_WORKER_HOST` | `0.0.0.0` | 监听地址 |
| `AI_WORKER_PORT` | `9100` | 监听端口 |
| `AI_WORKER_LOG_LEVEL` | `INFO` | 日志级别 |
| `AI_WORKER_ROLE` | `api` | `api` / `job`（同一镜像，P0 仅记录指标标签） |
| `AI_WORKER_PROMPTS_DIR` | 仓库内 `prompts/` | PromptPack 目录（容器里为 `/app/prompts`） |
| `AI_WORKER_SERVICE_TOKEN` | 空 | 静态服务令牌；`static`/`dual` 模式使用，未配置时 rerank fail closed（503），值不打印、不入文档 |
| `AI_WORKER_TOKEN_MODE` | `static` | 令牌模式：`static`（默认，行为不变）/ `ed25519`（只验短令牌）/ `dual`（轮换过渡：先验 JWT，失败再比静态） |
| `AI_WORKER_ED25519_PUBLIC_KEYS` | 空 | Ed25519 公钥集合 `kid=SPKI DER base64[,kid=...]`；支持多 kid 轮换，值不打印 |
| `AI_WORKER_ALLOW_INSECURE_INTERNAL` | `false` | 仅测试用：令牌未配置时仍放行 rerank；生产必须 false |
| `AI_WORKER_JAVA_GATEWAY_URL` | `http://app:8080` | Java 控制面地址；rerank 只经此调用内部模型网关 |
| `AI_WORKER_MODEL_ALIAS` | `rerank-default` | 响应 `modelAlias` 取值（worker 配置，不取上游响应） |
| `AI_WORKER_IDEMPOTENCY_TTL_SECONDS` | `600` | 幂等结果缓存 TTL（10 分钟） |
| `AI_WORKER_MAX_CANDIDATES` | `50` | 服务端候选上限；不得高于契约的 50 |

`AI_WORKER_SERVICE_TOKEN` 与 `AI_WORKER_ED25519_PUBLIC_KEYS` 只从环境变量读取：不写进日志、
不进错误信封、不落仓库。配置里**没有**任何 `*_API_KEY` 字段；Provider 密钥只在 Java 控制面。

W3.2 的 Ed25519 边界（冻结契约 §2）：

- **私钥只在 Java**（PKCS#8 DER base64，由 `INTERNAL_AI_ED25519_PRIVATE_KEY` 注入）；
  worker 只持公钥（SPKI DER base64），进程环境 allowlist 拒绝 `*_API_KEY`/`JWT_SECRET`，公钥
  不属于密钥材料但同样不打印。
- 短令牌 TTL 默认 300s、允许 ±60s 时钟偏移；Java 签发 `aud=["ai-worker","backend"]`、
  `scope="rerank model-call"`，worker 验 `ai-worker`/`rerank`，回传 Java 后由 Java 验
  `backend`/`model-call`。
- 轮换顺序：① Python 配置先加入新 `kid` 公钥并重启 → ② Java 切新 `kid`+私钥并重启 →
  ③ 观察 ≥2×TTL 无鉴权失败 → ④ 从 Python 移除旧公钥。**禁止**先删旧公钥再切 Java。
- 过渡：worker 可先设 `AI_WORKER_TOKEN_MODE=dual`（同时接受 JWT 与静态令牌），待 Java 切成
  `ed25519` 并观察稳定后，再收敛为 `ed25519`；过渡窗口应尽量短。
- 降级：两侧同时切回 `static`，复用同一个 `RERANK_SERVICE_TOKEN` / `AI_WORKER_SERVICE_TOKEN`。
- `/readyz` 增加 `auth` 检查：`ed25519` 模式公钥缺失/非法 → 503；`static` 模式未配置令牌时
  就绪探针仍为 200（端点逐请求 fail closed，与 P1 口径一致）。

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

## 11. 不在本批（P2+）

`/v1/rag/plan`、`/v1/agent/decide`、`/v1/planning/draft`、Memory 提取（Job 模式）、
MCP Host/Client、OTel 导出。P0 钉死契约/资产/门禁，P1 交付 rerank；其余按设计 §6.3 逐批推进。
