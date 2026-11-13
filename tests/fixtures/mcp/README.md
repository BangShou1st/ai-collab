# P1 MCP 测试语料

| 文件 | 用途 |
| --- | --- |
| `catalog-v1.json` | Java Gateway ↔ Python Host 的冻结目录 fixture；不得修改，Hash 以 `docs/api/mcp-gateway-internal-v1.md` 为准 |
| `local_readonly_server.py` | 本地只读测试 MCP Server；仅合成数据、无第三方凭据、无业务库、无写副作用 |

## 本地只读测试 Server

```bash
python tests/fixtures/mcp/local_readonly_server.py --port 0
```

启动后向 stdout 输出一行 JSON（`event=ready`、`url`、`port`），测试结束由父进程终止。
传入 `--tls-cert` 与 `--tls-key` 时以 HTTPS 启动；证书只用于 local/test，私钥不得提交。

工具：

- `echo_text` / `slow_echo` / `error_echo`：`readOnlyHint=true`；
- `write_probe`：`readOnlyHint=false`，只用于验证 Gateway 白名单拒绝，永远不应被暴露或执行。
- `rate_limited` / `server_error` / `partial_echo`：分别返回 HTTP 429 / 500 / 206，仅用于失败分类；
- `oversized_echo` / `unsafe_payload` / `injected_description`：合成超大结果、二进制/路径/凭据、
  注入描述，仅用于清洗与截断断言。

`echo_text` 返回同时包含合成 token 字段与提示注入文本，供结果清洗断言使用；
其中所有 token 均为固定合成值，不具备任何真实凭据能力。

测试控制面（仅本地夹具）：

```text
POST /__control/schema?mode=original|changed
```

`changed` 只改变 `echo_text` 描述，用于真实管理员 discover 流程验证 schema 漂移停用与审计。
该端点不是 MCP 协议的一部分，也不存在于生产。

## E2E

```bash
cd ai-collab-ai-worker
uv run pytest tests/e2e -q
uv run python tests/e2e/run_readonly_e2e.py --mode stub
uv run python tests/e2e/run_p2a_real_process.py --mode offline
```

真实 Gateway 入口（Lane A 合并后由中心执行；需要已配置的测试项目与内部令牌）：

```bash
cd ai-collab-ai-worker
uv run python tests/e2e/run_readonly_e2e.py \
  --mode real \
  --gateway-url http://127.0.0.1:8080/internal/ai/v1/mcp \
  --gateway-token "$MCP_GATEWAY_TOKEN"
```

也可以传 `--gateway-command "<启动命令>"`，harness 会等待 `ping` 就绪并在退出时回收进程。
当前 stub 运行的证据等级必须标注为 `TESTED(STUB)`，不得表述为已通过真实 Java Gateway。

P2-A 真实三进程编排见 `ai-collab-ai-worker/tests/e2e/run_p2a_real_process.py` 与
`docs/acceptance-evidence/2026-11-13/p2a-real-process/lane-c/runbook.md`。真实模式必须显式提供
Spring Boot jar、测试 `.env` 与独立 PostgreSQL 容器；启动失败或缺少证据时只能写 `NOT_RUN`/`FAIL`。
