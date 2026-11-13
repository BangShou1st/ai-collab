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

工具：

- `echo_text` / `slow_echo` / `error_echo`：`readOnlyHint=true`；
- `write_probe`：`readOnlyHint=false`，只用于验证 Gateway 白名单拒绝，永远不应被暴露或执行。

`echo_text` 返回同时包含合成 token 字段与提示注入文本，供结果清洗断言使用；
其中所有 token 均为固定合成值，不具备任何真实凭据能力。

## E2E

```bash
cd ai-collab-ai-worker
uv run pytest tests/e2e -q
uv run python tests/e2e/run_readonly_e2e.py --mode stub
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
