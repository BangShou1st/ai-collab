# ai-collab-ai-worker

AI Collab 的 **Python 认知面**：PromptPack、跨语言契约、离线评测。P0 是骨架批次，可部署、
可验证，但**不含**任何在线认知端点（rerank / plan / decide / planning 在 P1+）。

- 设计依据：[`docs/design/python-ai-capability-plane-2026-11-11.md`](../docs/design/python-ai-capability-plane-2026-11-11.md)
  §1.4（边界）、§6.1–§6.4（目录/技术栈/批次/契约纪律）、§7.1–§7.2（通用约定/PromptPack）、§10（门禁）
- 批次：**P0**（2026-11-11）
- 运行时：CPython 3.13 · FastAPI · Pydantic v2 · uv；端口 `9100`

## 1. 边界（工程不变量，不可交易）

| 能力 | P0 状态 |
| --- | --- |
| 连 PostgreSQL / Redis / MinIO | **禁止**（依赖树里也没有任何客户端） |
| 持有 `JWT_SECRET` / `POSTGRES_*` / `*_API_KEY` | **禁止**：出现即启动失败（§10.1） |
| 写业务表、发预签名 URL、代理 SSE、读 Cookie | **禁止** |
| 出站调用（模型 / Java 网关） | P0 **零外呼**；模型调用一律经 Java 网关（P1 起） |
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
    main.py                      # FastAPI 装配
    config.py                    # pydantic-settings + env allowlist
    api/                         # health / prompts / errors
    contract/                    # Pydantic 模型 + JSON Schema 快照（schemaVersion=1）
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

所有错误走统一信封 `{code, message, retryable, details?}`；9 个错误码见
[`src/ai_collab_worker/contract/README.md`](src/ai_collab_worker/contract/README.md) §2。

## 5. 契约纪律

- 契约版本 `schemaVersion=1`；**只加字段、不改语义**，破坏性变更必须新端点。
- 所有响应带 `schemaVersion`；P1 起所有请求带 `deadlineMs`（1–600000ms），超时返回
  `DEADLINE_EXCEEDED`，不得返回半成品。
- 重试只发生在 Java 侧（连接失败/502/503/504，指数退避 ≤2 次，非幂等端点不重试）；
  同一请求重试必须复用同一个 `Idempotency-Key`（8–128 位 `[A-Za-z0-9._:-]`）。
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
```

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

## 11. 不在本批（P1+）

`/v1/rag/rerank`、`/v1/rag/plan`、`/v1/agent/decide`、`/v1/planning/draft`、Memory 提取
（Job 模式）、MCP Host/Client、OTel 导出、模型网关调用与幂等缓存。P0 先把契约、资产与门禁
钉死，再按设计 §6.3 逐批接入。
