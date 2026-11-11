# P0 交付报告：Python 认知面骨架（`ai-collab-ai-worker`）

- 日期：2026-11-11
- 分支：`dev`（开工基线 `bc3085e9`，含 V2.1「§1.4 边界」修订；本批 7 个代码/测试提交 + 本报告）
- 设计依据：[Java 控制面 + Python 认知面](../../design/python-ai-capability-plane-2026-11-11.md)
  §1.4（边界）、§4（能力归属）、§6.1–§6.4（目录/技术栈/批次/契约纪律）、§7.1–§7.2（通用约定/PromptPack）、§10（门禁）
- 交接依据：[j1-to-p0 handoff](../handoffs/j1-to-p0-handoff-20261111.md) §5
- 子项目说明：[`ai-collab-ai-worker/README.md`](../../../ai-collab-ai-worker/README.md)

## 0. 结论摘要

| # | 交付 | 结论 | 证据等级 |
| --- | --- | --- | --- |
| P0-1 | 骨架与工具链（uv / 锁文件 / 3.13 / Dockerfile / README） | 已交付 | IMPLEMENTED / TESTED |
| P0-2 | 服务装配 + `/healthz` `/readyz` `/metrics` + config allowlist | 已交付 | IMPLEMENTED / TESTED / Docker 运行冒烟 |
| P0-3 | `contract/` Pydantic v2 模型 + 4 份 JSON Schema 快照（schemaVersion=1） | 已交付 | TESTED（快照双向校验） |
| P0-4 | PromptPack 资产 + `manifest.json` + 两个 GET + sha256/占位符自检 | 已交付 | IMPLEMENTED / TESTED / Docker 运行冒烟 |
| P0-5 | `ai-eval` CLI（canonical JSON + SHA-256，可复算、幂等） | 已交付 | IMPLEMENTED / TESTED |
| P0-6 | `tests/{unit,contract,integration}`（184 项） | 已交付 | TESTED |
| P0-7 | CI 独立 `python-worker` job | 已交付（推送后 CI 验证） | IMPLEMENTED / NOT_VERIFIED(CI) |
| P0-8 | 禁入依赖断言（已安装分发 + `uv.lock` + `pyproject`） | 已交付 | TESTED |
| P0-9 | 本报告 | 已交付 | — |

**DoD 核对**

| DoD 项 | 结果 |
| --- | --- |
| `uv sync --frozen` 可复现（含 `--offline`） | PASS（37 packages，离线复跑 0 变更） |
| `ruff check` + `ruff format --check` + `mypy --strict src` + `pytest` | PASS（184 passed；20 个源文件零 mypy 错误；39 个文件格式一致） |
| 依赖树无禁入包 | PASS（安装分发 + 锁文件 + pyproject 三重断言） |
| `/healthz` `/readyz` `/metrics` 可起 | PASS（TestClient + 真实 uvicorn + Docker 容器） |
| `/v1/prompts/manifest` 与 `/{key}/{version}` 正确且 sha256 校验通过 | PASS（manifest 与文档逐字节自洽） |
| `ai-eval` 同一冻结集两次输出 canonical JSON 完全一致 | PASS（`sha256=90a2856d…`，`bytes=2156`，`cases=5`） |
| 零 DB、零密钥、零外呼 | PASS（依赖树无客户端；env 禁入清单；`src/` 无出站 HTTP 代码） |
| CI 新增 `python-worker` job 绿、现有 job 不受影响 | 推送后验证（本报告 §7 记录待办） |
| 报告落盘 / 工作树干净 / 分支仅 `dev`、`main` | PASS |

## 1. 范围与边界

**本批做**：在独立子目录 `ai-collab-ai-worker/` 交付可运行、可验证的 Python 认知面骨架——
FastAPI 装配、健康/指标、跨语言契约与 JSON Schema 快照、PromptPack 资产与查询接口、离线
`ai-eval` CLI、三层测试、Dockerfile 与 CI job。

**本批不做**（设计 §1.4 / §6.3 明确）：

- 不实现 P1+ 认知端点（rerank / plan / decide / planning / memory / MCP）；
- 不连接 PostgreSQL / Redis / MinIO，不写任何业务表，不持密钥，无任何出站调用；
- 不改 Java、前端、Flyway 迁移（最新仍 V89）、既有 CI job、部署 compose；
- 不把 worker 接入 `ai-collab-deploy`（生产开关 `AI_WORKER_ENABLED` 的接入属于后续批次）。

**边界实现方式**（不是文档承诺，而是代码事实）：

- 禁入依赖靠 `tests/unit/test_forbidden_dependencies.py` 三重断言（已安装分发、`uv.lock`、`pyproject`）；
- 密钥/DB 凭据靠 `config.assert_env_allowlist`：`JWT_SECRET*`、`POSTGRES_*`、`*_API_KEY`、
  未知 `AI_WORKER_*` 任一出现即拒绝启动，错误只写变量名不写值；
- 出站调用：`src/` 内无 `httpx/requests/urllib/aiohttp` 导入；`httpx` 只在 dev 依赖里供
  FastAPI `TestClient` 使用，运行镜像 `--no-dev` 不含它。

## 2. 改动清单

**规模**：54 个文件、+5763 行（相对开工基线 `bc3085e9`，不含本报告与文档索引；其中 `uv.lock`
558 行、PromptPack 资产 2 个文件、源码 20 个 `.py`）。

```text
ai-collab-ai-worker/
  pyproject.toml  uv.lock  .python-version  Dockerfile  .dockerignore  README.md
  prompts/
    manifest.json
    knowledge.system/2026-11-11.1.md
    README.md
  src/ai_collab_worker/
    __init__.py  main.py  config.py
    api/{__init__,errors,health,prompts}.py
    contract/{__init__,common,prompts,schema}.py
    contract/snapshots/v1/{common-request,error-envelope,prompt-manifest,prompt-document}.schema.json
    contract/README.md
    observability/{__init__,logging,metrics}.py
    prompts/{__init__,store,build}.py
    eval/{__init__,canonical,cli}.py
  tests/
    conftest.py
    unit/{test_config,test_observability,test_prompt_store,test_canonical,test_forbidden_dependencies}.py
    contract/{test_contract_snapshots,test_error_contract,test_prompt_contract,test_prompt_pack_manifest}.py
    integration/{test_health,test_prompts_api,test_eval_cli,test_app_smoke}.py
    fixtures/frozen_set/{rag_baseline.v1.jsonl,README.md}
```

**提交**（`dev`，按时间序；均无 Java/前端/迁移改动）：

| SHA | 主题 | 来源 |
| --- | --- | --- |
| `0a2e4908` | feat(ai-worker): P0 冻结跨语言契约与工程骨架 | 编排 Agent（Phase 0） |
| `a4feee35` | feat(ai-worker): PromptPack 资产、manifest 与查询接口 | S2（`p0/s2` @ `4bc77a97`） |
| `65ed6388` | ci(ai-worker): 增加 Python worker 门禁与镜像 | S4（`p0/s4` @ `d33dbc9e`） |
| `696e6400` | feat(ai-worker): 装配 FastAPI 服务、健康检查与可观测性 | S1（`p0/s1` @ `b823587f`） |
| `5117946f` | test(ai-worker): 覆盖配置 allowlist、指标与健康检查端点 | S1（`p0/s1` @ `0ed62aec`） |
| `7506d1ad` | feat(ai-worker): 集成 PromptPack 路由与就绪检查 | 编排 Agent（集成） |
| `cb3abbfa` | feat(ai-worker): 增加 ai-eval canonical JSON 与指纹 CLI | S3（`p0/s3` @ `707900ab`） |

仓库根另有两处配套改动：`README.md` 代码库结构补一行 worker 目录；`.gitattributes` 为
`*.py` / `*.toml` / `uv.lock` / `Dockerfile` 固定 LF。

## 3. 为什么这么做

1. **先冻结契约，再并行实现**。`contract/` 同时是 Java J2 的输入：字段、错误码、PromptPack
   manifest 形状一旦漂移，Java 侧要跟着返工。Phase 0 把模型、4 份 JSON Schema 快照、错误信封
   处理器与 31 项契约测试固定后，才分派 S1–S4；四路各自基于同一提交的 worktree，互不覆盖。
2. **错误只有一条出口**。所有端点只 `raise AppError`，由 `api/errors.py` 统一渲染
   `{code,message,retryable,details?}`；校验错误不回显输入值，未捕获异常只回 `INTERNAL`，
   日志只记路径与异常类型——避免 Prompt/密钥/用户数据从错误响应泄漏。
3. **PromptPack 的"数字"必须可复算**。`chars`、`sha256`、`variables` 全部由
   `ai-prompts write` 从文件重建，测试再从文件反向复算并与 `manifest.json` 逐字段比对；
   `variables` 必须**恰好等于**模板占位符集合（多/少/乱序都是硬错误）。这样 J2 不需要相信
   Python 的 manifest，它自己就能验证。
4. **canonical JSON 的敌人是"隐式默认值"**：键序、浮点、Unicode 转义、尾随换行任意一项依赖
   语言默认行为，Java 与 Python 就会算出两个 SHA。规则写进 README §7.2，并用 hypothesis
   200 例做"编码→解析→编码"字节稳定证明，CLI 再用真实子进程验证两次运行逐字节一致。
5. **禁入清单不能只写在文档里**。测试同时检查已安装分发、`uv.lock` 与 `pyproject`，并带
   "守卫可证伪"用例（代表性禁入包必须命中），防止守卫本身失效却仍显示绿色。
6. **CI 只加不改**。`python-worker` 是独立 job（独立工作目录、独立锁文件），不触碰
   Java/前端/镜像发布 job；命令与本地门禁逐条一致，避免"本地绿、CI 红"。

## 4. 契约口径（J2 直接对齐）

- **版本**：所有响应带 `schemaVersion=1`；只加字段、不改语义，破坏性变更必须新端点。
- **错误码**（9 个，HTTP/retryable 固定）：

| code | HTTP | retryable |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 / 422 | false |
| `DEADLINE_EXCEEDED` | 504 | true |
| `MODEL_UNAVAILABLE` | 503 | true |
| `MODEL_RATE_LIMITED` | 429 | true |
| `PROMPT_UNAVAILABLE` | 503（未知 key/version 404） | false |
| `BUDGET_EXCEEDED` | 429 | false |
| `SCHEMA_VIOLATION` | 422 | false |
| `CONTENT_BLOCKED` | 400 | false |
| `INTERNAL` | 500 | true |

- **PromptPack**：`prompts/<key>/<version>.md`；正文 = 文件去掉末尾唯一换行；`chars` = Unicode
  码点数；`sha256` = 正文 UTF-8 字节摘要；`variables` = 模板占位符集合（`{{ name }}`，
  排序去重）。`GET /v1/prompts/manifest` 只返回 `key/version/sha256/chars`（设计 §7.2），
  `variables` 在文档响应里返回；取用时重读磁盘并复算 sha256/variables。
- **canonical JSON**：单行 `{"cases":[...],"schemaVersion":1}`；cases 按 `id` 码点升序；对象键
  递归码点升序；UTF-8 原样（只转义 `"`、`\` 与 `<0x20`）；整数无前导零；小数用十进制精确值、
  无指数、至少一位小数；无尾随换行；摘要为 canonical UTF-8 字节的 SHA-256。

## 5. 验证（命令 + 实际结果）

命令均在 `ai-collab-ai-worker/` 下执行。

### 5.1 依赖与静态门禁

| 命令 | 结果 |
| --- | --- |
| `uv sync --frozen` | OK，`Checked 37 packages`，锁文件无变更 |
| `uv sync --frozen --offline` | OK，`Checked 37 packages`（离线可复现） |
| `uv run ruff check .` | `All checks passed!` |
| `uv run ruff format --check` | `39 files already formatted` |
| `uv run mypy --strict src` | `Success: no issues found in 20 source files` |
| `uv run pytest -q` | `184 passed`（unit 110 / contract 42 / integration 32），1 条既有 Starlette TestClient 弃用警告 |

### 5.2 契约与 PromptPack 自检

| 检查 | 结果 |
| --- | --- |
| `uv run python -m ai_collab_worker.contract.schema` | `contract snapshots OK (4 files)`（重生成 == 入库） |
| `uv run ai-prompts check` | `prompts OK（1 条）` |
| manifest ↔ 模板 | `knowledge.system@2026-11-11.1`：`chars=1426`、`sha256=b4dc8cd7…c214d`、`variables=["docScope","locale"]`，与磁盘文件复算一致 |
| 镜像内取用 | Docker 容器 `GET /v1/prompts/knowledge.system/2026-11-11.1` → 200，返回正文 `chars=1426`、sha256 一致 |

### 5.3 `ai-eval` 确定性与幂等

```text
fingerprint #1: {"bytes":2156,"cases":5,"sha256":"90a2856d1bb512471f7b2243289b41230457e5f62a5d1adcb62acb0c18bb6146"}
fingerprint #2: 同上（逐字节一致）
verify --sha256 90a2856d… → OK（退出码 0）
canonicalize -o → 2156 字节；再次 canonicalize 该文件 → 字节完全相同（幂等）
```

冻结集：`tests/fixtures/frozen_set/rag_baseline.v1.jsonl`（5 条中文 RAG case，UTF-8 无 BOM、LF）。

### 5.4 Docker 构建与运行

`docker build -t ai-collab-ai-worker:p0-final ai-collab-ai-worker` → 成功（镜像 ID
`sha256:e5ff6435…`）。容器以 `10001:10001` 非 root 运行，实测：

| 端点 | 结果 |
| --- | --- |
| `/healthz` | 200，`status=ok`、`schemaVersion=1` |
| `/readyz` | 200，`{"status":"ready","checks":{"prompts":"ok"}}` |
| `/metrics` | 200，`text/plain`，含 `ai_worker_info` |
| `/v1/prompts/manifest` | 200，条目四字段 |
| `/v1/prompts/knowledge.system/2026-11-11.1` | 200，sha256 与 manifest 一致 |

镜像与容器在验证后已删除，仓库内无 Docker 产物。

### 5.5 零 DB / 零密钥 / 零外呼

- `tests/unit/test_forbidden_dependencies.py`：已安装分发、`uv.lock`、`pyproject` 三处均无
  LangChain/LlamaIndex/CrewAI/AutoGen/PydanticAI、DB/对象存储客户端、任务队列、模型 SDK、
  torch/transformers。
- `tests/unit/test_config.py`：`JWT_SECRET`、`JWT_SECRET_OLD`、`POSTGRES_DSN`、`OPENAI_API_KEY`、
  `ANTHROPIC_API_KEY`、未知 `AI_WORKER_*` 均拒绝启动，错误消息不含变量值。
- `src/` 无 `httpx/requests/urllib/aiohttp` 导入；`httpx` 仅 dev 依赖（TestClient），运行镜像
  `uv sync --no-dev` 不装。

### 5.6 CI 配置

`.github/workflows/ci.yml` 仅追加 `python-worker`（+28/−0 行），现有 6 个 job 零改动。
YAML 解析通过，job 顺序：`production-contract, backend-shard, backend, frontend, image, release,
python-worker`；步骤与本报告 §5.1 命令逐条一致（`astral-sh/setup-uv@v6`、uv `0.12.2`、
`uv sync --frozen` → lint → format → mypy → pytest）。

### 5.7 清理

- 临时分支 `p0/s1`、`p0/s2`、`p0/s3`、`p0/s4` 均已删除；4 个 worktree 已移除；
- `git worktree list` 只剩主工作树；`git branch` 只剩 `dev`、`main`；
- 未生成任何需入库的日志/构建产物（`*.log` 仅历史验收证据，未被本批新增）。

## 6. 行为变化

| 面 | 变化 |
| --- | --- |
| 仓库结构 | 新增独立子目录 `ai-collab-ai-worker/`（Python 3.13 + uv），根 README 结构图补一行 |
| 运行时 | 新进程角色可监听 `9100`，暴露 health/ready/metrics/prompts 五个端点；缺省不参与现有 compose |
| Java / 前端 | 零改动（无源码、依赖、API、页面变化） |
| 数据库 | 零迁移（未新增、未修改；最新仍 V89） |
| CI | 新增独立 `python-worker` job；现有 job 的命令、缓存、触发条件不变 |
| 启动安全 | Python 进程环境一旦出现密钥/DB 凭据会**拒绝启动**（新语义，仅作用于 worker） |
| 失败语义 | PromptPack 缺失/损坏时进程仍存活，`/readyz` 503、prompts 接口 503/404，不返回陈旧或降级正文 |

## 7. 遗留与风险

1. **CI 首次验证待推送**：`python-worker` job 只在 push 后跑过才算 LIVE；本报告先记录本地等价
   命令全绿，推送后补 run 证据（docs-only 提交不触发 CI，不会产生第二套代码验证）。
2. **Docker 构建未进 CI**：按本批约束只新增测试 job；镜像构建目前是本地/发布阶段动作，建议
   后续在 release 流程里加 `docker build` 冒烟。
3. **Prompt 双份来源窗口期**：J2 删除 Java `KnowledgePromptProvider` 之前，同一份问答 Prompt
   同时存在于 Java 常量与 PromptPack。若 J2 之前有人改 Java 常量会静默漂移；J2 应同波删除该
   常量，或加"manifest sha256 对齐"守卫。
4. **内部端点无鉴权**：P0 零密钥、零 JWT，端点安全依赖网络隔离（设计 §3/§7.8 的 `ai` 内部网）。
   在 P1 短期服务令牌落地前，**不得把 9100 发布到宿主或公网**。
5. **`/readyz` 反映最近一次刷新**：`PromptStore` 构造/`refresh()` 时校验全部模板；运行期磁盘
   被替换时由 `GET /v1/prompts/*` 的重读校验暴露（503），就绪探针不会逐请求重读。
6. **空 PromptPack 视为就绪**：契约允许 `prompts: []`；仓库资产非空由契约测试保证。若未来要求
   "空包 = 未就绪"，需要新增规则。
7. **chars 与版本排序**：`chars` 是 Unicode 码点（Java 必须 `codePointCount`，不能 `length()`）；
   manifest 按字符串序排序，`2026-11-11.10` 会排在 `2026-11-11.2` 前，跨语言不得改用日期语义。
8. **`ai-eval` 极端输入**：极大十进制指数（如 `1e999999999`）会在展开时占用大量内存；本批冻结集
   是可信输入，未设上限。极深嵌套返回 `RecursionError` 时 CLI 仍 exit 2 且无 traceback，但前缀
   为 `error:` 而非 `invalid dataset:`（退出码语义不受影响）。
9. **env allowlist 范围**：按设计 §10.1 只拦 `JWT_SECRET*`、`POSTGRES_*`、`*_API_KEY`；`JWT_ALGORITHM`
   之类的非密钥 JWT 变量不在拦截范围（README 已与实现对齐）。

## 8. 证据等级

| 结论 | 等级 | 依据 |
| --- | --- | --- |
| 骨架/契约/PromptPack/eval/测试/CI 配置已实现 | IMPLEMENTED | 代码与文档入库 |
| 本地全部门禁通过 | TESTED | §5.1–§5.3 命令输出 |
| 镜像可构建、端点可运行 | TESTED | §5.4 真实 Docker 容器 |
| 现有 Java/前端/迁移未受影响 | TESTED（局部） | `git diff --stat` 仅 worker 子目录 + 根 README/.gitattributes/CI；Java/前端测试未重跑（无改动） |
| CI `python-worker` job 绿 | NOT_VERIFIED（待推送） | 本地命令与 job 完全一致 |
| 生产可用 / 已上线 | NOT_CLAIMED | 本批只交付 P0 骨架，未接入部署编排 |

## 9. 编排与复核记录

| 路 | worktree / 分支 | 子 Agent 提交 | 复核门禁 | 合并方式 |
| --- | --- | --- | --- | --- |
| Phase 0 | 主工作树 | `0a2e4908` | 31 项契约测试、ruff/mypy | 直接提交 |
| S1 | `ai-collab-worktrees/s1` / `p0/s1` | `b823587f`、`0ed62aec` | 71 passed、ruff/format/mypy 全绿；复核 `config.py`/`main.py`/`health.py`/`metrics.py` | cherry-pick → `696e6400`、`5117946f` |
| S2 | `s2` / `p0/s2` | `4bc77a97` | 77 passed、ruff/format/mypy 全绿；复核 `store.py`/`build.py`/`api/prompts.py`/资产 | cherry-pick → `a4feee35` |
| S3 | `s3` / `p0/s3` | `707900ab` | 88 passed、ruff/format/mypy 全绿；复核 `canonical.py`/`cli.py` | cherry-pick → `cb3abbfa` |
| S4 | `s4` / `p0/s4` | `d33dbc9e` | 36 passed、ruff/format/mypy 全绿；Docker 构建成功；复核 CI diff（28/0）与 Dockerfile | cherry-pick → `65ed6388` |
| 集成 | 主工作树 | `7506d1ad` | 184 passed、全套门禁、Docker 运行冒烟 | 直接提交 |

每个子交付都由编排 Agent 重跑门禁、逐文件复核后才进入 `dev`；四路 worktree 与临时分支在合入后
立即删除，仓库最终只保留 `dev` 与 `main`。

## 10. 复跑命令

```bash
cd ai-collab-ai-worker
uv sync --frozen && uv sync --frozen --offline
uv run ruff check .
uv run ruff format --check
uv run mypy --strict src
uv run pytest
uv run python -m ai_collab_worker.contract.schema
uv run ai-prompts check
uv run ai-eval fingerprint tests/fixtures/frozen_set/rag_baseline.v1.jsonl --json
```

Docker（构建上下文必须是 `ai-collab-ai-worker/`）：

```bash
docker build -t ai-collab-ai-worker:dev ai-collab-ai-worker
docker run --rm -p 9100:9100 ai-collab-ai-worker:dev
```
