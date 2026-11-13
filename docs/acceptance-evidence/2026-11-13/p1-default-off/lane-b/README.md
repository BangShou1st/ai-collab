# Lane B：Python MCP Host（P1 default-off）

- 分支：`p1/mcp-host`
- worktree：`E:\project\ai-collab-worktrees\p1-mcp-host`
- 基线：`540a029d`
- 范围：仅 `ai-collab-ai-worker` 与本证据目录；无 Java/前端改动，无生产路由，无推送/合并。

## 交付

1. `src/ai_collab_worker/clients/mcp_host.py`
   - 固定调用 `POST /internal/ai/v1/mcp`，实现 `initialize`、`ping`、`tools/list`、`tools/call`。
   - 每调用注入 Bearer；不签发/缓存/落盘；无重试；不跟随重定向；不读取代理环境。
   - Java JSON-RPC `error.data.kind` 保留为 `McpErrorKind`，固定消息不拼接远端正文。
2. `src/ai_collab_worker/contract/mcp.py`
   - JSON-RPC 请求、结果与错误模型；12 个冻结错误 kind。
   - Unicode NFC、canonical JSON、逐工具 `toolSchemaHash`、`catalogVersion`。
   - 显示文本控制字符/空白规范化、有限注入片段中和、Schema 深度/节点/字节/外部引用限制。
   - 工具目录按 `name` 排序；`tools/call` 参数不补默认值；结果保留
     `outcome/untrusted/truncated/data/latencyMs`。
3. `contract/schema.py` + 10 个 MCP JSON Schema 快照；契约快照总数 24。

## 冻结 fixture

`tests/fixtures/mcp/catalog-v1.json`：

- `exposedName=mcp.local_readonly.echo_text`
- `toolSchemaHash=sha256:6e0e51dc1473a487caf37f66d09b14a1027a037c9fb357b032d1c7513dc82054`
- `catalogVersion=sha256:e38d33918b09368d0553ad8d6c936bdd3fff4d1bdea5e748b546ae85f10d1441`

测试对 canonical 字节、描述、Schema、Hash 与目录版本逐字节断言；Hash 由测试内独立
`hashlib.sha256` 对硬编码 canonical 字节复核。

## 校验结果（2026-11-13）

| 命令 | 结果 |
| --- | --- |
| `uv run pytest -q tests/contract/test_mcp_contract.py tests/unit/test_mcp_host.py tests/unit/test_mcp_boundary.py tests/contract/test_contract_snapshots.py` | `81 passed` |
| `uv run ruff check .` | `All checks passed!` |
| `uv run ruff format --check .` | `172 files already formatted` |
| `uv run mypy --strict src` | `Success: no issues found in 65 source files` |
| `uv run python -m ai_collab_worker.contract.schema` | `contract snapshots OK (24 files)` |
| `uv run ai-prompts check` | exit 0（26 个文档） |
| `uv run ai-prompts export-java --check` | exit 0（27 个文件） |
| `uv run pytest -q` | `1256 passed, 1 warning in 221.58s` |

全量 pytest 在测试子进程内剔除宿主注入的 `*_API_KEY` 风格变量名后执行；worker 的既有
启动 fail-closed 检测仍保持不变。未读取、记录或提交任何变量值。

## 残余风险

- 本 lane 未运行 Java Gateway ↔ Python Host 真实联调或 E2E；契约一致性由 fixture 与 mock
  transport 证明，跨进程联调归 Lane A/C。
- 注入片段替换是有限规则匹配，不构成安全证明；结果与 Schema 仍必须按不可信数据处理。
- Host 只做形状/大小初筛，不替代 Gateway 的 JSON Schema 与授权校验；不自动确认新 Schema。
- 客户端未实现整体调用 deadline 或响应字节流上限；当前由调用方超时与 Gateway 清洗/截断兜底。
