# P2-A Lane B：Python MCP Host 真实进程证据

> 日期：2026-11-13（Asia/Shanghai）
> 分支：`p2a/python-host`
> 提交：`d350d046`（真实 Host 进程）、`4cc80633`（`warnings` 修复）
> 结论：**Lane B PASS（真实 Uvicorn 进程；Java Gateway 三进程 E2E 由 Lane C/中心执行）**

## 1. 交付内容

| 文件 | 用途 |
| --- | --- |
| `ai-collab-ai-worker/src/ai_collab_worker/mcp_host_app.py` | 独立 Uvicorn/FastAPI MCP Host 进程；默认主 app 不装配 |
| `ai-collab-ai-worker/tests/integration/test_mcp_host_app.py` | 契约、身份伪造拒绝、真实进程 list/call、起停与日志泄露测试 |
| `ai-collab-ai-worker/README.md` | P2-A 测试进程启动命令与端点契约 |

安全边界：

- Gateway base URL 只从进程启动配置 `MCP_HOST_GATEWAY_URL` 读取，请求体多传
  `gatewayUrl` 会被 422 拒绝；
- Bearer 只从调用方 `Authorization` 逐请求透传，进程不读取/缓存/落盘 token；
- 不直连外部 MCP、不访问业务库/Redis/MinIO，不重试、不判定授权、不补默认参数；
- 工具参数或请求体含 `projectRef/userRef/projectId/userId` 等身份字段时，Host 在 422
  阶段拒绝，未触达 Java Gateway；
- Java 错误 `kind/retryable` 原样保留；不返回远端 `error.message`、参数或 token。
- `tools/call` 的 `warnings` 原样保留；旧 Java 结果缺少该字段时按空数组兼容，避免注入告警
  被 Pydantic 的 `extra="forbid"` 误判为 `INTERNAL`。

## 2. 启动与端点

```bash
cd ai-collab-ai-worker
MCP_HOST_GATEWAY_URL=http://127.0.0.1:18080 \
  uv run uvicorn ai_collab_worker.mcp_host_app:app \
  --host 127.0.0.1 --port 19100
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/healthz` | 进程存活 |
| GET | `/readyz` | Host client 已初始化 |
| POST | `/v1/mcp/initialize` | `{requestId}` |
| POST | `/v1/mcp/ping` | `{requestId}` |
| POST | `/v1/mcp/list` | `{requestId}`；同时保留 `/v1/mcp/tools/list` |
| POST | `/v1/mcp/call` | `{requestId,params}`；同时保留 `/v1/mcp/tools/call` |

成功返回：

```json
{
  "schemaVersion": 1,
  "requestId": "host-list-1",
  "operation": "tools/list",
  "outcome": "SUCCEEDED",
  "result": {}
}
```

Host 自己缺少 Bearer 返回 401 + `UNAUTHORIZED`；请求体非法返回 422；Java 调用失败返回
502 + 冻结契约中的 `kind/retryable`。`tools/call` 成功结果内的
`outcome/untrusted/truncated/data/latencyMs` 不做布尔化或空成功替换。

## 3. 测试结果

| 门禁 | 命令 | 结果 | 原始输出 |
| --- | --- | --- | --- |
| Host 定向 | `uv run pytest tests/unit/test_mcp_host.py tests/contract/test_mcp_contract.py tests/contract/test_contract_snapshots.py tests/integration/test_mcp_host_app.py -q` | **88 passed**, 1 warning, 4.09s | [`raw/pytest-host-targeted.txt`](raw/pytest-host-targeted.txt) |
| Python 全量 | `uv run pytest -q`（清理宿主注入的 `*_API_KEY`/`JWT_SECRET`/`POSTGRES_*`/`AI_WORKER_*`） | **1270 passed**, 1 warning, 264.45s | [`raw/pytest-full.txt`](raw/pytest-full.txt) |
| Ruff | `uv run ruff check .` | PASS | [`raw/ruff-check.txt`](raw/ruff-check.txt) |
| Ruff format | `uv run ruff format --check .` | PASS（177 files） | [`raw/ruff-format.txt`](raw/ruff-format.txt) |
| Mypy | `uv run mypy --strict src` | PASS（66 source files） | [`raw/mypy.txt`](raw/mypy.txt) |

警告为 FastAPI/Starlette `TestClient` 的依赖弃用提示，不是失败。全量测试的宿主环境清理
与 P1 报告口径一致：只移除测试进程继承的密钥类变量，不修改产品代码或测试断言。

## 4. 真实进程测试覆盖

`test_real_uvicorn_process_serves_list_and_call_then_stops`：

- 在 pytest 内启动真实 `python -m uvicorn ai_collab_worker.mcp_host_app:app` 子进程；
- 使用临时回环端口，等待 `/healthz` 200；
- 经真实 HTTP 调用 `/v1/mcp/list` 与 `/v1/mcp/call`，验证冻结 fixture 目录、
  `SUCCEEDED/untrusted/warnings` 与 Bearer 原样转发；
- 测试进程环境显式移除 `AI_WORKER_*` 与 `*_API_KEY`，证明 Host 不依赖服务令牌或 Provider 密钥；
- 终止进程并断言端口回收；断言 stdout/stderr 不含 Bearer token 或调用参数。

另外覆盖：

- 缺少 Host Bearer：401，零 Gateway 调用；
- `projectRef`/`projectId` 伪造：422，零 Gateway 调用；
- Java 返回 `TOOL_REVOKED`：502 且只保留 `kind/retryable`，远端 secret 不回显；
- Java/清洗器返回 `warnings`：Host 200 原样保留；缺失时模型默认空 tuple；
- 启动配置为 `ftp://`：进程启动失败，不静默回退；
- 请求体 `gatewayUrl` 改道：422，零 Gateway 调用。

## 5. 未覆盖 / 交接

| 项 | 状态 | 交接 |
| --- | --- | --- |
| 真实 Java Gateway + Python Host + 本地 MCP Server 三进程 E2E | `NOT_RUN`（Lane B 不启动 Java） | Lane C/中心按本页启动命令编排 |
| M-01/M-02/M-05/M-08/M-09/M-10/M-12 | Lane B 范围外 | Lane A/C 证据 |
| 生产主路径装配、TLS/ingress、canary | Deferred | 明确不在 P2-A 范围 |

生产 W6 仍为 `NOT_READY`；Lane B 只证明 Python Host 可作为真实进程运行并保持冻结契约边界。
